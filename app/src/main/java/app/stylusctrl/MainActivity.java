package app.stylusctrl;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewStub;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import androidx.appcompat.app.AlertDialog;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.navigationrail.NavigationRailView;
import com.google.android.material.snackbar.Snackbar;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
    private TextView editTarget;
    private TextView pageSub;
    private View mappingPage;
    private View pressurePage;
    private View buttonsPage;
    private View testPage;
    private View internalPage;
    private View appsPage;
    private View devicePage;
    private RecyclerView appList;
    private MaterialButtonToggleGroup appsDevice;
    private TextView watchState;
    private int rowHighlight;
    private int rowOnContainer;
    private int rowOnSurface;
    private int rowOutline;
    private float segmentRadius;
    /** Same radius as the apps list card, so the end rows follow its corners. */
    private float listCorner;
    private final List<AppEntry> shownApps = new ArrayList<>();
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
    /** When the external pen last disappeared. Re-enumeration drops it for about 3 seconds. */
    private long externalGoneAt = -1;
    private static final long EXTERNAL_GAP_MS = 4000;
    private String statusPrefix = "";
    private boolean externalPresent;
    private String externalTitle = "";
    /** Null while the other pages edit this device's baseline. */
    private String editPackage;
    private String editAppLabel = "";
    private String editingPresetId = "";
    private boolean editorLive;
    private int bindingRows;
    private final List<AppEntry> apps = new ArrayList<>();

    private static final class AppEntry {
        String pkg;
        String label;
        Drawable icon;
    }
    private final ActivityResultLauncher<String[]> openConfig =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::importConfig);

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(newBase);
        if (Build.VERSION.SDK_INT >= 31 && Build.VERSION.SDK_INT < 33) {
            SystemAccent.install(this);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 31 && Build.VERSION.SDK_INT < 33) {
            SystemAccent.install(this);
            getTheme().applyStyle(R.style.Theme_StylusCtrl, true);
        }
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);
        applyStatusBar();
        root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        pageTitle = findViewById(R.id.page_title);
        editTarget = findViewById(R.id.edit_target);
        pageSub = findViewById(R.id.page_sub);
        android.widget.FrameLayout pages = findViewById(R.id.pages);
        LayoutInflater inflater = getLayoutInflater();
        mappingPage = inflater.inflate(R.layout.page_mapping, pages, false);
        pressurePage = inflater.inflate(R.layout.page_pressure, pages, false);
        buttonsPage = inflater.inflate(R.layout.page_buttons, pages, false);
        testPage = inflater.inflate(R.layout.page_test, pages, false);
        internalPage = inflater.inflate(R.layout.page_internal, pages, false);
        appsPage = inflater.inflate(R.layout.page_apps, pages, false);
        devicePage = inflater.inflate(R.layout.page_device, pages, false);
        pages.addView(mappingPage);
        pages.addView(pressurePage);
        pages.addView(buttonsPage);
        pages.addView(testPage);
        pages.addView(internalPage);
        pages.addView(appsPage);
        pages.addView(devicePage);

        board = mappingPage.findViewById(R.id.board);
        presets = mappingPage.findViewById(R.id.presets);
        rotations = mappingPage.findViewById(R.id.tablet_rotation);
        curve = pressurePage.findViewById(R.id.curve);
        curveReadout = pressurePage.findViewById(R.id.curve_readout);
        status = devicePage.findViewById(R.id.status);
        devicePage.findViewById(R.id.load_config).setOnClickListener(v ->
                openConfig.launch(new String[] {"application/json", "text/plain", "*/*"}));
        pressurePage.findViewById(R.id.pressure_device).setVisibility(View.GONE);
        buttonsPage.findViewById(R.id.buttons_device).setVisibility(View.GONE);
        appList = appsPage.findViewById(R.id.app_rows);
        appList.setLayoutManager(new LinearLayoutManager(this));
        appList.setHasFixedSize(true);
        appList.setItemAnimator(null);
        appList.setItemViewCacheSize(8);
        appList.getRecycledViewPool().setMaxRecycledViews(0, 12);
        AppAdapter appsAdapter = new AppAdapter();
        appsAdapter.setHasStableIds(true);
        appList.setAdapter(appsAdapter);
        rowHighlight = MaterialColors.getColor(appList, com.google.android.material.R.attr.colorSecondaryContainer);
        rowOnContainer = MaterialColors.getColor(appList, com.google.android.material.R.attr.colorOnSecondaryContainer);
        rowOnSurface = MaterialColors.getColor(appList, com.google.android.material.R.attr.colorOnSurface);
        rowOutline = MaterialColors.getColor(appList, com.google.android.material.R.attr.colorOutline);
        segmentRadius = 20f * getResources().getDisplayMetrics().density;
        listCorner = 28f * getResources().getDisplayMetrics().density;
        watchState = appsPage.findViewById(R.id.watch_state);
        appsPage.findViewById(R.id.watch_foreground).setOnClickListener(v -> watchForeground());
        refreshWatchState();
        appsDevice = appsPage.findViewById(R.id.apps_device);
        appsDevice.check(R.id.apps_external);
        appsDevice.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked || filling) return;
            if (!externalPresent && checkedId != R.id.apps_internal) {
                filling = true;
                group.check(R.id.apps_internal);
                filling = false;
                return;
            }
            chooseEditDevice(checkedId == R.id.apps_internal);
        });
        io.execute(() -> {
            List<AppEntry> loaded = loadApps();
            runOnUiThread(() -> {
                apps.clear();
                apps.addAll(loaded);
                bindApps();
            });
        });
        TestCanvas canvas = testPage.findViewById(R.id.canvas);
        TextView sample = testPage.findViewById(R.id.sample);
        canvas.setSampleListener(sample::setText);
        testPage.findViewById(R.id.clear_canvas).setOnClickListener(v -> canvas.clear());

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
        refreshWatchState();
        if (ForegroundWatch.allowed(this)) startWatch();
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
            if (!devicesBusy) {
                devicesBusy = true;
                io.execute(() -> {
                    RootStore.Snapshot snap = RootStore.readDevices(MainActivity.this);
                    runOnUiThread(() -> {
                        devicesBusy = false;
                        if (resumed && snap.error == null) applyDevices(snap);
                    });
                });
            }
            if (resumed) root.postDelayed(this, 1500);
        }
    };

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyStatusBar();
        root.post(this::applyWindowSize);
    }

    /** Dark icons on a light bar, light icons on a dark bar. uiMode does not recreate this activity. */
    private void applyStatusBar() {
        int night = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightStatusBars(night != Configuration.UI_MODE_NIGHT_YES);
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
        appsPage.setVisibility(id == R.id.nav_apps ? View.VISIBLE : View.GONE);
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
        boolean hadTable = false;
        if (snap.configText != null) {
            try {
                profile = Profile.parse(snap.configText);
                hadTable = profile.hasPresetTable;
            } catch (Exception e) {
                parsed = false;
                pageSub.setText(R.string.config_parse_failed);
            }
        }
        if (parsed) adoptLibrary();
        else showProfile(profile);
        if (parsed && !hadTable && (snap.configText != null || snap.local)) writeConfig(false);
        if (snap.local) {
            int showW = snap.screenW > 0 ? snap.screenW : screenW;
            int showH = snap.screenH > 0 ? snap.screenH : screenH;
            String screen = showW > 0 ? getString(R.string.screen_size, showW, showH).trim() : "";
            statusPrefix = screen;
            if (parsed) publishDisplay();
            applyDevices(snap);
            if (!parsed) pageSub.setText(R.string.config_parse_failed);
            return;
        }
        pageSub.setVisibility(View.VISIBLE);
        String daemon = snap.daemonPid == null || snap.daemonPid.isEmpty()
                ? getString(R.string.daemon_stopped)
                : getString(R.string.daemon_pid, snap.daemonPid);
        String screen = snap.screenW > 0
                ? getString(R.string.screen_size, snap.screenW, snap.screenH)
                : getString(R.string.no_active_screen);
        statusPrefix = daemon + screen;
        if (parsed) publishDisplay();
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
        status.setText(statusPrefix + "\n" + RootStore.axesSummary(this, snap.pens));
        if (external != null) {
            externalGoneAt = -1;
            externalPresent = true;
            if (external.name != null && !external.name.isEmpty()) externalTitle = external.name;
        } else if (holdExternal()) {
            pageSub.setVisibility(View.VISIBLE);
            updateMappingNav();
            return;
        } else {
            externalPresent = false;
        }
        updateMappingNav();
        if (external != null) adoptAxes(external);
        String line;
        if (external == null) line = getString(R.string.no_external);
        else if (external.maxX > 0 && external.maxY > 0)
            line = external.name + "  " + external.maxX + "×" + external.maxY;
        else line = external.name;
        pageSub.setVisibility(View.VISIBLE);
        if (!line.equals(deviceLine)) {
            deviceLine = line;
            pageSub.setText(line);
        }
        syncAppsDevice();
    }

    /** Keep the previous pen across the 0100-to-0104 gap. A real unplug lasts longer than that. */
    private boolean holdExternal() {
        if (deviceLine.isEmpty() || deviceLine.equals(getString(R.string.no_external))) return false;
        long now = android.os.SystemClock.elapsedRealtime();
        if (externalGoneAt < 0) externalGoneAt = now;
        if (now - externalGoneAt < EXTERNAL_GAP_MS) return true;
        externalGoneAt = -1;
        return false;
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
        if (maxX <= 0 || maxY <= 0) {
            offerMatch(pen);
            return;
        }
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
            stampDisplay();
            String json = configJson();
            io.execute(() -> RootStore.write(MainActivity.this, json));
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
        RootStore.useProfile(p);
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
        MaterialSwitch pointer = internalPage.findViewById(R.id.internal_pointer);
        pointer.setChecked(p.internalPointer);
        checkMount(p);
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

    /** Writes the panel size into the config when it changes. The daemon reads that instead of dumpsys. */
    private void publishDisplay() {
        if (!stampDisplay()) return;
        String json = configJson();
        io.execute(() -> RootStore.write(MainActivity.this, json));
    }

    private boolean stampDisplay() {
        RootStore.Screen screen = RootStore.currentScreen(this);
        if (screen.width <= 0 || screen.height <= 0) return false;
        if (profile.hasDisplay && profile.displayW == screen.width && profile.displayH == screen.height
                && profile.displayRotation == screen.rotation) return false;
        profile.hasDisplay = true;
        profile.displayW = screen.width;
        profile.displayH = screen.height;
        profile.displayRotation = screen.rotation;
        return true;
    }

    private void applyWindowSize() {
        int[] window = readWindowSize();
        if (window[0] <= 0 || window[1] <= 0) return;
        if (window[0] == screenW && window[1] == screenH) return;
        screenW = window[0];
        screenH = window[1];
        board.setAxes(tabletMaxX, tabletMaxY, screenW, screenH);
        board.setMapping(profile.mode, profile.crop, profile.hasRect, profile.rect, profile.hasScreen, toFloat(profile.screen));
        publishDisplay();
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

    private void checkMount(Profile p) {
        com.google.android.material.chip.ChipGroup mounts = internalPage.findViewById(R.id.internal_orientation);
        int id = R.id.mount_0;
        if (p.internalOrientation == 90) id = R.id.mount_90;
        else if (p.internalOrientation == 180) id = R.id.mount_180;
        else if (p.internalOrientation == 270) id = R.id.mount_270;
        mounts.check(id);
    }

    private void wireInternal() {
        MaterialSwitch internal = internalPage.findViewById(R.id.internal_pen);
        internal.setOnCheckedChangeListener((button, checked) -> {
            if (!filling) profile.internalOn = checked;
        });
        MaterialSwitch pointer = internalPage.findViewById(R.id.internal_pointer);
        pointer.setOnCheckedChangeListener((button, checked) -> {
            if (!filling) profile.internalPointer = checked;
        });
        com.google.android.material.chip.ChipGroup mounts = internalPage.findViewById(R.id.internal_orientation);
        mounts.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (filling || checkedIds.isEmpty()) return;
            int id = checkedIds.get(0);
            int degrees = 0;
            if (id == R.id.mount_90) degrees = 90;
            else if (id == R.id.mount_180) degrees = 180;
            else if (id == R.id.mount_270) degrees = 270;
            profile.internalOrientation = degrees;
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
        stampDisplay();
        String json = configJson();
        io.execute(() -> {
            String code = RootStore.write(MainActivity.this, json);
            runOnUiThread(() -> {
                String message = writeMessage(code);
                Snackbar.make(root, message, Snackbar.LENGTH_LONG).show();
                if (haveRoot) status.setText(message + "\n\n" + status.getText());
            });
        });
    }

    private void importConfig(Uri uri) {
        if (uri == null) return;
        io.execute(() -> {
            String text;
            try {
                text = readUri(uri);
            } catch (IOException e) {
                text = null;
            }
            String body = text;
            if (body == null) {
                runOnUiThread(() -> Snackbar.make(root, R.string.write_failed, Snackbar.LENGTH_LONG).show());
                return;
            }
            try {
                Profile.parse(body);
            } catch (Exception e) {
                runOnUiThread(() -> Snackbar.make(root, R.string.config_parse_failed, Snackbar.LENGTH_LONG).show());
                return;
            }
            String code = RootStore.write(MainActivity.this, body);
            runOnUiThread(() -> {
                boolean ok = "local".equals(code) || "notified".equals(code) || "stopped".equals(code) || "written".equals(code);
                Snackbar.make(root, ok ? R.string.config_loaded : R.string.write_failed, Snackbar.LENGTH_LONG).show();
                if (ok) load();
            });
        });
    }

    private String readUri(Uri uri) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (out.size() + n > 65536) return null;
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    private void adoptLibrary() {
        PresetStore.install(this, profile, getString(R.string.preset_current));
        editorLive = false;
        editPackage = null;
        editAppLabel = "";
        editingPresetId = "";
        loadPreset(baselineId());
        editorLive = true;
        refreshEditLabel();
        updateMappingNav();
        bindApps();
    }

    private String configJson() {
        PresetStore.capture(profile, editingPresetId);
        return profile.toJson();
    }

    private void writeConfig() {
        writeConfig(true);
    }

    private void writeConfig(boolean announce) {
        String json = configJson();
        io.execute(() -> {
            String code = RootStore.write(MainActivity.this, json);
            if (!announce) return;
            runOnUiThread(() -> Snackbar.make(root, writeMessage(code), Snackbar.LENGTH_LONG).show());
        });
    }

    private String deviceKey() {
        return editingInternal ? "internal" : "external";
    }

    private String baselineId() {
        PresetStore.Device device = PresetStore.device(deviceKey());
        return device == null || device.baseline == null ? "" : device.baseline;
    }

    private String deviceTitle() {
        if (editingInternal) return getString(R.string.pen_internal_short);
        if (externalTitle != null && !externalTitle.isEmpty()) return externalTitle;
        return getString(R.string.pen_external);
    }

    private void refreshEditLabel() {
        if (editTarget == null) return;
        if (editPackage == null) {
            editTarget.setText(getString(R.string.edit_global, deviceTitle()));
            return;
        }
        PresetStore.Item item = PresetStore.find(editingPresetId);
        String preset = item == null ? editingPresetId : item.name;
        editTarget.setText(getString(R.string.edit_app, deviceTitle(), editAppLabel, preset));
    }

    private void updateMappingNav() {
        setMappingVisible(externalPresent && !editingInternal);
    }

    private void flushEditor() {
        if (!editorLive || editingPresetId == null || editingPresetId.isEmpty()) return;
        PresetStore.capture(profile, editingPresetId);
    }

    private void loadPreset(String id) {
        PresetStore.Item item = PresetStore.find(id);
        if (item == null) return;
        boolean was = editorLive;
        editorLive = false;
        profile.copyFeelFrom(item);
        editingPresetId = id;
        showProfile(profile);
        editorLive = was;
    }

    private void focusGlobal() {
        String next = baselineId();
        if (editPackage == null && next.equals(editingPresetId)) {
            refreshEditLabel();
            refreshSelection();
            return;
        }
        flushEditor();
        editPackage = null;
        editAppLabel = "";
        loadPreset(next);
        refreshEditLabel();
        updateMappingNav();
        refreshSelection();
    }

    private void focusApp(String pkg, String label, String presetId) {
        if (presetId == null || presetId.isEmpty()) {
            focusGlobal();
            return;
        }
        if (pkg.equals(editPackage) && presetId.equals(editingPresetId)) {
            refreshEditLabel();
            refreshSelection();
            return;
        }
        flushEditor();
        editPackage = pkg;
        editAppLabel = label;
        loadPreset(presetId);
        refreshEditLabel();
        updateMappingNav();
        refreshSelection();
    }

    private void chooseEditDevice(boolean internal) {
        if (internal == editingInternal && editorLive) return;
        flushEditor();
        editingInternal = internal;
        editPackage = null;
        editAppLabel = "";
        editorLive = false;
        editingPresetId = "";
        loadPreset(baselineId());
        editorLive = true;
        refreshEditLabel();
        updateMappingNav();
        bindApps();
    }

    /** Without an external tablet the apps page stays on the internal pen. */
    private void syncAppsDevice() {
        if (appsDevice == null) return;
        if (!externalPresent && (!editingInternal || !editorLive)) chooseEditDevice(true);
        int want = editingInternal ? R.id.apps_internal : R.id.apps_external;
        if (appsDevice.getCheckedButtonId() != want) {
            filling = true;
            appsDevice.check(want);
            filling = false;
        }
        View externalBtn = appsDevice.findViewById(R.id.apps_external);
        if (externalBtn.isEnabled() != externalPresent) externalBtn.setEnabled(externalPresent);
    }

    /** Package icons are drawn once. Scrolling then blits a small bitmap. */
    private Drawable raster(Drawable raw) {
        if (raw == null) return null;
        try {
            int size = Math.round(40f * getResources().getDisplayMetrics().density);
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            Drawable copy = raw.mutate();
            copy.setBounds(0, 0, size, size);
            copy.draw(canvas);
            bitmap.setDensity(getResources().getDisplayMetrics().densityDpi);
            return new BitmapDrawable(getResources(), bitmap);
        } catch (RuntimeException e) {
            return raw;
        }
    }

    private List<AppEntry> loadApps() {
        List<AppEntry> list = new ArrayList<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        PackageManager pm = getPackageManager();
        Intent launch = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo info : pm.queryIntentActivities(launch, 0)) {
            if (info.activityInfo == null) continue;
            String pkg = info.activityInfo.packageName;
            if (pkg == null || pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
            AppEntry entry = new AppEntry();
            entry.pkg = pkg;
            entry.label = info.loadLabel(pm).toString();
            entry.icon = raster(info.loadIcon(pm));
            list.add(entry);
        }
        list.sort(Comparator.comparing(entry -> entry.label, Collator.getInstance()));
        return list;
    }

    private void bindApps() {
        shownApps.clear();
        AppEntry global = new AppEntry();
        global.pkg = "";
        global.label = getString(R.string.app_global);
        shownApps.add(global);
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (AppEntry entry : apps) {
            seen.add(entry.pkg);
            shownApps.add(entry);
        }
        PresetStore.Device device = PresetStore.device(deviceKey());
        if (device != null) {
            for (String pkg : device.apps.keySet()) {
                if (seen.contains(pkg)) continue;
                AppEntry extra = new AppEntry();
                extra.pkg = pkg;
                extra.label = pkg;
                try {
                    extra.icon = raster(getPackageManager().getApplicationIcon(pkg));
                    extra.label = getPackageManager().getApplicationLabel(
                            getPackageManager().getApplicationInfo(pkg, 0)).toString();
                } catch (PackageManager.NameNotFoundException ignored) {
                }
                shownApps.add(extra);
            }
        }
        if (appList != null && appList.getAdapter() != null) appList.getAdapter().notifyDataSetChanged();
    }

    private AppEntry rowAt(RecyclerView.ViewHolder holder) {
        int pos = holder.getBindingAdapterPosition();
        if (pos < 0 || pos >= shownApps.size()) return null;
        return shownApps.get(pos);
    }

    private String spinnerPreset(Spinner spinner) {
        Object tag = spinner.getTag();
        int position = spinner.getSelectedItemPosition();
        if (!(tag instanceof List) || position < 0 || position >= ((List<?>) tag).size()) return "";
        String id = String.valueOf(((List<?>) tag).get(position));
        return "null".equals(id) ? "" : id;
    }

    private void deletePreset(Spinner spinner) {
        String id = spinnerPreset(spinner);
        if (id.isEmpty()) {
            Snackbar.make(root, R.string.preset_need_selection, Snackbar.LENGTH_LONG).show();
            return;
        }
        for (PresetStore.Device device : PresetStore.devices()) {
            if (id.equals(device.baseline)) {
                Snackbar.make(root, R.string.preset_is_baseline, Snackbar.LENGTH_LONG).show();
                return;
            }
        }
        boolean editingThis = id.equals(editingPresetId);
        PresetStore.delete(id);
        if (editingThis) {
            editingPresetId = "";
            editPackage = null;
            editAppLabel = "";
            focusGlobal();
        }
        bindApps();
        writeConfig(false);
        Snackbar.make(root, R.string.preset_deleted, Snackbar.LENGTH_LONG).show();
    }

    private String devicePreset(String pkg) {
        PresetStore.Device device = PresetStore.device(deviceKey());
        if (device == null) return null;
        String id = device.apps.get(pkg);
        return id == null || id.isEmpty() ? null : id;
    }

    private void fillPresetSpinner(Spinner spinner, String selectedId) {
        List<String> ids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        ids.add("");
        names.add(getString(R.string.preset_pick));
        int selection = 0;
        int index = 1;
        for (PresetStore.Item item : PresetStore.items()) {
            ids.add(item.id);
            names.add(item.name);
            if (item.id.equals(selectedId)) selection = index;
            index++;
        }
        bindingRows++;
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setTag(ids);
        spinner.setSelection(selection);
        bindingRows--;
    }

    private void promptRename(Spinner spinner) {
        String id = spinnerPreset(spinner);
        PresetStore.Item item = id.isEmpty() ? null : PresetStore.find(id);
        if (item == null) {
            Snackbar.make(root, R.string.preset_need_selection, Snackbar.LENGTH_LONG).show();
            return;
        }
        EditText input = new EditText(this);
        input.setHint(R.string.preset_name);
        input.setText(item.name);
        if (input.getText() != null) input.setSelection(input.getText().length());
        new AlertDialog.Builder(this)
                .setTitle(R.string.preset_rename)
                .setView(input)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, (dialog, which) -> {
                    String name = input.getText() == null ? "" : input.getText().toString();
                    if (!PresetStore.rename(id, name)) return;
                    refreshEditLabel();
                    if (appList != null && appList.getAdapter() != null) appList.getAdapter().notifyDataSetChanged();
                })
                .show();
    }

    private void promptNewPreset(AppEntry entry) {
        if (!PresetStore.canAdd()) {
            Snackbar.make(root, R.string.preset_limit, Snackbar.LENGTH_LONG).show();
            return;
        }
        EditText input = new EditText(this);
        input.setHint(R.string.preset_name);
        new AlertDialog.Builder(this)
                .setTitle(R.string.preset_add)
                .setView(input)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, (dialog, which) -> {
                    String name = input.getText() == null ? "" : input.getText().toString();
                    flushEditor();
                    PresetStore.Item created = PresetStore.fork(baselineId(), name);
                    if (created == null) {
                        Snackbar.make(root, R.string.preset_limit, Snackbar.LENGTH_LONG).show();
                        return;
                    }
                    if (!PresetStore.addOverride(deviceKey(), entry.pkg, created.id)) {
                        PresetStore.delete(created.id);
                        Snackbar.make(root, R.string.override_limit, Snackbar.LENGTH_LONG).show();
                        return;
                    }
                    focusApp(entry.pkg, entry.label, created.id);
                    bindApps();
                    writeConfig(false);
                    if (!ForegroundWatch.allowed(this)) watchForeground();
                })
                .show();
    }

    private void refreshSelection() {
        if (appList == null || appList.getAdapter() == null) return;
        appList.getAdapter().notifyItemRangeChanged(0, appList.getAdapter().getItemCount(), "sel");
    }

    private final class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {
        @Override
        public Holder onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
            View row = getLayoutInflater().inflate(R.layout.row_app, parent, false);
            return new Holder(row);
        }

        @Override
        public void onBindViewHolder(Holder holder, int position, List<Object> payloads) {
            if (!payloads.isEmpty()) {
                paint(holder, position);
                return;
            }
            super.onBindViewHolder(holder, position, payloads);
        }

        @Override
        public void onBindViewHolder(Holder holder, int position) {
            AppEntry entry = shownApps.get(position);
            boolean global = entry.pkg.isEmpty();
            String title = global ? getString(R.string.app_global) : entry.label;
            if (title != null && !title.contentEquals(holder.label.getText())) holder.label.setText(title);
            Drawable icon = global
                    ? holder.globalIcon(editingInternal)
                    : entry.icon;
            if (icon != null && holder.icon.getDrawable() != icon) holder.icon.setImageDrawable(icon);
            holder.mode.setVisibility(global ? View.GONE : View.VISIBLE);
            if (global) {
                if (holder.customRow != null) holder.customRow.setVisibility(View.GONE);
            } else {
                PresetStore.Device device = PresetStore.device(deviceKey());
                String assigned = device == null ? null : device.apps.get(entry.pkg);
                boolean custom = assigned != null && !assigned.isEmpty();
                holder.showMode(custom);
                if (custom) {
                    holder.ensureCustom();
                    holder.customRow.setVisibility(View.VISIBLE);
                    fillPresetSpinner(holder.spinner, assigned);
                } else if (holder.customRow != null) {
                    holder.customRow.setVisibility(View.GONE);
                }
            }
            paint(holder, position);
        }

        private void paint(Holder holder, int position) {
            AppEntry entry = shownApps.get(position);
            String current = editPackage == null ? "" : editPackage;
            int count = shownApps.size();
            holder.paint(entry.pkg.equals(current) ? rowHighlight : Color.TRANSPARENT,
                    position == 0, count > 0 && position == count - 1);
        }

        @Override
        public int getItemCount() {
            return shownApps.size();
        }

        @Override
        public long getItemId(int position) {
            String pkg = shownApps.get(position).pkg;
            if (pkg.isEmpty()) return 0L;
            return pkg.hashCode() & 0xffffffffL;
        }

        private void openRow(Holder holder) {
            AppEntry entry = rowAt(holder);
            if (entry == null) return;
            if (entry.pkg.isEmpty()) {
                focusGlobal();
                return;
            }
            String current = devicePreset(entry.pkg);
            if (current == null) focusGlobal();
            else focusApp(entry.pkg, entry.label, current);
        }

        final class Holder extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView label;
            final View mode;
            final TextView defBtn;
            final TextView customBtn;
            final GradientDrawable selected = new GradientDrawable();
            final GradientDrawable plate = new GradientDrawable();
            final ViewStub stub;
            View customRow;
            Spinner spinner;
            private Drawable internalIcon;
            private Drawable externalIcon;
            private int painted = Color.TRANSPARENT;
            private boolean roundedTop;
            private boolean roundedBottom;
            private boolean modeSet;
            private boolean customOn;

            Holder(View row) {
                super(row);
                icon = row.findViewById(R.id.app_icon);
                label = row.findViewById(R.id.app_label);
                mode = row.findViewById(R.id.app_mode);
                defBtn = row.findViewById(R.id.app_default);
                customBtn = row.findViewById(R.id.app_custom);
                stub = row.findViewById(R.id.app_custom_stub);
                row.setBackground(plate);
                float density = segmentRadius / 20f;
                GradientDrawable track = new GradientDrawable();
                track.setCornerRadius(segmentRadius);
                track.setStroke(Math.max(1, Math.round(density)), rowOutline);
                mode.setBackground(track);
                selected.setCornerRadius(Math.max(0f, segmentRadius - 3f * density));
                selected.setColor(rowHighlight);
                View.OnClickListener select = v -> openRow(this);
                row.setOnClickListener(select);
                label.setOnClickListener(select);
                icon.setOnClickListener(select);
                defBtn.setOnClickListener(v -> chooseCustom(false));
                customBtn.setOnClickListener(v -> chooseCustom(true));
            }

            void showMode(boolean custom) {
                if (modeSet && customOn == custom) return;
                modeSet = true;
                customOn = custom;
                defBtn.setBackground(custom ? null : selected);
                customBtn.setBackground(custom ? selected : null);
                defBtn.setTextColor(custom ? rowOnSurface : rowOnContainer);
                customBtn.setTextColor(custom ? rowOnContainer : rowOnSurface);
            }

            private void chooseCustom(boolean custom) {
                if (modeSet && customOn == custom) return;
                AppEntry entry = rowAt(this);
                if (entry == null || entry.pkg.isEmpty()) return;
                showMode(custom);
                if (!custom) {
                    PresetStore.removeOverride(deviceKey(), entry.pkg);
                    if (customRow != null) customRow.setVisibility(View.GONE);
                    if (entry.pkg.equals(editPackage)) focusGlobal();
                    else refreshSelection();
                    writeConfig(false);
                } else {
                    ensureCustom();
                    customRow.setVisibility(View.VISIBLE);
                    fillPresetSpinner(spinner, devicePreset(entry.pkg));
                    if (!ForegroundWatch.allowed(MainActivity.this)) watchForeground();
                }
            }

            void paint(int color, boolean top, boolean bottom) {
                if (painted == color && roundedTop == top && roundedBottom == bottom) return;
                painted = color;
                roundedTop = top;
                roundedBottom = bottom;
                float r = listCorner;
                plate.setCornerRadii(new float[] {
                        top ? r : 0f, top ? r : 0f,
                        top ? r : 0f, top ? r : 0f,
                        bottom ? r : 0f, bottom ? r : 0f,
                        bottom ? r : 0f, bottom ? r : 0f
                });
                plate.setColor(color);
            }

            Drawable globalIcon(boolean internal) {
                if (internal) {
                    if (internalIcon == null) internalIcon = raster(icon.getContext().getDrawable(android.R.drawable.ic_menu_slideshow));
                    return internalIcon;
                }
                if (externalIcon == null) externalIcon = raster(icon.getContext().getDrawable(android.R.drawable.ic_menu_edit));
                return externalIcon;
            }

            void ensureCustom() {
                if (customRow != null) return;
                customRow = stub.inflate();
                spinner = customRow.findViewById(R.id.app_presets);
                spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                        if (bindingRows > 0 || position <= 0) return;
                        AppEntry entry = rowAt(Holder.this);
                        if (entry == null || entry.pkg.isEmpty()) return;
                        Object tag = parent.getTag();
                        if (!(tag instanceof List) || position >= ((List<?>) tag).size()) return;
                        Object value = ((List<?>) tag).get(position);
                        String presetId = value == null ? "" : value.toString();
                        if (presetId.isEmpty() || presetId.equals(devicePreset(entry.pkg))) return;
                        if (!PresetStore.addOverride(deviceKey(), entry.pkg, presetId)) {
                            Snackbar.make(root, R.string.override_limit, Snackbar.LENGTH_LONG).show();
                            return;
                        }
                        focusApp(entry.pkg, entry.label, presetId);
                        writeConfig(false);
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {
                    }
                });
                customRow.findViewById(R.id.app_rename).setOnClickListener(v -> promptRename(spinner));
                customRow.findViewById(R.id.app_add).setOnClickListener(v -> {
                    AppEntry entry = rowAt(this);
                    if (entry != null && !entry.pkg.isEmpty()) promptNewPreset(entry);
                });
                customRow.findViewById(R.id.app_delete).setOnClickListener(v -> deletePreset(spinner));
            }
        }
    }

    private void refreshWatchState() {
        if (watchState == null) return;
        watchState.setText(ForegroundWatch.allowed(this) ? R.string.watch_running : R.string.watch_blocked);
    }

    private void watchForeground() {
        if (!ForegroundWatch.allowed(this)) {
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
            Snackbar.make(root, R.string.watch_need_access, Snackbar.LENGTH_LONG).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 41);
        }
        startWatch();
        Snackbar.make(root, R.string.watch_on, Snackbar.LENGTH_LONG).show();
    }

    private void startWatch() {
        Intent intent = new Intent(this, ForegroundWatch.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
        else startService(intent);
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
