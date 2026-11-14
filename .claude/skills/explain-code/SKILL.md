---
name: explain-code
description: Explain code in this wallet that someone finds suspicious, confusing or alarming, in a way a skeptical outsider can check for themselves. Use this whenever a user, reviewer, forum post or issue says the code "looks suspicious", "looks backdoored", "why is this seeded", "why isn't this random", questions the RNG or the monero-oxide deterministic generator, asks what a piece of the wallet really does, or asks for a plain-language explanation of any security-relevant code. Also use it when asked to write a public reply, FAQ entry or doc section answering such concerns.
---

# Explaining suspicious-looking code

People are right to be suspicious of wallet code, and reassurance is worth nothing to them. The only thing that helps is an explanation they can verify without trusting the author. So the job here is to find out what is true and say it, not to defend the project.

Every explanation ends in one of three verdicts, and all three are acceptable outcomes:

- **Looks odd, is sound.** Say what it does, why it looks wrong, and how to confirm it.
- **Is a real weakness.** Say so plainly, say who is affected and how badly, and point to the open finding.
- **Cannot tell yet.** Say what was checked, what was not, and what would settle it.

If you notice you are reaching for the first verdict before you have looked, stop and look.

## How to work

1. **Pin down the concern.** Quote it. Identify the exact file and function it is about. A vague worry ("the RNG is suspicious") usually hides a specific observation (for example a generator seeded from a hash); find that observation, because answering a different question will read as evasion.

2. **Gather evidence from the code as it is now.** Read the function and its callers. For anything in the upstream Monero crates, read the pinned version's source on disk (the `monero-rust` skill shows how to find it). For randomness questions, run:

   ```bash
   .claude/skills/explain-code/scripts/rng_inventory.sh
   ```

   It lists every place randomness is created, seeded or used, in this repository and in the pinned upstream crates. `references/rng.md` explains each entry as of the last review; treat that file as a starting map and re-check any entry you rely on, since code moves.

3. **Follow the value, not the name.** Names mislead. Upstream's `outgoing_view_key` is a random seed, not a view key. For any secret, trace where its bytes come from, where they go, and whether they are ever stored, logged or sent.

4. **Decide the verdict** from what you found. If it is a real weakness that is not already recorded, report it to the owner as a finding. Explaining it does not close it, and do not fix it quietly as part of writing the explanation.

5. **Write the explanation** in the format below.

## Format

Write for a reader who is technical enough to read code but may not know Monero or cryptography. Define a term the first time it appears. Lead with the verdict.

```
**Concern:** <the concern, quoted or closely paraphrased>

**Short answer:** <one or two sentences with the verdict>

**What the code does:** <plain description, with file and function names>

**Why it looks suspicious:** <the honest reason a careful reader would stop here>

**Check it yourself:** <commands or files that confirm each claim above>

**Limits:** <what this does not prove; open findings that touch it>
```

Keep "Limits" even when the verdict is "sound". There is nearly always something: no independent audit, an assumption about the operating system, a property that holds only while a caller keeps behaving. Leaving it out is what makes an explanation read like a sales pitch.

## Things to hold to

- Cite function names, and line numbers only after re-reading the file in this session.
- Do not claim an audit, review or test that did not happen. Host tests, device tests and hardware tests are separate things here; say which one you mean.
- Do not shrink open findings. The decoy findings in `.claude/skills/decoy-audit/references/issue-classes.md` and the unresolved upstream advisories in the docs are part of an honest answer when the question touches them.
- Do not describe the wallet as safe for real money on the strength of an explanation. The project rules say not to market it that way.
- Never paste real seeds, keys, node credentials or full transactions into an explanation. Test fixtures with obviously fake keys are fine if labelled as fixtures.
- "Upstream does it this way" is a reason something exists, not proof it is safe. Say what the safety depends on.
