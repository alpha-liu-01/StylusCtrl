package app.stylusctrl;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The daemon's json profile. Unknown keys are dropped on save. */
public class Profile {
    public String vendor = "0531";
    public String product = "0104";
    public String mode = "fit";
    public String crop = "center";
    public boolean hasRect;
    public int[] rect = new int[] {0, 0, 15200, 9500};
    public boolean hasScreen;
    public double[] screen = new double[] {0, 0, 1, 1};
    /** 0, 90, 180, or 270. The tablet's own rotation, independent of the screen. */
    public int rotation;
    /** Pixel size and Surface rotation (0–3) of the internal display. The daemon maps from this. */
    public boolean hasDisplay;
    public int displayW;
    public int displayH;
    public int displayRotation;
    /** Axis max the rect was edited against. Missing on old files; the app assumes 15200×9500. */
    public boolean hasSpan;
    public int spanX = 15200;
    public int spanY = 9500;
    public Feel external = Feel.tabletDefault();
    public Feel internal = new Feel();
    public boolean internalOn;

    /** Pressure curve and side buttons for one pen. Mapping is not part of this. */
    public static final class Feel {
        public double deadzone;
        public double ceiling = 1;
        public double[] curve = new double[] {0, 0, 0.4, 0.7, 0.4, 0.7, 1, 1};
        public String stylusAction = "passthrough";
        public final List<String> stylusKeys = new ArrayList<>();
        public String rubberAction = "eraser";
        public final List<String> rubberKeys = new ArrayList<>();
        public boolean eraserWins = true;

        public static Feel tabletDefault() {
            Feel feel = new Feel();
            feel.stylusAction = "shortcut";
            feel.stylusKeys.add("CTRL");
            feel.stylusKeys.add("Z");
            return feel;
        }

        public void copyFrom(Feel other) {
            deadzone = other.deadzone;
            ceiling = other.ceiling;
            curve = other.curve.clone();
            stylusAction = other.stylusAction;
            stylusKeys.clear();
            stylusKeys.addAll(other.stylusKeys);
            rubberAction = other.rubberAction;
            rubberKeys.clear();
            rubberKeys.addAll(other.rubberKeys);
            eraserWins = other.eraserWins;
        }
    }

    public static Profile parse(String text) throws Exception {
        JSONObject root = new JSONObject(text);
        Profile p = new Profile();
        JSONObject match = root.optJSONObject("match");
        if (match != null) {
            p.vendor = match.optString("vendor", p.vendor);
            p.product = match.optString("product", p.product);
        }
        JSONObject mapping = root.optJSONObject("mapping");
        if (mapping != null) {
            p.mode = mapping.optString("mode", p.mode);
            p.crop = mapping.optString("crop", p.crop);
            JSONArray rect = mapping.optJSONArray("rect");
            if (rect != null && rect.length() == 4) {
                p.hasRect = true;
                for (int i = 0; i < 4; i++) p.rect[i] = rect.getInt(i);
            }
            JSONArray screen = mapping.optJSONArray("screen");
            if (screen != null && screen.length() == 4) {
                p.hasScreen = true;
                for (int i = 0; i < 4; i++) p.screen[i] = screen.getDouble(i);
            }
            JSONArray span = mapping.optJSONArray("span");
            if (span != null && span.length() == 2) {
                p.hasSpan = true;
                p.spanX = span.getInt(0);
                p.spanY = span.getInt(1);
            }
            int rotation = mapping.optInt("rotation", 0);
            if (rotation == 1) rotation = 90;
            else if (rotation == 2) rotation = 180;
            else if (rotation == 3) rotation = 270;
            if (rotation == 90 || rotation == 180 || rotation == 270) p.rotation = rotation;
        }
        JSONObject display = root.optJSONObject("display");
        if (display != null) {
            p.displayW = display.optInt("width", 0);
            p.displayH = display.optInt("height", 0);
            int turn = display.optInt("rotation", 0);
            if (turn == 90) turn = 1;
            else if (turn == 180) turn = 2;
            else if (turn == 270) turn = 3;
            if (turn >= 0 && turn <= 3) p.displayRotation = turn;
            p.hasDisplay = p.displayW > 0 && p.displayH > 0;
        }
        readFeelPressure(root.optJSONObject("pressure"), p.external);
        readFeelButtons(root.optJSONObject("buttons"), root.optJSONObject("tools"), p.external);
        JSONObject internal = root.optJSONObject("internal");
        if (internal != null) {
            p.internalOn = internal.optBoolean("enabled", false);
            boolean ownPressure = readFeelPressure(internal.optJSONObject("pressure"), p.internal);
            boolean ownButtons = readFeelButtons(internal.optJSONObject("buttons"), internal.optJSONObject("tools"), p.internal);
            if (!ownPressure) copyPressure(p.external, p.internal);
            if (!ownButtons) copyButtons(p.external, p.internal);
        } else {
            p.internal.copyFrom(p.external);
        }
        return p;
    }

