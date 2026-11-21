#!/usr/bin/env bash
# Lists every place randomness is created, seeded or consumed: in this
# repository's Rust code and in the pinned upstream Monero crates.
# Read-only. Evidence for an explanation, not a verdict.
set -euo pipefail
root="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
cd "$root"
pattern='OsRng|ThreadRng|thread_rng|StdRng|SmallRng|ChaCha[0-9]*Rng|SeedableRng|from_seed|from_entropy|seed_from_u64|seeded_rng|getrandom|RngCore|CryptoRng|next_u(32|64)\(|fill_bytes\(|Scalar::random|rand::random'
is_test='(_tests?\.rs|/tests\.rs|/tests/|/regtest\.rs|/benches/|/examples/)'

echo "== Pinned randomness crates (Cargo.lock) =="
for crate in rand rand_core rand_chacha rand_distr getrandom; do
  grep -A1 "^name = \"$crate\"\$" Cargo.lock | sed -n 's/^version = "\(.*\)"/\1/p' | sed "s/^/  $crate /"
done

echo
echo "== This repository: production code =="
grep -rnE "$pattern" crates vendor --include='*.rs' | grep -vE "$is_test" || true

echo
echo "== This repository: test-only files (never shipped) =="
grep -rlE "$pattern" crates vendor --include='*.rs' | grep -E "$is_test" || true

echo
echo "== Pinned upstream Monero crates: production code =="
cargo metadata --locked --format-version 1 2>/dev/null | python3 -I -c '
import json, os, sys
for package in json.load(sys.stdin)["packages"]:
    if package["name"].startswith("monero-") and package["source"]:
        print(package["name"], package["version"], os.path.dirname(package["manifest_path"]))
' | sort | while read -r name version dir; do
  hits="$(grep -rnE "$pattern" "$dir/src" --include='*.rs' 2>/dev/null | grep -vE "$is_test" | sed "s#^$dir/##" || true)"
  if [ -n "$hits" ]; then
    echo "-- $name $version"
    echo "$hits"
  fi
done
echo
echo "Lines inside an inline '#[cfg(test)]' module still appear under production code; open the file to tell."
