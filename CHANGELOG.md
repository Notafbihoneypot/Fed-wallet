# Changelog

## 0.11.11 Fed POS NFC source candidate — 3 November 2026 (not built)

- Add a separate merchant NFC Forum NDEF URI reader mode alongside the existing IWM1 phone-to-phone HCE mode. Untrusted merchant requests must contain a single URI record, a matching-network Monero address and a positive, precise amount. Neither NFC mode prepares or sends a transaction.
- Merchant NFC requests prefill and lock the Send sheet's destination and amount until explicitly cleared; final fee/recipient review and authentication remain mandatory. QR scanning still uses the ordinary Send workflow.
- Fix the pre-existing Kotlin payment parser syntax error detected by standalone host compilation and validate legacy NFC network prefixes earlier. Add host-only smoke checks and JUnit source tests.
- Rust wallet engine/FFI, existing wallet cache, node profiles, signing identity and protocol dependency lockfile are unchanged. POS merchant account-1 scanning, authenticated view-key pairing, merchant settlement and tablet builds are **not** implemented; Fed POS 0.2 source archive was not supplied. No APK is available from this source candidate.

## 0.11.10 fingerprint and recipient scanning — 29 October 2026

- Defer biometric AAD operations until successful per-use CryptoObject authentication; fence stale callbacks, check sensor readiness, and preserve actionable PIN fallback. Replace abandoned enrollment aliases only under a fresh security grant without overwriting a saved biometric wrapper.
- Add a local camera scanner to Send for raw Monero addresses and supported payment URIs with exact optional amounts. Preserve explicit review/authorization; stop competing NFC reads and reject ambiguous or unsupported fields.
- Share the existing CameraX/ZXing decoder with Keystone scanning and test QR rotations and request parsing. Android code 28; Rust sources, native binaries and dependency versions unchanged from 0.11.9.

## 0.11.9 NFC preview — 28 October 2026

- Add opt-in one-shot phone-to-phone NFC receiving-address exchange; payment still requires amount entry, fee review and explicit authorization. Physical NFC interoperability remains untested.
- Fence each PIN migration conversion and readback against app-lock revocation with consistent file/authorization lock ordering.
- Retain both unresolved upstream cryptography advisories and the prior device-security test limitations. Android code 27; Rust native libraries are unchanged from 0.11.8.

## 0.11.8 wallets and app lock — 28 October 2026

- Consume post-send receipt events once; preserve payment details for manual viewing.
- Simplify Mainnet setup and provide a named-wallet picker while preserving older wallets.
- Add freshly authenticated recovery phrase and key viewing with software/view-only/hardware authority separation.
- Add optional app PIN, per-use strong biometric unlocking, resumable legacy encryption migration, and opt-in duress erasure scoped to this app.
- Retain Preview 2 signing identity, explicit Tor/node selection, transaction verification and subaddress behavior. Android code 26; Rust 0.11.8.

## 0.11.7 Tor payment preparation — 28 October 2026

- Replace the full first-request decoy distribution with bounded public-count pages, preserving the same upstream decoy-selection algorithm and complete distribution.
- Resume completed, encrypted parts after lock/retry; isolate the cache by wallet, selected endpoint, route and checked chain hash. Update only the tail when complete, and discard mismatched chain/source data.
- Shrink only timed-out/oversize public-count reads on the same route and endpoint; retain the 90-second request limit and response caps. Never replay ring/output requests or broadcasts.
- Show actual preparation stages and saved download size through a local progress API that does not take the engine mutex; preserve decoy lookup network errors and record only closed stage/route/error labels.
- Explicitly forbid background payment preparation. Keep fee review, device authorization, cryptographic/intent verification, fsynced reservations and exact-byte lost-reply handling.
- Preserve Preview 2 package/signing identity, data and all receiving-address/scanner behavior. Android 0.11.7-preview2 code 25; Rust 0.11.7; third-party dependencies unchanged.


