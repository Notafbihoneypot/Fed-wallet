//! Loopback test transport: excluded from every Android build.
use super::*;
use std::{
    io::{Read, Write},
    net::{Ipv4Addr, SocketAddrV4, TcpStream},
    sync::{
        atomic::{AtomicBool, AtomicUsize, Ordering},
        Arc, Mutex,
    },
};
#[derive(Clone, Default)]
struct Loopback {
    lose_reply: Arc<AtomicBool>,
    broadcasts: Arc<Mutex<Vec<Vec<u8>>>>,
    requests: Arc<AtomicUsize>,
    delay: Duration,
    scan_limit: Option<u64>,
    size_reductions: Arc<AtomicUsize>,
    block_error_after: Option<u64>,
    read_failures: Arc<AtomicUsize>,
    distributions: Arc<Mutex<Vec<u64>>>,
    fail_decoy_lookup: Arc<AtomicBool>,
    block_reads: Arc<AtomicUsize>,
}
impl HttpTransport for Loopback {
    async fn post(
        &self,
        route: &str,
        body: Vec<u8>,
        limit: Option<usize>,
    ) -> Result<Vec<u8>, InterfaceError> {
        self.requests.fetch_add(1, Ordering::SeqCst);
        if route == "get_outs.bin" && self.fail_decoy_lookup.load(Ordering::SeqCst) {
            return Err(InterfaceError::InterfaceError(
                crate::transport::NodeFailure::Timeout.to_string(),
            ));
        }
        if route == "get_output_distribution.bin" {
            let field = b"from_height";
            let at = body.windows(field.len()).position(|v| v == field).unwrap() + field.len() + 1;
            self.distributions
                .lock()
                .unwrap()
                .push(u64::from_le_bytes(body[at..at + 8].try_into().unwrap()));
        }
        if !self.delay.is_zero() {
            tokio::time::sleep(self.delay).await;
        }
        if route == "get_blocks.bin" {
            self.block_reads.fetch_add(1, Ordering::SeqCst);
            if let Some(after) = self.block_error_after {
                let field = b"start_height";
                let at = body
                    .windows(field.len())
                    .position(|value| value == field)
                    .unwrap()
                    + field.len()
                    + 1;
                let start = u64::from_le_bytes(body[at..at + 8].try_into().unwrap());
                if start >= after {
                    self.read_failures.fetch_add(1, Ordering::SeqCst);
                    return Err(InterfaceError::InterfaceError(
                        "synthetic read failure".into(),
                    ));
                }
            }
            if let Some(limit) = self.scan_limit {
                let field = b"max_block_count";
                let at = body
                    .windows(field.len())
                    .position(|value| value == field)
                    .unwrap()
                    + field.len()
                    + 1;
                let count = u64::from_le_bytes(body[at..at + 8].try_into().unwrap());
                if count > limit {
                    self.size_reductions.fetch_add(1, Ordering::SeqCst);
                    return Err(InterfaceError::InterfaceError(
                        crate::transport::NodeFailure::Size.to_string(),
                    ));
                }
            }
        }
        let fail = || InterfaceError::InterfaceError("loopback fixture failed".into());
        let mut stream = TcpStream::connect_timeout(
            &SocketAddrV4::new(Ipv4Addr::LOCALHOST, 48081).into(),
            Duration::from_secs(10),
        )
        .map_err(|_| fail())?;
        stream
            .set_read_timeout(Some(Duration::from_secs(30)))
            .map_err(|_| fail())?;
        write!(stream,"POST /{route} HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\nConnection: close\r\nContent-Length: {}\r\n\r\n",body.len()).map_err(|_|fail())?;
        stream.write_all(&body).map_err(|_| fail())?;
        let mut b = vec![];
        stream
            .take(32 * 1024 * 1024 + 8192)
            .read_to_end(&mut b)
            .map_err(|_| fail())?;
        let pos = b
            .windows(4)
            .position(|b| b == b"\r\n\r\n")
            .ok_or_else(fail)?;
        if !b.starts_with(b"HTTP/1.1 200") {
            return Err(fail());
        }
        let b = b[pos + 4..].to_vec();
        if b.len() > limit.unwrap_or(32 * 1024 * 1024) {
            return Err(fail());
        }
        if route == "send_raw_transaction" {
            self.broadcasts.lock().unwrap().push(body);
            if self.lose_reply.load(Ordering::SeqCst) {
                return Err(fail());
            }
        }
        Ok(b)
    }
}
fn fixture(n: u8) -> OfflineWallet {
    OfflineWallet::restore_spend_key(
        KeyNetwork::Mainnet,
        Zeroizing::new(format!("{n:02x}{}", "00".repeat(31))),
        0,
    )
    .unwrap()
}
async fn sync<T: HttpTransport + Send + 'static>(e: &mut WalletEngine<T>) -> SyncStatus {
    loop {
        let s = e.sync_batch().await.unwrap();
        if s.synced {
            return s;
        }
    }
}
#[tokio::test]
#[ignore = "Requires fresh isolated monerod --regtest on 127.0.0.1:48081; use scripts/test-regtest.py"]
async fn real_node_scan_sign_lost_reply_reopen_retry_mine_and_reorg() {
    let t = Loopback::default();
    let rpc = MoneroDaemon::new(t.clone()).await.unwrap();
    assert_eq!(rpc.latest_block_number().await.unwrap(), 0);
    let w = fixture(1);
    let r = fixture(3);
    let dir = tempfile::tempdir().unwrap();
    let rd = tempfile::tempdir().unwrap();
    let vd = tempfile::tempdir().unwrap();
    let mut e = WalletEngine::<Loopback>::new(&w, dir.path(), CancellationToken::new()).unwrap();
    // Fakechain has mainnet genesis. Exercise the production software gate.
    e.attach_transport(t.clone()).await.unwrap();
    let miner = e.pair.legacy_address(Network::Mainnet);
    // Exercise the production mainnet identity check with a hardware account.
    // Fakechain uses mainnet genesis; no bypass is needed for this companion.
    let hd = tempfile::tempdir().unwrap();
    let hardware_view = w.with_secret_keys(|v, _| hex::encode(v.to_bytes()));
    let hardware = OfflineWallet::restore_keystone(
        KeyNetwork::Mainnet,
        &miner.to_string(),
        Zeroizing::new(hardware_view),
        0,
    )
    .unwrap();
    let mut he =
        WalletEngine::<Loopback>::new(&hardware, hd.path(), CancellationToken::new()).unwrap();
    he.attach_transport(t.clone()).await.unwrap();
    // Exercise the actual Android production direct connector and genesis gate,
    // independently of the fault-injecting loopback test adapter.
    let direct_dir = tempfile::tempdir().unwrap();
    let mut direct =
        WalletEngine::<NodeTransport>::new(&hardware, direct_dir.path(), CancellationToken::new())
            .unwrap();
    direct
        .connect(
            "http://127.0.0.1:48081/",
            wallet_policy::connection::NetworkRoute::Direct,
        )
        .await
        .unwrap();
    rpc.generate_blocks(&miner, 1000).await.unwrap();
    // Compare request overhead at a fixed simulated network latency. Both
    // passes use the same production validation/scanner and synthetic chain.
    let mut measurements = vec![];
    let empty = fixture(9);
    for mode in 0..3 {
        let benchmark_dir = tempfile::tempdir().unwrap();
        let transport = Loopback {
            delay: Duration::from_millis(25),
            ..Loopback::default()
        };
        let mut benchmark =
            WalletEngine::<Loopback>::new(&empty, benchmark_dir.path(), CancellationToken::new())
                .unwrap();
        benchmark.attach_transport(transport.clone()).await.unwrap();
        benchmark.rescan(1).unwrap();
        let requests = transport.requests.load(Ordering::SeqCst);
        let saves = benchmark.store.saves.load(Ordering::SeqCst);
        let started = Instant::now();
        loop {
            let max_blocks = match mode {
                0 => 32,
                1 => benchmark.batch_size,
                _ => 2_000,
            };
            if benchmark.sync_window(max_blocks).await.unwrap().synced {
                break;
            }
        }
        assert_eq!(benchmark.status().unwrap().received_atomic, 0);
        measurements.push((
            transport.requests.load(Ordering::SeqCst) - requests,
            benchmark.store.saves.load(Ordering::SeqCst) - saves,
            started.elapsed(),
        ));
    }
    println!("1000-block scan benchmark (RPCs, saves, time), 25ms simulated RPC latency: fixed-32 {:?}; adaptive-single {:?}; coalesced {:?}", measurements[0], measurements[1], measurements[2]);
    // Timings affect adaptive sizes; record the comparison instead of making
    // correctness depend on the host's concurrent build load.
    assert!(measurements.iter().all(|value| value.0 > 0 && value.1 > 0));
    // A later read failure must not expose or save the earlier partial window.
    let failure_dir = tempfile::tempdir().unwrap();
    let fault = Loopback {
        block_error_after: Some(129),
        ..Loopback::default()
    };
    let mut interrupted =
        WalletEngine::<Loopback>::new(&empty, failure_dir.path(), CancellationToken::new())
            .unwrap();
    interrupted.attach_transport(fault.clone()).await.unwrap();
    interrupted.rescan(1).unwrap();
    let mut reported = 1;
    while let Ok(status) = interrupted.sync_batch().await {
        reported = status.next_height;
        assert!(reported <= 129);
    }
    assert_eq!(fault.read_failures.load(Ordering::SeqCst), 1);
    assert_eq!(interrupted.status().unwrap().next_height, reported);
    assert_eq!(
        interrupted.store.load().unwrap().unwrap().next_height,
        reported
    );
    drop(interrupted);
    let mut reopened =
        WalletEngine::<Loopback>::new(&empty, failure_dir.path(), CancellationToken::new())
            .unwrap();
    assert_eq!(reopened.status().unwrap().next_height, reported);
    reopened.attach_transport(t.clone()).await.unwrap();
    assert!(sync(&mut reopened).await.synced);
    let limited_dir = tempfile::tempdir().unwrap();
    let limited_transport = Loopback {
        scan_limit: Some(64),
        ..Loopback::default()
    };
    let mut limited =
        WalletEngine::<Loopback>::new(&empty, limited_dir.path(), CancellationToken::new())
            .unwrap();
    limited
        .attach_transport(limited_transport.clone())
        .await
        .unwrap();
    limited.rescan(1).unwrap();
    assert!(sync(&mut limited).await.synced);
    assert_eq!(limited_transport.size_reductions.load(Ordering::SeqCst), 1);
    assert_eq!(limited.batch_limit, 64);
    // Owned activity ends a quiet checkpoint window after the current read.
    let first_received = e.sync_batch().await.unwrap();
    assert!(!first_received.synced);
    assert_eq!(first_received.next_height, 128);
    assert!(first_received.received_atomic > 0);
    assert_eq!(
        e.store.load().unwrap().unwrap().next_height,
        first_received.next_height
    );
    let before = sync(&mut e).await;
    let reads = t.block_reads.load(Ordering::SeqCst);
    let saves = e.store.saves.load(Ordering::SeqCst);
    assert!(e.poll_updates().await.unwrap().synced);
    assert_eq!(t.block_reads.load(Ordering::SeqCst), reads);
    assert_eq!(e.store.saves.load(Ordering::SeqCst), saves);
    // A nonzero start must fetch only the one cumulative output count before
    // the range, yet preserve absolute output indexes and real scan results.
    let recent_dir = tempfile::tempdir().unwrap();
    let mut recent =
        WalletEngine::<Loopback>::new(&w, recent_dir.path(), CancellationToken::new()).unwrap();
    recent.attach_transport(t.clone()).await.unwrap();
    recent.rescan(980).unwrap();
    let recent_status = sync(&mut recent).await;
    let expected_recent = e
        .state
        .outputs
        .values()
        .filter(|o| o.height >= 980)
        .map(|o| decode_output(o).unwrap().commitment().amount)
        .sum::<u64>();
    assert_eq!(recent_status.received_atomic, expected_recent);
    assert_eq!(recent_status.start_height, 980);
    assert!(matches!(recent.rescan(990), Err(EngineError::ScanHistory)));
    recent.rescan(0).unwrap();
    assert_eq!(
        sync(&mut recent).await.received_atomic,
        before.received_atomic
    );
    let hardware_seen = sync(&mut he).await;
    assert_eq!(hardware_seen.received_atomic, before.received_atomic);
    let direct_seen = sync(&mut direct).await;
    assert_eq!(direct_seen.received_atomic, hardware_seen.received_atomic);
    assert!(direct_seen.balance_atomic.is_none());
    drop(direct);
    let mut direct =
        WalletEngine::<NodeTransport>::new(&hardware, direct_dir.path(), CancellationToken::new())
            .unwrap();
    assert_eq!(
        direct.status().unwrap().next_height,
        direct_seen.next_height
    );
    direct
        .connect(
            "http://127.0.0.1:48081/",
            wallet_policy::connection::NetworkRoute::Direct,
        )
        .await
        .unwrap();
    assert_eq!(
        sync(&mut direct).await.received_atomic,
        hardware_seen.received_atomic
    );
    assert!(hardware_seen.balance_atomic.is_none());
    assert!(hardware_seen.unlocked_atomic.is_none());
    assert_eq!(he.receive_address(0).unwrap(), miner.to_string());
    assert!(before.unlocked_atomic.unwrap() > 1_000_000_000_000);
    let view = w.with_secret_keys(|v, _| hex::encode(v.to_bytes()));
    let vw = OfflineWallet::restore_view_only(
        KeyNetwork::Mainnet,
        &w.details().address,
        Zeroizing::new(view),
        0,
    )
    .unwrap();
    let mut ve = WalletEngine::<Loopback>::new(&vw, vd.path(), CancellationToken::new()).unwrap();
    ve.attach_transport(t.clone()).await.unwrap();
    let seen = sync(&mut ve).await;
    assert_eq!(seen.received_atomic, before.received_atomic);
    assert!(seen.balance_atomic.is_none());
    // Simulate a seed restore whose local labels/count were lost. The saved
    // receiving range starts at 1, but a real payment to index 999 must be found
    // and its subaddress output must remain spendable by the restored signer.
    let original_receiver =
        WalletEngine::<Loopback>::new(&r, rd.path(), CancellationToken::new()).unwrap();
    let dest = original_receiver
        .pair
        .subaddress(Network::Mainnet, SubaddressIndex::new(0, 999).unwrap())
        .to_string();
    drop(original_receiver);
    assert!(matches!(
        ve.prepare(&dest, "1").await,
        Err(EngineError::ViewOnly)
    ));
    assert!(matches!(
        e.prepare(&dest, "0").await,
        Err(EngineError::Payment)
    ));
    assert!(matches!(
        e.prepare_max(&dest).await,
        Err(EngineError::InputLimit)
    ));
    assert!(matches!(
        ve.prepare_max(&dest).await,
        Err(EngineError::ViewOnly)
    ));
    // A two-input construction and repeated reviews share one full public
    // distribution download. Random ring/output queries remain uncached.
    let mut amounts = e
        .state
        .outputs
        .values()
        .filter_map(|r| {
            let o = decode_output(r).unwrap();
            e.spendable(r, &o).then_some(o.commitment().amount)
        })
        .collect::<Vec<_>>();
    amounts.sort_by_key(|a| std::cmp::Reverse(*a));
    let large = amount::format_xmr(amounts[0] + amounts[1] / 2);
    let full_before = t
        .distributions
        .lock()
        .unwrap()
        .iter()
        .filter(|h| **h == 0)
        .count();
    let multiple = e.prepare(&dest, &large).await.unwrap();
    assert!(!multiple.send_max);
    assert!(e.prepared.as_ref().unwrap().payment.inputs.len() >= 2);
    e.discard_review();
    assert_eq!(
        t.distributions
            .lock()
            .unwrap()
            .iter()
            .filter(|h| **h == 0)
            .count(),
        full_before + 1
    );
    // An actual upstream decoy lookup must keep its network error and cannot
    // create a review, reserve inputs or broadcast before fee review.
    t.fail_decoy_lookup.store(true, Ordering::SeqCst);
    let broadcasts_before_decoy_failure = t.broadcasts.lock().unwrap().len();
    assert!(matches!(
        e.prepare(&dest, "1").await,
        Err(EngineError::Transport(
            crate::transport::NodeFailure::Timeout
        ))
    ));
    assert!(e.prepared.is_none() && e.state.payments.is_empty());
    assert_eq!(
        e.preparation_progress().snapshot().stage,
        PreparationStage::SelectingDecoys
    );
    assert_eq!(
        t.broadcasts.lock().unwrap().len(),
        broadcasts_before_decoy_failure
    );
    t.fail_decoy_lookup.store(false, Ordering::SeqCst);
    let stale = e.prepare(&dest, "1").await.unwrap();
    let cached_height = e.state.next_height - 1;
    let reads = t.block_reads.load(Ordering::SeqCst);
    rpc.generate_blocks(&miner, 1).await.unwrap();
    // A late timer call cannot discard a review or start scanning behind it.
    e.poll_updates().await.unwrap();
    assert_eq!(e.prepared.as_ref().unwrap().review_id, stale.review_id);
    assert_eq!(t.block_reads.load(Ordering::SeqCst), reads);
    sync(&mut e).await;
    let refreshed = e.prepare(&dest, "1").await.unwrap();
    assert_ne!(refreshed.review_id, stale.review_id);
    assert_eq!(
        t.distributions
            .lock()
            .unwrap()
            .iter()
            .filter(|h| **h == 0)
            .count(),
        full_before + 1
    );
    assert_eq!(
        t.distributions.lock().unwrap().last().copied(),
        Some(cached_height)
    );
    e.discard_review();
    let review = e.prepare(&dest, "1").await.unwrap();
    assert!(review.fee_atomic > 0 && review.fee_atomic <= MAX_FEE);
    assert!(matches!(
        e.confirm("wrong review").await,
        Err(EngineError::StaleReview)
    ));
    let expired = e.prepare(&dest, "1").await.unwrap();
    e.prepared.as_mut().unwrap().created = Instant::now() - Duration::from_secs(301);
    let broadcasts_before = t.broadcasts.lock().unwrap().len();
    assert!(matches!(
        e.confirm(&expired.review_id).await,
        Err(EngineError::StaleReview)
    ));
    assert!(e.status().unwrap().pending.is_none());
    assert_eq!(t.broadcasts.lock().unwrap().len(), broadcasts_before);
    let review = e.prepare(&dest, "1").await.unwrap();
    let approved_bytes = e.prepared.as_ref().unwrap().payment.bytes.clone();
    // New ordinary blocks no longer force a new signature/review. Confirmation
    // scans their spent inputs before committing the exact approved bytes.
    rpc.generate_blocks(&miner, 2).await.unwrap();
    t.lose_reply.store(true, Ordering::SeqCst);
    assert!(matches!(
        e.confirm(&review.review_id).await,
        Err(EngineError::BroadcastUnknown)
    ));
    assert!(e.status().unwrap().pending.is_some());
    assert!(matches!(
        e.prepare(&dest, "1").await,
        Err(EngineError::Pending)
    ));
    assert_eq!(
        e.status().unwrap().pending.as_ref().unwrap().bytes,
        approved_bytes
    );
    let balance_before_mine = e
        .state
        .outputs
        .values()
        .filter(|o| o.spent_height.is_none())
        .map(|o| decode_output(o).unwrap().commitment().amount)
        .sum::<u64>();
    let payment_block_height = e.state.next_height;
    let txid = review.transaction.clone();
    let fee = review.fee_atomic;
    drop(e);
    let mut e = WalletEngine::<Loopback>::new(&w, dir.path(), CancellationToken::new()).unwrap();
    e.attach_transport(t.clone()).await.unwrap();
    assert_eq!(
        sync(&mut e).await.pending.as_ref().unwrap().transaction,
        txid
    );
    t.lose_reply.store(false, Ordering::SeqCst);
    let _ = e.retry_pending(&txid).await;
    let broadcasts = t.broadcasts.lock().unwrap().clone();
    assert_eq!(broadcasts.len(), 2);
    assert_eq!(broadcasts[0], broadcasts[1]);
    rpc.generate_blocks(&miner, 1).await.unwrap();
    let after = sync(&mut e).await;
    assert!(after.pending.is_none());
    let reward = e
        .state
        .outputs
        .values()
        .filter(|o| o.height == payment_block_height && o.coinbase)
        .map(|o| decode_output(o).unwrap().commitment().amount)
        .sum::<u64>();
    assert_eq!(
        after.balance_atomic.unwrap(),
        balance_before_mine - 1_000_000_000_000 - fee + reward
    );
    let mut receiver =
        WalletEngine::<Loopback>::new(&r, rd.path(), CancellationToken::new()).unwrap();
    receiver.attach_transport(t.clone()).await.unwrap();
    sync(&mut receiver).await;
    assert_eq!(
        receiver.receiving_addresses().unwrap().addresses.len(),
        1000
    );
    let mut legacy_metadata = receiver.state.clone();
    legacy_metadata.created_subaddress = 1;
    legacy_metadata.selected_subaddress = 1;
    legacy_metadata.subaddress_labels.clear();
    receiver.commit(legacy_metadata).unwrap();
    drop(receiver);
    let mut receiver =
        WalletEngine::<Loopback>::new(&r, rd.path(), CancellationToken::new()).unwrap();
    assert_eq!(
        receiver.receiving_addresses().unwrap().addresses.len(),
        1000
    );
    receiver.attach_transport(t.clone()).await.unwrap();
    assert_eq!(
        receiver.select_subaddress(999).unwrap().selected_address,
        dest
    );
    assert!(matches!(
        receiver.prepare_max(&miner.to_string()).await,
        Err(EngineError::SyncRequired)
    ));
    let received = sync(&mut receiver).await;
    assert_eq!(received.balance_atomic, Some(1_000_000_000_000));
    assert_eq!(received.unlocked_atomic, Some(0));
    assert!(matches!(
        receiver.prepare_max(&miner.to_string()).await,
        Err(EngineError::Funds)
    ));
    assert_eq!(received.next_unlock_blocks, Some(9));
    rpc.generate_blocks(&miner, 8).await.unwrap();
    let almost = sync(&mut receiver).await;
    assert_eq!(almost.unlocked_atomic, Some(0));
    assert_eq!(almost.next_unlock_blocks, Some(1));
    rpc.generate_blocks(&miner, 1).await.unwrap();
    let unlocked = sync(&mut receiver).await;
    assert_eq!(unlocked.next_unlock_blocks, None);
    assert_eq!(unlocked.unlocked_atomic, Some(1_000_000_000_000));
    rpc.generate_blocks(&miner, 1).await.unwrap();
    sync(&mut e).await;
    t.post(
        "pop_blocks",
        serde_json::json!({"nblocks":11}).to_string().into_bytes(),
        None,
    )
    .await
    .unwrap();
    assert_eq!(
        sync(&mut e).await.pending.as_ref().unwrap().transaction,
        txid
    );
    assert!(matches!(
        e.prepare(&dest, "1").await,
        Err(EngineError::Pending)
    ));
    e.rescan(0).unwrap();
    assert!(e.status().unwrap().pending.is_some());
    assert!(sync(&mut e).await.pending.is_some());
    assert!(e.decoy_cache.is_none());
    // Re-mine the reserved first payment after rollback, then sweep the mature
    // receiver's single normal output with the standard two-output shape.
    let _ = e.retry_pending(&txid).await;
    rpc.generate_blocks(&miner, 10).await.unwrap();
    sync(&mut e).await;
    assert_eq!(
        sync(&mut receiver).await.unlocked_atomic,
        Some(1_000_000_000_000)
    );
    let final_wallet = fixture(7);
    let max = receiver
        .prepare_max(&final_wallet.details().address)
        .await
        .unwrap();
    assert!(max.send_max && max.amount_atomic > 0 && max.fee_atomic > 0);
    assert_eq!(
        max.amount_atomic.checked_add(max.fee_atomic),
        Some(1_000_000_000_000)
    );
    let max_tx = max.transaction.clone();
    receiver.confirm(&max.review_id).await.unwrap();
    let journal = receiver.status().unwrap();
    let payment = journal
        .payments
        .iter()
        .find(|p| p.transaction == max_tx)
        .unwrap();
    assert_eq!(payment.total_atomic, 1_000_000_000_000);
    assert_eq!(payment.confirmations, 0);
    assert_eq!(payment.mined_height, None);
    // An accepted transaction need not be chosen for the very next template.
    // Bound the isolated-node wait while still requiring real inclusion.
    let mut swept = receiver.status().unwrap();
    for _ in 0..3 {
        rpc.generate_blocks(&miner, 1).await.unwrap();
        swept = sync(&mut receiver).await;
        if swept.payments[0].mined_height.is_some() {
            break;
        }
    }
    assert_eq!(swept.balance_atomic, Some(0));
    assert_eq!(swept.unlocked_atomic, Some(0));
    assert_eq!(swept.next_unlock_blocks, None);
    assert_eq!(swept.payments[0].confirmations, 1);
    assert!(swept.payments[0].mined_height.is_some());
    let final_dir = tempfile::tempdir().unwrap();
    let mut final_receiver =
        WalletEngine::<Loopback>::new(&final_wallet, final_dir.path(), CancellationToken::new())
            .unwrap();
    final_receiver.attach_transport(t.clone()).await.unwrap();
    assert_eq!(
        sync(&mut final_receiver).await.balance_atomic,
        Some(max.amount_atomic)
    ); // A replaced anchor is different from an ordinary height advance.
    sync(&mut e).await;
    let forked = e.prepare(&dest, "0.2").await.unwrap();
    let b = t.broadcasts.lock().unwrap().len();
    t.post(
        "pop_blocks",
        serde_json::json!({"nblocks":1}).to_string().into_bytes(),
        None,
    )
    .await
    .unwrap();
    assert!(matches!(
        e.confirm(&forked.review_id).await,
        Err(EngineError::StaleReview | EngineError::SyncRequired)
    ));
    assert_eq!(t.broadcasts.lock().unwrap().len(), b);
    assert!(e.status().unwrap().pending.is_none());
    sync(&mut e).await;
    let original = e.prepare(&dest, "0.2").await.unwrap();
    let original_inputs = e.prepared.as_ref().unwrap().payment.inputs.clone();
    let competing_dir = tempfile::tempdir().unwrap();
    let mut competing =
        WalletEngine::<Loopback>::new(&w, competing_dir.path(), CancellationToken::new()).unwrap();
    competing.attach_transport(t.clone()).await.unwrap();
    sync(&mut competing).await;
    let other = competing.prepare(&dest, "0.3").await.unwrap();
    assert!(competing
        .prepared
        .as_ref()
        .unwrap()
        .payment
        .inputs
        .iter()
        .any(|id| original_inputs.contains(id)));
    competing.confirm(&other.review_id).await.unwrap();
    rpc.generate_blocks(&miner, 1).await.unwrap();
    let b = t.broadcasts.lock().unwrap().len();
    assert!(matches!(
        e.confirm(&original.review_id).await,
        Err(EngineError::StaleReview)
    ));
    assert_eq!(t.broadcasts.lock().unwrap().len(), b);
    assert!(e.status().unwrap().pending.is_none());
}
