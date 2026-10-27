#!/bin/zsh
set -euo pipefail

SCRIPT_DIR=${0:A:h}
KEYSTORE_PATH="$HOME/Library/Application Support/Anchor Watch Signing/play-upload.jks"
KEY_ALIAS="anchor-watch-upload"
KEYCHAIN_SERVICE="com.jaco.navionicsgpsbridge.play-upload"
JDK_DIR="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
AAB_PATH="$SCRIPT_DIR/app/build/outputs/bundle/release/app-release.aab"

if [[ ! -f "$KEYSTORE_PATH" ]]; then
    echo "Play upload key not found. Run ./setup-play-upload-key.sh first." >&2
    exit 1
fi

export JAVA_HOME="$JDK_DIR"
export GRADLE_USER_HOME="$SCRIPT_DIR/.gradle-user-home"
export ANCHOR_UPLOAD_STORE_FILE="$KEYSTORE_PATH"
export ANCHOR_UPLOAD_STORE_PASSWORD="$(security find-generic-password \
    -a "$USER" \
    -s "$KEYCHAIN_SERVICE" \
    -w)"
export ANCHOR_UPLOAD_KEY_ALIAS="$KEY_ALIAS"
export ANCHOR_UPLOAD_KEY_PASSWORD="$ANCHOR_UPLOAD_STORE_PASSWORD"

cd "$SCRIPT_DIR"
"$SCRIPT_DIR/gradlew" --no-daemon :app:bundleRelease
"$JAVA_HOME/bin/jarsigner" -verify -verbose -certs "$AAB_PATH" >/dev/null

unset ANCHOR_UPLOAD_STORE_PASSWORD ANCHOR_UPLOAD_KEY_PASSWORD

echo "Verified signed Android App Bundle:"
echo "$AAB_PATH"