    private static boolean readFeelPressure(JSONObject pressure, Feel feel) throws Exception {
        if (pressure == null) return false;
        feel.deadzone = pressure.optDouble("deadzone", 0);
        feel.ceiling = pressure.optDouble("ceiling", 1);
        JSONArray curve = pressure.optJSONArray("curve");
        if (curve != null && curve.length() >= 6) {
            feel.curve = new double[8];
            if (curve.length() >= 8) {
                for (int i = 0; i < 8; i++) feel.curve[i] = curve.getDouble(i);
            } else {
                feel.curve[0] = curve.getDouble(0);
                feel.curve[1] = curve.getDouble(1);
                feel.curve[2] = curve.getDouble(2);
                feel.curve[3] = curve.getDouble(3);
                feel.curve[4] = curve.getDouble(2);
                feel.curve[5] = curve.getDouble(3);
                feel.curve[6] = curve.getDouble(4);
                feel.curve[7] = curve.getDouble(5);
            }
        }
        return true;
    }

    private static boolean readFeelButtons(JSONObject buttons, JSONObject tools, Feel feel) throws Exception {
        if (buttons == null && tools == null) return false;
        if (buttons != null) {
            readButton(buttons.optJSONObject("BTN_STYLUS"), true, feel);
            readButton(buttons.optJSONObject("BTN_TOOL_RUBBER"), false, feel);
        }
        if (tools != null) feel.eraserWins = tools.optBoolean("eraserWins", true);
        return true;
    }

    private static void copyPressure(Feel from, Feel to) {
        to.deadzone = from.deadzone;
        to.ceiling = from.ceiling;
        to.curve = from.curve.clone();
    }

    private static void copyButtons(Feel from, Feel to) {
        to.stylusAction = from.stylusAction;
        to.stylusKeys.clear();
        to.stylusKeys.addAll(from.stylusKeys);
        to.rubberAction = from.rubberAction;
        to.rubberKeys.clear();
        to.rubberKeys.addAll(from.rubberKeys);
        to.eraserWins = from.eraserWins;
    }

