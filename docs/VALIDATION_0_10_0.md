# Validation — 0.10.0 background sync and node/progress fixes

Executed 26 October 2026 (America/New_York). This build includes the reproduced identity-response connection fix, distinct safe connection errors, early native target height/full loading bar, a session-only background-sync option, application-owned wallet lifetime, authenticated UI return and a bounded notification-backed service.

## Findings from the supplied logs

Both supplied logs are from version code 11. The latest 89,333-byte log contains 26 pause/stop events, no fatal Java/native crash signature and no WalletNetwork/NET_* diagnostics. Its SHA256 is 159d23c2b7c8bfddd345f69595349d0a5b8e188689f04d3af8500b3b0190100b. SELinux cgroup-read denials do not establish a failed node connection.

Confirmed code behavior: Activity.onStop locked the native owner and cancelled its node child on every ordinary background/Back transition. After cancellation, the UI could expose connection controls before the old operation drained. The target height was also withheld until the first full batch completed, making an established connection appear indeterminate. A recreated Node model's empty loading placeholder could disconnect an application-owned live session without the new loading guard. Those application paths are fixed; the logs do not identify the user's particular RPC latency/failure.

Separately, an identity-labeled uncompressed HTTP response was rejected solely for having a Content-Encoding header, and all transport failures were collapsed into invalid/unavailable/too-large. A regression failed on the original code and passes after the fix. No claim is made that identity was the exact header on the user's node; its endpoint/response were not present in the log. NODE_CONNECTION_FIX.md records the distinction.

## Executed checks

| Check | Actual result |
| --- | --- |
| Rust scripts/check.sh | Formatting, 75 passed/zero failed; one isolated-node case excluded from the ordinary suite; all-target Clippy with warnings denied passed |
| Connection regression | Original identity response fails before fix; identity/no encoding accepted after fix; mixed/compressed coding and actual oversized/chunked responses rejected |
| Production engine handshake | Direct connection handles monerod's batch rejection, accepts identity responses, rejects wrong genesis and exposes the real target height before scanning |
| Safe native errors | Closed enum for permission/DNS/TCP/TLS/HTTP status/encoding/size/Tor/cancellation; arbitrary input cannot become a forwarded message |
| Direct/Tor policy | Explicit immutable route, Direct onion rejection, no Tor-to-Direct fallback, no TLS-to-HTTP downgrade, socket cancellation and bounded RPC remain passing |
| Isolated monerod integration | Monero 0.18.5.4: one passed, zero failed in 107.30s under concurrent build load |
| Actual node coverage | Production Direct engine/genesis checks, scan/cache reopen, hardware viewing parity; existing software transaction verification, lost reply, reservation, identical retry, maturity and reorg/rescan cases passed |
| JVM | 67 passed; zero failures/errors/skips on the rebuilt native library |
| Actual JNI failures | Local HTTP 403 and HTTPS-on-plaintext-port failures reach Kotlin as NET_STATUS/NET_TLS, with no private server body or misleading size claim |
| Background policy | Saved-wallet-only consent/service gating; setup/recovery/auth/busy/idle exclusion; connecting continuation and strict verified completion/error handling |
| Retained checks | Offline entropy/import/backup/authority, node migration, persisted scan range, progress arithmetic, poll-loop cancellation, authentication, setup Resume/Discard, encryption and clipboard checks remain passing |
| Python | Four packaging/build-environment/APK-integrity tests passed |
| Android native | Fresh arm64-v8a/x86_64 release builds from final Rust source; Kotlin bindings regenerated |
| Android | Final app/instrumentation assembly, native-backed JVM tests and lint passed: 77 tasks, 18 executed/59 up-to-date, 1m58s |
| Lint | Zero errors; six warnings: three ImplicitSamInstance notices at explicit stopService Intent constructions, one unused API attribute and two KTX suggestions |
| DEX/ZIP integrity | App and instrumentation ZIP CRCs, DEX lengths/checksums and duplicate classes checked |
| Signing/alignment | APK v2 signature, 16 KiB ZIP alignment and all ten ELF LOAD alignments verified |
| Native provenance | Packaged Rust libraries match independently NDK-stripped final compiler outputs for both ABIs |
| Android execution | Thirteen instrumentation cases compile; zero executed, including actual Activity/application wallet identity retention |

The isolated node is offline/loopback-only and uses synthetic public fixtures, not a user wallet or public chain transaction. The production Direct path uses normal genesis checking with a viewing fixture; the test-only software fakechain identity accommodation remains confined to cfg(test). This is not physical Keystone or Android/Tor execution. Timing is a functional-test record, not a performance benchmark.

All 592 registry package name/version/source/checksum tuples are unchanged from the prior verified source. Four local packages are 0.10.0. No new third-party dependency, animation asset or Monero node preset was added. The required platform foreground-service/dataSync/notification/wake-lock permissions are new. Application wallet lifetime and lifecycle behavior changed intentionally; wallet/setup/node/cache encrypted formats, seed derivations, signing/transaction policy and route restrictions are retained.

## Background ownership and limits

