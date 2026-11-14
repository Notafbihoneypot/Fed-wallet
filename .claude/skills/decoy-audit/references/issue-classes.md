# Decoy issue classes

Baseline: 14 November 2026, wallet 0.11.11, `monero-wallet 0.2.0`, `monero-interface 0.2.0`, `monero-daemon-rpc 0.2.0`. Function names are given instead of line numbers because lines move; re-read the code before citing it.

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
**Check:** probe lines `forced-second-round-k-of-16`. Upstream `select_n` outer `while` loop; there is a cap of ten candidate draws per ring member, and the wallet's `CachedDecoys` adapter adds no limit of its own.
**Baseline:** NOT protected, measured. Marking 1: narrowed to 15 in about 93% of runs. Marking 8: narrowed to 8 in about 50%. Marking 15: fully revealed in about 6%, abort otherwise.
**Fix options (owner decision):** tolerate at most a small number of unusable candidates per input and then abort, or abort on any second round. The second leaks nothing per attempt but honest nodes do occasionally return a locked candidate, and every retry feeds class 5.

## 4. False or truncated output counts

**What:** decoys are placed using the node's cumulative output counts. A node can return counts that are too small, shifted, or cut off early. Decoys then land far from the real spend, which stands out on-chain.
**Who learns:** everyone reading the chain, permanently. Caused by an actively lying node.
**Check:** `engine/decoys.rs`: `DistributionCache::validate` (only size and non-decreasing), `prepend` and the first-page branch of `distribution_for_payment` (any shortened oldest page is treated as the complete history). Nothing compares the counts with facts the wallet already holds, such as each owned output's chain index and block height, or the network's known first RingCT block.
**Baseline:** NOT protected, read from code, not exercised by a test. The false counts are also saved for that node and route.
**Fix direction:** reject a history that starts later than the network's known RingCT start, and check that each spent output's index falls inside the counts for its own block.

## 5. Repeat preparation of the same coin

**What:** every preparation picks fresh decoys and sends the real output's index again. Two preparations of the same coin share exactly one index.
**Who learns:** a node that only logs. Near-certain reveal after two preparations.
**Check:** `engine/payment.rs::prepare_payment` calls `OutputWithDecoys::new` each time; nothing keeps the ring. Review lifetime is 300 seconds. Confirm the app never re-prepares on its own (`quotePayment` in the Android view model is user-initiated).
**Baseline:** NOT protected, read from code. Same behaviour as the reference wallet with a remote node.
**Constraint:** the obvious fix, reusing the ring, is forbidden by the `AGENTS.md` rule against caching or replaying ring requests. Owner decision.

## 6. Historical selection bugs

**What:** the reference wallet's 2021 bugs (recent spends under-selected) and the 2023 off-by-one that could never pick an output exactly ten blocks old.
**Who learns:** everyone reading the chain, statistically.
**Check:** upstream `select_n`: the bound is taken ten entries from the end of the counts (includes ten-block-old outputs), the recent-window branch exists, gamma parameters 19.28 and 1/1.61. Ring size is 16 in `prepare_payment` and re-checked on the signed bytes in `verify.rs`.
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
