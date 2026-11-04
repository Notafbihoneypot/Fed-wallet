# Validation — 0.10.3 Android DNS preview

Executed 26 October 2026 (America/New_York). This release changes Android Direct hostname resolution and adds a bounded numeric-address handoff to the Rust transport. The earlier build-15 NET_DNS failures are confirmed; the device's exact hostname, resolver error and successful live connection remain unknown.

## Implemented behavior

Android resolves the selected validated Direct hostname after explicit network consent. Fully qualified names on API 29+ use cancellable DnsResolver queries on the system default network; local names/API 28 use platform InetAddress compatibility. Literal IPs skip DNS. Rust accepts at most 32 numeric addresses, retains only the selected URL's port and keeps that original URL for HTTP Host and HTTPS SNI/certificate verification. Its RPC/genesis checks remain mandatory. Reconnection performs another platform lookup; an active session holds its resolved addresses only in memory.

Tor remains entirely inside Arti and cannot invoke the new Direct entry point. Saving, editing and selecting nodes stay offline. Revoked/late lookups cannot start a connection. No node list, external DNS provider, automatic failover, HTTPS downgrade, additional manifest permission or disabled security setting is introduced.

Fixed messages distinguish NXDOMAIN, empty answers, DNS-server/refusal failures, other system resolver failure, typed permission denial, unavailable network and timeout. Logs contain only closed stage/route/error codes and bounded address-family counts. They never include resolver exception messages, destinations, addresses, credentials or wallet material. Native compatibility resolution now also preserves known network/timeout/permission categories instead of reporting all of them as DNS.

See [connection implementation and limits](ANDROID_DNS_0_10_3.md). Prior results remain in VALIDATION_0_10_2.md and earlier records.

## Executed checks

| Check | Actual result |
| --- | --- |
| Rust checks | scripts/check.sh passed: formatting, 79 passing workspace tests and workspace/all-targets Clippy with warnings denied; one optional real-node regtest ignored |
| New native transport cases | Resolved reserved domain connects only to the supplied local socket, keeps HTTP hostname/port, sends TLS with the original hostname in SNI, rejects plaintext on HTTPS, validates/deduplicates/bounds numeric addresses and preserves typed network/permission/timeout errors |
| Native engine handshake | Existing identity/RPC test expanded to both native-resolver and platform-resolved paths, each with correct and wrong genesis; correct network accepted, wrong network rejected |
| Actual UniFFI/JNA JVM suite | 94 tests passed; zero failures/errors/skips across 23 suites |
| New JVM coverage | Selected-host-only resolution, literal IP DNS avoidance, Tor/input isolation, closed DNS response diagnostics, invalid/empty answer rejection, lookup timeout, owner cancellation; actual new native method preserves HTTP authority/TLS behavior and rejects Tor use |
| Generated Kotlin bindings | Regenerated from freshly built 0.10.3 host library; new native method and checksum present; Android and JVM sources compiled |
| Android native build | Offline locked Rust cross-build returned success for arm64-v8a and x86_64 using NDK 28.2.13676358; both new libraries differ from 0.10.2 and export the new Direct method/checksum |
| Android/JVM/instrumentation/lint | 59 tasks executed; passed in 3m30s |
| Signed APK assembly | Initial assembly passed in 19s; final assembly/lint after API-guard annotation cleanup passed in 1m1s: 47 tasks, 14 executed/33 up-to-date |
| Lint | Zero errors; 14 warnings, consisting of dependency-update and retained service/attribute/KTX notices |
| Python unit checks | Four checks passed across APK-integrity, minimal native-build environment and source-packaging scripts; optional test-regtest.py requires monerod and was not executed |
| APK integrity | App: 5 DEX files, 15,566 classes; instrumentation: 3 DEX files, 1,055 classes; ZIP CRC, DEX bounds/checksums and duplicate-class checks passed |
| Native/signing/alignment | New API verified in both packaged Rust libraries; all 10 native ELF LOAD alignments and APK ZIP alignment meet 16 KiB; APK v2 signature and retained certificate verified |
| Private/fixture exclusions | APK checked for private signing/settings files and new fixture destination markers; no matches |

The Rust suite was rerun after strengthening the TLS SNI regression; all 79 cases and Clippy remained passing. The actual real-node scan/sign/broadcast/reorg regtest was not rerun. Its earlier records are historical, not fresh execution claims.

## Android execution and limits

18 instrumentation cases compiled; zero executed. No emulator boot or physical-device test ran for this release. Android DnsResolver callback/error behavior, GrapheneOS profile/VPN/Private DNS behavior, permission denial/regrant, the user's actual node, live Tor, background service scheduling and hardware remain unverified. Local socket/JNA tests verify the handoff and native checks, not DNS operation on the phone. No successful physical-device connection or exact device root cause is claimed.

The API 28/local-name InetAddress compatibility lookup can finish its already-started system call after cancellation; its result cannot initiate a revoked native session. Active sessions keep one in-memory resolved-address set until disconnect; a node DNS address change requires reconnecting. No raw resolver error or node address is available in the supplied build-15 log.

## APK and update

- File: independent-wallet-v0.10.3-android-dns-preview.apk
- Package/label: app.independentwallet.android.syncpreview / Independent Sync Preview
- Android version: 0.10.3-android-dns-preview; code 16
- Rust core: 0.10.3
- Minimum API 28; compile/target API 36; arm64-v8a and x86_64
- Bytes: 60,647,834
- APK SHA256: 14b5ceb9f96c2d73582a7624f026058ad75a4017e75815fc0dd47ba0d0af3e22
- Certificate SHA256: 63b5045ee6e8a9de7fe90fb05f18ea9397a248360ed4ee496b16b1519c7f31e4

The certificate/package match Sync Preview v0.8 through v0.10.2. Install over the existing Sync Preview without uninstalling or clearing its data. Encrypted wallet/setup/node/cache formats and keys are unchanged; settings do not auto-connect. The separate older resumetest package remains independent. This is a debug/development preview; private signing material is excluded.

## Scope

No cryptographic primitive, key derivation, seed/clipboard policy, transaction verification/authority, scan calculation, background lifecycle or wallet storage format changed. Software spending remains stagenet-only; Keystone mainnet/stagenet viewing has no implemented hardware signing or authoritative key-image/spent-balance import. Dependency declarations are unchanged; Cargo.lock changes only the four local workspace versions. No new vulnerability-database audit or physical security review was performed.

Source packaging contains a per-file SHA256SUMS manifest and excludes native binaries, private signing material, logs/wallet data, build outputs, caches/toolchains and local SDK/proxy configuration. Exact source bytes, archive CRC, manifest digests and exclusions are verified before delivery. Release artifacts are listed in the accompanying validation report.

Build tools: Rust 1.99.1, cargo-ndk 4.1.2, Temurin JDK 17.0.21+9, Gradle 8.14.6, AGP 8.10.0, Kotlin 2.1.0, SDK/build tools 36 and NDK 28.2.13676358. Published distribution checksums were verified. All build tooling stays outside the source deliverable.
