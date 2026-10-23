#!/system/bin/sh
# late_start. Magisk does not wait for this script. The loop is the supervisor:
# boot starts the daemon, and a crash starts it again.
MODDIR=${0%/*}
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
