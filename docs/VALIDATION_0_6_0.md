# Validation — 0.6.0 resumable setup preview

Executed 23 October 2026 (America/New_York). Created wallet setup is encrypted before keys are displayed. Leaving the app hides the keys and pauses setup; authenticated Resume setup restores the wallet, backup stage, original verification challenge and checkpointed partial answers. Copied phrases have no app timer or exit/lock cleanup. Clear copy remains an explicit ownership-checked action. SETUP_RESUME.md describes the boundaries.

**Software spending remains stagenet only. Keystone mainnet support remains view-only. Hardware signing and verified external key-image import remain unavailable.** No node defaults, automatic discovery/failover, direct-network fallback, seed derivation or transaction engine changes were introduced.

## Executed checks

| Check | Actual result |
| --- | --- |
| Rust scripts/check.sh | Formatting, 65 tests passed, zero failures; all-target Clippy with warnings denied passed |
| New native checkpoint coverage | Both seed formats restore the same wallet and original unfinished challenge; completed export/networking denied until verification; malformed/oversized records rejected; view-only authority preserved |
| JVM | 36 passed; zero failures, errors or skips, including two tests through the actual native bridge |
| Encrypted setup coverage | Authenticated reopen, writes after the authentication window, close/reopen, tampering/truncation, strict step/answer parsing, failed writes, committed writes with lost replies, stale overwrite/delete rejection |
| Clipboard coverage | Retention across controller recreation, explicit clear, foreign replacement preservation, unavailable clipboard and uncertain replies |
| Existing bridge coverage | Seed/import, ownership, mainnet software rejection, hardware signing rejection, QR/Keystone import and receiving-address checks remain passing |
| Python | Four tests passed: source exclusions, isolated native-build environment and two APK-integrity regressions |
| Native | Clean arm64-v8a/x86_64 release builds, host library and regenerated bindings; packaged Rust libraries match independently stripped clean compiler outputs exactly |
| Android | Clean isolated app/instrumentation APK assembly, JVM tests and lint succeeded: 77 tasks executed in 2m14s |
| Lint | Zero errors, seven warnings: one API-level attribute warning, five newer-version notices and one SharedPreferences KTX style suggestion |
| App integrity | Five valid DEX files, 15,453 distinct classes; lengths, SHA1/Adler checksums, duplicates and ZIP CRC checked |
| Instrumentation integrity | Three valid DEX files, 1,000 distinct classes; structural checks passed |
| Signing/alignment | APK v2 signature, 16 KiB ZIP alignment and all ten native libraries' ELF LOAD alignment verified |
| Android device execution | Seven instrumentation cases compiled; zero cases executed on a device |

The separately ignored real Monero integration case was not rerun for this milestone. Its previous successful 67.22-second run is recorded in VALIDATION_0_5_0.md and is historical evidence only. The present change exercises native offline checkpointing and application persistence, not live public-mainnet/Tor operation. No test-only loopback transport is compiled into Android.

Entropy smoke/failure tests do not estimate min-entropy or prove unpredictability. Existing OsRng and standard seed derivations remain unchanged. This update introduces no new random-number generator.

## Persistence and exposure

The setup checkpoint has a separate authenticated Android Keystore wrapping key, a random per-draft AES-256 data key and the existing AES-GCM envelope. AtomicFile stores only bounded encrypted records in noBackupFilesDir. A live encryption key may finish a queued pause write after the display/native owner lock; reopening requires authenticated unwrap again. Stale leases cannot overwrite/delete a newer record. The unfinished backup flag/challenge survives restart and cannot be bypassed through the completed-wallet constructor.

Normal backgrounding saves the current step and partial verification answers. Sudden process termination before the final checkpoint can lose the latest partial edit, while the created wallet remains in its earlier encrypted checkpoint. Unsubmitted import fields and incomplete camera scans are outside the created-wallet persistence guarantee. Storage failure, app-data deletion, device loss and Keystore invalidation still require an offline backup. Physical-device credential, screen-off and lifecycle behavior has not been executed here.

