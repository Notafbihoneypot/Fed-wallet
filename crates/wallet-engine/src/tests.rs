use crate::{
    state::{Checkpoint, OutputRecord, PendingPayment},
    store::Store,
    EngineError, State, WalletEngine,
};
use monero_daemon_rpc::HttpTransport;
use monero_wallet::interface::InterfaceError;
use std::{
    collections::BTreeMap,
    sync::{
        atomic::{AtomicUsize, Ordering},
        Arc,
    },
};
use tokio_util::sync::CancellationToken;
use wallet_keys::{Network, OfflineWallet};
use zeroize::Zeroizing;
#[derive(Clone, Default)]
pub(crate) struct NoNetwork(Arc<AtomicUsize>);
impl HttpTransport for NoNetwork {
    async fn post(&self, _: &str, _: Vec<u8>, _: Option<usize>) -> Result<Vec<u8>, InterfaceError> {
        self.0.fetch_add(1, Ordering::SeqCst);
        Err(InterfaceError::InterfaceError("test has no network".into()))
    }
}
fn wallet() -> OfflineWallet {
    OfflineWallet::restore_spend_key(
        Network::Stagenet,
        Zeroizing::new(format!("01{}", "00".repeat(31))),
        0,
    )
    .unwrap()
}
fn state() -> State {
    State::new(wallet().details().address, 0)
}
fn payment() -> PendingPayment {
    PendingPayment {
        transaction: "12".repeat(32),
        destination: wallet().details().address,
        amount_atomic: 1,
        fee_atomic: 2,
        mined_height: None,
        bytes: "00".into(),
        inputs: vec!["34".repeat(32)],
    }
}
#[test]
fn opening_cache_does_not_construct_network_client() {
    let d = tempfile::tempdir().unwrap();
    let e = WalletEngine::<NoNetwork>::new(&wallet(), d.path(), CancellationToken::new()).unwrap();
    assert!(!e.status().unwrap().synced);
    assert_eq!(e.receive_address(0).unwrap(), wallet().details().address);
    assert_ne!(e.receive_address(0).unwrap(), e.receive_address(1).unwrap());
    assert!(e.receive_address(21).is_err());
}
#[test]
fn status_reports_the_saved_rescan_start_after_reopening() {
    let d = tempfile::tempdir().unwrap();
    let w = OfflineWallet::restore_spend_key(
        Network::Stagenet,
        Zeroizing::new(format!("01{}", "00".repeat(31))),
        100,
    )
    .unwrap();
    let mut e = WalletEngine::<NoNetwork>::new(&w, d.path(), CancellationToken::new()).unwrap();
    assert_eq!(e.status().unwrap().start_height, 100);
    e.rescan(40).unwrap();
    assert_eq!(e.status().unwrap().start_height, 40);
    drop(e);
    let reopened = WalletEngine::<NoNetwork>::new(&w, d.path(), CancellationToken::new()).unwrap();
    assert_eq!(reopened.status().unwrap().start_height, 40);
    assert_eq!(reopened.status().unwrap().next_height, 40);
    assert!(!reopened.status().unwrap().synced);
}
#[test]
fn cache_lease_excludes_parallel_engines() {
    let d = tempfile::tempdir().unwrap();
    let e = WalletEngine::<NoNetwork>::new(&wallet(), d.path(), CancellationToken::new()).unwrap();
    assert!(matches!(
        WalletEngine::<NoNetwork>::new(&wallet(), d.path(), CancellationToken::new()),
        Err(EngineError::Busy)
    ));
    drop(e);
    assert!(WalletEngine::<NoNetwork>::new(&wallet(), d.path(), CancellationToken::new()).is_ok());
}
#[tokio::test]
async fn software_requires_sync_and_view_only_cannot_sign() {
    let d = tempfile::tempdir().unwrap();
    let w = wallet();
    let address = w.details().address;
    let mut e = WalletEngine::<NoNetwork>::new(&w, d.path(), CancellationToken::new()).unwrap();
    assert!(matches!(
        e.prepare(&address, "1").await,
        Err(EngineError::SyncRequired)
    ));
    drop(e);
    let view = w.with_secret_keys(|v, _| hex::encode(v.to_bytes()));
    let w = OfflineWallet::restore_view_only(Network::Stagenet, &address, Zeroizing::new(view), 0)
        .unwrap();
    let mut e = WalletEngine::<NoNetwork>::new(&w, d.path(), CancellationToken::new()).unwrap();
    assert!(e.status().unwrap().balance_atomic.is_none());
    assert!(e.status().unwrap().unlocked_atomic.is_none());
    assert!(matches!(
        e.prepare(&address, "1").await,
        Err(EngineError::ViewOnly)
    ));
}
#[test]
fn cancellation_prevents_further_operations() {
    let d = tempfile::tempdir().unwrap();
    let c = CancellationToken::new();
    let e = WalletEngine::<NoNetwork>::new(&wallet(), d.path(), c.clone()).unwrap();
    c.cancel();
    assert!(matches!(e.status(), Err(EngineError::Disconnected)));
    assert!(e.receive_address(1).is_err());
}
#[test]
fn mainnet_software_is_supported_and_testnet_stays_rejected() {
    let d = tempfile::tempdir().unwrap();
    let w = OfflineWallet::restore_spend_key(
        Network::Mainnet,
        Zeroizing::new(format!("01{}", "00".repeat(31))),
        0,
    )
    .unwrap();
    let engine = WalletEngine::<NoNetwork>::new(&w, d.path(), CancellationToken::new()).unwrap();
    assert_eq!(engine.receive_address(0).unwrap(), w.details().address);
    assert_eq!(engine.status().unwrap().balance_atomic, Some(0));
    let test = OfflineWallet::restore_spend_key(
        Network::Testnet,
        Zeroizing::new(format!("01{}", "00".repeat(31))),
        0,
    )
    .unwrap();
    assert!(matches!(
        WalletEngine::<NoNetwork>::new(&test, d.path(), CancellationToken::new()),
        Err(EngineError::Network)
    ));
}

