# Send review, Tor preparation and saved nodes — 0.11.4

Date: 27 October 2026 (America/New_York). Android code 21.

Install over Independent Sync Preview without uninstalling or clearing data. The package and signing identity are retained. Existing wallets, setup, scan checkpoints, pending transactions and reconnect/background preferences remain readable. This update does not need a new wallet or rescan.

## Sending

Open Send, enter the recipient on this wallet's network and either enter XMR or tap Max. Max uses every positive, mature, unspent and unreserved input within the existing 16-input limit. It deducts the exact calculated fee from their sum. It does not subtract a guessed fee or use floating point. A wallet requiring more than 16 eligible inputs gets an explicit error; Max never silently sends only part of its unlocked funds. The 0.05 XMR fee cap remains.

The native upstream builder first probes the same input/output shape without signing, then constructs the final Max amount. It retains normal change (including zero-valued change), ring size 16 and the upstream normal randomized decoy policy. Only the final transaction is signed. The final amount plus fee must equal the selected sum, and the exact serialized signed transaction still undergoes the existing payment-intent, key-image, CLSAG, range-proof and commitment checks. A zero-valued owned change output does not display a misleading next-unlock countdown.

The review shows recipient amount, fee, total cost in XMR, network and route before device authorization. Preparation syncs to the latest selected-node tip. A changed tip or expired review during authorization triggers a fresh quote for the same intent (including recalculating Max) and returns to review. The user must authorize again. The app never approves the replacement automatically. Invalid amount/address/funds errors no longer masquerade as a network failure or discard the last known sync state.

After submission, payment details show recipient, amount, fee, total cost, transaction ID, mined height and scanned confirmation count. The same details are available for pending payments and sent activity. Node acceptance, a lost submission reply and a block confirmation have distinct labels. Confirmation data is marked as last-scanned when disconnected or stale. Returning after process death has no transient acknowledgment, so a pending journal entry does not falsely claim a successful node reply.

Signed bytes and reservations still commit before broadcast. Unknown replies retain their exact transaction and reserved inputs. An explicit authenticated retry syncs first and only publishes the same journal bytes; it does not construct a replacement payment. Permanent-rejection abandonment and incoming mempool scanning remain outside this preview. Outgoing metadata comes from the local encrypted journal and cannot all be recovered from the public chain with a seed alone.

## Tor preparation

The pinned upstream sampler requests the full cumulative RingCT output distribution for each input. The daemon adapter uses an uncompressed uint64 array, making repeated Mainnet requests large. The new session adapter fetches it once and reuses the public counts for each input and subsequent review at that same chain snapshot. When the tip advances, it validates the previous block hash and fetches an overlapping increment, checks its length/cumulative boundary and appends the new counts. Reorg, rescan, new transport, disconnection and a new session invalidate it. It is bounded to the existing 32 MiB response budget and is not persisted.

Only public cumulative counts and a validated height/hash are cached. Actual ring/output lookups remain delegated to the same validated RPC; no ring, deterministic decoy selection, real-output-only request, remote scanner or alternate node is introduced. The first lookup still transfers the full distribution. Tor/node speed and subsequent block inclusion are outside this optimization. No Android/live-Tor speed parity is claimed.

The send sheet shows the actual operation phase and elapsed time, with indeterminate progress while the duration is unknown. Stop preparation and disconnect is available during construction. A pending submission is never labeled mined merely because a timer completes.

## Five saved user nodes

Node settings hold at most five named endpoint/route entries across Direct and Tor. Add, edit, choose or remove entries; no node is shipped by default. Choosing another endpoint/route disconnects and revokes remembered reconnect consent. Saving/selecting never starts a connection. Removing the active node leaves no selected endpoint; another saved node is not chosen automatically. Renaming the same canonical endpoint/route preserves its session and grant. Unsaved drafts block new connections to an older endpoint.

The last explicitly chosen Direct and Tor endpoints remain available for route switching. An empty route never falls back. Existing user-entered endpoints appear in the list after upgrade and remain intact when new entries are added. The same URL can be saved once per route, because the privacy/consent boundaries differ; duplicates within a route are rejected. Labels are bounded to 48 characters and do not appear in logs.

IWN4 extends only the plaintext payload inside the existing Android Keystore AES-GCM, no-backup, atomic node file. It includes a bounded list and selected endpoint identity alongside prior consent records. Legacy single-URL/IWN2/IWN3 records remain readable. Malformed lengths, duplicate endpoints, invalid selection references, unsupported routes and oversized files fail closed. Endpoint normalization/route validation still uses the Rust parser. Wallet/setup/cache/journal encryption formats and Android permissions do not change.

## References and validation

- Cake Wallet's documented Max/review/history interaction: https://docs.cakewallet.com/features/basic/send-funds
- Exact pinned construction and decoy APIs: https://docs.rs/monero-wallet/0.2.0/monero_wallet/
- Exact pinned distribution adapter: https://docs.rs/monero-daemon-rpc/0.2.0/monero_daemon_rpc/

The app's interface and implementation remain independent. See VALIDATION.md for executed host/isolated-node checks, Android compilation and the limits of on-device/Tor verification.
