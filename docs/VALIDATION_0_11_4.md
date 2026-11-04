# Validation — 0.11.4 send review, Tor preparation and five saved nodes

Date: 27 October 2026 (America/New_York). Android 0.11.4-sync-preview, code 21; Rust workspace 0.11.4.

## Outcome and scope

This update adds Max, recipient/fee/total review, payment receipts and scanned confirmations, a changed-tip review recovery path, session-scoped public output-count caching for Tor/Direct payment preparation, and five named user-added nodes. See [SEND_NODES_0_11_4.md](SEND_NODES_0_11_4.md) for behavior and limits. The prior validation is archived in VALIDATION_0_11_3.md.

The prior send path downloaded the full uncompressed RingCT count distribution for each input. It also consumed a review when the chain tip changed during authentication, then treated the error as a generic connection failure. The native sampler now shares public cumulative counts at a validated snapshot; an advanced tip uses a hash-anchored overlapping increment. Actual randomized ring/output requests remain unchanged. The UI syncs before construction and returns a changed/expired review to a fresh quote requiring new authorization. Local amount/address/funds errors preserve known connection/sync state.

| Check | Observed result |
| --- | --- |
| Required Rust script | scripts/check.sh passed: formatting, 93 tests, 0 failures; one isolated-node test ignored by default. Clippy over the workspace/all targets passed with warnings denied. |
| Isolated Monero node | One real-node case passed in 86.59 seconds against verified Monero CLI 0.18.5.4, offline fakechain and loopback-only RPC/P2P. No real funds/public broadcast. |
| Max arithmetic and bytes | Mature synthetic 1 XMR receiver swept using normal change/output shape. Recipient amount plus exact fee equaled 1 XMR; transaction mined successfully, recipient received the reviewed amount, sender balance/spendable became 0 and no zero-change unlock countdown appeared. Existing actual-byte/payment-intent, key-image, CLSAG, range-proof and commitment checks ran. |
| Max boundaries | Immature receipt and view-only Max rejected. More than 16 eligible positive inputs rejected explicitly rather than sending a partial Max. Existing 0.05 XMR fee cap retained. |
| Tor preparation overhead | Two-input construction and repeated same-tip reviews caused only one new full distribution request. After a new block, an anchored overlap began at the previous cached height instead of genesis. This measures request reuse, not public-node/Tor throughput or phone speed. |
| Review authorization | Mining a block after construction made confirmation return SyncRequired with no pending reservation/broadcast. Sync and reprepare produced a different review ID; the app returns that new quote for explicit approval. |
| Durable send behavior | Lost reply retained reservation; encrypted reopen and explicit retry sent byte-identical transactions. Inclusion, receipt maturity, reorg and rescan checks passed. Journal metadata reported pending confirmations as 0 and mined Max confirmation as 1 with amount/fee/total intact. |
| Earlier regressions | Software/hardware viewing boundary, genesis identity, nonzero scan start, interrupted-window rollback, size reduction, pooled cancellation and previous scan/sign checks retained and passed. |
| Kotlin/JVM + host UniFFI | 125 tests across 30 suites, 0 failures/errors/skips. Rebuilt host library and regenerated Kotlin bindings used. |
| Node list and payment policy | Five-node round-trip/limit, duplicate rejection, migration of both old endpoints/consent, rename preservation, selection/removal revocation, no automatic replacement, malformed selection/lengths and edited-draft guards passed. Submission acknowledgment/uncertainty versus mined labels and wrong-network typed FFI errors passed. |
| Final Android build | App/test assembly, JVM tests and lint passed. Generated app/root build outputs removed first; --rerun-tasks, Kotlin incremental compilation/build cache disabled; 78 tasks executed. |
| Lint | 0 errors, 9 warnings: 5 ImplicitSamInstance, 1 UnusedAttribute and 3 UseKtx. No dependency/manifest update or disabled check used to pass. |
| Instrumentation | 21 cases compiled; 0 executed. |
| Python helper regressions | Four tests passed across source packaging, native environment filtering and APK integrity. |
| App/test DEX | App: 5 DEX / 15,684 classes. Test: 3 DEX / 1,073 classes. ZIP CRC, DEX bounds/checksums and duplicate definitions checked; both assembly tasks enforce the guard. This is not Android's runtime bytecode verifier. |
| Native build/16 KiB | Both release ABIs rebuilt. All 10 ELF libraries / 28 LOAD segments had alignment at least 16 KiB. APK zipalign -P 16 passed. Packaged JNI bytes matched stripped outputs and a fresh NDK strip of source outputs; original and merged outputs matched. Test-only markers absent. |
| Dependency/storage compatibility | All external Cargo.lock package records and AndroidManifest.xml byte-equivalent to build 20. Only four local workspace lockfile versions changed. Wallet/setup/native encrypted state and journal formats unchanged. |

