# Independent Android Monero Wallet — Preview 2, 0.11.10

An independent native Android wallet with Kotlin/Compose and a Rust application core. This developer build combines local synchronization/software payments with explicit direct and embedded Tor routes with an original dark, green-and-amber retro interface and explicit seed-backup controls.

Setup has two choices: **Software wallet** and **Hardware wallet (Keystone)**. Software users generate fresh keys offline on their own phone, or import their own 16-word Polyseed or 25-word Monero seed. The app never supplies predetermined seeds or gets keys from a server. Keystone users scan the normal **Monero / Feather connection QR** and keep their seed/spending key on the hardware.

**New setup uses Mainnet. The wallet picker supports named wallets, and earlier test wallets remain accessible in Settings. Keystone and existing generic view-only accounts retain viewing authority; hardware signing and verified key-image sync are unavailable.** Viewing-only received outputs can include already spent funds and change, so they are not labeled as a spendable balance. See [Keystone scope](docs/KEYSTONE.md) and [validation](docs/VALIDATION.md).

## Build status

Version **0.11.10-preview2**, Android code **28**, updates **Independent Preview 2** (`app.independentwallet.android.syncpreview2`) in place using the same backed-up signing certificate: `01afd9a36efbb0d6573d8f12764164a14af3d99faa4a97ccfce45dff82cd52b5`. Install over Preview 2 without uninstalling/clearing its wallet data. The earlier `app.independentwallet.android.syncpreview` package remains separate; its original signing key is still unavailable.

The update fixes a large, one-request decoy-data download that can time out while preparing a Tor payment. It downloads bounded parts, saves completed parts encrypted for later attempts, checks their selected node/route and chain snapshot, and shows live preparation stages. It also preserves the original network error from a decoy lookup. See [Tor send update](docs/TOR_SEND_0_11_7.md). The earlier subaddress, scanner and installer hardening changes remain.

Two upstream vulnerability alerts and three maintenance warnings from the previous review remain documented in [security review](docs/SECURITY_0_11_6.md). This is a development preview; host tests do not establish live phone/Tor behavior or an independent security audit. See [validation](docs/VALIDATION.md).

The protected APK-signing key and separate private recovery file remain outside public source/APK/report artifacts. Retain the exact signing identity unless an owner-authorized Android signing lineage deliberately rotates it; do not generate an arbitrary replacement key. See [signing](docs/PREVIEW2_SIGNING.md).

## Wallet experience

Version 0.11.7 keeps the first decoy-data download resumable for the exact saved wallet, node and route. Subsequent preparations reuse its chain-anchored public counts and fetch the new tail. Only public-count reads shrink/retry automatically; ring/output lookups and broadcasts do not. Preparing a new payment still requires the visible unlocked wallet.

Version 0.11.6 adds **Receive -> New subaddress**, local labels and an address selector, with account 0 and up to 1,000 subaddresses plus the primary address. Indices/selection/labels persist encrypted and survive normal lock/reopen/rescan. Scanning watches the entire supported range, and a seed restore discovers used higher addresses when its scan start precedes the receipt. Labels and the unused-address list remain local. View-only/Keystone receiving does not enable signing. See [receiving update](docs/RECEIVING_0_11_6.md).


Version 0.11.5 pauses automatic updates during payment entry, preparation, review and authorization; retains an idle service through review/authentication; and lets only a previously authorized signed publication finish in a consented background session. Normal checks are batched about every five minutes; pending transactions check about every minute. A metadata-only poll precedes scanning, and only new blocks are scanned. A normal tip advance updates spent input status without changing approved bytes or requiring a new review. Reorgs, expiry and spent inputs still reject the old review.

Version 0.11.4 adds **Max**, an exact recipient/fee/total review, and payment details after submission and from activity. The receipt separates node acceptance, a lost submission reply and actual block confirmations. A tip change during authorization returns to an updated review and requires fresh approval. The Tor preparation path caches public cumulative output counts within the same wallet/node session instead of downloading the full history for every input; the first lookup can still be slow. Node settings now hold **up to five named, user-added endpoints**, with explicit selection and no presets/failover. See [send and saved-node update](docs/SEND_NODES_0_11_4.md).

Version 0.11.3 remembers Network access and Allow background sync for the exact saved wallet, node and route. Enable access once after updating; later authenticated unlocks reconnect automatically. A permitted background session keeps checking for new blocks after catch-up, so confirmation/spendable balances advance. The dashboard explains received-but-locked XMR and shows blocks until the next unlock. Network off and Stop and lock revoke automatic reconnect; node/route changes require fresh consent. Android can stop or time-limit background execution. See [connection and balance update](docs/CONNECTION_BALANCE_0_11_3.md).

