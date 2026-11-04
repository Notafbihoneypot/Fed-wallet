# Seed and privacy review — 0.4.0

Reviewed 22 October 2026. Scope: the seed-generation data path, seed exposure/authentication, clipboard ownership/expiry, lifecycle deadlines and existing sync/spend boundaries. This is a development review of selected files and primary references. Independent protocol/application review remains necessary.

## Comparison with primary implementations

| Control | Reference examined | Application behavior |
| --- | --- | --- |
| Random source | Monero 0.18.5.4 random.c / crypto.cpp; Feather secure_random.cpp | Rust rand_core::OsRng calls the OS CSPRNG. Every seed requires a successful fresh read; partial read failures return an error. There is no clock, password or deterministic test-source fallback in production. |
| Seed/scalar encoding | Original Polyseed specification and existing known-answer fixtures; Monero scalar-generation code | Standard English 16-word Polyseed and canonical 25-word legacy formats remain. No new derivation or user feature bits are introduced. Legacy generation reduces a 512-bit OS draw using curve25519-dalek; Monero's reviewed C++ code uses rejection sampling before 32-byte reduction. These are different sampling implementations. |
| Viewing secret material | Feather seed guide and SeedDialog | A saved seed wallet requires a fresh Android device-credential request. View-only wallets and key-only imports have no seed-view action. The view closes after 60 seconds, checked against elapsed time as well as a timer. New-wallet backup is shown before encryption and still requires the existing backup check. |
| Clipboard exposure | Android's official sensitive-clipboard guidance | Copy is explicit and follows a warning. Sensitive metadata requests that Android suppress the preview. A random owner marker allows cleanup without retrieving clipboard text. This metadata does not stop authorized readers or clipboard managers. |
| Session privacy | Existing lock/cancellation gates and Feather's documented lock controls | App switching/screen-off locks the wallet; open wallets also lock after five idle minutes. Inactivity uses elapsed time and checks interaction/resume boundaries. Seed display and clipboard deadlines do not depend solely on delayed callbacks. |
| Payments and transport | Existing actual-byte transaction verifier and isolated-node integration; Feather feature documentation | The previous local scan/sign/verify/journal flow and Arti transport remain. No default node, discovery or direct network fallback is added. Mainnet and hardware signing remain unavailable. |

Reviewed source snapshots: Feather `807bcab9e4277e9d16f11bc87a3932aadca6a323` (selected seed dialog and legacy secure-random helper); Monero tag v0.18.5.4 resolved to `85f036ba7019cd36f08e8a38a27c37ea7b522618` (random.c, crypto.cpp and wallet2.cpp). This is not a comprehensive review of either project or a claim that their source snapshots match every current release.

## Entropy and test interpretation

The production entry point fixes the source to OsRng. A private generic helper permits a deliberately failing CryptoRng only in unit tests; it is not exported through UniFFI or the application UI. The tests assert that even a partially written buffer followed by an error cannot produce a wallet, and no alternate generator is tried.

The resolved seed source is rand_core 0.6.4 → getrandom 0.2.17. Its Android/Linux backend uses the operating system's random facilities. The library's supported older-kernel fallback waits for OS entropy initialization before using /dev/urandom; it is still an OS source, not a custom application PRNG. The Android feature tree contains no application-selected RDRAND, JavaScript or custom backend. Inspecting the compiled target configuration is distinct from measuring a particular phone's kernel or hardware RNG.

Polyseed carries **150 secret bits**, with additional checksum/features/birthday information. The birthday is metadata, not extra entropy. Its specified key derivation is PBKDF2-HMAC-SHA256 with 10,000 iterations. A 25-word canonical legacy seed represents a nonzero scalar in a group of order about 2^252. Both formats' elliptic-curve security is bounded by the underlying cryptosystem. More CPU cycles or decorative randomness scores cannot certify stronger entropy.

The host checks draw one MiB through the OS RNG and apply deliberately liberal bit-frequency, byte-frequency and adjacent-byte checks to catch stuck or grossly biased output. A predictable generator can pass these tests. They do not estimate min-entropy, certify NIST compliance or prove unpredictability. Separately, 64 actual production wallets per format are generated and restored; all 128 addresses must be distinct and restoration must agree. Seed bytes/phrases are neither logged nor saved by these diagnostics.

## Clipboard contract and limitations

The user can copy the phrase during new-wallet backup, or after authenticating into the saved recovery view. The exact canonical phrase is copied as plain text. No automatic seed copying, system share intent, cloud backup or persistent application clipboard history is introduced.

A copy registers its ownership before writing, because an IPC error can mean the write committed but its reply was lost. A failed reply retains enough metadata for cleanup. A later copy cannot silently discard an outstanding unresolved cleanup lease. Timers request cleanup after 30 seconds; resume checks the elapsed deadline, including clock regression. Cleanup checks the owner marker and preserves a clip that has been replaced. Android exposes no atomic compare-and-clear operation, so an external replacement racing the check/clear cannot be completely excluded.

Switching to another app immediately locks/drops the wallet keys and display, while the user's explicitly authorized clipboard lease briefly remains available so pasting works. Screen-off, explicit lock, closing the seed page or choosing Clear copy requests immediate cleanup. Android may block background clipboard access, freeze the process or kill it; timers and cleanup are therefore **best effort**. A later resume retries unresolved cleanup. Android/keyboard/clipboard-manager histories, remote keyboards and copies already pasted elsewhere are outside the app's control. The confirmation dialog explains this before copying.

Kotlin/Compose/UniFFI and the Android clipboard can hold temporary plaintext copies that cannot all be erased reliably. The existing Keystore vault encryption also briefly receives a plaintext Rust snapshot in a Kotlin byte array, cleared in a finally block. No full-memory-erasure claim is made. Screenshot/overlay protections remain, including inherited secure-window policy for dialogs/sheets; actual Android/OEM execution is tracked in VALIDATION.md.

## Remaining review requirements

The retained RSA timing advisory and maintenance notices are tracked in DEPENDENCY_REVIEW.md; the audit is not clean. Further work includes Android/GrapheneOS lifecycle, clipboard preview/cleanup and Keystore execution; live Tor routing/DNS/cancellation captures; coverage-guided fuzzing; hardware adapter review and physical-device tests; and a protected developer-owned release-signing workflow. The local debug identity is not a production release key. No mainnet readiness or security parity with a major wallet is claimed.

## Primary references

- https://github.com/monero-project/monero/blob/85f036ba7019cd36f08e8a38a27c37ea7b522618/src/crypto/random.c
- https://github.com/monero-project/monero/blob/85f036ba7019cd36f08e8a38a27c37ea7b522618/src/crypto/crypto.cpp
- https://github.com/feather-wallet/feather/blob/807bcab9e4277e9d16f11bc87a3932aadca6a323/src/dialog/SeedDialog.cpp
- https://github.com/feather-wallet/feather/blob/807bcab9e4277e9d16f11bc87a3932aadca6a323/src/monero_seed/secure_random.cpp
- https://docs.featherwallet.org/guides/show-wallet-seed
- https://docs.featherwallet.org/guides/seed-scheme
- https://docs.featherwallet.org/guides/features
- https://github.com/tevador/polyseed#encoding
- https://docs.rs/getrandom/0.2.17/getrandom/
- https://developer.android.com/develop/ui/views/touch-and-input/copy-paste#sensitive-content
