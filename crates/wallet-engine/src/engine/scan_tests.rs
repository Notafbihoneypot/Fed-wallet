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

/// A node that answers only its tip height, which is below saved progress.
#[derive(Clone)]
struct LaggingNode;
impl HttpTransport for LaggingNode {
    async fn post(
        &self,
        route: &str,
        _: Vec<u8>,
        _: Option<usize>,
    ) -> Result<Vec<u8>, monero_wallet::interface::InterfaceError> {
        Ok(match route {
            "json_rpc" => br#"{"error":{"code":-32700}}"#.to_vec(),
            "get_height" => br#"{"height":301}"#.to_vec(),
            other => panic!("a lagging node must not be asked for {other}"),
        })
    }
}

#[tokio::test]
async fn lagging_node_never_rolls_back_saved_scan_progress() {
    let dir = tempfile::tempdir().unwrap();
    let mut engine =
        WalletEngine::<LaggingNode>::new(&wallet(), dir.path(), CancellationToken::new()).unwrap();
    engine.state.next_height = 1_000;
    engine.state.checkpoints = (872..1_000)
        .map(|height| Checkpoint {
            height,
            hash: [1; 32],
            next_output: height,
        })
        .collect();
    engine.rpc = Some(MoneroDaemon::new(LaggingNode).await.unwrap());
    assert!(matches!(
        engine.sync_batch().await,
        Err(EngineError::Height)
    ));
    assert_eq!(engine.state.next_height, 1_000);
    assert_eq!(engine.state.checkpoints.len(), 128);
}

#[test]
fn settled_payments_beyond_the_old_cap_still_save_and_reopen() {
    let dir = tempfile::tempdir().unwrap();
    let wallet = wallet();
    let mut engine =
        WalletEngine::<NoNetwork>::new(&wallet, dir.path(), CancellationToken::new()).unwrap();
    let mut next = engine.state.clone();
    next.next_height = 2_000;
    let payment = |i: u64, mined: u64| PendingPayment {
        transaction: format!("{i:064x}"),
        destination: wallet.details().address,
        amount_atomic: 1,
        fee_atomic: 1,
        mined_height: Some(mined),
        bytes: "00".into(),
        inputs: vec![format!("{i:064x}")],
    };
    next.payments = (0..1_025).map(|i| payment(i, i)).collect();
    // Still inside the rollback window: rebroadcast bytes must survive.
    next.payments.push(payment(5_000, 1_900));
    next.strip_settled_payments();
    assert_eq!(next.payments.pop().unwrap().bytes, "00");
    assert!(next.payments.iter().all(|p| p.bytes.is_empty()));
    engine.commit(next).unwrap();
    drop(engine);
    let engine =
        WalletEngine::<NoNetwork>::new(&wallet, dir.path(), CancellationToken::new()).unwrap();
    assert_eq!(engine.state.payments.len(), 1_025);
}

/// Every block read is too large for the response cap.
#[derive(Clone, Default)]
struct OversizedNode(std::sync::Arc<std::sync::atomic::AtomicUsize>);
impl HttpTransport for OversizedNode {
    async fn post(
        &self,
        route: &str,
        _: Vec<u8>,
        _: Option<usize>,
    ) -> Result<Vec<u8>, monero_wallet::interface::InterfaceError> {
        match route {
            "json_rpc" => Ok(br#"{"error":{"code":-32700}}"#.to_vec()),
            "get_height" => Ok(br#"{"height":1001}"#.to_vec()),
            _ => {
                self.0.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
                Err(size_error())
            }
        }
    }
}
fn size_error() -> monero_wallet::interface::InterfaceError {
    monero_wallet::interface::InterfaceError::InterfaceError(
        crate::transport::NodeFailure::Size.to_string(),
    )
}

#[tokio::test]
async fn oversized_prefetch_still_retries_at_the_minimum_batch() {
    let dir = tempfile::tempdir().unwrap();
    let node = OversizedNode::default();
    let mut engine =
        WalletEngine::<OversizedNode>::new(&wallet(), dir.path(), CancellationToken::new())
            .unwrap();
    engine.state.next_height = 900;
    engine.rpc = Some(MoneroDaemon::new(node.clone()).await.unwrap());
    // Scanning shrank the batch to the minimum after a 64-block prefetch started.
    engine.batch_size = batch::MIN;
    engine.prefetch = Some((
        900,
        964,
        tokio_util::task::AbortOnDropHandle::new(tokio::spawn(async { Err(size_error()) })),
    ));
    assert!(matches!(
        engine.sync_batch().await,
        Err(EngineError::Transport(crate::transport::NodeFailure::Size))
    ));
    // The 64-block failure is retried once at 32 blocks before giving up.
    assert_eq!(node.0.load(std::sync::atomic::Ordering::SeqCst), 1);
}
