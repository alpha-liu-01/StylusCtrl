#!/bin/sh
# Build the settings app. SDK lives in ANDROID_HOME or ~/Android/Sdk.
# The apk has no native code, so the arm64 and arm copies are the same
# package. The architecture split is the module zip, which carries stylusctld.
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
sdk=${ANDROID_HOME:-$HOME/Android/Sdk}
printf 'sdk.dir=%s\n' "$sdk" > "$root/local.properties"
cd "$root"
./gradlew :app:assembleDebug
mkdir -p "$root/out"
rm -f "$root/out/stylusctrl.apk"
cp "$root/app/build/outputs/apk/debug/app-debug.apk" "$root/out/stylusctrl-arm64.apk"
cp "$root/app/build/outputs/apk/debug/app-debug.apk" "$root/out/stylusctrl-arm.apk"
echo "$root/out/stylusctrl-arm64.apk"
echo "$root/out/stylusctrl-arm.apk"
