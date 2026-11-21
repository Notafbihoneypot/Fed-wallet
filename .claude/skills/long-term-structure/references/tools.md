# Tools

What each tool measures, why it matters here, and how to run it. Installing needs crates.io or Maven access; nothing here is wired into CI yet. Add a tool to `scripts/check.sh` or CI only after a trial run shows it is fast and quiet enough to keep.

## Already in use

- `scripts/check.sh`: `cargo fmt --check`, the full workspace tests, and `cargo clippy -D warnings`.
- `cargo audit` (see `docs/DEPENDENCY_REVIEW.md`): known vulnerabilities in dependencies.
- `.claude/skills/long-term-structure/scripts/structure_report.sh`: sizes, hotspots, stateful classes, public surface, duplicate dependencies, rule and doc counts. Offline and read-only.

## Rust: test quality

- **cargo-mutants** (`cargo install cargo-mutants`, then `cargo mutants -p wallet-engine`). Changes the code in small ways and checks the tests notice. It shows whether a test actually pins a rule, not just whether it runs the line. Slow: each mutant is a rebuild, so scope it to one crate or file (`--file crates/wallet-engine/src/engine/decoys.rs`). The best tool for the security-critical modules.
- **cargo-llvm-cov** (`cargo llvm-cov -p wallet-engine --html`). Line and branch coverage. Shows what is never exercised; pair it with mutation testing, because covered is not the same as checked.
- **proptest** (a dev-dependency). Property tests: state a rule ("any amount string that parses round-trips exactly") and let it search for counterexamples. Good for amounts, URIs, node endpoints and seed parsing.
- **cargo-fuzz** (needs nightly). Fuzzing for parsers fed untrusted input: payment requests, QR/UR frames, node replies.

## Rust: structure and dependencies

- **cargo-modules** (`cargo modules structure -p wallet-engine`). Prints the module tree and its visibility; shows when a module's insides are more public than they need to be.
- **cargo-deny** (`cargo deny check`). One policy file for advisories, licences, banned crates and duplicate versions. Would turn the duplicate-version list in the structure report into an enforced budget.
- **cargo-machete** (fast, heuristic) or **cargo-udeps** (precise, needs nightly). Unused dependencies.
- **cargo-geiger**. Counts `unsafe` across the whole dependency tree, not just first-party code.
- **cargo-bloat** (`cargo bloat --release -p wallet-ffi --crates`). What makes the native library large; relevant to APK size.
- **Clippy, stricter groups.** Try `cargo clippy -- -W clippy::pedantic -W clippy::cognitive_complexity -W clippy::too_many_lines` on one crate and keep the lints that find real problems.

## Kotlin and Android

- **detekt**. Static analysis with complexity rules (long methods, large classes, too many functions, complex conditions). The fastest way to put numbers on the view-model hotspot and stop it regrowing after a split.
- **Konsist**. Architecture rules written as unit tests ("view models must not import vault classes", "only these files may call the FFI"). Turns a structure decision into a failing test instead of a review comment.
- **Android Lint**, including the Compose lint checks. Already available through Gradle (`./gradlew :app:lint`); worth running and triaging once.

## History and hotspots

- **code-maat** (Adam Tornhill's open-source tool). Change coupling (files that always change together), churn and author distribution from `git log`. Useful once the history is longer; the repository currently has few commits, so hotspot rankings are still noisy.
- Plain git: `git log --format= --name-only | sort | uniq -c | sort -rn | head` lists the most-changed files.
