# Mainnet software wallets — 0.11.0

The earlier preview intentionally restricted software wallets to Stagenet. A valid Mainnet node therefore failed the native wallet/network gate even after address, DNS and HTTP configuration succeeded. The user explicitly authorized adding Mainnet software-wallet support. AGENTS.md now records that scope while retaining the other authority, storage, routing and consent requirements.

## Software and viewing authority

New software setup defaults to Mainnet, with an explicit Stagenet test-coin choice in the original wallet slot. Generation, supported 16-word Polyseed/25-word legacy import and canonical private-spend-key import pass the selected network to the existing Rust key machinery. No seed derivation or randomness algorithm was replaced.

The engine accepts Mainnet and Stagenet software wallets. It parses the primary and recipient addresses using the wallet's actual network and verifies the selected node's genesis before attaching its RPC client. Testnet remains unsupported by the online engine. A mismatch receives NET_NETWORK, not an instruction implying that Mainnet is always hardware-only.

Scanning, local key-image/spent detection, fee limits, actual signed-byte/signature/intent verification, durable pending reservations, exact-byte retry and reorg handling remain in Rust. A Mainnet label does not elevate a node's trust. View-only and Keystone accounts have no local spending key, cannot prepare/confirm/retry a payment and keep unknown spent-balance coverage visible. Hardware signing/key-image sync remains unimplemented.

## Preserving an existing test wallet

Updating the APK does not alter the original wallet's network or keys. For a completed Stagenet software wallet, unlock it and choose **Use this wallet on Mainnet**. The confirmation explains that both wallets use the same recovery backup, the original wallet/history stay saved, and test coins do not transfer. Choose a Mainnet restore height before the first payment to that backup; 0 scans from genesis. Then approve the phone's device authentication.

The native copy method requires a verified backup and a Stagenet software origin. It reconstructs Mainnet material with the existing seed/private-key adapter and leaves the original snapshot unchanged. Private keys/seed words never cross to Kotlin during derivation. Only the existing bounded snapshot-for-encrypted-storage path crosses the vault boundary, with its temporary byte array wiped. Hardware/view-only wallets cannot use this operation to obtain software authority.

The destination is an additional authenticated encrypted wallet, not a replacement of the original. The original wallet-v1.bin/setup-v1.bin files and their existing Keystore aliases remain unchanged. The separate Mainnet slot uses wallet-mainnet-v1.bin/setup-mainnet-v1.bin and distinct wrapping/vault aliases. Each slot retains its own encrypted setup lease and completed wallet. Existing destination wallets or unfinished setups block a copy; creation never overwrites them. Selecting the Mainnet slot can instead generate/import an independent wallet.

After a durable copy, the original owner is locked and the Mainnet slot is selected in a locked, offline state. Unlock it and explicitly enable networking. **Original wallet** returns to the retained wallet. The only new preference stores the public slot name; it contains no keys, seed, setup checkpoint or connection consent. Switching cancels the old owner/network/service epoch and never connects. Interrupted writes preserve the atomic vault result; an interrupted selection can be recovered through the saved wallet controls.

The existing encrypted scan format is retained. Its namespace/key derivation already includes the complete network-prefixed primary address and authority class, so the same backup on Mainnet and Stagenet uses different cache IDs, encryption keys and locks. The historical cache AAD string is preserved for compatibility. Original scan files and pending-payment history are not deleted, copied into another network or relabeled.

## UI and routing

Setup, wallet/node settings, receive/send labels and the device credential prompt identify the actual network. Backup, save and unlock remain busy until their final storage checks complete, so profile/auth actions cannot start on a partially published result. Late authentication results remain bound to the old epoch and cannot create a wallet after lock/cancellation. A Mainnet copy needs a fresh explicit confirmation and device authentication.

Node settings remain the user's existing encrypted Direct/Tor choices. No node list, preset, automatic failover or protocol downgrade is added. An HTTP endpoint still requires explicit HTTP selection and carries the existing unencrypted-traffic disclosure. Tor stays inside Arti, Direct uses the Android system-DNS handoff from 0.10.3, and saving/selecting a node remains offline. A profile change revokes live network consent.

## Executed evidence and limits

See VALIDATION.md for actual counts and artifact identity. Host tests cover backup/secret equivalence, unchanged source snapshots, network-bound addresses/recipients, cache separation, native/JNA copy/snapshot/lifetime behavior and continued viewing-only authority restrictions. The isolated real-monerod regtest now uses Mainnet software addresses with the production genesis check, scans synthetic funds, constructs/validates/submits a signed payment, loses a reply, reopens/retries exact bytes, checks recipient maturity and exercises reorg recovery. The daemon is offline and loopback-only; no real funds or public-node broadcast is involved.

Android integration cases cover copy authentication, encrypted-source preservation, cancellation, activity recreation and restoring both slots. Compilation does not establish their execution. Actual Pixel/GrapheneOS Keystore/authentication, rendered UI, lifecycle/background scheduling, live public-network synchronization/Tor and real hardware are separate device-verification requirements. This is a developer preview, not a new independent security audit or production-readiness claim.
