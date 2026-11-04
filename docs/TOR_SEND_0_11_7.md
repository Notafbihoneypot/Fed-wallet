# Tor payment preparation — 0.11.7

Date: 28 October 2026 (America/New_York). Android 0.11.7-preview2, code 25.

The reported timeout occurs **before the fee review**. This identifies preparation rather than an already authorized broadcast. No current phone log or live circuit trace was supplied, so the exact failing request on the phone is not yet established.

## Findings and changes

The previous first preparation requested the entire cumulative RingCT output distribution in one binary RPC response. The pinned upstream implementation uses uncompressed 64-bit counts. At mainnet scale this can mean tens of megabytes under the transport's single 90-second request limit. The previous cache existed only in the active engine; locking disconnected and discarded it. A slow initial response could therefore fail repeatedly after reopen. Separately, a network error while selecting decoys was collapsed into a generic node-data error.

The update reads the same distribution in pages containing at most 131,072 counts, about 1 MiB plus bounded binary-RPC overhead. It starts near the tip and works backwards so it never requests a page entirely before RingCT activation. A shortened oldest page identifies the same activation boundary accepted by the upstream parser. Cumulative boundary counts must agree exactly, and the upstream monotonicity and length checks remain. The full distribution is assembled before the existing upstream decoy-selection algorithm runs; no history or ring members are skipped.

Only public-count reads that return timeout/oversize errors shrink automatically, down to 4,096 counts (32 KiB), using the same explicitly selected endpoint and route. The per-request deadline, hard response cap, TLS checks and cancellation remain. Output/ring lookups and broadcasts are not automatically replayed. This change improves completion and retry behavior on a slow link; it does not reduce initial total count bytes, accelerate block mining or guarantee a healthy circuit/node.

Completed parts are fsynced and atomically replaced in a separate encrypted acceleration file. It uses a separate HKDF/AAD domain with the existing authenticated-encryption machinery, and the wallet's existing single-writer lease. The maximum file is 33,554,537 bytes (32 MiB of counts plus authenticated metadata/encryption). It contains the source identifier, chain anchor, completion flag and public cumulative counts; never rings, per-output responses, recipients or signed transaction bytes.

The source identifier binds the normalized selected endpoint and explicit Direct/Tor route. The wallet namespace already isolates networks and software/view-only/Keystone storage. A changed source or chain hash rejects the cache, and rescan clears it. There is one acceleration file per wallet namespace; changing endpoints replaces that file after the first successful page, without selecting another endpoint. Missing/corrupt acceleration data is a cache miss and cannot make the separate funds/payment journal unreadable.

An advertised block hash is not independent verification of an untrusted node's complete distribution. Existing node trust, Monero parser checks, actual input/output checks and local signed-transaction cryptographic/intent verification still apply. Initial download/checkpointing can remain slow: the bounded encrypted count file is rewritten after each successful page to preserve progress. Very slow links can need more than one visible unlocked session. An explicit retry resumes saved parts for the same source and checks its chain anchor again.

## Progress, errors and lifecycle

The screen shows checking inputs, getting the fee, loading decoy data with the saved byte count, selecting decoys, local construction and local verification. These values come from native progress atomics. The progress API does not take the engine mutex or make network requests, so it remains readable while a request is outstanding. A ready phase alone cannot display a review: the actual preparation result must return and pass the existing session/epoch guards.

Network errors from decoy selection keep their original closed NET code. A preparation failure identifies the step and says no payment was sent. Broadcast uncertainty remains distinct and never receives that message. Logs include only enum stage/route labels and closed diagnostic codes; node error bodies, endpoints, recipients, amounts, keys and transaction bytes are excluded.

The wallet must remain visible and unlocked to prepare a payment. Background/inactivity cancellation does not gain authority to continue a new quote or signing operation. Completed count parts remain reusable on a later authorized session. Normal scan/poll work remains paused during payment entry/review/authentication. The existing authorized-publication background exception, fsynced input reservations before broadcast, explicit exact-byte retry and reorg/spent-input review invalidation remain.

## Validation and limits

See [current validation](VALIDATION.md) for final executed counts and artifact hashes. New cases exercise a slow-link budget that rejects the former full request, equality of paged/full distributions, a pre-RingCT boundary, interrupted durable resume after reopen, a growing chain tail, changed source/route/fork, malformed overlap, smaller read budgets, maximum cache size, corruption/wrong key, separation from the funds journal, nonblocking progress and owner revocation. An isolated offline fakechain test injects a real upstream decoy-lookup timeout and requires no review, no reservation and no broadcast before continuing its existing send/retry/confirmation/reorg checks.

No live Tor circuit, GrapheneOS session, Android service lifecycle or on-device installation is claimed by these host checks. The remaining crypto-bigint/rsa advisory alerts and maintenance warnings from [the previous security review](SECURITY_0_11_6.md) have not been resolved by this update; third-party dependency versions are unchanged. This is a targeted fix and review, not a complete independent security audit.

Install over Independent Preview 2 without uninstalling or clearing data. The same backed-up package/certificate is retained. No Monero keys, wallet data or user funds were accessed for development; isolated tests use disposable fixtures. See [signing and compatibility](PREVIEW2_SIGNING.md).

Primary protocol references: [Monero daemon RPC](https://www.getmonero.org/resources/developer-guides/daemon-rpc.html#get_output_distribution), the pinned [monero-daemon-rpc 0.2.0 source](https://docs.rs/crate/monero-daemon-rpc/0.2.0/source/src/bin_rpc/mod.rs), and [monero-interface 0.2.0](https://docs.rs/crate/monero-interface/0.2.0/source/src/provides_decoys.rs).
