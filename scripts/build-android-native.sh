#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_dir"
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to an installed Android NDK.}"
command -v cargo-ndk >/dev/null || { echo "Install pinned cargo-ndk 4.1.2 first." >&2; exit 1; }
rustup target add aarch64-linux-android x86_64-linux-android
# Fetch before invoking cargo-ndk. Its panic report can print the environment;
# the offline build below must not inherit credentials or unrelated tokens.
cargo fetch --locked
python3 "$project_dir/scripts/native-build.py"
bash "$project_dir/scripts/build-bindings.sh"
