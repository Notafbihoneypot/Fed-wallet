# Syncing and spending — 0.3.0 stagenet integration

The engine was introduced in 0.3.0; the seed/privacy controls are documented in SECURITY_0_4.md. Version 0.5.0 adds Keystone view-only pairing (KEYSTONE.md) and mainnet viewing for that mode. This is an independent Kotlin/Compose Android wallet with a Rust core. Wallet creation/import remains offline. Enabling Network access authorizes local scanning through the selected Direct/Tor node and remembers that exact wallet/node/route for authenticated unlocks from 0.11.3. Changing the node/route or turning access off revokes automatic reconnect. Software signing supports Mainnet and Stagenet from 0.11.0, using the wallet network for recipient validation; hardware signing remains unavailable.

Version 0.7.0 exposes the existing engine through Network access and Sync now on the Node screen and dashboard, plus separate HTTPS/onion slots. NETWORK_ACCESS.md describes consent, encrypted settings migration, foreground polling and GrapheneOS permission troubleshooting. Version 0.9 adds explicit direct routing as described below; automatic fallback remains absent.

Version 0.8.0 adds the native animated progress presentation and explicit post-sync onion choice described in SYNC_PROGRESS.md. It uses a separate syncpreview package after loss of the previous private signing identity; keep the prior installation and its data.

## Protocol and transport choices

The adapter uses published, pinned `monero-wallet 0.2.0` and `monero-daemon-rpc 0.2.0` from monero-oxide. The reviewed upstream snapshot is `731657ae3385be667abb556266369a497bc86f13`. Protocol algorithms are reused; the application does not invent seed derivation, decoy sampling, transaction formats or signature schemes. This replaces the earlier wallet2 integration candidate described in the 0.2.1 design notes.

Arti 0.47.0 is embedded with Tokio, Rustls, onion-client support and bundled SQLite. Optional onion hosting, pluggable transport executables, RPC, native TLS and additional xz/zstd directory codecs are not enabled. Rust wrapper crates forbid unsafe code; that does not make every dependency Rust-only or free of unsafe code. Ring and bundled SQLite include native code.

The production HTTP adapter fixes Direct or Tor when the native session is created. Direct uses normal HTTP/HTTPS sockets and system DNS without constructing Arti. The chosen node sees the client IP and HTTP is unencrypted. Tor receives an actual Arti stream; hostnames pass directly to Tor without the direct resolver. In Tor mode clearnet nodes require HTTPS; HTTP is accepted only for v3 onion services, whose identities Arti validates. Normal TLS certificate/hostname verification applies to either HTTPS route. There is no route or node fallback. Redirects, encoded HTTP responses, unbounded responses and routes such as `is_key_image_spent` are rejected. Requests have a 90-second timeout and a 32 MiB hard response limit; bootstrap has a 180-second timeout. Cancellation drops the request future, HTTP driver and selected stream.

There is no preset Monero node, node discovery, automatic failover, price feed or telemetry. Arti uses the Tor network's standard directory authorities and relays to establish Tor circuits; those are separate from choosing a Monero node. Node login/authentication is not implemented in this preview. A saved node configuration does not start a connection.

## Scan state

The scanner starts at 128 blocks per batch and adapts between 32 and 512 based on measured batch duration, validates block numbering/linkage and transaction membership through the upstream interface, scans locally and persists an atomic checkpoint. The selected node must have the official genesis hash for the wallet's actual network: Mainnet or Stagenet for supported software/viewing wallets. This is a remote-node wallet, not an independently validating full node: the app does not validate chain work/consensus or protect against every malicious-node or eclipse attack.

Output keys are deduplicated locally. The wallet derives software-wallet key images locally and checks chain input images while scanning. It never asks a node for the spent status of wallet-specific key images. Global RingCT output numbering is maintained and checked against subsequent block responses. A restore beginning above genesis initially depends on the node's historical output-distribution claim. Decoy data also depends on that selected node; Tor does not make an actively malicious node trustworthy.

Version 0.11.2 groups quiet reads into a durable scan window of at most 2,000 blocks with a two-second target, while each individual read retains its adaptive 32–512-block and 32 MiB bounds. A slow read can exceed the time target. Receipt/spend/confirmation changes end the window after that read. Progress is returned only after the window commits, and a failure/cancellation discards unreported partial work. A single scanner/state clone is reused within each window. All explicit state changes and the pending journal still commit synchronously before broadcast. The transport pools at most one HTTP connection per immutable node/route session, reads responses fully, discards failures and never replays a failed send. See SYNC_0_11_2.md.

The last 128 checkpoint hashes support reorg rollback. A deeper reorg restarts at the original restore point. Outputs, spend marks and history roll back together. Outbound transaction reservations survive rollback and rescans. Each checkpoint commits through a temporary file, file fsync, rename and directory fsync. A storage failure stops the session, including a failure after rename, so the next session must reload the durable state.

The cache/journal is encrypted with ChaCha20-Poly1305 and random nonces. HKDF-SHA256 derives its key from the private view key with a versioned application context and wallet/mode namespace. Software and view-only caches use separate namespaces. An exclusive file lease prevents concurrent software engines for the same wallet. An unreadable/authentication-failed cache is preserved and never silently reset, because it may contain an uncertain payment reservation.

## Sync and send coordination (0.11.5 candidate)

See SYNC_SEND_0_11_5.md. New blocks may update reviewed input spent status without changing approved bytes. Reorgs, expired reviews and spent inputs still reject. Automatic checks are batched and pause through payment work. A live consented service may finish only a previously device-authorized publication in background; new construction/signing remains foreground-only. Signed delivery is blocked by the missing original private keystore.

