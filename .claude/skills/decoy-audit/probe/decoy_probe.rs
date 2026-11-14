//! Decoy-request probe, installed temporarily by `scripts/run_probe.sh`.
//!
//! It drives the wallet's own decoy adapter (`CachedDecoys`) and the pinned
//! upstream selection against a node that misbehaves in specific ways, and
//! prints one RESULT line per behaviour. It measures; it never fails the build
//! for an exposed behaviour, so every line is always printed.
//!
//! If this stops compiling, the engine's internals moved. Update the probe to
//! match; do not delete a scenario to make it build.
use super::*;
use crate::tests::NoNetwork;
use curve25519_dalek::constants::ED25519_BASEPOINT_TABLE;
use monero_wallet::{ed25519::Commitment, interface::ProvidesUnvalidatedDecoys};
use std::{ops::RangeBounds, sync::{Arc, Mutex}};

const REAL_INDEX: u64 = 5_000;
const TIP: usize = 1_000;

#[derive(Clone, Copy, PartialEq)]
enum Mode {
    Honest,
    Empty,
    /// Round one only: report this many random positions as locked.
    Lock(usize),
}
struct Node {
    real: [Point; 2],
    mode: Mode,
    requests: Mutex<Vec<Vec<u64>>>,
}
impl ProvidesBlockchainMeta for Node {
    async fn latest_block_number(&self) -> Result<usize, InterfaceError> {
        Ok(TIP)
    }
}
// The unvalidated trait, so upstream's own response validation runs as it does
// for the real daemon client.
impl ProvidesUnvalidatedDecoys for Node {
    async fn ringct_output_distribution(
        &self,
        _: impl Send + RangeBounds<usize>,
    ) -> Result<Vec<u64>, InterfaceError> {
        Ok((1..=1_001).map(|block| block * 10).collect())
    }
    async fn unlocked_ringct_outputs(
        &self,
        indexes: &[u64],
        _: EvaluateUnlocked,
    ) -> Result<Vec<Option<[Point; 2]>>, TransactionsError> {
        let round = {
            let mut r = self.requests.lock().unwrap();
            r.push(indexes.to_vec());
            r.len()
        };
        if self.mode == Mode::Empty {
            return Ok(vec![]);
        }
        let mut locked = vec![false; indexes.len()];
        if let (Mode::Lock(k), 1) = (self.mode, round) {
            // The node cannot tell which index is real: it picks positions blindly.
            let mut left = k;
            while left > 0 {
                let p = (OsRng.next_u64() as usize) % indexes.len();
                if !locked[p] {
                    locked[p] = true;
                    left -= 1;
                }
            }
        }
        Ok(indexes
            .iter()
            .zip(locked)
            .map(|(&i, locked)| {
                (!locked).then(|| {
                    if i == REAL_INDEX {
                        self.real
                    } else {
                        [point(), point()]
                    }
                })
            })
            .collect())
    }
}
fn point() -> Point {
    Point::from(&Scalar::random(&mut OsRng).into() * ED25519_BASEPOINT_TABLE)
}
fn owned_output(engine: &WalletEngine<NoNetwork>, amount: u64) -> WalletOutput {
    let offset = Scalar::random(&mut OsRng);
    let secret = Zeroizing::new((**engine.spend.as_ref().unwrap()).into() + offset.into());
    let mut bytes = vec![9; 32];
    bytes.extend(0u64.to_le_bytes());
    bytes.extend(REAL_INDEX.to_le_bytes());
    bytes.extend(
        Point::from(&*secret * ED25519_BASEPOINT_TABLE)
            .compress()
            .to_bytes(),
    );
    offset.write(&mut bytes).unwrap();
    Commitment::new(Scalar::random(&mut OsRng), amount)
        .write(&mut bytes)
        .unwrap();
    bytes.extend([0, 0, 0, 0]);
    WalletOutput::read(&mut bytes.as_slice()).unwrap()
}
/// Runs the wallet's own adapter (`CachedDecoys`) around the node, as `prepare_payment` does.
async fn select(
    output: &WalletOutput,
    mode: Mode,
) -> (Result<Vec<u64>, String>, Vec<Vec<u64>>) {
    let node = Node {
        real: [output.key(), output.commitment().commit()],
        mode,
        requests: Mutex::default(),
    };
    let counts = Arc::new((1..=1_001u64).map(|block| block * 10).collect::<Vec<_>>());
    let adapter = decoys::CachedDecoys {
        rpc: &node,
        height: TIP,
        counts,
    };
    let result = OutputWithDecoys::new(&mut OsRng, &adapter, 16, TIP, output.clone())
        .await
        .map(|input| {
            let mut at = 0;
            input
                .decoys()
                .offsets()
                .iter()
                .map(|o| {
                    at += o;
                    at
                })
                .collect::<Vec<_>>()
        })
        .map_err(|e| e.to_string());
    let requests = node.requests.lock().unwrap().clone();
    (result, requests)
}

