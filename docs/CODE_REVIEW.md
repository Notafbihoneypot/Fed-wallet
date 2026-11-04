# Security review — Independent Android Wallet 0.2.1

Historical 0.2.1 offline review. For the 0.3.0 networked integration, see SYNC_SPEND.md and current VALIDATION.md.

Reviewed 21 October 2026. Scope: Rust keys/session/policy code, vendored seed utilities, Android UI/authentication/storage, manifests, build scripts, dependency advisories and source/APK contents. This is a development review with tests, not an independent security audit.

Software and view-only wallets remain supported. This is an offline, stagenet developer build. Scanning, spending, Tor connections and hardware pairing are unavailable.

## Findings repaired

| Finding | Impact and scope | Fix and verification |
| --- | --- | --- |
| Vault creation used per-instance locking; a failed save confused file existence with a saved session | Separate activities could race key/file creation. A file appearing during setup could make an unsaved wallet appear saved after its save failed. | Shared creation gate covers existence checking, key creation and atomic writing. Conflicting saves lock the unsaved session. Three JVM gate tests passed; the Android collision regression compiled but was not device-executed. |
| Connection tickets lacked issuing-gate identity | Equal node/counter values allowed independent gates to accept each other's tickets. This is a latent adapter isolation defect; there is no network transport in this APK. | Tickets retain an Arc owner and require owner identity. The regression failed against the old implementation and passed after repair. A ticket still does not prove Tor routing. |
| Source packaging swept arbitrary files outside Android | Environment files, wallet files, certificates and signing keystores could accidentally enter a source ZIP. No such real secrets were found in the reviewed ZIP. | Source-format allowlists, restricted Android properties files and symlink checks. A dummy-secret regression reproduced the old inclusion and now passes. Secrets embedded in otherwise valid source files still require content scanning. |
| cargo-ndk's panic report prints its environment | Inherited registry, signing, cloud or proxy credentials could enter build logs. This concerns the build host. | Fetch first, then run cargo-ndk offline with an environment allowlist. The dummy-credential regression passed. |
| Gradle 8.14.1 matched published high-severity advisories | Build-host daemon deserialization and repository-failure handling. | Updated to 8.14.6; official distribution and wrapper-JAR checksums verified. Android compilation, JVM tests, lint and APK assembly passed with it. |
| Build tooling resolved JDOM 2.0.6, matching CVE-2021-33813 | XML external-entity advisory in build tooling. JDOM is absent from the APK runtime graph; wallet inputs do not use XML. | Pinned 2.0.6.1 when present in build/project configurations. Gradle selected the patched root module; the checked lint configuration had no separate matching module. Patched parser/API/application-XML smoke checks passed. OSV returned no match for the patched version. |
| Secret allocations could grow before their zeroizing wrapper cleared them | Reallocation can leave an old secret buffer unwiped. Temporary Polyseed coefficients also lacked a zeroizing guard. This was not a demonstrated remote leak. | Preallocated view-key snapshots, seed output and legacy checksum-prefix strings; guarded Polyseed polynomial arrays. Existing derivation vectors and the expanded Rust suite passed. Kotlin/FFI copies remain a limitation. |

Additional hardening: one outstanding credential request; restore-height validation again when Import runs; session handover only after preparation succeeds; native-linkage failures handled during vault operations; content-capture exclusion on API 30+ and overlay hiding on API 31+. Import errors show their human-readable detail without a generated field-name prefix.

Earlier protections remain: fixed seed-parser count/overflow handling, strict full-word formats, canonical/nonzero scalars, canonical address keys and subgroup checks, matching address/view keys, backup confirmation, monotonic authentication expiry, bounded authenticated storage, backup exclusions and no default nodes/network permission.

## Sensitive-information review

No genuine credentials, user seeds, private wallet files or signing keystores were found in the reviewed source and APK scans. Source test data intentionally include public upstream mnemonic/private-key vectors and emulator PIN 246810. These are fixtures, not production defaults. The ordinary APK excludes test code.