## 0.11.6 receiving addresses, scan performance and security — 27 October 2026

- Create/select/label receiving subaddresses offline and persist their indices/selection/labels encrypted before display. Support account 0, primary plus up to 1,000 subaddresses; retain metadata through rescan and recover used indices from old cached outputs/scans.
- Watch the entire creatable range after seed restore. Reuse the upstream scan table, index unspent images once per window and compute pending-input reservations once per status instead of nested ledger searches. No block/verification/checkpoint shortcut is introduced.
- Bind QR frames to their address, use a bounded lazy address list and retain cancellable single-owner cache operations with no offline network construction or late cross-wallet UI adoption.
- Disable installer Java/native debugging; retain the Preview 2 package/certificate. Update ctutils 0.4.3 -> 0.4.4 for RUSTSEC-2026-0385. Document two remaining upstream vulnerability alerts and three unmaintained transitive packages without suppression.
- Rust 0.11.6, Android code 24. Cache metadata gains backward-reading optional fields; existing wallet/setup/node/journal formats and pending reservations stay intact. No key rotation or app-data deletion is performed.


## 0.11.5 Preview 2 signed channel — 27 October 2026

- Deliver the existing 0.11.5 sync/send fixes as a separately identified app after the user's request for a new signed installer; app.independentwallet.android.syncpreview2, Independent Preview 2, Android code 23.
- Preserve the old installation by supporting side-by-side installation; no silent protected-data, node or pending-payment-journal transfer is introduced.
- Use a new protected RSA-4096 APK signing identity with separate owner backup/recovery files; remove the public debug-password assumption. Do not confuse it with the missing original identity or wallet recovery keys.
- Update disposable instrumentation guards, permission guidance and build/signing/recovery documentation. Rust/native production code and dependency pins remain unchanged from the tested code-22 candidate.

## 0.11.5 automatic sync and send coordination — 27 October 2026

- Batch idle checks at five minutes (pending payments one minute); inspect tip metadata first and avoid resetting scan/review state for an unchanged tip.
- Pause automatic checks through payment entry, preparation, review, device authentication and publication. Keep the notification/service alive while foreground payment work pauses polling.
- Let previously device-authorized immutable signed publication finish in an opted-in background session; never prepare/re-quote/sign there. Idle unsubmitted reviews are discarded on leaving while permitted read-only sync continues.
- Catch up input spent status through a bounded review window without changing approved transaction bytes for ordinary new blocks; reject forks, expired reviews and spent inputs before reservation/broadcast.
- Regenerate bindings for pollUpdates, retain wallet/cache/node formats, package, privacy boundaries, dependencies and permissions.
- Android code 22, Rust 0.11.5. Original private signing keystore was removed by workspace maintenance. Candidate can be compiled unsigned; signed update remains blocked pending restoration of the matching key. Last signed build remains 21.

## 0.11.4 send review, Tor preparation and five saved nodes — 27 October 2026

- Add native Max with exact fee deduction, standard change/output shape, existing input/fee caps and full signed-byte verification.
- Review recipient amount, fee, total, network and route. Requote a changed chain/review and require fresh device authorization; local input errors preserve connection state.
- Show durable journal payment details, submission acknowledgment/uncertainty, total cost, transaction ID and scanned block confirmations.
- Cache public cumulative output counts within the same chain/session; increment with anchored overlap after a new tip. Keep random decoy selection and actual output requests unchanged.
- Save up to five named user nodes, explicitly choose/edit/remove them and preserve old endpoints/preferences. No shipped list, automatic failover or route downgrade.
- IWN4 extends the existing encrypted node payload; wallet/setup/cache formats, package/certificate and dependencies remain compatible. Android code 21, Rust 0.11.4.

## 0.11.3 remembered network access and balance confirmations — 27 October 2026