## Consent and storage

IWN4 stores at most five named endpoint/route entries plus selected identity and prior connection preferences inside the existing Android Keystore AES-GCM encrypted, no-backup atomic node file. Old single-URL/IWN2/IWN3 payloads remain readable. No network grant is invented during migration. Name-only changes to the same canonical endpoint/route retain its session/grant. New/changed selection disconnects and revokes reconnect consent. Removing an active entry leaves no automatically selected replacement. There is no default list or failover.

Names are bounded to 48 characters; endpoints to 2,048 UTF-8 bytes; complete payload to 20,480 bytes. Duplicate route/endpoint identities, invalid selected references, unsupported values and malformed/trailing/oversized data fail closed. The same Rust endpoint/route validator applies before saving/using entries. Saving/selecting alone remains offline. An unfinished form blocks new connections to an older saved endpoint.

Background/reconnect behavior from 0.11.3 is retained: matching consent after authenticated unlock, bounded live dataSync service only, UI locked in background, no payment/key reopening after process death, no service signing and no automatic submission replay. Network off and Stop and lock revoke preference. A normal lock preserves the matching preference for the next authenticated unlock.

Signed bytes/input reservations still commit before broadcast. Unknown replies remain pending and only the same bytes can be retried. Node acceptance is distinct from block confirmation; disconnected confirmation data is marked last-scanned. Receipt metadata comes from the local journal and the transient node acknowledgment is not falsely persisted across a restart. Max uses checked exact atomic arithmetic and never bypasses output maturity, reservations, view-only authority or fee/input bounds.

Cached counts are public, session-scoped, height/hash-bound and bounded to 32 MiB of uint64 values. Actual rings/output responses are not cached. A new transport, reorg, rescan or disconnected/destroyed owner invalidates the cache. No deterministic sampler, trusted-node bypass, extra resolver, HTTP downgrade, redirect, alternate node, Tor-to-Direct fallback, seed derivation or cryptographic primitive was added. No private wallet/node data or keys were inspected/uploaded.

## Execution limits

No physical phone/emulator UI, Android Keystore node-file round-trip, notification/screen-off lifecycle, live Tor routing/throughput, actual mobile send or hardware execution was performed. Compiled instrumentation and host policy tests do not establish those results. The first Tor distribution download and node performance can still be slow; mining confirmation is separate from preparation and cannot be guaranteed by this optimization. No Monero One speed parity is claimed.

The existing preview limits remain: one recipient/up to 16 inputs, 0.05 XMR fee cap, account 0/first 20 subaddresses, no incoming mempool scan, no time-lock consensus-clock adapter, no safe permanent-rejection abandonment, external verified key-image sync or hardware signing. Mainnet and Stagenet remain separate. No independent security audit or production-funds certification is claimed.

The build environment disconnected during preparation. Generated Gradle caches were rebuilt in an isolated temporary directory and the missing full JDK compiler was provisioned from a verified official Temurin archive. Earlier incomplete builds are not deliverables. Final assembly/tests/lint and package checks completed successfully without changing dependencies, manifest settings or security gates.

## APK and update identity

- File: independent-wallet-v0.11.4-sync-preview.apk
- Package / label: app.independentwallet.android.syncpreview / Independent Sync Preview
- Version 0.11.4-sync-preview, code 21; Rust 0.11.4
- Minimum API 28, compile/target API 36; arm64-v8a and x86_64
- Bytes: 60,239,580
- APK SHA256: 64dfbe096876084d6cd27a6a88099ec3eddb44faa4382176ab3aa9d99cb65e65
- Certificate SHA256: 63b5045ee6e8a9de7fe90fb05f18ea9397a248360ed4ee496b16b1519c7f31e4
- Packaged ARM64 wallet library SHA256: 0d78f2ffe81fdb7c91bc07138c125f40ee432132448f950979d1a7abf9d79c96
- Packaged x86_64 wallet library SHA256: 39a2790cf706d94470a36eccd91d56b51c5cb5e2219425adbb95dca86aa0f226

Install in place over the current Sync Preview without uninstalling or clearing data. Existing build-20 consent, completed/unfinished wallets, chosen nodes, scan checkpoints and pending payments remain readable; no recreation or new rescan is required. The private signing key is excluded from every deliverable.

Tools: Rust 1.99.1, cargo-ndk 4.1.2, verified Temurin JDK 17.0.21+9, Gradle 8.14.6, AGP 8.10.0, Kotlin 2.1.0, SDK/build tools 36, NDK 28.2.13676358, platform tools 37.0.1 and verified Monero CLI 0.18.5.4. Toolchains/caches are excluded from source.