Version 0.11.2 reuses a single live HTTP/HTTPS connection within the selected node/route session, with cancellation closing even idle connections. Completed responses alone return to the pool; interrupted/error/oversize responses discard the socket and broadcasts are never automatically replayed. Quiet scan reads are grouped into durable windows of up to 2,000 blocks with a two-second target. New receipts, spends and confirmations end the window after the current read. Each reported result is already saved; a failed/cancelled partial window leaves the previous checkpoint in place. Payment reservations still commit before broadcast. See [connection/checkpoint update](docs/SYNC_0_11_2.md).

Version 0.11.1 fixes slow initial scans: choose **Change scan start → Wallet created today · use recent blocks** for a newly created wallet, confirm the start precedes its first payment, then **Apply and sync**. Pause a running scan first. A fresh zero-height connection waits for this choice or **Scan from beginning**; it no longer immediately starts a full-history scan. Recent blocks proposes the connected node's height minus 1,440 blocks; older wallets need an earlier known height. Forward changes are rejected if the local wallet already has outputs, history or payment reservations. Earlier rescans remain available and preserve reservations. See [sync update](docs/SYNC_0_11_1.md).

The Original/Mainnet tabs are removed. Choose or add a wallet from the wallet-name picker. Each new wallet offers software creation, supported seed import or Keystone setup on Mainnet. Existing vault paths and unfinished setups remain available; earlier/test wallets can be opened from Settings. Duplicate imports of the same primary address are rejected to avoid separate payment journals for the same wallet.

Settings provides freshly authenticated recovery-phrase and key viewing, an optional 8–12 digit app PIN, optional strong biometric unlock, and an optional duress PIN. The duress PIN is off by default; if enabled and entered, it immediately erases every wallet and record in this app, including pending-payment records. It does not erase other apps, the phone, blockchain records or external backups. Keep recovery backups safe.

Send includes **Scan address QR**, using the local camera without Google services or image uploads. It accepts standard, subaddress and integrated-address text, plus supported `monero:` payment URIs with an optional exact amount. A scan fills the form and clears Max; users still review the full recipient, amount and fee before authorizing. Separate payment IDs, wallet-import QR data and unsupported/duplicate URI fields are rejected. Final checksum/network validation remains in native payment preparation. Camera and NFC reading are mutually exclusive.

Fingerprint protection retains its existing per-use strong BiometricPrompt CryptoObject and encrypted root format. AAD is supplied only after authentication; unavailable sensors, canceled prompts and enrollment changes preserve PIN fallback. To recover an invalidated biometric wrapper, unlock with the normal app PIN, disable biometric unlock in Settings, authenticate again, then enable it. This replaces only the biometric wrapper, not wallet keys or the PIN.

Phone-to-phone NFC is an experimental address exchange between compatible copies of this app. The recipient opens Receive, selects an address and chooses **Share address by NFC**; the sender opens Send and chooses **Tap to read address**. NFC and an unlocked phone are required. The sender enters the amount, checks the recipient address and fee, and separately authorizes payment. The address is public, NFC can be intercepted or substituted, and the tap is not proof of payment. This is not Goblin-card, bank terminal, or other wallet interoperability. Physical Android/GrapheneOS NFC validation is pending.

**Development preview:** Physical GrapheneOS fingerprint, camera and NFC behavior and all security flows are not fully validated on devices. Use only test wallets with no funds and do not rely on duress. Read docs/VALIDATION.md.

