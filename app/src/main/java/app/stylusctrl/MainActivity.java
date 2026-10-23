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

import java.util.Locale;
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
        wireInternal();

        NavigationRailView rail = findViewById(R.id.rail);
        rail.setOnItemSelectedListener(this::showPage);
        rail.setSelectedItemId(R.id.nav_map);
        findViewById(R.id.save).setOnClickListener(v -> save());
        showProfile(profile);
        load();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        root.post(this::applyWindowSize);
    }

    @Override
    protected void onDestroy() {
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
        pageSub.setText("正在向 root 读取配置…");
        io.execute(() -> {
            RootStore.Snapshot snap = RootStore.read();
            runOnUiThread(() -> applySnapshot(snap));
        });
    }

    private void applySnapshot(RootStore.Snapshot snap) {
        if (!snap.root) {
            String message = snap.error == null ? "没有 root。Magisk 需要允许这个应用。" : snap.error;
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
        if (snap.configText != null) {
            try {
                profile = Profile.parse(snap.configText);
            } catch (Exception e) {
                pageSub.setText("配置解析失败，界面用默认值");
            }
        }
        showProfile(profile);
        String daemon = snap.daemonPid == null || snap.daemonPid.isEmpty()
                ? "守护进程没在跑。保存会写配置，刷上模块并重启后才生效。"
                : "守护进程 pid " + snap.daemonPid;
        String screen = snap.screenW > 0 ? "\n屏幕 " + snap.screenW + "×" + snap.screenH : "\n没有读到活动中的屏幕";
        status.setText(daemon + screen + "\n" + RootStore.axesSummary(snap.devices));
        pageSub.setText(snap.daemonPid == null || snap.daemonPid.isEmpty() ? "守护进程没在跑" : "外接数位板");
    }

    private void showProfile(Profile p) {
        filling = true;
        board.setAxes(tabletMaxX, tabletMaxY, screenW, screenH);
        board.setRotation(p.rotation);
        board.setMapping(p.mode, p.crop, p.hasRect, p.rect, p.hasScreen, toFloat(p.screen));
        checkPreset(p);
        checkRotation(p);
        curve.setPoints(absoluteCurve(p));
        showCurveReadout(curve.getPoints());
        MaterialButtonToggleGroup stylus = buttonsPage.findViewById(R.id.stylus_action);
        stylus.check("shortcut".equals(p.stylusAction) ? R.id.stylus_shortcut : R.id.stylus_pass);
        MaterialButtonToggleGroup rubber = buttonsPage.findViewById(R.id.rubber_action);
        int rubberId = R.id.rubber_eraser;
        if ("shortcut".equals(p.rubberAction)) rubberId = R.id.rubber_shortcut;
        else if ("stylus2".equals(p.rubberAction)) rubberId = R.id.rubber_stylus2;
        rubber.check(rubberId);
        MaterialSwitch eraser = buttonsPage.findViewById(R.id.eraser_wins);
        eraser.setChecked(p.eraserWins);
        MaterialSwitch internal = internalPage.findViewById(R.id.internal_pen);
        internal.setChecked(p.internalOn);
        refreshChords();
        showChordRows();
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
        profile.deadzone = 0;
        profile.ceiling = 1;
        profile.curve = new double[] {0, 0, 0.4, 0.7, 0.4, 0.7, 1, 1};
        float[] xy = absoluteCurve(profile);
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
            profile.stylusAction = checkedId == R.id.stylus_shortcut ? "shortcut" : "passthrough";
            showChordRows();
        });
        rubber.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (filling || !isChecked) return;
            if (checkedId == R.id.rubber_shortcut) profile.rubberAction = "shortcut";
            else if (checkedId == R.id.rubber_stylus2) profile.rubberAction = "stylus2";
            else profile.rubberAction = "eraser";
            showChordRows();
        });
        MaterialSwitch eraser = buttonsPage.findViewById(R.id.eraser_wins);
        eraser.setOnCheckedChangeListener((button, checked) -> {
            if (!filling) profile.eraserWins = checked;
        });
        MaterialButton stylusAdd = buttonsPage.findViewById(R.id.stylus_add);
        MaterialButton rubberAdd = buttonsPage.findViewById(R.id.rubber_add);
        stylusAdd.setOnClickListener(v -> addKey(profile.stylusKeys));
        rubberAdd.setOnClickListener(v -> addKey(profile.rubberKeys));
    }

    private void wireInternal() {
        MaterialSwitch internal = internalPage.findViewById(R.id.internal_pen);
        internal.setOnCheckedChangeListener((button, checked) -> {
            if (!filling) profile.internalOn = checked;
        });
    }

    private void addKey(java.util.List<String> keys) {
        if (keys.size() >= KeyChord.MAX) {
            Snackbar.make(root, "一组快捷键最多八个", Snackbar.LENGTH_SHORT).show();
            return;
        }
        KeyChord.pick(this, keys, this::refreshChords);
    }

    private void refreshChords() {
        ChipGroup stylus = buttonsPage.findViewById(R.id.stylus_chord);
        ChipGroup rubber = buttonsPage.findViewById(R.id.rubber_chord);
        KeyChord.render(stylus, profile.stylusKeys, () -> {});
        KeyChord.render(rubber, profile.rubberKeys, () -> {});
    }

    private void showChordRows() {
        buttonsPage.findViewById(R.id.stylus_chord_row)
                .setVisibility("shortcut".equals(profile.stylusAction) ? View.VISIBLE : View.GONE);
        buttonsPage.findViewById(R.id.rubber_chord_row)
                .setVisibility("shortcut".equals(profile.rubberAction) ? View.VISIBLE : View.GONE);
    }

    private void storeCurve(float[] xy) {
        float lo = xy[0];
        float hi = xy[6];
        if (hi - lo < 0.02f) hi = Math.min(1f, lo + 0.02f);
        profile.deadzone = lo;
        profile.ceiling = hi;
        double span = hi - lo;
        for (int i = 0; i < 4; i++) {
            profile.curve[i * 2] = (xy[i * 2] - lo) / span;
            profile.curve[i * 2 + 1] = xy[i * 2 + 1];
        }
    }

    private void showCurveReadout(float[] xy) {
        curveReadout.setText(String.format(Locale.US,
                "低于 %.0f%% 保持起点，高于 %.0f%% 顶到终点", xy[0] * 100f, xy[6] * 100f));
    }

    private float[] absoluteCurve(Profile p) {
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

    private void save() {
        for (String key : profile.stylusKeys) {
            if (!KeyChord.known(key)) {
                Snackbar.make(root, "侧键里有不认识的键", Snackbar.LENGTH_LONG).show();
                return;
            }
        }
        for (String key : profile.rubberKeys) {
            if (!KeyChord.known(key)) {
                Snackbar.make(root, "橡皮位里有不认识的键", Snackbar.LENGTH_LONG).show();
                return;
            }
        }
        if ("shortcut".equals(profile.stylusAction) && profile.stylusKeys.isEmpty()) {
            Snackbar.make(root, "侧键快捷键至少要一个键", Snackbar.LENGTH_LONG).show();
            return;
        }
        if ("shortcut".equals(profile.rubberAction) && profile.rubberKeys.isEmpty()) {
            Snackbar.make(root, "橡皮位快捷键至少要一个键", Snackbar.LENGTH_LONG).show();
            return;
        }
        String json = profile.toJson();
        pageSub.setText("正在写入…");
        io.execute(() -> {
            String message = RootStore.write(json);
            runOnUiThread(() -> {
                pageSub.setText(message);
                Snackbar.make(root, message, Snackbar.LENGTH_LONG).show();
                status.setText(message + "\n\n" + status.getText());
            });
        });
    }
}
