# Decision records

`AGENTS.md` says *what* must hold. A decision record (ADR) says *why*, what else was considered, and what it costs. Without the why, the next person either follows a rule blindly or removes it without knowing what it protected.

## When to write one

Write an ADR only when all three hold:

1. **Hard to reverse.** Changing it later means migrating stored data, breaking signed releases, changing the FFI, or re-auditing security.
2. **Surprising without context.** A capable newcomer would ask "why on earth is it done this way?".
3. **A real trade-off.** There was a credible alternative, and choosing this one cost something on at least one lens (security, speed, simplicity).

Everything else belongs in a code comment, a commit message, or nowhere. Over-documenting buries the decisions that matter.

## Where and how

- One file per decision: `docs/adr/NNNN-short-title.md`, numbered in order, never renumbered.
- Create `docs/adr/` with the first ADR, not before.
- Never edit a decided ADR's reasoning. To change course, write a new ADR that supersedes it and mark the old one `Superseded by NNNN`.
- When an ADR backs an `AGENTS.md` rule, link the ADR from that rule so the rule stays one line.

## Template

```markdown
# NNNN. Title in plain words

Status: Proposed | Accepted | Superseded by NNNN
Date: YYYY-MM-DD
Context: which bounded context(s) this belongs to

## Decision
One or two sentences, in glossary terms.

## Why
The forces: the attacker or failure, the cost on a phone, the complexity.

## Alternatives considered
Each with the reason it lost.

## Consequences
- Security: what is now protected, and what is still exposed.
- Speed: runtime and development cost.
- Simplicity: what a reader must now know.

## Revisit when
The concrete event that should reopen this (an upstream release, FCMP++, a device test result).
```

## Existing candidates

These decisions already exist as rules in `AGENTS.md` with no recorded reasoning. They are good first ADRs when work next touches them:

- Ring reuse for the same coin within 720 blocks (the one allowed exception to never replaying ring requests).
- Decoy counts are untrusted and checked against the wallet's own scan.
- No default node list and no automatic failover.
- Tor never falls back to direct.
- The replacement signing identity for Preview 2.
- Transaction keys stored only in the encrypted journal and shown only after authentication.
