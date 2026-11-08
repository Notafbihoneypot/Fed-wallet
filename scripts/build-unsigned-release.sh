#!/usr/bin/env bash
# Build unsigned release APK from repository root.
# Do not insert or use a signing key at this stage.
set -euo pipefail
cd "$(dirname "$0")/.."
bash scripts/build-android-native.sh
bash android/gradlew -p android :app:assembleRelease --no-daemon --stacktrace
apk='android/app/build/outputs/apk/release/app-release-unsigned.apk'
python3 scripts/verify-unsigned-apk.py "$apk" --lib libindependent_wallet.so --abi arm64-v8a --abi x86_64
python3 scripts/check-apk.py "$apk"
if [[ -n "${ANDROID_HOME:-}" ]]; then
  "$ANDROID_HOME/build-tools/36.0.0/zipalign" -c -P 16 4 "$apk"
  if "$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose "$apk"; then
    echo 'ERROR: APK is signed already' >&2; exit 1
  fi
fi
sha256sum "$apk"
echo "Ready: $apk (unsigned, not installable until signed)"
