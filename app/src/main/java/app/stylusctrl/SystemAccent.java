package app.stylusctrl;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.loader.ResourcesLoader;
import android.content.res.loader.ResourcesProvider;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Android 12 keeps the static theme unless these colors are replaced.
 * Material's dynamic colors skip Samsung devices that are not One UI, and the
 * accent resource ids compiled against a newer SDK do not match Android 12.
 * Looking the colors up by name and overlaying them uses this device's ids.
 * Android 13 and newer stay on DynamicColors and never call this.
 */
final class SystemAccent {
    private static final String TAG = "StylusCtrl";
    private static ResourcesLoader loader;
    private static ParcelFileDescriptor held;
    private static File heldFile;

    private SystemAccent() {}

    static void install(Context context) {
        Resources res = context.getResources();
        boolean night = (res.getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        int primary = platform(res, night ? "system_accent1_200" : "system_accent1_600");
        if (primary == 0) {
            Log.w(TAG, "no system accent");
            return;
        }
        int[] ids = {
                R.color.brand_primary,
                R.color.brand_on_primary,
                R.color.brand_primary_container,
                R.color.brand_on_primary_container,
                R.color.brand_secondary,
                R.color.brand_on_secondary,
                R.color.brand_secondary_container,
                R.color.brand_on_secondary_container
        };
        int[] values = night
                ? new int[] {
                        primary,
                        platform(res, "system_accent1_800"),
                        platform(res, "system_accent1_700"),
                        platform(res, "system_accent1_100"),
                        platform(res, "system_accent2_200"),
                        platform(res, "system_accent2_800"),
                        platform(res, "system_accent2_700"),
                        platform(res, "system_accent2_100")
                }
                : new int[] {
                        primary,
                        platform(res, "system_accent1_0"),
                        platform(res, "system_accent1_100"),
                        platform(res, "system_accent1_900"),
                        platform(res, "system_accent2_600"),
                        platform(res, "system_accent2_0"),
                        platform(res, "system_accent2_100"),
                        platform(res, "system_accent2_900")
                };
        for (int value : values) {
            if (value == 0) {
                Log.w(TAG, "system accent shade missing");
                return;
            }
        }
        try {
            byte[] table = table(ids, values);
            File file = new File(context.getCacheDir(), "accent-" + System.nanoTime() + ".arsc");
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(table);
            }
            ParcelFileDescriptor pfd = ParcelFileDescriptor.open(
                    file, ParcelFileDescriptor.MODE_READ_ONLY);
            ResourcesLoader next = new ResourcesLoader();
            next.addProvider(ResourcesProvider.loadFromTable(pfd, null));
            res.addLoaders(next);
            if (loader != null) {
                try {
                    res.removeLoaders(loader);
                } catch (Throwable ignored) {
                }
            }
            if (held != null) {
                try {
                    held.close();
                } catch (IOException ignored) {
                }
            }
            if (heldFile != null) heldFile.delete();
            held = pfd;
            heldFile = file;
            loader = next;
        } catch (Throwable t) {
            Log.w(TAG, "system accent", t);
        }
    }

    private static int platform(Resources res, String name) {
        int id = res.getIdentifier(name, "color", "android");
        if (id == 0) return 0;
        try {
            return res.getColor(id, null);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** A resources.arsc that overrides the app color ids. Same layout aapt2 writes. */
    private static byte[] table(int[] ids, int[] values) throws IOException {
        int pkg = (ids[0] >>> 24) & 0xff;
        int type = (ids[0] >>> 16) & 0xff;
        int max = 0;
        for (int id : ids) {
            if (((id >>> 24) & 0xff) != pkg || ((id >>> 16) & 0xff) != type) {
                throw new IOException("color id");
            }
            max = Math.max(max, id & 0xffff);
        }
        int count = max + 1;
        String[] typeNames = new String[type];
        for (int i = 0; i < type - 1; i++) typeNames[i] = "";
        typeNames[type - 1] = "color";
        String[] keys = new String[ids.length];
        int[] keyOf = new int[count];
        int[] colorOf = new int[count];
        boolean[] present = new boolean[count];
        for (int i = 0; i < ids.length; i++) {
            int entry = ids[i] & 0xffff;
            keys[i] = "c" + i;
            keyOf[entry] = i;
            colorOf[entry] = values[i];
            present[entry] = true;
        }
        byte[] typePool = pool(typeNames);
        byte[] keyPool = pool(keys);
        byte[] spec = typeSpec(type, count);
        byte[] body = typeBody(type, count, present, keyOf, colorOf);
        int header = 0x120;
        int keyAt = header + typePool.length;
        int specAt = keyAt + keyPool.length;
        byte[] pkgChunk = new byte[specAt + spec.length + body.length];
        ByteBuffer buf = ByteBuffer.wrap(pkgChunk).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) 0x0200);
        buf.putShort((short) header);
        buf.putInt(pkgChunk.length);
        buf.putInt(pkg);
        byte[] name = utf16Raw("app.stylusctrl");
        buf.put(name);
        buf.put(new byte[256 - name.length]);
        buf.putInt(header);
        buf.putInt(0);
        buf.putInt(keyAt);
        buf.putInt(0);
        buf.putInt(0);
        buf.put(typePool);
        buf.put(keyPool);
        buf.put(spec);
        buf.put(body);
        byte[] global = pool(new String[0]);
        byte[] out = new byte[12 + global.length + pkgChunk.length];
        ByteBuffer all = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        all.putShort((short) 0x0002);
        all.putShort((short) 0x000c);
        all.putInt(out.length);
        all.putInt(1);
        all.put(global);
        all.put(pkgChunk);
        return out;
    }

