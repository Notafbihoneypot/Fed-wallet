# Vendored seed utilities

Upstream: https://github.com/monero-oxide/monero-wallet-util
Snapshot: 011f90d73f79bd96cc29f524e3a55d2cf9a5d7b1
Directories: seed and polyseed
License: MIT; preserved in each directory.

Downloaded GitHub API tarball:
https://api.github.com/repos/monero-oxide/monero-wallet-util/tarball/011f90d73f79bd96cc29f524e3a55d2cf9a5d7b1

API tarball SHA-256:
801d5f579535a50de298f9855935e6fc8c67e875f8c6a7305b47864c7304ae34

## Local changes

- Polyseed from_string rejects a word count other than 16 before indexing its fixed polynomial array.
- Legacy decoding uses checked multiply/add and checked u32 conversion instead of panicking on out-of-range word triples.
- Package manifests are adapted to this workspace and its declared Rust minimum. The seed tests use monero-rs' existing Keccak function instead of an additional Git dependency.
- Seed-printing statements are removed from upstream tests.
- Polyseed polynomial arrays use zeroizing guards. Seed output and legacy checksum-prefix strings reserve capacity before copying secret material to avoid freed allocations during growth.
- Formatting is normalized by the workspace formatter.

No wordlists, encoding schemes, checksum algorithms or key-derivation algorithms are changed. The application wrapper supports full English words only, rejects unsupported feature bits, and imposes input/storage bounds.

The 0.2.1 review re-fetched the pinned API tarball, matched its recorded hash, and verified all 23 wordlists byte-for-byte. Source/manifests/tests differ only through the documented adaptations above.

The retained upstream unit tests run with cargo test --workspace. Application regressions separately exercise malformed counts, overflow triples, exact derivations, storage round trips and authority boundaries.

Additional test-vector source:
https://github.com/tevador/polyseed/tree/91b0a954c98f0dbc9cadfcdcb2088ccf83b8ecb8
The PBKDF2 golden value in wallet-keys tests was independently calculated with Python/OpenSSL from that C test's documented entropy and salt, 10,000 SHA-256 iterations and a 32-byte result.
