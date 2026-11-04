# Validation — Independent Preview 2, 0.11.7

Date: 28 October 2026 (America/New_York). Android 0.11.7-preview2, code 25; Rust 0.11.7.

## Result and scope

The user reported a Tor timeout before the fee review. Code inspection found a first-payment request for the entire uncompressed cumulative output distribution, potentially tens of megabytes under one 90-second deadline, and a cache lost on wallet lock. A simulated slow-link budget reproduces failure of that old single request. The revised bounded download succeeds, matches the full expected distribution and resumes encrypted completed parts after interruption/reopen. A separate real upstream decoy-lookup timeout now preserves NET_TIMEOUT instead of becoming a generic node-data error.

No new phone log/live circuit trace was supplied. These are demonstrated code-path fixes and diagnostics; they do not establish the exact request that failed on the user's phone. First preparation can still be slow. The total initial count data and Monero confirmation times are unchanged.

The update is installable over Independent Preview 2 without uninstalling or clearing data: package `app.independentwallet.android.syncpreview2`, label Independent Preview 2, certificate SHA256 `01afd9a36efbb0d6573d8f12764164a14af3d99faa4a97ccfce45dff82cd52b5`. The same backed-up RSA-4096 identity was recovered and verified against both the previous signed installer and the new one. No key generation, rotation, seed change, wallet migration or actual user-fund transaction was performed. The original Sync Preview package remains separate because its old private signing key is unavailable.

## Implemented behavior

- Public counts are downloaded backwards from the checked tip in pages of at most 131,072 counts (1 MiB plus bounded RPC overhead), preserving the upstream activation-boundary semantics and complete distribution. Adjacent cumulative counts must agree; lengths, monotonicity, chain anchors and the hard 4,194,304-count cap are checked.
- Only public-count reads shrink/retry on timeout or size errors, down to 4,096 counts (32 KiB). The selected endpoint/route, per-request 90-second deadline, TLS verification, cancellation and response caps remain. Ring/output queries and broadcasts are never automatically replayed.
- Completed pages are encrypted, fsynced and atomically saved in a separate file, scoped to the wallet namespace plus normalized node/route and a checked chain anchor. A complete cache needs only a new tail; an interrupted cache resumes saved parts. Changed node/route/fork, corrupt or absent data cause a cache miss. Rescan clears this acceleration data. It never shares or replaces the funds/payment journal.
- The count file uses separate HKDF/AAD domains with existing ChaCha20Poly1305, the existing single-writer lease and a 33,554,537-byte maximum. It contains public counts/source identifier/snapshot metadata, never rings, per-output responses, recipients or signed bytes. Maximum-size round trip, tampering, wrong-key and journal-isolation tests passed.
- Native progress atomics report actual preparation stages and saved byte count without acquiring the engine mutex or making network requests. The Android screen adopts results only through existing live-session/epoch guards. Logs record only closed stage/route/error labels. Error text distinguishes an unsent preparation failure from an uncertain broadcast.
- A new payment preparation cannot continue in a locked/background session. The existing exception for completing an already device-authorized immutable publication is retained. Fee/total/Max review, fresh Android authorization, actual cryptographic/intent verification, input reservations fsynced before publication, and exact-byte explicit retry remain.

See [Tor send design and limits](TOR_SEND_0_11_7.md). Subaddresses, scanner performance changes, remembered consent, up to five explicitly selected user-added nodes, network separation and receiving QR binding are retained.

## Final executed validation

