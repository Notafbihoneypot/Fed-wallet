# Node entry and connection selection — 0.10.2

This change addresses two confirmed application behaviors. The form rejected a bare hostname/IP and RPC port because it required a full protocol prefix. Also, editing the visible node field did not change the saved endpoint supplied to Network access; a connection could still attempt the previous saved hostname. The latter can make retries appear to ignore an address correction.

The new form displays an HTTP/HTTPS choice. Enter your own host/IP and RPC port, or paste a full URL. A bare entry uses the displayed protocol; a full HTTPS URL is not downgraded by an HTTP form selection. Pasting a full URL updates the displayed protocol to match it. Changing the protocol button rewrites an existing explicit HTTP/HTTPS prefix as an intentional edit; it does not connect or save. The initial empty form visibly selects HTTP and discloses that it is unencrypted. The protocol choice is independent of the Direct/Tor route.

Surrounding pasted whitespace and the exact /json_rpc suffix continue to work. The final complete URL is bounded to 2048 characters and validated by the unchanged Rust parser. Numeric ports, IPv6 brackets, credentials, queries/fragments, interior whitespace and malformed schemes remain checked. No RPC port, node, route or failover endpoint is supplied. Arbitrary proxy path prefixes and node credentials remain unsupported.

## Save before connecting

A shared connectionNode gate supplies an endpoint only when the saved canonical URL matches the current draft and protocol and settings are loaded, valid and idle. Both the node screen and wallet dashboard use it. Editing the host or protocol disables new connection/retry actions until Save succeeds. The screen explains the pending save and shows the actual saved endpoint. A rejected oversized paste also blocks a new connection while its error is displayed.

Editing alone does not revoke an already active scan. Saving an equivalent canonical node preserves that scan. Saving a valid different node still cancels the old session and requires fresh explicit network access. Invalid input never overwrites saved settings. Direct and Tor slots remain independent; an empty Tor slot cannot fall back to Direct.

Connection entry validates its canonical URL again before native setup. It also checks Android's INTERNET permission. A denied GrapheneOS Network permission now receives NET_PERMISSION and app-permission guidance before any native hostname lookup. Permission restoration does not automatically reconnect. DNS/network errors still leave a failed connection offline and retain encrypted progress.

GrapheneOS documents that its Network permission is based on INTERNET, is per-profile, and can return ordinary network-down errors when denied: https://grapheneos.org/features#network-permission-toggle . The preflight distinguishes a denied app permission; it does not diagnose every device, VPN or resolver failure.

## What the supplied log establishes

The latest uploaded log is from app version code 14 in an Android 17 secondary profile. It contains ten Direct connection failures with NET_DNS, ten corresponding operation-failed diagnostics, and no fatal crash signature. No other WalletNetwork diagnostic occurs. This means hostname resolution failed before a TCP connection/Monero handshake; it does not establish whether the hostname was misspelled, unavailable, blocked, stale, or affected by the profile's network configuration.

The diagnostics intentionally omit the entered hostname. The log does not prove that missing protocol or an unsaved edit was the exact user trigger. This update fixes the reproduced form/selection behaviors and improves permission diagnosis; successful DNS resolution or syncing with an unseen user endpoint is not claimed.

## Update and limits

Android version 0.10.2-node-entry-preview, code 15, retains the Sync Preview package and certificate. Install over the existing app without uninstalling or clearing data. Wallet, setup, node and cache formats are unchanged. Existing saved selections remain full canonical URLs and initialize the form's protocol accordingly.

The Rust core, generated UniFFI API, dependency declarations, lockfile and both Android Rust libraries remain unchanged from 0.10.1 (core version 0.10.0). No key derivation, transaction authority, background lifecycle or sync calculation is changed. Software spending remains stagenet-only; Keystone mainnet viewing still has no hardware signing or authoritative spent-balance/key-image sync.

See VALIDATION.md for executed checks and the distinction between host tests, compiled instrumentation and actual device execution.
