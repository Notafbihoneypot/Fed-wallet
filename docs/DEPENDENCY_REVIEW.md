# Dependency review — 0.5.0

For the Keystone milestone, OSV was queried on 23 October 2026 for all 12 newly locked Rust packages: ur 0.4.1, minicbor 0.19.1, minicbor-derive 0.13.0, bitcoin-private 0.1.0, bitcoin_hashes 0.12.0, crc 3.4.0, crc-catalog 2.5.0, phf/phf_generator/phf_macros/phf_shared 0.11.3 and rand_xoshiro 0.6.0. No matches were returned. No pre-existing package versions were updated; serde/serde_json were already locked. Full current Android runtime OSV results are recorded in VALIDATION.md. Absence of a database match does not prove safety.

RustSec scan: cargo-audit 0.22.2, advisory database commit 640bf5a9f06e66d8721e8772766531fe77c8630d (22 October 2026). The raw audit result is **not clean**: one vulnerability advisory and three unmaintained notices are reported. No ignore list is used.

## Retained RSA timing advisory

RUSTSEC-2023-0071 / CVE-2023-49092 affects rsa 0.9.10 in Arti's dependency tree. The advisory describes private-key timing leakage and lists no patched version. Updating to a release candidate does not resolve it according to the current advisory.

Source review found that this app configures Arti as a client, creates no RSA private key, exposes no RSA signing/decryption API and uses Monero curve keys for wallet signing. tor-llcrypto's RSA wrapper explicitly documents that Tor clients do not need RSA private keys; public-key verification is used for Tor documents/certificates. This supports a limited reachability assessment for this configured client, not a blanket claim that the dependency is fixed or safe in every use. A build that adds relay/authority/RSA-private-key functionality must revisit it.

The report retains the advisory instead of patching cryptography or suppressing the scan. Mainnet software spending remains disabled. Version 0.5.0 allows only Keystone view-only mainnet scanning; further independent review and device/Tor validation remain necessary. The RustSec scan below is retained from 22 October; it is not represented as a fresh full cargo-audit run for this milestone.

Primary sources:

- https://rustsec.org/advisories/RUSTSEC-2023-0071.html
- https://docs.rs/tor-llcrypto/0.47.0/src/tor_llcrypto/pk/rsa.rs.html
- https://github.com/RustCrypto/RSA/issues/626

## Maintenance notices

- RUSTSEC-2026-0374: basic-toml 0.1.10, optional host binding generator path: basic-toml → askama_derive → askama_macros → askama → uniffi_bindgen. It is absent from the normal Android library tree.
- RUSTSEC-2025-0141: bincode 2.0.1 is unmaintained. It is locked as an optional typed-index-collections dependency; its feature is not enabled in this application's resolved Android tree, including the host bindgen configuration.
- RUSTSEC-2024-0436: paste 1.0.15 is an unmaintained proc macro used while compiling Arti dependencies such as tor-basic-utils and slotmap-careful. Its generated code forms part of those compiled crates; the proc-macro executable itself is a build-time component.

These are tracked maintenance notices, not silently converted into a passing audit. Exact normal Android dependency paths and additional APK/source checks are recorded in VALIDATION.md.
