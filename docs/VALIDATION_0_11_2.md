# Validation — 0.11.2 connection reuse and scan checkpoints

Date: 27 October 2026 (America/New_York). Android version 0.11.2-sync-preview, code 19; Rust workspace 0.11.2.

## Scope and results

The release reuses a bounded HTTP/1.1 connection within each selected-node/route session and groups quiet scan reads into durable windows. It reduces socket setup, repeated tip checks and scan-cache writes. Every reported scan result is committed first; wallet activity ends grouping after the current read. Payment reservations still commit synchronously before broadcast. See [SYNC_0_11_2.md](SYNC_0_11_2.md) for behavior, tradeoffs and upgrade steps. The preceding release record is retained in VALIDATION_0_11_1.md.

| Check | Observed result |
| --- | --- |
| Required Rust check script | 92 tests passed, 0 failed; 1 isolated-node case ignored in the default run. Workspace formatting and Clippy across all targets passed with warnings denied. |
| Explicit isolated monerod test | 1 passed in 115.32 seconds after final changes. Monero CLI 0.18.5.4, offline fakechain, loopback-only bindings. |
| Pooled transport | Concurrent clones share one socket; server closure opens a new socket; interruption of a partial body discards it; cancellation closes an idle pooled socket and prevents later requests. A lost broadcast reply is observed once without automatic replay. Existing encoding, status, size, DNS, TLS/SNI, no-downgrade and route checks passed. |
| Durable scan windows | An injected mid-window read failure preserves the last returned height in memory and on disk, including after encrypted reopen. An owned-output chunk triggers an early durable result. Full scan equivalence and resumed completion passed. No elapsed-time ordering assertion depends on host load. |
| Earlier scan/payment regressions | Nonzero recent-height initialization, earlier full rescan, response-size reduction, forward-start protection, configured subaddresses, hardware/view-only authority, real production Direct transport and actual signed-payment/lost-reply/durable-reservation/reopen/exact-byte-retry/confirmation/maturity/reorg flow passed. |
| 1,000-block overhead measurement | At 25 ms simulated delay per RPC: fixed-32 windows used 96 RPCs / 32 saves / 3.374848 s; adaptive single-read windows used 12 / 4 / 0.837522 s; coalesced windows used 6 / 1 / 0.622248 s. Identical synthetic-chain scanner/validation; connection setup excluded. |
| Connection setup measurement | Twenty identical HTTP RPCs with 25 ms simulated delay per new TCP socket used 20 connections / 0.542996 s when closed each time, versus 1 connection / 0.031733 s with reuse. Both completed 20 requests. Counts/correctness are asserted; wall-clock ordering is not. |
| Kotlin/JVM + host UniFFI | 105 tests passed across 26 suites; 0 failures, errors or skipped cases. Real JNI calls use the rebuilt 0.11.2 host core. |
| Android build | App assembly, test assembly, JVM checks and lint completed. Kotlin incremental compilation/build cache disabled. |
| Android instrumentation | 21 cases compiled into the test APK; 0 executed. |
| Lint | 0 errors, 17 warnings: 5 ImplicitSamInstance reports on stopService(Intent), 1 UnusedAttribute, 8 dependency-version notices and 3 UseKtx notices. External dependency pins and Android permissions/settings are unchanged. |
| Python checks | 4 tests passed across source-packaging, native-build environment and APK-integrity helpers. |
| App DEX integrity | 5 DEX files, 15,620 classes; bounds, checksums, ZIP CRC and duplicate-class checks passed. |
| Test DEX integrity | 3 DEX files, 1,073 classes; integrity checks passed. |
| Native build | Both arm64-v8a and x86_64 release libraries rebuilt from final production Rust source; Kotlin bindings regenerated. Merged JNI inputs match source outputs, packaged copies match stripped outputs and fresh NDK stripping reproduces their bytes. |
| 16 KiB support | All 10 packaged ELF libraries checked; all 28 LOAD segments have alignment at least 16 KiB. APK zipalign -P 16 verification passed. |
| Install identity | Signature matches the retained Sync Preview certificate. Application ID, label, code 19, version name, min SDK 28, target SDK 36 and both ABIs verified in packaged manifest/badging. |
| Source/exclusions | Source archive has per-file SHA256SUMS, byte/CRC/exclusion checks. No private signing key, wallet files, node settings, build/proxy credentials, toolchain, cache or native binaries are included. New test-only markers are absent from the APK. |

The final isolated scan test measures overhead with an injected fixed delay on a short synthetic chain. It is not public-node, Android, historical-chain or Monero One throughput evidence. The preceding run measured 3.664098 / 0.948102 / 0.659756 seconds for those three modes, with the same 96/12/6 requests and 32/4/1 saves; timing varies with host load. The connection measurement is separate and is not added to the scan result as a predicted speedup.

