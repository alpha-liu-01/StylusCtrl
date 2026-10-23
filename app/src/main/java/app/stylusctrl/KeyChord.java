package app.stylusctrl;

import android.content.Context;
import android.view.ViewGroup;
import android.widget.ScrollView;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Arrays;
import java.util.List;

/** A shortcut is an ordered chord of up to eight keys, pressed together. */
public final class KeyChord {
    public static final int MAX = 8;
    public static final String[] KEYS = {
            "CTRL", "ALT", "SHIFT", "META",
            "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M",
            "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z",
            "1", "2", "3", "4", "5", "6", "7", "8", "9", "0",
            "SPACE", "ENTER", "TAB", "ESC", "UP", "DOWN", "LEFT", "RIGHT", "BACKSPACE", "DELETE",
            "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12",
            "BACK", "HOME", "RECENT", "APP_SWITCH", "MENU", "COPY", "CUT", "PASTE",
            "VOL_DOWN", "VOL_UP", "MUTE", "BRIGHTNESS_DOWN", "BRIGHTNESS_UP",
            "PLAY", "PREV", "NEXT", "POWER", "SLEEP", "WAKEUP"
    };

    private KeyChord() {}

    public static boolean known(String key) {
        for (String item : KEYS) {
            if (item.equals(key)) return true;
        }
        return false;
    }

    public static void render(ChipGroup group, List<String> keys, Runnable changed) {
        group.removeAllViews();
        for (int i = 0; i < keys.size(); i++) {
            final int index = i;
            Chip chip = new Chip(group.getContext());
            chip.setText(keys.get(i));
            chip.setCloseIconVisible(true);
            chip.setOnCloseIconClickListener(v -> {
                if (index < keys.size()) keys.remove(index);
                render(group, keys, changed);
                changed.run();
            });
            group.addView(chip);
        }
    }

    public static void pick(Context context, List<String> keys, Runnable changed) {
        if (keys.size() >= MAX) return;
        ChipGroup choices = new ChipGroup(context);
        int pad = (int) (12 * context.getResources().getDisplayMetrics().density);
        choices.setPadding(pad, pad, pad, pad);
        ScrollView scroll = new ScrollView(context);
        scroll.addView(choices, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final androidx.appcompat.app.AlertDialog[] dialog = new androidx.appcompat.app.AlertDialog[1];
        for (String name : KEYS) {
            Chip chip = new Chip(context);
            chip.setText(name);
            chip.setOnClickListener(v -> {
                if (keys.size() < MAX) keys.add(name);
                changed.run();
                if (dialog[0] != null) dialog[0].dismiss();
            });
            choices.addView(chip);
        }
        dialog[0] = new MaterialAlertDialogBuilder(context)
                .setTitle(context.getString(R.string.add_key))
                .setView(scroll)
                .setNegativeButton(context.getString(R.string.cancel), null)
                .create();
        dialog[0].show();
    }

    public static List<String> copyOf(List<String> keys) {
        return Arrays.asList(keys.toArray(new String[0]));
    }
}