- Remember network/background preferences encrypted, scoped to the exact saved wallet, endpoint and route. Reconnect once after authenticated unlock. Turn network access off or Stop and lock to revoke; changing nodes/routes revokes access. Old settings migrate without gaining new consent.
- Keep an opted-in bounded background service checking for new blocks after catch-up, with locked UI, authenticated return, no background signing or process-death/boot restart, and CPU wake-lock release while idle.
- Show why received XMR is not spendable and the native number of blocks until the next unlock. Keep the existing normal/miner maturity and additional timelocks, spent detection and durable payment reservations.
- Regenerate UniFFI bindings and both Android native ABIs; retain Sync Preview signing/package identity. Android code 20; Rust 0.11.3.

## 0.11.2 connection reuse and durable scan windows — 27 October 2026

- Reuse one HTTP/HTTPS connection per selected node/route session; fully drain successful responses, reject expired/closed connections before reuse, and close idle sockets on cancellation. Keep original Host/SNI, TLS verification, Tor isolation and response/time bounds. Never automatically replay an attempted broadcast.
- Coalesce quiet reads into durable windows up to 2,000 blocks with a two-second target while preserving smaller adaptive fetches. Checkpoint receipts/spends/confirmations after the current read. Publish only saved progress, discard partial windows on failure/cancellation, and retain immediate payment-reservation commits.
- Reuse scan state/scanner within the window and reduce repeated metadata checks, disk writes and progress-state work. Preserve earlier reorg/rescan, pending-payment and explicit network/background/lock behavior.
- Add persistent-connection, dropped-response, idle-cancellation and no-broadcast-replay tests plus full synthetic-chain checkpoint/failure/reopen/throughput comparisons.
- Android code 19; Rust 0.11.2; same Sync Preview identity and unchanged encrypted formats/dependency pins/permissions.

## 0.11.1 sync start, batch throughput and notification progress — 27 October 2026

- Ask where an unscanned zero-height wallet should begin; add pause, explicit recent-block selection for new wallets, and bounded manual scan-start changes. Never skip locally recorded funds/history or durable payment reservations. Keep the prior start until the user confirms and applies a change.
- Start scan batches at 128 blocks and adapt between 32 and 512; keep all block/transaction/output validation, response bounds, cancellation and atomic checkpoints.
- Fetch only the one cumulative output count immediately before a nonzero start, instead of downloading all earlier counts.
- Show precise early percentages, current/target block heights and expanded remaining counts in the private foreground notification; preserve verified completion and Stop and lock. Stop idle services when a scan pauses or waits for its start choice.
- Retain the Sync Preview application/certificate, unchanged encrypted formats and Mainnet/Stagenet authority. Android code 18; Rust 0.11.1.

## 0.11.0 Mainnet software wallets and preserved test-wallet upgrade — 26 October 2026

- Enable Mainnet software generation/import, local scanning and signing while retaining network-specific genesis, address/recipient validation, signed-byte verification and pending-payment protection. Stagenet remains available as an explicit test network.
- Add an authenticated separate Mainnet copy for existing completed Stagenet software wallets, using the same supported backup through existing native derivation adapters. Preserve the original encrypted wallet and cache, allow an explicit Mainnet restore height and keep the new wallet locked/offline until opened.
- Add separate encrypted Mainnet vault/setup aliases and saved wallet selection; switches revoke the old owner and do not connect. Existing original files/aliases and encryption formats remain unchanged.
- Keep view-only/Keystone authority unable to sign, improve network mismatch diagnostics, and show the actual network throughout setup, receive/send and authentication UI.
- Test a full Mainnet-addressed software scan/sign/submission/reopen/retry/confirmation/reorg flow against an isolated offline monerod fakechain with the production genesis check. Retain Direct/Tor, DNS/TLS, backup, clipboard and storage checks.
- Raise Android version code to 17, Rust core to 0.11.0, and retain the Sync Preview package/certificate.

## 0.10.3 Android system DNS and Direct connection handoff — 26 October 2026

