# Validation — 0.6.1 resume and discard preview

Executed 24 October 2026 (America/New_York). Resume setup remains available. Discard setup is now a separate action on paused, backup, verification and unfinished-dashboard screens. It requires an explicit dialog confirmation and Android device authentication, removes only the exact unfinished draft and returns to creation/import. The old seed is not needed. SETUP_RESUME.md describes the flow and limits.

## Executed checks

| Check | Actual result |
| --- | --- |
| Kotlin/JVM | 39 tests passed; zero failures, errors or skips |
| New storage cases | Three tests: discard without decryption, preparation without deletion, stale/reused confirmations, fresh creation, silent deletion failure and lost delete reply |
| Existing actual native bridge | Setup/seed, software/mainnet rejection, hardware authority, QR import and ownership tests passed against a freshly compiled host library |
| Python | Four packaging/build-environment/APK-integrity tests passed |
| Android build | Isolated source build; app and instrumentation APK assembly, JVM tests and lint passed; final run 77 tasks, 18 executed/59 up-to-date, 1m1s |
| Lint | Zero errors; 13 warnings: eleven newer-version notices, one API-level attribute warning and one SharedPreferences KTX style suggestion |
| App integrity | Five DEX files, 15,471 distinct classes; ZIP CRC, declared lengths, SHA1/Adler checksums and duplicates checked |
| Instrumentation integrity | Three DEX files, 1,021 distinct classes; structural checks passed |
| Signing | APK v2 signature verified; certificate and application ID match the v0.6.0 resume preview |
| Alignment | 16 KiB ZIP alignment and ELF LOAD alignment for all ten native libraries verified |
| Native Android core | Unchanged v0.6.0 source and packaged Rust binary digests; independent NDK stripping of the retained compiler outputs matches the APK |
| Android device cases | Nine compiled, zero executed; two new discard cases and added completed-wallet protection assertions |

Rust and vendored source, Cargo.toml, Cargo.lock and the toolchain pin were compared with the delivered v0.6.0 source: 67 files are unchanged. Android Rust libraries were retained from that build; the host library was rebuilt for actual JNA/UniFFI tests. The earlier 65-test Rust run and real-node integration results are historical, not freshly rerun here. No Rust/FFI, protocol, seed-generation or encrypted-record format change was needed.

## Deletion and preservation

Before requesting discard credentials, the app locks the native owner/display and finishes queued checkpoint writes. It captures the encrypted draft's SHA256 fingerprint without decrypting its contents. A current credential ticket may commit only that exact record. The shared setup monitor serializes fingerprint checks, writes and deletion. A changed/replaced draft rejects an old request; a successful request cannot be reused to delete a later wallet. Failed deletion is not reported as successful while the file remains. If a delete reply is lost, the app reconciles actual file existence.

Keep setup and credential cancellation retain the encrypted draft. Ordinary backgrounding still pauses it. A late/expired credential response cannot authorize discard. Unavailable draft decryption does not block deliberate deletion, but unreadable/oversized storage fails closed. New setup begins only after actual removal; a replacement wallet is not generated silently.

Only setup-v1.bin and its AtomicFile companions are deleted. Completed wallet storage is guarded both before preparing discard and before committing it. Node settings, sync/payment caches, the hardware wallet and clipboard text are outside the deletion path. File deletion is not forensic secure erasure. Recovery of the discarded wallet requires its existing recovery phrase/private key or hardware wallet.

Existing setup persistence boundaries remain: normal pauses checkpoint progress, sudden termination can lose the latest partial edit, unsubmitted import fields/incomplete camera scans are not saved wallets, and device loss/app-data deletion/Keystore invalidation still require an offline backup. Copied text has no app exit/lock/timer cleanup; Clear copy is explicit and Android may manage its own clipboard. Settings text now reflects this contract.

## Security scope and scans

Software spending remains stagenet only. Keystone mainnet support remains view-only; hardware signing and verified external key-image import remain unavailable. Node selection and Tor routing policies are unchanged. No dependency declarations or Rust lockfile entries changed.

No fresh complete dependency-vulnerability audit was performed. The previously recorded rsa 0.9.10 RUSTSEC-2023-0071/CVE-2023-49092 and three maintenance notices remain unresolved; see DEPENDENCY_REVIEW.md. No independent audit or major-wallet security/performance parity is claimed.

Gitleaks 8.30.1 default rules with redaction found zero matches in the final APK's extracted DEX/native strings. Public spend/view vectors and test-only fixture markers were checked absent. The final source scan retains two inspected false positives: historical prose in docs/CODE_REVIEW.md line 55 and the public upstream archive checksum in vendor/UPSTREAM.md line 11. No allowlist was added; the raw source scan retains its nonzero status. Scanning does not prove the absence of all secrets or vulnerabilities.

## APK and update compatibility

- Package: app.independentwallet.android.resumetest; label: Independent Resume Test
- Version code 8; version 0.6.1-resume-discard-preview
- Minimum API 28; target/compile API 36; ABIs arm64-v8a and x86_64
- APK size: 59,922,234 bytes
- APK SHA256: f40e57796a201401330564bb9cb669a281e66b25992ffdf3debd0b067b292c50
- Certificate SHA256: 94cb8ea12bfcdc87c79c98397efba912c7a3ba1096360ad9b351a5c1d9af0c51

The package, retained private signing key and encrypted storage formats match v0.6.0. This APK can update that resume preview in place while preserving its data. Do not uninstall or clear app data to update. Pre-0.6 channels use separate identities/storage and remain separate installations. The signing key is private and excluded from source; this is a debug preview, not a production release.

Tools: Rust 1.99.1 (host build only), Temurin JDK 17.0.21+9, Gradle 8.14.6, AGP 8.10.0, Kotlin 2.1.0, SDK/build tools 36 and NDK 28.2.13676358. Downloaded distributions were checked against their published digests.

## Outstanding device execution

Nine instrumentation cases compile. No Android device case was executed. The added cases cover UI/credential cancellation, verify-stage discard/new creation, stale credential rejection and completed-wallet protection; their real Android/GrapheneOS behavior remains to be executed. Physical installation/update, credential prompts, lifecycle/screen-off persistence, Keystore behavior, clipboard retention, camera and secure-window behavior remain device checks. Compilation and host tests do not substitute for those checks.

Physical Keystone pairing, live Tor routing/DNS/cancellation captures and broader production review remain outstanding. Mainnet software spending, hardware spending and authoritative hardware spent-balance sync remain outside this milestone.

## Delivery

The source ZIP contains per-file SHA256SUMS and excludes signing keys, credentials, wallet files, native binaries and build caches. ZIP CRCs, every manifest hash, exclusions and application/test source identity against the final build input were verified. Prior results remain in VALIDATION_0_6_0.md and older records. The report combines SETUP_RESUME.md with this record and appends final artifact digests outside the source tree.
