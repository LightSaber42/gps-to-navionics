#!/bin/zsh
set -euo pipefail

KEYSTORE_DIR="$HOME/Library/Application Support/Anchor Watch Signing"
KEYSTORE_PATH="$KEYSTORE_DIR/play-upload.jks"
CERTIFICATE_PATH="$KEYSTORE_DIR/play-upload-certificate.pem"
KEY_ALIAS="anchor-watch-upload"
KEYCHAIN_SERVICE="com.jaco.navionicsgpsbridge.play-upload"
JDK_DIR="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
KEYTOOL="$JDK_DIR/bin/keytool"

if [[ ! -x "$KEYTOOL" ]]; then
    echo "Android Studio's keytool was not found at $KEYTOOL" >&2
    exit 1
fi

if [[ -e "$KEYSTORE_PATH" ]]; then
    echo "Refusing to replace the existing upload key at $KEYSTORE_PATH" >&2
    exit 1
fi

mkdir -p "$KEYSTORE_DIR"
chmod 700 "$KEYSTORE_DIR"

export ANCHOR_GENERATED_UPLOAD_PASSWORD="$(openssl rand -hex 32)"

security add-generic-password \
    -U \
    -a "$USER" \
    -s "$KEYCHAIN_SERVICE" \
    -w "$ANCHOR_GENERATED_UPLOAD_PASSWORD" >/dev/null

if ! "$KEYTOOL" -genkeypair \
    -keystore "$KEYSTORE_PATH" \
    -storetype PKCS12 \
    -storepass:env ANCHOR_GENERATED_UPLOAD_PASSWORD \
    -alias "$KEY_ALIAS" \
    -keypass:env ANCHOR_GENERATED_UPLOAD_PASSWORD \
    -keyalg RSA \
    -keysize 4096 \
    -validity 10000 \
    -dname "CN=Anchor Watch Play Upload, OU=Mobile, O=Anchor Watch"; then
    security delete-generic-password -a "$USER" -s "$KEYCHAIN_SERVICE" >/dev/null 2>&1 || true
    exit 1
fi

chmod 600 "$KEYSTORE_PATH"

"$KEYTOOL" -exportcert \
    -rfc \
    -keystore "$KEYSTORE_PATH" \
    -storepass:env ANCHOR_GENERATED_UPLOAD_PASSWORD \
    -alias "$KEY_ALIAS" \
    -file "$CERTIFICATE_PATH" >/dev/null

chmod 644 "$CERTIFICATE_PATH"
unset ANCHOR_GENERATED_UPLOAD_PASSWORD

echo "Created the protected Play upload key and public certificate:"
echo "$KEYSTORE_PATH"
echo "$CERTIFICATE_PATH"
echo "Back up the keystore and its macOS Keychain password before enabling Play App Signing."
