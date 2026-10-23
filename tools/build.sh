#!/bin/sh
# Build the daemon. Host clang has no aarch64 linker, so this uses a
# static musl cross compiler when one is available.
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
mkdir -p "$root/out"

gcc -std=c11 -Wall -Wextra -DSTYLUSCTRL_TEST -o /tmp/stylusctld-test "$root/tools/stylusctld.c"
/tmp/stylusctld-test

cc=""
if [ -n "${STYLUSCTRL_CC:-}" ]; then
    cc=$STYLUSCTRL_CC
elif command -v aarch64-linux-musl-gcc >/dev/null 2>&1; then
    cc=$(command -v aarch64-linux-musl-gcc)
elif [ -x "$HOME/.cache/stylusctrl/aarch64-linux-musl-cross/bin/aarch64-linux-musl-gcc" ]; then
    cc=$HOME/.cache/stylusctrl/aarch64-linux-musl-cross/bin/aarch64-linux-musl-gcc
fi
if [ -z "$cc" ]; then
    echo "stylusctld: no aarch64 musl compiler. Set STYLUSCTRL_CC, or unpack" >&2
    echo "https://musl.cc/aarch64-linux-musl-cross.tgz under ~/.cache/stylusctrl/" >&2
    exit 1
fi
"$cc" -std=c11 -static -Os -Wall -Wextra -o "$root/out/stylusctld" "$root/tools/stylusctld.c"
echo "$root/out/stylusctld"