Gitleaks 8.30.1 used default rules with full output redaction. Additional inspection covered hard-coded 32-byte hex literals, secret logging, saved state/intents, backup configuration and APK strings. The final APK contains none of the checked public seed/key fixtures. Scanner success does not prove that every possible secret or memory leak is absent.

The final source scan raised two benign generic-api-key alerts. One was review prose about future Tor testing; the other was the published upstream archive checksum. The exact source fragments were inspected, and the checksum matched the downloaded archive. Neither is a credential. No scanner allowlist was added; the raw default-rule scan exits nonzero and requires this documented triage.

The source packager excludes build caches, native binaries, local SDK configuration and private-file formats. The verified Gradle wrapper JAR is the intended binary exception.

## Verification

- Rust 1.99.1: formatting, 40 workspace tests, Clippy with warnings denied.
- 36,096 deterministic mutation cases for snapshots/view-only authority, URLs and amounts; additional key/address/boundary regressions. These are mutation tests, not coverage-guided fuzzing.
- 17 Kotlin/JVM tests: zero failures, errors or skips. Four exercise the real host JNA/UniFFI Rust library for backup/locking, view-only storage, malformed inputs and concurrent calls.
- Two Python regressions for packaging and native-builder environment isolation.
- Both Android native ABIs and regenerated Android-mode bindings; application and instrumentation APK assembly; lint with zero errors and three warnings.
- APK v2 signature, ZIP 16 KiB alignment and all six native libraries' 16 KiB ELF load alignment verified. No INTERNET or CAMERA permission.
- Cargo audit: zero known vulnerabilities; one informational basic-toml maintenance notice in the optional host-generator chain. The normal Android library tree excludes it.
- OSV: zero matches for 55 resolved APK runtime Maven coordinates. The separate build-only JDOM finding was patched and checked separately. Every build transitive/native component was not independently audited.
- Pinned upstream seed API tarball hash matched; all 23 wordlists matched byte-for-byte.

The JDOM resolution pin followed APK assembly. Its Gradle configuration and parser checks ran separately; no application Kotlin/Rust source changed after the passing application build. The APK does not contain JDOM. Full APK assembly was not repeated after that build-only pin.

## Remaining work before production

Kotlin strings, Compose rendering and UniFFI serialization buffers can retain secret copies. Clearing the display and zeroizing owned Rust values do not guarantee complete memory erasure. A dedicated secret-buffer boundary needs further design and review.

Five Android instrumentation cases compiled; zero executed. Device credentials, Keystore, process death, configuration/lifecycle transitions and OEM behavior need device execution. Host tests do not exercise the Android loader/Keystore.

Still required: independent security/cryptographic review, coverage-guided fuzzing, release/reproducible-build verification and the actual scanning/signing, verified local key-image, Tor/DNS/failure and hardware-device integrations. Payment-summary tests do not verify transaction signatures; connection-policy tests do not prove Tor routing.

## Test APK

The new debug signing certificate differs from the previously supplied APK. Android cannot update that installation in place. Use a separate disposable emulator and preserve existing wallet data/backups.

APK SHA256: dd18f61ced0e1dfeae9b4987d61132f554550c11adf006270b3ef79b79dc974b.

## References

- Gradle advisories: [SSL repository errors](https://github.com/gradle/gradle/security/advisories/GHSA-j5m7-59rp-24f5), [client/daemon deserialization](https://github.com/gradle/gradle/security/advisories/GHSA-xwqc-3h47-hg64), [worker/daemon deserialization](https://github.com/gradle/gradle/security/advisories/GHSA-mvvg-497x-hmj8), [exhausted retries](https://github.com/gradle/gradle/security/advisories/GHSA-mqwm-5m85-gmcv), [unknown hosts](https://github.com/gradle/gradle/security/advisories/GHSA-w78c-w6vf-rw82).
- JDOM: [publisher's patched release](https://github.com/hunterhacker/jdom/releases/tag/JDOM-2.0.6.1) and [CVE-2021-33813](https://github.com/advisories/GHSA-2363-cqg2-863c).
- RustSec: [basic-toml maintenance notice](https://rustsec.org/advisories/RUSTSEC-2026-0374.html).
