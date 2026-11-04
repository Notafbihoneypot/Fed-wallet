# Validation — 0.11.5 sync/send coordination candidate

Date: 27 October 2026 (America/New_York). Android 0.11.5-sync-preview, code 22; Rust 0.11.5.

**Signed update blocked.** This delivery contains fixed source, validation and an unsigned APK candidate. Automated workspace maintenance removed the original private preview signing keystore. Source and prior APK archives deliberately excluded it; the APK public certificate cannot recover the private key. The existing signed 0.11.4/build-21 APK has not been replaced by this unsigned candidate. Keep the installed wallet/data. Restoring the matching original keystore is required for an in-place signed update. No replacement key/package, signature-verification bypass or data-clearing workflow was introduced.

Expected original certificate SHA256: `63b5045ee6e8a9de7fe90fb05f18ea9397a248360ed4ee496b16b1519c7f31e4`. It is not a signature on the candidate. Prior signed validation is archived in VALIDATION_0_11_4.md. See SYNC_SEND_0_11_5.md for detailed behavior.

## Findings and changes

The previous 30-second timer entered the sync/UI-reset path even for an unchanged tip, and payment entry was not part of the polling guard. An unchanged tip did not re-download the blockchain. Review/authentication already blocked scheduled work, but the service collector stopped the service because it reused a stricter background-work predicate. Leaving during publication could consequently destroy its network owner. Native confirmation also rejected any new height, causing normal new blocks to force re-quote/sign/reapproval. No fresh phone log establishes which path caused the user's reported Tor failure.

Routine metadata checks are now batched at about five minutes, or one minute while a durable payment is pending. An unchanged tip performs no block-body download/checkpoint write and does not enter the scan UI. Actual new ranges still scan from the committed checkpoint, preserving balance, maturity and spent detection. Sync now/user-started payment catch-up remain immediate. No restore-height advance or skipped block range hides new activity.

Payment entry/preparation/review/authentication/publication exclude automatic polls and manual refresh/rescan races. Service lifetime is separate from permission to perform work: review/authentication retain an idle service. Returning through device authentication retains an existing background owner without permitting a new poll or quote. Timers restart with a fresh delay after completion/dismissal/authentication; no queued immediate sync burst follows. Local payment-input errors are distinguished from transport errors, so dismissing a form cannot hide a network failure.

Only a previously device-authorized immutable signed publication may finish in a live, opted-in background session. Leaving an idle unsubmitted form/review discards it before allowing normal read-only background sync. Discard is local/non-network work and occurs before another quote can start, avoiding a queued discard erasing a newer review. Leaving unapproved construction cancels it. The service never initiates/signs/requotes/retries a payment or reopens keys after process death. A failed review while hidden cannot trigger a replacement signature.

Native confirmation anchors the existing review to its original block height/hash, catches up spent-input status within a maximum 128-block window and the existing five-minute lifetime, rechecks the anchor and rejects spent/missing/reserved/ineligible inputs. Ordinary new blocks do not alter approved amount/fee/ID/bytes. Reorgs, expiry, excessive lag and spent inputs stop before a new reservation or broadcast. Signed bytes/reservations still commit before any publication; uncertain replies keep the same exact pending bytes for explicit retry.

## Executed validation

