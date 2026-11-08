//! Real upstream binary RPC parser against a bounded disposable count fixture.
use super::*;
use std::sync::{
    atomic::{AtomicUsize, Ordering},
    Arc, Mutex,
};

#[derive(Clone)]
struct CountNode {
    first: usize,
    tip: Arc<AtomicUsize>,
    generation: Arc<AtomicUsize>,
    requests: Arc<Mutex<Vec<(usize, usize)>>>,
    fail_at: Arc<AtomicUsize>,
    fail_from: Arc<AtomicUsize>,
    max_page: usize,
    corrupt_boundary: bool,
}
impl CountNode {
    fn new(first: usize, tip: usize) -> Self {
        Self {
            first,
            tip: Arc::new(AtomicUsize::new(tip)),
            generation: Default::default(),
            requests: Default::default(),
            fail_at: Default::default(),
            fail_from: Default::default(),
            max_page: 128 * 1024,
            corrupt_boundary: false,
        }
    }
    fn hash(&self, height: usize) -> [u8; 32] {
        let mut hash = [0; 32];
        hash[..8].copy_from_slice(&(height as u64).to_le_bytes());
        hash[31] = self.generation.load(Ordering::SeqCst) as u8;
        hash
    }
    fn count(&self, height: usize) -> u64 {
        ((height - self.first + 1) * (2 + self.generation.load(Ordering::SeqCst))) as u64
    }
    fn expected(&self) -> Vec<u64> {
        (self.first..=self.tip.load(Ordering::SeqCst))
            .map(|h| self.count(h))
            .collect()
    }
}
fn length(n: usize) -> Vec<u8> {
    if n < 64 {
        vec![(n << 2) as u8]
    } else if n < 16384 {
        (((n as u16) << 2) | 1).to_le_bytes().to_vec()
    } else {
        (((n as u32) << 2) | 2).to_le_bytes().to_vec()
    }
}
fn field(name: &str, kind: u8, value: &[u8]) -> Vec<u8> {
    [
        vec![name.len() as u8],
        name.as_bytes().to_vec(),
        vec![kind],
        value.to_vec(),
    ]
    .concat()
}
fn distribution_response(start: usize, counts: &[u64]) -> Vec<u8> {
    let data = counts
        .iter()
        .flat_map(|n| n.to_le_bytes())
        .collect::<Vec<_>>();
    let values = [length(data.len()), data].concat();
    let object = [
        vec![2 << 2],
        field("start_height", 5, &(start as u64).to_le_bytes()),
        field("distribution", 10, &values),
    ]
    .concat();
    [
        b"\x01\x11\x01\x01\x01\x01\x02\x01\x01".to_vec(),
        vec![2 << 2],
        field("status", 10, b"\x08OK"),
        field("distributions", 0x8c, &[vec![1 << 2], object].concat()),
    ]
    .concat()
}
impl HttpTransport for CountNode {
    async fn post(
        &self,
        route: &str,
        body: Vec<u8>,
        limit: Option<usize>,
    ) -> Result<Vec<u8>, InterfaceError> {
        if route == "json_rpc" {
            let request: serde_json::Value = serde_json::from_slice(&body).unwrap();
            let response = if request.is_array() {
                serde_json::json!({"error":{"code":-32700}})
            } else {
                assert_eq!(request["method"], "on_get_block_hash");
                let height = request["params"][0].as_u64().unwrap() as usize;
                serde_json::json!({"jsonrpc":"2.0","id":request["id"],"result":hex::encode(self.hash(height))})
            };
            return Ok(serde_json::to_vec(&response).unwrap());
        }
        assert_eq!(route, "get_output_distribution.bin");
        let number = |name: &[u8]| {
            let at = body.windows(name.len()).position(|b| b == name).unwrap() + name.len() + 1;
            u64::from_le_bytes(body[at..at + 8].try_into().unwrap()) as usize
        };
        let (from, to) = (number(b"from_height"), number(b"to_height"));
        let call = {
            let mut requests = self.requests.lock().unwrap();
            requests.push((from, to));
            requests.len()
        };
        let fail_from = self.fail_from.load(Ordering::SeqCst);
        if to - from + 1 > self.max_page
            || call == self.fail_at.load(Ordering::SeqCst)
            || (fail_from > 0 && call >= fail_from)
        {
            return Err(InterfaceError::InterfaceError(
                crate::transport::NodeFailure::Timeout.to_string(),
            ));
        }
        assert!(
            to >= self.first,
            "never request an entirely pre-RingCT page"
        );
        let start = from.max(self.first);
        let mut counts = (start..=to).map(|h| self.count(h)).collect::<Vec<_>>();
        if self.corrupt_boundary && call > 1 {
            *counts.last_mut().unwrap() += 1;
        }
        let response = distribution_response(start, &counts);
        assert!(response.len() <= limit.unwrap());
        Ok(response)
    }
}
async fn engine(directory: &Path, node: &CountNode) -> WalletEngine<CountNode> {
    let wallet = OfflineWallet::restore_spend_key(
        KeyNetwork::Stagenet,
        Zeroizing::new(format!("01{}", "00".repeat(31))),
        0,
    )
    .unwrap();
    let mut engine = WalletEngine::new(&wallet, directory, CancellationToken::new()).unwrap();
    engine.state.next_height = node.tip.load(Ordering::SeqCst) as u64 + 1;
    engine.rpc = Some(MoneroDaemon::new(node.clone()).await.unwrap());
    engine.decoy_source = Some([7; 32]);
    engine
}

