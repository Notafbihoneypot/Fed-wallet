# Validation — 0.10.1 node URL fix preview

Executed 26 October 2026 (America/New_York). This is a Kotlin node-entry and save-order correction over the verified 0.10.0 background-sync preview. The uploaded logs do not contain the entered node URL or a specific validation error; the exact user's trigger remains unconfirmed.

## Reproduced defects and result

The old checkedNode adapter forwarded human input directly to the intentionally strict Rust base-URL parser. Four actual native-backed JVM regressions failed before the change: surrounding pasted whitespace, standard /json_rpc suffixes, HTTPS/bracketed IPv6 RPC URLs, and Tor RPC URLs. All four pass after the local input adapter was added. The adapter trims only surrounding whitespace, recognizes only the exact standard suffix, and retains Rust validation of the canonical base URL.

The node screen previously disconnected before validation. Validation now precedes the revocation callback; invalid input preserves the saved node and active connection. Re-saving the same canonical node normalizes the draft without invoking revocation. A valid different endpoint still revokes the old connection and never connects automatically. Missing protocol, bad numeric port, IPv6 brackets, credentials, unsupported paths/queries, malformed authority and route mismatch have fixed specific messages. An oversized paste keeps the previous draft and reports its limit.

Protocol/host/port/route are never guessed or silently changed. No HTTPS downgrade, Tor-to-Direct fallback, node preset, hostname resolution or networking is introduced by input validation or saving. See NODE_URL_FIX.md.

## Executed checks

| Check | Actual result |
| --- | --- |
| Original URL regression | Four cases executed against original application adapter and actual Rust/UniFFI parser; four failures reproduced |
| Fixed JVM suite | 77 passed; zero failures/errors/skips, including all ten new URL acceptance/rejection/redaction cases |
| Existing bridge coverage | Actual native HTTP 403/TLS errors, node migration, entropy/import/authority, encrypted snapshot, progress and background policy checks remained passing |
| Host native library | Rebuilt unchanged wallet-ffi from locked source with Rust 1.99.1; build passed in 1m20s |
| Rust policy smoke test | 19 passed: 18 security-property cases plus one 20,000-mutation adversarial parser test |
| Python checks | Four native-build environment, source packaging and APK-integrity tests passed |
| Android build | App/instrumentation assembly, JVM suite and lint passed: 77 tasks, 66 executed/11 up-to-date, 2m41s |
| Added Android settings tests | Follow-up instrumentation assembly and lint passed: 53 tasks, 7 executed/46 up-to-date, 1m3s |
| Lint | Zero errors; 12 warnings and one trim-lambda hint. Six warnings are dependency update notices; the other six are retained service/attribute/KTX notices |
| APK identity | Sync Preview package retained; Android version code 14; matching retained certificate verified |
| APK integrity | App ZIP CRC/DEX bounds/checksums/duplicate-class checks passed: five DEX files, 15,546 classes; instrumentation: three DEX files, 1,052 classes |
| Class continuity | No external class was removed; moving the checkedNode top-level function removed its old file class and added the input adapter/error classes plus generated UI classes |
| Native continuity | Both Rust libraries byte-identical to verified 0.10.0 APK; all ten native libraries have identical allocated ELF sections to baseline |
| Signing/alignment | APK v2 signature, 16 KiB ZIP alignment and every native ELF LOAD segment alignment passed |
| Fixture/private-file exclusions | No private signing/settings file or new test fixture marker occurs in the app APK |

The JNA 5.17.0 libraries differ in nonallocated stripping metadata because this packaging run did not use the prior NDK stripper; their allocated sections are identical. Native Rust binaries were retained from the original APK whose complete SHA256 was verified against the previous delivery record. No fresh Android Rust compilation is claimed. Generated UniFFI API and 70 native/API source files, Cargo manifests and lockfile were checked byte-for-byte unchanged. Core version remains 0.10.0 intentionally; Android app version is 0.10.1.

The Rust engine/full isolated monerod suite was not rerun for this Kotlin-only correction. Earlier actual results remain in VALIDATION_0_10_0.md and previous records; they are not new execution claims.

## Android execution

Seventeen instrumentation cases compiled. Five targeted cases were prepared for a disposable Android 16 x86_64 emulator, but this environment has no hardware acceleration and software emulation did not complete boot within the bounded 360-second attempt. No APK installation or Android instrumentation test executed. No rendered-UI, actual activity-recreation, physical-device or live-node pass is claimed.

Four new emulator-only instrumentation cases cover invalid-save callback/storage preservation, equivalent-node save continuity, changed-node revocation/persistence/activity recreation, and oversized paste behavior. The targeted execution also includes the existing application-wallet identity retention case. These tests use reserved public node names and do not connect to a public node or use a user wallet.

Actual Pixel/GrapheneOS Android 17 installation/update, the user's node, live direct/Tor traffic, screen-off/background service scheduling and hardware execution remain outside the host results. The original logs cannot establish a particular TLS, permission, RPC-port or reachability failure.

## APK and update

- File: independent-wallet-v0.10.1-node-url-fix-preview.apk
- Package: app.independentwallet.android.syncpreview
- Version: 0.10.1-node-url-fix-preview; code 14
- Minimum API 28; compile/target API 36; arm64-v8a and x86_64
- Bytes: 59,731,680
- SHA256: 9f7c405f573efd326c2b988318576b51006c6137702ca7ab024c9a7b92f41554
- Certificate SHA256: 63b5045ee6e8a9de7fe90fb05f18ea9397a248360ed4ee496b16b1519c7f31e4

Certificate and package match Sync Preview v0.8 through v0.10.0, with a higher version code. Install over that existing app; do not uninstall or clear data. Wallet/setup/node/cache encryption and formats remain unchanged. The separate older resumetest package is still independent. Private signing material is excluded from deliverables; this is a development/debug preview.

## Scope and delivery

Software spending remains stagenet-only. Keystone mainnet/stagenet viewing remains available; hardware signing and verified key-image/authoritative spent-balance import remain unavailable. No new cryptography, transaction authority, seed handling, clipboard policy, dependency declarations, node selection, background service or sync-progress logic was introduced.

No new vulnerability-database audit or physical security review was performed. The retained dependency review and prior security scope remain applicable. Reproduced input defects and passing local checks do not establish successful syncing with an endpoint absent from the logs.

Final source contains a per-file SHA256SUMS manifest and excludes private signing material, wallet files, uploaded logs, native binaries, build caches, local SDK/proxy configuration and credentials. Archive integrity and exact source/manifest verification are recorded with the deliverables. Earlier executed records are preserved rather than presented as fresh results.

Build tools: Rust 1.99.1; JDK Temurin 17.0.21+9; Gradle 8.14.6; AGP 8.10.0; Kotlin 2.1.0; SDK/build tools 36. Published distribution digests were verified. Temporary build tooling is kept outside the source deliverable.
