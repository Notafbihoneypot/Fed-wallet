# Domain language

Domain-driven design starts with a *ubiquitous language*: one meaning per term, used the same way by the owner, in the code, in the UI and in the docs. A wallet has many near-synonyms that hide real differences (output, coin, input, ring member; balance, unlocked, spendable). When a term is fuzzy, bugs live in the gap.

## The glossary file

`docs/CONTEXT.md`, created when the first term settles. One entry per term:

```markdown
### Term
**Context:** Payments
**Means:** one sentence a non-developer could follow.
**In code:** `TypeName` in `crate::module` (or "not yet a type")
**Not to be confused with:** the near-synonym, and how it differs.
```

Rules:
- Use the glossary's words in code, UI text and docs. If code uses a different word, either rename the code or record the alias under the entry.
- One term, one meaning per bounded context. If a word means different things in two contexts, give each its own entry with its context, and keep the translation at the context boundary.
- No synonyms in UI text: if the glossary says "payment", the screen does not also say "transfer".
- If the repository grows a second, independent area with its own vocabulary, split into one `CONTEXT.md` per area and add a `docs/CONTEXT-MAP.md` that names each context and how they talk to each other.

## Terms likely to need entries

Settle these when work next touches them; check the code for current usage first.

- **Output / coin / input / ring member / decoy.** An output the wallet owns becomes an input when spent; the input's ring holds it plus decoys.
- **Balance / unlocked / spendable / reserved.** `status()` reports several of these; the UI should name each consistently.
- **Review / preparation / payment / submission / confirmation.** The stages from "Review payment" to "in a block".
- **Node / endpoint / route / session.** A saved endpoint plus a route makes a session; consent is per wallet, node and route.
- **Lock / background / pause.** These have different effects on keys, network and consent.

## Making the language real in Rust

The strongest glossary is one the compiler enforces.

- **Domain primitives.** Wrap raw strings and integers in newtypes that can only be built through a validating constructor: an atomic XMR amount, a transaction ID, a validated endpoint, a subaddress index. Once built, they are valid everywhere. (*Secure by Design* calls these domain primitives; Alexis King's "Parse, don't validate" is the same idea.)
- **States as types.** Prefer an enum with one variant per state over several booleans that could combine into impossible states. Typestate (a type per stage, consuming transitions) suits one-way flows such as prepared → authorized → published.
- **Errors as a closed set.** `EngineError` is already a closed enum; keep it that way, and name variants in glossary terms.
- **Context boundaries as crates.** A crate boundary is the cheapest enforced boundary Rust has. When a module in `wallet-engine` starts needing a public interface of its own, consider whether it is a separate context.
- **Kotlin mirrors, never decides.** UniFFI records carry Rust's decisions to the UI. If Kotlin needs a rule, add it to Rust and expose the result.
