# Validation — Independent Preview 2, 0.11.6

Date: 27 October 2026 (America/New_York). Android 0.11.6-preview2, code 24; Rust 0.11.6.

## Delivered behavior and compatibility

Open Receive -> New subaddress to create a receiving address with an optional local label; Choose receiving address selects a saved address and Save label renames it. Creation/selection/labels work offline after authenticated unlock of a completed saved wallet. The primary address and up to 1,000 account-0 subaddresses share the existing wallet balance. Their indices, labels and selection are persisted encrypted before display, retained on rescan, and bounded/validated at the Rust boundary. No new wallet seed, network access, remote address service or signing authority is created.

Scanning covers the entire supported address range, caches its lookup table per active engine, indexes unspent key images per window and computes pending-input reservations once per status. It retains actual block/transaction checks, maturity/spent rules and exact signed-byte/intent verification. Seed restore scans before the first receipt to discover used higher addresses; labels/unused issuance history remain local. Old caches reconstruct their used address range from already saved outputs without a new scan. An older previously scanned range can still need an explicit earlier rescan for an address beyond its old 20-address coverage.

The receive UI only displays a QR frame for the exact address used to encode it, renders address choices lazily and generates the QR off the UI thread. Local metadata operations share the existing cache lease, are owner-cancelled, hold no owner mutex across I/O, pause timer/quote races and cannot publish a late result after locking or into another wallet. Returning through device unlock remains possible during an outstanding metadata operation. A terminal native closure disconnects a retained online UI instead of leaving verified connection state displayed.

This is an in-place update for `app.independentwallet.android.syncpreview2`, label Independent Preview 2. The existing backed-up signing certificate remains `01afd9a36efbb0d6573d8f12764164a14af3d99faa4a97ccfce45dff82cd52b5`. No new signing key, key rotation, data deletion or wallet/endpoint migration is performed. Install over Preview 2 without uninstalling/clearing data. The separate original Sync Preview package still cannot be updated without its missing original key. Existing wallet/setup/node/journal keys/formats remain; scan state gains backward-reading optional address metadata. The unsigned source and private signing backups remain separate.

## Executed validation

| Check | Result |
| --- | --- |
| Required Rust checks | scripts/check.sh passed: formatting, 96 tests, zero failures, 1 isolated case ignored by default. Clippy workspace/all targets passed with warnings denied. |
| Isolated real Monero node | One final release-profile test case passed using checksum-verified Monero CLI 0.18.5.4 in offline fakechain/loopback-only mode. No real funds/public transaction. |
| Subaddress receive/spend | A real test transaction to account 0, subaddress 999 was found by a restored wallet whose local issuance count was absent. Its balance/maturity and subsequent Max spend passed actual local signed-byte/proof/intent checks and node admission. |
| Legacy receive metadata | A cache containing that received output with a legacy counter was reopened; its visible address range recovered without a new block scan. Reopened cached state still had to sync before payment preparation. |
| Existing critical regressions | Lost submission reply, durable reservation/encrypted reopen/exact-byte retry, fee/zero-change Max, input/fee caps, view-only/hardware authority, expiry/new blocks/forked anchor/spent-input review rejection, rescan/reorg/maturity and bounded scan/pool/transport tests passed. |
| New address unit tests | Distinct deterministic addresses, offline creation, encrypted reopen, selection/renaming, rescan preservation, legacy missing-field defaults, invalid labels/indices/metadata rejection, 1,000-address limit and cancelled-owner rejection passed. |
| Android host/actual UniFFI | 137 tests / 32 suites, no failures/errors/skips. Includes offline receiving creation/reopen/labels, shared active journal/owner revocation, unverified-setup rejection and policy exclusions. Fresh host native library/regenerated Kotlin bindings used. |
| Android compile/package | Final app assembly, host tests, instrumentation Kotlin compilation and lint succeeded with --rerun-tasks, Kotlin incremental/build cache disabled. Same final source and freshly rebuilt native ABI payloads packaged. |
| Instrumentation | 21 existing device cases compiled; 0 executed. No device/Keystore/UI/lifecycle claim is inferred from compilation. |
| Lint | Zero errors, 14 warnings: 5 GradleDependency, 5 ImplicitSamInstance, 1 UnusedAttribute, 3 UseKtx. No check was disabled. Version notices are not a security scan. |
| Helper regressions | Four source-package/native-environment/APK-integrity Python tests passed. |
| Rust dependency audit | 1380 advisories; fetched database commit 99670cfc0bc574433034b75876a4b791ccaa2efb, updated 2026-10-27T11:02:42+01:00. Three baseline vulnerability alerts -> two retained alerts after ctutils 0.4.3 -> 0.4.4. No ignored/suppressed alerts; final audit exits nonzero. |
| External dependency scope | ctutils is the only changed external Cargo.lock entry. Four local package versions changed; Monero/Tor/TLS/protocol dependencies and Android dependency pins are otherwise retained. |
| APK signature | SDK apksigner verification passed, APK Signature Scheme v3, one RSA-4096 signer; exact Preview 2 certificate digest matched. Minimum API 28 is supported. |
| Installer debugging/backup | Final packaged manifest/badging showed no debuggable application; platform backup remains disabled. Java/native debugging disabled in the build type; native libraries built as optimized releases. Task name assembleDebug does not imply the delivered APK is debuggable. |
| ZIP/DEX | ZIP CRC, DEX lengths/checksums and duplicate-class checks passed: 2 DEX files / 15,736 classes. This is not Android runtime bytecode verification. |
| Native integrity/alignment | Both Android release ABIs rebuilt after final Rust changes. All 10 ELF libraries / 28 LOAD segments met at least 16 KiB alignment; zipalign -P 16 passed before/after signing. Original/merged and packaged/stripped bytes matched; fresh NDK strip reproduced packaged wallet payloads. Test-only markers absent. |
| Signing payload integrity | Every unsigned ZIP payload entry retained byte-identically; only APK signature block/standard META-INF signature entries added. No signing password in APK. |

