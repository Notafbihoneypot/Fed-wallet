# Keystone view-only companion — 0.5.0

Implemented 23 October 2026. The two setup choices are Software wallet and Hardware wallet. Software supports fresh offline OS-CSPRNG generation or import of supported existing seeds/canonical deterministic spend keys. Every generated seed is made on its user's device; no predetermined seed, key service or custom randomness fallback is introduced. Import validates supported formats and key relationships, but cannot measure the original imported keys' entropy.

## Hardware account flow

1. On Keystone, select the Monero connection QR under Feather, using the normal unencrypted connection mode.
2. Select Hardware wallet in Android. Optionally enter a known restore height from before the first incoming payment; blank preserves the QR's height, normally zero. Choose Scan Keystone connection. Camera permission is requested at that action. Pasting the connection data is available when a camera is unavailable. An override is bounded in Rust and preserved in the encrypted snapshot; a height after a payment can hide that payment from the scan.
3. Rust validates the address checksum, primary-address type, supported network, canonical nonzero private view key, matching public view key and prime-order nonidentity public spend point. Mainnet and stagenet are accepted; testnet is rejected by this adapter.
4. Compare the full primary address with Keystone and explicitly check the matching-address box before saving the completed wallet. In 0.6.0, imported viewing data first receives a credential-protected encrypted setup checkpoint before display; connection still requires the completed-wallet save. The QR's source string identifies the supported format; it is not cryptographic device attestation.
5. Choose the appropriate node yourself and connect through embedded Tor. Mainnet viewing uses the mainnet genesis identity and mainnet subaddress prefixes. No preset nodes, discovery, trust elevation, direct connector or key-image spent query is added.

Hardware account snapshots use IWK1 origin tag 5 and contain only the private view scalar and primary address. Opening the snapshot restores Keystone mode, with no recovery phrase or spend scalar. All native prepare, confirm and retry paths reject accounts without a spending key. Mainnet software wallets are available from 0.11.0; this does not give a Keystone/view-only account a local spending key. Pending payments in a view-only cache are rejected; hardware caches have their own namespace.

## QR boundary

The parser supports the official JSON version 0 fields `version`, `primaryAddress`, `privateViewKey`, `restoreHeight`, `encrypted`, and `source`; optional `walletName` is ignored for display. Unsupported versions, private/encrypted QR, unknown fields (including spending-key fields), duplicates, wrong JSON types and trailing data are rejected without echoing input. Address and view-key validation remains in Rust. Hardware seed phrases have no import path here.

Raw JSON and single/multipart `ur:bytes` are supported. Other UR types cannot enter pairing. JSON is capped at 4096 bytes; a QR frame at 9000 ASCII characters; declared messages at 4099 CBOR bytes; fragment count at 64; received-frame attempts at 256; each scan session expires after 120 seconds. Fountain counts, byte lengths, fragment geometry, URI indices and stream identity are checked before the upstream decoder can allocate based on declared counts. Duplicate frames are ignored, and mixed streams are rejected. Pairing completion consumes the wallet into its native owner.

UR 0.4.1 provides standard Bytewords/CRC/fountain decoding; this app does not invent that encoding. Its deterministic Xoshiro fragment selection is unrelated to wallet entropy generation, which remains fixed to OsRng. Intermediate third-party decoder, camera, Kotlin and FFI allocations cannot all be reliably wiped. No total memory-erasure claim is made.

CameraX 1.4.2 supplies lifecycle-bound preview and Y-plane image analysis. The existing ZXing library decodes QR locally, with bounded resolution, throttled frames, one analyzer worker and at most one queued delivery. The app creates no photo/video files and uses no ML Kit/Google Play services. Stop/disposal unbinds only this scanner's camera use cases. Device execution still needs validation on Android/GrapheneOS.

## Current limits and signing boundary

This is a view-only integration. It scans detected received outputs and displays their total, which may include spent funds and change. It does not provide authoritative spent or spendable balances. Verified output export/key-image import and hardware signing are not implemented, so Send stays disabled for hardware accounts.

The prior hardware repository was inspected at `ef4b48e5f5d38ecbcf3a08a4abbb3939607b6d0f`. Its adapter uses C++ wallet2 exports/imports, unlike this project's Rust engine. In particular, its temporary trusted-daemon import behavior is not copied. Its earlier successful physical-device transaction is evidence for that earlier application/device pairing, not a test of this APK.

The next hardware payment adapter needs the actual encrypted Monero cold-wallet archives, locally verified key-image/output mapping and exact signed-byte cryptographic/payment verification. A QR summary or copied UI cannot enable spending. The reviewed Keystone signed-set serializer writes a placeholder transaction secret key; a future verifier must establish the actual destination/amount binding with a supported protocol rather than treating returned construction metadata as proof. No firmware change or blanket interoperability claim is made by this milestone.

## Primary source snapshots

- Keystone firmware `3690bf1f36cb9e817f9a2412a9fc7560c08953f0`: `src/ui/gui_widgets/multi/cypherpunk/gui_connect_wallet_widgets.c` maps Feather to GuiGetCakeData; `rust/rust_c/src/wallet/cypherpunk_wallet/cake.rs` constructs the version-0 connection JSON.
- https://github.com/KeystoneHQ/keystone3-firmware/blob/3690bf1f36cb9e817f9a2412a9fc7560c08953f0/rust/rust_c/src/wallet/cypherpunk_wallet/cake.rs
- https://github.com/KeystoneHQ/keystone3-firmware/blob/3690bf1f36cb9e817f9a2412a9fc7560c08953f0/rust/apps/monero/src/signed_transaction.rs
- Monero v0.18.5.4 network constants: https://github.com/monero-project/monero/blob/85f036ba7019cd36f08e8a38a27c37ea7b522618/src/cryptonote_config.h
- UR 0.4.1 archive SHA256: `010f24a953db5d22d0010969ca3bbf40b3857b89f47c0f7be0da4c2d7ded0760`; https://crates.io/crates/ur/0.4.1 and https://github.com/dspicher/ur-rs
- https://developer.android.com/media/camera/camerax/architecture

See VALIDATION.md for checks actually executed, dependency findings and remaining physical-device/live-Tor coverage.