The node uses regtest/offline flags, DNS checkpoints/IGD/ZMQ disabled and loopback RPC/P2P. The runner removes temporary data and terminates the node. Amounts are synthetic; no public-node payment was broadcast. Fault injection, save counters and benchmark fixtures compile only under cfg(test).

## Durability and lifecycle boundaries

Quiet scan windows contain at most 2,000 blocks fetched in independently bounded adaptive chunks of 32–512. The roughly two-second target is checked after a chunk; one slow request can exceed it within the unchanged 90-second timeout. No scan status is returned before its encrypted atomic save. Failure/cancellation drops unreported partial work; the prior committed checkpoint remains. Pause finishes the current call, while explicit lock cancels. No dirty state is retained between calls or flushed by a new blocking Drop handler. Receipt, spend and confirmation changes end grouping after the current read.

All non-scan mutations retain their prior immediate commit behavior. In particular, actual signed-byte verification and durable signed-transaction/input reservations remain before broadcast; interrupted replies do not permit automatic resend or new input reuse. Reorg rollback and earlier rescan preserve reservations.

The pool belongs only to an immutable endpoint/route session. Complete successful responses alone may return a ready connection. Reuse eligibility ends after 20 idle seconds; a socket is not forcibly timed out at that exact instant. Cancellation closes even idle pooled sockets. Known closed/expired sockets are replaced before a request; an in-flight failure surfaces without automatic application-request replay. The original URL remains the HTTP/TLS authority, with Android Direct DNS and Arti remote DNS unchanged. TLS verification, response bounds, no redirects, explicit network/background consent, node selection and route isolation are preserved.

## Device and security boundaries

The user reported that 0.11.1 connects and scans on their phone. That report is not execution evidence for this update. Zero physical Pixel/GrapheneOS update tests, Android instrumentation executions, rendered notification/service scheduling tests, phone throughput or cross-wallet benchmarks ran for 0.11.2. The 21 instrumentation cases compile only. Live Tor and hardware execution were not performed.

The recent-wallet range choice, detailed private notification counters and bounded opt-in Android foreground service remain from 0.11.1. No iOS location-based background policy, automatic scan-start movement, new permission, remote scanning/key upload, node default, automatic failover or HTTP downgrade was introduced. Opening the app remains locked/offline until explicit session consent. Keys, seed derivations, encrypted vault/setup/cache formats, original/Mainnet slots and signing authority are unchanged.

This remains a developer preview with the existing operational limits in README.md and SYNC_SPEND.md. No new independent security audit or production-funds certification is claimed. Hardware/view-only sessions cannot sign and existing external key-image/spent-status limitations remain visible.

## APK and update identity

- File: independent-wallet-v0.11.2-sync-preview.apk
- Package / label: app.independentwallet.android.syncpreview / Independent Sync Preview
- Android version: 0.11.2-sync-preview; code 19
- Rust: 0.11.2
- Minimum API 28; compile/target API 36; arm64-v8a and x86_64
- Bytes: 60,042,968
- APK SHA256: 3963074ab7b4fbdbdaa7f13aeaf001fa83c2038719258f44794c4348e3dd8b75
- Certificate SHA256: 63b5045ee6e8a9de7fe90fb05f18ea9397a248360ed4ee496b16b1519c7f31e4
- Packaged ARM64 wallet library SHA256: 105c4609eda34648a3f80f0ffde6c088b3f9f4985ecf590aa14c2a796bef2c99
- Packaged x86_64 wallet library SHA256: f8cfbd5f249c84911d534555610ceb323c6bd8a9f6746d3047c44225917087f2

Install over the existing Sync Preview without uninstalling or clearing data. Retain wallet files, unfinished setup, selected node/route, chosen scan start and committed progress. Unlock and explicitly reconnect. A previous full-history start remains until the user pauses and explicitly changes it. The private preview signing key is excluded from all deliverables.

Cargo.lock differs from the delivered 0.11.1 source only in four local workspace version entries. All external package entries and AndroidManifest.xml were compared with that archive; dependency pins and permissions/application settings match exactly. No new dependencies were introduced.

Build tools: pinned Rust 1.99.1, cargo-ndk 4.1.2, Temurin JDK 17.0.21+9, Gradle 8.14.6, AGP 8.10.0, Kotlin 2.1.0, platform/build tools 36, NDK 28.2.13676358 and Monero CLI 0.18.5.4. The previously verified toolchain installations/caches were reused; they are excluded from the source deliverable.
