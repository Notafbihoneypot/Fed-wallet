#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# Owner decision: Nostr belongs to Fed POS, never to this wallet.
if grep -qi 'nostr' Cargo.lock android/app/build.gradle.kts; then
  echo "A Nostr dependency entered the wallet build; Nostr belongs to Fed POS (see AGENTS.md)." >&2
  exit 1
fi
cargo fmt --all -- --check
cargo test --locked --workspace
cargo clippy --locked --workspace --all-targets -- -D warnings