## Send and node-list update (0.11.4)

See SEND_NODES_0_11_4.md for exact Max, post-send journal details, changed-tip review handling, session-scoped public-count caching and the five-node encrypted list. The 16-input and 0.05 XMR fee limits remain; Max rejects a wallet requiring more than 16 positive eligible inputs rather than silently sending only part of the balance.

## Software payment flow

1. Sync to the current selected-node tip. Validate the recipient's address on the opened wallet network, point encodings and positive exact atomic-unit amount.
2. Select unlocked local outputs, obtain a bounded fee estimate and use the upstream normal decoy policy with ring size 16. The preview supports up to 16 inputs and caps the fee at 0.05 XMR.
3. Construct and sign locally with CLSAG/Bulletproof+. Signing and online-session creation do not export secret keys. The vault flow separately passes a plaintext snapshot through Kotlin for Android Keystore encryption; its byte array is cleared afterward, without a guarantee of erasing all copies.
4. Parse the exact serialized transaction with no trailing bytes and confirm canonical round-trip serialization. Match its outputs, encrypted amounts, commitments and extra against the private local construction intent. Independently check the exact fee and expected local key-image set, verify every CLSAG and the range proof, and verify commitment conservation including the fee.
5. Show recipient, amount, fee and total. An unguessable review ID is bound to this native session, transaction and chain tip, and expires after five minutes. Require Android device authentication before confirmation.
6. Recheck the tip, then durably save the signed transaction and input reservation **before** broadcast. A timeout or rejection retains the reservation. Retrying sends exactly the same transaction bytes. A subsequent scan reconciles its confirmation; reorgs restore the reservation.

The broadcast API accepts only the native review identifier or an existing pending transaction ID. It cannot accept an arbitrary caller-supplied transaction or payment summary. Importing hardware-signed transactions is not enabled.

## View-only and UI behavior

View-only sessions never receive a spending key and cannot prepare payments. They show detected received outputs, not an authoritative spendable balance. That total may include spent funds and change. Verified external key-image import is not part of this integration.

The dashboard leads with the balance/spendable amount, Tor/sync state and Send/Receive actions. Activity follows below; full transaction IDs appear on demand. Receiving uses a locally encoded QR and a copy-address action. Sending and payment review use dedicated sheets. Node selection and privacy settings remain separate. The interface follows familiar wallet conventions without copying another application's branding or implementation.

The resume preview uses package `app.independentwallet.android.resumetest` and has separate storage from earlier previews. Screenshot/overlay protection, disabled backup and credential-gated vault encryption remain enabled. Screen-off/background locking keeps encrypted progress and cancels online children unless a consented bounded foreground service is scanning or checking for later blocks. Created setup now resumes from its encrypted checkpoint; explicit seed copying uses manual cleanup as documented in SETUP_RESUME.md. Authentication remains bounded to 60 seconds. Kotlin/Compose/FFI temporary copies cannot all be reliably erased.

Opening a connection first registers a cancellable native child with its offline owner. Seed reconstruction and encrypted-cache validation then run off the Android main thread without holding the owner mutex. Locking can revoke the child during initialization; cancellation is checked again after engine publication, and during cached-output validation. Tests cover locking before initialization and concurrent initialization/locking.

## Current limits

- RingCT outputs are supported. Historical pre-RingCT outputs, other accounts and subaddresses beyond the first 20 of account 0 are not covered. The receive sheet currently uses subaddress 1 when an engine is open, otherwise the primary address.
- Incoming mempool transactions are not scanned; activity appears after inclusion in a block. Additional time-based locks remain locked until a consensus-clock adapter is implemented. Normal output maturity and miner locks are enforced.
- Choose a restore height before the wallet's first receipt. A restore at height 0 scans from the beginning. This build does not silently skip history based on a node or phone-clock guess. Earlier rescans keep pending payment reservations. A user-confirmed forward start is allowed only for a locally empty wallet; the engine rejects it if outputs, history or payment records exist. First connections with no scan and start height 0 wait for an explicit range choice. Recent-block suggestions use the selected node tip minus 1,440, with a warning and required confirmation; no phone-clock or seed-date guess silently skips history. Pause waits for the current bounded batch and preserves its committed state.
- Outgoing address/fee details depend on the local encrypted journal. A seed restore cannot reconstruct all outgoing metadata from the public chain.
- A permanently rejected pending transaction remains reserved. A safe abandonment/reconciliation workflow requires further work; do not create a fresh payment merely because a broadcast timed out.
- Hardware signing/key-image sync and node credentials remain unavailable. Mainnet software wallets are supported from 0.11.0. The app keeps an original wallet slot plus a separate Mainnet slot, with encrypted setup/vault data isolated and network consent bound to the exact wallet/node/route, restored only after authenticated unlock. Background continuation is available only with explicit remembered consent and the foreground-service rules in BACKGROUND_SYNC.md. Keystone pairing uses the local QR scanner and remains viewing-only. Device/Keystore/camera execution and live Tor routing tests are recorded separately from host compilation and isolated-node tests.

## Primary references

- https://github.com/monero-oxide/monero-oxide/tree/731657ae3385be667abb556266369a497bc86f13
- https://docs.rs/monero-wallet/0.2.0/monero_wallet/
- https://docs.rs/monero-daemon-rpc/0.2.0/monero_daemon_rpc/
- https://docs.rs/arti-client/0.47.0/arti_client/
- https://arti.torproject.org/FAQs/
- https://github.com/monero-project/monero/blob/v0.18.5.4/src/cryptonote_config.h
- https://github.com/zxing/zxing/releases/tag/zxing-3.5.4
