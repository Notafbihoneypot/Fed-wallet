# Validation — 0.9.0 direct sync, then optional Tor

Executed 25 October 2026 (America/New_York). Normal user-selected Monero HTTP/HTTPS RPC nodes now have an explicit Direct transport without Arti startup. A completed live scan offers Switch to Tor/Set up Tor node; the Tor node is also chosen by the user. Route selection disconnects and keeps network access off until explicitly enabled. Both routes reload the same durable encrypted scan checkpoint. The native animation and real counters remain.

## Executed checks

| Check | Actual result |
| --- | --- |
| Rust scripts/check.sh | Formatting, 70 tests passed, zero failures; one separate real-node test ignored in the ordinary suite; all-target Clippy with warnings denied passed |
| Direct transport | Actual loopback TCP RPC with no Tor state/cache creation; cancellation closes the socket and prevents reuse |
| Route security | Direct rejects onion; Tor rejects normal cleartext HTTP before any socket; rejected Tor route never connects directly |
| HTTPS failure | Production direct connector sends a TLS handshake; handshake failure returns an error without retrying plaintext |
| HTTP boundaries | Shared HTTP path retains route allowlist, size limits, redirect and encoded-response rejection |
| Isolated Monero integration | Monerod 0.18.5.4, final source run: 1 passed in 62.01s, zero failures; fresh offline loopback-only fakechain |
| Actual production direct engine | Native direct connection and mainnet genesis validation with a public Keystone-view fixture, scanning and received-output parity, encrypted-cache reopen/reconnect and checkpoint retention |
| Existing transaction integration | Actual signed-byte verification, lost broadcast reply, durable reservation, reopen/retry of identical bytes, mined confirmation, recipient maturity and reorg/rescan retention passed |
| JVM | Final native library: 61 passed, zero failures/errors/skips |
| JNI route/cache checks | Explicit native route reporting, Direct onion rejection, Tor cleartext rejection, one live child across route changes, Direct-to-Tor encrypted scan-range reuse |
| Node preferences | Legacy URL and existing IWN2 binary migration, selected Tor preservation, default Direct, slot isolation/no fallback, strict UTF-8/length/trailing-byte rejection |
| Retained tests | Native seed/import/backup/view-only/Keystone authority, real range/progress, foreground polling/cancellation, authentication, resumable setup/discard and clipboard tests remain passing |
| Python | Four packaging, native-build-environment and APK-integrity tests passed |
| Android native | Both ABIs rebuilt from final source with pinned NDK; Kotlin bindings regenerated |
| Android | Unit/instrumentation compilation and lint stage passed (39 tasks, 4m50s); final app/instrumentation assembly, JVM checks and lint passed (77 tasks, 40 executed/37 up-to-date, 43s) |
| Lint | Zero errors; 11 warnings: eight dependency-version notices, one unused API attribute, two KTX suggestions |
| App integrity | Five DEX files, 15,524 distinct classes; ZIP CRC, DEX sizes/checksums and duplicate classes verified |
| Instrumentation integrity | Three DEX files, 1,037 distinct classes; structural checks passed |
| Signature/alignment | APK v2 signature, 16 KiB ZIP and all ten ELF LOAD alignments verified |
| Native provenance | Packaged Rust libraries match independently NDK-stripped fresh compiler outputs for both ABIs |
| Android device execution | Twelve instrumentation cases compiled, zero executed |

The real-node test uses synthetic public keys and offline-generated blocks only. Its fault-injecting Loopback adapter is test-only. The added production NodeTransport/WalletEngine path uses its real Direct connector and normal mainnet genesis check with a viewing fixture; the software signing exercise retains the existing test-only fakechain identity accommodation. This is not a public-mainnet transaction, physical Keystone pairing or live Tor test. The final isolated test passed after the route-neutral disconnected-error wording was rebuilt.

All 592 registry dependency name/version/source/checksum tuples are unchanged from the verified prior source. Four local package versions are 0.9.0. Tokio's existing net feature is now declared explicitly. No new third-party package, permission, node preset, analytics service or animation dependency was added. Generated UniFFI declarations and both packaged native libraries include the same explicit route API. Wallet/setup/cache encryption and transaction/key algorithms are unchanged. The IWN2 format is byte-compatible; its first/second endpoint slots now mean Direct/Tor.

## Route and privacy boundary

The user explicitly chooses Direct or Tor before creating a native online child. Its node and route cannot change while alive. Direct accepts normal HTTP/HTTPS and uses Tokio TCP/system resolution without constructing Arti. Tor accepts v3 onion HTTP/HTTPS or normal HTTPS and obtains its streams and destination resolution through Arti. There is no Tor-to-Direct fallback, node discovery, preset endpoint, automatic connection or automatic post-sync switch.

