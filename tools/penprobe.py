#!/usr/bin/env python3
"""Decode pen, side-button, and eraser events from a P620 over adb.

Passive: getevent does not grab the device, so the pen still draws.
One line is printed when the tool, tip, side button, or pressure band changes.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import threading
import time

LINE = re.compile(r"\[\s*([0-9.]+)\]\s+(\S+)\s+(\S+)\s+(\S+)")

TOOL_KEYS = {
    # Android getevent labels code 0x140 as BTN_DIGI. It is BTN_TOOL_PEN.
    "BTN_DIGI": "pen",
    "BTN_TOOL_PEN": "pen",
    "BTN_TOOL_RUBBER": "eraser",
    "BTN_TOOL_PENCIL": "pencil",
    "BTN_TOOL_BRUSH": "brush",
    "BTN_TOOL_AIRBRUSH": "airbrush",
}
SIDE_KEYS = ("BTN_STYLUS", "BTN_STYLUS2", "BTN_STYLUS3")


def adb(serial: str, *args: str) -> str:
    result = subprocess.run(
        ["adb", "-s", serial, *args],
        check=True,
        capture_output=True,
        text=True,
    )
    return result.stdout


def discover(serial: str) -> list[tuple[str, str]]:
    """Return (role, /dev/input/eventN) pairs."""
    listing = adb(
        serial,
        "shell",
        "for e in /sys/class/input/event*; do"
        " n=$(basename \"$e\");"
        " name=$(cat \"$e/device/name\" 2>/dev/null);"
        " props=$(cat \"$e/device/properties\" 2>/dev/null);"
        " printf '%s\\t%s\\t%s\\n' \"$n\" \"$name\" \"$props\";"
        " done",
    )
    found: list[tuple[str, str]] = []
    wacom_plain: list[tuple[str, str]] = []
    for raw in listing.splitlines():
        parts = raw.split("\t")
        if len(parts) < 2:
            continue
        node, name = parts[0], parts[1]
        props = parts[2].strip() if len(parts) > 2 else ""
        path = f"/dev/input/{node}"
        pointer = False
        if props:
            try:
                pointer = int(props, 16) & 0x1 != 0
            except ValueError:
                pointer = False
        if name == "sec_e-pen":
            found.append(("internal", path))
        elif "System Control" in name:
            found.append(("tablet-keys", path))
        elif name.startswith("Wacom "):
            wacom_plain.append((path, "pointer" if pointer else "other"))
    for path, kind in wacom_plain:
        found.append(("tablet" if kind == "pointer" else "tablet-vendor", path))
    return found


class Decoder(threading.Thread):
    def __init__(self, serial: str, role: str, path: str) -> None:
        super().__init__(daemon=True)
        self.serial = serial
        self.role = role
        self.path = path
        self.proc: subprocess.Popen[bytes] | None = None
        self.abs: dict[str, int] = {}
        self.keys: dict[str, int] = {}
        self.last_sig: tuple | None = None
        self.last_print = 0.0

    def stop(self) -> None:
        if self.proc and self.proc.poll() is None:
            self.proc.terminate()

    def run(self) -> None:
        self.proc = subprocess.Popen(
            ["adb", "-s", self.serial, "shell", "getevent", "-lt", self.path],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            bufsize=0,
        )
        assert self.proc.stdout is not None
        buf = b""
        while True:
            chunk = self.proc.stdout.read(256)
            if not chunk:
                break
            buf += chunk
            while b"\n" in buf:
                line, buf = buf.split(b"\n", 1)
                self._handle(line.decode("utf-8", "replace").strip())

    def _handle(self, line: str) -> None:
        match = LINE.search(line)
        if not match:
            if line:
                emit(f"{self.role} raw {line}")
            return
        _boot, ev_type, code, value = match.groups()
        if ev_type == "EV_ABS":
            raw = int(value, 16)
            if raw >= 0x80000000:
                raw -= 0x100000000
            self.abs[code] = raw
            return
        if ev_type == "EV_KEY":
            if value in ("DOWN", "UP"):
                down = value == "DOWN"
            else:
                down = int(value, 16) != 0
            self.keys[code] = 1 if down else 0
            if code not in TOOL_KEYS and code not in SIDE_KEYS and code != "BTN_TOUCH":
                emit(
                    f"{self.role} key {code} {'down' if down else 'up'}"
                    f" xy={self._xy()} pressure={self.abs.get('ABS_PRESSURE', 0)}"
                )
            return
        if ev_type == "EV_SW":
            emit(f"{self.role} switch {code} {value}")
            return
        if ev_type == "EV_SYN" and code == "SYN_REPORT":
            self._report()

    def _tool(self) -> str:
        if self.keys.get("BTN_TOOL_RUBBER"):
            return "eraser"
        for key, name in TOOL_KEYS.items():
            if self.keys.get(key):
                return name
        return "none"

    def _xy(self) -> str:
        return f"{self.abs.get('ABS_X', '-')},{self.abs.get('ABS_Y', '-')}"

    def _report(self) -> None:
        pressure = self.abs.get("ABS_PRESSURE", 0)
        tool = self._tool()
        touch = "down" if self.keys.get("BTN_TOUCH") else "up"
        side_names = []
        if self.keys.get("BTN_STYLUS"):
            side_names.append("side1")
        if self.keys.get("BTN_STYLUS2"):
            side_names.append("side2")
        if self.keys.get("BTN_STYLUS3"):
            side_names.append("side3")
        side = "+".join(side_names) if side_names else "up"
        sig = (tool, touch, side, 0 if pressure == 0 else 1)
        now = time.monotonic()
        interesting = sig != self.last_sig
        heartbeat = tool != "none" and now - self.last_print >= 0.4
        if not interesting and not heartbeat:
            return
        self.last_sig = sig
        self.last_print = now
        tilt = f"{self.abs.get('ABS_TILT_X', '-')},{self.abs.get('ABS_TILT_Y', '-')}"
        distance = self.abs.get("ABS_DISTANCE", "-")
        emit(
            f"{self.role} tool={tool} touch={touch} side={side}"
            f" pressure={pressure} distance={distance} tilt={tilt} xy={self._xy()}"
        )


def emit(text: str) -> None:
    stamp = time.strftime("%H:%M:%S")
    print(f"{stamp} {text}", flush=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    args = parser.parse_args()
    devices = discover(args.serial)
    if not devices:
        print("no pen nodes found", file=sys.stderr)
        return 1
    for role, path in devices:
        emit(f"listen {role} {path}")
    threads = [Decoder(args.serial, role, path) for role, path in devices]
    for thread in threads:
        thread.start()
    try:
        while any(thread.is_alive() for thread in threads):
            time.sleep(0.5)
    except KeyboardInterrupt:
        for thread in threads:
            thread.stop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
