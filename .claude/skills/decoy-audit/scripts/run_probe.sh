#!/usr/bin/env bash
# Installs the decoy probe as a temporary test module, runs it, and restores
# the engine file exactly as it was (including any uncommitted edits).
set -euo pipefail
skill="$(cd "$(dirname "$0")/.." && pwd)"
root="$(git -C "$skill" rev-parse --show-toplevel)"
engine="$root/crates/wallet-engine/src/engine.rs"
probe="$root/crates/wallet-engine/src/engine/decoy_audit_probe.rs"
if [ -e "$probe" ]; then
  echo "A probe file is already installed at $probe; remove it first." >&2
  exit 1
fi
backup="$(mktemp)"
cp -p "$engine" "$backup"
cleanup() {
  cp -p "$backup" "$engine"
  rm -f "$backup" "$probe"
}
trap cleanup EXIT
cp "$skill/probe/decoy_probe.rs" "$probe"
printf '\n#[cfg(test)]\nmod decoy_audit_probe;\n' >> "$engine"
cd "$root"
echo "Pinned upstream versions (Cargo.lock):"
for crate in monero-wallet monero-interface monero-daemon-rpc monero-oxide monero-clsag; do
  version="$(grep -A1 "^name = \"$crate\"\$" Cargo.lock | sed -n 's/^version = "\(.*\)"/\1/p' | head -1)"
  echo "  $crate $version"
done
echo
log="$(mktemp)"
status=0
cargo test --locked -p wallet-engine decoy_audit_probe -- --nocapture >"$log" 2>&1 || status=$?
if [ "$status" -ne 0 ]; then
  tail -40 "$log"
  rm -f "$log"
  echo "The probe did not build or run. Fix the probe to match the engine; do not drop scenarios." >&2
  exit "$status"
fi
grep '^RESULT ' "$log" || { tail -40 "$log"; rm -f "$log"; echo "No RESULT lines were printed." >&2; exit 1; }
rm -f "$log"
