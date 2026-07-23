#!/usr/bin/env bash
set -euo pipefail

resolve_adb() {
  if command -v adb >/dev/null 2>&1; then
    command -v adb
    return
  fi
  for root in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}"; do
    if [[ -n "$root" && -x "$root/platform-tools/adb" ]]; then
      echo "$root/platform-tools/adb"
      return
    fi
  done
  echo "adb was not found. Set ANDROID_HOME or install platform-tools on PATH." >&2
  exit 1
}

ADB="$(resolve_adb)"
if ! "$ADB" devices | grep -q $'\tdevice'; then
  echo "No authorized Android device is connected." >&2
  exit 1
fi

"$ADB" logcat -c
echo "Waiting for HyperIconPack / LSPosed logs..."
"$ADB" logcat -v threadtime | grep -E 'HyperIconPack|LSPosed|Xposed'