    private static byte[] typeSpec(int type, int count) {
        ByteBuffer buf = ByteBuffer.allocate(16 + count * 4).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) 0x0202);
        buf.putShort((short) 0x0010);
        buf.putInt(buf.capacity());
        buf.put((byte) type);
        buf.put((byte) 0);
        buf.putShort((short) 1);
        buf.putInt(count);
        return buf.array();
    }

    private static byte[] typeBody(int type, int count, boolean[] present, int[] keyOf, int[] colorOf) {
        int header = 0x54;
        int entriesStart = header + count * 4;
        int dataBytes = 0;
        for (boolean on : present) if (on) dataBytes += 16;
        ByteBuffer buf = ByteBuffer.allocate(entriesStart + dataBytes).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) 0x0201);
        buf.putShort((short) header);
        buf.putInt(buf.capacity());
        buf.put((byte) type);
        buf.put((byte) 0);
        buf.putShort((short) 0);
        buf.putInt(count);
        buf.putInt(entriesStart);
        buf.putInt(64);
        buf.put(new byte[60]);
        int dataAt = 0;
        for (int i = 0; i < count; i++) {
            if (!present[i]) {
                buf.putInt(0xffffffff);
                continue;
            }
            buf.putInt(dataAt);
            dataAt += 16;
        }
        for (int i = 0; i < count; i++) {
            if (!present[i]) continue;
            int color = colorOf[i];
            buf.putShort((short) 8);
            buf.putShort((short) 0);
            buf.putInt(keyOf[i]);
            buf.putShort((short) 8);
            buf.put((byte) 0);
            buf.put((byte) ((color >>> 24) == 0xff ? 0x1d : 0x1c));
            buf.putInt(color);
        }
        return buf.array();
    }

    private static byte[] pool(String[] strings) {
        byte[][] encoded = new byte[strings.length][];
        int[] offsets = new int[strings.length];
        int cursor = 0;
        for (int i = 0; i < strings.length; i++) {
            offsets[i] = cursor;
            encoded[i] = utf16(strings[i]);
            cursor += encoded[i].length;
        }
        int start = 28 + strings.length * 4;
        int size = start + cursor;
        size += (4 - (size & 3)) & 3;
        ByteBuffer buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) 0x0001);
        buf.putShort((short) 0x001c);
        buf.putInt(size);
        buf.putInt(strings.length);
        buf.putInt(0);
        buf.putInt(0);
        buf.putInt(start);
        buf.putInt(0);
        for (int offset : offsets) buf.putInt(offset);
        for (byte[] item : encoded) buf.put(item);
        return buf.array();
    }

    /** Package names are raw UTF-16, not a length-prefixed pool string. */
    private static byte[] utf16Raw(String text) {
        ByteBuffer buf = ByteBuffer.allocate(text.length() * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < text.length(); i++) buf.putShort((short) text.charAt(i));
        return buf.array();
    }

    /** Length, UTF-16 chars, and a trailing NUL. Short names only. */
    private static byte[] utf16(String text) {
        ByteBuffer buf = ByteBuffer.allocate(4 + text.length() * 2).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) text.length());
        for (int i = 0; i < text.length(); i++) buf.putShort((short) text.charAt(i));
        buf.putShort((short) 0);
        return buf.array();
    }
}
