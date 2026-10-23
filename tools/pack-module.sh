#!/bin/sh
# Build stylusctld and pack the Magisk module zip.
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
"$root/tools/build.sh"
stage=$(mktemp -d)
trap 'rm -rf "$stage"' EXIT
cp -a "$root/magisk/stylusctrl/." "$stage/"
cp "$root/out/stylusctld" "$stage/stylusctld"
chmod 755 "$stage/service.sh" "$stage/stylusctld" \
    "$stage/META-INF/com/google/android/update-binary"
mkdir -p "$root/out"
rm -f "$root/out/stylusctrl.zip"
(
    cd "$stage"
    zip -r "$root/out/stylusctrl.zip" .
)
echo "$root/out/stylusctrl.zip"
