# Decoy issue classes

Baseline: 15 November 2026, wallet 0.11.11 plus the unreleased decoy-data checks, `monero-wallet 0.2.0`, `monero-interface 0.2.0`, `monero-daemon-rpc 0.2.0`. Function names are given instead of line numbers because lines move; re-read the code before citing it.

Paths under "upstream" are inside the pinned `monero-wallet` crate source (see the `monero-rust` skill for how to find it on disk).

## 1. Real output missing from the first request

**What:** if the wallet's first decoy request does not contain the real output, the node sees 15 known decoys, then a transaction using them plus one more. That one is the real spend. An earlier upstream version had this bug (a loop counter was incremented before an `== 0` check, so the real output was never included).
**Who learns:** a node that only logs. Full reveal.
**Check:** probe line `real-in-first-request`. Upstream `decoys.rs::select_n`, the `first_iter` flag.
**Baseline:** protected, measured (50 of 50).

## 2. Empty or short reply

**What:** the node returns fewer outputs than asked, hoping the wallet skips its check on the real output and asks again without it.
**Who learns:** an actively lying node. Full reveal if it works.
**Check:** probe line `empty-reply`. Two independent guards: the reply parser `accumulate_outs` in `monero-daemon-rpc` rejects a wrong count, and the blanket `ProvidesDecoys` impl in `monero-interface` rejects a wrong length.
**Baseline:** protected, measured (abort after one request).

## 3. Forced second round

**What:** the node returns a correct-length reply but marks some candidates as locked (or gives them undecodable keys). Upstream's outer loop then asks for replacements, and that request never contains the real output. The real spend must be among the first-round candidates that survive into the ring. If the node happens to mark the real output itself, the wallet aborts with "node presented different view of output we're trying to spend".
**Who learns:** an actively lying node. It narrows 16 to (16 minus the number it marked), with no error shown, whenever its blind pick misses the real output.
**Check:** probe lines `forced-second-round-k-of-16`. Upstream `select_n` outer `while` loop. The wallet's `CachedDecoys` adapter counts unusable replies (locked, undecodable, torsioned, identity key) and refuses the reply once more than `MAX_UNUSABLE_CANDIDATES` (2) have been seen for one input, before any replacement request is sent. Test: `a_node_reporting_many_unusable_candidates_cannot_force_more_requests`.
**Baseline:** BOUNDED, measured. Marking 1 or 2: a ring is still built and the node narrows the real spend to 15 or 14. Marking 3 or more: abort in 400 of 400 runs, with a `NODE_DECOYS` error. Before the limit, marking 15 fully revealed the real spend in about 6% of runs.
**Why not zero:** honest nodes do occasionally return a locked candidate, and aborting on every one would force retries that feed class 5.

## 4. False or truncated output counts

**What:** decoys are placed using the node's cumulative output counts. A node can return counts that are too small, shifted, or cut off early. Decoys then land far from the real spend, which stands out on-chain.
**Who learns:** everyone reading the chain, permanently. Caused by an actively lying node.
**Check:** `engine/decoys.rs`. `DistributionCache::validate` refuses a finished history that stops before block 0 and is shorter than `MIN_HISTORY_BLOCKS` (one year). `check_counts` runs before counts are handed to selection and compares them with the wallet's own data: the cumulative count at every saved checkpoint (the wallet counts RingCT outputs itself for its last 128 scanned blocks) and the chain index and block of every owned output. A mismatch returns `EngineError::DecoyData` and `distribution_for_payment` drops the in-memory and saved counts. Tests: `a_history_cut_short_by_the_node_is_refused_and_not_kept`, `counts_that_contradict_the_wallets_own_scan_are_refused`, `an_owned_output_must_lie_inside_the_counts_for_its_own_block`.
**Baseline:** PARTLY protected, measured by those tests. Before the checks, a history cut to 21 blocks was accepted and saved, and on a synthetic chain moved the median decoy age from 975 blocks to 297,688 with only 7% of decoys under 30 days old (87% with honest counts).
**Residual:** counts older than the wallet's 128-block window are pinned only at blocks where the wallet owns an output. A node could still misstate older regions for a wallet whose outputs are all very recent. Closing that would need the wallet to keep its own per-block counts for everything it scans.

## 5. Repeat preparation of the same coin

**What:** if every preparation picked fresh decoys and sent the real output's index again, two preparations of the same coin would share exactly one index.
**Who learns:** a node that only logs. Near-certain reveal after two preparations.
**Check:** `engine/payment.rs::prepare_payment` reuses `reusable_ring` before selecting, and `keep_ring` saves a fresh ring in the encrypted journal before it is used. Reuse lasts `RING_REUSE_BLOCKS` (720) and requires an exact match with the output; `State::rollback` drops rings a reorg reaches and `mark_spent` drops a spent output's ring. Test: `a_retry_reuses_the_ring_the_node_already_saw`.
**Baseline:** protected within 720 blocks, measured by that test (one node request across retries and a restart). Since 17 November 2026; before, NOT protected.
**Residual:** a retry more than 720 blocks (about one day) later selects again and repeats the exposure. A selection the wallet aborts (for example class 3's limit) saves no ring, so retrying on the same node exposes the real output again; switch node after `NODE_DECOYS`.

## 6. Historical selection bugs

**What:** the reference wallet's 2021 bugs (recent spends under-selected) and the 2023 off-by-one that could never pick an output exactly ten blocks old.
**Who learns:** everyone reading the chain, statistically.
**Check:** upstream `select_n`: the bound is taken ten entries from the end of the counts (includes ten-block-old outputs), the recent-window branch exists, gamma parameters 19.28 and 1/1.61. Ring size is 16 in `prepare_payment` and re-checked on the signed bytes in `verify.rs`. Test `every_signed_ring_hides_the_real_output_among_fifteen_requested_decoys` decodes signed bytes the way the receipt does: 16 distinct members, real output once at varying positions, every member from the request that carried the real output.
**Baseline:** protected, read from code.

## 7. Unusable decoys

**What:** ring members with torsion or an identity key cannot be real spends, so a ring containing one is smaller than it looks, or marks the wallet software.
**Check:** upstream `select_n` skips torsioned keys and commitments and identity keys. It does not skip an identity commitment; if the reference wallet does, that difference is a software fingerprint worth noting.
**Baseline:** protected for torsion and identity keys, read from code.

## 8. Node shows a different real output

**What:** the node returns a different key or commitment for the output being spent.
**Check:** upstream compares the reply for the real index with the wallet's own data and aborts on a mismatch.
**Baseline:** protected, measured indirectly (it is the abort seen in class 3).

## 9. Stale height

**What:** if decoys are chosen at an old height, the ring has no members from the newest blocks, which marks the transaction.
**Check:** `check_tip` requires the synced flag and a matching checkpoint hash; the app syncs until caught up before preparing.
**Baseline:** protected, read from code.

## 10. Count cache integrity and scope

**What:** the saved counts could be tampered with, reused for another wallet, node or route, or reused after a reorg.
**Check:** `store/decoys.rs` (authenticated encryption, separate key derivation) and the `source` and chain-hash checks in `distribution_for_payment`. Tests: `cargo test --locked -p wallet-engine decoy`.
**Baseline:** protected, measured by the nine existing tests. Note this protects the cache from outsiders and staleness; it does not make a lying node's counts true (class 4).

## Source for classes 1 to 3

"The Monero-oxide bug bounty should take privacy seriously", Spirobel, monerochan.news article 16. Its position: with a remote node, a misbehaving node should only ever be able to cause a failed payment.