#[tokio::test]
async fn paged_history_matches_full_distribution_without_requesting_pre_ringct_blocks() {
    // The old single read exceeds this simulated slow-link response budget.
    let node = CountNode::new(1_220_000, 1_620_000);
    let rpc = MoneroDaemon::new(node.clone()).await.unwrap();
    assert!(matches!(
        rpc.ringct_output_distribution(..=1_620_000).await,
        Err(InterfaceError::InterfaceError(_))
    ));
    node.requests.lock().unwrap().clear();
    let directory = tempfile::tempdir().unwrap();
    let mut engine = engine(directory.path(), &node).await;
    let counts = engine
        .distribution_for_payment(node.hash(1_620_000))
        .await
        .unwrap();
    assert_eq!(counts.as_ref(), &node.expected());
    assert!(node
        .requests
        .lock()
        .unwrap()
        .iter()
        .all(|(from, to)| to - from < 128 * 1024));
    assert_eq!(
        engine.preparation_progress().snapshot().cached_bytes as usize,
        counts.len() * 8
    );
    let calls = node.requests.lock().unwrap().len();
    engine
        .distribution_for_payment(node.hash(1_620_000))
        .await
        .unwrap();
    assert_eq!(node.requests.lock().unwrap().len(), calls);
}

#[tokio::test]
async fn interrupted_history_survives_reopen_and_extends_the_same_canonical_snapshot() {
    let node = CountNode::new(10_000, 410_000);
    node.fail_from.store(2, Ordering::SeqCst);
    // Fail all sizes, preserving the first durable page before returning error.
    let directory = tempfile::tempdir().unwrap();
    let mut first = engine(directory.path(), &node).await;
    let cancel = first.cancel.clone();
    // A cancelled owner cannot continue downloading or publish a review.
    let progress = first.preparation_progress();
    assert!(matches!(
        first.distribution_for_payment(node.hash(410_000)).await,
        Err(EngineError::Transport(
            crate::transport::NodeFailure::Timeout
        ))
    ));
    let saved = first.store.load_distribution().unwrap().unwrap();
    assert!(!saved.complete);
    let oldest_saved = saved.height + 1 - saved.counts.len();
    assert!(progress.snapshot().cached_bytes > 0);
    cancel.cancel();
    assert!(matches!(
        first.distribution_for_payment(node.hash(410_000)).await,
        Err(EngineError::Disconnected)
    ));
    drop(first);
    node.requests.lock().unwrap().clear();
    node.tip.store(410_003, Ordering::SeqCst);
    node.fail_at.store(0, Ordering::SeqCst);
    node.fail_from.store(0, Ordering::SeqCst);
    let mut reopened = engine(directory.path(), &node).await;
    assert_eq!(
        reopened
            .distribution_for_payment(node.hash(410_003))
            .await
            .unwrap()
            .as_ref(),
        &node.expected()
    );
    let requests = node.requests.lock().unwrap().clone();
    assert_eq!(requests[0], (410_000, 410_003));
    assert!(requests[1..].iter().all(|(_, to)| *to <= oldest_saved));
    assert!(reopened.state.payments.is_empty());
    drop(reopened);
    node.requests.lock().unwrap().clear();
    let mut complete = engine(directory.path(), &node).await;
    assert_eq!(
        complete
            .distribution_for_payment(node.hash(410_003))
            .await
            .unwrap()
            .as_ref(),
        &node.expected()
    );
    assert!(node.requests.lock().unwrap().is_empty());
}

