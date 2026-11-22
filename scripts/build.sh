#!/usr/bin/env bash
# Сборка TV Unlocker без Gradle.
# ANDROID_SDK — путь к Android SDK (build-tools 34.0.0 и platform android-34).
set -e

: "${ANDROID_SDK:?Укажите ANDROID_SDK=/path/to/android-sdk}"
BT="$ANDROID_SDK/build-tools/34.0.0"
PLATFORM="$ANDROID_SDK/platforms/android-34/android.jar"
WORK="$(mktemp -d)"
OUT="TV-Unlocker.apk"
KS="${KS:-my.keystore}"
KS_PASS="${KS_PASS:-android}"

command -v java >/dev/null || { echo "JDK не найден в PATH"; exit 1; }
[ -f "$PLATFORM" ] || { echo "Не найдена платформа: $PLATFORM (sdkmanager 'platforms;android-34')"; exit 1; }

echo "[1/6] javac"
mkdir -p "$WORK/classes" "$WORK/dexout"
javac -encoding UTF-8 -source 8 -target 8 -cp "$PLATFORM" \
      -d "$WORK/classes" src/ru/tvunlocker/*.java

echo "[2/6] aapt2"
"$BT/aapt2" link -o "$WORK/app.apk" \
      --manifest AndroidManifest.xml -I "$PLATFORM"

echo "[3/6] d8"
"$BT/d8" --release --min-api 21 --lib "$PLATFORM" \
      --output "$WORK/dexout" "$WORK"/classes/ru/tvunlocker/*.class

echo "[4/6] упаковка dex"
(cd "$WORK/dexout" && jar --update --file ../app.apk classes.dex)

echo "[5/6] zipalign"
"$BT/zipalign" -f 4 "$WORK/app.apk" "$WORK/app-aligned.apk"

echo "[6/6] apksigner"
if [ ! -f "$KS" ]; then
    echo "  keystore $KS не найден, создаю (пароли: android)"
    keytool -genkeypair -keystore "$KS" -alias tvu -storepass "$KS_PASS" \
            -keypass "$KS_PASS" -keyalg RSA -keysize 2048 -validity 10000 \
            -dname "CN=TV Unlocker"
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KS_PASS" \
      --key-pass "pass:$KS_PASS" --out "$OUT" "$WORK/app-aligned.apk"
"$BT/apksigner" verify "$OUT"

echo
echo "Готово: $OUT ($(stat -c %s "$OUT") байт)"
