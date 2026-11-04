# Validation — 0.7.0 network preview

Executed 25 October 2026 (America/New_York). Network access and Sync now are exposed on the Node screen and wallet dashboard. Enabling access starts the existing Rust Tor/scanning engine; healthy unlocked sessions check for new blocks every 30 seconds. Use onion node selects a separate user-supplied endpoint. HTTPS and onion connections both use embedded Tor, with no direct route, preset nodes or automatic fallback. NETWORK_ACCESS.md documents use, consent, migration and limits.

The disabled cloud-backup Settings entry was removed. Packaged allowBackup=false, fullBackupContent=false and data-extraction rules were verified. Local encrypted setup/wallet storage, manual offline backup and Resume/Discard remain.

## Executed checks

| Check | Actual result |
| --- | --- |
| Rust scripts/check.sh | Formatting, 65 tests passed, zero failures; all-target Clippy with warnings denied passed |
| Real Monero integration | One isolated-node release test passed in 62.19 seconds: scanning, software sign/verify, lost reply, durable reopen/exact retry, confirmation, maturity, reorg/rescan and hardware mainnet-format viewing |
| JVM | 48 passed; zero failures, errors or skips, including actual native bridge calls |
| New endpoint tests | Legacy single-node migration, separate slots, empty-slot no-fallback, malformed lengths/UTF-8/trailing data, redacted formatting and native node-type/credential validation |
| New polling tests | No work before explicit start, cancellation after stop, blocked/busy gating and restart without duplicate polling jobs |
| Existing JVM checks | Encrypted setup/resume/discard, clipboard retention, credential windows, ownership, QR/Keystone pairing and software/mainnet rejection remain passing |
| Python | Four source-packaging/build-environment/APK-integrity tests passed |
| Android | Clean isolated app/instrumentation assembly, unit tests and lint passed; 77 tasks executed in 5m9s |
| Lint | Zero errors; 11 warnings: eight dependency-version notices, one API-level attribute warning and two KTX style suggestions |
| App integrity | Five valid DEX files, 15,505 distinct classes; lengths, SHA1/Adler checksums, duplicates and ZIP CRC checked |
| Instrumentation integrity | Three valid DEX files, 1,026 distinct classes; structural checks passed |
| Signing/alignment | APK v2 signature, matching retained preview certificate, 16 KiB ZIP alignment and ELF LOAD alignment for all ten native libraries verified |
| Backup controls | Removed Settings text absent from APK strings; packaged backup-disable flags and data-extraction-rules reference verified |
| Device execution | Ten instrumentation cases compiled; zero executed |

The real-node test runs Monero 0.18.5.4 offline on loopback, using a synthetic chain. Its archive matched the published signed SHA256 ba2928962c101b609434bf8c1b43ad59ebe96c5525977284ca599d8bf0f02d10. Mainnet-format genesis/scanning uses the production hardware identity check; only the software stagenet fixture has a test-module fakechain bypass. This proves core RPC/scanning/transaction behavior against that isolated node, not live Tor routing or public-mainnet operation. No direct loopback transport is compiled into Android.

Rust/vendor source, Cargo.toml, Cargo.lock and the toolchain pin remain identical to v0.6.1: 67 files compared. The host bridge and test binaries were freshly built in temporary directories. Packaged Android Rust libraries retain the previously validated v0.6.0 core; their recorded binary digests and independently stripped compiler outputs match this APK. Generated bindings and wallet/setup formats are unchanged.

## Connection and persistence boundaries

The network switch is live-session consent, not a saved preference or proof of routing. Offline setup, loading node settings and selecting/saving a profile do not construct a Tor connection. Completed encrypted wallet storage remains required; backup verification and Keystone address comparison cannot be bypassed by enabling access. The native transport continues to carry node traffic and destination DNS over Tor, verify HTTPS certificates/onion identity and validate the wallet network's genesis.

Connected becomes true after native connection checks. Scan results show actual heights; failures mark the display unsynced. Foreground polling skips active work, authentication, reviews, busy wallets and errors, and does not extend the five-minute inactivity timeout. Network off/node changes/lock/background cancel the polling job and native requests. Epoch checks ignore late replies. No background service, WorkManager job, extra Android permission or new runtime dependency was added.