| Check | Result |
| --- | --- |
| Required Rust checks | `scripts/check.sh`: **105 passed, 0 failed, 1 isolated test ignored by default**; formatting and Clippy workspace/all-targets with warnings denied passed. |
| Isolated real Monero node | The final built test binary passed **1 isolated case**, 0 failures, in **139.57 seconds**, against checksum-verified Monero CLI 0.18.5.4 in offline fakechain/loopback-only mode. No public-network transfer or real funds. |
| Timeout regressions | Oversized full read rejected by the fixture; equivalent paged distribution succeeds; partial pages survive failure/reopen and extend a growing chain; smaller read budget adapts; changed source/fork and bad overlap rejected. |
| Decoy lookup and publication | Real upstream decoy timeout injected before review: NET_TIMEOUT retained, no prepared review, no payment reservation and no broadcast. Existing cryptographic tamper/intent/fee/Max/spent-input/reorg/lost-reply/exact-byte retry checks retained. |
| Nonblocking progress | Rust test reads progress while the engine mutex is held and requires a result within 1 second; disconnect revokes access. Actual host UniFFI verifies local progress reads and owner-lock revocation without Tor construction. |
| Android host tests | **139 tests, 33 suites**, 0 failures/errors/skips, through the fresh host library. |
| Android final build | Assembly, host tests, instrumentation Kotlin compilation and lint all passed; **69 tasks** reported. Bindings were regenerated from the final native host library, and both Android release ABIs were built and freshness-checked. |
| Android lint | **0 errors, 16 warnings**: GradleDependency=7, ImplicitSamInstance=5, UnusedAttribute=1, UseKtx=3. No warnings were suppressed by this change. |
| Device/instrumentation | **21 cases compiled, 0 run**. No emulator, GrapheneOS device, live Tor circuit, actual installation or Android-service lifecycle execution is claimed. |
| Build/deliverable helpers | Four Python regressions passed across native environment isolation, APK integrity and source packaging. |
| Installer identity/integrity | Apksigner verification passed, single expected certificate, APK v3 signature; previous installer signature matches. Correct package/code/name/min28/target36, Java/native debugging disabled, backup disabled. APK ZIP/DEX checksums/bounds/unique-class checks passed. |
| Native payload | Both original JNI files match Gradle merge inputs; packaged bytes match stripped outputs and a fresh NDK strip. Test-only markers excluded. 28 ELF LOAD segments meet at least 16-KiB alignment; ZIP alignment passed before/after signing. |
| Dependencies | All **592 third-party Cargo lockfile entries/checksums** match 0.11.6; only the four local wallet crate versions changed. No forced crypto upgrade or protocol-library fork. |

The isolated fixture now permits up to three generated blocks to observe real transaction inclusion, rather than assuming an accepted transaction must appear in the very next template. An earlier run failed that strict next-template assumption and another passed; the final bounded-inclusion fixture passed. This does not weaken the requirement for actual mining or the wallet's confirmation/spend rules. Host scan microbenchmarks remain synthetic and do not measure real Tor throughput.

## Installer and native hashes

Installer: `independent-wallet-v0.11.7-preview2.apk` — **55,338,808 bytes**.

SHA256: `06a0a8c568c00cb0cb3e4add96546c628bbdbb6de1fd3233179f840a9a07ad2a`.

DEX: 2 files / 15,748 classes. ABIs: arm64-v8a and x86_64.

| Native payload | Packaged bytes | SHA256 |
| --- | ---: | --- |
| arm64-v8a | 15,552,224 | `ec20685902457f975488faa4091a42e6a9d44c90a6df9b5fe1fac4b3268f6d8d` |
| x86_64 | 16,580,600 | `98fd5484cc4b3f4966811ac95007347431ec2138a246a58c52d68c754df6f0ae` |

Source packaging verifies every included file against its internal SHA256SUMS and local source, with CRC checks. Toolchains, build outputs, local wallet material and private signing credentials are excluded; the Gradle wrapper JAR remains an intentional build input. The separate checksum file covers the installer, source archive and this delivered report. Protected release-key/password backups remain separate and unchanged.

## Remaining limits and security findings

This is a targeted timeout/lifecycle/cache review, not an independent full security audit. Neither checks of an advertised block hash nor a matching cached boundary independently prove the full distribution supplied by an untrusted node. Existing upstream validation, local signing verification and trust assumptions remain. The initial download still transfers the full public distribution, and the bounded encrypted file is rewritten after successful pages; slow links/storage can still take time. Locks and errors must remain recoverable without skipping verification or changing route/node.

The two known upstream alerts from [the previous review](SECURITY_0_11_6.md) remain unresolved and are not suppressed: **crypto-bigint 0.5.5 / RUSTSEC-2026-0387**, and **rsa 0.9.10 / RUSTSEC-2023-0071**. The previous review also recorded basic-toml, bincode and paste maintenance warnings. Their dependency versions remain unchanged. The two advisory pages were rechecked on 28 October; the crypto-bigint fix remains in an incompatible newer release and rsa still lists no patched version. A new whole-database audit or Android dependency vulnerability audit was not performed for this targeted update. No exploit-free or production-security claim is made.

Primary references: [Monero RPC](https://www.getmonero.org/resources/developer-guides/daemon-rpc.html#get_output_distribution), [crypto-bigint advisory](https://rustsec.org/advisories/RUSTSEC-2026-0387.html), [rsa advisory](https://rustsec.org/advisories/RUSTSEC-2023-0071.html). The pinned protocol crate source and original review provide implementation details.

## Device follow-up

Install this update over Preview 2, unlock the existing wallet, keep Tor and the selected node, and retry **Review payment**. Observe the named stage and saved data size. A first download can remain slower than later attempts. If it fails, retain the new WalletNetwork lines containing its stage/NET code and the visible wording; these distinguish history download, fee lookup and decoy lookup. Do not recreate a payment after an uncertain authorized broadcast: the existing receipt/journal's exact-transaction retry remains the relevant path.