- Dark charcoal surfaces, green/amber accents, monospace headings and stepped pixel borders, with a balance dashboard, visible route/sync state and recent activity.
- Dedicated Send and Receive sheets, local receiving QR, copy-address action and full payment review with fee/total before Android authentication.
- Offline software creation/import, backed by the operating-system cryptographic random source. English 16-word Polyseed, 25-word legacy and canonical deterministic private spend keys are supported.
- Keystone normal connection QR import, with local camera scanning or pasted data. Rust checks the primary address/private view-key match and preserves hardware mode in encrypted storage. Generic view-only snapshots remain readable, with no separate setup tile.
- No preset Monero nodes, automatic selection or silent failover. Saving a node does not connect.
- Choose HTTP or HTTPS, then enter your own hostname/IP and RPC port, or paste a full URL. Saving canonicalizes the entry through the existing Rust parser. Edited or invalid drafts block new connections to an older saved address on both screens. Invalid input and re-saving the same canonical endpoint leave a running scan alone. See [node entry and connection fixes](docs/NODE_ENTRY_0_10_2.md).
- Direct connections resolve the selected hostname through Android before handing a bounded, session-only IP list to Rust. The selected URL remains the HTTP/TLS authority. Android 29+ uses cancellable system DNS for fully qualified names; local names and Android 28 retain platform InetAddress resolution. Literal IPs do not perform DNS. Lookup errors distinguish missing names, empty answers, DNS refusal/server failures, network blockage and timeout without logging destinations. Tor never uses this resolver. Reconnect to refresh addresses. See [Android DNS update](docs/ANDROID_DNS_0_10_3.md).
- Network access starts the selected route and Rust scanning engine. New settings default to Direct for normal HTTP/HTTPS Monero RPC nodes, without Tor startup. Save a separate Tor node (v3 onion or HTTPS), then switch after syncing if desired. The list remembers the last explicitly selected endpoint for each route; switching closes the old session and keeps access off until explicitly enabled. Explicit lock and node changes cancel access. Without background consent, leaving also cancels. With Allow background sync and notifications enabled, a bounded foreground service scans and checks for later blocks while the wallet UI is locked. The candidate batches routine checks about every five minutes (pending payments about one minute), using tip metadata first, while Android permits execution. A remembered matching connection resumes after authenticated unlock.
- A native pixel-orbit sync card shows connecting/scanning/checking/paused/synced states, real range-based percentages, scanned/target heights, remaining blocks and delayed-batch activity. Completion is driven by native status; animation never invents progress. System-disabled animations remain static. After syncing, an optional action opens the saved Tor slot or helps add one, without auto-connecting.
- Created wallets are saved as encrypted setup drafts before their keys are shown. Leaving pauses setup; unlock to resume the same wallet, backup step and checkpointed verification answers. Completing the wallet save retires its redundant setup draft.
- Resume setup and Discard setup are separate choices. Discard is available on the paused, backup, verification and unfinished dashboard screens, requires confirmation and device credentials, and removes only the exact unfinished draft. It needs no seed or working draft-decryption key and leaves clipboard copies alone.
- Device-authenticated recovery viewing for saved seed wallets, a 60-second recovery-view limit and five-minute inactivity lock. Locking clears the display and native owner, while keeping saved files.
- Explicit seed copying uses sensitive-preview metadata and manual Clear copy. The app does not clear it on a timer, exit, lock or page close. Android may independently clear it and clipboard managers may retain copies.
- Existing screenshot/overlay protections, disabled backup, encrypted node settings and Android Keystore credential-gated wallet vault.

Version 0.10.0 fixes rejection of uncompressed RPC responses marked Content-Encoding: identity, requests uncompressed responses explicitly, and preserves safe error codes for DNS, Android network permission, TCP, TLS, HTTP status, compression and actual size-limit failures. Failed connections have an explicit Retry connection action. TLS validation, bounded responses, Tor routing and saved progress are retained. See [connection fix](docs/NODE_CONNECTION_FIX.md).

Background sync is opt-in and its preference is remembered for the saved wallet/node. Enable Allow background sync and grant notifications while the wallet is open, then connect. A private progress notification shows fractional percentages, current/target block heights and expanded remaining-block counts, and offers Stop and lock; there is no boot/process-death restart or background payment. The active software/viewing wallet stays in memory during its bounded background session; explicit Lock always revokes it. A full-width progress bar and block counters appear as soon as the node tip is known; decorative activity no longer substitutes for known progress. See [background lifecycle](docs/BACKGROUND_SYNC.md).

## Sync and spending

To sync: finish wallet setup and save it on this device, open Node, leave Use Tor off for your normal node, save the matching endpoint, then enable Network access. The existing single saved node is preserved on upgrade. Choose a scan start for an unscanned zero-height wallet. Use a node on the opened wallet's Mainnet or Stagenet network. On GrapheneOS, allow the app's Network permission in system settings. Direct connects without Tor bootstrap. After scanning, choose Switch to Tor/Set up Tor node, save your own onion or HTTPS endpoint and enable access again. Tor bootstrap may take a few minutes. Existing Tor selection is preserved on update; it is never silently downgraded. The Wallet page shows compact connection progress; Node has detailed progress, Sync now and connection controls. No cloud-backup control is provided; platform backup exclusions remain enabled.

The new Rust engine uses pinned monero-oxide wallet/daemon crates for protocol operations. The Rust transport fixes the explicitly selected route for each native session. Direct uses the system resolver/socket for your HTTP/HTTPS node; the node sees your IP and plain HTTP is unencrypted. Tor uses Arti streams and remote destination DNS with no direct fallback; its normal nodes require HTTPS and v3 onion nodes may use HTTP. Switching later cannot hide earlier direct activity. TLS certificate/hostname checks apply to both routes; node credentials remain unsupported.

Scanning runs away from the UI thread in bounded batches with encrypted atomic checkpoints, local software-wallet key-image spent detection, output deduplication and reorg rollback. It covers RingCT outputs, account 0, its primary address and all 1,000 supported subaddresses. A restore at height 0 scans from the beginning; choose a known earlier height when importing an existing wallet.

