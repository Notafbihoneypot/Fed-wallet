use super::*;
use crate::tests::NoNetwork;

fn wallet() -> OfflineWallet {
    OfflineWallet::restore_spend_key(
        KeyNetwork::Mainnet,
        Zeroizing::new(format!("01{}", "00".repeat(31))),
        0,
    )
    .unwrap()
}

#[test]
fn explicit_forward_start_is_saved_for_an_empty_wallet_and_reopens_offline() {
    let dir = tempfile::tempdir().unwrap();
    let wallet = wallet();
    let mut engine =
        WalletEngine::<NoNetwork>::new(&wallet, dir.path(), CancellationToken::new()).unwrap();
    engine.state.subaddress_count = 45;
    engine.rescan(3_700_000).unwrap();
    let status = engine.status().unwrap();
    assert_eq!(status.start_height, 3_700_000);
    assert_eq!(status.next_height, 3_700_000);
    assert!(!status.synced && !status.has_scan);
    drop(engine);
    let mut engine =
        WalletEngine::<NoNetwork>::new(&wallet, dir.path(), CancellationToken::new()).unwrap();
    assert_eq!(engine.status().unwrap().start_height, 3_700_000);
    assert_eq!(engine.state.subaddress_count, 45);
    engine.rescan(0).unwrap();
    assert_eq!(engine.status().unwrap().start_height, 0);
    assert_eq!(engine.state.subaddress_count, 45);
}

#[test]
fn forward_start_cannot_hide_known_outputs_history_or_payment_reservations() {
    let dir = tempfile::tempdir().unwrap();
    let wallet = wallet();
    let mut engine =
        WalletEngine::<NoNetwork>::new(&wallet, dir.path(), CancellationToken::new()).unwrap();
    // Synthetic records stay in memory; each guard must reject before commit.
    engine.state.outputs.insert(
        "01".repeat(32),
        OutputRecord {
            bytes: "00".into(),
            height: 0,
            coinbase: false,
            image: None,
            spent_height: None,
        },
    );
    assert!(matches!(engine.rescan(10), Err(EngineError::ScanHistory)));
    assert_eq!(engine.state.outputs.len(), 1);
    engine.state.outputs.clear();
    engine.state.history.push(HistoryEntry {
        transaction: "02".repeat(32),
        height: 0,
        received_atomic: 1,
        sent_atomic: None,
        destination: None,
    });
    assert!(matches!(engine.rescan(10), Err(EngineError::ScanHistory)));
    assert_eq!(engine.state.history.len(), 1);
    engine.state.history.clear();
    engine.state.payments.push(PendingPayment {
        transaction: "03".repeat(32),
        destination: wallet.details().address,
        amount_atomic: 1,
        fee_atomic: 1,
        mined_height: None,
        bytes: "00".into(),
        inputs: vec!["04".repeat(32)],
    });
    assert!(matches!(engine.rescan(10), Err(EngineError::ScanHistory)));
    assert_eq!(engine.state.payments.len(), 1);
    assert!(engine.state.reserved(&"04".repeat(32)));
    engine.state.payments.clear();
    engine.target_height = 100;
    assert!(matches!(engine.rescan(101), Err(EngineError::Height)));
    assert_eq!(engine.state.start_height, 0);
    assert!(engine.rescan(100).is_ok());
}
