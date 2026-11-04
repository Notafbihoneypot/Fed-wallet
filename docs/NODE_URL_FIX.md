# Node URL input and connection continuity — 0.10.1

Four regressions run against the original application and actual Rust/UniFFI parser failed: pasted surrounding whitespace, standard JSON-RPC URLs, HTTPS/IPv6 RPC URLs, and Tor RPC URLs. The prior Android adapter passed these strings directly into a strict native base-URL parser, which deliberately accepts no path or whitespace. The node screen also called disconnect before attempting validation, cancelling an active scan even when the new input was rejected.

The uploaded logs do not record the entered URL or a specific URL-validation error. These are reproduced code defects; they do not establish the exact string that triggered the user's report. No user endpoint, wallet material or uploaded device log is included in the deliverables.

## Resulting behavior

The Kotlin human-entry adapter trims only surrounding whitespace and removes only an exact `/json_rpc` or `/json_rpc/` suffix. Rust remains the final validator of the resulting base URL and onion syntax. Nothing resolves a hostname or constructs a network client during validation or saving. Examples use reserved public documentation names, not preset nodes:

| Input | Result |
| --- | --- |
| ` https://NODE.example:18081/json_rpc ` | `https://node.example:18081/` |
| `http://[2001:db8::1]:18081/json_rpc/` | `http://[2001:db8::1]:18081/` |
| `node.example:18081` | Explain that `http://` or `https://` must be supplied |
| HTTP ordinary node with Tor selected | Explain Direct mode or an HTTPS/v3 onion endpoint is required |
| Onion address with Direct selected | Explain that Use Tor must be enabled |
| Port zero, nonnumeric port or port above 65535 | Explain the supported numeric RPC port range |
| Inline credentials, query/fragment, arbitrary path or interior whitespace | Reject with a fixed specific message |

Protocol, host, port and route are not guessed or switched. Explicit HTTPS is never downgraded. Encoded authorities and ambiguous URL recovery are rejected before the Rust parser; interior whitespace is not removed into a different hostname. Only the standard suffix is recognized; arbitrary reverse-proxy path prefixes remain unsupported because this transport sends several distinct monerod RPC routes.

Monero's official daemon RPC documentation demonstrates `/json_rpc` as well as separate HTTP RPC paths: https://docs.getmonero.org/rpc-library/monerod-rpc/ . The adapter stores the base endpoint because the existing native transport already chooses the correct RPC route per request.

## Saving and sync

Validation runs before the node screen's disconnection callback. Rejected input leaves the selected encrypted node, route, consent and active native scan untouched. Saving an equivalent canonical endpoint simply normalizes the visible draft and does not disconnect or rewrite settings. Changing to a valid different endpoint revokes the old session before saving; no save automatically connects. A storage failure keeps existing settings, and networking stays off after a valid change's revocation.

An oversized paste now reports the 2048-character limit while keeping the previous editable draft. The field is single-line; pasted edge line breaks are still handled by the adapter. Fixed error messages discard raw Java/native exceptions and never include the supplied URL, credential or private path. Native HTTP/network diagnostic behavior is retained.

## Scope and update identity

Android version is 0.10.1-node-url-fix-preview, code 14, package `app.independentwallet.android.syncpreview`. The retained certificate matches v0.8 through v0.10.0. Install over the existing Sync Preview without uninstalling or clearing its data. Wallet, setup, node and scan-cache formats remain compatible.

This change is in Kotlin input/UI handling only. The Rust source, lockfile, generated UniFFI API, shipped arm64/x86_64 native libraries, cryptography, background service, progress calculation and transaction authority remain unchanged from 0.10.0. The native core version remains 0.10.0 intentionally. Software spending is still stagenet-only; Keystone mainnet viewing does not gain hardware signing or authoritative spent-balance support.

Executed checks and remaining device limits are recorded in VALIDATION.md. Passing fixture/regression tests cannot establish reachability or successful syncing with an endpoint absent from the supplied logs.
