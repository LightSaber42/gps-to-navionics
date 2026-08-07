#!/bin/zsh
set -euo pipefail

SCRIPT_DIR=${0:A:h}
SDK_DIR=$(sed -n 's/^sdk.dir=//p' "$SCRIPT_DIR/local.properties" | head -n 1)
BUILD_TOOLS="$SDK_DIR/build-tools/36.1.0"
PLATFORM_JAR="$SDK_DIR/platforms/android-36/android.jar"
JDK_DIR="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export JAVA_HOME="$JDK_DIR"
BUILD_DIR="$SCRIPT_DIR/app/build/manual-debug"
GENERATED_DIR="$BUILD_DIR/generated"
CLASSES_DIR="$BUILD_DIR/classes"
DEX_DIR="$BUILD_DIR/dex"
OUTPUT_DIR="$SCRIPT_DIR/app/build/outputs/apk/debug"

rm -rf "$BUILD_DIR"
mkdir -p "$GENERATED_DIR" "$CLASSES_DIR" "$DEX_DIR" "$OUTPUT_DIR"

"$BUILD_TOOLS/aapt2" compile \
    --dir "$SCRIPT_DIR/app/src/main/res" \
    -o "$BUILD_DIR/resources.zip"

"$BUILD_TOOLS/aapt2" link \
    -o "$BUILD_DIR/app-unsigned.apk" \
    -I "$PLATFORM_JAR" \
    --manifest "$SCRIPT_DIR/app/src/main/AndroidManifest.xml" \
    --java "$GENERATED_DIR" \
    --min-sdk-version 26 \
    --target-sdk-version 35 \
    --version-code 3 \
    --version-name 1.2 \
    --auto-add-overlay \
    -R "$BUILD_DIR/resources.zip"

find "$SCRIPT_DIR/app/src/main/java" "$GENERATED_DIR" -name '*.java' -print \
    > "$BUILD_DIR/java-sources.txt"

"$JDK_DIR/bin/javac" \
    --release 17 \
    -Xlint:all \
    -classpath "$PLATFORM_JAR" \
    -d "$CLASSES_DIR" \
    @"$BUILD_DIR/java-sources.txt"

"$JDK_DIR/bin/jar" --create --file "$BUILD_DIR/classes.jar" -C "$CLASSES_DIR" .
"$BUILD_TOOLS/d8" \
    --lib "$PLATFORM_JAR" \
    --min-api 26 \
    --output "$DEX_DIR" \
    "$BUILD_DIR/classes.jar"

cp "$BUILD_DIR/app-unsigned.apk" "$BUILD_DIR/app-with-dex.apk"
zip -q -j "$BUILD_DIR/app-with-dex.apk" "$DEX_DIR/classes.dex"

if [[ ! -f "$SCRIPT_DIR/debug.keystore" ]]; then
    "$JDK_DIR/bin/keytool" -genkeypair \
        -keystore "$SCRIPT_DIR/debug.keystore" \
        -storepass android \
        -keypass android \
        -alias androiddebugkey \
        -dname "CN=Android Debug,O=Android,C=US" \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000
fi

"$BUILD_TOOLS/apksigner" sign \
    --ks "$SCRIPT_DIR/debug.keystore" \
    --ks-key-alias androiddebugkey \
    --ks-pass pass:android \
    --key-pass pass:android \
    --out "$OUTPUT_DIR/app-debug.apk" \
    "$BUILD_DIR/app-with-dex.apk"

"$BUILD_TOOLS/apksigner" verify --verbose "$OUTPUT_DIR/app-debug.apk"
echo "$OUTPUT_DIR/app-debug.apk"
