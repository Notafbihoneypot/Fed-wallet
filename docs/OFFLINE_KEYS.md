# Offline keys and authenticated storage

## Supported inputs

| Input | Validation and derivation |
| --- | --- |
| New Polyseed | OS cryptographic entropy; existing Polyseed algorithm, no feature bits; device clock supplies only the birthday |
| New legacy seed | OS cryptographic entropy; existing 25-word English Monero encoding |
| Seed import | Explicit 16/25 format; exact word count; checksum; full English words; canonical output; no passphrase/offset support |
| Private spend key | Exactly 64 hex characters; canonical, nonzero scalar; deterministic view-key derivation |
| View-only | Primary address and network match; nonzero canonical private view key matches public view key; prime-order nonidentity public spend point |
| Keystone | Normal Feather connection JSON/UR bytes; the same viewing-key validation; version/source checks; no spend-key input; hardware origin persists in storage |

The spend-key import does not restore wallets with an independently chosen, non-derived private view key. Subaddresses and integrated addresses are rejected as view-only primary addresses. Other mnemonic formats are unsupported, including BIP39 phrases that happen to have a familiar word count.

The offline Rust primitives support mainnet/stagenet/testnet address prefixes for vectors. Android software setup and spending remain stagenet only. Keystone import and vault reopening allow mainnet/stagenet view-only accounts; the native engine independently enforces that boundary and checks the selected chain's genesis.

## Backup and authority

Newly generated software sessions cannot export a completed-wallet snapshot or open networking until three requested recovery words match. A separate IWS1 setup export can be immediately encrypted before that check; its constructor restores the unverified flag and original challenge. Imported material is treated as an existing backup. Hardware and view-only modes never request a hardware seed or acquire local signing authority. See SETUP_RESUME.md for the credential-protected draft and pause/resume flow.

This milestone stores one wallet. There is no in-app replace/delete operation. A corrupt or unreadable saved wallet is kept rather than automatically replaced. The recovery phrase is the portable backup; Android Keystore keys are device-bound.

A process-wide gate covers the existence check, Keystore-key creation and atomic write across vault instances. A failed save while a file exists locks the unsaved session; file existence alone never labels it as saved. The app uses one Android process; this gate is not a cross-process file lock.

## Storage format

The plaintext IWK1 record has a bounded, versioned header containing network, origin and restore height, followed by a length-delimited supported seed or key payload. Maximum plaintext size is 4096 bytes. View-only origin 4 and Keystone origin 5 contain only a view scalar and primary address. Hardware origin cannot restore a software spend key or recovery phrase. Existing software/view-only tags retain their encoding.

The Android IWE1 envelope contains its version marker, a fresh 96-bit GCM IV and AES-GCM ciphertext/tag. The marker and a vault-specific domain string are authenticated as additional data. Parsing is bounded before decryption. Decrypted arrays and immediate export arrays are cleared in finally blocks.

AES-256 keys live under a dedicated AndroidKeyStore alias, require recent device authentication and an unlocked device, and are not reused for node settings. Records live in noBackupFilesDir and use AtomicFile. The manifest disables backup and excludes all configured cloud/device-transfer domains.

On API 30+, the key allows strong biometrics or device credentials for a 30-second authentication validity window. Older declared API levels use the corresponding time-bound authentication API. The current UI deliberately uses the Android device-credential confirmation screen. Hardware-backed/StrongBox availability is not promised.

## Locking

An ordinary stop or screen-off invalidates the session and authentication ticket. A system credential request has a unique generation and 60-second lifetime. Its callback checks both generation and elapsed time; a delayed coroutine timer cannot extend the request. Process death loses unsaved keys and input; saved wallets reopen locked.

The activity permits one outstanding credential request. Screen-off invalidates its ViewModel ticket while retaining the activity request's identity until its result arrives, preventing an old result from being assigned a new request's identity.

Configuration changes retain an active session in the ViewModel. Created setup is checkpointed encrypted, including its backup step and bounded partial verification answers. Seeds are not placed in intents, SavedStateHandle, preferences, analytics or logs. In 0.6.0, explicit clipboard copying retains sensitive metadata and remains until explicit clearing/replacement or OS behavior; the app no longer removes it on exit/lock/page-close/timer. Only the nonsecret clipboard owner token is stored in preferences. See SETUP_RESUME.md; the older SECURITY_0_4.md timer contract is historical.

## Remaining limits

- UI strings and UniFFI temporaries are not guaranteed to be erased immediately; review a dedicated secret-buffer ABI before a production release.
- Root/process compromise, a malicious keyboard/accessibility service and compromised firmware are outside the protection provided by application encryption.
- Android Keystore behavior and lock-screen transitions need physical-device coverage across supported API levels and OEMs.
- Local caches, software spent detection and verified software payments are available on stagenet. Mainnet is enabled only for Keystone viewing. Hardware signing and verified external key-image import remain unavailable.
- This is a developer integration, not an independent security audit.
