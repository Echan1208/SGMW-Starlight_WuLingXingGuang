#!/bin/bash
# Builds PedestrianWarningOff.apk without Gradle: javac -> d8 -> aapt2 -> zipalign -> apksigner.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17)}"
export PATH="$JAVA_HOME/bin:$PATH"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$ANDROID_HOME/build-tools/34.0.0"
ANDROID_JAR="$ANDROID_HOME/platforms/android-33/android.jar"
OUT="$HERE/build"
KEYSTORE="$HERE/release.keystore"

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex"

javac -source 11 -target 11 -encoding UTF-8 -Xlint:-options \
    -classpath "$ANDROID_JAR" -d "$OUT/classes" \
    $(find "$HERE/src" -name '*.java')

"$BT/d8" --min-api 33 --lib "$ANDROID_JAR" --output "$OUT/dex" \
    $(find "$OUT/classes" -name '*.class')

"$BT/aapt2" link -o "$OUT/unsigned.apk" -I "$ANDROID_JAR" \
    --manifest "$HERE/AndroidManifest.xml"
(cd "$OUT/dex" && zip -q -j "$OUT/unsigned.apk" classes.dex)

"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

if [ ! -f "$KEYSTORE" ]; then
    keytool -genkeypair -keystore "$KEYSTORE" -alias pwoff -keyalg RSA -keysize 2048 \
        -validity 36500 -storepass pwoffpass -keypass pwoffpass -dname "CN=PedestrianWarningOff"
fi
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias pwoff \
    --ks-pass pass:pwoffpass --key-pass pass:pwoffpass \
    --out "$HERE/PedestrianWarningOff.apk" "$OUT/aligned.apk"

echo "Built $HERE/PedestrianWarningOff.apk"
