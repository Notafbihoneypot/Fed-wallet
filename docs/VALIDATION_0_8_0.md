# Validation — 0.8.0 animated sync preview

Executed 25 October 2026 (America/New_York). The dashboard and Node screen show native pixel-orbit activity, real block-range percentages/heights/remaining counts, connection/checking/paused/synced states and slow-batch timing. Completion is derived from the live native session rather than animation. An optional onion action appears after completion; it opens the chosen slot and keeps network access off until explicitly enabled. HTTPS and onion endpoints both continue through embedded Tor.

## Executed checks

| Check | Actual result |
| --- | --- |
| Rust scripts/check.sh | Formatting, 66 tests passed, zero failures; all-target Clippy with warnings denied passed |
| New native regression | Persisted scan start changes on rescan and survives encrypted-cache reopen while original wallet restore height remains different |
| JVM | 58 passed; zero failures, errors or skips, including actual native/JNA bridge tests |
| Progress tests | Unknown/inconsistent heights, restore-range percentages, no premature 100%, cached/error/disconnected/refresh states, unsigned bounds, reorg/rescan rewind, elapsed-clock reset and orbit geometry |
| New actual bridge | Create cache at original height 100, rescan to 40, reopen, read native start 40 and compute 50% at height 120 of target 200 |
| Existing checks | Seed/import, ownership, backup/Keystone authority, node slots, poll cancellation, encrypted setup/resume/discard, clipboard and authentication windows remain passing |
| Python | Four source-packaging/build-environment/APK-integrity tests passed |
| Native Android | Clean arm64-v8a/x86_64 release builds after the final status API change; Kotlin bindings regenerated |
| Android | Final app/instrumentation assembly, JVM verification and lint passed; final 77 tasks, 48 executed/29 up-to-date, 2m4s after the separate unit/compile stage |
| Lint | Zero errors; 13 warnings: ten newer-version notices, one API-level attribute warning and two KTX suggestions |
| App integrity | Five DEX files, 15,521 distinct classes; ZIP CRC, lengths, SHA1/Adler checksums and duplicates checked |
| Instrumentation integrity | Three DEX files, 1,037 distinct classes; structural checks passed |
| Signing/alignment | APK v2 signature, 16 KiB ZIP alignment and ELF LOAD alignment for all ten native libraries verified |
| Native provenance | Packaged Rust libraries match independently stripped fresh compiler outputs for both ABIs |
| Device execution | Twelve instrumentation cases compiled, zero executed; two added real Compose counter/completion cases |

The new native/UniFFI start_height status field reads encrypted engine state. It fixes the case where a rescan begins earlier than the wallet's original restore height. The Kotlin projection uses that authoritative field; Kotlin bindings and both native libraries were rebuilt together. Four local package versions moved from 0.6.0 to 0.8.0. Registry dependency name/version/source tuples are unchanged; no new dependency or runtime feature was added. Wallet/setup/node/cache encrypted formats, transport and transaction algorithms are unchanged.

The real isolated Monero integration was not rerun for this presentation/status-only milestone. Its successful 62.19-second run in v0.7.0 is historical evidence, recorded in VALIDATION_0_7_0.md. No live public-node/Tor execution is counted here.

## Presentation and routing boundaries

The orbit/scan line indicates activity; numeric progress is not driven by a timer. Unknown height remains indeterminate. Reaching a number alone cannot show Synced: a connected enabled session, known consistent range, native synced flag and finished sync check are required. Errors, disconnected/cached results and fresh tip checks suppress completion. Reorgs/rescans may reduce progress; changed native block counters reset the last-update timestamp in either direction.

Animation is static for offline/paused/synced states or when Android reports animators disabled. The display clock refreshes once per second only for active states and is disposed with the card. It does not keep the screen awake, touch the inactivity deadline or start network/background work. Text describes every state; decorative pixels are hidden from accessibility output, with range/state semantics and a polite title. Larger-font, reduced-motion, screen-reader, actual frame/layout and lifecycle behavior remain device checks, not host-test claims.

