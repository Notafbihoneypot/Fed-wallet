#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_dir"
cargo build --locked -p wallet-ffi
case "$(uname -s)" in
  Linux) native_library=target/debug/libindependent_wallet.so ;;
  Darwin) native_library=target/debug/libindependent_wallet.dylib ;;
  *) echo "Use Linux or macOS for binding generation." >&2; exit 1 ;;
esac
cargo run --locked -p wallet-ffi --features bindgen --bin uniffi-bindgen -- generate \
  --library "$native_library" \
  --language kotlin --no-format \
  --out-dir android/app/src/main/java
