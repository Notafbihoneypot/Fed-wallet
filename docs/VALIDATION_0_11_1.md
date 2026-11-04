# Validation — 0.11.1 sync start and progress update

Date: 27 October 2026 (America/New_York). Android version 0.11.1-sync-preview, code 18; Rust workspace 0.11.1.

## Scope and results

The release adds an explicit initial scan-range choice and pause/change-start flow, adaptive bounded scan batches, a single-height cumulative-output lookup, and detailed private notification progress. Mainnet/Stagenet separation, keys, encrypted storage formats, node/route consent, payment verification and durable reservations are preserved. See SYNC_0_11_1.md for implementation and upgrade steps. The prior release record is retained in VALIDATION_0_11_0.md.

| Check | Observed result |
| --- | --- |
| Required Rust check script | 87 tests passed, 0 failed; 1 isolated-node case ignored in the default run. Formatting and Clippy across workspace/all targets passed with warnings denied. |
| Explicit isolated monerod test | 1 passed in 88.83 seconds after final changes. Monero CLI 0.18.5.4, offline fakechain, loopback-only bindings. |
| Recent-height initialization | A real nonzero-height scan fetched the preceding cumulative count and matched the full scan's received outputs for the same range. An earlier rescan recovered the full results. |
| Size-limit reduction | Fault-injected a response-size failure above 64 blocks, observed one reduction, then completed with the smaller session ceiling and unchanged response cap. |
| Funds/reservation protection | Forward changes reject recorded outputs/history/payment journals; earlier rescans preserve reservations and configured subaddresses. Empty-wallet range changes survive encrypted reopen. Full actual scan/sign/lost-reply/reopen/exact-byte retry/confirmation/maturity/reorg flow passed. |
| Batch-overhead benchmark | At 25 ms simulated latency per loopback RPC: fixed 32 blocks used 96 requests / 3.8076 seconds; adaptive used 12 requests / 1.1157 seconds for the same 1,000 blocks. Both use identical scan/validation. Handshake excluded. |
| Kotlin/JVM + real host UniFFI | 105 tests passed across 26 suites; 0 failures, errors or skipped cases. Covers prior key/copy/storage/DNS/route behavior plus fractional notification counters, completion, initial choice and explicit height confirmation/bounds. |
| Android build | Clean Kotlin build, final app assembly, test assembly and lint completed. |
| Android instrumentation | 21 cases compiled into the test APK; 0 executed. Includes a new initial-range-choice presentation case. |
| Lint | 0 errors, 16 warnings: 5 ImplicitSamInstance reports on stopService(Intent) calls, 1 UnusedAttribute, 7 dependency-version notices and 3 UseKtx notices. Dependency declarations and external lock entries were not changed. |
| Python checks | 4 tests passed across source-packaging, native-build environment and APK-integrity helpers. |
| App DEX integrity | 5 DEX files, 15,620 classes; bounds, checksums and duplicate-class checks passed. |
| Test DEX integrity | 3 DEX files, 1,073 classes; integrity checks passed. |
| Native build | Both arm64-v8a and x86_64 release libraries rebuilt from the final Rust source; Kotlin bindings regenerated. APK copies match fresh NDK stripping of those exact source outputs. |
| 16 KiB support | All 10 packaged ELF libraries checked; all 28 LOAD segments have alignment at least 16 KiB. APK zipalign -P 16 verification passed. |
| Install identity | Signature verified against the retained Sync Preview certificate; application ID, min SDK 28, target SDK 36 and code 18 checked in the packaged manifest. |
| Source/exclusions | Per-file manifest/local byte comparison, archive CRC and exclusions checked. No private signing key, wallet files, node settings, build/proxy credentials, toolchain or native binaries included. New native test markers are absent from the app APK. |

