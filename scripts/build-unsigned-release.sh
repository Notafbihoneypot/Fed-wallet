#!/usr/bin/env bash
# Build unsigned release APK from repository root.
# Do not insert or use a signing key at this stage.
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ "wallet" == 'wallet' ]]; then
  bash scripts/build-android-native.sh
  lib='libindependent_wallet.so'
  abis=(arm64-v8a x86_64)
  build_tools='36.0.0'
else
  bash scripts/build-android.sh
  lib='libfedpos_core.so'
  abis=(arm64-v8a armeabi-v7a x86_64)
  build_tools='35.0.0'
fi
bash android/gradlew -p android :app:assembleRelease --no-daemon --stacktrace
apk='android/app/build/outputs/apk/release/app-release-unsigned.apk'
args=("$apk" --lib "$lib")
for abi in "${{abis[@]}}"; do args+=(--abi "$abi"); done
python3 scripts/verify-unsigned-apk.py "${{args[@]}}"
if [[ -n "${{ANDROID_HOME:-}}" ]]; then
  "$ANDROID_HOME/build-tools/$build_tools/zipalign" -c -P 16 4 "$apk"
  if "$ANDROID_HOME/build-tools/$build_tools/apksigner" verify --verbose "$apk"; then
    echo 'ERROR: APK is signed already' >&2; exit 1
  fi
fi
sha256sum "$apk"
echo "Ready: $apk (unsigned, not installable until signed)"
