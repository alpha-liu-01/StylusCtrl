package app.stylusctrl;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Named feels on disk, plus which preset each pen and package uses. The daemon reads the copy embedded in config.json. */
public final class PresetStore {
    public static final int MAX_PRESETS = 8;
    public static final int MAX_OVERRIDES = 8;

    public static final class Item {
        public String id = "";
        public String name = "";
        public String mode = "fit";
        public String crop = "center";
        public int rotation;
        public boolean hasRect;
        public int[] rect = new int[] {0, 0, 15200, 9500};
        public boolean hasScreen;
        public double[] screen = new double[] {0, 0, 1, 1};
        public boolean hasSpan;
        public int spanX = 15200;
        public int spanY = 9500;
        public Profile.Feel external = new Profile.Feel();
        public Profile.Feel internal = new Profile.Feel();

        public void copyFrom(Item other) {
            mode = other.mode;
            crop = other.crop;
            rotation = other.rotation;
            hasRect = other.hasRect;
            rect = other.rect.clone();
            hasScreen = other.hasScreen;
            screen = other.screen.clone();
            hasSpan = other.hasSpan;
            spanX = other.spanX;
            spanY = other.spanY;
            external.copyFrom(other.external);
            internal.copyFrom(other.internal);
        }
    }

    public static final class Device {
        public String id = "";
        public String vendor = "";
        public String product = "";
        public String name = "";
        public String namePrefix = "";
        public String bus = "";
        public boolean directSet;
        public boolean direct;
        public boolean penSet;
        public boolean pen;
        public boolean multitouchSet;
        public boolean multitouch;
        public String baseline = "";
        public final LinkedHashMap<String, String> apps = new LinkedHashMap<>();

        public boolean internal() {
            return "internal".equals(id);
        }
    }

    private static final List<Item> items = new ArrayList<>();
    private static final List<Device> devices = new ArrayList<>();
    private static String active = "";
    private static int next = 1;
    private static boolean ready;
    private static File dir;

    private PresetStore() {}

    public static synchronized List<Item> items() {
        return new ArrayList<>(items);
    }

    public static synchronized List<Device> devices() {
        return new ArrayList<>(devices);
    }

    public static synchronized Item find(String id) {
        for (Item item : items) {
            if (item.id.equals(id)) return item;
        }
        return null;
    }

    public static synchronized Device device(String id) {
        for (Device device : devices) {
            if (device.id.equals(id)) return device;
        }
        return null;
    }

    /** Load the library. A config that already has presets replaces the feel bodies and keeps local names. */
    public static synchronized void install(Context context, Profile profile, String currentName) {
        dir = new File(context.getFilesDir(), "presets");
        if (!ready) loadDisk();
        if (profile.hasPresetTable) merge(profile);
        else if (items.isEmpty()) seed(profile, currentName);
        if (devices.isEmpty()) seedDevices(profile);
        syncExternal(profile);
        ensureBaselines();
        saveDisk();
        ready = true;
    }

    /** Writes the editor into this preset. A missing id is left alone so another preset is not overwritten. */
    public static synchronized void capture(Profile profile, String presetId) {
        if (!ready) return;
        Item item = find(presetId);
        if (item == null) return;
        syncExternal(profile);
        profile.copyFeelTo(item);
        active = item.id;
        saveDisk();
    }

    /** A new preset copied from an existing one. The source stays as it is. */
    public static synchronized Item fork(String sourceId, String name) {
        String trimmed = name == null ? "" : name.trim();
        Item src = find(sourceId);
        if (src == null || trimmed.isEmpty() || items.size() >= MAX_PRESETS) return null;
        Item item = new Item();
        item.id = "p" + next++;
        item.name = trimmed;
        item.copyFrom(src);
        items.add(item);
        saveDisk();
        return item;
    }

    public static synchronized boolean canAdd() {
        return items.size() < MAX_PRESETS;
    }

    public static synchronized boolean rename(String id, String name) {
        Item item = find(id);
        String trimmed = name == null ? "" : name.trim();
        if (item == null || trimmed.isEmpty()) return false;
        item.name = trimmed;
        try {
            saveIndex();
        } catch (Exception ignored) {
            return false;
        }
        return true;
    }

    /** Drops the library entry. Baselines that pointed at it are left so the live file can stay until the next write. */
    public static synchronized boolean delete(String id) {
        Item item = find(id);
        if (item == null) return false;
        items.remove(item);
        if (id.equals(active)) active = "";
        for (Device device : devices) {
            device.apps.entrySet().removeIf(entry -> id.equals(entry.getValue()));
        }
        ensureBaselines();
        saveDisk();
        if (dir != null) new File(dir, id + ".json").delete();
        return true;
    }

