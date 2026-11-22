# Glossary

One shared language for the owner, contributors and the code. Each term has one meaning here. When code, screens or docs use a different word for the same thing, either rename it or note the alias under the term.

Each entry gives: **Context** (which part of the project it belongs to), **Means** (plain words), **In code** (where it lives, or "not in code"), and **Not to be confused with** where a near-synonym exists.

How to change it: edit an entry when its meaning changes, and say so in the commit message. Add a term when two people (or a person and the code) used one word two ways. The `long-term-structure` skill adds terms as plans are discussed.

Sections: [Watch](#watch) · [Rust](#rust) · [Libraries](#libraries) · [Monero and monero-oxide](#monero-and-monero-oxide) · [Android](#android) · [Security](#security) · [Speed](#speed) · [Decision making](#decision-making) · [Critical thinking](#critical-thinking) · [Nostr](#nostr) · [Server design](#server-design) · [Decentralized](#decentralized) · [Memory](#memory)

---

## Watch

### PineTime / InfiniTime
**Context:** Watch
**Means:** the **PineTime** is Pine64's open-source smartwatch; **InfiniTime** is the open-source software it usually runs. It talks to the phone over Bluetooth.
**In code:** not yet in code; work happens on branch `claude/pinetime-watch`.

### Watch alert
**Context:** Watch, security
**Means:** a short message the watch shows when a payment is received or confirmed. It is the only thing the wallet sends to a watch (owner decision): no keys, addresses, balances, node details or payment approval.
**Not to be confused with:** a payment approval, which happens only on the phone with PIN or biometrics.

---

## Rust

### Rust core
**Context:** Architecture
**Means:** the part of the wallet written in Rust. It holds the keys, scans the chain, builds and checks payments, and decides what is valid. The Android app asks it to do things and shows the results.
**In code:** `crates/` (`wallet-keys`, `wallet-policy`, `wallet-engine`, `wallet-ffi`).
**Not to be confused with:** the Android app, which displays and collects input but should not make wallet decisions.

### Crate
**Context:** Rust
**Means:** one Rust package: a unit that is compiled, versioned and depended on as a whole. A crate boundary is the strongest separation Rust offers cheaply.
**In code:** each folder in `crates/` and `vendor/`; listed in the root `Cargo.toml`.
**Not to be confused with:** a module, which is a section inside a crate.

### Workspace
**Context:** Rust
**Means:** all the project's crates built together with one shared lock file and one version number.
**In code:** root `Cargo.toml` (`[workspace]`) and `Cargo.lock`.

### Newtype
**Context:** Rust, domain design
**Means:** a small type that wraps a plain value so it can only be created after it has been checked, and cannot be mixed up with other values of the same raw kind.
**In code:** `NodeEndpoint(Url)` in `wallet-policy`. Amounts are still plain `u64` atomic units: a candidate for a newtype.

### Enum (state)
**Context:** Rust, domain design
**Means:** a type with a fixed list of possible cases, such as a wallet mode. Using one instead of several true/false flags makes impossible combinations impossible to write.
**In code:** `Mode` (`Software`, `ViewOnly`, `Keystone`), `SeedFormat`, `NetworkRoute`.

### Closed error
**Context:** Rust, security
**Means:** an error drawn from a fixed list of categories, each with a fixed message. It never carries text from a node, an address or a key, so errors cannot leak data.
**In code:** `EngineError` in `wallet-engine/src/lib.rs`; mapped to `OnlineError` at the app boundary.

### Unsafe code
**Context:** Rust, security
**Means:** Rust code that switches off some of the compiler's memory-safety checks. The project's own crates contain none; the only use is in code generated for the Rust–Kotlin bridge.
**In code:** none first-party; `wallet-ffi` notes the generated boundary.

### Async
**Context:** Rust, speed
**Means:** code that can wait for the network without blocking a whole thread. The Rust core uses the Tokio runtime for node requests and Tor.
**In code:** `tokio`, `async fn` throughout `wallet-engine`.

---

## Libraries

### Dependency
**Context:** Libraries
**Means:** code written by someone else that the wallet includes and runs. Every dependency is code that must be trusted.
**In code:** `Cargo.toml` files (Rust), `android/app/build.gradle.kts` (Android).

### Pinned version
**Context:** Libraries, security
**Means:** a dependency fixed to one exact version, so an update never arrives without a deliberate change and review.
**In code:** `monero-wallet = "=0.2.0"` and similar in `crates/wallet-engine/Cargo.toml`; exact versions of everything in `Cargo.lock`.

### Upstream
**Context:** Libraries
**Means:** the original project a dependency comes from, and where fixes are made first.
**Not to be confused with:** vendored code, which is a local copy.

### Vendored code
**Context:** Libraries
**Means:** a dependency copied into this repository and lightly modified, with every change listed.
**In code:** `vendor/seed`, `vendor/polyseed`, `vendor/ur`; changes recorded in `vendor/UPSTREAM.md`.

### Security advisory
**Context:** Libraries, security
**Means:** a public notice that a dependency version has a known vulnerability, usually with a RUSTSEC or CVE number.
**In code:** tracked in `docs/DEPENDENCY_REVIEW.md` and `docs/SECURITY_0_11_6.md`; open advisories stay listed until fixed.

### UniFFI bindings
**Context:** Libraries, Android
**Means:** the generated code that lets Kotlin call the Rust core. Regenerated whenever the Rust side of the bridge changes.
**In code:** `crates/wallet-ffi` (Rust side); generated `android/app/src/main/java/app/independentwallet/bindings/` (Kotlin side, not committed); `scripts/build-bindings.sh`.

### Arti
**Context:** Libraries, security
**Means:** the Tor Project's Tor client written in Rust, built into the wallet so node traffic can go through Tor without a separate app.
**In code:** `arti-client` in `wallet-engine`; used by `NodeTransport` for the Tor route.

---

## Monero and monero-oxide

### monero-oxide
**Context:** Libraries, Monero
**Means:** the open-source Rust implementation of Monero's wallet machinery (scanning, decoy selection, signing, node communication) that the wallet reuses instead of writing its own cryptography.
**In code:** the crates `monero-wallet`, `monero-daemon-rpc`, `monero-interface`, `monero-oxide`, `monero-clsag` and others, pinned in `Cargo.lock`. `wallet-engine` is the only crate that talks to them.

### Node
**Context:** Monero, server design
**Means:** a computer running the Monero software that holds a copy of the blockchain. The wallet asks it for blocks and decoy data and gives it finished payments to broadcast. The user chooses it; there is no built-in list.
**In code:** `NodeEndpoint` (address), `NodeTransport` (connection), `NodeStore.kt` (saved nodes, up to five).
**Not to be confused with:** a server the project runs; it runs none.

### Route
**Context:** Monero, security
**Means:** how the wallet reaches the node: **Direct** (normal internet) or **Tor**. Tor never falls back to Direct.
**In code:** `NetworkRoute` in `wallet-policy`.

### Mainnet / Stagenet
**Context:** Monero
**Means:** Mainnet is the real Monero network with real money. Stagenet is a separate test network whose coins are worthless. Wallets, addresses and saved data for the two are kept apart.
**In code:** `Network` in `wallet-keys`; genesis checks in `wallet-engine/src/engine.rs`.

### Atomic unit
**Context:** Monero, payments
**Means:** the smallest amount of Monero (a piconero). 1 XMR = 1,000,000,000,000 atomic units. All arithmetic is done in atomic units to avoid rounding errors.
**In code:** `ATOMIC_UNITS`, `parse_xmr`, `format_xmr` in `wallet-policy/src/amount.rs`.

### Output
**Context:** Monero, chain view
**Means:** a single coin on the chain, created by a transaction and owned by whoever can spend it. The wallet's coins are the outputs it found while scanning.
**In code:** `OutputRecord` in `wallet-engine/src/state.rs`; `WalletOutput` from monero-oxide.
**Not to be confused with:** an input (an output being spent) or a transaction (which can contain several outputs).

### Input
**Context:** Monero, payments
**Means:** one of your outputs being spent inside a new transaction. A payment has one or more inputs (up to 16 here).

### Ring, ring member, decoy
**Context:** Monero, payments
**Means:** each input is signed together with 15 other outputs from the chain, so observers see 16 possible coins and cannot tell which one was spent. The 16 are the **ring members**; the 15 that are not yours are **decoys**.
**In code:** `OutputWithDecoys` (monero-oxide); ring size 16 in `payment.rs` and checked in `verify.rs`.

### Decoy counts
**Context:** Monero, payments
**Means:** the per-block count of outputs on the chain that the node supplies, used to choose decoys at realistic ages. The wallet treats them as untrusted and checks them against its own scan.
**In code:** `DistributionCache`, `check_counts` in `wallet-engine/src/engine/decoys.rs`. Called the "RingCT output distribution" in monero-oxide.

### Key image
**Context:** Monero, chain view
**Means:** a unique tag produced when an output is spent. The same output always produces the same key image, which is how the network stops double spends and how the wallet spots its own spent coins without telling the node which coins are its own.
**In code:** `image` field of `OutputRecord`; spent detection in `engine/sync.rs`.

### Spend key / view key
**Context:** Monero, security
**Means:** the private **spend key** can move funds. The private **view key** can only see incoming payments. A view-only wallet has the view key but not the spend key.
**In code:** `OfflineWallet` in `wallet-keys`; `Mode::ViewOnly`.

### Subaddress
**Context:** Monero, receiving
**Means:** an extra receiving address belonging to the same wallet, so different payers cannot link their payments to each other. This wallet supports the primary address plus up to 1,000 subaddresses.
**In code:** `MAX_SUBADDRESSES`, `ReceivingAddressBook`.

### Balance / unlocked / reserved / received
**Context:** Monero, chain view
**Means:** **received** is everything ever received. **Balance** is unspent coins not already committed to a pending payment. **Unlocked** is the part of the balance that can be spent now (a coin needs 10 confirmations, or 60 for mining rewards). **Reserved** coins are inputs of a payment waiting to confirm.
**In code:** `received_atomic`, `balance_atomic`, `unlocked_atomic` in `SyncStatus` (`engine.rs`); unlock rule in `unlock_height`.

### Transaction ID / transaction key
**Context:** Monero, payments
**Means:** the **transaction ID** is the public name of a transaction on the chain. The **transaction key** is a secret value from a payment you sent; with the ID and the recipient's address it proves what you paid. It cannot spend funds.
**In code:** `PendingPayment.transaction` and `tx_key` in `state.rs`; shown in `PaymentDetails.kt` after authentication.

### Review, preparation, submission, confirmation
**Context:** Payments
**Means:** the stages of a payment. **Preparation** builds and checks it on the phone. The **review** is what the user sees before authorizing. **Submission** sends the exact signed bytes to the node. **Confirmation** is when it appears in a block.
**In code:** `prepare_payment`, `PaymentReview`, `confirm`, `publish_exact` in `wallet-engine`.
**Not to be confused with:** the node accepting a payment, which is not a confirmation.

### FCMP++
**Context:** Monero
**Means:** a planned Monero upgrade (full-chain membership proofs) that replaces 16-member rings with proofs hiding each spend among all outputs on the chain, making decoy selection obsolete. Not yet on mainnet.
**In code:** not in code; arrives through monero-oxide updates.

---

## Android

### Activity
**Context:** Android
**Means:** one full-screen window of the app that Android starts, pauses and stops. VoidKey has one.
**In code:** `MainActivity.kt`.

### Compose
**Context:** Android
**Means:** the toolkit the app uses to draw its screens from its current state. When the state changes, the affected part of the screen is redrawn.
**In code:** every `@Composable` function, for example `WalletDashboard.kt`.

### View model
**Context:** Android
**Means:** the object that holds a screen's state and handles user actions, and survives screen rotation.
**In code:** `OfflineWalletViewModel.kt` (the main one, and the largest file in the project), `WalletViewModel.kt`.

### Bottom sheet / dialog
**Context:** Android
**Means:** a **bottom sheet** slides up from the bottom (Send, Review payment, Payment details). A **dialog** is a box in the middle of the screen (the PIN prompt). Each is a separate window on top of the app.
**In code:** `ModalBottomSheet`, `AlertDialog`.

### Android Keystore
**Context:** Android, security
**Means:** the phone's protected key storage, often backed by secure hardware. The wallet's encryption keys live there and can be tied to the screen lock or a biometric.
**In code:** `WalletVault.kt` (`KeyGenParameterSpec`, AES-GCM).

### Biometric prompt
**Context:** Android, security
**Means:** the system fingerprint or face dialog. The wallet requires a strong biometric and ties each use to a specific key operation, so a faked "success" cannot unlock anything.
**In code:** `launchBiometric` in `MainActivity.kt` (`BiometricPrompt` with `CryptoObject`).

### Foreground service
**Context:** Android, speed
**Means:** background work Android allows to keep running because it shows a visible notification. Used only for opted-in background sync, with a Stop and lock button.
**In code:** `WalletSyncService.kt`, `BackgroundSyncPolicy.kt`.

### APK, signing key, version code
**Context:** Android, security
**Means:** the **APK** is the installable app file. It must be signed; phones only install an update signed with the **same signing key**. The **version code** is a number that must increase with every release.
**In code:** `android/app/build.gradle.kts`; signing history in `PREVIEW2_SIGNING.md`.

### Unsigned build
**Context:** Android, release
**Means:** an APK built by CI without any key. It cannot be installed until the owner signs it, so the signing key never touches CI.
**In code:** `.github/workflows/build-unsigned-release.yml`, `scripts/build-unsigned-release.sh`.

---

## Security

### Threat model
**Context:** Security
**Means:** a written answer to "who might attack this, what do they want, what can they do?". Every security decision should name which attacker it stops.

### Trust boundary
**Context:** Security
**Means:** a line where data passes from a less trusted party to a more trusted one and must be checked. In this wallet: user ↔ app, app ↔ node, Kotlin ↔ Rust, app ↔ disk, app ↔ other apps on the phone.

### Passive node / active node
**Context:** Security, Monero
**Means:** a **passive** node is honest but logs what it sees. An **active** node deliberately sends false or tailored data. The wallet must protect privacy against both; against an active node, the goal is that the worst it can do is make a payment fail.

### Fail closed
**Context:** Security
**Means:** when something unexpected happens, stop and refuse, rather than continue with a guess.
**Not to be confused with:** fail open (carry on regardless), which this project avoids.

### Authentication
**Context:** Security
**Means:** proving it is really the owner, by app PIN, strong biometric or the phone's screen lock, immediately before a sensitive action (unlock, send, show keys).
**In code:** `VaultAction`, `beginAuthentication` / `finishAuthentication` in `OfflineWalletViewModel.kt`; `AuthenticationDialog.kt`.

### Vault
**Context:** Security
**Means:** the encrypted file on the phone holding a saved wallet's secrets, unlocked only after authentication.
**In code:** `WalletVault.kt`, `VaultEnvelope.kt`.

### Network consent
**Context:** Security
**Means:** the owner's explicit permission to connect a specific wallet to a specific node by a specific route. Changing any of the three cancels it.
**In code:** remembered-connection handling in `OfflineWalletViewModel.kt`; rules in `AGENTS.md`.

### Duress PIN
**Context:** Security
**Means:** an optional second PIN that, when entered, erases this app's data on the phone. It does not affect backups or the blockchain.
**In code:** `AppLockManager.kt`, `AppWiper.kt`.

### Sensitive clipboard
**Context:** Security
**Means:** copying a secret (recovery phrase, wallet key, transaction key) in a way that asks Android to hide the preview and lets the user clear it with **Clear copy**. The app never clears it on its own.
**In code:** `SeedClipboard.kt`, `AndroidSeedClipboard.kt`.

### Metadata leak
**Context:** Security, privacy
**Means:** revealing who, when or what without revealing content, for example a website seeing your IP address next to your transaction ID.

---

## Speed

### Latency
**Context:** Speed
**Means:** how long one action takes from start to result, as the user experiences it.

### Round trip
**Context:** Speed, server design
**Means:** one request to the node and its reply. Over Tor a round trip can take seconds, so the number of round trips matters more than the size of each.

### Full state write
**Context:** Speed, memory
**Means:** saving the wallet's entire encrypted scan state to disk and forcing it onto storage. Safe but slow, so the wallet batches quiet sync progress and saves immediately only when something important changes.
**In code:** `commit` and `Store::save` in `wallet-engine`.

### Engine lock
**Context:** Speed, Rust
**Means:** the lock that lets only one operation use the wallet engine at a time. Holding it during slow network work makes everything else wait.
**In code:** the `Mutex` around `WalletEngine` in `wallet-ffi/src/online.rs`.

### Batch / prefetch
**Context:** Speed
**Means:** **batching** groups many blocks into one request. **Prefetching** downloads the next batch while the current one is scanned.
**In code:** `engine/batch.rs`, `Prefetch` in `engine/sync.rs`.

### Hotspot
**Context:** Speed (of development)
**Means:** code that is both complex and changed often; where improvements pay back soonest.
**In code:** listed by `.claude/skills/long-term-structure/scripts/structure_report.sh`.

### Host-tested / device-tested
**Context:** Speed, critical thinking
**Means:** **host-tested** means tests passed on a build computer. **Device-tested** means it was run on a real phone. Host tests are fast but cannot prove phone behaviour, so the two are always reported separately.

---

## Decision making

### Three lenses
**Context:** Decision making
**Means:** every design choice is judged on **security**, then **simplicity**, then **speed**, unless the speed cost makes the feature unusable.

### Trade-off
**Context:** Decision making
**Means:** a choice where gaining on one lens costs on another. Name both sides explicitly.

### Decision record (ADR)
**Context:** Decision making
**Means:** a short document recording a decision, why it was made, what else was considered and what it costs. Written only for decisions that are hard to reverse, surprising without context and a real trade-off.
**In code:** `docs/adr/` (created with the first record).
**Not to be confused with:** a rule in `AGENTS.md`, which says *what* must hold but not *why*.

### Owner decision
**Context:** Decision making
**Means:** a choice only the project owner can make, usually because it trades privacy, cost or risk. The person recommending presents options and a recommendation; the owner decides.

### Reversible / irreversible
**Context:** Decision making
**Means:** a reversible decision can be undone cheaply, so decide quickly. An irreversible one (a signing key, a stored data format, a published transaction) needs more care.

### Decide later
**Context:** Decision making
**Means:** an explicit, recorded postponement with the event that should reopen it. Different from never deciding.

### Strategic / tactical
**Context:** Decision making
**Means:** **tactical** work makes the current change work as fast as possible. **Strategic** work also leaves the code easier to change safely next time.

---

## Critical thinking

### Claim / evidence
**Context:** Critical thinking
**Means:** a **claim** is a statement; **evidence** is what supports it (a test, a measurement, a cited source). A claim without evidence is reported as an assumption.

### Measured / read from code
**Context:** Critical thinking
**Means:** **measured** means a test or experiment produced the result. **Read from code** means it was concluded by reading. Both are useful; measured is stronger. Always say which.

### Root cause / symptom
**Context:** Critical thinking
**Means:** the **symptom** is what the user sees ("send freezes"). The **root cause** is why it happens (a screen rebuilt behind the PIN prompt). Fixes target the root cause.

### Assumption
**Context:** Critical thinking
**Means:** something believed true but not checked. Write it down, so it can be checked or revisited.

### Proof of routing (readiness is not proof)
**Context:** Critical thinking, security
**Means:** a status flag saying "ready" or "connected via Tor" is not evidence the traffic actually went that way. Evidence comes from how the code is built and tested.

### Steelman
**Context:** Critical thinking
**Means:** stating the strongest version of an opposing view before rejecting it, so the decision survives the best objection, not the weakest.

---

## Nostr

Owner decision (21 November 2026): Nostr belongs to Fed POS and stays out of the wallet. A relay would be a new party able to see wallet activity, while the wallet's privacy rests on talking only to a node the user chooses. `scripts/check.sh` fails if a Nostr dependency enters the wallet build. These entries give shared meanings for Fed POS discussions.

### Nostr
**Context:** Nostr
**Means:** an open protocol ("Notes and Other Stuff Transmitted by Relays") where users sign messages with their own key and publish them to independent servers called relays. No single company controls it.
**In code:** not in the wallet, by decision; Fed POS only.

### Relay
**Context:** Nostr, server design
**Means:** a server that stores and forwards Nostr messages. Anyone can run one; a relay can drop or log messages but cannot forge them.
**Not to be confused with:** a Monero node, or a Tor relay.

### Event
**Context:** Nostr
**Means:** one signed Nostr message (a post, a profile update, a direct message).

### npub / nsec
**Context:** Nostr, security
**Means:** a Nostr public key (**npub**, shareable) and private key (**nsec**, secret). Nostr keys are a different kind from Monero keys; a Monero seed or key must never be reused as a Nostr key.

### NIP
**Context:** Nostr
**Means:** "Nostr Implementation Possibility": a numbered specification for one feature of the protocol.

---

## Server design

### Client / server
**Context:** Server design
**Means:** a **client** asks; a **server** answers. The wallet is a client. The Monero node is the server it talks to. The project runs no server of its own.

### Backend
**Context:** Server design
**Means:** server-side software supporting an app. The Fed POS project includes one; the wallet does not.
**In code:** not in this repository; see `docs/FED_POS_INTEGRATION_0_11_11.md`.

### API / RPC
**Context:** Server design
**Means:** an **API** is the set of requests a server accepts. **RPC** (remote procedure call) is the style Monero nodes use: the wallet asks the node to run a named function and return the result.
**In code:** `monero-daemon-rpc`; bounded responses in `wallet-engine/src/transport.rs`.

### Bounded response
**Context:** Server design, security
**Means:** a hard limit on how much data the wallet will accept from a server for one request, so a hostile server cannot exhaust memory.
**In code:** response size limits in `NodeTransport`.

### Idempotent
**Context:** Server design
**Means:** an action that is safe to repeat: doing it twice has the same effect as once. Retrying a lost payment submission is idempotent because the wallet resends the exact same signed bytes.
**In code:** `retry_pending` / `publish_exact` in `wallet-engine`.

### Stateless / stateful
**Context:** Server design
**Means:** a **stateless** server remembers nothing between requests; a **stateful** one keeps data about each client. The less a server remembers about you, the less it can leak.

---

## Decentralized

### Decentralized
**Context:** Decentralized
**Means:** run by many independent parties with no single owner who can switch it off or change the rules alone. Monero, Tor and Nostr are decentralized.
**Not to be confused with:** distributed, which only means spread over several machines (a single company can run a distributed system).

### Peer-to-peer
**Context:** Decentralized
**Means:** participants connect to each other directly instead of through a central server. Monero nodes form a peer-to-peer network; the wallet itself does not join it.

### Own node / remote node
**Context:** Decentralized, security
**Means:** an **own node** is one you run, so you trust it. A **remote node** is someone else's; it can see your requests and may lie. Your own node, ideally reached over Tor, is the strongest privacy setup.

### Self-custody
**Context:** Decentralized, security
**Means:** you alone hold the keys to your funds; no company can freeze or move them. VoidKey is self-custodial.

### Trust minimization
**Context:** Decentralized, security
**Means:** designing so that as little as possible depends on any one party being honest, for example checking a node's decoy counts against the wallet's own scan instead of believing them.

### Censorship resistance
**Context:** Decentralized
**Means:** no single party can stop a valid payment or message from being published.

---

## Memory

### RAM / storage
**Context:** Memory
**Means:** **RAM** is short-term working memory, cleared when the app's process ends. **Storage** is the phone's long-term disk. Secrets in storage are always encrypted.

### Zeroize
**Context:** Memory, security
**Means:** overwriting a secret in RAM with zeros as soon as it is no longer needed, so it cannot be read later from memory.
**In code:** `Zeroizing` and `zeroize` across the Rust crates.
**Not to be confused with:** a guarantee that no copy exists anywhere; Kotlin strings, screen state and library internals can keep temporary copies (`docs/ARCHITECTURE.md`).

### Memory safety
**Context:** Memory, security, Rust
**Means:** code cannot read or write memory it shouldn't, which rules out a large class of security bugs. Rust provides it by default; C and C++ do not.

### Process death
**Context:** Memory, Android
**Means:** Android killing the app's process, which erases everything in RAM. The wallet never reopens keys automatically afterwards; the owner must unlock again.

### Memory bound
**Context:** Memory, speed
**Means:** a fixed upper limit on how much memory an operation may use, so large or hostile data cannot crash the app.
**In code:** page and count limits in `engine/decoys.rs` (`MAX_COUNTS`), bounded RPC responses in `transport.rs`.
