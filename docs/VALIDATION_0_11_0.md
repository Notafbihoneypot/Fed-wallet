# Validation — 0.11.0 Mainnet software-wallet preview

Executed 26 October 2026 (America/New_York). The user explicitly authorized Mainnet software-wallet support after the earlier preview's Stagenet-only restriction prevented use of their Mainnet node. This release enables the full software path and preserves existing wallets through an explicit authenticated separate Mainnet copy.

## Resulting behavior

Software creation/import defaults to Mainnet, with an explicit Stagenet test-network option. The Rust engine accepts both networks, validates primary and recipient addresses against the opened wallet, and checks the selected node's genesis before RPC attachment. Testnet remains unsupported online. The old hardware-only Mainnet error is replaced with NET_NETWORK for an actual mismatch. Existing cryptographic adapters, signed-byte/intent/signature checks, fee bounds, durable reservations and exact-byte retry remain required.

An existing completed Stagenet software wallet can create an independently encrypted Mainnet copy with the same backup and an explicitly chosen restore height. The native copy requires backup verification, uses the existing seed/private-key adapters, preserves the original snapshot and cannot promote viewing-only/hardware authority. The original wallet/setup files, Keystore aliases and scan history stay saved. A new Mainnet vault/setup slot uses different fixed files/aliases. Slot selection stores only its public name in preferences, locks/revokes the prior owner and starts offline. Completed wallets and existing Mainnet drafts cannot be overwritten. The new copy opens locked and needs explicit network consent after unlock.

The existing scan namespace includes the complete network-prefixed primary address and authority role, so matching keys on Mainnet/Stagenet have different cache IDs, encryption keys and leases. Encryption/snapshot/cache formats are unchanged. Save/unlock/setup stay busy through their final storage checks. Original node settings, explicit Direct/Tor choice, Android DNS handoff, TLS checks, seed clipboard policy and session-only background consent remain in place. No default node, automatic fallback, HTTPS downgrade, added manifest permission or live transaction is introduced.

See MAINNET_0_11_0.md for the implementation, upgrade and interruption behavior. Earlier results remain in VALIDATION_0_10_3.md and historical records.

## Executed checks

| Check | Actual result |
| --- | --- |
| Required Rust check | scripts/check.sh passed: formatting, 84 passing workspace tests, one optional real-node case ignored by default; workspace/all-targets Clippy passed with warnings denied |
| New key/engine cases | Both seed formats preserve backup/key equivalence and original snapshots; private-key copy preserves identity material; invalid height/source modes rejected; Mainnet software accepted, Testnet rejected; wrong-network recipients rejected; matching keys on separate networks obtain independent cache leases |
| Actual offline monerod | The explicitly invoked ignored regtest passed against Monero 0.18.5.4 in loopback-only offline fakechain mode; one test passed in 137.16s after 52.14s debug-profile compilation |
| Real-daemon Mainnet path | Software fixtures now use Mainnet addresses with the production genesis gate and no genesis bypass; synthetic-fund scan, local signing, accepted submission, lost reply, durable reopen/exact-byte retry, confirmation, recipient maturity and reorg recovery all passed |
| Viewing authority | Existing Keystone Mainnet tests plus new generic Mainnet viewing/JNA checks reject prepare/confirm/retry and software-copy escalation; unknown authoritative spent balance remains null |
| Generated UniFFI | Fresh 0.11.0 host library and Kotlin bindings built; new copyToMainnet API/checksum and retained Direct DNS handoff present |
| Actual JVM/JNA suite | 99 tests passed across 24 suites; zero failures/errors/skips; five added Mainnet cases cover generation/backup, copy/source preservation, actual native recipient/network/caches/owner lifetime, viewing-only authority and original storage compatibility |
| Android checks | Initial unit/instrumentation/lint build passed in 4m8s: 59 tasks executed; final check after storage-state/UI/test changes passed in 1m21s: 17 executed, 42 up-to-date |
| Device regression compilation | 20 instrumentation cases compiled, including new encrypted-source preservation, separate slot restore/activity recreation and cancelled-copy authentication cases |
| Native cross-build | Locked offline cargo-ndk build succeeded for arm64-v8a and x86_64 using NDK 28.2.13676358; both packaged libraries export copyToMainnet with checksum 32039 matching generated Kotlin |
| APK assembly | Passed in 26s: 36 tasks, 20 executed/16 up-to-date |
| Lint | Zero errors; 17 warnings: retained service SAM false positives, older-API attribute, dependency-update notices and KTX suggestions |
| Python checks | Four APK-integrity, minimal native-build-environment and source-packaging checks passed |
| APK integrity | App: 5 DEX files, 15,595 classes; instrumentation: 3 DEX files, 1,070 classes; ZIP CRC, DEX bounds/checksums and duplicate-class checks passed |
| Signature/alignment | APK v2 signature and retained certificate verified; 16 KiB ZIP alignment and all 10 native ELF LOAD alignments verified; new method/checksum verified in both ABI libraries |
| Exclusions | App APK checked for private signing/settings files and new test fixture markers; source archive CRC, manifest/local-file digests and exclusions verified before delivery |

