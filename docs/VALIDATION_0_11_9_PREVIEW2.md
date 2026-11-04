# Validation — Independent Preview 2, 0.11.9

Build date: 28 October 2026 (America/New_York). Android package `app.independentwallet.android.syncpreview2`, version `0.11.9-preview2`, code 27; the Rust core and native payloads are unchanged from 0.11.8.

## Status

This is a signed, device-unverified development preview. Use a test wallet with no funds and do not rely on the duress feature. PIN migration and duress clearing have limited disposable-emulator coverage, but biometric unlock, physical NFC communication, GrapheneOS devices, Keystone and live transactions have not been validated. Keep an independently verified seed backup before any wallet migration or update. Do not uninstall or clear existing wallet apps.

## Changes

- The receiver may explicitly share the selected public address by NFC. A sender running a compatible copy may explicitly read it into Send, enter an amount, review the destination and fee, and authorize the normal transaction. This is not payment completion, Goblin-card support, banking-terminal support, or general wallet interoperability.
- A temporary in-memory offer uses Android NFC host card emulation and a private versioned AID. It is limited to one successful read or 60 seconds, ends when the app pauses or the screen turns off, and requires device unlock. Sender reader mode is foreground-only. The protocol bounds input, checks network and base58-shaped data, and does not carry keys, transaction bytes or credentials. Existing native payment preparation checks the Monero address. NFC does not authenticate the other phone; users need to verify the destination independently.
- PIN migration now obtains the file's own lock before the security authorization lock, and holds the latter across each conversion and readback. Revocation and migration commits therefore serialize, while setup-file operations retain a consistent lock order.

## Build and validation

- Android debug assembly, instrumentation APK compilation, and lint completed successfully; lint reported zero errors and 14 warnings.
- 16 targeted Android host tests passed: 2 NFC request parser tests, 3 security authorization tests and 11 setup-store tests. These are host tests; no claim is made that they prove an Android Keystore, biometric, actual NFC radio or wipe flow.
- A disposable API 30 x86_64 emulator ran 2 NFC service/APDU tests successfully after correcting the test harness to instantiate the Android service on the main thread; physical radio communication was not tested. Four substantive PIN/storage cases passed: separate vaults and PIN change; revoked authorization; duress recognition during normal-PIN lockout; and refusal to replace an absent PIN configuration when an encrypted backup exists. A fifth case only prepared a disposable fixture. The initial legacy migration case failed because the Android credential had expired; rerunning it after a fresh device unlock passed (1 test). An actual duress PIN was then entered through an automated UI test against a public test fixture; the app process ended and a separate assertions-only test passed, checking that the fixture wallet files, app preferences, pending marker and app-prefixed Keystore aliases were gone. A second fixture staged a pending-wipe marker, restarted the app, and passed the same assertions, demonstrating startup recovery for that staged state. The earlier slow manual UI attempt failed its clear-data assertion; it is not counted as a pass. These tests did not demonstrate erasure of every possible file or interrupted phase, behavior on GrapheneOS, biometric handling, other-app isolation, or forensic overwrite.
- The previous 0.11.8 run reported 106 Rust tests passing, 1 ignored, and 152 Android host tests passing; those are historical results for that source state, not a new full-suite run. No Rust code was changed in this candidate. Packaged arm64-v8a and x86_64 Rust libraries match the signed 0.11.8 preview byte for byte.
- APK ZIP/DEX/unique-class checks passed (2 DEX files, 15,938 classes), ZIP alignment passed, and all 28 ELF LOAD segments are at least 16-KiB aligned. Backup and Java/native debugging are disabled.
- Signed APK: 55,617,392 bytes, SHA256 `c68dad4152bfa2b432bd69e1bcbfe5cfbd0cb6106cc9a478633543e7bd9b9f91`. Certificate SHA256 `01afd9a36efbb0d6573d8f12764164a14af3d99faa4a97ccfce45dff82cd52b5`, the existing Preview 2 signer. The older Sync Preview app is a separate package.

## Unresolved security advisories

The two known RustSec alerts remain. `crypto-bigint 0.5.5` / RUSTSEC-2026-0387 requires a patched, incompatible newer release; the current Monero dependency requires the old API. `rsa 0.9.10` / RUSTSEC-2023-0071 has no published patched version. Prior source review found the app configures Arti as a Tor client and did not identify an RSA private-decryption/signing application path, but that is not proof of non-reachability. These dependencies were not silently replaced or labeled fixed. See SECURITY_0_11_6.md and DEPENDENCY_REVIEW.md. No independent full security audit is claimed.

## Required follow-up

On disposable, genuinely unlocked Android devices, test migration of a legacy wallet and interrupted migration, PIN changes and lockouts, biometric enrollment changes and fallback, actual duress erase and pending-wipe restart recovery. Test receiving and sending between two physical NFC-equipped Android phones, including GrapheneOS with NFC enabled, screen-off/cancel/address changes and different networks; verify that NFC alone never signs or publishes. Validate recipient addresses and confirm payment through the selected Monero node. Use test funds for any live network, Tor or Keystone tests.
