package app.stylusctrl;

import android.util.Base64;

import com.topjohnwu.superuser.Shell;

import java.nio.charset.StandardCharsets;
import java.util.List;

public final class RootStore {
    public static final String CONFIG_PATH = "/data/adb/stylusctrl/config.json";

    private RootStore() {}

    public static final class Snapshot {
        public boolean root;
        public String configText;
        public String daemonPid;
        public String devices;
        public String display;
        public String error;
        public int tabletMaxX = 15200;
        public int tabletMaxY = 9500;
        public int screenW;
        public int screenH;
    }

    public static Snapshot read() {
        Snapshot snap = new Snapshot();
        try {
            Shell.Result id = Shell.cmd("id -u").exec();
            snap.root = id.isSuccess() && !id.getOut().isEmpty() && "0".equals(id.getOut().get(0).trim());
            if (!snap.root) {
                snap.error = id.getErr().isEmpty() ? "没有 root" : join(id.getErr());
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
            Shell.Result ev = Shell.cmd("getevent -p").exec();
            snap.devices = join(ev.getOut());
            readAxes(snap);
            Shell.Result display = Shell.cmd("dumpsys display").exec();
            snap.display = join(display.getOut());
            readDisplay(snap);
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

    public static String write(String json) {
        try {
            String b64 = Base64.encodeToString(json.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            Shell.Result result = Shell.cmd(
                    "mkdir -p /data/adb/stylusctrl",
                    "printf '%s' '" + b64 + "' | base64 -d > " + CONFIG_PATH,
                    "chmod 644 " + CONFIG_PATH,
                    "if pidof stylusctld >/dev/null 2>&1; then kill -USR1 $(pidof stylusctld); echo notified; else echo stopped; fi"
            ).exec();
            if (!result.isSuccess()) {
                return result.getErr().isEmpty() ? "写入失败" : join(result.getErr());
            }
            String last = result.getOut().isEmpty() ? "" : result.getOut().get(result.getOut().size() - 1).trim();
            if ("notified".equals(last)) return "已写入，守护进程会重读";
            if ("stopped".equals(last)) return "已写入。守护进程没在跑，装上模块并重启后才会生效";
            return "已写入";
        } catch (Exception e) {
            return e.getMessage() == null ? e.toString() : e.getMessage();
        }
    }

    private static void readAxes(Snapshot snap) {
        if (snap.devices == null) return;
        String name = "";
        boolean pen = false;
        for (String line : snap.devices.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("name:")) {
                name = trimmed.substring(trimmed.indexOf('"') + 1);
                if (name.endsWith("\"")) name = name.substring(0, name.length() - 1);
                pen = name.contains("Wacom") && !name.startsWith("StylusCtrl");
            } else if (pen && trimmed.startsWith("ABS_X")) {
                int max = axisMax(trimmed);
                if (max > 0) snap.tabletMaxX = max;
            } else if (pen && trimmed.startsWith("ABS_Y")) {
                int max = axisMax(trimmed);
                if (max > 0) snap.tabletMaxY = max;
            }
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

    public static String axesSummary(String getevent) {
        if (getevent == null || getevent.isEmpty()) return "没有读到输入设备";
        StringBuilder out = new StringBuilder();
        String name = "";
        boolean interesting = false;
        for (String line : getevent.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("name:")) {
                name = trimmed.substring(trimmed.indexOf('"') + 1);
                if (name.endsWith("\"")) name = name.substring(0, name.length() - 1);
                interesting = name.contains("Wacom") || name.contains("sec_e-pen") || name.startsWith("StylusCtrl");
                if (interesting) {
                    if (out.length() > 0) out.append("\n");
                    out.append(name);
                }
            } else if (interesting && (trimmed.startsWith("ABS_X") || trimmed.startsWith("ABS_Y")
                    || trimmed.startsWith("ABS_PRESSURE"))) {
                out.append("\n  ").append(trimmed);
            }
        }
        return out.length() == 0 ? "没有看到数位板或内屏笔" : out.toString();
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
