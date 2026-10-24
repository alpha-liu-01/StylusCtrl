package app.stylusctrl;

import android.content.Context;
import android.util.Base64;

import com.topjohnwu.superuser.Shell;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RootStore {
    public static final String CONFIG_PATH = "/data/adb/stylusctrl/config.json";

    private RootStore() {}

    public static final class Pen {
        public String name = "";
        public String vendor = "";
        public String product = "";
        public boolean external;
        public boolean internal;
        public boolean pointer;
        public boolean direct;
        public boolean penTool;
        public boolean pressure;
        public boolean multitouch;
        public String bustype = "";
        public int maxX;
        public int maxY;
    }

    public static final class Snapshot {
        public boolean root;
        public String configText;
        public String daemonPid;
        public String devices;
        public String display;
        public String error;
        /** Settings live in the app directory. The system daemon reads that file. */
        public boolean local;
        public int tabletMaxX;
        public int tabletMaxY;
        public int screenW;
        public int screenH;
        public final List<Pen> pens = new ArrayList<>();
    }

    public static Snapshot read(Context context) {
        Snapshot snap = new Snapshot();
        try {
            snap.root = isRoot();
            if (!snap.root) {
                snap.local = true;
                snap.configText = readLocal(context);
                return snap;
            }
            Shell.Result cfg = Shell.cmd("base64 " + CONFIG_PATH).exec();
            if (cfg.isSuccess() && !cfg.getOut().isEmpty()) {
                String b64 = joinRaw(cfg.getOut());
                byte[] raw = Base64.decode(b64, Base64.DEFAULT);
                snap.configText = new String(raw, StandardCharsets.UTF_8);
            }
            Shell.Result pid = Shell.cmd("pidof stylusctld || true").exec();
            snap.daemonPid = pid.getOut().isEmpty() ? "" : pid.getOut().get(0).trim();
            Shell.Result ev = Shell.cmd(DEVICE_CMD).exec();
            applyDeviceDump(snap, join(ev.getOut()));
            Shell.Result display = Shell.cmd("dumpsys display").exec();
            snap.display = join(display.getOut());
            readDisplay(snap);
        } catch (Exception e) {
            snap.error = e.getMessage() == null ? e.toString() : e.getMessage();
        }
        return snap;
    }

    /** Names, axes, and ids only. Used while the screen is open to notice plug and unplug. */
    public static Snapshot readDevices() {
        Snapshot snap = new Snapshot();
        try {
            Shell.Result id = Shell.cmd("id -u").exec();
            snap.root = id.isSuccess() && !id.getOut().isEmpty() && "0".equals(id.getOut().get(0).trim());
            if (!snap.root) {
                snap.error = id.getErr().isEmpty() ? "no_root" : join(id.getErr());
                return snap;
            }
            Shell.Result ev = Shell.cmd(DEVICE_CMD).exec();
            applyDeviceDump(snap, join(ev.getOut()));
        } catch (Exception e) {
            snap.error = e.getMessage() == null ? e.toString() : e.getMessage();
        }
        return snap;
    }

    public static void rereadDisplay() {
        try {
            Shell.cmd("if pidof stylusctld >/dev/null 2>&1; then kill -USR1 $(pidof stylusctld); fi").exec();
        } catch (Exception ignored) {
        }
    }

    public static String write(Context context, String json) {
        try {
            if (!isRoot()) return writeLocal(context, json);
            String b64 = Base64.encodeToString(json.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            Shell.Result result = Shell.cmd(
                    "mkdir -p /data/adb/stylusctrl",
                    "printf '%s' '" + b64 + "' | base64 -d > " + CONFIG_PATH,
                    "chmod 644 " + CONFIG_PATH,
                    "if pidof stylusctld >/dev/null 2>&1; then kill -USR1 $(pidof stylusctld); echo notified; else echo stopped; fi"
            ).exec();
            if (!result.isSuccess()) {
                return result.getErr().isEmpty() ? "failed" : join(result.getErr());
            }
            String last = result.getOut().isEmpty() ? "" : result.getOut().get(result.getOut().size() - 1).trim();
            if ("notified".equals(last) || "stopped".equals(last)) return last;
            return "written";
        } catch (Exception e) {
            return e.getMessage() == null ? e.toString() : e.getMessage();
        }
    }

    private static boolean isRoot() {
        Shell.Result id = Shell.cmd("id -u").exec();
        return id.isSuccess() && !id.getOut().isEmpty() && "0".equals(id.getOut().get(0).trim());
    }

    private static File localConfig(Context context) {
        return new File(context.getFilesDir(), "config.json");
    }

    private static String readLocal(Context context) throws IOException {
        File file = localConfig(context);
        if (!file.isFile()) return null;
        byte[] raw = new byte[(int) file.length()];
        try (FileInputStream in = new FileInputStream(file)) {
            int off = 0;
            while (off < raw.length) {
                int n = in.read(raw, off, raw.length - off);
                if (n < 0) break;
                off += n;
            }
        }
        return new String(raw, StandardCharsets.UTF_8);
    }

    /** Atomic enough for one settings file. The daemon watches the mtime of config.json. */
    private static String writeLocal(Context context, String json) throws IOException {
        File dir = context.getFilesDir();
        File out = new File(dir, "config.json");
        File tmp = new File(dir, "config.json.tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(json.getBytes(StandardCharsets.UTF_8));
            fos.getFD().sync();
        }
        if (out.exists() && !out.delete()) return "failed";
        if (!tmp.renameTo(out)) return "failed";
        return "local";
    }

    private static final String DEVICE_CMD =
            "getevent -p; echo '##IDS##'; "
                    + "for ev in /sys/class/input/event*; do "
                    + "name=$(cat \"$ev/device/name\" 2>/dev/null | tr '\\n' ' '); "
                    + "vendor=$(cat \"$ev/device/id/vendor\" 2>/dev/null | tr -d '\\n'); "
                    + "product=$(cat \"$ev/device/id/product\" 2>/dev/null | tr -d '\\n'); "
                    + "bustype=$(cat \"$ev/device/id/bustype\" 2>/dev/null | tr -d '\\n'); "
                    + "printf '%s\\t%s\\t%s\\t%s\\n' \"$name\" \"$vendor\" \"$product\" \"$bustype\"; "
                    + "done";

    private static void applyDeviceDump(Snapshot snap, String dump) {
        if (dump == null) return;
        String events = dump;
        String ids = "";
        int mark = dump.indexOf("##IDS##");
        if (mark >= 0) {
            events = dump.substring(0, mark);
            ids = dump.substring(mark + "##IDS##".length());
        }
        snap.devices = events.trim();
        snap.pens.addAll(parseGetevent(snap.devices));
        attachIds(snap.pens, ids);
        for (Pen pen : snap.pens) classify(pen);
        for (Pen pen : snap.pens) {
            if (!pen.external) continue;
            snap.tabletMaxX = pen.maxX;
            snap.tabletMaxY = pen.maxY;
            break;
        }
    }

    static List<Pen> parseGetevent(String text) {
        List<Pen> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        Pen cur = null;
        boolean inProps = false;
        boolean sawPressure = false;
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (line.startsWith("add device")) {
                cur = new Pen();
                out.add(cur);
                inProps = false;
                sawPressure = false;
                continue;
            }
            if (cur == null) continue;
            if (trimmed.startsWith("name:")) {
                int q = trimmed.indexOf('"');
                String name = q >= 0 ? trimmed.substring(q + 1) : trimmed.substring(5).trim();
                if (name.endsWith("\"")) name = name.substring(0, name.length() - 1);
                cur.name = name.trim();
                inProps = false;
            } else if (trimmed.startsWith("input props")) {
                inProps = true;
            } else if (inProps && trimmed.startsWith("INPUT_PROP_POINTER")) {
                cur.pointer = true;
            } else if (inProps && trimmed.startsWith("INPUT_PROP_DIRECT")) {
                cur.direct = true;
            } else if (axisCode(trimmed) == 0x00) {
                int max = axisMax(trimmed);
                if (max > 0) cur.maxX = max;
            } else if (axisCode(trimmed) == 0x01) {
                int max = axisMax(trimmed);
                if (max > 0) cur.maxY = max;
            } else if (axisCode(trimmed) == 0x18) {
                sawPressure = true;
                cur.pressure = true;
            } else if (trimmed.contains("BTN_DIGI") || trimmed.contains("BTN_TOOL_PEN")) {
                cur.penTool = true;
            } else if (trimmed.contains("ABS_MT_SLOT") || trimmed.contains("ABS_MT_POSITION_X")) {
                cur.multitouch = true;
            } else if (trimmed.endsWith(":") && !trimmed.startsWith("ABS")) {
                inProps = false;
            }
            cur.pressure = sawPressure;
        }
        return out;
    }

    /** Same rule as stylusctld is_builtin_pen. An empty bus falls back to the i2c-hid name. */
    static void classify(Pen pen) {
        boolean builtin = false;
        if (!pen.name.startsWith("StylusCtrl")) {
            if ("sec_e-pen".equals(pen.name)) {
                builtin = true;
            } else if (pen.direct && pen.penTool && pen.pressure && !pen.multitouch && pen.maxX > 0 && pen.maxY > 0) {
                String bus = pen.bustype == null ? "" : pen.bustype.trim().toLowerCase(Locale.US);
                if (bus.startsWith("0x")) bus = bus.substring(2);
                if (bus.isEmpty()) builtin = pen.name.startsWith("hid-over-i2c ");
                else builtin = "18".equals(bus) || "0018".equals(bus);
            }
        }
        pen.internal = builtin;
        pen.external = pen.pointer && !pen.direct && pen.pressure && pen.maxX > 0 && pen.maxY > 0
                && !pen.name.startsWith("StylusCtrl") && !builtin;
    }

    private static void attachIds(List<Pen> pens, String table) {
        List<String[]> rows = new ArrayList<>();
        for (String line : table.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            String[] parts = trimmed.split("\t");
            if (parts.length < 3) continue;
            rows.add(parts);
        }
        boolean[] used = new boolean[rows.size()];
        for (Pen pen : pens) {
            for (int i = 0; i < rows.size(); i++) {
                if (used[i]) continue;
                if (!pen.name.equals(rows.get(i)[0].trim())) continue;
                used[i] = true;
                pen.vendor = hex4(rows.get(i)[1]);
                pen.product = hex4(rows.get(i)[2]);
                if (rows.get(i).length >= 4) pen.bustype = rows.get(i)[3].trim();
                break;
            }
        }
    }

    static String hex4(String raw) {
        if (raw == null) return "";
        String s = raw.trim().toLowerCase(Locale.US);
        if (s.startsWith("0x")) s = s.substring(2);
        if (s.isEmpty()) return "";
        try {
            int value = Integer.parseInt(s, 16);
            return String.format(Locale.US, "%04x", value);
        } catch (NumberFormatException e) {
            return s;
        }
    }

    private static final Pattern AXIS_CODE = Pattern.compile("(?:ABS_X\\b|ABS_Y\\b|ABS_PRESSURE\\b|([0-9a-fA-F]{4}))\\s+:\\s+value");

    /** ABS_X/Y/PRESSURE, or the hex codes this tablet's getevent prints (0000, 0001, 0018). */
    private static int axisCode(String line) {
        if (line.startsWith("ABS_X")) return 0x00;
        if (line.startsWith("ABS_Y")) return 0x01;
        if (line.startsWith("ABS_PRESSURE")) return 0x18;
        Matcher matcher = AXIS_CODE.matcher(line);
        if (!matcher.find() || matcher.group(1) == null) return -1;
        try {
            return Integer.parseInt(matcher.group(1), 16);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int axisMax(String line) {
        int at = line.indexOf("max ");
        if (at < 0) return -1;
        int start = at + 4;
        int end = start;
        while (end < line.length() && (Character.isDigit(line.charAt(end)) || line.charAt(end) == '-')) end++;
        try {
            return Integer.parseInt(line.substring(start, end));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void readDisplay(Snapshot snap) {
        if (snap.display == null) return;
        int from = 0;
        int best = -1;
        boolean bestActive = false;
        while (true) {
            int at = snap.display.indexOf("type=INTERNAL", from);
            if (at < 0) break;
            int end = snap.display.indexOf('}', at);
            if (end < 0) end = Math.min(snap.display.length(), at + 4000);
            String slice = snap.display.substring(at, end);
            boolean active = slice.contains("isActive=true");
            if (best < 0 || (active && !bestActive)) {
                best = at;
                bestActive = active;
            }
            if (active) break;
            from = at + 13;
        }
        if (best < 0 || !bestActive) return;
        int end = snap.display.indexOf('}', best);
        String slice = snap.display.substring(best, end < 0 ? snap.display.length() : end);
        int frame = slice.indexOf("logicalFrame=Rect(");
        if (frame < 0) return;
        String body = slice.substring(frame + "logicalFrame=Rect(".length());
        String[] parts = body.split("[^0-9-]+");
        int[] nums = new int[4];
        int n = 0;
        for (String part : parts) {
            if (part.isEmpty() || "-".equals(part)) continue;
            try {
                nums[n++] = Integer.parseInt(part);
            } catch (NumberFormatException e) {
                return;
            }
            if (n == 4) break;
        }
        if (n < 4) return;
        int w = nums[2] - nums[0];
        int h = nums[3] - nums[1];
        if (w > 0 && h > 0) {
            snap.screenW = w;
            snap.screenH = h;
        }
    }

    public static String axesSummary(Context context, List<Pen> pens) {
        if (pens == null || pens.isEmpty()) return context.getString(R.string.no_input_devices);
        StringBuilder out = new StringBuilder();
        for (Pen pen : pens) {
            boolean show = pen.external || pen.internal || pen.name.startsWith("StylusCtrl");
            if (!show) continue;
            if (out.length() > 0) out.append("\n");
            if (pen.external) out.append(context.getString(R.string.pen_external_short)).append(' ');
            else if (pen.internal) out.append(context.getString(R.string.pen_internal_short)).append(' ');
            out.append(pen.name);
            if (!pen.vendor.isEmpty() && !pen.product.isEmpty()) {
                out.append("  ").append(pen.vendor).append(":").append(pen.product);
            }
            if (pen.maxX > 0 && pen.maxY > 0) {
                out.append("\n  X 0–").append(pen.maxX).append("  Y 0–").append(pen.maxY);
            }
        }
        return out.length() == 0 ? context.getString(R.string.no_pen) : out.toString();
    }

    private static String join(List<String> lines) {
        StringBuilder b = new StringBuilder();
        for (String line : lines) {
            if (b.length() > 0) b.append('\n');
            b.append(line);
        }
        return b.toString();
    }

    private static String joinRaw(List<String> lines) {
        StringBuilder b = new StringBuilder();
        for (String line : lines) b.append(line.trim());
        return b.toString();
    }
}
