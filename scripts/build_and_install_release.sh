#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_STUDIO_JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
APK_PATH="$ROOT_DIR/build/outputs/apk/release/Unexpected-Keyboard-release.apk"

usage() {
  cat <<EOF
Usage: $(basename "$0") [--build-only] [--replace-incompatible] [--drop-data]

Build the release APK with Android Studio's bundled Java, then install it on the
connected Android device unless --build-only is passed.

Set RELEASE_KEYSTORE, RELEASE_KEYSTORE_PASSWORD, RELEASE_KEY_ALIAS, and
RELEASE_KEY_PASSWORD to sign with a real release key. If they are unset, this
script signs the local release build with debug.keystore so it can be installed
for testing.

Use --replace-incompatible to handle an existing install signed with a different
key by uninstalling it with -k before installing the new APK. If the device
still rejects the new signature after that, pass --drop-data to fully remove the
old package state and app data before retrying.
EOF
}

BUILD_ONLY=0
REPLACE_INCOMPATIBLE=0
DROP_DATA=0
for arg in "$@"; do
  case "$arg" in
    --build-only)
      BUILD_ONLY=1
      ;;
    --replace-incompatible)
      REPLACE_INCOMPATIBLE=1
      ;;
    --drop-data)
      DROP_DATA=1
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

if [[ -z "${RELEASE_KEYSTORE:-}" ]]; then
  export RELEASE_KEYSTORE="$ROOT_DIR/debug.keystore"
  export RELEASE_KEYSTORE_PASSWORD="debug0"
  export RELEASE_KEY_ALIAS="debug"
  export RELEASE_KEY_PASSWORD="debug0"
  echo "RELEASE_KEYSTORE is unset; using debug.keystore for local release install."
fi

if [[ ! -f "$RELEASE_KEYSTORE" ]]; then
  echo "Release keystore not found: $RELEASE_KEYSTORE" >&2
  exit 1
fi

export PATH="$JAVA_HOME/bin:$PATH"

cd "$ROOT_DIR"
echo "JAVA_HOME=$JAVA_HOME"
"$JAVA_HOME/bin/java" -version
./gradlew --no-daemon --no-configuration-cache assembleRelease

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

if ! INSTALL_OUTPUT="$(adb install -r "$APK_PATH" 2>&1)"; then
  echo "$INSTALL_OUTPUT" >&2
  if [[ "$REPLACE_INCOMPATIBLE" -eq 1 \
        && "$INSTALL_OUTPUT" == *"INSTALL_FAILED_UPDATE_INCOMPATIBLE"* ]]; then
    echo "Existing package has a different signature; uninstalling with -k and retrying."
    adb shell cmd package uninstall -k juloo.keyboard2
    if ! RETRY_OUTPUT="$(adb install "$APK_PATH" 2>&1)"; then
      echo "$RETRY_OUTPUT" >&2
      if [[ "$DROP_DATA" -eq 1 \
            && "$RETRY_OUTPUT" == *"INSTALL_FAILED_UPDATE_INCOMPATIBLE"* ]]; then
        echo "Retained package state still has a different signature; dropping app data and retrying."
        adb shell cmd package uninstall juloo.keyboard2 || true
        adb install "$APK_PATH"
      else
        exit 1
      fi
    else
      echo "$RETRY_OUTPUT"
    fi
  else
    exit 1
  fi
else
  echo "$INSTALL_OUTPUT"
fi
echo "Installed: $APK_PATH"
