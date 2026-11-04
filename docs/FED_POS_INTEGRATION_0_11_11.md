# Fed POS 0.2 integration review — source-only candidate, 3 November 2026

## Input and limits

Reviewed the user-provided `Fed POS 0.2 — Code & Build Guide.mht`, exported from a Claude artifact. It is a *build guide*, not the actual `fedpos-0.2.0-src.zip` or `.tar.gz` claimed in the guide. The guide reports 92 host Rust tests, but those tests cannot be reproduced without that source archive. It expressly says the Android POS UI has never been compiled. There is **no validated merchant POS APK** at this stage.

An older `Fed-POS-0.1.0-source-and-backend.zip` was recovered separately from the user's prior saved files. Its `scripts/check.sh` ran **19 Python mock-RPC tests successfully** in this review. However, it is a different architecture: the Android terminal talks to a separate Python/SQLite server and a view-only `monero-wallet-rpc` instance. It does **not** meet the newly requested 0.2 on-device/no-POS-server design, so it is not interchangeable with the missing 0.2 Rust source and was not silently substituted. The older tests use fake RPC data, not Monero network settlement.

The existing Independent Wallet source is 0.11.10-preview2, and its Rust scanner registers only account 0, primary and 1,000 minor subaddresses. The POS guide instead derives checkout addresses under account 1 and requires at least 200 unused-index lookahead. **These accounts are incompatible until account-1 scanning is implemented and verified.** Do not present the existing wallet as capable of seeing all POS payments.

## Implemented here: payer-side NFC compatibility

- In Send, choose `Tap to pay at POS` to read a foreground NFC Forum NDEF tag with exactly one well-known URI record. Reject other record types, multiple records, oversized payloads, payment URIs with unknown/duplicate fields, cross-network prefixes, zero/missing amounts, amounts with more than 12 decimals, amounts greater than u64 atomic units, and URI fields such as secret keys.
- Choose `Read another wallet by NFC` to retain the existing IWM1 private APDU protocol. Android recommends `FLAG_READER_SKIP_NDEF_CHECK` for HCE phone-to-phone reads; merchant NDEF reading cannot use that flag. Separating the modes avoids accidentally treating unrelated NFC tags as a payment.
- The merchant request fills destination and *exact quoted amount* and locks both text fields until `Clear POS request`. The user must independently verify the merchant and full recipient address, then review the native prepared amount, fee and total and explicitly authorize with the existing authentication process. NFC never conveys a signed transaction or authorizes broadcast.
- `PaymentQrScanControl` already accepts ordinary Monero payment URIs with amount; it remains independently available, but a QR scan is not an authenticated merchant identity.
- The existing request parser had a missing space after `else` in a Kotlin expression, preventing standalone compilation. This was corrected without changing the amount arithmetic.

**Verification performed here:** `bash scripts/check-pos-parser.sh` (host Kotlin compiler + JRE): valid merchant amounts plus negative cases and legacy private NFC codec. This is not an Android APK build, not UI integration testing, and not proof of NFC/GrapheneOS interoperability. The JUnit source test `PosPaymentInputTest.kt` has not run under Android Gradle here.

## Critical next work, blocked until POS source arrives

1. Read `README.md`, `docs/ARCHITECTURE.md`, `crates/fedpos-core/src/{scan.rs,settle.rs,store.rs,node/}` from the exact POS source archive. Re-run locked Rust tests, clippy/fmt, dependency and TLS/Node security review, then compile `scripts/build-android.sh` and Android Gradle. Confirm the guide's described 92 tests against the actual code.
2. Test merchant view-only pairing and account-1 subaddress derivation on **stagenet**, from account and seed vectors. Do not put private spend keys or seed phrases on the terminal. Require device-authenticated, deliberately initiated view-key export and show a privacy warning: a view key reveals incoming-wallet history. Pairing QR should be versioned and network-checked; don't ship a generic seed exporter.
3. Extend the wallet's Rust scanner with encrypted account-1 high-water/restore metadata, 200-index lookahead, bounded growth and explicit account-1 balance/history, including restore/reorg/restart/import and spend-key-image tests. Registering only 200 initial addresses without a growth path is insufficient. Avoid scanning an unbounded account space or silently merging unverified account-1 funds into displayed account-0 balances.
4. For merchant settlement, match exact destination output commitments and amounts using independently verified chain data; handle missed pool transactions, reorgs, under/overpayments, time-locks, double spends, malformed node responses and fee/price staleness. Do not use a sender's receipt or payment proof alone as merchant settlement.
5. Default to **at least one confirmation** for normal sales, ideally more for higher-value transactions. Treat `0-conf` as risky even when observed on a personally controlled node; a node can be partitioned, and unconfirmed transactions can disappear. Never label 0-conf as irreversible or final.
6. A reliable full-screen kiosk on Android requires managed/device-owner lock-task configuration; UI flags alone do not prevent a user from escaping the app. Review staff PIN rate limits and key protection and do not overpromise kiosk isolation.
7. Compare on-device scan benchmarks at equal restore height and node: connect latency, blocks per second, peak memory, reorg recovery, DNS/Tor startup and power; avoid disabling signature verification, lock screens or encrypted checkpoint persistence in the name of speed.

## Wallet security findings confirmed by source

- Existing `android:allowBackup="false"`, `usesCleartextTraffic="false"`, dataSync foreground-service restrictions and `isDebuggable=false` are present. Native direct Tor routing and node-genesis matching already exist. These are defenses, not a security audit.
- `WalletEngine::scanner()` registers only account 0, subaddresses 1..1,000. Its scan path checks sequential heights, chain linkage, output index and saves durable checkpoint windows; it already caches the scanner and indexes unspent key images once per window, both relevant performance improvements.
- The pre-existing `crypto-bigint 0.5.5` (RUSTSEC-2026-0387) and `rsa 0.9.10` (RUSTSEC-2023-0071) advisories remained unresolved as of the 0.11.10 validation; this candidate does not change dependencies or claim to resolve them.
- This source candidate cannot verify the supplied v0.11.10 fingerprint fix on a real Pixel. Perform physical biometric, camera, NDEF/HCE, screen-off and node-connection tests before using real funds.

## Build and validation commands

```sh
bash scripts/check-pos-parser.sh             # portable parser test, executed in this review
bash scripts/check.sh                        # requires the pinned Rust toolchain
bash scripts/build-bindings.sh               # requires Rust tools
bash scripts/build-android-native.sh         # requires Android NDK
bash android/gradlew -p android :app:assembleDebug :app:testDebugUnitTest :app:lintDebug -PunsignedPreview=true
```

Never generate a new signer as a silent replacement for the existing Preview 2 signing identity. Android source still refers to the backed-up identity; no signing material was included in the source archive or this candidate.
