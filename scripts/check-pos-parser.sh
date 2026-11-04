#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
command -v kotlinc >/dev/null || { echo 'Needs kotlinc on PATH' >&2; exit 2; }
command -v java >/dev/null || { echo 'Needs Java on PATH' >&2; exit 2; }
out="$(mktemp --suffix=.jar)"
trap 'rm -f "$out"' EXIT
kotlinc \
  android/app/src/main/java/app/independentwallet/android/MoneroPaymentRequest.kt \
  android/app/src/main/java/app/independentwallet/android/NfcPaymentProtocol.kt \
  android/app/src/main/java/app/independentwallet/android/PosPaymentInput.kt \
  scripts/PosParserSmoke.kt -include-runtime -d "$out"
java -jar "$out"
