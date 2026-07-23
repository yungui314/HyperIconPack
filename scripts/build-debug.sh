#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [[ -z "${JAVA_HOME:-}" ]]; then
  if command -v java >/dev/null 2>&1; then
    JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
    export JAVA_HOME
  else
    echo "JAVA_HOME is not set and java was not found on PATH." >&2
    exit 1
  fi
fi

if [[ -z "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ]]; then
  if [[ -f local.properties ]]; then
    sdk_dir="$(sed -n 's/^sdk.dir=//p' local.properties | tail -n1 | sed 's/\\/\//g' | sed 's/\r$//')"
    if [[ -n "$sdk_dir" ]]; then
      export ANDROID_HOME="$sdk_dir"
      export ANDROID_SDK_ROOT="$sdk_dir"
    fi
  fi
fi

if [[ -z "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ]]; then
  echo "ANDROID_HOME / ANDROID_SDK_ROOT is not set (and local.properties has no sdk.dir)." >&2
  exit 1
fi

./gradlew :app:assembleDebug --no-daemon "$@"