Clipboard preferences contain only a random nonsecret owner token, never phrase text. The app does not clear a copied seed on exit, lock, page closure or a timer. Android may independently clear the clipboard, and other applications may replace or retain its contents. Explicit Clear copy preserves a known foreign replacement; ownership check and clear are not atomic Android operations. Kotlin/JCA/FFI copies cannot all be reliably erased. Older timed-cleanup behavior is superseded by SETUP_RESUME.md.

Permissions remain INTERNET, HIDE_OVERLAY_WINDOWS, CAMERA and the application-scoped AndroidX receiver permission. Camera scanning remains local. Hardware setup exposes no spending-key or seed-view/copy action; a normal Keystone connection QR intentionally supplies a private view key to the companion.

## Dependencies and sensitive-data checks

No third-party dependency was added or upgraded. Rust lockfile package name/version/source tuples match v0.5.0. This milestone did not rerun a complete RustSec/OSV audit. DEPENDENCY_REVIEW.md retains the earlier rsa 0.9.10 RUSTSEC-2023-0071/CVE-2023-49092 finding and three maintenance notices; they remain unresolved. Previous database results are historical, not a current clean-audit claim.

Gitleaks 8.30.1 default rules with redaction reported zero findings in the final APK's extracted DEX/native strings. Public spend/view vectors and test-only wallet/clipboard/QR markers were checked absent. The source scan reported two inspected false positives: historical prose in docs/CODE_REVIEW.md line 55 and a public archive checksum in vendor/UPSTREAM.md line 11. No allowlist was added; the source scanner's nonzero finding status is retained. These checks cannot detect every possible secret.

## APK and build tools

- Package: app.independentwallet.android.resumetest; label: Independent Resume Test
- Version code 7, version 0.6.0-resume-preview; minimum API 28, target/compile API 36
- ABIs: arm64-v8a and x86_64; size: 59,305,456 bytes
- APK SHA256: 79f3ddca4557ecf81cc651dc5c3a33bee661107bf3537f53b071bc67375284b3
- Preview certificate SHA256: 94cb8ea12bfcdc87c79c98397efba912c7a3ba1096360ad9b351a5c1d9af0c51

This is a separate preview installation with separate wallet storage. The prior private preview signing key was unavailable after workspace maintenance; signing keys were deliberately excluded from saved source. This APK therefore installs beside the previous preview and is not an in-place update or automatic migration. Keep the previous installation and its data. Pair Keystone again or import a written stagenet software backup in this preview. The new private signing key is excluded from deliverables. Production needs a protected developer-owned signing workflow.

Tools: Rust 1.99.1; Temurin JDK 17.0.21+9; Gradle 8.14.6; AGP 8.10.0; Kotlin 2.1.0; SDK/build tools 36; NDK 28.2.13676358; cargo-ndk 4.1.2; UniFFI 0.32.2. Downloaded tool distributions were checked against published digests. Final compilation used clean temporary directories to avoid stale/corrupt managed-workspace caches. Android's normal native symbol stripping accounts for the difference from compiler output; independently applying the NDK strip tool produced byte-for-byte packaged matches.

## Remaining execution

Seven instrumentation cases compile, including created-wallet copy/exit/reopen and updated package/Keystore cleanup. **Zero Android device cases executed.** Installation/loader, Android/GrapheneOS clipboard retention, credentials/cancellation/retry, screen-off pause writes, Keystore invalidation, secure-window and camera behavior need physical-device execution. Host tests and APK compilation do not prove those platform behaviors.

Physical Keystone pairing and live Tor routing/destination-DNS/cancellation captures remain outstanding. Hardware spending, authoritative spent-balance sync, more account coverage, non-derived imported key pairs, background sync and independent production review remain future work. No major-wallet security/performance parity or absence of vulnerabilities is claimed.

## Delivery

Source includes a per-file SHA256SUMS manifest and excludes native binaries, build caches, signing keys, credentials, SDK paths and wallet files. ZIP CRCs, every manifest hash, exclusions and runtime-source identity against the clean build input were verified. Earlier results remain in VALIDATION_0_5_0.md and older validation records. The delivery report combines SETUP_RESUME.md with this record; final artifact digests are appended outside the source tree.
