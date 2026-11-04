# Validation — 0.10.2 node entry preview

Executed 26 October 2026 (America/New_York). This is a Kotlin input/settings/permission-preflight correction over 0.10.1. No Rust, generated UniFFI API, native dependency, wallet storage or transaction-authority change is included.

## Confirmed behaviors corrected

1. A bare hostname/IP plus RPC port was rejected because the form required a typed full protocol prefix. The form now shows HTTP/HTTPS selection and uses that explicit selection for a bare entry. Full URLs keep their own scheme; HTTPS is not silently downgraded.
2. Editing the node field left the connection controls using the previous saved URL. Both the node screen and wallet dashboard now require a loaded, valid saved URL matching the current draft/protocol. Editing or a validation error blocks new connections until Save succeeds, while an existing scan is not cancelled merely by editing.

Saving a valid different endpoint still revokes the old session and never connects automatically. Equivalent canonical saves retain the live scan. NodeStore's encrypted format and independent Direct/Tor slots remain unchanged. No node, Monero RPC port, route, or fallback is chosen automatically. The empty form visibly selects HTTP and discloses its unencrypted transport cost.

Connection entry revalidates the full canonical URL before native setup and checks Android INTERNET permission before DNS/network access. Denied app Network access receives NET_PERMISSION with profile-specific guidance. This is not a workaround for a misspelled/unavailable hostname, VPN or DNS failure. No permission is granted automatically and no failed connection is displayed as connected.

See NODE_ENTRY_0_10_2.md for behavior and limits.

## Supplied-log evidence

The latest uploaded log is from Sync Preview version code 14 in a secondary Android 17 profile. It records ten Direct NET_DNS connection failures and ten matching operation failures, with no other WalletNetwork messages and no fatal crash markers. The log SHA256 is 96c825ec14cff52b463e5656571cfb46deb2e7989dc795478bd3bd92a2ce02e4.

The diagnostics omit the hostname. They establish failed hostname lookup before a TCP/Monero handshake; they cannot distinguish an invalid/stale hostname, resolver issue or access policy. They do not prove that a missing prefix or unsaved edit was the user's exact trigger. No uploaded log, user node address, wallet material or private signing key is packaged.

## Executed checks

| Check | Actual result |
| --- | --- |
| JVM suite | 85 tests passed; zero failures/errors/skips |
| New regression cases | Eight tests passed: bare host/IP/IPv6 entry with selected protocol and exact port; native normalization/storage round-trip; explicit HTTPS retention; malformed scheme and Direct/Tor boundaries; credential/path/port/length limits; stale host/protocol suppression; settings readiness/errors; empty Tor-slot isolation |
| Retained tests | Previous URL, native HTTP 403/TLS error propagation, key/Keystone authority, migration, encrypted setup/vault, auth/lifetime, clipboard and live-progress policy coverage remained passing |
| Host native build | Rebuilt unchanged wallet-ffi from locked source for actual native-backed JVM calls; passed in 2m25s |
| Python tests | Four build-environment/source-packaging/APK-integrity checks passed |
| Android build | App/instrumentation assembly and lint passed: 72 tasks executed, 7m31s |
| JVM build/run | 22 tasks: 5 executed, 17 up-to-date; passed in 41s |
| Lint | Zero errors; 15 warnings: nine dependency update notices plus six retained service/attribute/KTX notices |
| APK integrity | App: five DEX files, 15,550 classes; instrumentation: three DEX files, 1,055 classes; ZIP CRC/DEX checksums/bounds/duplicate-class checks passed |
| Class continuity | No external class removed relative to 0.10.1; the new protocol enum and generated Kotlin/UI classes account for expected app-code changes |
| Native continuity | All ten packaged native libraries byte-identical to the verified 0.10.1 APK |
| Signing/alignment | Matching certificate, APK v2 signature, 16 KiB ZIP alignment and all native ELF LOAD segment alignments verified |
| Private-file/fixture exclusions | App APK contains no private signing/wallet/settings file or new test fixture markers |

The native core remains 0.10.0 intentionally; Android app version is 0.10.2. Rust source, generated bindings, manifests and lockfile were checked unchanged. The full Rust/isolated-node suite and Android Rust cross-compilation were not repeated for this Kotlin-only change. Actual earlier records remain in VALIDATION_0_10_1.md, VALIDATION_0_10_0.md and older files.

## Device execution and limits

Eighteen Android instrumentation cases compile, including the new actual ViewModel/store regression for a bare hostname save and stale endpoint blocking. Zero Android instrumentation cases executed for this build. No new emulator boot attempt was made after the prior bounded software-emulator boot failure.

Actual Pixel/GrapheneOS installation/update, rendered protocol controls, Network-permission denial/regrant, live node/DNS/Tor traffic, background lifecycle and physical hardware remain unverified. Host tests and compiled instrumentation do not establish those outcomes. In particular, the new permission preflight still requires execution on GrapheneOS; no claim is made that the supplied NET_DNS failure was caused by revoked permission.

## APK and update identity

- File: independent-wallet-v0.10.2-node-entry-preview.apk
- Package/label: app.independentwallet.android.syncpreview / Independent Sync Preview
- Version: 0.10.2-node-entry-preview; code 15
- Minimum API 28; compile/target API 36; arm64-v8a and x86_64
- Bytes: 59,748,068
- APK SHA256: 6a22f606cf00c143536a373cc0b8d07d9c3848b19bddc28511241b938db36a37
- Certificate SHA256: 63b5045ee6e8a9de7fe90fb05f18ea9397a248360ed4ee496b16b1519c7f31e4

The package/certificate match Sync Preview v0.8 through v0.10.1, with a higher version code. Install over the existing Sync Preview without uninstalling or clearing data. Stored full URLs initialize the displayed protocol correctly. Wallet/setup/node/cache formats remain unchanged. The older resumetest channel remains separate. This is a development/debug preview; private signing material is excluded.

## Scope and delivery

Software spending remains stagenet-only. Keystone mainnet/stagenet viewing remains available without hardware signing or authoritative external key-image/spent-balance support. Cryptography, seed/key handling, sync calculation, background service and transaction authority are unchanged. No new vulnerability-database audit or physical security review was performed; retained dependency/security limitations still apply.

Source packaging includes a per-file SHA256SUMS manifest and excludes native binaries, toolchains/build caches, wallet files, uploaded logs, signing keys, local SDK/proxy configuration and credentials. Archive CRC, every manifest digest, exact local source bytes and exclusions are checked before delivery. Earlier validation reports are preserved as historical records.

Build tools: Rust 1.99.1; Temurin JDK 17.0.21+9; Gradle 8.14.6; AGP 8.10.0; Kotlin 2.1.0; SDK/build tools 36. Distribution checksums were verified. Tools are outside the deliverable source.