The benchmark ran in an isolated synthetic chain with an injected fixed RPC delay. It measures request overhead under those conditions, not public-node/phone throughput, historical-chain scans or Monero One parity. The first run was 96 requests / 3.5875 seconds versus 12 / 0.9784 seconds; variation in later timings is expected under concurrent build load. No claim of exact speed equivalence is made.

The isolated node starts with regtest/offline flags, DNS checkpoints/IGD/ZMQ disabled, RPC/P2P bound to loopback. The runner terminates it and removes temporary data afterward. All amounts are synthetic; no public-node payment was broadcast. Test adapters/fault injection are behind Rust cfg(test) and excluded from Android native libraries.

One incremental Kotlin compilation reported stale/conflicting function overloads despite unique source declarations. A clean rebuild with Kotlin incremental compilation/build cache disabled passed, and the final APK was assembled with those settings. Packaged DEX integrity and JNI input/output matching were checked afterward. No compiler/plugin dependency changes were used to work around this.

## Device and security boundaries

The user reported that the preceding build connects and syncs on their phone. That report is not an execution test of this update. Zero Android instrumentation tests, physical Pixel/GrapheneOS update, rendered notification/service scheduling, background pause/return credential flow, phone throughput or cross-wallet benchmarks ran for 0.11.1. The 21 instrumentation cases compile but are not reported as passed execution tests. Live Tor/hardware tests were not performed.

This remains a developer preview without a new independent security audit or production-funds certification. Existing limits on verified external spent-status/key-image imports, hardware signing, input/fee/subaddress coverage and other operations remain as documented in README.md and SYNC_SPEND.md. No cryptography, seed derivation, TLS verification policy, node defaults, failover or permissions were added/changed for this release.

The new-wallet shortcut is explicit and proposes the connected tip minus 1,440 blocks. It is not an authoritative wallet birthday or a guarantee about an old wallet's transactions. The user must confirm the chosen height precedes the first payment; full-history and earlier scans remain available. Native guards prevent a forward start from dropping already recorded local activity/reservations. No private view/spend key is sent to a scanning service, and notification text includes only public counters/route labels.

## APK and update identity

- File: independent-wallet-v0.11.1-sync-preview.apk
- Package / label: app.independentwallet.android.syncpreview / Independent Sync Preview
- Android version: 0.11.1-sync-preview; code 18
- Rust: 0.11.1
- Minimum API 28; compile/target API 36; arm64-v8a and x86_64
- Bytes: 59,977,428
- APK SHA256: 27eecd2247ec1904e8b6ede6f35243dd76e4d9189cbc8ccd448e7bfa3d85a687
- Certificate SHA256: 63b5045ee6e8a9de7fe90fb05f18ea9397a248360ed4ee496b16b1519c7f31e4
- Packaged ARM64 wallet library SHA256: a31a5d311492099feef8b0ba29ade3895bfafe732e09fe715e5d12dc669011ea
- Packaged x86_64 wallet library SHA256: eab403c9bfb8ff9485cfc68a8fbe67beb86813cbff92f4aa2c3ac494426a01d8

Install over the existing Sync Preview to retain original/Mainnet wallet files, unfinished setup, saved nodes and scan progress. Opening remains locked/offline; network/background consent remains explicit for the current live session. A previously running full-history scan keeps its saved start until the user pauses and explicitly applies a different start. The Mainnet-copy and original-wallet data/aliases remain unchanged. The retained private preview signing material is excluded from deliverables.

Cargo.lock differs from 0.11.0 only in the four local workspace version entries. External dependency pins and AndroidManifest.xml permissions/application settings were compared byte-for-byte with the delivered 0.11.0 source.

Build tools: pinned Rust 1.99.1, cargo-ndk 4.1.2, Temurin JDK 17.0.21+9, Gradle 8.14.6, AGP 8.10.0, Kotlin 2.1.0, platform/build tools 36, NDK 28.2.13676358 and Monero CLI 0.18.5.4. Downloaded distributions were checked against retained/published checksum metadata. Toolchains/caches remain outside the source deliverable.