Node preferences retain the original Android Keystore alias, AES-GCM/AAD and node-v1.bin filename. Legacy URL payloads migrate to the appropriate slot; the next explicit preference edit writes the bounded IWN2 format. A selected empty slot yields no endpoint. A process-wide monitor protects atomic read-modify-write and preserves the inactive slot. Corrupt/unavailable settings fail closed until the user explicitly forgets node settings. Resetting those preferences does not delete wallet/setup/cache files. URL syntax validation does not prove a node is reachable or honest.

On GrapheneOS, the OS Network permission must allow the app. The troubleshooting link opens system app settings; it cannot grant or bypass OS permission. Actual UI/Keystore/network-permission behavior still needs device execution. Returning from system settings requires unlock and explicit network consent again.

## Sensitive data and dependencies

Gitleaks 8.30.1 default rules with redaction found zero matches in extracted APK DEX/native strings. Public spend/view vectors and test-only QR/wallet/node fixture markers were checked absent. The source scan retains two inspected historical false positives: docs/CODE_REVIEW.md line 55 and a public archive checksum in vendor/UPSTREAM.md line 11. No allowlist was added; the raw source scan retains its nonzero status. Scan results cannot prove the absence of every secret or vulnerability.

No dependency declarations or Rust lockfile entries changed. No fresh full dependency-vulnerability database audit was performed. DEPENDENCY_REVIEW.md retains the unresolved rsa 0.9.10 RUSTSEC-2023-0071/CVE-2023-49092 finding and three maintenance notices. This is not an independent security audit or a claim of major-wallet security/performance parity. Kotlin/JCA/FFI temporary copies cannot all be reliably erased.

## APK and update

- Package: app.independentwallet.android.resumetest; label: Independent Wallet Preview
- Version code 9; version 0.7.0-network-preview
- Minimum API 28; target/compile API 36; ABIs arm64-v8a and x86_64
- Size: 59,371,000 bytes
- APK SHA256: e1f1b730c0acdfa14cdd0db99901027cc7f8dc482b62de9f0d9249ca6c01c4a4
- Certificate SHA256: 94cb8ea12bfcdc87c79c98397efba912c7a3ba1096360ad9b351a5c1d9af0c51

The package/signing identity matches v0.6.x, so this APK updates that channel in place. Keep app data when updating; do not uninstall. Existing wallet/setup state and the saved node are preserved. The debug signing key is private and excluded from source. Pre-0.6 preview channels retain separate identities/storage. This remains a development preview.

Tools: Rust 1.99.1, Temurin JDK 17.0.21+9, Gradle 8.14.6, AGP 8.10.0, Kotlin 2.1.0, SDK/build tools 36 and NDK 28.2.13676358. Downloaded tool distributions were checked against published digests.

## Remaining checks and limits

Ten Android instrumentation cases compile, including real Keystore endpoint persistence, node-type UI switching and disabled access before setup completion. Zero device cases were executed. Physical installation/update, GrapheneOS network-permission errors, live Tor bootstrap/onion/HTTPS connections, destination-DNS and cancellation captures, screen-off behavior and physical Keystone pairing remain pending. Host integration uses a real isolated Monero node and does not substitute for those tests.

Software wallets still use stagenet. Keystone mainnet/stagenet viewing is supported; hardware signing and verified external key-image import are unavailable, so received-output totals can include spent funds/change. Foreground sync pauses on lock/background/inactivity. Restores may scan from genesis; no speed benchmark or automatic history skipping was introduced. Node credentials, more accounts/subaddresses, incoming mempool and background scanning remain future work. Existing cache failures/pending-payment reservations are not silently erased.

## Delivery

Source contains per-file SHA256SUMS and excludes signing keys, credentials, wallet files, native binaries and build caches. ZIP CRCs, every manifest hash, exclusions and runtime/test source identity against the clean build input were verified. Prior records remain in VALIDATION_0_6_1.md and older files. The report combines NETWORK_ACCESS.md with this record and appends artifact digests outside the source tree.
