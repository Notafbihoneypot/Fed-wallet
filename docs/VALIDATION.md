# Validation — Independent Preview 2, 0.11.10

29 October 2026 (America/New_York). Package `app.independentwallet.android.syncpreview2`, version `0.11.10-preview2`, Android code 28, min SDK 28, target SDK 36. Rust source/dependencies and native wallet libraries are unchanged from 0.11.9.

## Fixes and limits

The previous Send sheet had no camera scanner. It now includes **Scan address QR**, using the existing local CameraX/ZXing pipeline. It accepts raw Monero receiving addresses and supported `monero:` payment URIs, fills the destination and optional exact decimal amount, and clears Max. It never starts preparation or publishes a transaction on scan. Native preparation still verifies the full address and checksum before a transaction can be approved. The parser rejects duplicate/unknown parameters, wallet-import fields, separate payment IDs, malformed text, different-network prefixes, precision beyond 12 decimals and amounts exceeding uint64 atomic units. Optional recipient names/descriptions are parsed but do not change the recipient or serve as identity proof. Camera permission denial offers paste and Android-settings recovery; capture closes on stop/disposal. Starting the camera terminates competing NFC reads.

The previous biometric code updated AES-GCM AAD before BiometricPrompt authenticated its per-use Keystore cipher. AAD is itself a Keystore operation on Android, so it now occurs only after the successful CryptoObject callback. The existing wrapping format and AAD bytes remain compatible. Strong biometrics and per-use CryptoObject checks are retained. Sensor capability checks and closed error messages preserve PIN fallback. Attempt tokens ignore stale callbacks after cancellation, backgrounding or a newer prompt. Re-enrollment after an abandoned attempt replaces only an unsaved biometric alias, under the current normal-PIN security grant; it never silently replaces a saved wrapper. Fingerprint-enrollment invalidation directs the user to unlock with app PIN, disable biometrics, authenticate again and re-enable. These are source-demonstrated corrections; without a supplied error trace they do not establish the precise cause of every failure on the user's phone.

This is a development preview, not an independent security audit. Physical Pixel/GrapheneOS fingerprint and camera behavior, NFC radio operation, Keystone and live transactions are not established by host compilation. Keep a verified recovery backup and test without funds. Duress remains opt-in and must never be exercised on real user data for testing.

## Executed validation

- A final clean Android build passed assembly, instrumentation Kotlin compilation and lint. Lint: zero errors, 11 warnings. An earlier incremental build encountered stale cache/output metadata; the final clean build regenerated outputs with incremental Kotlin compilation disabled.
- Nine targeted host tests passed with zero failures/errors/skips: three payment-request parser cases, one image-decoder case covering all four QR rotations and blank frames, three security-authorization cases and two NFC protocol cases. The image test calls the same decoder as the live camera; it does not prove physical camera permissions/focus or phone hardware.
- Java/native debugging and Android backup are disabled. APK ZIP/DEX/unique-class checks passed: two DEX files and 15,966 classes. APK ZIP alignment passed; all 28 ELF LOAD segments meet at least 16-KiB alignment.
- Packaged arm64-v8a and x86_64 Rust libraries match the prior signed APK byte for byte. All Cargo manifests, lockfile entries, Rust and vendored protocol sources match the saved 0.11.9 source. Rust tests were not rerun for this Kotlin-only change; previous test results are historical, not new validation.
- Signer certificate SHA256: `01afd9a36efbb0d6573d8f12764164a14af3d99faa4a97ccfce45dff82cd52b5`, the retained Preview 2 identity. APK: 55,650,160 bytes, SHA256 `917822275d02ac2f7aca0da7383894c655f04b3af8fc17febff81c62eb5a7d8f`.
- A guarded emulator-only fingerprint UI test is compiled to exercise enabling a real CryptoObject wrapper, locking, fingerprint unlocking and cancellation back to PIN, using only a public disposable wallet fixture. No physical-device success is claimed. The fresh emulator booted very slowly, then its lock-settings service died with Android DeadObjectException during initial test-PIN setup. No enrolled-fingerprint test executed and no biometric runtime pass is claimed for this build. This emulator setup failure is not evidence that the wallet change passes or fails on a physical Pixel.

## Remaining dependency findings

Existing `crypto-bigint 0.5.5` / RUSTSEC-2026-0387 and `rsa 0.9.10` / RUSTSEC-2023-0071 alerts remain. This change does not modify or suppress them and does not run a new whole-dependency audit. See the archived 0.11.9 validation and SECURITY_0_11_6.md for dependency reachability limits. No blanket security or exploit-free claim is made.

## Install and retest

Use the update only in the Preview 2 channel; keep recovery backups. Do not uninstall or clear the current app. If the biometric wrapper was invalidated, use the app PIN and disable/re-enable biometrics through authenticated Settings. In Send, select **Scan address QR**, grant camera access and scan the recipient's address/payment QR. Check the complete destination, amount and fee, then authorize normally. A scan never proves the recipient's identity. The old Sync Preview package is separate and its data must remain intact.

Primary implementation references: Android biometric documentation https://developer.android.com/identity/sign-in/biometric-auth and Keystore AAD streaming https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/keystore/java/android/security/keystore2/AndroidKeyStoreAuthenticatedAESCipherSpi.java ; Monero payment URI fields https://www.getmonero.org/resources/developer-guides/wallet-rpc.html#make_uri .
