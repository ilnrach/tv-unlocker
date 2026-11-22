# Сборка

Приложение собирается напрямую утилитами Android SDK без Gradle.
Понадобятся:

- JDK 17 (подойдёт [Temurin](https://adoptium.net/));
- Android SDK: `build-tools;34.0.0` и `platforms;android-34`
  (ставятся через `sdkmanager` из [command line tools](https://developer.android.com/studio#command-line-tools-only));
- опционально `platform-tools` для adb.

## Вручную

```bash
SDK=/path/to/android-sdk
BT=$SDK/build-tools/34.0.0
PLATFORM=$SDK/platforms/android-34/android.jar
WORK=$(mktemp -d)   # лучше путь без не-ASCII символов

# 1. компиляция
javac -encoding UTF-8 -source 8 -target 8 -cp "$PLATFORM" \
      -d "$WORK/classes" src/ru/tvunlocker/*.java

# 2. ресурсы и манифест
"$BT/aapt2" link -o "$WORK/app.apk" \
      --manifest AndroidManifest.xml -I "$PLATFORM"

# 3. байткод
"$BT/d8" --release --min-api 21 --lib "$PLATFORM" \
      --output "$WORK/dexout" "$WORK"/classes/ru/tvunlocker/*.class

# 4. dex внутрь APK
cd "$WORK/dexout" && jar --update --file ../app.apk classes.dex && cd -

# 5. выравнивание и подпись
"$BT/zipalign" -f 4 "$WORK/app.apk" "$WORK/app-aligned.apk"
"$BT/apksigner" sign --ks my.keystore --ks-pass pass:android \
      --out TV-Unlocker.apk "$WORK/app-aligned.apk"
```

Ключ хранилища создаётся один раз:

```bash
keytool -genkeypair -keystore my.keystore -alias tvu -storepass android \
        -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=TV Unlocker"
```

Не выкладывайте keystore в репозиторий (он в `.gitignore`).

## Скриптом

`scripts/build.sh` повторяет те же шаги, пути берёт из переменных окружения:

```bash
ANDROID_SDK=/path/to/android-sdk ./scripts/build.sh
```

Результат: `TV-Unlocker.apk` в корне репозитория.

## Важно

- Собирайте в пути без кириллицы: `aapt2` и эмулятор на Windows падают
  на не-ASCII путях.
- Перед сборкой поменяйте `PC_PRIMARY` в `TVUnlocker.java` и
  `PC_UPLOAD_HOST` в `KeyActivity.java` на IP своего компьютера,
  если пользуетесь отправкой отчётов по сети (`[U]`).
