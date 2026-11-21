---
name: long-term-structure
description: Strategic, long-term design for this wallet, built on domain-driven design and judged on security, speed and simplicity, with domain rules in the Rust core. Use it to grill a plan or feature idea before code is written (one question at a time, recording the domain glossary and architecture decisions in docs as answers settle); to review a module, file or the whole codebase for structural debt; when deciding where new code belongs; after a bug whose root cause was structure rather than a typo; or when someone says refactor, architecture, design, technical debt, "long term", "strategic", "domain model", "ubiquitous language", "bounded context", "ADR" or "grill me". Use it even when the request sounds tactical ("just add X") if X touches keys, payments, node data or the Rust/Kotlin boundary.
---

# Long-term structure

Tactical programming asks "what is the quickest change that makes this work?". Strategic programming asks "what change leaves the system easier to change safely next time?". John Ousterhout's *A Philosophy of Software Design* frames it as an investment: spend a steady 10–20% of effort on design, continuously, instead of a rewrite later. In a wallet, tactical shortcuts don't just slow you down; they turn into security bugs.

`AGENTS.md` holds the binding project rules and wins over anything here. This skill decides *how* to shape code within those rules.

## The three lenses

Judge every design question, option and review finding on all three, in this order:

1. **Security.** Which trust boundary does this touch (user ↔ app, app ↔ node, Kotlin ↔ Rust, process ↔ disk)? Who could abuse it, and what do they learn or gain? Does a failure fail closed?
2. **Speed.** What does it cost on a mid-range phone: CPU, battery, network round trips (over Tor, each one is seconds), disk writes with fsync, time holding the engine mutex? Speed is also *development* speed: how many files does the next change in this area touch?
3. **Simplicity.** Is this the simplest design that hides the complexity behind a small interface (a *deep* module)? Simplicity is not fewer lines; it is fewer things a reader must hold in their head at once.

When the lenses conflict, security wins, then simplicity, then speed, unless the speed cost makes the feature unusable. State the trade-off explicitly whenever one wins over another; that is a decision worth recording.

## Where domain rules live

The Rust core owns the domain. Kotlin renders state and collects intent; it should not decide what is valid, what is spendable or what is safe.

| Bounded context | Owns | Lives in |
|---|---|---|
| Keys and seeds | Seed formats, derivation, wallet modes and their authority | `wallet-keys` |
| Policy | Amounts, endpoints, routes, session rules | `wallet-policy` |
| Chain view | Scanning, outputs, spent detection, checkpoints, reorgs | `wallet-engine` (`sync`, `state`) |
| Payments | Preparation, decoys, signing, verification, publication | `wallet-engine` (`payment`, `decoys`, `verify`) |
| Node access | Direct/Tor transport, bounded RPC | `wallet-engine` (`transport`) |
| Device security | Vaults, PIN, biometrics, duress, clipboard | Android (`AppLockManager`, vaults) |
| Presentation | Screens, prompts, progress | Android (Compose, view models) |

The upstream Monero crates are an external context; `wallet-engine` is the anti-corruption layer that keeps their concepts from leaking outward. The FFI crate is a published language between Rust and Kotlin: keep it small and stable.

Express domain rules as Rust types so invalid states cannot be built: newtypes for domain terms (an atomic amount, a validated node endpoint, a transaction ID), enums for states instead of booleans, parsing at the boundary instead of checking deep inside, and closed error categories. See `references/domain-language.md`.

## Modes

### Grill a plan (before code)

Use when someone brings a feature idea, a plan or a change that crosses a context boundary. Follow `references/grilling.md`: one question at a time, each with your recommended answer and its security/speed/simplicity reasoning. Look in the codebase before asking anything the code can answer. Write to the docs as answers settle, not at the end:

- a term the user and code must share → `docs/CONTEXT.md` (glossary, created when the first term settles);
- a decision that is hard to reverse, surprising without context and a real trade-off → `docs/adr/NNNN-title.md` (see `references/decisions.md`).

Stop when every open question has an answer or an explicit "decide later", then summarise the plan in the domain's own words.

### Review structure (any time)

1. Run `bash .claude/skills/long-term-structure/scripts/structure_report.sh`. It is read-only and offline.
2. Pick at most three areas: the top hotspots, anything the current task is about to touch, and anything a recent bug passed through.
3. For each, check the red flags below, read the code, and ask "what made the last bug in here possible?".
4. Report findings with the three lenses, a size estimate (hours, days), and which upcoming work the fix would make cheaper. Rank by that last point: structure work earns its keep when it pays off on the next change.

### Change code strategically (during work)

- **Tidy first, separately.** Make structural changes in their own commits that change no behaviour, before the behaviour change they enable (Kent Beck, *Tidy First?*). Reviewers can then check each kind of change on its own.
- **Leave it better, in proportion.** A small investment on every change: a clearer name, a type instead of a comment, an extracted function where a reader got lost. Not a rewrite.
- **Pull complexity down.** If a caller has to remember a rule, move the rule into the callee or into a type. Every remaining "remember to…" in `AGENTS.md` is a candidate.
- **Define errors out of existence** where you can: an API that cannot be called wrongly beats a check that rejects wrong calls.

## Red flags

Most of these come from Ousterhout; each has a real example in this repo.

- **One mechanism, many jobs.** The `generation` counter both cleared secrets and gave the whole dashboard its Compose identity, so every PIN prompt rebuilt the payment sheet over the prompt (fixed in 0.11.13). When a value means two things, split it.
- **God object.** `OfflineWalletViewModel.kt`: about 1,450 lines, 55 mutable fields, 99 functions spanning vaults, setup, security, network, sync and payments. Bugs in one concern ride on another's state. Splitting it along the bounded contexts above is the largest structural investment available.
- **Shallow module.** An interface nearly as complex as its implementation, or a layer that only passes calls through.
- **Information leakage.** The same knowledge (a format, a limit, an ordering rule) encoded in two places that must change together.
- **Temporal decomposition.** Code split by "what happens first" rather than by what knowledge it hides.
- **Rules instead of structure.** `AGENTS.md` has 25 rules and about 1,300 words, many saying "never do X". Each rule a reviewer must remember is a design the code does not yet enforce, and none of them records *why*. Backfill decision records for the ones that keep causing work.
- **Hard to name or describe.** If a function or type cannot be described in one plain sentence, its boundary is probably wrong.
- **Silent no-ops.** A guard that returns without telling the user (`onlineWork` returns early when busy) looks like a freeze from the outside.

## Learning loop

- After every bug: write two lines on what about the structure allowed it, and add a red flag here if it is a new kind.
- When a hotspot is investigated, record the finding even if nothing is changed yet; next time starts from it.
- Prefer a test that pins the domain rule (property tests, mutation testing) over a comment that states it.

`references/reading.md` lists what to read for which problem; `references/tools.md` lists the tools that measure and enforce structure here, with how to run them.