The daemon is created with regtest/offline flags, no DNS checkpoints/IGD/ZMQ and loopback-only P2P/RPC bindings, then terminated and its temporary data removed. All mined/spent amounts are synthetic fixtures. No real funds were used, and no public-node transaction was submitted. The read-only public-node checks from the earlier troubleshooting are distinct from this test.

## Device and security limits

Zero Android instrumentation cases executed for this release. No emulator boot, physical Pixel/GrapheneOS update, device-auth/Keystore copy flow, rendered UI, actual background/service scheduling, live public-network synchronization/Tor or hardware test ran. Host compilation, native/JNA and offline-daemon success do not establish those outcomes. Mainnet copy authentication, storage aliases and lifecycle behavior require device verification. The additional encrypted slot is implemented using the existing vault/setup and AtomicFile mechanisms rather than new cryptography.

This remains a debug/developer preview and has not received a new independent security audit or production-funds validation. Hardware signing and verified external key-image/spent-balance import are unfinished; viewing-only received totals can include spent outputs/change. Existing input/fee/subaddress and other scope limitations remain in README.md and SYNC_SPEND.md.

## APK/update identity

- File: independent-wallet-v0.11.0-mainnet-preview.apk
- Package/label: app.independentwallet.android.syncpreview / Independent Sync Preview
- Android version: 0.11.0-mainnet-preview; code 17
- Rust core: 0.11.0
- Minimum API 28; compile/target API 36; arm64-v8a and x86_64
- Bytes: 59,961,060
- APK SHA256: 90dafecee874273c3a714f9ee19e22a65eefb768052632f3fb853658f12cf46e
- Certificate SHA256: 63b5045ee6e8a9de7fe90fb05f18ea9397a248360ed4ee496b16b1519c7f31e4

Package/certificate match Sync Preview v0.8 through v0.10.3. Install as an update to retain the original data; a Stagenet wallet remains Stagenet until the user explicitly creates/selects its separate Mainnet copy. New setup can independently generate/import Mainnet material. Existing original file names/aliases and encrypted formats remain compatible. The older resumetest package is separate. Private signing material is excluded.

The source archive includes a per-file SHA256SUMS manifest and excludes signing keys, wallet files, uploaded logs, native binaries, caches/toolchains/build outputs and local SDK/proxy settings. Dependency declarations are unchanged; Cargo.lock changes only the four local workspace package versions. No crypto/seed derivation is invented. Device authentication, source preservation and chain/address binding stay mandatory.

Tools: Rust 1.99.1, cargo-ndk 4.1.2, Temurin JDK 17.0.21+9, Gradle 8.14.6, AGP 8.10.0, Kotlin 2.1.0, SDK/build tools 36, NDK 28.2.13676358 and Monero CLI 0.18.5.4. Distribution digests were checked against their published metadata; build tools stay outside source deliverables.