The final synthetic 1,000-block test compared current batch strategies with 25 ms artificial RPC delay: fixed-32 used 96 RPCs/32 saves, adaptive single used 12/4, and coalesced used 6/1. It does not establish a before/after production speed factor or parity with another wallet. Times depend on concurrent build load and device/node conditions. The new lookup/index changes do not skip blocks or checks.

## Security findings and remaining alerts

The QR/address publication issue, debug-enabled installer exposure and a compatible ctutils timing dependency fix were addressed. Metadata bounds, single-writer ownership and lock/epoch guards cover the new address operations. This is a targeted source review, not a clean security certification.

Two upstream vulnerability alerts remain: crypto-bigint 0.5.5 / RUSTSEC-2026-0387, and rsa 0.9.10 / RUSTSEC-2023-0071. The former is used by the maintained Monero hash-to-point implementation; a patched >=0.7.6 is incompatible with its current ^0.5.1 API constraint. Reviewed uses do not include private scalar arithmetic, but point/ownership data can remain privacy-sensitive, so timing risk is not dismissed. RSA is transitive through Arti client/key-format helpers; this application does not identify a private RSA network-operation path, but this is source triage, not a proof of non-reachability. No compatible complete upstream fix was available for these paths. basic-toml, bincode and paste also carry maintenance warnings. Full details/primary advisory links are in SECURITY_0_11_6.md below.

## Artifact

- File: independent-wallet-v0.11.6-preview2.apk
- Bytes: 55,289,656
- SHA256: `3a14203484d3be31a4fd1c2cd06b27876cb022d5df8b72eaae5fa2177d8d602b`
- Package/version: app.independentwallet.android.syncpreview2 / 0.11.6-preview2 / code 24
- Certificate SHA256: `01afd9a36efbb0d6573d8f12764164a14af3d99faa4a97ccfce45dff82cd52b5`
- ABIs/API: arm64-v8a, x86_64; minimum 28, compile/target 36
- Packaged ARM64 wallet library SHA256: `99ecc7ff054ce9df7f5f34c625d4b4e07ead73a489293658e62d9e63588b13d0`
- Packaged x86_64 wallet library SHA256: `abc0a8bc8ecb4e2f083cb02749b9fb64106890f11b5b65d24855dd46d7cca788`

## Limits and device validation

No phone/emulator installation or screen rendering, GrapheneOS lifecycle/Keystore/notification execution, real screen-off/return test, live Tor routing/throughput/send or hardware execution occurred. The final APK needs device validation. No real-money transfer or full independent security audit is claimed. Non-debuggable does not make this preview production-ready. Existing one-recipient/16-input/0.05-XMR fee, incoming mempool/additional time-lock/hardware/key-image-import/permanent-abandonment limits remain. Only account 0 and primary plus 1,000 subaddresses are scanned; earlier restore starts remain necessary. Labels/unused issuance history are not part of a seed backup, and metadata saves still rewrite the existing bounded encrypted cache.

Node/route consent, no presets/failover/TLS downgrade, remembered authenticated reconnect, five user-added endpoints, bounded opt-in background service, no boot/process-death key reopening, device lock/overlay/screenshot/backup protections and explicit clipboard-copy policy remain. No wallet secret, node credential, full user transaction or private APK key/password was uploaded in source/report/APK artifacts.

Tools: Rust 1.99.1, cargo-ndk 4.1.2, Temurin JDK 17.0.21+9, Gradle 8.14.6, AGP 8.10.0/Kotlin 2.1.0, SDK/Build Tools 36 and NDK 28.2.13676358, cargo-audit 0.22.2, Monero CLI 0.18.5.4. Downloaded toolchain components checked against pinned/published hashes. A build-network runtime CA was installed only in the build JDK's truststore; application/node trust roots and validation were not changed.
