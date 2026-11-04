# Security and performance review — 0.11.6

Scope: application changes for receiving addresses; engine state/scan/spend coordination, FFI ownership/cancellation, receive QR publication, Android installer settings and the locked Rust dependency graph. This is a targeted source/dependency review, not an independent audit. Device execution and live Tor behavior still require validation.

## Changes

- The previous receiving composable retained a remembered QR bitmap while a changed address was being encoded. A primary-to-subaddress transition, or the new selector, could briefly pair old QR data with new text. New QR frames carry their source address and are shown only for a matching current selection. Address/label/QR publication is local and bound to the same unlocked owner.
- New address indices/labels/selection commit to authenticated encrypted storage before the address is returned. Invalid indices, over-limit creation, oversize/control-bearing labels and corrupt metadata fail before a new address is exposed. Rescan and legacy-cache migration preserve address metadata/reservations. There is no plaintext address-book preference file or new exported Android component.
- The distributed APK now disables Java/Android and native debugging in its build type. Debug-build task names are retained for compatibility with existing host/instrumentation checks, but the installer manifest must not advertise `android:debuggable=true`. This reduces debugger exposure; it does not protect a compromised/rooted OS or establish production readiness. See [Android debugging risk](https://developer.android.com/privacy-and-security/risks/android-debuggable).
- Offline address work uses an owner-cancelled local engine child and the same encrypted-cache lease. It constructs no daemon/Tor client and cannot reopen a locked owner. No owner mutex is held across file I/O or the engine lock. Late UI results cannot appear in another wallet or after locking.
- Scan tables are reused per engine. Spent detection indexes unspent key images once per durable scan window, then looks up observed block images; it no longer iterates every saved output in every block. A spent image is removed from this window index after its first observation. Status computes the set of reserved inputs once, avoiding an output-by-payment nested search. Blocks, receipts, maturity, spent detection, signature/intent verification and durable outbound reservations remain checked.

## Dependency findings

The baseline locked Rust graph produced three vulnerability alerts. A compatible update changes only ctutils 0.4.3 to 0.4.4. This addresses [RUSTSEC-2026-0385](https://rustsec.org/advisories/RUSTSEC-2026-0385.html), covering mask-generation/comparison timing, on the Arti tor-proto -> polyval -> universal-hash path. No alert was ignored or suppressed. rustls was already 0.23.46, covering RUSTSEC-2026-0346 through -0349; no TLS verification downgrade was introduced.

Two vulnerability alerts remain in the final lockfile. The audit still exits nonzero; it is not a clean security result:

| Alert | Dependency and observed use | Status |
| --- | --- | --- |
| [RUSTSEC-2026-0387](https://rustsec.org/advisories/RUSTSEC-2026-0387.html) | crypto-bigint 0.5.5, required by monero-ed25519 0.1.0. Reviewed usage is the hash-to-point implementation; private scalar arithmetic uses Dalek. | Upstream patched version is >=0.7.6, outside the dependency's ^0.5.1 range and incompatible with its existing API. No compatible published monero-ed25519 update was found. No private-scalar exploit was demonstrated by this review, but public point/ownership data can still be privacy-sensitive; timing risk is not dismissed or claimed fixed. No custom cryptography/backport was invented. |
| [RUSTSEC-2023-0071](https://rustsec.org/advisories/RUSTSEC-2023-0071.html) | rsa 0.9.10 through Arti/its key-format helpers. This app configures a Tor client, not a relay/service, and no application RSA private-decryption/signing path was identified. The APK-signing key uses Java's signing tools, not this Rust dependency. | No upstream patched version is listed. The observed client configuration reduces the relevant private-operation exposure, but this is source triage, not proof of non-reachability. The alert is retained. |

The database also reports unmaintained basic-toml, bincode and paste. They remain transitive upstream maintenance risks; no replacement serializers/protocol formats were substituted in this change. Android lint/version notices are not an Android dependency vulnerability audit. There is no claim that all third-party vulnerabilities, side channels or unsafe dependency internals have been eliminated.

## Preserved boundaries and limits

The selected node/route is explicit. Direct/Tor never silently switch; there is no preset/failover/alternate-DNS/TLS bypass. RPC response/time limits and cancellation stay in place. A remote node may withhold or misrepresent data; this is not a full independently validating consensus node. No wallet-specific spent-key-image query or secret upload was added.

Seed/private-view/spend material remains in the existing Rust/Keystore lifecycle. No complete memory erasure is claimed for temporary Kotlin/FFI copies. Screen/overlay protection, authenticated unlock, disabled platform backup, same Preview 2 package/certificate and exact-transaction journal behavior remain. Existing incoming-mempool, additional time-lock, hardware-signing/key-image-import and permanent-rejection-abandonment limits remain. Labels/selection are local metadata; a seed does not back them up. Store/state size limits still apply, and address metadata saves rewrite the existing bounded encrypted cache, so unusually large wallets can have slower metadata edits.

Tests and exact artifact hashes are recorded in VALIDATION.md. Synthetic batch timings are not a production speed comparison with other wallets. No new real-money payment or live Tor send was performed during validation.