After completion on an HTTPS endpoint, the optional Choose saved onion node/Add an onion node action disconnects and opens that slot. It preserves the HTTPS setting, offers no preselected node and does not reconnect. Both endpoint kinds already use Tor; this milestone does not introduce direct IP routing or imply that Tor protection begins only after syncing. Existing native backup/authority/transport gates remain authoritative.

Resume/Discard setup, local encryption, manual clipboard retention and disabled platform backups remain. The new sync clock has no clipboard cleanup behavior. Native cancellation/epoch checks reject late results; last durable batch/payment checkpoints are retained. Software remains stagenet only; Keystone supports mainnet viewing with hardware sending/key-image spent-status import still unavailable.

## APK and signing identity

- Package: app.independentwallet.android.syncpreview; label: Independent Sync Preview
- Version code 10; version 0.8.0-sync-preview
- Minimum API 28; target/compile API 36; ABIs arm64-v8a and x86_64
- APK size: 59,420,144 bytes
- APK SHA256: 19cb98255593977f3b2eddbf6f7a4a203b455505301b98bc80ab537282f8138c
- New preview certificate SHA256: 63b5045ee6e8a9de7fe90fb05f18ea9397a248360ed4ee496b16b1519c7f31e4

Workspace maintenance removed the earlier private debug signing key. It was deliberately excluded from saved source and deliverables; the recovered APK provides its public certificate, not its private key. This build therefore uses a separate package/new private key and installs beside v0.7. It cannot update or automatically migrate that older installation. Keep the old app/data; pair Keystone again or import a written stagenet software backup and enter your node in this preview. PREVIEW_SIGNING.md explains the boundary. No private signing key is uploaded. Stable production/future updates require a protected developer-owned signing workflow outside a transient workspace.

## Sensitive-data and dependency checks

Gitleaks 8.30.1 default rules with redaction found zero matches in extracted APK DEX/native strings. Public spend/view and test-only fixture markers were checked absent. The source scan retains two inspected historical false positives: docs/CODE_REVIEW.md line 55 and the public archive checksum in vendor/UPSTREAM.md line 11. No allowlist was added; raw source finding status remains nonzero. These scans cannot prove absence of all secrets or vulnerabilities.

No fresh full vulnerability database audit was performed. DEPENDENCY_REVIEW.md retains unresolved rsa 0.9.10 RUSTSEC-2023-0071/CVE-2023-49092 and three maintenance notices. This is not an independent audit or a major-wallet security/speed parity claim. Kotlin/Compose/JCA/FFI copies cannot all be reliably erased.

Tools: Rust 1.99.1, cargo-ndk 4.1.2, UniFFI 0.32.2, Temurin JDK 17.0.21+9, Gradle 8.14.6, AGP 8.10.0, Kotlin 2.1.0, SDK/build tools 36 and NDK 28.2.13676358. Downloaded distributions were checked against published digests. Builds used temporary compiler/cache output to avoid stale managed-workspace caches.

## Remaining execution

Twelve Android instrumentation cases compile. Zero were executed. The new cases exercise actual Compose progress/completion text with synthetic public counters; they are emulator-guarded and are not part of the app APK. Actual phone install/rendering, high font scale, motion preferences, accessibility, screen-off behavior, GrapheneOS network permission and live Tor/onion/HTTPS routing/DNS/cancellation captures remain pending. Physical Keystone pairing and production review remain pending. Compilation and host checks do not substitute for these executions.

Hardware spending, authoritative hardware spent-balance sync, software mainnet support, more accounts/subaddresses and locked/background scanning remain outside this release. Received-output totals can include spent funds/change. Full-history restores may take substantial time; the animation does not accelerate scanning or guess a later start height.

## Delivery

Source contains per-file SHA256SUMS and excludes native binaries, build caches, signing keys, credentials and wallet files. ZIP CRCs, every manifest hash, exclusions and runtime/test identity against final build input were verified. Earlier records remain in VALIDATION_0_7_0.md and older documents. The delivery report combines SYNC_PROGRESS.md with this record and appends artifact digests outside the source tree.
