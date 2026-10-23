#!/system/bin/sh
# late_start. Magisk does not wait for this script. The loop is the supervisor:
# boot starts the daemon, and a crash starts it again.
MODDIR=${0%/*}
# Magisk shows one description. locale/<tag>.prop holds translations.
# A new language is another file there; en.prop is the fallback.
apply_module_locale() {
    locale=$(getprop persist.sys.locale 2>/dev/null)
    if [ -z "$locale" ]; then
        locale=$(getprop ro.product.locale 2>/dev/null)
    fi
    tag=$(printf '%s' "$locale" | tr '[:upper:]' '[:lower:]' | tr '_' '-')
    lang=$(printf '%s' "$tag" | cut -c 1-2)
    src=""
    if [ -n "$tag" ] && [ -f "$MODDIR/locale/${tag}.prop" ]; then
        src="$MODDIR/locale/${tag}.prop"
    elif [ -n "$lang" ] && [ -f "$MODDIR/locale/${lang}.prop" ]; then
        src="$MODDIR/locale/${lang}.prop"
    elif [ -f "$MODDIR/locale/en.prop" ]; then
        src="$MODDIR/locale/en.prop"
    fi
    if [ -z "$src" ]; then
        return 0
    fi
    name=$(sed -n 's/^name=//p' "$src" | head -n 1)
    desc=$(sed -n 's/^description=//p' "$src" | head -n 1)
    id=$(sed -n 's/^id=//p' "$MODDIR/module.prop" | head -n 1)
    version=$(sed -n 's/^version=//p' "$MODDIR/module.prop" | head -n 1)
    versionCode=$(sed -n 's/^versionCode=//p' "$MODDIR/module.prop" | head -n 1)
    author=$(sed -n 's/^author=//p' "$MODDIR/module.prop" | head -n 1)
    if [ -z "$name" ] || [ -z "$desc" ] || [ -z "$id" ]; then
        return 0
    fi
    cat > "$MODDIR/module.prop" <<EOF
id=$id
name=$name
version=$version
versionCode=$versionCode
author=$author
description=$desc
EOF
}
apply_module_locale
LOGDIR=/data/adb/stylusctrl
mkdir -p "$LOGDIR"
if [ ! -f "$LOGDIR/config.json" ]; then
    cp "$MODDIR/config.json.default" "$LOGDIR/config.json"
fi
# Magisk install_module sets ordinary files to 0644. The shell can still
# run this script; exec of stylusctld needs the bit.
chmod 755 "$MODDIR/stylusctld" 2>/dev/null || true
BIN="$MODDIR/stylusctld"
if [ ! -x "$BIN" ]; then
    cp "$MODDIR/stylusctld" "$LOGDIR/stylusctld"
    chmod 755 "$LOGDIR/stylusctld"
    BIN="$LOGDIR/stylusctld"
fi
while true; do
    "$BIN" >>"$LOGDIR/stylusctld.log" 2>&1
    sleep 2
done
