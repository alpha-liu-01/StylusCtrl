package app.stylusctrl;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.google.android.material.color.MaterialColors;

/**
 * Tablet area on the left, screen area on the right, with a line between each pair of corners.
 * Dragging a corner, an edge, or the inside of either rectangle reports axis-aligned rects.
 */
public class MappingBoard extends View {
    public interface Listener {
        void onRectsChanged(int[] tablet, float[] screen);
    }

    private static final int NONE = 0;
    private static final int BODY = 1;
    private static final int TL = 2;
    private static final int TR = 3;
    private static final int BR = 4;
    private static final int BL = 5;
    private static final int EDGE_L = 6;
    private static final int EDGE_R = 7;
    private static final int EDGE_T = 8;
    private static final int EDGE_B = 9;

    private final Paint padPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint areaPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tabletPad = new RectF();
    private final RectF screenPad = new RectF();

    private int maxX = 15200;
    private int maxY = 9500;
    private int screenW = 2000;
    private int screenH = 1200;
    private int rotation;
    private String mode = "fit";
    private String crop = "center";
    private boolean customTablet;
    private boolean customScreen;
    private final int[] source = new int[] {0, 0, 15200, 9500};
    private final float[] dest = new float[] {0, 0, 1, 1};

    private Listener listener;
    private int dragPad;
    private int dragPart;
    private float grabX;
    private float grabY;
    private final float[] dragOrigin = new float[4];