Allow background sync is live-session-only and off after a fresh process/unlock without a continuing job. It starts only from the visible app's explicit consent/connection/manual sync or rescan. The non-exported dataSync service never opens a wallet/node itself and is START_NOT_STICKY. It does not restart/unlock/reconnect after process death, reboot or force stop. Foreground polling does not keep restarting an idle service.

A permitted active saved-wallet scan can retain the native wallet while the UI locks on Back/background/screen-off/inactivity. This includes software-wallet key material needed for local spent detection; it is not a view-key-only background export. The opt-in text discloses in-memory retention. Returning requires a valid fresh Android credential ticket before the same owner/details become visible. Setup/recovery, payment review/preparation/sending and pending authentication cannot be background jobs. No service action signs, broadcasts, displays keys or changes route/node.

An enabled private notification shows route/real percentage and offers Stop and lock. No address, balance, node URL, credential or transaction appears there or in service intents. Connection IDs guard old service callbacks; operation epochs guard stale native results. Explicit lock/network off/node changes revoke children, cancel work and retain encrypted checkpoints. Replacement networking remains disabled until prior work drains. An empty loading placeholder cannot disconnect the service-owned session before node settings are read.

Partial wake lock acquisition is bounded to six hours and released on stop/destruction/replacement. A separate 5h50 deadline and the Android 15+ shared dataSync timeout stop/cancel/lock the job. Stop, failure or timeout preserves checkpoints and never reports successful completion. Android can still kill/restrict work; uninterrupted execution is not promised. The original lock-and-cancel behavior applies without service/consent. Screen-off invalidates pending authentication. Explicit copied seeds are still cleared only manually/OS behavior, not lifecycle or timers.

## Progress and transport

Native genesis/network validation now fetches and publishes the node tip before the first batch. Full-width bar, percentages (two decimals below 1%), next/target/remaining heights and notification progress derive from the actual persisted native start/next/target range. Known progress uses a filled static pixel orbit; unknown progress can animate. Numeric progress is never timed/fabricated. Errors, disconnection, inconsistent/unknown ranges and unfinished checks cannot claim 100%. Full-history restores can still take substantial time; no restore height is silently advanced.

Direct uses the chosen HTTP/HTTPS node and system DNS without Arti startup. It exposes IP to the node and leaves HTTP unencrypted; those costs remain visible before consent. Tor uses Arti streams and destination DNS, accepts onion/HTTPS, and never falls back to Direct. Switching later cannot erase prior exposure. HTTPS certificate/hostname verification remains enabled, with no downgrade or redirect. Exact identity is accepted without decompression; other coding, encoded/mixed fields and oversized responses are rejected. Response caps and timeouts remain. Static diagnostics distinguish failures without forwarding raw OS/node input.

## APK/update identity

- Package: app.independentwallet.android.syncpreview; label: Independent Sync Preview
- Version code 13; version 0.10.0-background-sync-preview
- Minimum API 28; target/compile API 36; arm64-v8a and x86_64
- APK bytes: 60,441,562
- APK SHA256: 8a7c7daaa8224ef99687b8ba12c0332256c9b975fc05b0592f6010646c928497
- Certificate SHA256: 63b5045ee6e8a9de7fe90fb05f18ea9397a248360ed4ee496b16b1519c7f31e4

Package/certificate match v0.8/v0.9, with a higher version code; an in-place update can retain their data. The older v0.7 resumetest package remains separate; keep its installation/data. Private signing material is excluded from every deliverable. This is a debug preview, not a production-signed release. PREVIEW_SIGNING.md records the signing boundary.

## Remaining execution and known scope

Actual phone install/update, notification permission denial/revocation/channel disable, service start/stop/timeout, Back/screen-off/return authentication, activity/process death, rendered bar/font scale/accessibility/reduced motion, GrapheneOS Network permission and live Direct/Tor/DNS/cancellation captures remain pending. Thirteen instrumentation cases compiled; none ran. Host tests and local monerod do not substitute for those device cases. The exact user's node failure cannot be reconstructed from the uploaded logs; the new fixed diagnostic codes and batch markers make future failures distinguishable.

Software signing remains stagenet-only. Keystone mainnet/stagenet viewing is available; hardware spending and verified key-image/authoritative hardware spent-balance import remain unavailable. Received-output totals can include spent funds/change. No independent security/anonymity or speed-parity claim is made. A full mainnet history may exceed an individual background service window; resume its durable progress explicitly.

No fresh full vulnerability-database audit was performed. DEPENDENCY_REVIEW.md retains rsa 0.9.10 RUSTSEC-2023-0071/CVE-2023-49092 and three maintenance notices. Kotlin/Compose/FFI/JCA copies cannot all be reliably erased. Gitleaks/source findings and archive verification are recorded in the delivery report; scanning cannot prove the absence of every secret or vulnerability.

Tools: Rust 1.99.1, cargo-ndk 4.1.2, UniFFI 0.32.2, Temurin 17.0.21+9, Gradle 8.14.6, AGP 8.10.0, Kotlin 2.1.0, SDK/build tools 36, NDK 28.2.13676358, Monero 0.18.5.4 and Gitleaks 8.30.1. Distributions were checked against published digests. Build caches were isolated from deliverable source. Earlier executed records remain in VALIDATION_0_9_0.md and previous-version documents.