- Resolve Direct hostnames through Android, then pass only bounded numeric addresses to Rust for the active session. Preserve the selected URL hostname and port for HTTP and HTTPS authentication; keep all genesis/RPC checks. Literal addresses skip DNS.
- Use cancellable Android DNS on API 29+ for fully qualified names, with the platform InetAddress compatibility path for local names/API 28. Keep Tor resolution inside Arti, with no Direct fallback or alternate DNS provider.
- Replace opaque Direct NET_DNS failures with fixed missing-name, empty-answer, DNS-server/refusal, network, permission and timeout diagnostics. Native compatibility lookup also preserves known permission/network/timeout error categories. Never log resolver exception text, node hosts or addresses.
- Keep resolved addresses only in memory until disconnect. Retry/reconnect performs a fresh lookup. Saving/selecting node settings remains offline.
- Bump Android version code to 16 and the Rust core to 0.10.3, regenerate bindings, and retain the existing Sync Preview package/signing identity and encrypted storage formats.

## 0.10.2 explicit protocol and saved-node connection controls — 26 October 2026

- Add a visible HTTP/HTTPS protocol choice. Bare hostname/IP plus RPC port uses that selection; full URLs retain their explicit scheme. Canonicalization and route checks still finish in the unchanged Rust parser.
- Prevent new connections on the node and wallet screens from silently using an older saved endpoint while the current node draft is edited, invalid, loading or saving. Editing alone does not cancel a live scan.
- Validate the canonical endpoint again at connection entry, and check Android's INTERNET permission before DNS/native networking. Denied Network access receives an explicit NET_PERMISSION error rather than relying on a resolver failure.
- Keep the same application/certificate with Android version code 15. Preserve native wallet binaries, storage formats, key handling, transaction authority and Direct/Tor boundaries.

## 0.10.1 node URL input and scan continuity — 26 October 2026

- Accept surrounding pasted whitespace and the exact `/json_rpc` or `/json_rpc/` suffix through a local input adapter, then validate the canonical base URL with the unchanged Rust parser.
- Distinguish missing protocol, invalid port, IPv6 brackets, credentials, unsupported paths/queries, malformed addresses and Direct/Tor mismatch using fixed messages without echoing input.
- Validate a new node before revoking an active connection. Invalid input and re-saving an equivalent endpoint leave the existing scan alone; valid endpoint changes still require fresh explicit connection consent.
- Make the URL field single-line and report oversized paste attempts while preserving the previous draft.
- Retain the Sync Preview package/certificate, bump Android version code to 14, and preserve wallet/setup/node/cache formats and the 0.10.0 native core.

## 0.10.0 background sync, connection and progress fixes — 26 October 2026

- Add explicit background-sync permission, notification with Stop and lock, bounded dataSync service/wake lock, shared wallet owner across Activity recreation and authenticated UI return. Stop on completion/error/timeout; never restart after process death.
- Keep node changes/explicit lock hard-cancelling. Prevent empty loading placeholders and stale service/operation callbacks from breaking a live or replacement connection.
- Expose target height before the first block batch, add a full-width real loading bar and fine percentages under 1%, and stop decorative spinning when measurable progress is available.

- Fix immediate rejection of valid uncompressed HTTP RPC responses labeled Content-Encoding: identity. Explicitly request identity, retain all bounds and reject compressed/mixed coding.
- Preserve static error categories through the native engine/UniFFI boundary instead of labeling every failure invalid/unavailable/too large.
- Distinguish Android network permission, DNS, TCP refusal/reachability, TLS, HTTP status, Tor startup/node, actual size and protocol errors without echoing private input.
- Keep a failed connection retryable with explicit consent and preserve saved node/mode/scan progress. Record only closed error/route codes in Android diagnostics.
- Add regression coverage for the original failure, complete identity-labeled node handshake/genesis verification and actual native-to-JVM HTTP/TLS error propagation.