    public MappingBoard(Context context, AttributeSet attrs) {
        super(context, attrs);
        int primary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary, 0xFF1B6B66);
        int surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceVariant, 0xFFE7E0D8);
        int onSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, 0xFF1C1B1A);
        padPaint.setColor(surface);
        areaPaint.setStyle(Paint.Style.FILL);
        areaPaint.setColor(primary);
        areaPaint.setAlpha(56);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(dp(2));
        linePaint.setColor(primary);
        handlePaint.setColor(primary);
        labelPaint.setColor(onSurface);
        labelPaint.setTextSize(dp(14));
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setRotation(int degrees) {
        int quarter = ((degrees % 360) + 360) % 360 / 90;
        if (quarter == this.rotation) return;
        this.rotation = quarter;
        rebuildShown();
        invalidate();
    }

    public int visualMaxX() {
        return (rotation & 1) == 1 ? maxY : maxX;
    }

    public int visualMaxY() {
        return (rotation & 1) == 1 ? maxX : maxY;
    }

    /** Move a rectangle from one tablet rotation into another, keeping the same physical area. */
    public static int[] turnRect(int[] rect, int fromDegrees, int toDegrees, int maxX, int maxY) {
        int from = ((fromDegrees % 360) + 360) % 360 / 90;
        int to = ((toDegrees % 360) + 360) % 360 / 90;
        int width = maxX + 1;
        int height = maxY + 1;
        int[] xs = {rect[0], rect[2], rect[0], rect[2]};
        int[] ys = {rect[1], rect[1], rect[3], rect[3]};
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxOutX = Integer.MIN_VALUE;
        int maxOutY = Integer.MIN_VALUE;
        double[] tmp = new double[2];
        for (int i = 0; i < 4; i++) {
            unrotatePt(from, width, height, xs[i], ys[i], tmp);
            rotatePt(to, width, height, tmp[0], tmp[1], tmp);
            int x = (int) Math.round(tmp[0]);
            int y = (int) Math.round(tmp[1]);
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxOutX = Math.max(maxOutX, x);
            maxOutY = Math.max(maxOutY, y);
        }
        int limitX = (to & 1) == 1 ? maxY : maxX;
        int limitY = (to & 1) == 1 ? maxX : maxY;
        return new int[] {
                clamp(minX, 0, limitX),
                clamp(minY, 0, limitY),
                clamp(maxOutX, 0, limitX),
                clamp(maxOutY, 0, limitY)
        };
    }

    private static void rotatePt(int quarter, int width, int height, double u, double v, double[] out) {
        switch (quarter & 3) {
            case 1:
                out[0] = v;
                out[1] = (width - 1) - u;
                break;
            case 2:
                out[0] = (width - 1) - u;
                out[1] = (height - 1) - v;
                break;
            case 3:
                out[0] = (height - 1) - v;
                out[1] = u;
                break;
            default:
                out[0] = u;
                out[1] = v;
                break;
        }
    }

    private static void unrotatePt(int quarter, int width, int height, double u, double v, double[] out) {
        switch (quarter & 3) {
            case 1:
                out[0] = (width - 1) - v;
                out[1] = u;
                break;
            case 2:
                out[0] = (width - 1) - u;
                out[1] = (height - 1) - v;
                break;
            case 3:
                out[0] = v;
                out[1] = (height - 1) - u;
                break;
            default:
                out[0] = u;
                out[1] = v;
                break;
        }
    }

    public void setAxes(int maxX, int maxY, int screenW, int screenH) {
        if (maxX > 0) this.maxX = maxX;
        if (maxY > 0) this.maxY = maxY;
        if (screenW > 0 && screenH > 0) {
            this.screenW = screenW;
            this.screenH = screenH;
        }
        rebuildShown();
        invalidate();
    }

    public void setMapping(String mode, String crop, boolean customTablet, int[] tablet,
                           boolean customScreen, float[] screen) {
        this.mode = mode == null ? "fit" : mode;
        this.crop = crop == null ? "center" : crop;
        this.customTablet = customTablet;
        this.customScreen = customScreen;
        if (tablet != null && tablet.length == 4) System.arraycopy(tablet, 0, source, 0, 4);
        if (screen != null && screen.length == 4) System.arraycopy(screen, 0, dest, 0, 4);
        rebuildShown();
        invalidate();
    }

    private final int[] shownTablet = new int[4];
    private final float[] shownScreen = new float[4];

    private void rebuildShown() {
        int limitX = visualMaxX();
        int limitY = visualMaxY();
        int x0 = customTablet ? source[0] : 0;
        int y0 = customTablet ? source[1] : 0;
        int x1 = customTablet ? source[2] : limitX;
        int y1 = customTablet ? source[3] : limitY;
        order(shownTablet, x0, y0, x1, y1, limitX, limitY);
        if ("fit".equals(mode)) fitWindow(crop, shownTablet, screenW, screenH);
        if (customScreen) {
            System.arraycopy(dest, 0, shownScreen, 0, 4);
        } else {
            shownScreen[0] = 0;
            shownScreen[1] = 0;
            shownScreen[2] = 1;
            shownScreen[3] = 1;
        }
        orderFloat(shownScreen);
    }

    /** Cover-fit window inside the current source rect. Matches the daemon's crop names. */
    static void fitWindow(String crop, int[] rect, int dispW, int dispH) {
        int x0 = rect[0];
        int y0 = rect[1];
        int x1 = rect[2];
        int y1 = rect[3];
        int rw = Math.max(1, x1 - x0 + 1);
        int rh = Math.max(1, y1 - y0 + 1);
        double xscale = (double) dispW / rw;
        double yscale = (double) dispH / rh;
        double fs = Math.max(xscale, yscale);
        if (fs <= 0) return;
        double visW = dispW / fs;
        double visH = dispH / fs;
        double originX = 0;
        double originY = 0;
        if (rw - visW > 1) {
            if ("right".equals(crop)) originX = rw - visW;
            else if (!"left".equals(crop)) originX = (rw - visW) / 2.0;
        }
        if (rh - visH > 1) {
            if ("bottom".equals(crop)) originY = rh - visH;
            else if (!"top".equals(crop)) originY = (rh - visH) / 2.0;
        }
        rect[0] = clamp(x0 + (int) Math.round(originX), 0, x1);
        rect[1] = clamp(y0 + (int) Math.round(originY), 0, y1);
        rect[2] = clamp(x0 + (int) Math.round(originX + visW) - 1, rect[0], x1);
        rect[3] = clamp(y0 + (int) Math.round(originY + visH) - 1, rect[1], y1);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        layoutPads();
        canvas.drawRoundRect(tabletPad, dp(16), dp(16), padPaint);
        canvas.drawRoundRect(screenPad, dp(16), dp(16), padPaint);
        RectF tablet = tabletRect();
        RectF screen = screenRect();
        canvas.drawRoundRect(tablet, dp(8), dp(8), areaPaint);
        canvas.drawRoundRect(screen, dp(8), dp(8), areaPaint);
        canvas.drawRoundRect(tablet, dp(8), dp(8), linePaint);
        canvas.drawRoundRect(screen, dp(8), dp(8), linePaint);
        float[] tc = corners(tablet);
        float[] sc = corners(screen);
        for (int i = 0; i < 4; i++) {
            canvas.drawLine(tc[i * 2], tc[i * 2 + 1], sc[i * 2], sc[i * 2 + 1], linePaint);
        }
        drawHandles(canvas, tc);
        drawHandles(canvas, sc);
        canvas.drawText(getContext().getString(R.string.label_tablet), tabletPad.left + dp(12), tabletPad.top + dp(22), labelPaint);
        canvas.drawText(getContext().getString(R.string.label_screen), screenPad.left + dp(12), screenPad.top + dp(22), labelPaint);
    }

    private void drawHandles(Canvas canvas, float[] c) {
        float r = dp(7);
        for (int i = 0; i < 4; i++) canvas.drawCircle(c[i * 2], c[i * 2 + 1], r, handlePaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        layoutPads();
        int action = event.getActionMasked();
        float x = event.getX();
        float y = event.getY();
        if (action == MotionEvent.ACTION_DOWN) {
            getParent().requestDisallowInterceptTouchEvent(true);
            dragPad = hitPad(x, y);
            if (dragPad == 0) return false;
            dragPart = hitPart(dragPad == 1 ? tabletRect() : screenRect(), x, y);
            if (dragPart == NONE) return false;
            grabX = x;
            grabY = y;
            float[] box = dragPad == 1 ? toFrac(shownTablet) : shownScreen.clone();
            System.arraycopy(box, 0, dragOrigin, 0, 4);
            return true;
        }
        if (dragPart == NONE) return false;
        if (action == MotionEvent.ACTION_MOVE) {
            applyDrag(x, y);
            publish();
            invalidate();
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (action == MotionEvent.ACTION_UP) {
                applyDrag(x, y);
                publish();
            }
            dragPart = NONE;
            getParent().requestDisallowInterceptTouchEvent(false);
            invalidate();
            return true;
        }
        return false;
    }

    private void applyDrag(float x, float y) {
        RectF pad = dragPad == 1 ? tabletPad : screenPad;
        float dx = (x - grabX) / Math.max(1f, pad.width());
        float dy = (y - grabY) / Math.max(1f, pad.height());
        float[] next = dragOrigin.clone();
        switch (dragPart) {
            case BODY:
                shift(next, dx, dy);
                break;
            case TL:
                next[0] += dx;
                next[1] += dy;
                break;
            case TR:
                next[2] += dx;
                next[1] += dy;
                break;
            case BR:
                next[2] += dx;
                next[3] += dy;
                break;
            case BL:
                next[0] += dx;
                next[3] += dy;
                break;
            case EDGE_L:
                next[0] += dx;
                break;
            case EDGE_R:
                next[2] += dx;
                break;
            case EDGE_T:
                next[1] += dy;
                break;
            case EDGE_B:
                next[3] += dy;
                break;
            default:
                return;
        }
        orderFloat(next);
        float min = 0.04f;
        if (next[2] - next[0] < min) {
            if (dragPart == EDGE_L || dragPart == TL || dragPart == BL) next[0] = next[2] - min;
            else next[2] = next[0] + min;
        }
        if (next[3] - next[1] < min) {
            if (dragPart == EDGE_T || dragPart == TL || dragPart == TR) next[1] = next[3] - min;
            else next[3] = next[1] + min;
        }
        orderFloat(next);
        if (dragPad == 1) {
            shownTablet[0] = Math.round(next[0] * visualMaxX());
            shownTablet[1] = Math.round(next[1] * visualMaxY());
            shownTablet[2] = Math.round(next[2] * visualMaxX());
            shownTablet[3] = Math.round(next[3] * visualMaxY());
            customTablet = true;
            mode = "stretch";
        } else {
            System.arraycopy(next, 0, shownScreen, 0, 4);
            customScreen = true;
            mode = "stretch";
        }
    }

    private void publish() {
        if (listener == null) return;
        int[] tablet = shownTablet.clone();
        float[] screen = shownScreen.clone();
        listener.onRectsChanged(tablet, screen);
    }

    private int hitPad(float x, float y) {
        if (tabletPad.contains(x, y)) return 1;
        if (screenPad.contains(x, y)) return 2;
        return 0;
    }

    private int hitPart(RectF rect, float x, float y) {
        float slop = dp(22);
        float[] c = corners(rect);
        int[] ids = {TL, TR, BR, BL};
        for (int i = 0; i < 4; i++) {
            if (Math.hypot(x - c[i * 2], y - c[i * 2 + 1]) <= slop) return ids[i];
        }
        boolean insideX = x >= rect.left - slop && x <= rect.right + slop;
        boolean insideY = y >= rect.top - slop && y <= rect.bottom + slop;
        if (insideY && Math.abs(x - rect.left) <= slop) return EDGE_L;
        if (insideY && Math.abs(x - rect.right) <= slop) return EDGE_R;
        if (insideX && Math.abs(y - rect.top) <= slop) return EDGE_T;
        if (insideX && Math.abs(y - rect.bottom) <= slop) return EDGE_B;
        if (rect.contains(x, y)) return BODY;
        return NONE;
    }

    /** Outer corners sit on the bezel: the sides in landscape, the bottom in portrait. */
    private float sideInset() {
        if (getResources().getConfiguration().orientation != Configuration.ORIENTATION_LANDSCAPE) return 0f;
        return dp(48);
    }

    private float bottomInset() {
        if (getResources().getConfiguration().orientation != Configuration.ORIENTATION_PORTRAIT) return 0f;
        return dp(48);
    }

    private void layoutPads() {
        float gap = dp(28);
        float label = dp(28);
        float side = sideInset();
        float left = getPaddingLeft() + side;
        float top = getPaddingTop();
        float right = getWidth() - getPaddingRight() - side;
        float bottom = getHeight() - getPaddingBottom() - bottomInset();
        float w = Math.max(1f, right - left);
        float h = Math.max(1f, bottom - top);
        boolean row = w >= h;
        RectF a = new RectF();
        RectF b = new RectF();
        if (row) {
            float each = (w - gap) / 2f;
            a.set(left, top, left + each, bottom);
            b.set(a.right + gap, a.top, a.right + gap + each, a.bottom);
        } else {
            float each = (h - gap) / 2f;
            a.set(left, top, right, top + each);
            b.set(a.left, a.bottom + gap, a.right, a.bottom + gap + each);
        }
        letterbox(a, visualMaxX(), visualMaxY(), label, tabletPad);
        letterbox(b, screenW, screenH, label, screenPad);
    }

    private void letterbox(RectF slot, int axisW, int axisH, float label, RectF out) {
        float innerTop = slot.top + label;
        float availW = slot.width();
        float availH = Math.max(1f, slot.bottom - innerTop);
        float aspect = axisH <= 0 ? 1f : (float) axisW / (float) axisH;
        float bw = availW;
        float bh = bw / aspect;
        if (bh > availH) {
            bh = availH;
            bw = bh * aspect;
        }
        float left = slot.left + (availW - bw) / 2f;
        float top = innerTop + (availH - bh) / 2f;
        out.set(left, top, left + bw, top + bh);
    }

    private RectF tabletRect() {
        float[] f = toFrac(shownTablet);
        return new RectF(
                lerp(tabletPad.left, tabletPad.right, f[0]),
                lerp(tabletPad.top, tabletPad.bottom, f[1]),
                lerp(tabletPad.left, tabletPad.right, f[2]),
                lerp(tabletPad.top, tabletPad.bottom, f[3]));
    }

    private RectF screenRect() {
        return new RectF(
                lerp(screenPad.left, screenPad.right, shownScreen[0]),
                lerp(screenPad.top, screenPad.bottom, shownScreen[1]),
                lerp(screenPad.left, screenPad.right, shownScreen[2]),
                lerp(screenPad.top, screenPad.bottom, shownScreen[3]));
    }

    private float[] toFrac(int[] raw) {
        return new float[] {
                raw[0] / (float) visualMaxX(),
                raw[1] / (float) visualMaxY(),
                raw[2] / (float) visualMaxX(),
                raw[3] / (float) visualMaxY()
        };
    }

    private static float[] corners(RectF r) {
        return new float[] {r.left, r.top, r.right, r.top, r.right, r.bottom, r.left, r.bottom};
    }

    private static void shift(float[] box, float dx, float dy) {
        float w = box[2] - box[0];
        float h = box[3] - box[1];
        box[0] = clampF(box[0] + dx, 0, 1 - w);
        box[1] = clampF(box[1] + dy, 0, 1 - h);
        box[2] = box[0] + w;
        box[3] = box[1] + h;
    }

    private static void order(int[] out, int x0, int y0, int x1, int y1, int maxX, int maxY) {
        if (x0 > x1) {
            int t = x0;
            x0 = x1;
            x1 = t;
        }
        if (y0 > y1) {
            int t = y0;
            y0 = y1;
            y1 = t;
        }
        out[0] = clamp(x0, 0, maxX);
        out[1] = clamp(y0, 0, maxY);
        out[2] = clamp(x1, 0, maxX);
        out[3] = clamp(y1, 0, maxY);
    }

    private static void orderFloat(float[] box) {
        if (box[0] > box[2]) {
            float t = box[0];
            box[0] = box[2];
            box[2] = t;
        }
        if (box[1] > box[3]) {
            float t = box[1];
            box[1] = box[3];
            box[3] = t;
        }
        box[0] = clampF(box[0], 0, 1);
        box[1] = clampF(box[1], 0, 1);
        box[2] = clampF(box[2], 0, 1);
        box[3] = clampF(box[3], 0, 1);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static float clampF(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
