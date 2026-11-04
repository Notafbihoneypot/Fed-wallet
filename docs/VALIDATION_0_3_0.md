# Validation — 0.3.0 sync/spend integration

Executed 21 October 2026. This is development verification of a stagenet-only Android wallet, not an independent audit or production release. The earlier offline-only results are retained in VALIDATION_0_2_1.md.

## Scope and corrections

The integration adds a Rust monero-oxide wallet adapter, embedded Arti transport, encrypted scan state and pending-payment journal, local software signing, and a redesigned Compose dashboard. Offline creation/import, software/view-only separation and user-selected nodes remain in place.

Review corrections include mode-separated caches; no destructive cache reset; durable reservations before broadcast; cancellation after storage failure; exact-byte retries; rescan/reorg reservation retention; bounded HTTP responses and routes; no direct transport or destination DNS; and owner/child revocation during cache initialization. Heavy initialization runs off the UI thread without holding the offline owner's lock. The initial send sheet closes when the immutable payment review opens.

The Android vault flow briefly handles a plaintext Rust snapshot in a Kotlin byte array for Keystore encryption. Imported-key snapshots contain private keys. The app clears that array in a finally block; JVM/Compose/FFI copies cannot all be reliably erased. The online payment API signs inside Rust and does not export its spending key.

## Executed checks

| Check | Result |
| --- | --- |
| scripts/check.sh, Rust 1.99.1 | Formatting, 52 tests passed, all-target Clippy with warnings denied |
| Rust distribution | Four upstream seed, 13 key, six native session, 19 policy, ten engine/HTTP tests |
| Real Monero integration | Separate ignored-by-default test passed using the verified Monero 0.18.5.4 binary on a fresh offline loopback fakechain |
| Adversarial inputs | Snapshot/key/address/URL/amount mutation cases retained; HTTP redirects/encoding/oversize rejected; signed-byte corruption and mismatched fee/key images rejected |
| Python | Source-packaging exclusions and native-builder credential-environment isolation passed |
| Native builds | Matching arm64-v8a/x86_64 release libraries, host library and regenerated Kotlin bindings built successfully |
| Kotlin/JVM | 21 passed, zero failures/errors/skips; six real host JNA/UniFFI, two QR, four envelope, four auth-window, three creation-gate, two restore-height tests |
| Android | App and instrumentation APKs assembled successfully; five instrumentation cases compiled, zero device cases executed |
| Lint | Zero errors, five warnings (one newer-platform attribute and four newer-dependency notices) |
| APK | v2 signature verified; ZIP 16 KiB alignment and all six native libraries' ELF LOAD alignment passed |
| Permissions | INTERNET, HIDE_OVERLAY_WINDOWS and app-scoped AndroidX receiver permission; no CAMERA |
| Secret checks | Default-rule redacted source and extracted-APK-string Gitleaks scans; public test keys/recovery fixtures and test-only loopback runner strings absent from application APK |

The isolated-node test mines a 1,000-block synthetic history so normal upstream decoy selection can operate. Earlier short fakechain fixtures failed the decoy round limit; production sampling was not weakened to accommodate the test. It scans software and view-only wallets, rejects invalid/stale payments, signs and verifies a payment, simulates a lost accepted broadcast reply, reopens the encrypted cache, retries identical bytes, mines the payment, checks sender/recipient balances and output maturity, pops 11 blocks, and verifies reservations survive reorg and rescan. This test uses an in-process cfg(test) loopback transport; it does not exercise Tor or Android networking.

## Dependencies

cargo-audit 0.22.2 scanned Cargo.lock (586 locked packages) using RustSec commit 4b587f00996ebe5c05a73eaef105ef241e876414, updated 21 October 2026 (1,348 advisories). The raw audit is **not clean**: rsa 0.9.10 reports RUSTSEC-2023-0071 / CVE-2023-49092, with no patched version listed. The vulnerability concerns private RSA operations. This configured Tor client exposes no private RSA operations; wallet signing uses Monero curve keys. Source inspection supports a limited reachability assessment, not removal of the advisory or proof that every dependency is safe. See DEPENDENCY_REVIEW.md for source links and the assessment.

