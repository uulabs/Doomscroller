#!/bin/sh
# UU Doomscroller prototype - framework-only build, no Gradle, no dependencies.
set -eu

SDK="$HOME/Library/Android/sdk"
BT="$SDK/build-tools/36.0.0"
AJAR="$SDK/platforms/android-36/android.jar"
SRC="$(cd "$(dirname "$0")" && pwd)"
OUT="${UULABS_SCRATCH_DIR:?UULABS_SCRATCH_DIR must be set}/doomscroller"

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex"

echo "== javac"
javac -nowarn -classpath "$AJAR" -d "$OUT/classes" \
      $(find "$SRC/java" -name '*.java')

echo "== d8"
"$BT/d8" --lib "$AJAR" --min-api 26 --output "$OUT/dex" \
      $(find "$OUT/classes" -name '*.class')

echo "== aapt2 link"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$AJAR" --manifest "$SRC/AndroidManifest.xml"

echo "== add dex"
cp "$OUT/base.apk" "$OUT/app-unsigned.apk"
(cd "$OUT/dex" && zip -q -j "$OUT/app-unsigned.apk" classes.dex)

echo "== zipalign"
"$BT/zipalign" -f 4 "$OUT/app-unsigned.apk" "$OUT/app-aligned.apk"

echo "== sign"
KS="$SRC/keystore.jks"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass doomscroller -keypass doomscroller \
          -alias uu -keyalg RSA -keysize 2048 -validity 3650 \
          -dname "CN=UU Doomscroller" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:doomscroller --key-pass pass:doomscroller \
        --out "$OUT/doomscroller.apk" "$OUT/app-aligned.apk"

echo "APK: $OUT/doomscroller.apk"
ls -l "$OUT/doomscroller.apk"
