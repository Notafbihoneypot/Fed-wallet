# Current node-list update

Version 0.11.4 supports up to five named, user-added endpoint/route entries. See SEND_NODES_0_11_4.md. Selecting another node revokes the current connection and remembered reconnect consent; no automatic replacement or failover is permitted. Legacy route endpoints/preferences remain readable.

# Direct startup and sync, then optional Tor — 0.10.0

The Node screen and dashboard use an explicit native route. New settings start with Direct, so a normal Monero RPC node can connect and sync without Tor bootstrap. Once the live native scan is complete, Switch to Tor or Set up Tor node opens the Tor slot. The animation and block counters continue to reflect actual native scan state.

## Use

1. Finish setup, save the wallet and unlock it. Open Node, leave Use Tor off, enter your own full http:// or https:// Monero RPC URL and port, then Save direct node. No node is supplied by the app.
2. Enable Network access. The Rust direct connector opens only that endpoint, checks the wallet network's genesis and scans locally. It does not create an Arti client. Direct connects expose the client's IP to the node; HTTP is unencrypted. Use HTTPS when available.
3. Watch the real percentages, block heights and remaining blocks. Cached scan state, an error, a numeric tip alone or animation cannot report verified completion. Healthy foreground sessions check for blocks every 30 seconds without extending the five-minute inactivity limit.
4. After sync, optionally choose Switch to Tor/Set up Tor node. This disconnects and opens your saved Tor slot or leaves it empty for you to enter a node. Save a v3 .onion URL (HTTP or HTTPS) or a normal HTTPS endpoint, then explicitly enable Network access again. You may select Tor before an initial sync as well.
5. The Tor session bootstraps Arti, performs the same node/genesis checks and resumes from the last durable encrypted scan checkpoint. It does not restart the wallet's scan. A newer node tip or different chain branch can legitimately require more scanning or rollback. A Tor failure never tries Direct or another node.

Direct/Tor labels and startup/sync/send messages show the chosen route. Switching to Tor protects subsequent connections; it cannot hide the IP/activity already exposed during direct sync. Reusing a node or similar timing can still allow correlation; no complete anonymity claim is made. Neither route makes an untrusted node authoritative for balances or consensus.

Software wallets still require stagenet nodes. Keystone supports mainnet/stagenet viewing. Hardware spending and authoritative hardware spent-balance/key-image sync are unavailable. Mainnet software signing remains rejected in Rust.

## Consent and lifecycle

Saving a node/route never starts a connection. Network enabled/connected flags exist only in the current unlocked session and begin false after launch/unlock. The saved route preference is retained; an existing Tor selection is never silently downgraded on update. Normal startup here means connecting to your node, not running a full monerod process on the phone or auto-connecting during app launch.

The native online child is constructed with an explicit route enum. The endpoint and connector are immutable until that child is destroyed. Online creation validates route/endpoint compatibility before loading keys/cache. The owner permits only one live child and revokes children on lock. A Kotlin label or policy readiness flag is not evidence of the actual route.

Changing node or route first disconnects/cancels the current session and clears payment review. Epoch checks reject late callbacks. A new connection waits for old native work to drain; there is no live in-place route mutation or overlapping replacement connection. Scans and pending-payment reservations persist in the existing encrypted wallet-specific cache, independent of the transport. A fresh session validates the selected node again and cannot reuse an old verified-synced flag.

Network off and explicit lock cancel networking. Background/screen-off/inactivity lock the UI; by default they also cancel networking. A live, explicitly permitted foreground scan can continue with a private notification and bounded partial wake lock. It stops at completion, error, user stop, node/route change or timeout and never restarts after process death. BACKGROUND_SYNC.md describes owner lifetime, memory and authentication boundaries. An OS DNS lookup already dispatched during Direct may finish at the resolver after cancellation; switching cannot undo a prior DNS request. Tor node hostnames go straight to Arti and never enter the direct resolver. Physical-device routing/cancellation captures remain a separate validation requirement.

## Transport boundaries

| Selection | Accepted node | DNS/stream |
| --- | --- | --- |
| Direct | Normal HTTP or HTTPS; onion rejected | System resolution and Tokio TCP; no Arti startup |
| Tor | V3 onion HTTP/HTTPS or normal HTTPS | Arti connection and destination resolution; no direct fallback |

HTTPS always uses normal certificate/hostname verification and bundled public roots. A TLS failure cannot downgrade to HTTP. Tor to a normal cleartext HTTP node is rejected to avoid an unencrypted exit connection. Redirects, encoded HTTP responses, unsupported RPC routes and responses exceeding the lower of the caller's limit or 32 MiB are rejected. Requests are cancellation-aware with a 90-second timeout; Tor bootstrap is bounded to 180 seconds. Errors do not echo endpoints or credentials. Node authentication is not implemented; inline credentials are rejected.

The platform usesCleartextTraffic=false flag is retained for platform HTTP APIs. The explicitly selected native HTTP RPC path uses Rust sockets; its unencrypted nature is disclosed in the node form and before network consent. HTTPS should be used when available. Actual Android network permission/routing behavior still requires device execution.

## Encrypted slots and migration

IWN2 keeps the same binary layout, AES-GCM/AAD, Android Keystore alias, node-v1.bin filename and 4,105-byte payload bound. Its existing HTTPS slot becomes Direct; its onion slot becomes Tor. Both endpoints and the selected ordinal are preserved. Old HTTPS selection will connect directly only after fresh, explicitly enabled network consent with a visible Direct label. Existing Tor selection remains Tor. Legacy single-URL records migrate into the corresponding slot offline. There is no automatically populated Tor address and an empty chosen slot never falls back.

AtomicFile and a process-wide monitor preserve the other slot during changes. Strict UTF-8, length, selected-kind and trailing-byte checks remain. Corrupt/unavailable settings are retained until explicit Forget node settings; that action does not delete a wallet, setup draft, cache or clipboard copy. Network consent is not stored in the record.

## Android and retained scope

On GrapheneOS, allow Network permission for the app in system settings; the app cannot override that permission. An error offers Open app settings. Returning requires unlocking and explicitly enabling networking again. A wrong network, unreachable node, invalid HTTPS certificate or blocked Tor may also cause errors; no fallback is attempted.

The cloud-backup UI remains absent, platform backups/transfers excluded, and wallet/setup/node/cache files private and encrypted. Resume and Discard setup, manual offline backup, explicit-only clipboard clearing, screenshot/overlay controls and hardware/view-only authority limits remain unchanged.

Primary references: https://docs.getmonero.org/interacting/remote-nodes/ ; https://docs.rs/tokio/1.52.0/tokio/net/struct.TcpStream.html ; https://arti.torproject.org/ ; https://developer.android.com/reference/android/security/NetworkSecurityPolicy ; https://grapheneos.org/features#network-permission-toggle . Executed checks and limits are recorded in VALIDATION.md.
