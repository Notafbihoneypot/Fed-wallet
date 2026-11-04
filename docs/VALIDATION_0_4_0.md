# Validation — 0.4.0 retro and seed privacy

Executed 22 October 2026. Development verification of a stagenet preview. The selected Monero/Feather source comparison, RNG interpretation and clipboard contract are in SECURITY_0_4.md. Earlier results are retained in VALIDATION_0_3_0.md and VALIDATION_0_2_1.md.

## What changed

The original Compose interface now uses dark charcoal, green/amber accents, monospace headings, stepped shapes and a pixel wallet icon. No runtime library was added. Explicit seed copying uses Android's sensitive-preview metadata, an ownership marker and a best-effort 30-second cleanup lease. Saved seed viewing requires a fresh device-credential request and expires after 60 seconds. An open wallet locks after five minutes without interaction, with elapsed-time checks at interaction/resume boundaries.

Switching apps locks the wallet and clears its display, while an explicitly authorized clipboard lease briefly remains for pasting. Screen-off/explicit lock/closing the seed page requests earlier clipboard cleanup. Other apps can retain copies, and Android may block background cleanup or suspend/kill the process. New-wallet setup is discarded when backgrounded; a copied backup can be imported when returning. Kotlin/Compose/UniFFI/clipboard copies cannot all be erased reliably. These tradeoffs are shown before copying.

## Passed checks

| Check | Actual result |
| --- | --- |
| Rust scripts/check.sh | Formatting, 55 tests, zero failures, all-target Clippy with warnings denied |
| Rust distribution | Four upstream seed, 16 key/entropy, six native session, 19 policy and ten engine/HTTP tests |
| New entropy coverage | Both formats fail closed after partial RNG read errors; one-MiB OS-RNG smoke sample; 64 fresh/restored production wallets per format, 128 distinct addresses |
| Real Monero integration | Release-mode isolated-node scan/sign, signed-byte/fee/key-image mutation rejection, lost broadcast reply, durable reopen/exact retry, mining/maturity, 11-block reorg and rescan test passed |
| Kotlin/JVM | 26 passed; zero failures, errors or skips |
| New JVM coverage | Three clipboard ownership/expiry/uncertain-reply cases and two inactivity deadline/clock-regression cases |
| Python | Source-packaging/private-file exclusions, native-builder environment isolation and two APK-integrity regression cases passed |
| Native builds | arm64-v8a/x86_64 release libraries, matching host library and regenerated UniFFI Kotlin bindings built |
| Android | Clean app and instrumentation APKs assembled; 77 build/check tasks executed successfully |
| Lint | Zero errors, five warnings (one older-platform attribute and four newer dependency notices) |
| APK structure | Five valid DEX files, 12,892 distinct classes; declared lengths, SHA1/Adler checksums, ZIP CRCs and duplicate-class checks passed |
| Instrumentation APK structure | Three DEX files, 991 distinct classes; structural checks passed |
| APK signing/alignment | v2 signature, 16 KiB ZIP alignment and all six native libraries' ELF LOAD alignment verified |
| Permissions | INTERNET, HIDE_OVERLAY_WINDOWS and the app-scoped AndroidX receiver permission; no CAMERA |

A malformed extra DEX file was discovered in the first incremental APK: its declared size exceeded the bytes stored in ZIP. It was not delivered. A clean build in an isolated temporary directory corrected the artifact; all app/JVM/lint checks were rerun. The new structural checker rejects the observed truncated prefix, modified data and duplicate classes, and is required by assembleDebug. It complements signing checks; it is not Android's complete bytecode verifier. The final source tree's stale generated outputs were replaced with the verified artifacts.

The one-MiB test deliberately has liberal statistical bounds. It catches gross defects; it cannot establish min-entropy, cryptographic unpredictability or the state of a particular Android phone. No production diagnostic score or claim of maximum randomness is added. The production entry point remains fixed to the OS CSPRNG and standard seed derivations.