#[tokio::test]
async fn recipient_network_validation_is_bound_to_the_opened_wallet() {
    let stagenet = wallet();
    let mainnet = stagenet.copy_to_mainnet(0).unwrap();
    for (source, other) in [(&stagenet, &mainnet), (&mainnet, &stagenet)] {
        let dir = tempfile::tempdir().unwrap();
        let mut engine =
            WalletEngine::<NoNetwork>::new(source, dir.path(), CancellationToken::new()).unwrap();
        assert!(matches!(
            engine.prepare(&other.details().address, "1").await,
            Err(EngineError::Payment)
        ));
    }
}

#[test]
fn matching_keys_on_different_networks_have_separate_cache_leases() {
    let original = wallet();
    let mainnet = original.copy_to_mainnet(27).unwrap();
    let dir = tempfile::tempdir().unwrap();
    let a =
        WalletEngine::<NoNetwork>::new(&original, dir.path(), CancellationToken::new()).unwrap();
    let b = WalletEngine::<NoNetwork>::new(&mainnet, dir.path(), CancellationToken::new()).unwrap();
    assert_ne!(a.receive_address(0).unwrap(), b.receive_address(0).unwrap());
    assert_eq!(b.status().unwrap().start_height, 27);
}
#[test]
fn encrypted_round_trip_preserves_pending_reservations() {
    let d = tempfile::tempdir().unwrap();
    let mut s = state();
    s.payments.push(payment());
    let st = Store::open(d.path(), &s.address, &[7; 32]).unwrap();
    st.save(&s).unwrap();
    let r = st.load().unwrap().unwrap();
    assert!(r.reserved(&"34".repeat(32)));
    let p = std::fs::read_dir(d.path())
        .unwrap()
        .map(|e| e.unwrap().path())
        .find(|p| p.extension().is_some_and(|e| e == "bin"))
        .unwrap();
    let b = std::fs::read(p).unwrap();
    assert!(!b
        .windows(s.address.len())
        .any(|b| b == s.address.as_bytes()));
}
#[test]
fn wrong_cache_key_and_tampering_fail_closed() {
    let d = tempfile::tempdir().unwrap();
    let s = state();
    let st = Store::open(d.path(), &s.address, &[7; 32]).unwrap();
    st.save(&s).unwrap();
    drop(st);
    let wrong = Store::open(d.path(), &s.address, &[8; 32]).unwrap();
    assert!(wrong.load().is_err());
    drop(wrong);
    let st = Store::open(d.path(), &s.address, &[7; 32]).unwrap();
    let p = std::fs::read_dir(d.path())
        .unwrap()
        .map(|e| e.unwrap().path())
        .find(|p| p.extension().is_some_and(|e| e == "bin"))
        .unwrap();
    let mut b = std::fs::read(&p).unwrap();
    b[20] ^= 1;
    std::fs::write(&p, &b).unwrap();
    assert!(st.load().is_err());
    std::fs::write(&p, &b[..15]).unwrap();
    assert!(st.load().is_err());
}
#[test]
fn reorg_rolls_back_outputs_and_spends_but_keeps_reservations() {
    let mut s = state();
    s.next_height = 15;
    s.next_output = Some(2);
    s.checkpoints = vec![
        Checkpoint {
            height: 9,
            hash: [1; 32],
            next_output: 1,
        },
        Checkpoint {
            height: 14,
            hash: [2; 32],
            next_output: 2,
        },
    ];
    s.outputs = BTreeMap::from([
        (
            "34".repeat(32),
            OutputRecord {
                bytes: "00".into(),
                height: 5,
                coinbase: false,
                image: Some("56".repeat(32)),
                spent_height: Some(13),
            },
        ),
        (
            "78".repeat(32),
            OutputRecord {
                bytes: "00".into(),
                height: 12,
                coinbase: false,
                image: None,
                spent_height: None,
            },
        ),
    ]);
    let mut p = payment();
    p.mined_height = Some(13);
    s.payments.push(p);
    s.rollback(10);
    assert_eq!(s.outputs.len(), 1);
    assert!(s.outputs.values().next().unwrap().spent_height.is_none());
    assert!(s.payments[0].mined_height.is_none());
    assert!(s.reserved(&"34".repeat(32)));
    assert_eq!(s.next_output, Some(1));
    assert_eq!(s.next_height, 10);
}
