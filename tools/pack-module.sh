#!/bin/sh
# Build stylusctld and pack one Magisk zip per architecture.
# The 32-bit zip carries the armv7 daemon under the name stylusctld,
# which is the path service.sh executes.
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
"$root/tools/build.sh"

pack() {
    bin=$1
    outzip=$2
    stage=$(mktemp -d)
    cp -a "$root/magisk/stylusctrl/." "$stage/"
    cp "$bin" "$stage/stylusctld"
    chmod 755 "$stage/service.sh" "$stage/stylusctld" \
        "$stage/META-INF/com/google/android/update-binary"
    rm -f "$outzip"
    (
        cd "$stage"
        zip -r "$outzip" .
    )
    rm -rf "$stage"
    echo "$outzip"
}

mkdir -p "$root/out"
rm -f "$root/out/stylusctrl.zip"
pack "$root/out/stylusctld" "$root/out/stylusctrl-arm64.zip"
pack "$root/out/stylusctld32" "$root/out/stylusctrl-arm.zip"
