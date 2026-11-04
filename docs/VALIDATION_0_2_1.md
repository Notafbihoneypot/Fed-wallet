# Validation — security review 0.2.1

Executed 21 October 2026. Development verification of the offline/stagenet wallet.

## Passed

| Check | Result |
| --- | --- |
| scripts/check.sh, Rust 1.99.1 | Formatting, 40 tests, all-target Clippy with warnings denied |
| Rust test distribution | Four upstream seed, 13 key, four session and 19 policy tests |
| Mutation corpus | 12,000 arbitrary/structured snapshots, 4,096 view-snapshot mutations, 20,000 URL/amount cases; additional boundary/address/key checks |
| Kotlin/JVM tests | 17 passed; zero failures, errors or skips |
| JVM distribution | Four envelope, four authentication-window, three shared-creation gate, two restore-height, four real host-native bridge tests |
| Python checks | Packaging/private-file exclusions and native-builder environment isolation |
| Native build | arm64-v8a and x86_64 release libraries, matching host library, regenerated Android-mode Kotlin bindings |
| Android build | Debug app and instrumentation APKs assembled; lint zero errors, three warnings |
| APK checks | v2 signature, ZIP 16 KiB alignment, all six native libraries' 16 KiB ELF load alignment |
| APK permissions | HIDE_OVERLAY_WINDOWS and app-scoped AndroidX receiver permission; no INTERNET or CAMERA |
| Secret checks | Redacted Gitleaks 8.30.1 default-rule scans; literal/log/storage review; public seed/key fixtures absent from APK |
| Provenance | Recorded API tarball SHA256 matched; 23 upstream wordlists identical |

The vault correction has host serialization tests and a compiled Android save-conflict regression. AtomicFile/Keystore/device lifecycle behavior has not been device-executed.

The final source scan raised two benign generic-api-key alerts. One was review prose about future Tor testing; the other was the published upstream archive checksum. The exact source fragments were inspected, and the checksum matched the downloaded archive. Neither is a credential. No scanner allowlist was added; the raw default-rule scan exits nonzero and requires this documented triage.

## Dependencies

cargo-audit 0.22.2 used RustSec commit 4b587f00996ebe5c05a73eaef105ef241e876414, updated 21 October 2026, 1,348 advisories. Result: zero known vulnerabilities and one informational unmaintained notice, basic-toml 0.1.10 / RUSTSEC-2026-0374. The package belongs to optional host binding generation; the normal Android library tree excludes it. No advisory is silently ignored.

OSV returned zero matches for 55 resolved APK runtime Maven coordinates. Gradle advisory ranges were checked separately; wrapper/distribution updated and checksum-verified at 8.14.6.

The build-tool JDOM 2.0.6 matched CVE-2021-33813 and was subsequently pinned to 2.0.6.1. A separate successful Gradle configuration run verified the patched root selection; the checked lint configuration contained no separate matching module. Its JAR matched Maven Central SHA256, normal/application XML and blocked-entity smoke checks passed, and OSV had no matches for 2.0.6.1. This build-only pin followed APK assembly; application Kotlin/Rust sources did not change after the passing application build. Full APK assembly was not repeated after the pin.

Advisory checks are component checks, not proofs of exploitability or absence of flaws. Every build-plugin transitive dependency and native implementation was not independently audited.

## Toolchain and APK

JDK 17.0.20.1; Gradle 8.14.6; AGP 8.10.0; Kotlin 2.1.0; SDK 36/build tools 36.0.0; NDK 28.2.13676358; cargo-ndk 4.1.2; UniFFI 0.32.2. Minimum Android API 28, ABIs arm64-v8a and x86_64.

The complete application build ran in an isolated temporary project after runner cache corruption. All application test/build tasks executed. Lint warnings concern an older-platform attribute and newer available dependency versions. Gradle reports future Gradle 9 migration notices; this project pins 8.14.6.

APK: app.independentwallet.android.foundation, version code 3, version 0.2.1-offline-stagenet; 28,783,225 bytes.

APK SHA256: dd18f61ced0e1dfeae9b4987d61132f554550c11adf006270b3ef79b79dc974b.

Debug certificate SHA256: b1c34860bcac72c65ccab52e1006dd3e9095bde7a7f1778ecf2abc40ad81518d. It differs from the prior APK; this APK cannot update that installation in place. Use a separate disposable emulator and retain existing data/backups.

## Device execution and remaining coverage

Five instrumentation cases compiled: seed/backup with rotation/backgrounding; stale authentication; view-only/platform protections; credential vault/tampering; conflicting saved-file state. Zero device cases executed in this review. The suite requires a disposable emulator, clears its fixture vault and uses PIN 246810. It refuses physical devices.

Remaining: Android loader/Keystore/OEM lifecycle execution, physical-device/hardware tests, FFI secret-copy review, coverage-guided fuzzing, independent audit, release verification and the future Tor, scanning/signing and local verified key-image engines. No sync-speed, battery, mainnet-spend or hardware-signing result is claimed.