## 0.9.0 normal node startup and sync, then Tor — 25 October 2026

- Add an explicit native Direct route for normal HTTP/HTTPS Monero RPC nodes; direct startup does not initialize Arti.
- Default new settings to Direct and offer Switch to Tor/Set up Tor node after real sync completion. Tor accepts a user-chosen v3 onion or HTTPS endpoint and has no direct fallback.
- Keep routes separate in encrypted node slots, preserve existing selections and cancel old sessions before reconnecting with their durable scan cache.
- Display Direct/Tor state, route-specific startup/sync/send text and the direct-IP/plain-HTTP privacy cost before network consent. Preserve the native sync animation, percentages and heights.
- Keep the 0.8 application/signing identity for an in-place update. Keys, transaction policy, wallet/cache encryption, clipboard, backups and stagenet/mainnet authority limits remain unchanged.


## 0.8.0 animated sync — 25 October 2026

- Add an original native pixel-orbit/scanning animation with real percentages, block range/remaining counters and explicit connecting/checking/paused/synced states.
- Expose the engine's persisted scan start through the Rust/UniFFI status record so rescans and reopened caches use the correct range; rebuild native libraries/bindings without changing encrypted formats.
- Track live sync work and progress timestamps separately from other network/payment operations. Stop motion at completion/off/pause and respect system-disabled animations.
- Gate completion on the connected native session and verified synced flag; unknown, cached, error and fresh-tip-check states cannot display a fabricated 100%.
- Offer optional onion-node setup/selection after sync. The action opens settings, preserves both slots and leaves network access off until re-enabled.
- Add a separate sync-preview package because workspace maintenance removed the earlier private debug signing key. Keep v0.7 installed; storage is separate.

## 0.7.0 network controls — 25 October 2026

- Expose Network access, scan progress and Sync now on the node screen and dashboard, using the existing Rust Tor/scanning engine.
- Add separate encrypted HTTPS/onion endpoint slots, an explicit Use onion node switch and migration of the existing saved node. Empty slots do not fall back; saving/switching stops access and never reconnects automatically.
- Check for new blocks every 30 seconds while unlocked/connected, skipping active operations, authentication, reviews and errors. Lock/background/off cancels the foreground loop and native requests.
- Remove the cloud-backup Settings entry while retaining disabled platform backups. Add a link to system app settings when a network error may involve GrapheneOS permissions.
- Keep resume/discard, software stagenet restrictions and Keystone mainnet viewing boundaries.

## 0.6.1 resume and discard — 24 October 2026

- Keep Resume setup and add explicit Discard setup on paused and unfinished-wallet screens.
- Confirm and authenticate discard, finish queued checkpoint writes, and bind deletion to the exact encrypted draft. Cancellation keeps setup; stale confirmations cannot delete newer progress.
- Permit discarding without a seed or draft-decryption key. Verify deletion before offering a fresh setup; protect completed wallets and retain copied clipboard text.
- Correct stale Settings text about timed clipboard cleanup and Keystone pairing.
- Update the 0.6.0 resume preview in place with the same package and signing identity; retain the unchanged Rust core and encrypted storage formats.

## 0.6.0 resumable setup — 23 October 2026

- Replace automatic unfinished-wallet discard with credential-protected encrypted setup checkpoints and Resume setup.
- Save the created wallet before displaying its keys; retain backup-verification status, original challenge, step and checkpointed partial answers across pausing/reopening.
- Keep the native spending/network gate closed until backup verification; preserve corrupt/conflicting files rather than replacing them.
- Remove exit/lock/page-close/timer seed-clipboard cleanup. Keep explicit Clear copy, sensitive metadata and nonsecret owner tracking across restarts.
- Preserve native/clipboard separation: screen locking still hides keys while durable wallet/setup files remain.
- Add a separate resume preview package; device tests remain distinct from host validation.

## 0.5.0 Keystone view-only companion — 23 October 2026