| Check | Result |
| --- | --- |
| Required Rust check | scripts/check.sh passed: formatting, 93 tests, 0 failures; one isolated-node case ignored by default. Clippy workspace/all targets passed with warnings denied. |
| Real isolated Monero node | One case passed in 85.88 s, verified Monero CLI 0.18.5.4, offline fakechain/loopback-only RPC/P2P. No real funds or public transaction. |
| Idle polling | Unchanged-tip poll preserved committed state with no block-body reads or store writes. Polling during an existing review retained its ID and performed no block download. |
| New block during review | Two normal new blocks were mined after review construction. Confirmation caught up without replacing/re-signing the reviewed transaction; lost-reply pending bytes exactly equaled the originally reviewed bytes. |
| Unsafe review rejection | Expiry and replaced chain anchor stopped before broadcast. A separate fixture engine published a competing transaction using an overlapping input; after mining it, the original review was rejected with no new broadcast/reservation. |
| Earlier payment/scan checks | Max exact fee subtraction/zero-change balance, view-only/input limits, actual-byte/intent/key-image/signature/range-proof/commitment verification, lost reply, encrypted reopen/exact retry, mining, maturity, reorg/rescan and prior bounded-scan/transport regressions passed. |
| Android host/UniFFI | 134 JVM tests / 31 suites, 0 failures/errors/skips. Rebuilt host library and regenerated bindings for pollUpdates used. |
| New policy checks | Entry/review/authentication exclusions; paused service retention; consented immutable publication; background quote prohibition; metadata-poll owner/no overlap; five-minute/one-minute cadence; stop/pause/error/start-choice guards; notification privacy/indeterminate payment state; local payment versus transport errors; background-owner unlock retention passed. |
| Final Android compile | Generated app/root build outputs removed, --rerun-tasks, Kotlin incremental/build cache disabled. 58 tasks executed; unsigned app assembly, JVM tests, instrumentation compilation and lint passed. |
| Instrumentation | 21 existing cases compiled; 0 executed. No test APK/device execution claimed. |
| Lint | 0 errors, 14 warnings: 5 ImplicitSamInstance, 1 UnusedAttribute, 5 dependency-version notices, 3 UseKtx. No dependency/manifest update or disabled check used. |
| Python helper tests | Four source-package/native-environment/APK-integrity regressions passed. |
| Candidate DEX | 5 DEX files / 15,688 classes. ZIP CRC, DEX bounds/checksums and duplicate-class checks passed; assembly enforces the guard. This is not Android runtime bytecode verification. |
| Native/16 KiB | Both release ABIs rebuilt from final Rust production code. All 10 ELF libraries / 28 LOAD segments had alignment at least 16 KiB. zipalign -P 16 passed. Original/merged libraries matched; packaged bytes matched stripped outputs and a fresh NDK strip. Test-only markers absent. |
| Signing | Intentionally unsigned: apksigner verification failed as expected and no JAR signature entry was present. This is not installable as an update. Package/version/minimum/target ABI metadata checked independently. |
| Compatibility | External Cargo.lock packages and AndroidManifest.xml match build 21. Four local workspace lockfile versions changed. Wallet/setup/node/journal encrypted formats remain unchanged. |

## Privacy, limits and remaining work

The selected node/route stays immutable. Direct system DNS, Tor destination resolution, TLS/genesis checks, 32 MiB response bound, timeout/cancellation and no fallback/replay policies remain. No wallet-specific key-image spent query, secret upload, seed derivation or invented Monero cryptography was added. Five user-added nodes, remembered exact-wallet/node/route consent and earlier setup/clipboard rules remain.

The 5h50 service bound, idle wake-lock release, stop/timeout behavior, locked background UI and no process-death/reboot key reopening remain. In-progress approved publication can finish; a new payment cannot begin/sign/requote in background. Stop/network-off or node changes can still cancel network work, retaining any already committed uncertain reservation. Remote-node admission can race a later chain/mempool change; node rejection or lost response retains the exact pending transaction rather than automatically replacing it.

No phone/emulator UI, GrapheneOS lifecycle/Keystore/notification execution, actual screen-off/return behavior, live Tor throughput/routing or hardware execution was performed. These require device validation after original-key signing. The identified code fixes do not prove that every Tor send failure is resolved. Routine timers may be delayed by Android. Block inclusion/first Tor download latency are not guaranteed. Existing one-recipient/16-input/0.05 XMR limits, incoming-mempool/time-lock/hardware/key-image/abandonment coverage limits remain. No independent security audit is claimed.

## Unsigned candidate artifact

- File: independent-wallet-v0.11.5-UNSIGNED-for-original-key-signing.apk
- Package/label: app.independentwallet.android.syncpreview / Independent Sync Preview
- Version: 0.11.5-sync-preview; code 22; minimum API 28, compile/target 36
- ABIs: arm64-v8a, x86_64
- Bytes: 60,284,636
- SHA256: 1b4036d28c82872624ef16a3c2def5e32ce95caa5c39a71efd1440a60b5a4263
- Signature/installable update: none / no
- Packaged ARM64 wallet library SHA256: e8deb0407c0d9674df322f9a23ad4fbca71bdbd346d3e3e43c44d0550653446d
- Packaged x86_64 wallet library SHA256: 82601a3d498d93befd7235743cdc61c411aa9750a1c6f7cafb2daef1a4eb27fe

The unsigned candidate is for original-key signing/review only. Restore the matching keystore at `.private-signing/preview-debug.keystore` and build without `-PunsignedPreview=true` to create a signed update, then verify its certificate/signature/integrity before distribution. The default build does not generate a new compatible identity. Preserve the installed wallet and data.

Tools: Rust 1.99.1, cargo-ndk 4.1.2, checksum-verified Temurin 17.0.21+9, Gradle 8.14.6, AGP 8.10.0/Kotlin 2.1.0, SDK/build tools 36, NDK 28.2.13676358 and checksum-verified Monero CLI 0.18.5.4. Tools/caches/keys excluded from source.