Three maintenance notices remain: basic-toml 0.1.10 (host binding generator), bincode 2.0.1 (locked optional dependency with its feature disabled in the resolved Android tree), and paste 1.0.15 (proc macro used to compile Arti dependencies). No ignore list is used. Normal Android dependency paths and the host bindgen configuration were checked with cargo tree.

OSV's batch endpoint returned no matches for all 56 resolved APK runtime Maven coordinates. That is an advisory snapshot, not a security guarantee. The existing Gradle 8.14.6 checksum pin and build-only JDOM 2.0.6.1 correction remain and were included in the successful complete build. These checks do not constitute an audit of every transitive build or native dependency.

## Toolchain and artifact checks

Rust 1.99.1; JDK Temurin 17.0.20.1+1 (download SHA256 3808d1d15e3ec6bd5b84057fb5d84c33d8a1536a258146bcea2e603fc726e08e); Gradle 8.14.6; AGP 8.10.0; Kotlin 2.1.0; SDK platform/build tools 36; NDK 28.2.13676358; cargo-ndk 4.1.2; UniFFI 0.32.2. Minimum API 28, ABIs arm64-v8a and x86_64.

Monero test archive SHA256 ba2928962c101b609434bf8c1b43ad59ebe96c5525977284ca599d8bf0f02d10 matched the signed published hashes. GPG verification reported a good signature from the published binaryFate fingerprint 81AC591FE9C4B65C5806AFC3F0AF4D462A0BDF92.

Application: `app.independentwallet.android.stagenet`, version code 4, version `0.3.0-sync-stagenet`, label Independent Sync Test. It installs beside the earlier `.foundation` package with separate wallet storage. APK size: 55,079,569 bytes. This universal debug APK contains both supported ABIs and embedded Tor; UI simplicity is not a claim about minimum binary size.

APK SHA256: `da2080d0fc4045b7d6f44c1e60a07dc6c04ab93f8596ee88747e5898f6d4f074`.

Debug certificate SHA256: `448bab8f37ffd7b4aea5c331417ec25a66224cd6a30b375957433b2ebef5138e`. This is a development signature, not a production signing identity. No signing keystore is included in the source/deliverables.

Gitleaks 8.30.1 scanned the packaged source with default rules and redaction. Two alerts were inspected: the published upstream API-tarball SHA256 in vendor/UPSTREAM.md and historical review prose in docs/CODE_REVIEW.md. Both are false positives, not credentials. No allowlist was added; the source scanner's raw exit remains nonzero. The extracted APK-string scan (8.07 MB) reported zero findings. Two public recovery phrases, the fixed spend/view vectors and test-only runner markers were also checked absent. Literal/log review found no new production wallet-key/seed/transaction logging. This does not prove that all secrets or memory copies are discoverable by a scanner.

The source archive contains a per-file SHA256SUMS manifest and excludes native binaries, build caches, local SDK paths, wallet files, signing keys and build credentials. Both source packaging and environment-isolation regression checks passed. A final instrumentation-only correction updated its safety guard to the new `.stagenet` package; its APK was rebuilt, and the application APK hash remained unchanged.

## Device execution and limits

Five instrumentation cases cover seed backup/rotation/backgrounding, stale authentication, view-only/platform protections, credential vault/tampering, and conflicting saved-file state. They require a disposable emulator, clear this package's fixture vault, use public fixture keys/PIN 246810 and refuse physical devices. Their compilation and their execution are reported separately.

**No Android device/emulator tests, live Tor routing/DNS/cancellation captures or hardware signing tests were executed in this integration.** This environment has no /dev/kvm. Host compilation and a loopback fakechain cannot validate the Android loader, screen-lock/Keystore behavior, secure bottom sheets, lifecycle races across OEM devices, Tor reachability or circuit teardown. No sync-speed/battery comparison is claimed.

Mainnet and hardware pairing/signing remain unavailable. Other limits include account 0/first 20 subaddresses, RingCT outputs, no incoming mempool scan or background sync, conservative time-based locks, no node credentials, and no safe abandonment flow for permanently rejected pending transactions. Remote-node consensus/work and malicious-node/eclipse resistance are not independently provided. Further device/Tor validation, coverage-guided fuzzing, independent protocol/application review and release verification are required before enabling mainnet.
