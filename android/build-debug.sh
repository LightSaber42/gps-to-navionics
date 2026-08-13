#!/bin/zsh
set -euo pipefail

SCRIPT_DIR=${0:A:h}
SDK_DIR=$(sed -n 's/^sdk.dir=//p' "$SCRIPT_DIR/local.properties" | head -n 1)
BUILD_TOOLS="$SDK_DIR/build-tools/36.1.0"
JDK_DIR="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
APK_PATH="$SCRIPT_DIR/app/build/outputs/apk/debug/app-debug.apk"

export JAVA_HOME="$JDK_DIR"
export GRADLE_USER_HOME="$SCRIPT_DIR/.gradle-user-home"

cd "$SCRIPT_DIR"
"$SCRIPT_DIR/gradlew" --no-daemon :app:assembleDebug
"$BUILD_TOOLS/apksigner" verify --verbose "$APK_PATH"
echo "$APK_PATH"