Software payments use the upstream decoy policy, exact atomic units and locally held spending keys. The engine checks actual signed bytes against private construction data, verifies signatures/range proofs and commitment conservation, then stores a durable input reservation before broadcast. A timeout retains that reservation; retrying publishes the same transaction bytes. Recipient addresses and node genesis must match the opened wallet network. Keystone and generic view-only accounts hold only viewing authority.

The preview has a 0.05 XMR fee cap, up to 16 inputs and one recipient. Incoming mempool scanning, time-based additional locks, permanent-rejection abandonment and external verified key-image import require further work. Details and threat-model limits are in [sync/spend integration](docs/SYNC_SPEND.md).

## Install and build

The current 0.11.6 Preview 2 installer uses `app.independentwallet.android.syncpreview2` and the separately backed-up Preview 2 signing key. Later Preview 2 updates must retain both. This is a developer preview, not a production release. Old Sync Preview installations have independent storage and cannot be updated with this key.

Install Python 3, pinned Rust 1.99.1, JDK 17, SDK platform 36/build tools 36.0.0 and NDK 28.2.13676358. Install cargo-ndk 4.1.2 with `cargo install cargo-ndk --version 4.1.2 --locked`, then set ANDROID_HOME and ANDROID_NDK_HOME.

Build an unsigned Preview 2 candidate and sign locally with the retained key:

    bash scripts/check.sh
    bash scripts/build-bindings.sh
    bash scripts/build-android-native.sh
    bash android/gradlew -p android :app:assembleDebug :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug -PunsignedPreview=true
    zipalign -c -P 16 4 android/app/build/outputs/apk/debug/app-debug-unsigned.apk
    apksigner sign --ks /private/path/independent-wallet-preview2-signing-key.p12 --ks-key-alias independentwallet-preview2 --out independent-wallet-v0.11.10-preview2.apk android/app/build/outputs/apk/debug/app-debug-unsigned.apk
    apksigner verify --verbose --print-certs independent-wallet-v0.11.10-preview2.apk

The signing command prompts for the password; do not put it in shell history. The Android SDK build tools must be available on PATH. Compare the certificate SHA256 with PREVIEW2_SIGNING.md. Do not modify the APK after signing. Normal signed Gradle builds use the key path in IW_SIGNING_KEYSTORE (or `.private-signing/preview2.p12`) and IW_SIGNING_PASSWORD supplied locally, with alias `independentwallet-preview2`. Neither secret belongs in source, CI logs or public releases. Never regenerate a key as a substitute for a lost identity. Keep independent owner-controlled backups of the encrypted key and password.

Gradle is pinned/checksummed at 8.14.6. ABIs are arm64-v8a and x86_64; minimum Android API 28. The source archive includes generated bindings, not native libraries or build caches. Build native libraries before packaging an APK or running real host JNA/UniFFI tests.

The debug assembly also checks DEX lengths, checksums and duplicate classes. Rebuild from clean output if that guard fails.

Additional checks:

    python3 scripts/test-package-source.py
    python3 scripts/test-native-build.py
    python3 scripts/test-apk-integrity.py
    python3 scripts/test-regtest.py --monerod /path/to/verified/monerod

The regtest runner starts a fresh offline node on loopback only and mines a sufficiently old synthetic chain. The fault-injecting loopback adapter exists only under Rust cfg(test). The software signer now uses Mainnet addresses and the real fakechain/mainnet genesis gate, with no genesis bypass. The same test also exercises the actual production direct connector with the mainnet genesis gate and a view-only account, encrypted-cache reopen and local scanning. It exercises scanning, signed-byte verification, lost broadcast replies, durable reservations, reopen/retry, confirmation, recipient maturity and reorgs.

Android instrumentation must run only on a disposable emulator and uses public fixture keys. The security suite clears this app’s fixture data and must never run against a personal wallet.

## Security and independence

Offline key operations never create a Tor or daemon client. Locking the native key owner revokes online children; background/screen-off events lock the UI; only a consented bounded foreground service may retain its native wallet for scanning and tip polling until stop, error or timeout. Returning requires device credentials. Rust-owned secret buffers use zeroization, but Kotlin/Compose/FFI can retain temporary copies. No complete process-memory erasure is claimed.

This application does not clone another wallet. Its UI uses familiar balance/send/receive/activity conventions. Existing protocol implementations, third-party libraries and original licenses are documented separately. No synchronization-speed comparison or independent security audit is claimed.

See [resumable setup and copy behavior](docs/SETUP_RESUME.md), [Keystone scope](docs/KEYSTONE.md), [historical seed/privacy review](docs/SECURITY_0_4.md), [architecture](docs/ARCHITECTURE.md), [offline keys](docs/OFFLINE_KEYS.md), [0.2.1 historical review](docs/CODE_REVIEW.md) and [seed provenance](vendor/UPSTREAM.md).

License: GPL-3.0-only. Third-party components retain their licenses.
