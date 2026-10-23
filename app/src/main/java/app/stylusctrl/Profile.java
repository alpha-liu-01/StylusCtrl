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
    public double deadzone;
    public double ceiling = 1;
    /** Eight numbers: four cubic points, x and y, in the span between the two deadzones. */
    public double[] curve = new double[] {0, 0, 0.4, 0.7, 0.4, 0.7, 1, 1};
    public String stylusAction = "shortcut";
    public List<String> stylusKeys = new ArrayList<>();
    public String rubberAction = "eraser";
    public List<String> rubberKeys = new ArrayList<>();
    public boolean eraserWins = true;
    public boolean internalOn;

    public Profile() {
        stylusKeys.add("CTRL");
        stylusKeys.add("Z");
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
            int rotation = mapping.optInt("rotation", 0);
            if (rotation == 1) rotation = 90;
            else if (rotation == 2) rotation = 180;
            else if (rotation == 3) rotation = 270;
            if (rotation == 90 || rotation == 180 || rotation == 270) p.rotation = rotation;
        }
        JSONObject pressure = root.optJSONObject("pressure");
        if (pressure != null) {
            p.deadzone = pressure.optDouble("deadzone", 0);
            p.ceiling = pressure.optDouble("ceiling", 1);
            JSONArray curve = pressure.optJSONArray("curve");
            if (curve != null && curve.length() >= 6) {
                p.curve = new double[8];
                if (curve.length() >= 8) {
                    for (int i = 0; i < 8; i++) p.curve[i] = curve.getDouble(i);
                } else {
                    p.curve[0] = curve.getDouble(0);
                    p.curve[1] = curve.getDouble(1);
                    p.curve[2] = curve.getDouble(2);
                    p.curve[3] = curve.getDouble(3);
                    p.curve[4] = curve.getDouble(2);
                    p.curve[5] = curve.getDouble(3);
                    p.curve[6] = curve.getDouble(4);
                    p.curve[7] = curve.getDouble(5);
                }
            }
        }
        JSONObject buttons = root.optJSONObject("buttons");
        if (buttons != null) {
            readButton(buttons.optJSONObject("BTN_STYLUS"), true, p);
            readButton(buttons.optJSONObject("BTN_TOOL_RUBBER"), false, p);
        }
        JSONObject tools = root.optJSONObject("tools");
        if (tools != null) p.eraserWins = tools.optBoolean("eraserWins", true);
        JSONObject internal = root.optJSONObject("internal");
        if (internal != null) p.internalOn = internal.optBoolean("enabled", false);
        return p;
    }

    private static void readButton(JSONObject obj, boolean stylus, Profile p) throws Exception {
        if (obj == null) return;
        String action = obj.optString("action", stylus ? "passthrough" : "eraser");
        List<String> keys = new ArrayList<>();
        JSONArray arr = obj.optJSONArray("keys");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) keys.add(arr.getString(i));
        }
        if (stylus) {
            p.stylusAction = action;
            if (!keys.isEmpty()) p.stylusKeys = keys;
        } else {
            p.rubberAction = action;
            p.rubberKeys = keys;
        }
    }

    public String toJson() {
        StringBuilder b = new StringBuilder();
        b.append("{\n");
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
        b.append(" },\n");
        b.append("  \"pressure\": { \"curve\": [");
        for (int i = 0; i < 8; i++) {
            if (i > 0) b.append(", ");
            b.append(String.format(Locale.US, "%.4f", curve[i]));
        }
        b.append("], \"deadzone\": ");
        b.append(String.format(Locale.US, "%.4f", deadzone));
        b.append(", \"ceiling\": ");
        b.append(String.format(Locale.US, "%.4f", ceiling));
        b.append(" },\n");
        b.append("  \"buttons\": {\n");
        b.append("    \"BTN_STYLUS\": ").append(buttonJson(stylusAction, stylusKeys)).append(",\n");
        b.append("    \"BTN_TOOL_RUBBER\": ").append(buttonJson(rubberAction, rubberKeys)).append("\n");
        b.append("  },\n");
        b.append("  \"tools\": { \"eraserWins\": ").append(eraserWins).append(" },\n");
        b.append("  \"internal\": { \"enabled\": ").append(internalOn).append(" }\n");
        b.append("}\n");
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
}
