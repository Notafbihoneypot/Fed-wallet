# Architecture — simple, secure, fast

An original Kotlin/Compose Android interface with a Rust application core and maintained Monero protocol machinery behind narrow adapters.

| Component | Responsibility | Current scope |
| --- | --- | --- |
| Kotlin/Compose | Two-choice setup, dashboard, local QR scanner, lifecycle and device authentication | Software stagenet; Keystone mainnet/stagenet viewing |
| wallet-policy | Exact amounts, explicit endpoints and policy/session primitives | Rust host-tested |
| wallet-keys | Supported seed derivations, canonical keys, mode-preserving snapshots | Offline; no socket dependencies |
| wallet-engine | Local scanning, balances/spent detection, checkpoints, signing and exact-byte verification | RingCT/account 0/first 20 subaddresses; software signing stagenet only |
| Node transport | Explicit Direct sockets or Arti streams, verified HTTPS and bounded RPC | User-selected route/node only; Tor never falls back to direct |
| UniFFI | Typed native boundary and ownership/cancellation | Keys remain within Rust during scanning/signing |
| Android vault/setup/node stores | Credential-protected wallet/setup encryption and encrypted node selection | Created setup resumes after pause; no Android backup |
| Local scan store | Encrypted checkpoints and durable pending transaction reservations | Atomic writes, exclusive lease, reorg rollback |
| Keystone adapter | Feather connection QR and mode-preserving view-only import | Mainnet/stagenet viewing; key-image sync and external signing unavailable |

## Key and network boundaries

Software wallets hold a local spending key. View-only wallets hold only the private view key and public spending key. Hardware recovery phrases have no hardware-import path in this app. Unsupported modes never gain local signing authority.

Offline creation/import and vault unlock do not initialize Tor, DNS or daemon clients. An explicit Connect action creates a local engine, then boots Arti and opens the chosen endpoint. Mainnet software wallets are rejected at both Android and engine boundaries. Keystone accounts contain no spend scalar and may scan mainnet; transport attachment checks the wallet's expected mainnet/stagenet genesis and receiving subaddresses use its network. The node remains fixed within the session. Saving/changing/forgetting a node cancels the previous online session; no node is selected automatically.

The older NetworkGate type is a policy primitive, not the transport. Production routing is enforced by an immutable per-session Direct/Tor connector. Direct accepts normal HTTP/HTTPS and system DNS; Tor passes hostnames straight to Arti and accepts v3 onion or authenticated HTTPS. Route changes cancel the native child before explicit reconnection and reload encrypted scan checkpoints. Neither route redirects or queries a wallet-specific key-image list; Tor never falls back to Direct. See SYNC_SPEND.md for endpoint restrictions, timeout/size bounds and remote-node trust limits.

## Lifetimes and Android authentication

Locking the native offline owner cancels every online child. Background/screen-off hides the UI and requires authentication on return. By default it cancels networking; with remembered background consent and a live bounded foreground service, a saved wallet keeps its native owner for scanning and subsequent tip polling until stop/error/timeout. Network consent is encrypted and bound to the exact wallet, endpoint fingerprint and route; matching consent reconnects only after authenticated unlock. Explicit lock, service stop, node/route changes or process death still revoke it. The service has no signing, seed-display or automatic reconnect action. Created setup is committed before display, and pause checkpoints the current step/answers. A pending encrypted write retains only its checkpoint buffer/data key until completion; both are cleared afterward. Resume requires device credentials. Explicit seed copies use manual ownership-checked clearing; lifecycle events and timers do not clear the clipboard. Saved recovery viewing still requires fresh authentication and expires after 60 seconds, and unlocked sessions lock after five idle minutes. Wallet/network generations reject late results; a system credential request remains bounded to 60 seconds. See SETUP_RESUME.md for the exact persistence/clipboard contract and limits.

Vault I/O and native work run off the main thread. Android authentication is required for vault save/unlock and before confirming or retrying a payment. Payment review data remains an immutable native construction, represented to Kotlin by an opaque random identifier. A changed node, closed owner, expired review or changed chain tip cannot reuse that review.

Rust-owned key buffers use zeroization. Kotlin strings, Compose state, FFI serialization and dependency internals can retain temporary copies; complete process-memory erasure is not claimed.

## Scan and broadcast state

Scan checkpoints and outbound reservations share one authenticated encrypted state. Software/view-only namespaces remain separate. Local output/key-image mappings determine spent status without sending a wallet's key-image set to the node. Duplicate output keys are not credited twice.

Reorg rollback removes outputs and spend marks from replaced blocks, rolls back history and restores uncertain payment reservations. Deep reorgs restart at the earlier restore point. Corruption and uncertain fsync outcomes preserve the file and stop the current session.

The signer validates exact signed bytes against private construction data, the fee and expected key images, verifies CLSAG/range proofs and commitment conservation, then atomically stores the payment/input reservation before the first broadcast. An unknown reply permits retry of the same bytes; it does not release inputs for a new payment. View-only totals remain explicitly incomplete for spent status.

## Performance and verification

Use view-tag-aware upstream scanning, 32-block batches, encrypted checkpoints and worker threads. Cryptographic dependencies are optimized in host tests as in the upstream workspace; Android uses release optimization. No speed comparison with another wallet is claimed.

Host/isolated-node tests, Android compilation, actual device execution, hardware interoperability and live Tor routing verification are distinct evidence. See VALIDATION.md for completed checks and remaining coverage.
