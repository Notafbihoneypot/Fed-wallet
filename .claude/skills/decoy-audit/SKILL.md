---
name: decoy-audit
description: Audit how this wallet picks and requests ring decoys, and report what a node operator or chain observer could learn about the real spend. Use this whenever someone asks whether the wallet is "protected" against a decoy, ring, remote-node or transaction-privacy issue, shares an article or advisory about decoy selection, changes anything in engine/decoys.rs, engine/payment.rs or verify.rs, or changes the pinned monero-wallet / monero-daemon-rpc / monero-interface versions. Run it before claiming anything about spend privacy.
---

# Decoy audit

A Monero transaction hides the real input among 15 decoys. The wallet has no copy of the chain, so it asks the user's chosen node for decoy data. That makes two questions matter:

- **What does the node learn** from the wallet's requests, if it only logs them, and if it actively lies?
- **What does everyone learn** from the finished transaction on-chain?

An audit answers both with evidence. A claim that was measured and a claim that was read from the code are different strengths of evidence, so label each finding as one or the other.

## Steps

1. **Record what is being audited.** Note the pinned upstream versions (the probe prints them) and the commit. If the network allows, check whether newer versions are published (`https://index.crates.io/mo/ne/monero-wallet`).

2. **Run the probe.**

   ```bash
   bash .claude/skills/decoy-audit/scripts/run_probe.sh
   ```

   It temporarily installs `probe/decoy_probe.rs` as a test module, runs the wallet's real decoy adapter and the pinned upstream selection against a misbehaving fake node, prints one `RESULT` line per behaviour, and restores the engine file exactly as it was. `PROTECTED`, `BOUNDED` or `EXPOSED` on each line describes that behaviour only. `BOUNDED` means the node could still narrow the real spend, but only within the wallet's fixed limit.

   If it fails to compile, the engine's internals moved. Update the probe to match. Do not delete a scenario to make it build, because a silently dropped scenario reads later as "nothing found".

3. **Run the existing decoy tests.**

   ```bash
   cargo test --locked -p wallet-engine decoy
   ```

4. **Read the code for what the probe cannot measure.** `references/issue-classes.md` lists every known class, where to look, and the baseline result. Work through all of it; the probe covers only the request pattern.

5. **If the audit was prompted by an article or advisory, map each claim in it to a class** and say which are closed in the pinned version and which are not. If the source cannot be read, say so before anything else instead of auditing against a guess.

6. **Report** using the format below.

## Judging results

The baseline in `references/issue-classes.md` is what was found the last time. A result that differs from the baseline is the finding, in either direction: a regression needs explaining, and so does an improvement (confirm it is real and update the baseline in the same change).

Be precise about the attacker. "A node that logs requests", "a node that returns false data" and "anyone reading the chain" are different attackers, and a weakness against one is not a weakness against all. The standard to hold the wallet to is that a misbehaving node can at worst make a payment fail. Anything that lets it learn or narrow the real spend while the wallet carries on without an error falls short of that standard and is reportable, even though upstream documents active nodes as out of scope.

An audit reports. Do not change the payment path as part of it unless the owner asked for a fix. Some fixes also collide with project rules, for example `AGENTS.md` forbids caching or replaying ring requests, which rules out reusing a ring across retries. Those need the owner's decision, so present them as decisions.

## Report format

Lead with the answer to the question that was asked, in one or two sentences. Then:

- **Audited:** commit, pinned upstream versions, date.
- **Protected:** each behaviour confirmed safe, with "measured" or "read from code".
- **Not protected:** each weakness with who learns what, how much (use the probe's numbers), and whether the wallet shows an error.
- **Changed since baseline:** anything that moved.
- **Decisions for the owner:** fixes that need a choice, with a recommendation.
- **Not checked:** anything skipped and why (for example no device test, no live node).
