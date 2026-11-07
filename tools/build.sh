#!/bin/sh
# Build the daemon. Host clang has no aarch64 linker, so this uses a
# static musl cross compiler when one is available. The armv7 binary is
# out/stylusctld32, for a 32-bit tablet such as the Note 8.0.
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

cc32=""
if [ -n "${STYLUSCTRL_CC32:-}" ]; then
    cc32=$STYLUSCTRL_CC32
elif command -v armv7l-linux-musleabihf-gcc >/dev/null 2>&1; then
    cc32=$(command -v armv7l-linux-musleabihf-gcc)
elif [ -x "$HOME/.cache/stylusctrl/armv7l-linux-musleabihf-cross/bin/armv7l-linux-musleabihf-gcc" ]; then
    cc32=$HOME/.cache/stylusctrl/armv7l-linux-musleabihf-cross/bin/armv7l-linux-musleabihf-gcc
fi
if [ -z "$cc32" ]; then
    echo "stylusctld32: no armv7 musl compiler. Set STYLUSCTRL_CC32, or unpack" >&2
    echo "https://musl.cc/armv7l-linux-musleabihf-cross.tgz under ~/.cache/stylusctrl/" >&2
    exit 1
fi
"$cc32" -std=c11 -static -Os -Wall -Wextra -o "$root/out/stylusctld32" "$root/tools/stylusctld.c"
echo "$root/out/stylusctld32"
