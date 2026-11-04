# Automatic updates and sending — 0.11.5 candidate

Date: 27 October 2026 (America/New_York). Android code 22. Signed delivery is blocked by the missing original private preview signing keystore; see PREVIEW_SIGNING.md. Keep the installed app and its data.

## Findings

Build 21's timer called the sync path every 30 seconds. An unchanged tip did not download the blockchain again, but it still entered the scan UI and reset scan/review state. The timer also lacked a payment-entry state, so it could interrupt the form before a native review existed. Poll eligibility already blocked an existing review/authentication, but the service collector used a different background-continuation predicate: it stopped the service when a review appeared. A later hide/return could therefore lose the network owner.

Native confirmation required equality with the latest height. A new ordinary block during a slow Tor preparation or device authentication caused a fresh quote/signature and another approval even when the reviewed transaction was still valid. Leaving during an approved publication also treated it like unapproved construction and destroyed its network owner. These paths can explain interrupted sends; no fresh phone log was supplied to prove which one caused the reported Tor failure.

## Scheduling and UI

Normal automatic checks wait about five minutes. A durable pending transaction shortens that to about one minute. Android may delay those timers; no wake alarm/restart job is introduced. Sync now and a user-started payment catch up immediately.

A timer first reads tip metadata. An unchanged tip preserves the displayed scan state and performs no block-body download/checkpoint write. A changed tip scans only the new range from the saved checkpoint in bounded batches. Receiving/spent detection, reorg checks and maturity rules still inspect the relevant new blocks; skipping them could produce an incorrect balance. Restore height is never advanced to suppress updates.

Payment entry is explicit view-model state. Automatic checks stop during entry, preparation, review, authentication and publication; manual refresh/rescan cannot race that work. Dismissal or completion starts a new timer delay instead of releasing a queued immediate sync. A foreground review/authentication keeps the existing service alive and idle. Notifications show payment work as indeterminate rather than claiming synchronization or submission has completed.

Leaving an idle, unsubmitted form/review discards that review and locks the UI while retaining permitted read-only background sync. Leaving unapproved construction cancels it. A previously device-authorized signed transaction may finish its immutable publication in a live, explicitly consented service session. The service never initiates a payment, opens keys, signs, replaces/requotes it, retries it automatically, or reopens the owner after process death. A changed/expired review discovered while hidden stops; fresh construction requires visible authenticated foreground use.

## Native review safety

The review retains its creation time, transaction bytes, input IDs and chain anchor (height/hash). Confirmation may catch up at most 128 new blocks and remains bounded by the existing five-minute review lifetime. It checks the original anchor before/after catching up, scans new input spent status locally, and rejects missing, spent, reserved or no-longer-eligible inputs. Ordinary new blocks do not alter the reviewed recipient amount, fee, transaction ID or bytes and do not cause signing again.

An anchor replacement/reorg, expiry, excessive lag or input conflict rejects the old review before a new reservation/broadcast. In the visible foreground, the existing recovery path can present a new quote requiring fresh device approval. In background, it cannot construct/sign that new quote. The node still admits/rejects a transaction against its current chain/mempool; the final network race cannot be made atomic with a remote node. Unknown replies retain their exact durable signed transaction and reservation, with explicit exact-byte retry only.

Actual serialized-byte intent checks, key images, CLSAG/range-proof/commitment verification, maturity, fee/input caps, chain/recipient network isolation, Direct/Tor boundaries and encrypted state formats remain. No key-image spent-status query is sent to a node. No default/alternate node, automatic resolver fallback, TLS downgrade or Tor-to-Direct fallback is added.

## Build and validation scope

See VALIDATION.md for executed tests and artifact hashes. Native regressions cover unchanged-tip polling without block downloads/writes, preserving a review against a timer, exact approved bytes after ordinary new blocks, expiry/reorg rejection, a competing transaction spending the same input, and previous Max/lost-reply/reopen/maturity checks. Host policy tests cover service lifetime versus poll permission, payment/authentication exclusions, consented publication, background signing prohibition, timer intervals and private notification contents.

Phone lifecycle, Android Keystore/notification execution and live Tor behavior require device verification. An unsigned build cannot be installed as an in-place update; original-key signing remains a separate unresolved blocker.

Primary reference for incremental wallet refresh: https://docs.getmonero.org/rpc-library/wallet-rpc/#refresh
