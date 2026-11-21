# Randomness map

Reviewed 15 November 2026 at wallet 0.11.11 plus the unreleased transaction-key change, with `monero-wallet 0.2.0`, `monero-clsag 0.1.0`, `monero-bulletproofs 0.1.0`, `monero-ed25519 0.1.0`, `rand_core 0.6.4`, `getrandom 0.2.17`. Regenerate the raw list with `scripts/rng_inventory.sh` and re-check any entry before relying on it.

This records what the code does. It is not an independent audit, and none is claimed.

## The one source of secret randomness

Every secret random value in the wallet's own code comes from `rand_core::OsRng`, which asks the operating system's generator through the `getrandom` crate. There is no application-level generator, no seed from a clock, password or device identifier, and no fallback if the operating system refuses: the call returns an error (`EntropyUnavailable` or `Entropy`) and the operation stops.

Where it is used:

| Place | What is drawn | Notes |
| --- | --- | --- |
| `wallet-keys` `OfflineWallet::generate` | Seed entropy for a new wallet | See "Polyseed bytes are zeroed" and "injectable generator" below |
| `wallet-engine` `prepare_payment` | 32-byte per-payment seed (`outgoing`) | See "deterministic generator" below |
| `wallet-engine` `prepare_payment` | `OsRng` handed to decoy selection and to `sign` | Decoy choice and signature nonces |
| `wallet-engine` `prepare_payment` | 16-byte review identifier | An opaque handle for the UI, not a key |
| `wallet-engine` `store.rs`, `store/decoys.rs` | 12-byte nonce per encrypted save | See "random nonces" below |
| `wallet-engine` `verify.rs` | Weights for batch proof verification | Verification only |
| `wallet-ffi` `offline.rs` | Which three seed words the backup check asks for | Not a secret |

## Things that look suspicious

### A deterministic generator seeded from a hash builds the transaction

**What a reader sees:** upstream `send/tx_keys.rs::seeded_rng` builds a `ChaCha20Rng` from `keccak256` of a label, a 32-byte value called `outgoing_view_key`, and the input keys and commitments. That generator, not the operating system, produces the transaction's one-time keys, the output order shuffle and the range-proof randomness (`send/tx.rs`, label `bulletproof`).

**Why it looks bad:** "transaction secrets come from a seeded PRNG" is exactly what a backdoored or weakened generator looks like. If the seed were guessable, an observer could rebuild the transaction's secrets and read its recipient and amount.

**What is true:** the generator is only as unpredictable as its seed, and this wallet's seed is 32 fresh bytes from `OsRng` drawn inside `prepare_payment` for each preparation (the variable `outgoing`). It is held in a zeroizing buffer, passed only to `SignableTransaction::new` and to the function that re-derives this payment's transaction key, and is itself never stored, logged, sent or derived from anything else. So the output is a deterministic expansion of 256 operating-system random bytes, which is a standard construction. Upstream designed it this way so that several signers can agree on the same transaction and so a sender can re-derive keys later.

**Check:** `grep -rn outgoing crates --include=*.rs` shows every use (creation, two constructor calls and the transaction-key derivation). Read `seeded_rng` in the pinned `monero-wallet` source to see everything that goes into the hash.

**Limits:**
- Safety depends on the caller. Upstream's own documentation says the value must be treated as a private key and must not be reused across different transactions that spend the same inputs. Any future change that stores, reuses or derives `outgoing` would break this and must be treated as a security change.
- Within one preparation, the fee probe for "send max" and the final transaction share the seed and the inputs, so they would derive the same keys. The probe is never signed, serialized or sent. If that ever changes, it becomes the reuse upstream warns about.
- The seed is thrown away, but one value derived from it is kept: the transaction key, the first output of the seeded generator. `payment.rs::transaction_key` re-derives it, keeps it only if it reproduces the public key inside the signed transaction, and stores it in the encrypted journal so the sender can prove the payment later. With the transaction ID and the recipient's address, that key lets someone confirm the amount sent to that address; it cannot spend, and it does not reveal the seed or any other payment's key. It is shown only after device authentication.

