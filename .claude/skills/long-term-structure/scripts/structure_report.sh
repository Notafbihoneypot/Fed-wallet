#!/usr/bin/env bash
# Read-only snapshot of where this codebase's structure is under strain.
# No network, no builds, no edits. Numbers are signals to investigate, not verdicts.
set -uo pipefail
root="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
cd "$root"
source_files() { git ls-files '*.rs' '*.kt' | grep -v -e '/bindings/' -e '^vendor/' -e '/build/'; }

echo "== Largest source files (lines) =="
source_files | xargs wc -l 2>/dev/null | grep -v ' total$' | sort -rn | head -10

echo
echo "== Hotspots: commits touching the file x its size =="
echo "   (High on both = complex code that keeps changing: the best place to invest.)"
git log --format= --name-only -- '*.rs' '*.kt' | grep -v -e '/bindings/' -e '^vendor/' | sort | uniq -c |
  while read -r commits file; do
    [ -f "$file" ] || continue
    lines=$(wc -l < "$file")
    echo "$((commits * lines)) commits=$commits lines=$lines $file"
  done | sort -rn | head -10

echo
echo "== Kotlin classes carrying the most state (mutable fields, functions) =="
for f in $(git ls-files 'android/*.kt' | grep -v -e '/bindings/' -e '/test/' -e '/androidTest/'); do
  vars=$(grep -c -E '^\s*(private |internal )?var ' "$f")
  funs=$(grep -c -E '\bfun ' "$f")
  echo "$vars vars, $funs funs  $f"
done | sort -rn | head -5

echo
echo "== Rust public surface per crate (pub items outside tests) =="
for crate in crates/*/; do
  count=$(grep -rhE '^\s*pub (fn|struct|enum|trait|type|const|mod) ' "$crate/src" --include='*.rs' 2>/dev/null |
    wc -l)
  echo "$count  $crate"
done

echo
echo "== unsafe in first-party Rust (vendor excluded) =="
grep -rn --include='*.rs' -E '\bunsafe\b' crates || echo "none"

echo
echo "== Duplicate dependency versions in the shipped graph =="
cargo tree --locked --offline -d -e normal --target all 2>/dev/null | grep -E '^[a-zA-Z]' | sort -u ||
  echo "(cargo tree unavailable offline; run cargo fetch --locked first)"

echo
echo "== Rules, decisions and vocabulary =="
echo "AGENTS.md rules: $(grep -c '^- ' AGENTS.md 2>/dev/null) ($(wc -w < AGENTS.md 2>/dev/null) words)"
echo "Decision records (docs/adr): $(ls docs/adr/*.md 2>/dev/null | wc -l)"
echo "Glossary (docs/CONTEXT.md): $([ -f docs/CONTEXT.md ] && echo present || echo absent)"
echo "Docs files: $(ls docs | wc -l), of which validation reports: $(ls docs | grep -c VALIDATION)"
