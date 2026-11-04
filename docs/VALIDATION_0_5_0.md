# Validation — 0.5.0 Keystone companion preview

Executed 23 October 2026. The two setup choices are Software wallet and Hardware wallet (Keystone). Software users generate fresh keys offline on their own phone or import a supported existing seed/private spend key. Keystone's normal Monero/Feather connection QR opens a view-only account; its seed and spending key stay on the hardware. KEYSTONE.md documents the protocol, parser and authority boundaries.

**Mainnet is supported only for Keystone viewing. Software spending remains stagenet only. Hardware signing and verified external key-image import are unavailable.** Received outputs may include spent funds and change, so the total is not labeled as a spendable balance. Hardware Send is disabled. Users must compare the displayed primary address with Keystone before saving; the QR source string is not device attestation.

## Executed checks

| Check | Actual result |
| --- | --- |
| Rust scripts/check.sh | Formatting, 62 tests passed, zero failures, all-target Clippy with warnings denied |
| New Rust coverage | Five parser/UR/authority/restore-height cases; hardware prepare/confirm/retry rejection, network-correct receiving addresses, official mainnet genesis identity |
| Existing Rust coverage | Seed/entropy, session ownership, policy, encrypted cache and transaction verification checks remain passing |
| Real Monero integration | One isolated-node release test passed in 67.22 seconds: hardware transport identity/scanning plus software sign/verify, lost reply, durable reopen/exact retry, mining/maturity and reorg/rescan |
| JVM | 29 passed; zero failures, errors or skips |
| New actual bridge coverage | Mode-preserving snapshots, mainnet software rejection, hardware signing rejection, owner cancellation, redacted errors, restore-height override |
| Dense pairing QR | Actual ZXing bitmap decoding and Rust import at all four quarter-turn orientations |
| Python | Source-packaging exclusion test, isolated native-build environment test and two APK-integrity regression cases passed |
| Native | arm64-v8a/x86_64 release libraries rebuilt after the final FFI change, matching host library and regenerated Kotlin bindings |
| Android | Clean isolated app/instrumentation APK assembly, JVM tests and lint succeeded; 77 tasks executed in 2m33s |
| Lint | Zero errors, one UnusedAttribute warning: enableOnBackInvokedCallback is ignored below API 33 |
| App integrity | Five valid DEX files, 15,428 distinct classes; declared lengths, SHA1/Adler checksums, duplicates and ZIP CRC checked |
| Instrumentation integrity | Three valid DEX files, 988 distinct classes; structural checks passed |
| Signing/alignment | APK v2 signature, 16 KiB ZIP alignment and all ten native libraries' ELF LOAD alignment verified |

The offline loopback-only Monero fakechain uses mainnet genesis. The new hardware account attaches through the actual production mainnet identity check and sees the same received outputs as the software fixture. Only the pre-existing stagenet fixture uses an explicit test-module fakechain bypass. No bypass/direct loopback transport is compiled into Android. This exercises mainnet-format identity and scanning against an isolated node, not a live public-mainnet/Tor session. The ordinary 62-test suite leaves this separately executed integration case ignored; the runner executes it explicitly.

Entropy smoke/failure tests do not estimate min-entropy or prove unpredictability. OsRng and standard seed derivations remain fixed in production. UR's deterministic fragment-selection RNG is unrelated to key generation.

## Dependencies and exposure

OSV returned no matches for all 12 newly locked Rust packages and all 78 resolved Android runtime Maven coordinates. No pre-existing Rust versions were updated. DEPENDENCY_REVIEW.md retains the 22 October RustSec scan: rsa 0.9.10 RUSTSEC-2023-0071/CVE-2023-49092 and three maintenance notices. They are not fixed or suppressed; the audit is not clean. A fresh full cargo-audit scan was not run for this milestone. Database matches/absence are not proof of security.

Gitleaks 8.30.1 default rules with redaction reported zero findings in the final APK's extracted DEX/native strings. Public spend/view vectors and test-only Keystone/wallet/clipboard markers were checked absent. Source-scan and archive results are recorded below. Temporary Kotlin, camera, serde and UR memory copies cannot all be reliably erased.

Manifest permissions are INTERNET, HIDE_OVERLAY_WINDOWS, CAMERA and the application-scoped AndroidX receiver permission. Camera permission is requested at Scan Keystone connection. CameraX preview/analysis and ZXing run locally, with no ML Kit, Google Play services, storage/audio permission or image-upload service. Camera frames are not saved. Four added camera native libraries across the two ABIs passed the same 16 KiB ELF checks.

The normal connection QR exports the private view key to the companion by design. The app validates it in Rust, encrypts it through the credential-gated vault and scans locally. Hardware setup exposes no spending-key/seed import or hardware seed-view/copy action. Software clipboard controls retain the contract in SECURITY_0_4.md.

## APK and tools

- Package: `app.independentwallet.android.companiontest`; label: Independent Companion Test
- Version code 6, version `0.5.0-companion-preview`; minimum API 28, target/compile API 36
- ABIs: arm64-v8a and x86_64; size: 59,223,540 bytes
- APK SHA256: `257a937af71937b9a34d80f54a3fc4c6e2cad0a8dbe40253c00bef9e817386c3`
- Preview certificate SHA256: `8a72b2ff3b015f2abee6a084ece5c733899b1f7b378394d6e235459729302bcb`

This separate debug preview has its own wallet storage and signing identity. The older preview keystore was unavailable after workspace maintenance; it was deliberately excluded from saved source. This APK is not signed as an update to that older preview. Pair Keystone again or import a written stagenet software backup in this channel. The new keystore is private and excluded from deliverables. Production requires a protected developer-owned release-signing workflow.

Tools: Rust 1.99.1; Temurin JDK 17.0.21+9; Gradle 8.14.6; AGP 8.10.0; Kotlin 2.1.0; SDK/build tools 36; NDK 28.2.13676358; cargo-ndk 4.1.2; UniFFI 0.32.2. Downloads were checked against published digests. Monero 0.18.5.4 archive matched the previously verified signed published SHA256 `ba2928962c101b609434bf8c1b43ad59ebe96c5525977284ca599d8bf0f02d10`.

## Remaining execution

Six pre-existing instrumentation cases compile with the new package guard. **Zero Android device cases executed.** Installation/loader, Android/GrapheneOS camera permission/focus/scanning and lifecycle, credentials, Keystore, secure-window and clipboard behavior need device execution. The previous unsuccessful software-emulator attempt was not repeated.

Physical Keystone pairing and live Tor routing/destination-DNS/cancellation captures remain outstanding. The user's successful earlier Keystone transaction was in the earlier C++-backed app and is not counted as validation of this APK. Hardware spending, authoritative spent-balance sync, more account coverage, non-derived imported key pairs, background sync and production review need further work. No independent audit or major-wallet security/performance parity is claimed.

## Delivery

Source has a per-file SHA256SUMS manifest and excludes native libraries, build caches, signing keys, credentials, SDK paths and wallet files. Prior results remain in VALIDATION_0_4_0.md, VALIDATION_0_3_0.md and VALIDATION_0_2_1.md. The delivery report combines KEYSTONE.md with this record.

Gitleaks reported two source findings: historical prose at docs/CODE_REVIEW.md line 55 and the public upstream archive checksum at vendor/UPSTREAM.md line 11. Both were inspected and are false positives, not credentials. No allowlist was added; the raw source scan retains its nonzero status. Final ZIP CRCs and every manifest hash were verified. These checks do not certify that every possible secret can be detected.
