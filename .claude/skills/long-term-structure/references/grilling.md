# Grilling a plan

The goal is a shared, precise understanding of a plan before code exists, so the code that follows is the strategic version, not the first version that compiled. The interview is relentless but not hostile: it is the questions a careful security reviewer, a performance engineer and a maintainer would each ask, asked before the work instead of after.

## How to run it

1. **Read first.** Before the first question, read the relevant code, `AGENTS.md`, `docs/CONTEXT.md` and `docs/adr/` if they exist. Never ask something the repository can answer; state what you found instead ("Payments already refuse featured addresses in `prepare_payment`, so I'll assume that stays").
2. **One question at a time.** Each question names the decision, gives 2–3 concrete options, and recommends one with a sentence each on security, speed and simplicity. The person answers, picks, or says "decide later".
3. **Go down the tree.** Start with the questions whose answers change the most other answers: which bounded context owns this, what crosses a trust boundary, what is stored and where, what the failure mode is. Leave naming and UI wording for last.
4. **Pin vague answers.** "It should be secure", "make it fast", "like other wallets" are not answers. Ask for the attacker, the number (seconds, bytes, taps), or the specific wallet behaviour. If a term is used two ways, stop and settle it in the glossary before continuing.
5. **Write as you go.** When a term settles, add it to `docs/CONTEXT.md` immediately. When a decision passes the gates in `decisions.md`, write the ADR immediately. Batching to the end loses nuance and invites the person to skim.
6. **Stop** when every branch has an answer or an explicit "decide later", or when the person says so. Finish with a summary of the plan in glossary terms, the ADRs written, and the open "decide later" items.

## Questions worth asking in this wallet

Pick the ones that apply; do not ask all of them.

**Boundaries and ownership**
- Which bounded context owns this rule? If the answer is "Kotlin", why can't the Rust core own it?
- Does this add anything to the FFI surface? Can it reuse an existing record instead?
- Does any upstream Monero concept leak past `wallet-engine`?

**Security**
- What new data does this store, where, encrypted under which key, and when is it deleted?
- What does the node, the network, another app on the phone, or someone holding the unlocked phone learn or gain?
- What happens on lock, background, process death, reorg, a lying node, a lost reply?
- Does any `AGENTS.md` rule apply, and does the plan satisfy it by construction or by reviewer vigilance?

**Speed**
- How many network round trips does it add, and what happens to them over Tor?
- Does it write the full encrypted state, and how often? Does it hold the engine mutex across I/O?
- What does it do to the time from tap to result on a mid-range phone, and is that time visible as real progress?

**Simplicity**
- Could this be one deep function instead of several shallow steps the caller must sequence?
- Which existing concept does it reuse, and which new one does it add? New concepts need a glossary entry.
- What would we delete if this turned out wrong, and how hard would that be?

## Tone

Be direct and brief. Recommend; do not survey every option. Say plainly when an answer conflicts with a rule or an earlier decision, and point to it.
