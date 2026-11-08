# Preview 2 signing and installation — 0.11.5

Date: 27 October 2026 (America/New_York). The user explicitly requested a new APK after being told the original signing key was missing. A separate package avoids requiring deletion of the old installation.

## Application identity

- Label: Independent Preview 2
- Application ID: app.independentwallet.android.syncpreview2
- Android version: 0.11.5-preview2, code 23; minimum API 28, target/compile API 36
- Rust core: 0.11.5, unchanged from the tested sync/send candidate
- ABIs: arm64-v8a and x86_64
- New certificate SHA256: `01afd9a36efbb0d6573d8f12764164a14af3d99faa4a97ccfce45dff82cd52b5`
- New signing key: RSA 4096, PKCS12, alias independentwallet-preview2

This is not an in-place update for app.independentwallet.android.syncpreview. The original private key remains unavailable. The old certificate digest 63b5045ee6e8a9de7fe90fb05f18ea9397a248360ed4ee496b16b1519c7f31e4 must never be presented as the signer of Preview 2. The manifest label, application ID, version, signing configuration, permission error label and disposable instrumentation package guards changed; wallet, scanning, payment, transport and Rust dependency code did not.

## Installation and wallet recovery

Install this app beside the old one. Keep the old installation/data until an owner-held recovery backup has been verified and the restored wallet checked. If the old app was already uninstalled, its local encrypted state cannot be copied by this app. Restore using the existing supported seed/private spend-key backup on the correct network; never send that backup to the developer. Creating a fresh wallet does not restore existing funds.

Choose a restore height earlier than the first receipt. A birthday carried by a supported seed can assist, but do not advance a restored older wallet to today's height to hide scan work. Re-enter the user's own node and explicitly grant Network, notifications and background consent as needed. No preset node, migration shortcut, imported app encryption key or platform backup is enabled.

Wallet files, node settings and payment journals are isolated by Android application identity. A seed restore reconstructs keys; it does not transfer the old pending-payment reservation or exact signed-byte journal. Do not initiate simultaneous payments from both copies. Reconcile any earlier uncertain submission with the existing app/node/chain before another send. No automatic old-journal migration or pending-payment abandonment is claimed.

## Durable ownership of the new APK signing key

The encrypted key is delivered separately as `independent-wallet-preview2-signing-key.p12`, and its separate private recovery/password file as `independent-wallet-preview2-signing-recovery.txt`. Save both in protected owner-controlled storage with independent offline backups. Put the password in a password manager separately from the key. They are APK release credentials, unrelated to any Monero seed, private spending key or private view key. Neither key nor password is included in the APK, source ZIP or validation report. Never commit or publish these private backups.

Preview 2 releases through 0.11.10 required this identity. The source can build unsigned using `-PunsignedPreview=true`, then be signed on the owner's computer using SDK apksigner and the retained private key. The signing tool prompts for the password. Reconfirm package, version, alignment, APK integrity and certificate before distributing an update.

Preview 2 still is a development/debug build. APK signing proves artifact identity/integrity; it is not a security audit or evidence of live GrapheneOS/Tor behavior. See VALIDATION.md for executed checks and outstanding device validation.

## 0.11.6 / build 24

The current installer retains this exact package/certificate and updates build 23 in place. It disables Android/Java and native debugging through the existing build type, while keeping minification disabled for the tested code path. The development task is still named assembleDebug; the delivered APK must not be debuggable. A non-debuggable preview is not a production-security certification. See SECURITY_0_11_6.md and current VALIDATION.md.

## 0.11.7 / build 25

This Tor-preparation update retains the same certificate, package, non-debuggable installer settings and existing application data. It installs over Preview 2 builds 23/24 without uninstalling or clearing data. No key rotation or new key is performed.

## 0.11.11 / build 29 replacement identity

The owner reported that the prior Preview 2 private signing key was lost and explicitly authorized a replacement because the installed wallets were disposable tests. Version 0.11.11 keeps package `app.independentwallet.android.syncpreview2` but is signed by RSA-4096 certificate SHA256 `ec1a8a280afe3d0185b6e5a175f226bf7d1f9f93424c020b474d0fa2d7865c15`, using PKCS12 alias `voidkey-release`.

Android cannot treat this as an update to a build signed by the prior key. Uninstalling the prior build deletes its local app data; install 0.11.11 fresh only after accepting that loss. Future VoidKey updates must retain this replacement key. Keep its encrypted `.p12` and password in separate owner-controlled offline backups; neither belongs in source, CI, an APK or a release attachment.
