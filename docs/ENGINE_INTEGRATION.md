# Engine integration contract — updated for 0.9.0

The adopted initial engine is Rust monero-oxide: published monero-wallet 0.2.0 and monero-daemon-rpc 0.2.0 behind the wallet-engine adapter. The earlier wallet2/Molly SDK candidate was not adopted. This choice keeps local protocol scanning/signing and a custom Arti transport in the Rust core without exposing keys to a separate wallet RPC process.

See [SYNC_SPEND.md](SYNC_SPEND.md) for the implemented data flow, exact verification steps, privacy boundaries and current exclusions. Source dependency versions/checksums are pinned in Cargo.lock. The reviewed upstream snapshot is 731657ae3385be667abb556266369a497bc86f13.

## Required boundaries

- Offline key setup never starts network clients.
- The selected node and Direct/Tor route are immutable within an online session. Node/route changes and lock cancel the session and invalidate pending UI work.
- Production daemon requests use the explicitly chosen direct socket or actual Tor stream. Direct supports normal HTTP/HTTPS and system DNS. Tor uses remote destination DNS, permits onion/HTTPS endpoints and never falls back to direct; policy flags cannot substitute for actual routing.
- View-only/hardware modes never acquire a local spend key. View-only spent coverage remains explicit.
- Actual signed bytes, cryptographic validity and immutable local payment intent are verified before broadcast.
- Durable reservations precede broadcast. An uncertain reply never grants permission to prepare a replacement payment.
- Cache corruption or ambiguous storage writes stop the session and preserve the file for recovery.
- Bounded parsing, atomic checkpoints, output deduplication and reorg rollback apply to received and spent state together.
- No seed, private key, full transaction or node credential logging.

## Remaining work

Live Tor routing/failure/cancellation verification on Android, Keystore/process-death/device tests, performance benchmarks, wider historical/account coverage, incoming mempool support, safe pending-payment abandonment, verified key-image import and hardware-specific signing adapters remain distinct tasks. Software stagenet interoperability is not evidence of mainnet readiness or hardware interoperability.
