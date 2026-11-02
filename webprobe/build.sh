#!/bin/sh
# Build the WebView probe APK without Gradle: javac -> d8 -> aapt2 -> zipalign -> apksigner.
set -eu

SDK="$HOME/Library/Android/sdk"
BT="$SDK/build-tools/36.0.0"
AJAR="$SDK/platforms/android-36/android.jar"
SRC="$(cd "$(dirname "$0")" && pwd)"
OUT="${UULABS_SCRATCH_DIR:?UULABS_SCRATCH_DIR must be set}/webprobe"

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex"

echo "== javac"
javac -nowarn -classpath "$AJAR" -d "$OUT/classes" \
      "$SRC/java/au/com/uulabs/webprobe/Main.java"

echo "== d8"
"$BT/d8" --lib "$AJAR" --min-api 31 --output "$OUT/dex" \
      $(find "$OUT/classes" -name '*.class')

echo "== aapt2 link"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$AJAR" --manifest "$SRC/AndroidManifest.xml"

echo "== add dex"
cp "$OUT/base.apk" "$OUT/app-unsigned.apk"
(cd "$OUT/dex" && zip -q -j "$OUT/app-unsigned.apk" classes.dex)

echo "== zipalign"
"$BT/zipalign" -f 4 "$OUT/app-unsigned.apk" "$OUT/app-aligned.apk"

echo "== sign"
KS="$OUT/debug.keystore"
keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
        -alias probe -keyalg RSA -keysize 2048 -validity 3650 \
        -dname "CN=UU Probe" >/dev/null 2>&1
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
        --out "$OUT/probe.apk" "$OUT/app-aligned.apk"

echo "APK: $OUT/probe.apk"
ls -l "$OUT/probe.apk"
