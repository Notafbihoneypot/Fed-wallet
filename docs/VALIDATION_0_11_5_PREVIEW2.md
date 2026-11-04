# Validation — Independent Preview 2, 0.11.5

Date: 27 October 2026 (America/New_York). Android `0.11.5-preview2`, code 23; Rust 0.11.5.

## Delivered application identity

The user requested a new signed APK after being informed that the original signing key was missing. This delivery deliberately creates **a separate application** with application ID `app.independentwallet.android.syncpreview2`, label **Independent Preview 2**, and a new protected RSA-4096 APK-signing key. It can install beside the old `app.independentwallet.android.syncpreview` app. It cannot update that package or access its isolated encrypted data. No uninstall, clearing of app data, backup-policy change, signature bypass, seed upload or automatic wallet/node/pending-journal migration was performed or added.

The original key is still missing. Its certificate digest is not reused or represented as an authorized signer of this new application. Preview 2 has its own certificate SHA256: `01afd9a36efbb0d6573d8f12764164a14af3d99faa4a97ccfce45dff82cd52b5`.

The protected APK-signing key and separate private recovery/password information are delivered as separate owner-backup artifacts, excluded from the APK, public source archive and this report. Keep the exact key for every Preview 2 update, save independent offline backups and keep the password separately. These APK release credentials are unrelated to Monero recovery keys. See PREVIEW2_SIGNING.md.

## Code scope and inherited validation

This build packages the previously tested 0.11.5 sync/send coordination fix: five-minute routine metadata checks (one minute for pending payments), no block-body rescan for an unchanged tip, payment-entry/review/authentication polling exclusions, idle service retention, consented completion of an already authorized immutable submission, and bounded spent-input catch-up without replacing approved bytes on ordinary new blocks. Reorgs, expiry and spent inputs still reject the review. No new wallet/payment/scan/route algorithm was added while preparing this installer.

Compared with the retained code-22 source ZIP, Rust production/test/vendor sources, Cargo.toml/Cargo.lock and the Rust toolchain pin are byte-identical. Both packaged native wallet libraries are byte-identical to the verified code-22 release ABI payloads. Android changes are application ID, label, version, protected signing configuration, permission-error label, disposable test-package guards and documentation.

The earlier 0.11.5 validation remains in VALIDATION_0_11_5_CANDIDATE.md. Its 93 Rust tests, Clippy/format checks and real offline Monero-CLI fakechain payment case were executed during the core fix and were not rerun for this identity-only packaging change. That isolated case covered normal new blocks during review, no idle block downloads, expired/forked/spent-input rejection, Max/fee shape, exact-byte verification, uncertain submission/reopen/retry, maturity and scan/reorg regressions. This is inherited evidence for unchanged native code, not a claim of new live-network testing.

## Checks executed for this installer

| Check | Result |
| --- | --- |
| Android build | `assembleDebug`, `testDebugUnitTest`, `compileDebugAndroidTestKotlin` and `lintDebug` succeeded. All 58 tasks reran; Kotlin incremental/build cache disabled. |
| Android host/UniFFI | 134 tests in 31 suites passed, no failures/errors/skips; actual previously rebuilt host native library used. |
| Instrumentation | 21 cases compiled against the new disposable application package; 0 executed. |
| Lint | 0 errors; 20 warnings: 11 dependency-version notices, 5 ImplicitSamInstance, 1 UnusedAttribute and 3 UseKtx. These are the same categories as before; more dependency-version notices were available during this run. No dependency was changed and no check was disabled. |
| APK signature | Android SDK apksigner verification passed; APK Signature Scheme v3, one RSA-4096 signer, exact new certificate digest matched the exported protected keystore certificate. v3 covers the app's minimum Android API 28. |
| Signing integrity | Every unsigned payload entry is retained byte-identically; only the APK signature block and standard META-INF signature entries were added. APK contains no signing password. |
| Package metadata | `app.independentwallet.android.syncpreview2`, code 23, `0.11.5-preview2`, label Independent Preview 2; minimum API 28, target/compile API 36. |
| DEX/ZIP integrity | CRC, DEX lengths/checksums and duplicate-class checks passed: 5 DEX files and 15,688 classes. This is not device runtime bytecode verification. |
| Native payload | Both native wallet ABI hashes matched the tested code-22 candidate; original/merged libraries matched, packaged/stripped outputs matched, and fresh NDK stripping produced identical bytes. Release payloads excluded test-only markers. |
| Alignment | All 10 ELF libraries / 28 LOAD segments had alignment at least 16 KiB. `zipalign -c -P 16 4` passed before and after signing. |
| Old installation | A separate package/identity is used; no old app/data was uninstalled or modified by these build operations. Side-by-side installation is supported by package separation, but not executed on a device here. |

## Artifact

- File: independent-wallet-v0.11.5-preview2.apk
- Bytes: 60,341,109
- APK SHA256: `d42685f0fd941bf37100f07e792942be46561e6e7bef30dd7167ad56f95f6944`
- Signing certificate SHA256: `01afd9a36efbb0d6573d8f12764164a14af3d99faa4a97ccfce45dff82cd52b5`
- ABIs: arm64-v8a and x86_64
- ARM64 wallet library SHA256: `e8deb0407c0d9674df322f9a23ad4fbca71bdbd346d3e3e43c44d0550653446d`
- x86_64 wallet library SHA256: `82601a3d498d93befd7235743cdc61c411aa9750a1c6f7cafb2daef1a4eb27fe`
- Installer status: signed APK for a new Preview 2 installation, not an update to the old Sync Preview package

## Recovery and remaining limits

Keep the old wallet and its local data until a verified owner-held backup restores successfully and the wallet is checked. If already uninstalled, restore with the existing supported seed/private-spend backup on the correct Mainnet/Stagenet network, with a scan start before its first receipt. Generating a new wallet does not restore the existing one. Node/permission/background settings need explicit setup in the new app. Never send wallet recovery secrets to the developer.

A seed restore does not transfer the old durable pending-payment reservation or exact signed-byte journal. Do not run simultaneous sends from both app copies. Reconcile an earlier uncertain send before issuing another payment. No automatic pending-payment abandonment or migration is added.

No phone/emulator installation, GrapheneOS Keystore/notification/lifecycle execution, screen-off/return test, live Tor throughput/routing check or hardware execution was performed. A valid APK signature and host tests do not prove every reported Tor send failure is resolved. The fixes need device validation. This remains a developer/debug preview, with no independent security audit or production-readiness claim. Existing one-recipient/16-input/0.05-XMR-fee limits, incoming mempool/time-lock/hardware/key-image/abandonment coverage limits remain. Five manually added nodes, explicit routes, no presets/failover/TLS downgrade, existing encrypted formats and disabled platform backup remain.