#[tokio::test]
async fn small_read_budget_shrinks_only_public_count_pages() {
    let mut node = CountNode::new(10_000, 210_000);
    node.max_page = 32 * 1024;
    let directory = tempfile::tempdir().unwrap();
    let mut engine = engine(directory.path(), &node).await;
    assert_eq!(
        engine
            .distribution_for_payment(node.hash(210_000))
            .await
            .unwrap()
            .as_ref(),
        &node.expected()
    );
    let requests = node.requests.lock().unwrap();
    assert_eq!(requests[0].1 - requests[0].0 + 1, 128 * 1024);
    assert_eq!(requests[1].1 - requests[1].0 + 1, 64 * 1024);
    assert!(requests[2..].iter().all(|(from, to)| to - from < 32 * 1024));
}

#[tokio::test]
async fn failed_cache_write_never_blocks_payment_counts() {
    let node = CountNode::new(10_000, 310_000);
    let directory = tempfile::tempdir().unwrap();
    let mut engine = engine(directory.path(), &node).await;
    let lock = std::fs::read_dir(directory.path())
        .unwrap()
        .map(|e| e.unwrap().path())
        .find(|p| p.extension().is_some_and(|e| e == "lock"))
        .unwrap();
    // A directory where the temporary file belongs makes every save fail.
    std::fs::create_dir(lock.with_extension("decoys.tmp")).unwrap();
    assert_eq!(
        engine
            .distribution_for_payment(node.hash(310_000))
            .await
            .unwrap()
            .as_ref(),
        &node.expected()
    );
    assert!(engine.store.load_distribution().unwrap().is_none());
}

#[tokio::test]
async fn changed_chain_invalidates_disk_counts_and_bad_page_boundaries_are_rejected() {
    let node = CountNode::new(10_000, 310_000);
    let directory = tempfile::tempdir().unwrap();
    let mut first = engine(directory.path(), &node).await;
    first
        .distribution_for_payment(node.hash(310_000))
        .await
        .unwrap();
    drop(first);
    node.generation.store(1, Ordering::SeqCst);
    node.requests.lock().unwrap().clear();
    let mut reopened = engine(directory.path(), &node).await;
    assert_eq!(
        reopened
            .distribution_for_payment(node.hash(310_000))
            .await
            .unwrap()
            .as_ref(),
        &node.expected()
    );
    assert!(node.requests.lock().unwrap().len() > 1);
    drop(reopened);
    let other_directory = tempfile::tempdir().unwrap();
    let mut broken = node.clone();
    broken.requests.lock().unwrap().clear();
    broken.corrupt_boundary = true;
    let mut other = engine(other_directory.path(), &broken).await;
    assert!(matches!(
        other.distribution_for_payment(broken.hash(310_000)).await,
        Err(EngineError::Node)
    ));
    assert!(!other.store.load_distribution().unwrap().unwrap().complete);
    assert!(other.prepared.is_none() && other.state.payments.is_empty());
}

#[tokio::test]
async fn persisted_counts_are_not_reused_for_a_different_selected_node_or_route() {
    use wallet_policy::connection::NetworkRoute;
    let direct = WalletEngine::<CountNode>::distribution_source(
        "https://selected.example:18081/",
        NetworkRoute::Direct,
    )
    .unwrap();
    let tor = WalletEngine::<CountNode>::distribution_source(
        "https://selected.example:18081/",
        NetworkRoute::Tor,
    )
    .unwrap();
    let other = WalletEngine::<CountNode>::distribution_source(
        "https://another.example:18081/",
        NetworkRoute::Tor,
    )
    .unwrap();
    assert_ne!(direct, tor);
    assert_ne!(tor, other);
    let node = CountNode::new(10_000, 210_000);
    let directory = tempfile::tempdir().unwrap();
    let mut first = engine(directory.path(), &node).await;
    first.decoy_source = Some(direct);
    first
        .distribution_for_payment(node.hash(210_000))
        .await
        .unwrap();
    drop(first);
    node.requests.lock().unwrap().clear();
    let mut changed = engine(directory.path(), &node).await;
    changed.decoy_source = Some(tor);
    assert_eq!(
        changed
            .distribution_for_payment(node.hash(210_000))
            .await
            .unwrap()
            .as_ref(),
        &node.expected()
    );
    assert!(!node.requests.lock().unwrap().is_empty());
    assert_eq!(
        changed.store.load_distribution().unwrap().unwrap().source,
        tor
    );
}