- Simplify setup to Software wallet and Hardware wallet (Keystone). Software keeps local offline generation and seed/key import.
- Import Keystone's normal Monero/Feather connection JSON or UR bytes through a bounded Rust parser and local QR camera; require a primary-address comparison before saving.
- Preserve hardware viewing authority in encrypted snapshots, with no spend key or recovery phrase on the phone.
- Enable mainnet viewing only for Keystone accounts, with network-specific genesis checks and receiving-address prefixes. Software payments remain stagenet only.
- Keep hardware sending disabled and received totals visibly distinct from an authoritative spent balance; key-image sync and external signing need further work.
- Use a separate companion preview package with a new local debug signing identity.

## 0.4.0 retro and seed privacy — 22 October 2026

- Add an original dark green/amber retro theme, stepped surfaces, monospace headings and a pixel wallet icon.
- Add opt-in sensitive seed copying with an ownership-checked cleanup timer and explicit clipboard risk disclosure.
- Add credential-gated viewing for saved seed wallets, a bounded seed display and foreground inactivity locking.
- Test both seed-generation failure paths, one MiB of OS RNG output for gross defects, and fresh/restore behavior for 128 generated wallets.
- Review selected Monero and Feather seed/RNG controls; keep seed formats and cryptography unchanged.
- Use a separate retro stagenet test channel and a local preview signing identity.

## 0.3.0 stagenet integration — 21 October 2026

- Add the Rust monero-oxide scanning/signing adapter and embedded Tor transport, requiring HTTPS or v3 onion nodes.
- Add encrypted scan checkpoints, local spent detection, reorg rollback and durable exact-transaction retry reservations.
- Add software payment review/authentication while preserving view-only authority boundaries.
- Replace the foundation display with a balance-first native dashboard, activity rows and Send/Receive sheets with local QR receiving.
- Use a separate stagenet package so the test build installs beside the offline foundation.

## 0.2.1 security review — 21 October 2026

- Serialized wallet creation across vault instances and corrected the saved/unsaved state after conflicting saves.
- Bound connection tickets to their issuing gate.
- Restricted source packaging, rejected symlinked inputs, and isolated cargo-ndk from credential environment variables.
- Updated/checksum-pinned Gradle 8.14.6; pinned JDOM 2.0.6.1 in build resolution and the Android NDK version.
- Reduced secret-buffer reallocations and zeroized temporary Polyseed coefficients, preserving algorithms and wordlists.
- Kept one outstanding credential request, rechecked import heights at action time, improved failed-session cleanup, and added content-capture/overlay protections.
- Added adversarial input, shared-vault and real host UniFFI tests. See docs/VALIDATION.md for actual execution and device limits.

## 0.2.0 offline integration — 21 October 2026

- Added offline Rust software-wallet creation/import, deterministic private-key import and matching view-only restoration.
- Added an Android-authenticated encrypted vault, backup confirmation and lifecycle/session locking.
- Added strict seed-format boundaries and narrow malformed-input fixes to vendored seed utilities.
- Fixed native build flags and Android-specific generated object cleanup.
- Added exact-time authentication expiration, stale-result protection and explicit backup/transfer exclusions.
- Cross-compiled arm64/x86_64 libraries and assembled a stagenet-only developer APK. See docs/VALIDATION.md for executed tests and limits.
- Kept Tor, chain scanning, balance display, spending and hardware pairing unavailable pending their integrations.

## 0.1.0 foundation — 20 October 2026

- Created an independent Kotlin/Compose Android shell.
- Added the Rust policy core and generated UniFFI Kotlin bindings.
- Added exact amount handling, explicit node selection, connection generation checks and semantic payment matching.
- Added encrypted local node configuration and restrictive Android defaults.
- Added 17 host tests for security-related behavior.
- Kept real wallet creation, seed import, synchronization, Tor transport, hardware signing and broadcasting unavailable pending engine integration.
