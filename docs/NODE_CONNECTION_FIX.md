# Node connection failure — 0.10.0

## What was established

The supplied Android system log is from syncpreview version code 11 (0.9.0) on GrapheneOS/Android 17 in a secondary profile. It contains no fatal Java/native crash signature and no specific node-response, DNS, socket or TLS diagnostic. Its SELinux cgroup-read denials do not establish a blocked wallet connection. The node URL, HTTP response and exact native failure were deliberately not recorded; the log alone cannot prove the phone's precise trigger.

Two concrete application defects were found:

1. The HTTP transport rejected any Content-Encoding header, including identity, which means uncompressed bytes. A local regression reproducing an identity-labeled response failed on the original code with the same generic node-request failure. A node or reverse proxy using this header was immediately rejected even with a small valid response.
2. The engine mapped all transport failures into the same invalid/unavailable/too-large message. DNS failures, Android permission denial, connection refusal, HTTPS/certificate problems, HTTP 401/403/429 and actual oversized responses were indistinguishable. This misreported the cause and provided no useful app-specific diagnostic in the uploaded log.

The original identity regression now passes. A complete production Direct WalletEngine handshake accepts an identity-labeled RPC server, handles monerod's batch-unsupported response, verifies the correct stagenet genesis and still rejects a wrong genesis. No unverified genesis or protocol data is substituted.

## Resulting behavior

Requests explicitly ask for Accept-Encoding: identity. Responses without encoding, or explicitly using only identity, are accepted. Each Content-Encoding field is checked; compressed, mixed and malformed encodings remain rejected. There is no decompression implementation or new dependency. Content-Length and streamed/chunked size limits still apply, including to identity responses. The existing 32 MiB hard cap and stricter caller bounds are retained.

Native transport failures use a closed enum and fixed diagnostic messages. Only known values pass through the engine/UniFFI boundary; node bodies, HTTP reason phrases, URLs, node credentials and arbitrary OS/server text are never forwarded. Actual size-limit failure says NET_SIZE. Other failures identify their own reason:

| Code | Meaning |
| --- | --- |
| NET_PERMISSION | Network access denied; check permission in the Android profile being used |
| NET_DNS | Hostname resolution failed |
| NET_REFUSED / NET_CONNECT | TCP refusal / unreachable connection |
| NET_TIMEOUT | Request or Tor bootstrap timeout |
| NET_TLS | HTTPS setup/certificate failure; check HTTPS support on that RPC port |
| NET_STATUS | Non-200 HTTP status; only its numeric status is exposed |
| NET_ENCODING / NET_SIZE | Unsupported encoding / real bounded-response overflow |
| NET_TOR_START / NET_TOR_NODE | Tor initialization / node connection failure |
| NODE_RPC | Invalid Monero RPC data, including a website or wrong RPC service |

Only an allowlisted code and Direct/Tor route are written to Android WalletNetwork diagnostics. Unrecognized input becomes NET_UNKNOWN. No endpoint, payload, key or raw exception is logged. Tests verify redaction even when private-looking fixture text is supplied.

A failed connection stays offline, shows Connection failed and allows explicit Retry connection. The saved node, selected mode and durable encrypted scan checkpoint remain. A retry uses only the chosen route and endpoint; it does not auto-connect, pick a node, downgrade HTTPS or fall back from Tor to Direct.

Direct is still available for ordinary HTTP/HTTPS node RPC URLs, with its IP/plain-HTTP disclosure before consent. Tor is optional after sync or can be selected initially. HTTPS certificate verification, route boundaries, request cancellation, network/genesis checks and wallet authority restrictions remain. Existing Tor preference is retained on update.

## Evidence and limits

The report records the original failing regression, final checks and APK provenance. Actual JNI/JVM tests exercise native HTTP access denial and HTTPS on a plaintext port, confirming they reach the UI with status/TLS codes rather than a size claim. The isolated monerod test exercises real production Direct connection and scanning; it does not contact the user's node.

The uploaded log does not establish that identity was the exact header used by the user's node. This release fixes the reproduced defect and the loss of error causes. Phone retry is needed to confirm the user's endpoint; if a separate permission, TLS, HTTP status or RPC problem remains, the app and next exported log will identify it without exposing secrets. Live Android/Tor behavior, physical installation/update and hardware pairing are separate pending checks.

The package and certificate are retained for updates from 0.8/0.9. Wallet/setup/node/cache formats are unchanged. Software signing remains stagenet only; Keystone mainnet viewing is still view-only. No cloud-backup UI, clipboard cleanup or key regeneration was added.
