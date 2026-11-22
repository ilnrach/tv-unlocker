@echo off
rem Сборка TV Unlocker без Gradle.
rem ANDROID_SDK — путь к Android SDK. Пример:
rem set ANDROID_SDK=C:\Android\Sdk

if "%ANDROID_SDK%"=="" (
    echo Укажите ANDROID_SDK=path\to\android-sdk
    exit /b 1
)

set BT=%ANDROID_SDK%\build-tools\34.0.0
set PLATFORM=%ANDROID_SDK%\platforms\android-34\android.jar
set WORK=%TEMP%\tvunlocker-build
set OUT=TV-Unlocker.apk
if "%KS%"=="" set KS=my.keystore
if "%KS_PASS%"=="" set KS_PASS=android

if not exist "%PLATFORM%" (
    echo Не найдена платформа: %PLATFORM%
    exit /b 1
)

echo [1/6] javac
if exist "%WORK%" rmdir /s /q "%WORK%"
mkdir "%WORK%\classes" "%WORK%\dexout"
javac -encoding UTF-8 -source 8 -target 8 -cp "%PLATFORM%" ^
      -d "%WORK%\classes" src\ru\tvunlocker\*.java || exit /b 1

echo [2/6] aapt2
"%BT%\aapt2" link -o "%WORK%\app.apk" --manifest AndroidManifest.xml -I "%PLATFORM%" || exit /b 1

echo [3/6] d8
call "%BT%\d8.bat" --release --min-api 21 --lib "%PLATFORM%" --output "%WORK%\dexout" "%WORK%\classes\ru\tvunlocker\*.class" || exit /b 1

echo [4/6] упаковка dex
cd /d "%WORK%\dexout"
jar --update --file ..\app.apk classes.dex
cd /d %~dp0

echo [5/6] zipalign
"%BT%\zipalign" -f 4 "%WORK%\app.apk" "%WORK%\app-aligned.apk" || exit /b 1

echo [6/6] apksigner
if not exist "%KS%" (
    echo   keystore %KS% не найден, создаю ^(пароли: android^)
    keytool -genkeypair -keystore %KS% -alias tvu -storepass %KS_PASS% -keypass %KS_PASS% -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=TV Unlocker"
)
call "%BT%\apksigner.bat" sign --ks %KS% --ks-pass pass:%KS_PASS% --key-pass pass:%KS_PASS% --out %OUT% "%WORK%\app-aligned.apk" || exit /b 1
call "%BT%\apksigner.bat" verify %OUT%

echo.
for %%A in (%OUT%) do echo Готово: %OUT% (%%~zA байт)
