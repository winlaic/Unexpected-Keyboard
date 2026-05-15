#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_STUDIO_JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
APK_PATH="$ROOT_DIR/build/outputs/apk/debug/Unexpected-Keyboard-debug.apk"

usage() {
  cat <<EOF
Usage: $(basename "$0") [--build-only]

Build the debug APK with Android Studio's bundled Java, then install it on the
connected Android device unless --build-only is passed.
EOF
}

BUILD_ONLY=0
for arg in "$@"; do
  case "$arg" in
    --build-only)
      BUILD_ONLY=1
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      usage >&2
      exit 2
      ;;
  esac
done

if [[ -z "${JAVA_HOME:-}" ]]; then
  export JAVA_HOME="$ANDROID_STUDIO_JAVA_HOME"
fi

if [[ ! -x "$JAVA_HOME/bin/java" ]]; then
  echo "Java runtime not found at JAVA_HOME=$JAVA_HOME" >&2
  echo "Install Android Studio or set JAVA_HOME to a valid JDK." >&2
  exit 1
fi

export PATH="$JAVA_HOME/bin:$PATH"

cd "$ROOT_DIR"
echo "JAVA_HOME=$JAVA_HOME"
"$JAVA_HOME/bin/java" -version
./gradlew assembleDebug

if [[ "$BUILD_ONLY" -eq 1 ]]; then
  echo "Built: $APK_PATH"
  exit 0
fi

if ! command -v adb >/dev/null 2>&1; then
  echo "adb not found in PATH; built APK only: $APK_PATH" >&2
  exit 1
fi

DEVICE_COUNT="$(adb devices | awk 'NR > 1 && $2 == "device" { count++ } END { print count + 0 }')"
if [[ "$DEVICE_COUNT" -eq 0 ]]; then
  echo "No connected adb device; built APK only: $APK_PATH" >&2
  exit 1
fi

adb install -r "$APK_PATH"
echo "Installed: $APK_PATH"