## Dependencies and scan scope

cargo-audit 0.22.2 scanned 586 locked packages against RustSec commit 640bf5a9f06e66d8721e8772766531fe77c8630d (1,349 advisories, fetched 22 October 2026). Result: one vulnerability advisory, RUSTSEC-2023-0071 / CVE-2023-49092 in rsa 0.9.10, plus three unmaintained notices (basic-toml, bincode, paste). No advisory ignore list is used and the audit is not clean. The reviewed configured Tor client uses public RSA verification, with no application RSA-private-key API; this is a scoped reachability assessment, not a patch or proof of safety. Details and primary links are in DEPENDENCY_REVIEW.md.

OSV returned no matches for all 56 resolved APK runtime Maven coordinates. The Gradle 8.14.6 checksum pin and JDOM 2.0.6.1 build-only correction remain in the passing build. Every native/transitive build dependency has not been independently audited.

Gitleaks 8.30.1 scanned the packaged source with default rules and redaction. Two findings were inspected: the published API-tarball checksum in vendor/UPSTREAM.md and historical review prose in docs/CODE_REVIEW.md. Both are false positives, not credentials. No allowlist was added, so the raw source scan retains its nonzero exit. The final extracted-APK-string scan reported zero findings. Fixed public spend/view vectors, two public recovery-phrase fixtures and test-only runner/clipboard-fixture markers were checked absent from the application APK.

The source ZIP has a per-file SHA256SUMS manifest. ZIP CRCs, manifest hashes and private-file exclusions were verified. Native libraries, build caches, SDK paths, wallet files, signing keystores and build credentials are excluded. Scanner results are not proof that every possible secret or memory copy can be detected.

## Artifact and toolchain

Application: app.independentwallet.android.stagenet.retro, version code 5, version 0.4.0-retro-stagenet; label Independent Retro Test. It installs beside prior previews and has separate wallet storage. The local preview signing identity is retained privately for this channel and excluded from archives; production needs a developer-owned protected release key.

APK size: 55,128,797 bytes. Universal debug APK, arm64-v8a and x86_64; minimum API 28, target/compile API 36.

APK SHA256: df5d536776b236e41d34f4c7e38f7327d96526082761feec9a1a24983fe7f3bc.

Debug certificate SHA256: 890933159f52bfb690843a1c0b97043030f768f4ceb0479aba3932ae7f5985d0.

Rust 1.99.1; Temurin JDK 17.0.20.1+1; Gradle 8.14.6; AGP 8.10.0; Kotlin 2.1.0; SDK/build tools 36; NDK 28.2.13676358; cargo-ndk 4.1.2; UniFFI 0.32.2.

Monero 0.18.5.4 test archive SHA256 ba2928962c101b609434bf8c1b43ad59ebe96c5525977284ca599d8bf0f02d10 matched the previously verified signed published digest. The runner creates a fresh offline loopback fakechain with a 1,000-block synthetic history. Its direct transport is cfg(test) only and is absent from the application APK. It does not exercise Arti or Android networking.

## Android execution and remaining limits

Six instrumentation cases compiled: the previous five offline/vault cases (including the new authenticated recovery-view assertions) and an Android clipboard metadata/ownership case. **Zero device cases executed.** This host has no /dev/kvm. An official Android 35 AOSP image with emulator 37.3.5 was attempted in software/TCG mode but did not become ADB-ready; the emulator was stopped. Compilation and host tests do not verify Android/OEM/GrapheneOS clipboard access/preview, credential UI, Keystore, loader, secure dialogs/sheets or lifecycle behavior.

Live Tor destination-DNS/routing/cancellation captures, physical/hardware-device tests and independent review remain outstanding. Mainnet and hardware signing stay disabled. Other integration limits are recorded in SYNC_SPEND.md, including RingCT/account/subaddress coverage, conservative time locks, remote-node trust and pending-payment abandonment. No speed/battery benchmark or security parity with a major wallet is claimed.
