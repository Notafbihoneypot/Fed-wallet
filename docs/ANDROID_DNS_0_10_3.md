# Android DNS and Direct connections — 0.10.3

The supplied build-15 log records repeated Direct NET_DNS errors, including five attempts around 01:41–01:42 on 27 October UTC, spanning a new app process. The supplied text contains no node address or resolver error detail. It establishes failure in the previous lookup stage, before TCP/Monero RPC; it cannot establish a misspelled hostname, Android resolver incompatibility, denied network policy, VPN failure or DNS outage. No physical-device fix is claimed from this log alone.

## Connection change

Previously every Direct RPC request called Tokio/std's native getaddrinfo path, and almost all lookup errors became NET_DNS. Android Direct startup now resolves the selected, validated hostname with the Android platform and passes a bounded list of numeric IP addresses through the new connectDirectResolved UniFFI method. No second native hostname lookup occurs in that session. Rust still opens all TCP/TLS connections and performs the unchanged Monero identity/genesis and scan checks.

Android API 29+ uses DnsResolver.query with a null Network and default flags: the app's default system network/DNS configuration. CancellationSignal follows coroutine cancellation and the 15-second lookup timeout. No custom DNS server, VPN bypass, raw UDP resolver, public probe or external test node is introduced. Android API 28 and local/single-label names retain Android InetAddress resolution, preserving hosts/search-domain behavior. That compatibility call may finish its already-started system lookup after coroutine cancellation; its result cannot initiate a revoked connection.

Literal IPv4/IPv6 input skips DNS. Tor never calls the Android resolver or this Direct-only native entry point: Arti still resolves the selected onion/HTTPS destination. The native method rejects a Tor session, and the transport rejects onion URLs. No route fallback exists.

The new native constructor accepts 1–32 numeric IP strings, each at most 45 bytes. Rust parses each string as IpAddr, deduplicates it and applies only the selected URL's port. An IP-literal URL may only receive that same literal. A supplied address cannot contain a hostname, URL, port or IPv6 zone suffix. The URL remains unchanged for HTTP Host, HTTPS SNI/certificate authentication, RPC and wallet-network checks. HTTP is not substituted for HTTPS. Addresses are kept only in the active in-memory transport; disconnect destroys them, and reconnect performs a fresh platform lookup. A session does not refresh DNS between RPC requests.

Only an explicit connection request invokes resolution. Saving, editing or selecting a node remains offline. Existing epoch/native cancellation checks prevent a late answer after lock, node/route change or leaving without background consent from opening a connection. A failed lookup remains visibly offline, with encrypted settings and scan progress retained.

## Fixed diagnostics

| Code | Meaning |
| --- | --- |
| NET_DNS_NXDOMAIN | DNS reports no such hostname |
| NET_DNS_EMPTY | No usable IP addresses were returned |
| NET_DNS_SERVER | DNS server/temporary resolver failure |
| NET_DNS_BLOCKED | DNS refused the query |
| NET_DNS_SYSTEM | Other platform lookup/response failure |
| NET_PERMISSION | Typed permission denial or denied INTERNET preflight |
| NET_CONNECT | Typed unavailable/unreachable system network |
| NET_TIMEOUT | Bounded lookup timeout or typed OS timeout |

Responses and typed error causes are mapped to a closed enum. Logs record only fixed route/stage/error codes and bounded IPv4/IPv6 counts, never hosts, resolved addresses, URLs, resolver exception messages, node credentials or wallet material. Native compatibility lookup also retains known permission, network-down/unreachable and timeout categories instead of labeling all of them DNS failures.

The more precise codes help locate an unresolved device/network issue. A truly nonexistent hostname or a DNS/VPN outage still needs the relevant address or configuration corrected; this update does not invent a replacement endpoint or disable security controls.

## Validation and limits

See VALIDATION.md for actual executed results. Local native tests exercise a deliberately unresolvable fixture hostname mapped only to a local server, retaining HTTP authority and HTTPS protocol enforcement. The engine checks both correct and wrong genesis on the resolved path. JVM/JNA tests exercise the new actual UniFFI method. These do not execute Android's DNS framework, GrapheneOS Network permission, a user's node, live Tor, background scheduling or physical hardware. Device verification remains required.

References: Android's public [DnsResolver API](https://developer.android.com/reference/android/net/DnsResolver) and [callback response codes](https://developer.android.com/reference/android/net/DnsResolver.Callback).
