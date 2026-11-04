Candidate changes in 0.11.5 (unsigned, Android code 22): see SYNC_SEND_0_11_5.md for batched metadata polling, payment pauses and approved-publication continuation. Original-key signing is still required for an installable update.

# Background synchronization and remembered connections

Current behavior: 0.11.4, Android build 21; background consent/lifecycle behavior from 0.11.3 is preserved. See CONNECTION_BALANCE_0_11_3.md for the upgrade and consent changes.

## Use and consent

Finish offline setup and save the wallet. Unlock, enable Allow background sync, allow Wallet sync notifications, and enable Network access for your own selected node. Wait for the ongoing notification before leaving. Network/background preferences are stored encrypted and bound to the exact wallet, endpoint and route. Old installations need one explicit connection after upgrading; later authenticated unlocks reconnect once automatically. Setup and saving a node remain offline. New nodes/routes require fresh network consent.

Leaving, pressing Back or turning the screen off locks the UI. A consented live foreground service may finish scanning and wait for later blocks, polling about every 30 seconds while the OS permits. Open the app and authenticate to return to that same owner; no second native engine/cache lease is opened. Turning Network access off or Stop and lock revokes reconnect permission. Explicit wallet lock cancels the current native owner and keeps the preference for the next authenticated unlock. Without background consent or a live service, leaving cancels current networking.

Pause/start-height selection stops an idle service; errors and service/system timeouts stop it. Verified catch-up alone no longer stops a healthy consented service. Error/disconnected/cached state never claims verified completion. All returned scan progress remains encrypted and durable. The app never unlocks keys or starts a service after process death, reboot or force stop. Reopening and authenticating can restore a matching saved preference.

## Memory and authority

Background continuation retains the active wallet in native memory during the bounded service session. For software wallets this includes material for local spent detection. It is not a view-key-only export, and the opt-in text discloses retention. No seed/private key is placed in service intents, notifications, preferences, saved UI state or additional files.

Sensitive UI/setup/recovery displays are hidden on leaving. Return requires device authentication. Only saved-wallet scanning, connection startup and idle tip polling can survive leaving. Payment preparation/review/sending, recovery viewing and setup cannot become background jobs. New polling is blocked during authentication or payment work. The service has no key-open, seed-display, signing, broadcast or automatic reconnect action. View-only/Keystone authority cannot gain a spend key.

The Application-owned ViewModelStore preserves the single owner across Activity recreation/Back. Node settings loading placeholders cannot disconnect it. Operation epochs and service connection IDs reject stale work. Hard lock destroys native owners/children. A new connection waits for prior work to drain. Remembered network consent is checked only against an authenticated saved wallet and the current valid, unedited saved node/route.

## Platform lifetime and power

WalletSyncService remains non-exported, dataSync-typed and START_NOT_STICKY. Starting requires visible-app consent or a matching restored preference after authenticated unlock, background permission and an enabled notification channel. No boot receiver, WorkManager restart, battery exemption or extra permission was added.

A bounded partial wake lock is acquired for active work and released while waiting for blocks and on destruction. A 5h50 deadline stops before an uninterrupted six-hour run; Android 15+ also enforces a shared dataSync budget. Service.onTimeout immediately locks/cancels the current owner while retaining the saved preference. Android/GrapheneOS may delay or terminate execution, and Network permission remains required. Background sync is not a guarantee of permanent connectivity while asleep.

The private notification shows route, fractional progress, current/target heights and expanded remaining/start counters, with Watching for new blocks after verified catch-up. Stop and lock revokes permission. Secret lock-screen visibility is retained. No balance, address, node URL, transaction, key or credential is included. Diagnostics contain only fixed safe operation/error codes and batch markers.

## Truthful progress and validation

The selected-node genesis and tip are checked before scanning. Progress uses native start/next/target heights only, remains indeterminate when unknown and returns after durable checkpoint commits. Reorg/rescan can rewind it. Known progress uses a static orbit and filled bar; decorative activity cannot invent blocks or completion. A restore from 0 can still be lengthy.

Host checks cover native transport/genesis/error propagation, strict progress ranges, encrypted preference payload migration/revocation, exact wallet/node/route consent matching, background scan/idle polling eligibility and payment/authentication exclusions. Real-node tests cover confirmation maturity, signed payments/reservations/recovery and reorgs. Instrumentation execution, phone notification/Keystore/service scheduling, screen-off/return authentication, timeout and live Tor require device tests; see VALIDATION.md for actual execution results.

Primary Android references:

- https://developer.android.com/develop/background-work/services/fgs
- https://developer.android.com/develop/background-work/services/fgs/service-types
- https://developer.android.com/develop/background-work/services/fgs/timeout
- https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
