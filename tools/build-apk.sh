#!/bin/sh
# Build the settings app. SDK lives in ANDROID_HOME or ~/Android/Sdk.
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
sdk=${ANDROID_HOME:-$HOME/Android/Sdk}
printf 'sdk.dir=%s\n' "$sdk" > "$root/local.properties"
cd "$root"
./gradlew :app:assembleDebug
mkdir -p "$root/out"
cp "$root/app/build/outputs/apk/debug/app-debug.apk" "$root/out/stylusctrl.apk"
echo "$root/out/stylusctrl.apk"
