# Validation — 0.11.3 remembered connections and spendable XMR

Date: 27 October 2026 (America/New_York). Android version 0.11.3-sync-preview, code 20; Rust workspace 0.11.3.

## Scope and findings

The prior app deliberately discarded network/background consent on hard UI lock and stopped its background service at verified catch-up. That made repeated manual network enabling necessary, and stopped confirmation updates while away after a completed scan. This release implements the user's request to remember exact wallet/node/route permission, reconnect after authenticated unlock, and keep checking later blocks during an opted-in bounded background session.

The existing normal-output maturity rule is preserved. Native status now exposes blocks until the next eligible output unlocks, and the dashboard explains received-but-locked funds or stale confirmation data. The supplied message alone does not establish whether this user's receipt was mature; no private wallet or current receipt was examined. An isolated real-node receipt was tested through the exact maturity boundary.

See [CONNECTION_BALANCE_0_11_3.md](CONNECTION_BALANCE_0_11_3.md) and [BACKGROUND_SYNC.md](BACKGROUND_SYNC.md). Prior build 19 validation is archived in VALIDATION_0_11_2.md.

| Check | Observed result |
| --- | --- |
| Required Rust check | 93 tests passed, 0 failed; 1 isolated-node case ignored in default tests. Formatting and Clippy over workspace/all targets passed with warnings denied. |
| Isolated Monero test | 1 passed in 115.51 seconds. Monero CLI 0.18.5.4; offline fakechain; loopback-only RPC/P2P. |
| Receipt maturity | A synthetic 1 XMR normal receipt was locked after its first confirmation, with nine blocks remaining. Eight additional blocks left spendable at 0 with one block remaining; the tenth confirmation made 1 XMR spendable and cleared the count. |
| Unlock safety | Normal/miner maturity, extra block locks and unsupported time-based locks retained. Pending-payment reservations and view-only/Keystone restrictions are unchanged. Helper tests cover normal/miner thresholds and additional block/time locks. |
| Earlier engine regressions | Actual signing/byte verification, lost broadcast reply, durable reservation, reopen/exact-byte retry, confirmation, reorg/rescan, nonzero scan start, size reduction, interrupted-window recovery, pooled cancellation and no automatic broadcast replay passed. |
| Kotlin/JVM + host UniFFI | 116 tests passed across 28 suites, 0 failures/errors/skips. Actual rebuilt host library and regenerated Kotlin bindings used. |
| New policy/UI checks | Preference codec round-trip/legacy migration, malformed and duplicate consent rejection, exact wallet/node/route/visibility/unlock guards, node/route and network-off revocation, preserved background choice, idle locked-service polling, auth/service/pause exclusions, received/stale/additional-lock/reservation/viewing explanations passed. |
| Final Android build | Fresh generated outputs, --rerun-tasks, Kotlin incremental compilation/build cache disabled; all 78 Gradle tasks executed. App/test assembly, JVM checks and lint passed. |
| Android instrumentation | 21 cases compiled; 0 executed. |
| Lint | 0 errors, 16 warnings: 5 ImplicitSamInstance, 1 UnusedAttribute, 7 dependency-version notices and 3 UseKtx notices. External dependencies and manifest permissions/settings unchanged. |
| Python helpers | 4 tests passed across source packaging, native-build environment and APK integrity. |
| App DEX | 5 DEX files, 15,631 classes; ZIP CRC, bounds, checksums and duplicate-class checks passed. |
| Test DEX | 3 DEX files, 1,073 classes; same integrity checks passed. Both assembly tasks now enforce this guard. |
| Native ABI/build | Both arm64-v8a and x86_64 release libraries rebuilt from final production Rust source. Packaged JNI bytes match stripped outputs and fresh NDK stripping of original source outputs. |
| 16 KiB support | All 10 native ELF libraries and 28 LOAD segments checked; alignment at least 16 KiB. APK zipalign -P 16 verification passed. |
| Install identity | Retained Sync Preview signing certificate, package/label, version/code, min SDK 28, target SDK 36 and both ABIs verified. |
| Source archive | Per-file SHA256SUMS, local byte equivalence, archive CRC and exclusions verified. No signing key, wallet/cache/node data, build credentials, binaries or toolchain included. Test-only native markers absent from APK. |

An intermediate test APK had 83 duplicated synthetic class definitions in stale DEX outputs. The new test-assembly guard rejected it. A Gradle clean left some prior generated outputs present; explicitly deleting Android app/root build outputs and forcing all tasks resolved the issue. Both final APKs passed their integrity guards. Discarded intermediate packages are not deliverables. No dependency/plugin update or disabled validation was used to pass the build.

