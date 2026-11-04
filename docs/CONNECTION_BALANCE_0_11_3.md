# Remembered connections and spendable XMR — 0.11.3

Date: 27 October 2026 (America/New_York). Android version 0.11.3-sync-preview, code 20; Rust workspace 0.11.3.

## Upgrade and use

Install this APK over Independent Sync Preview without uninstalling or clearing data. Unlock the same wallet and enable Network access once. Keep Allow background sync enabled and permit Wallet sync notifications if you want updates while away. Earlier releases did not store network consent, so an old installation needs this first explicit connection. No wallet recreation, new node or rescan is needed.

After that, a later authenticated unlock automatically attempts the same saved wallet/node/route connection. It makes one automatic attempt per unlock, with an explicit retry on error. The Network access switch represents the remembered permission as well as the active session; the phase and progress display describe actual connectivity. A failed connection does not remove the user's ability to turn the remembered permission off.

Turning Network access off or choosing Stop and lock revokes automatic reconnect. Ordinary wallet locking destroys the current native owner but keeps the preference for the next authenticated unlock. Changing the saved node or route revokes reconnect permission, even when switching back; saving a node alone does not connect. Unsaved/invalid node drafts block new connections. Other wallet slots and empty Tor slots cannot inherit a grant. Setup remains offline, and unsaved wallets cannot connect automatically.

## Background updates after catch-up

The previous service stopped immediately on reaching the tip. Leaving afterward therefore destroyed the connection, and confirmations could stop advancing until another manual connect. Its background checkbox was also lost when the UI state was reset.

An opted-in service now keeps the existing native wallet/connection available after catch-up and checks for new blocks about every 30 seconds while Android permits execution. Background UI remains locked. The service cannot unlock a wallet, prepare or send a payment, display keys, create another engine or restart itself after process death/reboot. Returning to an existing service-owned wallet requires device authentication. Polling is blocked during authentication, payment review, errors and a manual pause. A never-scanned zero-height wallet still waits for its explicit scan-start choice.

The notification remains visible while watching for blocks. Its details say Watching for new blocks when caught up; progress continues to come from live native state. No balances, addresses, node URLs, keys or transaction data appear in it. The CPU wake lock is released while idle and acquired for active work, with the existing bound. Stop and lock revokes remembered network permission before stopping the current owner.

The app retains its 5h50 service deadline and Android's dataSync budget/timeout handling. Android may delay background polling or terminate the service/process. A timeout stops the current session without deleting the saved preference; reopening and authenticating can resume it. This does not promise permanent execution while the phone is asleep or after force stop. No new permission, battery exemption, boot receiver or scheduler was added.

## Received versus spendable

Received XMR can be visible before it is spendable. The engine continues to enforce its existing 10-confirmation maturity for normal receipts, 60 for miner outputs, additional height locks and pending-payment input reservations. Time-based additional locks remain unavailable until the existing consensus-clock adapter limitation is addressed. No unlock rule or signing authority is relaxed.

Native status now reports the number of additional scanned blocks until the earliest eligible unspent, unreserved output unlocks. The dashboard explains that waiting state. It requests a fresh sync when its balance/confirmation information is stale or disconnected, identifies additional locks when no height can unlock them, and retains the pending-payment reservation explanation. Viewing wallets continue to show received outputs with unknown spent status, without a software spendable balance.

The real-node regression sends one synthetic XMR to a fresh software receiver. After the first confirmation, balance is 1 XMR, spendable is 0 and nine blocks remain. After eight further blocks it remains locked with one block left. The tenth confirmation makes 1 XMR spendable and removes the remaining-block count. The test also retains lost-reply recovery, exact-byte retry, durable reservations, encrypted reopen and reorg checks. No public-node transaction or real funds were used.

## Preference storage and consent

Preferences live in the existing Android Keystore-encrypted, no-backup node file. The IWN3 payload contains at most one record per wallet slot: wallet address, route, a SHA256 fingerprint of the canonical endpoint/route, reconnect permission and background preference. It contains no seed, private key or signed transaction. This fingerprint binds local consent; it is not a new Monero cryptographic construction. The other saved endpoint and wallet preference remain intact during updates.

Old single-URL and IWN2 payloads remain readable and do not acquire consent during migration. Records are bounded and reject malformed flags, lengths, duplicate wallet slots and unsupported values. Mutations use the existing encrypted atomic write and process-wide monitor; preference writes are serialized. A queued grant cannot attach itself to a concurrently changed node. Revocation also works after an error or while the node draft is invalid. Storage errors are visible and cannot silently authorize a new connection.

The selected HTTP/HTTPS protocol, Direct/Tor route, Android DNS handoff, TLS hostname/certificate checks, genesis check, response bounds, connection pooling and no automatic broadcast replay remain. There is no default node, failover, HTTP downgrade or remote scanning/key upload.

## Validation scope

See [VALIDATION.md](VALIDATION.md) for final test/build/signature results. Host tests cover preference migration/round-trip/revocation and wallet/node/route/unlock guards; background polling eligibility; balance messages; and the native maturity boundary. Actual phone background scheduling, encrypted Keystore preference round-trip, credential return, service timeout and notification rendering require on-device execution and are not claimed by compiled instrumentation.

Primary references: [Monero CLI balance guide](https://www.getmonero.org/resources/user-guides/monero-wallet-cli.html), [Android foreground-service timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout).