    public static synchronized boolean addOverride(String deviceId, String pkg, String presetId) {
        Device device = device(deviceId);
        String trimmed = pkg == null ? "" : pkg.trim();
        if (device == null || trimmed.isEmpty() || find(presetId) == null) return false;
        if (!device.apps.containsKey(trimmed) && device.apps.size() >= MAX_OVERRIDES) return false;
        device.apps.put(trimmed, presetId);
        saveDisk();
        return true;
    }

    public static synchronized void removeOverride(String deviceId, String pkg) {
        Device device = device(deviceId);
        if (device == null) return;
        device.apps.remove(pkg);
        saveDisk();
    }

    /** Called while building config.json. No leading comma when the library is not open yet. */
    public static synchronized void appendConfig(StringBuilder b) {
        if (!ready || items.isEmpty()) return;
        b.append(",\n  \"presets\": {\n");
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            if (i > 0) b.append(",\n");
            b.append("    \"").append(Profile.jsonString(item.id)).append("\": ").append(Profile.presetBody(item));
        }
        b.append("\n  },\n  \"devices\": [\n");
        for (int i = 0; i < devices.size(); i++) {
            if (i > 0) b.append(",\n");
            b.append("    ").append(deviceJson(devices.get(i)));
        }
        b.append("\n  ]");
    }

    private static void seed(Profile profile, String currentName) {
        Item item = new Item();
        item.id = "p" + next++;
        item.name = currentName == null || currentName.isEmpty() ? "Current" : currentName;
        profile.copyFeelTo(item);
        items.add(item);
        active = item.id;
    }

    private static void seedDevices(Profile profile) {
        Device internal = new Device();
        internal.id = "internal";
        internal.baseline = active;
        devices.add(internal);
        Device external = new Device();
        external.id = "external";
        external.baseline = active;
        external.vendor = profile.vendor;
        external.product = profile.product;
        external.name = profile.matchName == null ? "" : profile.matchName;
        devices.add(external);
    }

    private static void merge(Profile profile) {
        Map<String, String> names = new LinkedHashMap<>();
        for (Item item : items) names.put(item.id, item.name);
        String previous = active;
        items.clear();
        for (Item parsed : profile.parsedPresets) {
            String kept = names.get(parsed.id);
            if (kept != null && !kept.isEmpty()) parsed.name = kept;
            items.add(parsed);
            int n = presetNumber(parsed.id);
            if (n >= next) next = n + 1;
        }
        if (!profile.parsedDevices.isEmpty()) {
            devices.clear();
            devices.addAll(profile.parsedDevices);
        }
        if (find(previous) != null) active = previous;
        else if (!items.isEmpty()) active = items.get(0).id;
    }

    private static int presetNumber(String id) {
        if (id == null || !id.startsWith("p")) return 0;
        try {
            return Integer.parseInt(id.substring(1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void syncExternal(Profile profile) {
        Device external = device("external");
        if (external == null) {
            external = new Device();
            external.id = "external";
            external.baseline = active;
            devices.add(external);
        }
        external.vendor = profile.vendor;
        external.product = profile.product;
        external.name = profile.matchName == null ? "" : profile.matchName;
        if (device("internal") == null) {
            Device internal = new Device();
            internal.id = "internal";
            internal.baseline = active;
            devices.add(0, internal);
        }
    }

    private static void ensureBaselines() {
        if ((active.isEmpty() || find(active) == null) && !items.isEmpty()) active = items.get(0).id;
        for (Device device : devices) {
            if (find(device.baseline) == null && !active.isEmpty()) device.baseline = active;
        }
    }

    private static String deviceJson(Device device) {
        StringBuilder b = new StringBuilder();
        b.append("{ \"id\": \"").append(Profile.jsonString(device.id)).append("\"");
        if (!device.internal()) {
            b.append(", \"match\": { ");
            boolean comma = false;
            comma = field(b, comma, "vendor", device.vendor);
            comma = field(b, comma, "product", device.product);
            comma = field(b, comma, "name", device.name);
            comma = field(b, comma, "namePrefix", device.namePrefix);
            comma = field(b, comma, "bus", device.bus);
            if (!comma) b.append("\"vendor\": \"0531\", \"product\": \"0104\"");
            if (device.directSet) b.append(", \"direct\": ").append(device.direct);
            if (device.penSet) b.append(", \"pen\": ").append(device.pen);
            if (device.multitouchSet) b.append(", \"multitouch\": ").append(device.multitouch);
            b.append(" }");
        } else if (hasMatch(device)) {
            b.append(", \"match\": { ");
            boolean comma = false;
            comma = field(b, comma, "name", device.name);
            comma = field(b, comma, "namePrefix", device.namePrefix);
            comma = field(b, comma, "vendor", device.vendor);
            comma = field(b, comma, "product", device.product);
            comma = field(b, comma, "bus", device.bus);
            if (device.directSet) {
                if (comma) b.append(", ");
                b.append("\"direct\": ").append(device.direct);
                comma = true;
            }
            if (device.penSet) {
                if (comma) b.append(", ");
                b.append("\"pen\": ").append(device.pen);
                comma = true;
            }
            if (device.multitouchSet) {
                if (comma) b.append(", ");
                b.append("\"multitouch\": ").append(device.multitouch);
            }
            b.append(" }");
        }
        b.append(", \"baseline\": \"").append(Profile.jsonString(device.baseline)).append("\"");
        b.append(", \"apps\": {");
        boolean first = true;
        for (Map.Entry<String, String> entry : device.apps.entrySet()) {
            if (!first) b.append(", ");
            first = false;
            b.append("\"").append(Profile.jsonString(entry.getKey())).append("\": \"")
                    .append(Profile.jsonString(entry.getValue())).append("\"");
        }
        b.append("} }");
        return b.toString();
    }

    private static boolean hasMatch(Device device) {
        return notEmpty(device.name) || notEmpty(device.namePrefix) || notEmpty(device.vendor) || notEmpty(device.product)
                || notEmpty(device.bus) || device.directSet || device.penSet || device.multitouchSet;
    }

    private static boolean notEmpty(String value) {
        return value != null && !value.isEmpty();
    }

    private static boolean field(StringBuilder b, boolean comma, String key, String value) {
        if (value == null || value.isEmpty()) return comma;
        if (comma) b.append(", ");
        b.append("\"").append(key).append("\": \"").append(Profile.jsonString(value)).append("\"");
        return true;
    }

    private static void loadDisk() {
        items.clear();
        devices.clear();
        active = "";
        next = 1;
        if (dir == null) return;
        File index = new File(dir, "index.json");
        if (index.isFile()) {
            try {
                JSONObject root = new JSONObject(readFile(index));
                active = root.optString("active", "");
                next = root.optInt("next", 1);
                JSONArray list = root.optJSONArray("items");
                if (list != null) {
                    for (int i = 0; i < list.length(); i++) {
                        JSONObject one = list.getJSONObject(i);
                        String id = one.optString("id", "");
                        File body = new File(dir, id + ".json");
                        if (!body.isFile()) continue;
                        Item item = Profile.readPreset(id, new JSONObject(readFile(body)));
                        item.name = one.optString("name", id);
                        items.add(item);
                    }
                }
            } catch (Exception ignored) {
                items.clear();
            }
        }
        File deviceFile = new File(dir, "devices.json");
        if (deviceFile.isFile()) {
            try {
                JSONArray list = new JSONArray(readFile(deviceFile));
                for (int i = 0; i < list.length(); i++) {
                    JSONObject one = list.optJSONObject(i);
                    if (one != null) devices.add(Profile.readDevice(one));
                }
            } catch (Exception ignored) {
                devices.clear();
            }
        }
    }

    private static void saveIndex() throws Exception {
        if (dir == null) return;
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        JSONObject index = new JSONObject();
        index.put("active", active);
        index.put("next", next);
        JSONArray list = new JSONArray();
        for (Item item : items) {
            JSONObject one = new JSONObject();
            one.put("id", item.id);
            one.put("name", item.name);
            list.put(one);
        }
        index.put("items", list);
        writeFile(new File(dir, "index.json"), index.toString());
    }

    private static void saveDisk() {
        if (dir == null) return;
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        try {
            saveIndex();
            for (Item item : items) {
                writeFile(new File(dir, item.id + ".json"), Profile.presetBody(item));
            }
            JSONArray deviceList = new JSONArray();
            for (Device device : devices) deviceList.put(new JSONObject(deviceJson(device)));
            writeFile(new File(dir, "devices.json"), deviceList.toString());
        } catch (Exception ignored) {
        }
    }

    private static String readFile(File file) {
        try {
            byte[] raw = java.nio.file.Files.readAllBytes(file.toPath());
            return new String(raw, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static void writeFile(File file, String text) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
    }
}