The retained 1,000-block synthetic benchmark used 25 ms per loopback RPC and reported fixed-32 (96 RPCs / 32 saves / 3.317999 s), adaptive single-read (12 / 4 / 0.843339 s) and coalesced (6 / 1 / 0.576800 s). It is a regression/overhead measurement, not Android/public-node/Monero One speed parity. Test-only adapters/counters are cfg(test) and absent from native release libraries.

## Consent, lifecycle and storage

Enabling Network access after a successful selected-node handshake stores reconnect/background preferences in the existing Android Keystore-encrypted no-backup node file. Consent is bound to a wallet slot/address, route and canonical-endpoint fingerprint. New grants cannot attach to a concurrently changed saved endpoint. Old single-URL/IWN2 records remain readable and acquire no new consent on migration. IWN3 adds bounded preference records; it changes the node payload, not the encrypted wallet/setup/native cache or keys. Preference writes retain the existing encrypted atomic-file process monitor and are serialized.

A matching grant causes at most one automatic connect attempt per authenticated unlock, only for a saved open wallet and valid unedited current node. Settings loading, invalid drafts, locked/setup/recovery state, pending authentication and disconnected errors do not silently loop reconnect attempts. Network off or Stop and lock revokes the grant, including after connection failure or while a draft is invalid. The access switch represents remembered permission; actual connected/progress state is reported separately. Ordinary hard lock stops networking/keys and retains the preference for the next authenticated unlock. Node/route changes revoke reconnect permission even if changed back; other endpoint/wallet records remain saved.

Background permission is remembered but execution still requires an enabled notification channel and live dataSync service. Healthy catch-up now permits idle tip polling. UI remains locked, return requires credentials, and new polling cannot run during payment review/authentication or pause. The service cannot unlock keys, prepare/sign/send, show a seed or reopen a native engine. Wake lock is released while idle, bounded during active work and released on destruction. Existing 5h50 deadline and platform timeouts stop the current owner without deleting preferences. No boot/process-death restart, scheduler, extra permission or battery exemption was added.

Payment reservations continue to commit before broadcast; uncertain submissions retry only the same bytes. No TLS/genesis, signed-byte verification, local spent detection, Direct/Tor isolation, node-selection or response-limit boundary was weakened. No automatic HTTP downgrade, alternate node, fallback resolver, remote scanning service or key upload was added. No new seed derivation or cryptographic primitive for Monero was introduced.

## Execution limits

No physical Pixel/GrapheneOS update, encrypted Android Keystore preference round-trip, notification rendering, screen-off/Back/authenticated return, service timeout, runtime permission revocation, actual phone scheduling or live Tor/hardware execution was performed for this release. Instrumentation was compiled only. Host policy/codec tests do not prove Android lifecycle execution. Android may delay or terminate background work; no always-running promise is made. Opening after process death requires authentication before reconnecting.

This remains the existing developer preview with the limitations in README.md/SYNC_SPEND.md, including external spent-status/key-image and hardware signing coverage. Time-based additional locks remain unavailable until a consensus-clock adapter exists. No new independent security audit or production-funds certification is claimed.

## APK and update identity

- File: independent-wallet-v0.11.3-sync-preview.apk
- Package / label: app.independentwallet.android.syncpreview / Independent Sync Preview
- Android version: 0.11.3-sync-preview; code 20
- Rust: 0.11.3
- Minimum API 28; compile/target API 36; arm64-v8a and x86_64
- Bytes: 60,075,740
- APK SHA256: 24182f2edcb0b960b2623c791ae6ccd8884f24848a3ff757f86bbb46e326fb46
- Certificate SHA256: 63b5045ee6e8a9de7fe90fb05f18ea9397a248360ed4ee496b16b1519c7f31e4
- Packaged ARM64 wallet library SHA256: ad9f6c0387fe17f4086d6a887cf4b8b45ae53c2fdd80b0e1387a2f2087c85033
- Packaged x86_64 wallet library SHA256: 162f0254462399decbf148e8a83cb25978676d5985a40154e5f731807b2f4bdc

Install in place without uninstalling/clearing data. Unlock and enable network/background access once after updating, because the prior release did not store consent. Later authenticated unlocks restore the matching saved preference. Wallets, setup, selected endpoints/routes, chosen scan start and committed progress remain. No new rescan is required. The private preview signing key is excluded from deliverables.

Cargo.lock differs from delivered 0.11.2 only in four local workspace version entries. All external package entries and AndroidManifest.xml were compared with the prior archive and match. Build tools retained: Rust 1.99.1, cargo-ndk 4.1.2, Temurin JDK 17.0.21+9, Gradle 8.14.6, AGP 8.10.0, Kotlin 2.1.0, SDK/build tools 36, NDK 28.2.13676358 and verified Monero CLI 0.18.5.4. Tools/caches are excluded from source delivery.
