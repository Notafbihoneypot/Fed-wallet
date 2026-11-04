use super::*;
use crate::tests::NoNetwork;
use tokio_util::sync::CancellationToken;
use wallet_keys::{Mode, Network as KeyNetwork, OfflineWallet};
use zeroize::Zeroizing;

fn fixture() -> OfflineWallet {
    let hot = OfflineWallet::restore_spend_key(
        KeyNetwork::Mainnet,
        Zeroizing::new(format!("01{}", "00".repeat(31))),
        0,
    )
    .unwrap();
    let view = hot.with_secret_keys(|v, _| hex::encode(v.to_bytes()));
    OfflineWallet::restore_keystone(
        KeyNetwork::Mainnet,
        &hot.details().address,
        Zeroizing::new(view),
        0,
    )
    .unwrap()
}
#[tokio::test]
async fn mainnet_hardware_viewing_cannot_prepare_confirm_or_retry() {
    let d = tempfile::tempdir().unwrap();
    let w = fixture();
    assert_eq!(w.details().mode, Mode::Keystone);
    let mut e = WalletEngine::<NoNetwork>::new(&w, d.path(), CancellationToken::new()).unwrap();
    assert!(e.status().unwrap().balance_atomic.is_none());
    assert!(e.status().unwrap().unlocked_atomic.is_none());
    assert!(matches!(
        e.prepare(&w.details().address, "1").await,
        Err(EngineError::ViewOnly)
    ));
    assert!(matches!(
        e.confirm("untrusted-review").await,
        Err(EngineError::ViewOnly)
    ));
    assert!(matches!(
        e.retry_pending("untrusted-id").await,
        Err(EngineError::ViewOnly)
    ));
    for index in [0, 1, 20] {
        let s = e.receive_address(index).unwrap();
        assert!(monero_wallet::address::MoneroAddress::from_str(
            monero_wallet::address::Network::Mainnet,
            &s
        )
        .is_ok());
        assert!(monero_wallet::address::MoneroAddress::from_str(
            monero_wallet::address::Network::Stagenet,
            &s
        )
        .is_err());
    }
}
