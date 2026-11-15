---
name: monero-rust
description: How to read, change and test the Rust Monero code in this wallet (wallet-engine, wallet-keys, wallet-policy, wallet-ffi and the pinned monero-oxide crates monero-wallet, monero-daemon-rpc, monero-interface). Use this whenever a task touches scanning, decoys, rings, transaction building or signing, key images, seeds, node RPC, the UniFFI boundary, or an upstream Monero crate version, and whenever you need to know what an upstream function really does. Use it even for small edits, because the upstream crates have non-obvious contracts that are easy to break.
---

# Monero Rust in this wallet

`AGENTS.md` at the repository root holds the binding project rules. Read it first; this skill is the how-to that sits beside it. If the two ever disagree, `AGENTS.md` wins.

## The shape of the code

| Crate | Job | Network? |
| --- | --- | --- |
| `crates/wallet-policy` | Exact amounts, endpoint and session rules | No |
| `crates/wallet-keys` | Seed formats, key derivation, wallet modes | No. Must never gain a socket dependency |
| `crates/wallet-engine` | Scanning, spent detection, decoys, signing, byte-level verification, encrypted local state, node transport (Direct or Tor) | Yes, only through an explicit transport |
| `crates/wallet-ffi` | UniFFI boundary to Kotlin; owns lifetimes and cancellation | Wraps the engine |
| `vendor/seed`, `vendor/polyseed`, `vendor/ur` | Vendored, lightly adapted upstream code. `vendor/UPSTREAM.md` lists every local change | No |

Protocol work (decoy selection, CLSAG, Bulletproofs, transaction layout) is done by the upstream monero-oxide crates. The wallet's own code is a narrow adapter around them. Keep it that way: when upstream behaviour is unsafe for this wallet, constrain it at the adapter and fail closed. Do not re-implement or fork cryptography, because a private variant would have no reviewers and could silently diverge from the network's rules.

## Read upstream source, don't guess

The upstream crates are pinned with exact versions (`=0.2.0` style) in `crates/wallet-engine/Cargo.toml`. Their source is on disk once fetched:

```bash
cargo fetch --locked
cargo metadata --locked --format-version 1 | python3 -I -c '
import json, os, sys
for p in json.load(sys.stdin)["packages"]:
    if p["name"].startswith("monero-") and p["source"]:
        print(p["name"], p["version"], os.path.dirname(p["manifest_path"]))'
```

Before relying on an upstream function, open it at the pinned version and read its doc comment and body. Several contracts below were only discoverable that way. Published versions can be listed from `https://index.crates.io/mo/ne/monero-wallet` (one JSON line per version) if the network allows it.

## Contracts that are easy to break

**Decoy providers.** `monero_wallet::interface` has two traits. `ProvidesUnvalidatedDecoys` is what a node client implements; a blanket impl turns it into `ProvidesDecoys` and adds checks (reply length equals request length, counts never decrease). The wallet's `CachedDecoys` adapter in `engine/decoys.rs` implements `ProvidesDecoys` directly and forwards output requests to the node client, so those checks still run. If you add another provider, implement the unvalidated trait unless you have a reason not to, otherwise you lose upstream's validation without noticing.

**Output counts.** `ringct_output_distribution` returns cumulative RingCT output counts, one per block. The vector starts at the first block that has RingCT outputs, not at genesis, so a position in the vector is not a block height. Upstream selection only uses positions relative to the end.

**Node counts are checked before use.** `distribution_for_payment` hands counts to selection only after `check_counts` agrees with the wallet's own checkpoints and owned outputs, and `CachedDecoys` refuses a reply with more than two unusable candidates per input. Build one `CachedDecoys` per input, because that limit is per ring.

**Selecting decoys.** `OutputWithDecoys::new(rng, provider, 16, block_number, output)` picks a ring for one input. `block_number` is the last block the wallet has scanned. The real output is sent to the node inside the first request so the node cannot single it out; any later request omits it. Run the `decoy-audit` skill after touching anything on this path.

**The "outgoing view key" is an RNG seed.** `SignableTransaction::new` takes 32 bytes it calls `outgoing_view_key`. Upstream hashes it with the inputs and uses the result to seed a deterministic ChaCha20 generator for the transaction keys, the output shuffle and the range-proof randomness. So it must be 32 fresh bytes from `OsRng` for every preparation, zeroized afterwards, and never stored, logged, reused or derived from anything guessable. Reusing it for two different transactions that spend the same inputs can repeat secret values. `engine/payment.rs` does this correctly today; keep it that way. The one thing kept from it is the transaction key (`payment.rs::transaction_key`), stored in the encrypted journal only after it reproduces the public key in the signed transaction; the seed itself is never persisted.

**Signing randomness.** `sign(&mut OsRng, spend_key)` uses the caller's generator for the CLSAG nonces. Always pass `OsRng`.

**Verify the bytes.** After signing, `verify.rs::verify_transaction` re-parses the serialized transaction and checks it against the private construction data (`Eventuality`), the expected key images, fee, ring size, signatures, range proofs and commitment balance. Nothing is stored or broadcast before that passes. A summary supplied by a caller is never enough.

**Two point types.** `monero_wallet::ed25519::{Point, Scalar}` wrap `curve25519_dalek` types; conversions are explicit `.into()` calls. Secret scalars live in `Zeroizing`.

**Errors are closed categories.** Map failures to an `EngineError` variant. Never put node addresses, recipients, amounts, keys or transaction bytes into an error string or log line.

**Arithmetic.** Release builds keep overflow checks on. Use `checked_*` and return an error rather than letting a hostile value panic the process.

## Changing code

1. Make the change in the smallest crate that can own it.
2. Add or extend a test that needs no node where possible. Two patterns exist:
   - a mock `ProvidesDecoys` (see `engine/payment_tests.rs`) for selection and signing;
   - a fake HTTP transport that speaks the real binary RPC format (see `CountNode` in `engine/decoy_tests.rs`) for anything that parses node replies.
3. Run `scripts/check.sh` (format check, all workspace tests, clippy with warnings as errors).
4. If the FFI surface changed, regenerate bindings with `scripts/build-bindings.sh` and compile the Android source.
5. The end-to-end test against a real local node is `scripts/test-regtest.py --monerod <path to monerod>`. It is ignored by default because it needs that binary.
6. Report host compilation, device tests and hardware tests separately. Do not let a passing host build stand in for a device test.

## Changing an upstream version

Keep the pin exact. Read the upstream diff for the decoy, send and RPC code, re-run the `decoy-audit` skill and compare its results with the previous baseline, run `scripts/check.sh`, and update the reviewed-snapshot note in `docs/SYNC_SPEND.md` and the advisory notes. Unresolved upstream advisories stay visible in the validation and security notes until they are really fixed.
