package app.stylusctrl;

import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.navigationrail.NavigationRailView;
import com.google.android.material.snackbar.Snackbar;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private Profile profile = new Profile();
    private boolean filling;
    private int tabletMaxX = 15200;
    private int tabletMaxY = 9500;
    private int screenW = 2000;
    private int screenH = 1200;

    private View root;
    private TextView pageTitle;
    private TextView pageSub;
    private View mappingPage;
    private View pressurePage;
    private View buttonsPage;
    private View testPage;
    private View internalPage;
    private View devicePage;
    private MappingBoard board;
    private ChipGroup presets;
    private ChipGroup rotations;
    private CurveGraph curve;
    private TextView curveReadout;
    private TextView status;
    private NavigationRailView rail;
    private boolean editingInternal;
    private boolean resumed;
    private boolean devicesBusy;
    private boolean knowRoot;
    private boolean haveRoot;
    private String offeredMatch = "";
    private String deviceLine = "";
    private String statusPrefix = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);
        root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        pageTitle = findViewById(R.id.page_title);
        pageSub = findViewById(R.id.page_sub);
        android.widget.FrameLayout pages = findViewById(R.id.pages);
        LayoutInflater inflater = getLayoutInflater();
        mappingPage = inflater.inflate(R.layout.page_mapping, pages, false);
        pressurePage = inflater.inflate(R.layout.page_pressure, pages, false);
        buttonsPage = inflater.inflate(R.layout.page_buttons, pages, false);
        testPage = inflater.inflate(R.layout.page_test, pages, false);
        internalPage = inflater.inflate(R.layout.page_internal, pages, false);
        devicePage = inflater.inflate(R.layout.page_device, pages, false);
        pages.addView(mappingPage);
        pages.addView(pressurePage);
        pages.addView(buttonsPage);
        pages.addView(testPage);
        pages.addView(internalPage);
        pages.addView(devicePage);

        board = mappingPage.findViewById(R.id.board);
        presets = mappingPage.findViewById(R.id.presets);
        rotations = mappingPage.findViewById(R.id.tablet_rotation);
        curve = pressurePage.findViewById(R.id.curve);
        curveReadout = pressurePage.findViewById(R.id.curve_readout);
        status = devicePage.findViewById(R.id.status);
        TestCanvas canvas = testPage.findViewById(R.id.canvas);
        TextView sample = testPage.findViewById(R.id.sample);
        canvas.setSampleListener(sample::setText);

        board.setListener(this::onBoardChanged);
        curve.setListener(xy -> {
            if (filling) return;
            storeCurve(xy);
            showCurveReadout(xy);
        });
        presets.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (filling || checkedIds.isEmpty()) return;
            applyPreset(checkedIds.get(0));
        });
        rotations.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (filling || checkedIds.isEmpty()) return;
            applyRotation(checkedIds.get(0));
        });
        pressurePage.findViewById(R.id.curve_reset).setOnClickListener(v -> resetCurve());
        wireButtons();
        wireDeviceChoice();
        wireInternal();

        rail = findViewById(R.id.rail);
        rail.setOnItemSelectedListener(this::showPage);
        rail.setSelectedItemId(R.id.nav_map);
        findViewById(R.id.save).setOnClickListener(v -> save());
        showProfile(profile);
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        root.removeCallbacks(watchDevices);
        root.post(watchDevices);
    }

    @Override
    protected void onPause() {
        resumed = false;
        root.removeCallbacks(watchDevices);
        super.onPause();
    }

    private final Runnable watchDevices = new Runnable() {
        @Override
        public void run() {
            if (!resumed) return;
            if (knowRoot && !haveRoot) return;
            if (!devicesBusy) {
                devicesBusy = true;
                io.execute(() -> {
                    RootStore.Snapshot snap = RootStore.readDevices();
                    runOnUiThread(() -> {
                        devicesBusy = false;
                        if (resumed && snap.root) applyDevices(snap);
                    });
                });
            }
            if (resumed) root.postDelayed(this, 1500);
        }
    };

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        root.post(this::applyWindowSize);
    }

    @Override
    protected void onDestroy() {
        resumed = false;
        root.removeCallbacks(watchDevices);
        io.shutdownNow();
        super.onDestroy();
    }

    private boolean showPage(MenuItem item) {
        int id = item.getItemId();
        mappingPage.setVisibility(id == R.id.nav_map ? View.VISIBLE : View.GONE);
        pressurePage.setVisibility(id == R.id.nav_curve ? View.VISIBLE : View.GONE);
        buttonsPage.setVisibility(id == R.id.nav_keys ? View.VISIBLE : View.GONE);
        testPage.setVisibility(id == R.id.nav_test ? View.VISIBLE : View.GONE);
        internalPage.setVisibility(id == R.id.nav_internal ? View.VISIBLE : View.GONE);
        devicePage.setVisibility(id == R.id.nav_device ? View.VISIBLE : View.GONE);
        pageTitle.setText(item.getTitle());
        return true;
    }

    private void load() {
        pageSub.setText(R.string.reading_config);
        io.execute(() -> {
            RootStore.Snapshot snap = RootStore.read(MainActivity.this);
            runOnUiThread(() -> applySnapshot(snap));
        });
    }

    private void applySnapshot(RootStore.Snapshot snap) {
        knowRoot = true;
        haveRoot = snap.root;
        if (!snap.root && !snap.local) {
            String message = snap.error == null || "no_root".equals(snap.error)
                    ? getString(R.string.no_root) : snap.error;
            pageSub.setText(message);
            status.setText(message);
            return;
        }
        if (snap.tabletMaxX > 0) tabletMaxX = snap.tabletMaxX;
        if (snap.tabletMaxY > 0) tabletMaxY = snap.tabletMaxY;
        int[] window = readWindowSize();
        if (window[0] > 0 && window[1] > 0) {
            screenW = window[0];
            screenH = window[1];
        } else if (snap.screenW > 0 && snap.screenH > 0) {
            screenW = snap.screenW;
            screenH = snap.screenH;
        }
        boolean parsed = true;
        if (snap.configText != null) {
            try {
                profile = Profile.parse(snap.configText);
            } catch (Exception e) {
                parsed = false;
                pageSub.setText(R.string.config_parse_failed);
            }
        }
        showProfile(profile);
        if (snap.local) {
            String screen = screenW > 0 ? getString(R.string.screen_size, screenW, screenH) : "";
            statusPrefix = getString(R.string.local_config);
            status.setText(statusPrefix + screen);
            if (parsed) pageSub.setText(R.string.local_config);
            return;
        }
        String daemon = snap.daemonPid == null || snap.daemonPid.isEmpty()
                ? getString(R.string.daemon_stopped)
                : getString(R.string.daemon_pid, snap.daemonPid);
        String screen = snap.screenW > 0
                ? getString(R.string.screen_size, snap.screenW, snap.screenH)
                : getString(R.string.no_active_screen);
        statusPrefix = daemon + screen;
        applyDevices(snap);
    }

    private void applyDevices(RootStore.Snapshot snap) {
        RootStore.Pen external = null;
        for (RootStore.Pen pen : snap.pens) {
            if (pen.external) {
                external = pen;
                break;
            }
        }
        setMappingVisible(external != null);
        if (external != null) adoptAxes(external);
        status.setText(statusPrefix + "\n" + RootStore.axesSummary(this, snap.pens));
        String line = external == null ? getString(R.string.no_external) : external.name + "  " + external.maxX + "×" + external.maxY;
        if (!line.equals(deviceLine)) {
            deviceLine = line;
            pageSub.setText(line);
        }
    }

    private void setMappingVisible(boolean show) {
        if (rail == null) return;
        MenuItem item = rail.getMenu().findItem(R.id.nav_map);
        if (item == null) return;
        if (item.isVisible() != show) item.setVisible(show);
        if (!show && rail.getSelectedItemId() == R.id.nav_map) rail.setSelectedItemId(R.id.nav_curve);
    }

    private void adoptAxes(RootStore.Pen pen) {
        int maxX = pen.maxX;
        int maxY = pen.maxY;
        int oldX = profile.hasSpan ? profile.spanX : 15200;
        int oldY = profile.hasSpan ? profile.spanY : 9500;
        boolean changed = maxX != oldX || maxY != oldY;
        if (changed && maxX > 0 && maxY > 0 && profile.hasRect) {
            profile.rect = Profile.scaleRect(profile.rect, profile.rotation, oldX, oldY, maxX, maxY);
            Snackbar.make(root, getString(R.string.axes_scaled, maxX, maxY), Snackbar.LENGTH_LONG).show();
        }
        if (maxX > 0 && maxY > 0) {
            profile.hasSpan = true;
            profile.spanX = maxX;
            profile.spanY = maxY;
            tabletMaxX = maxX;
            tabletMaxY = maxY;
        }
        if (changed) {
            board.setAxes(tabletMaxX, tabletMaxY, screenW, screenH);
            board.setMapping(profile.mode, profile.crop, profile.hasRect, profile.rect,
                    profile.hasScreen, toFloat(profile.screen));
        }
        offerMatch(pen);
    }

    private void offerMatch(RootStore.Pen pen) {
        if (pen.vendor.isEmpty() || pen.product.isEmpty()) return;
        String id = pen.vendor + ":" + pen.product;
        String current = RootStore.hex4(profile.vendor) + ":" + RootStore.hex4(profile.product);
        if (id.equals(current)) {
            offeredMatch = "";
            return;
        }
        if (id.equals(offeredMatch)) return;
        offeredMatch = id;
        final String vendor = pen.vendor;
        final String product = pen.product;
        Snackbar.make(root, getString(R.string.tablet_mismatch, id, current), Snackbar.LENGTH_LONG)
                .setAction(R.string.use_this_tablet, v -> {
                    profile.vendor = vendor;
                    profile.product = product;
                    offeredMatch = "";
                    pageSub.setText(getString(R.string.match_unsaved, id));
                    deviceLine = pageSub.getText().toString();
                })
                .show();
    }

    private void showProfile(Profile p) {
        filling = true;
        board.setAxes(tabletMaxX, tabletMaxY, screenW, screenH);
        board.setRotation(p.rotation);
        board.setMapping(p.mode, p.crop, p.hasRect, p.rect, p.hasScreen, toFloat(p.screen));
        checkPreset(p);
        checkRotation(p);
        syncDeviceToggle();
        showFeel(feel());
        MaterialSwitch internal = internalPage.findViewById(R.id.internal_pen);
        internal.setChecked(p.internalOn);
        filling = false;
    }

    private Profile.Feel feel() {
        return editingInternal ? profile.internal : profile.external;
    }

    private void showFeel(Profile.Feel f) {
        curve.setPoints(absoluteCurve(f));
        showCurveReadout(curve.getPoints());
        MaterialButtonToggleGroup stylus = buttonsPage.findViewById(R.id.stylus_action);
        int stylusId = R.id.stylus_pass;
        if ("shortcut".equals(f.stylusAction)) stylusId = R.id.stylus_shortcut;
        else if ("eraser".equals(f.stylusAction)) stylusId = R.id.stylus_eraser;
        stylus.check(stylusId);
        MaterialButtonToggleGroup rubber = buttonsPage.findViewById(R.id.rubber_action);
        int rubberId = R.id.rubber_eraser;
        if ("shortcut".equals(f.rubberAction)) rubberId = R.id.rubber_shortcut;
        else if ("stylus2".equals(f.rubberAction)) rubberId = R.id.rubber_stylus2;
        rubber.check(rubberId);
        MaterialSwitch eraser = buttonsPage.findViewById(R.id.eraser_wins);
        eraser.setChecked(f.eraserWins);
        refreshChords();
        showChordRows();
    }

    private void syncDeviceToggle() {
        MaterialButtonToggleGroup pressure = pressurePage.findViewById(R.id.pressure_device);
        MaterialButtonToggleGroup buttons = buttonsPage.findViewById(R.id.buttons_device);
        pressure.check(editingInternal ? R.id.pressure_internal : R.id.pressure_external);
        buttons.check(editingInternal ? R.id.buttons_internal : R.id.buttons_external);
        int hint = editingInternal ? View.VISIBLE : View.GONE;
        pressurePage.findViewById(R.id.pressure_hint).setVisibility(hint);
        buttonsPage.findViewById(R.id.buttons_hint).setVisibility(hint);
    }

    private void wireDeviceChoice() {
        MaterialButtonToggleGroup pressure = pressurePage.findViewById(R.id.pressure_device);
        MaterialButtonToggleGroup buttons = buttonsPage.findViewById(R.id.buttons_device);
        pressure.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (filling || !isChecked) return;
            chooseDevice(checkedId == R.id.pressure_internal);
        });
        buttons.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (filling || !isChecked) return;
            chooseDevice(checkedId == R.id.buttons_internal);
        });
    }

    private void chooseDevice(boolean internal) {
        if (editingInternal == internal) return;
        editingInternal = internal;
        filling = true;
        syncDeviceToggle();
        showFeel(feel());
        filling = false;
    }

    private void checkPreset(Profile p) {
        int id = R.id.preset_custom;
        if (!p.hasRect && !p.hasScreen) {
            if ("stretch".equals(p.mode)) id = R.id.preset_stretch;
            else if ("left".equals(p.crop)) id = R.id.preset_left;
            else if ("right".equals(p.crop)) id = R.id.preset_right;
            else if ("top".equals(p.crop)) id = R.id.preset_top;
            else if ("bottom".equals(p.crop)) id = R.id.preset_bottom;
            else id = R.id.preset_center;
        }
        presets.check(id);
    }

    private void applyPreset(int id) {
        if (id == R.id.preset_custom) {
            if (!profile.hasRect && !profile.hasScreen) {
                filling = true;
                checkPreset(profile);
                filling = false;
            }
            return;
        }
        profile.hasRect = false;
        profile.hasScreen = false;
        profile.mode = id == R.id.preset_stretch ? "stretch" : "fit";
        if (id == R.id.preset_left) profile.crop = "left";
        else if (id == R.id.preset_right) profile.crop = "right";
        else if (id == R.id.preset_top) profile.crop = "top";
        else if (id == R.id.preset_bottom) profile.crop = "bottom";
        else profile.crop = "center";
        board.setMapping(profile.mode, profile.crop, false, profile.rect, false, toFloat(profile.screen));
    }

    private void checkRotation(Profile p) {
        int id = R.id.rot_0;
        if (p.rotation == 90) id = R.id.rot_90;
        else if (p.rotation == 180) id = R.id.rot_180;
        else if (p.rotation == 270) id = R.id.rot_270;
        rotations.check(id);
    }

    private void applyRotation(int id) {
        int degrees = 0;
        if (id == R.id.rot_90) degrees = 90;
        else if (id == R.id.rot_180) degrees = 180;
        else if (id == R.id.rot_270) degrees = 270;
        if (degrees == profile.rotation) return;
        if (profile.hasRect) {
            profile.rect = MappingBoard.turnRect(profile.rect, profile.rotation, degrees, tabletMaxX, tabletMaxY);
        }
        profile.rotation = degrees;
        board.setRotation(degrees);
        board.setMapping(profile.mode, profile.crop, profile.hasRect, profile.rect, profile.hasScreen, toFloat(profile.screen));
    }

    private void applyWindowSize() {
        int[] window = readWindowSize();
        if (window[0] <= 0 || window[1] <= 0) return;
        if (window[0] == screenW && window[1] == screenH) return;
        screenW = window[0];
        screenH = window[1];
        board.setAxes(tabletMaxX, tabletMaxY, screenW, screenH);
        board.setMapping(profile.mode, profile.crop, profile.hasRect, profile.rect, profile.hasScreen, toFloat(profile.screen));
        io.execute(RootStore::rereadDisplay);
    }

    @SuppressWarnings("deprecation")
    private int[] readWindowSize() {
        if (Build.VERSION.SDK_INT >= 30) {
            Rect bounds = getWindowManager().getCurrentWindowMetrics().getBounds();
            return new int[] {bounds.width(), bounds.height()};
        }
        Display display = getWindowManager().getDefaultDisplay();
        Point point = new Point();
        display.getRealSize(point);
        return new int[] {point.x, point.y};
    }

    private void resetCurve() {
        Profile.Feel f = feel();
        f.deadzone = 0;
        f.ceiling = 1;
        f.curve = new double[] {0, 0, 0.4, 0.7, 0.4, 0.7, 1, 1};
        float[] xy = absoluteCurve(f);
        filling = true;
        curve.setPoints(xy);
        filling = false;
        showCurveReadout(xy);
    }

    private void onBoardChanged(int[] tablet, float[] screen) {
        if (filling) return;
        profile.mode = "stretch";
        profile.hasRect = true;
        profile.rect = tablet.clone();
        profile.hasScreen = true;
        for (int i = 0; i < 4; i++) profile.screen[i] = screen[i];
        filling = true;
        presets.check(R.id.preset_custom);
        filling = false;
    }

    private void wireButtons() {
        MaterialButtonToggleGroup stylus = buttonsPage.findViewById(R.id.stylus_action);
        MaterialButtonToggleGroup rubber = buttonsPage.findViewById(R.id.rubber_action);
        stylus.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (filling || !isChecked) return;
            if (checkedId == R.id.stylus_shortcut) feel().stylusAction = "shortcut";
            else if (checkedId == R.id.stylus_eraser) feel().stylusAction = "eraser";
            else feel().stylusAction = "passthrough";
            showChordRows();
        });
        rubber.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (filling || !isChecked) return;
            if (checkedId == R.id.rubber_shortcut) feel().rubberAction = "shortcut";
            else if (checkedId == R.id.rubber_stylus2) feel().rubberAction = "stylus2";
            else feel().rubberAction = "eraser";
            showChordRows();
        });
        MaterialSwitch eraser = buttonsPage.findViewById(R.id.eraser_wins);
        eraser.setOnCheckedChangeListener((button, checked) -> {
            if (!filling) feel().eraserWins = checked;
        });
        MaterialButton stylusAdd = buttonsPage.findViewById(R.id.stylus_add);
        MaterialButton rubberAdd = buttonsPage.findViewById(R.id.rubber_add);
        stylusAdd.setOnClickListener(v -> addKey(feel().stylusKeys));
        rubberAdd.setOnClickListener(v -> addKey(feel().rubberKeys));
    }

    private void wireInternal() {
        MaterialSwitch internal = internalPage.findViewById(R.id.internal_pen);
        internal.setOnCheckedChangeListener((button, checked) -> {
            if (!filling) profile.internalOn = checked;
        });
    }

    private void addKey(java.util.List<String> keys) {
        if (keys.size() >= KeyChord.MAX) {
            Snackbar.make(root, R.string.chord_max, Snackbar.LENGTH_SHORT).show();
            return;
        }
        KeyChord.pick(this, keys, this::refreshChords);
    }

    private void refreshChords() {
        ChipGroup stylus = buttonsPage.findViewById(R.id.stylus_chord);
        ChipGroup rubber = buttonsPage.findViewById(R.id.rubber_chord);
        KeyChord.render(stylus, feel().stylusKeys, () -> {});
        KeyChord.render(rubber, feel().rubberKeys, () -> {});
    }

    private void showChordRows() {
        buttonsPage.findViewById(R.id.stylus_chord_row)
                .setVisibility("shortcut".equals(feel().stylusAction) ? View.VISIBLE : View.GONE);
        buttonsPage.findViewById(R.id.rubber_chord_row)
                .setVisibility("shortcut".equals(feel().rubberAction) ? View.VISIBLE : View.GONE);
    }

    private void storeCurve(float[] xy) {
        float lo = xy[0];
        float hi = xy[6];
        if (hi - lo < 0.02f) hi = Math.min(1f, lo + 0.02f);
        Profile.Feel f = feel();
        f.deadzone = lo;
        f.ceiling = hi;
        double span = hi - lo;
        for (int i = 0; i < 4; i++) {
            f.curve[i * 2] = (xy[i * 2] - lo) / span;
            f.curve[i * 2 + 1] = xy[i * 2 + 1];
        }
    }

    private void showCurveReadout(float[] xy) {
        curveReadout.setText(getString(R.string.curve_readout, xy[0] * 100f, xy[6] * 100f));
    }

    private float[] absoluteCurve(Profile.Feel p) {
        double lo = p.deadzone;
        double hi = p.ceiling;
        if (!(hi > lo)) hi = 1;
        double span = hi - lo;
        float[] xy = new float[8];
        for (int i = 0; i < 4; i++) {
            xy[i * 2] = (float) (lo + p.curve[i * 2] * span);
            xy[i * 2 + 1] = (float) p.curve[i * 2 + 1];
        }
        return xy;
    }

    private static float[] toFloat(double[] values) {
        float[] out = new float[4];
        for (int i = 0; i < 4; i++) out[i] = (float) values[i];
        return out;
    }

    private boolean keysOk(Profile.Feel feel, String which) {
        for (String key : feel.stylusKeys) {
            if (!KeyChord.known(key)) {
                Snackbar.make(root, getString(R.string.unknown_side_key, which), Snackbar.LENGTH_LONG).show();
                return false;
            }
        }
        for (String key : feel.rubberKeys) {
            if (!KeyChord.known(key)) {
                Snackbar.make(root, getString(R.string.unknown_rubber_key, which), Snackbar.LENGTH_LONG).show();
                return false;
            }
        }
        if ("shortcut".equals(feel.stylusAction) && feel.stylusKeys.isEmpty()) {
            Snackbar.make(root, getString(R.string.shortcut_needs_key, which), Snackbar.LENGTH_LONG).show();
            return false;
        }
        if ("shortcut".equals(feel.rubberAction) && feel.rubberKeys.isEmpty()) {
            Snackbar.make(root, getString(R.string.rubber_needs_key, which), Snackbar.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    private void save() {
        if (!keysOk(profile.external, getString(R.string.pen_external))
                || !keysOk(profile.internal, getString(R.string.pen_internal))) return;
        String json = profile.toJson();
        pageSub.setText(R.string.writing);
        io.execute(() -> {
            String code = RootStore.write(MainActivity.this, json);
            runOnUiThread(() -> {
                String message = writeMessage(code);
                pageSub.setText(message);
                Snackbar.make(root, message, Snackbar.LENGTH_LONG).show();
                status.setText(message + "\n\n" + status.getText());
            });
        });
    }

    private String writeMessage(String code) {
        if ("local".equals(code)) return getString(R.string.wrote_local);
        if ("notified".equals(code)) return getString(R.string.wrote_notified);
        if ("stopped".equals(code)) return getString(R.string.wrote_stopped);
        if ("written".equals(code)) return getString(R.string.wrote);
        if ("failed".equals(code)) return getString(R.string.write_failed);
        return code;
    }
}
