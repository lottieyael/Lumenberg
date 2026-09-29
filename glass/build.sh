#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$sdk" ]]; then echo 'Set ANDROID_SDK_ROOT to an SDK with platform 35 and build-tools 35.0.0.' >&2; exit 1; fi
tools="$sdk/build-tools/35.0.0"
platform="$sdk/platforms/android-35/android.jar"
mkdir -p build/classes build/dex
rm -f build/resources.zip build/resources.apk build/Lumenberg-Glass-0.1.0.apk
"$tools/aapt2" compile --dir app/src/main/res -o build/resources.zip
sed 's/<manifest /<manifest package="dev.lumenberg.glass" /' app/src/main/AndroidManifest.xml > build/AndroidManifest.xml
"$tools/aapt2" link -o build/resources.apk -I "$platform" --manifest build/AndroidManifest.xml --min-sdk-version 28 --target-sdk-version 35 --version-code 1 --version-name 0.1.0 build/resources.zip
find app/src/main/java -name '*.java' -print > build/sources.txt
javac -encoding UTF-8 -source 17 -target 17 -classpath "$platform" -d build/classes @build/sources.txt
find build/classes -name '*.class' -print > build/classes.txt
"$tools/d8" --lib "$platform" --min-api 28 --output build/dex @build/classes.txt
cp build/resources.apk build/unsigned.apk
(cd build/dex && zip -q -u ../unsigned.apk classes*.dex)
"$tools/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk
keystore="${GLASS_SIGNING_KEYSTORE:-build/preview.keystore}"
if [[ ! -f "$keystore" ]]; then
    keytool -genkeypair -keystore "$keystore" -storepass android -keypass android -alias androiddebugkey -dname 'CN=Lumenberg Glass Preview' -keyalg RSA -keysize 2048 -validity 10000 >/dev/null 2>&1
fi
"$tools/apksigner" sign --ks "$keystore" --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out build/Lumenberg-Glass-0.1.0.apk build/aligned.apk
"$tools/apksigner" verify build/Lumenberg-Glass-0.1.0.apk
echo "Built $(pwd)/build/Lumenberg-Glass-0.1.0.apk"