Direct sync exposes the client's IP to its chosen node; HTTP is unencrypted. The form and network controls disclose this before consent. Switching to Tor afterward does not erase prior IP/DNS/activity exposure or prevent all correlation. TLS certificate/hostname checks remain enabled for either HTTPS route; invalid TLS cannot downgrade to HTTP. Tor cleartext exit connections and inline node credentials remain rejected. The platform HTTP cleartext flag stays disabled; explicit native HTTP RPC uses Rust sockets and is labeled unencrypted. Android execution remains pending.

Switching routes/nodes revokes the old child, cancels work/polling, clears payment review and rejects stale callbacks. Replacement connection waits for work to drain and requires a new user enable action. The same encrypted cache resumes locally; the selected node's genesis and current tip are checked again, and cached synced state cannot establish completion. Direct DNS already submitted to the OS may finish after cancellation; prior lookups cannot be undone. Tor never uses that direct resolver.

Existing selected Tor preferences remain Tor on update. The old HTTPS slot becomes Direct, but every launch/unlock begins with network consent off and the route is visible before enabling. Empty slots never fall back or populate themselves. Switching to Tor after completion opens settings and pauses access; this avoids an unreviewed automatic endpoint/route change.

The animation is decorative. Percentages, heights and completion still use authoritative native state. Unknown progress is indeterminate; cached/disconnected/error/checking state cannot show verified completion. No keep-awake or background service was added. Lock/background/screen-off and five-minute inactivity still cancel networking and retain encrypted checkpoints.

## APK and update identity

- Package: app.independentwallet.android.syncpreview; label: Independent Sync Preview
- Version code 11; version 0.9.0-direct-sync-preview
- Minimum API 28; target/compile API 36; arm64-v8a and x86_64
- APK size: 59,649,528 bytes
- APK SHA256: f74234a7ea1ff5495fee6cbe9ff85025b11ad82a090bccc157b172f94a2ee0ce
- Certificate SHA256: 63b5045ee6e8a9de7fe90fb05f18ea9397a248360ed4ee496b16b1519c7f31e4

The certificate and package match v0.8 and the version code is higher: this APK can update v0.8 in place. Wallet, setup and cache formats are unchanged. The separate older v0.7 resumetest app still cannot be updated/migrated by this package; keep its app/data. PREVIEW_SIGNING.md documents the signing boundary. The private preview signing key is excluded from all deliverables. This remains a debug/development APK, not a production-signed release.

## Sensitive data and remaining limits

Gitleaks 8.30.1 default rules with redaction found zero matches in extracted APK DEX/native strings. Public fixture keys and test markers are absent from the app. The source scan returned its normal finding status (exit 1): exactly two inspected historical false positives at docs/CODE_REVIEW.md:55 (historical key-handling discussion) and vendor/UPSTREAM.md:11 (a public upstream archive checksum). No allowlist was added. Final archive verification is recorded in the delivery report. Scanning cannot prove absence of every secret or vulnerability.

No fresh full vulnerability-database audit was performed. DEPENDENCY_REVIEW.md retains unresolved rsa 0.9.10 RUSTSEC-2023-0071/CVE-2023-49092 and three maintenance notices. Registry versions/checksums are unchanged. This is not an independent security audit, anonymity guarantee or wallet-speed/security comparison. Rust zeroization does not reliably erase every Kotlin/Compose/FFI/JCA temporary copy.

Software signing remains stagenet only. Keystone mainnet/stagenet viewing remains available; hardware signing and verified key-image import/authoritative hardware spent-balance sync are unavailable. Received-output totals may include spent funds/change. Full-history restores can take time; this milestone removes the mandatory Tor bootstrap from Direct and does not claim a measured scanning speedup.

Phone install/update/rendering, actual Android Direct/Tor/onion/HTTPS routing and DNS/cancellation captures, GrapheneOS permission, accessibility/high-font-scale/reduced-motion, screen-off/lifecycle behavior and physical Keystone pairing remain pending. Twelve instrumentation cases compiled; none executed. Host checks and isolated monerod do not substitute for these executions.

The cloud-backup UI remains absent, platform backups disabled and Resume/Discard setup and manual clipboard retention unchanged. No user seed/view/spend key, real wallet/cache, user transaction or node credential is included in deliverables. Public fixture keys remain in test source only.

Tools: Rust 1.99.1, cargo-ndk 4.1.2, UniFFI 0.32.2, Temurin 17.0.21+9, Gradle 8.14.6, AGP 8.10.0, Kotlin 2.1.0, SDK/build tools 36, NDK 28.2.13676358, Gitleaks 8.30.1 and Monero 0.18.5.4. Tool distributions and the Monero archive were checked against published digests. The Monero linux-x64 archive SHA256 is ba2928962c101b609434bf8c1b43ad59ebe96c5525977284ca599d8bf0f02d10. Build outputs/caches were isolated from source.

Earlier records remain in VALIDATION_0_8_0.md, VALIDATION_0_7_0.md and older version documents. NETWORK_ACCESS.md and SYNC_PROGRESS.md describe the resulting behavior and transport boundaries.