    private static void readButton(JSONObject obj, boolean stylus, Feel feel) throws Exception {
        if (obj == null) return;
        String action = obj.optString("action", stylus ? "passthrough" : "eraser");
        List<String> keys = new ArrayList<>();
        JSONArray arr = obj.optJSONArray("keys");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) keys.add(arr.getString(i));
        }
        if (stylus) {
            feel.stylusAction = action;
            if (!keys.isEmpty()) {
                feel.stylusKeys.clear();
                feel.stylusKeys.addAll(keys);
            }
        } else {
            feel.rubberAction = action;
            feel.rubberKeys.clear();
            feel.rubberKeys.addAll(keys);
        }
    }

    public String toJson() {
        StringBuilder b = new StringBuilder();
        b.append("{\n");
        if (hasDisplay) {
            b.append("  \"display\": { \"width\": ").append(displayW).append(", \"height\": ").append(displayH)
                    .append(", \"rotation\": ").append(displayRotation).append(" },\n");
        }
        b.append("  \"match\": { \"vendor\": \"").append(vendor).append("\", \"product\": \"")
                .append(product).append("\" },\n");
        b.append("  \"mapping\": { \"mode\": \"").append(mode).append("\", \"crop\": \"")
                .append(crop).append("\", \"rotation\": ").append(rotation);
        if (hasRect) {
            b.append(", \"rect\": [").append(rect[0]).append(", ").append(rect[1]).append(", ")
                    .append(rect[2]).append(", ").append(rect[3]).append("]");
        }
        if (hasScreen) {
            b.append(", \"screen\": [");
            for (int i = 0; i < 4; i++) {
                if (i > 0) b.append(", ");
                b.append(String.format(Locale.US, "%.4f", screen[i]));
            }
            b.append("]");
        }
        if (hasSpan) {
            b.append(", \"span\": [").append(spanX).append(", ").append(spanY).append("]");
        }
        b.append(" },\n");
        b.append("  \"pressure\": ").append(pressureJson(external)).append(",\n");
        b.append("  \"buttons\": ").append(buttonsJson(external)).append(",\n");
        b.append("  \"tools\": { \"eraserWins\": ").append(external.eraserWins).append(" },\n");
        b.append("  \"internal\": {\n");
        b.append("    \"enabled\": ").append(internalOn).append(",\n");
        b.append("    \"pressure\": ").append(pressureJson(internal)).append(",\n");
        b.append("    \"buttons\": ").append(buttonsJson(internal)).append(",\n");
        b.append("    \"tools\": { \"eraserWins\": ").append(internal.eraserWins).append(" }\n");
        b.append("  }\n");
        b.append("}\n");
        return b.toString();
    }

    private static String pressureJson(Feel feel) {
        StringBuilder b = new StringBuilder();
        b.append("{ \"curve\": [");
        for (int i = 0; i < 8; i++) {
            if (i > 0) b.append(", ");
            b.append(String.format(Locale.US, "%.4f", feel.curve[i]));
        }
        b.append("], \"deadzone\": ");
        b.append(String.format(Locale.US, "%.4f", feel.deadzone));
        b.append(", \"ceiling\": ");
        b.append(String.format(Locale.US, "%.4f", feel.ceiling));
        b.append(" }");
        return b.toString();
    }

    private static String buttonsJson(Feel feel) {
        StringBuilder b = new StringBuilder();
        b.append("{\n");
        b.append("    \"BTN_STYLUS\": ").append(buttonJson(feel.stylusAction, feel.stylusKeys)).append(",\n");
        b.append("    \"BTN_TOOL_RUBBER\": ").append(buttonJson(feel.rubberAction, feel.rubberKeys)).append("\n");
        b.append("  }");
        return b.toString();
    }

    private static String buttonJson(String action, List<String> keys) {
        StringBuilder b = new StringBuilder();
        b.append("{ \"action\": \"").append(action).append("\"");
        if ("shortcut".equals(action) && keys != null && !keys.isEmpty()) {
            b.append(", \"keys\": [");
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) b.append(", ");
                b.append("\"").append(keys.get(i)).append("\"");
            }
            b.append("]");
        }
        b.append(" }");
        return b.toString();
    }

    /** Rect is in the rotated tablet space. Full-span rects jump to the new full span; partial ones scale. */
    public static int[] scaleRect(int[] rect, int rotation, int oldX, int oldY, int newX, int newY) {
        boolean swap = rotation == 90 || rotation == 270;
        int oldW = swap ? oldY : oldX;
        int oldH = swap ? oldX : oldY;
        int newW = swap ? newY : newX;
        int newH = swap ? newX : newY;
        if (rect[0] <= 0 && rect[1] <= 0 && rect[2] >= oldW && rect[3] >= oldH) {
            return new int[] {0, 0, newW, newH};
        }
        return new int[] {
                scale(rect[0], oldW, newW),
                scale(rect[1], oldH, newH),
                scale(rect[2], oldW, newW),
                scale(rect[3], oldH, newH)
        };
    }

    private static int scale(int value, int oldMax, int newMax) {
        if (oldMax <= 0) return value;
        return (int) Math.round(value * (double) newMax / (double) oldMax);
    }
}
