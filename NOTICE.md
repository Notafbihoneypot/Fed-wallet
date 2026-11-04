# Notices

Original project code is GPL-3.0-only. Third-party components retain their licenses and notices.

The vendored seed and Polyseed utilities come from monero-oxide/monero-wallet-util at commit 011f90d73f79bd96cc29f524e3a55d2cf9a5d7b1. Each directory retains its upstream MIT license. See vendor/UPSTREAM.md for provenance and local changes.

The Rust monero crate comes from monero-rs; cryptographic operations and serialization dependencies retain their upstream licenses. Cargo.lock identifies exact packages.

The networked adapter links monero-oxide's monero-wallet 0.2.0 and monero-daemon-rpc 0.2.0 (MIT), and Tor Project's Arti 0.47.0 (MIT OR Apache-2.0). The Android receive QR encoder uses ZXing Core 3.5.4 (Apache-2.0). Their transitive components retain their own licenses. See docs/SYNC_SPEND.md for the reviewed protocol snapshot and primary sources, and docs/DEPENDENCY_REVIEW.md for current advisory findings.

The Gradle wrapper retains Gradle's Apache-2.0 notices. UniFFI-generated Kotlin includes upstream runtime material; UniFFI uses MPL-2.0. Kotlin, AndroidX, JNA and other Android dependencies retain their respective licenses.

Keystone pairing uses ur 0.4.1 (MIT), minicbor 0.19.1 (BlueOak-1.0.0), serde/serde_json (MIT OR Apache-2.0), AndroidX CameraX 1.4.2 (Apache-2.0), and the existing ZXing Core QR decoder. Transitive packages retain their licenses. KEYSTONE.md records the original device-protocol source references; the device firmware and the prior application's C++ adapter are not vendored.

No Monero One, Monerujo, Feather, Molly Android SDK, wallet2 engine or Arti source is vendored into this application. Referencing those projects for compatibility does not imply affiliation or equivalent security/performance.
