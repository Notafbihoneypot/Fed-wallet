# Visible sync and optional Tor selection — 0.10.0

The Node screen and wallet dashboard now share an original Compose/Canvas sync card in the existing charcoal, green and amber pixel style. A moving amber orbit and quiet scan line indicate activity while real numeric counters show progress. No downloaded animation assets, fonts, Lottie engine, new permission or dependency was added.

## Truthful progress

Connecting has indeterminate activity and elapsed time while Tor and native node checks run. Scanning shows the actual next/target heights and percentage over the range beginning at the persisted native scan start. The new start_height status field comes from encrypted engine state, not the wallet's original restore height, so rescans and reopen remain accurate. Percentage stays below 100 until the connected native session reports synced, has a known target and is no longer executing a sync check. Reaching a numeric target without that confirmation shows Checking chain tip. Cached, disconnected, errored or inconsistent status cannot show Synced.

Sync work has its own flag, independent from preparing/reviewing/sending a payment. Every completed native batch updates status; a changed block counter refreshes the last-progress timestamp, including a reorg rewind. A slow batch shows seconds since the last counter change after 15 seconds. These are monotonic activity timers, not estimated time remaining or simulated block movement. No height is guessed before the node supplies one.

The view model clears syncing in finally and checks the existing network epoch before publishing results. Cancel/off/lock/late replies cannot finish a newer connection's animation. Existing native cancellation, encrypted batch checkpoints, backup/authority gates and pending-payment storage are unchanged.

## Animation and accessibility

The 32-cell stepped orbit is native Canvas drawing. Draw state animates only when progress is still unknown; known progress uses a static filled orbit plus a full-width determinate loading bar. Synced, paused and offline states are static. When ValueAnimator.areAnimatorsEnabled() is false, the orbit stays static while real counters/text continue updating. The active clock refreshes at one second, is disposed with the card and never renews the wallet inactivity timer. The animation itself does not start or keep work alive. Explicit background consent uses a separate bounded dataSync service.

The card includes text for every color-coded state, a spoken state/range, a polite heading announcement and a larger dial for increased font scale. Decorative pixels are excluded from accessibility output. Real large-font, screen-reader, reduced-motion and lifecycle behavior still need device execution as recorded in VALIDATION.md.

Version 0.10 exposes the real target height immediately after the native connection/genesis checks, before the first batch. It shows small percentages to two decimal places below 1% and retains block/remaining counts. Background scans use the same range and service notification. BACKGROUND_SYNC.md describes the lifecycle.

## Optional Tor route after sync

After actual completion in Direct mode, Switch to Tor or Set up Tor node opens the Node screen's Tor slot. It disconnects the direct session and preserves the direct node. Save your own v3 onion or HTTPS endpoint, then enable Network access explicitly. The new native session reloads the durable encrypted scan cache, validates the selected node and checks its current tip; it does not restart the scan from zero.

New settings default to normal direct startup/sync. Direct supports HTTP/HTTPS, exposes the client IP to the node and leaves HTTP unencrypted. Tor uses embedded Arti for streams and destination DNS and never falls back to Direct. Existing Tor selection persists on upgrade. Selecting/saving either slot never connects. There are no preset nodes, discovery or fallback. NETWORK_ACCESS.md describes transport and migration boundaries.

The card retains its original animation and native counters. Direct connection displays LINK and node checks; Tor displays TOR and Tor startup/node checks. Route labels and sync/send messages describe the actual native session. An animation cannot establish either routing or completion.