### Signature nonces

**What a reader sees:** `sign(&mut OsRng, ...)` and, in `monero-clsag`, `Scalar::random(rng)` for the nonce and ring responses.

**What is true:** these come straight from `OsRng`, not from the seeded generator. `Scalar::random` draws 64 bytes and reduces them, which avoids bias. A repeated or predictable CLSAG nonce would expose the spending key, so this is the single most important place for the generator to be the operating system's.

**Check:** follow `rng` from `SignableTransaction::sign` in `monero-wallet` into `Clsag::sign` in `monero-clsag`.

### `next_u64() % n`

**What a reader sees:** modulo on a random 64-bit value in upstream `decoys.rs` (picking an output inside a block, and the recent-spend window) and in `wallet-ffi` for the backup challenge.

**Why it looks bad:** modulo introduces bias, a classic mistake when generating keys.

**What is true:** none of these values are keys. The bias is at most `n / 2^64` with `n` in the thousands at most, far below anything observable, and the inputs are public chain data or a word position.

### Polyseed bytes are zeroed after being drawn

**What a reader sees:** in `wallet-keys`, 32 random bytes are drawn and then `entropy[19..].fill(0)` and `entropy[18] &= 0x3f`.

**Why it looks bad:** it looks like deliberately throwing away entropy.

**What is true:** the Polyseed format carries exactly 150 secret bits (16 words, with the rest used for a checksum, the wallet birthday and feature flags). `vendor/polyseed` defines `SECRET_BITS = 150` and rejects entropy with any other bits set. 150 bits is the format's strength, not a reduction made by this wallet. The 25-word legacy format instead draws 64 bytes and reduces them to a full scalar.

**Check:** the constants at the top of `vendor/polyseed/src/lib.rs`.

### An injectable generator in key generation

**What a reader sees:** `generate_with_rng<R: RngCore + CryptoRng>` takes any generator.

**What is true:** it is a private function. The only public entry, `generate`, passes `OsRng`. The parameter exists so tests can exercise failure paths (for example an operating system that returns no entropy). Tests live in `entropy_tests.rs` and `tests/adversarial_inputs.rs`.

**Check:** confirm the function has no `pub` and list its callers.

### Xoshiro256** in `vendor/ur`

**What a reader sees:** a non-cryptographic generator in production code.

**What is true:** it is part of the Uniform Resources animated-QR format used for Keystone. The format requires both sides to run Xoshiro256** seeded from a hash of public frame data so they agree on which fragments each frame mixes together. It never produces a secret.

**Check:** `vendor/ur/src/xoshiro.rs` and its use in `sampler.rs` and the fountain decoder.

### Random nonces for file encryption

**What a reader sees:** a random 12-byte nonce for ChaCha20-Poly1305 on every save, under a long-lived key.

**What is true:** random 96-bit nonces are safe until the number of saves under one key gets astronomically large; the chance of a repeat stays negligible for any realistic number of saves. Worth knowing as an assumption rather than a concern.

### Fixed keys and seeds in tests

**What a reader sees:** spend keys like `01` followed by zeros, and fixed generator seeds.

**What is true:** these are in test files only (`*_tests.rs`, `tests.rs`, `tests/`, `regtest.rs`), which are compiled only for `cargo test` and are not in the Android build.

### Several `rand` and `getrandom` versions in `Cargo.lock`

**What is true:** other dependencies (mostly the Tor client) pull their own versions. The wallet crates pin `rand_core 0.6.4`. Multiple versions mean a larger tree to review, not a different generator for wallet secrets.

## What this does not establish

- That the operating system's generator on a given device is sound.
- That the pinned upstream crates are free of defects. The decoy findings in the `decoy-audit` skill are examples of upstream behaviour that is documented but still unsafe against a lying node.
- That anyone independent has reviewed this wallet.