#[tokio::test]
async fn decoy_audit_probe() {
    let wallet = OfflineWallet::restore_spend_key(
        KeyNetwork::Stagenet,
        Zeroizing::new(format!("01{}", "00".repeat(31))),
        0,
    )
    .unwrap();
    let dir = tempfile::tempdir().unwrap();
    let engine =
        WalletEngine::<NoNetwork>::new(&wallet, dir.path(), CancellationToken::new()).unwrap();
    let output = owned_output(&engine, 1_000_000_000_000);
    let verdict = |protected: bool| if protected { "PROTECTED" } else { "EXPOSED" };

    // 1. Honest node: the real output must be inside the first request, and an
    //    honest node must see exactly one request of ring size.
    let (mut first_has_real, mut single_request) = (0, 0);
    for _ in 0..50 {
        let (ring, requests) = select(&output, Mode::Honest).await;
        ring.unwrap();
        first_has_real += usize::from(requests[0].contains(&REAL_INDEX));
        single_request += usize::from(requests.len() == 1 && requests[0].len() == 16);
    }
    println!(
        "RESULT real-in-first-request {}: real output in first request {first_has_real}/50",
        verdict(first_has_real == 50)
    );
    println!(
        "RESULT single-request-when-honest {}: exactly one 16-index request {single_request}/50",
        verdict(single_request == 50)
    );

    // 2. Empty reply: must abort instead of asking again without the real output.
    let (ring, requests) = select(&output, Mode::Empty).await;
    println!(
        "RESULT empty-reply {}: {} after {} request(s)",
        verdict(ring.is_err() && requests.len() == 1),
        match &ring {
            Ok(_) => "RING BUILT".to_owned(),
            Err(e) => format!("aborted ({e})"),
        },
        requests.len()
    );

    // 3. Correct-length reply with k blindly chosen candidates reported locked.
    //    "Narrowed to" is how many ring members the node was never asked about
    //    after round one; the real output is always one of them.
    for k in [1usize, 2, 8, 15] {
        let (mut aborted, mut built, mut later_has_real) = (0, 0, 0);
        let mut narrowed = BTreeSet::new();
        for _ in 0..400 {
            let (ring, requests) = select(&output, Mode::Lock(k)).await;
            match ring {
                Err(_) => aborted += 1,
                Ok(ring) => {
                    built += 1;
                    later_has_real +=
                        usize::from(requests[1..].iter().any(|r| r.contains(&REAL_INDEX)));
                    let later: BTreeSet<u64> = requests[1..].iter().flatten().copied().collect();
                    narrowed.insert(ring.iter().filter(|i| !later.contains(i)).count());
                }
            }
        }
        println!(
            "RESULT forced-second-round-{k}-of-16 {}: aborted {aborted}/400, ring built silently {built}/400, node narrowed real spend to {narrowed:?} of 16, later request contained real {later_has_real}",
            verdict(built == 0)
        );
    }
}
