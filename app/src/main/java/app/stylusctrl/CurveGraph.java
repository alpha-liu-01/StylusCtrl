package app.stylusctrl;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.google.android.material.color.MaterialColors;

/** Four draggable cubic points. X is input pressure, Y is output. Endpoints are the two deadzones. */
public class CurveGraph extends View {
    public interface Listener {
        void onCurveChanged(float[] xy);
    }

    private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint curvePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF plot = new RectF();
    private final float[] pts = new float[] {0f, 0f, 0.4f, 0.7f, 0.4f, 0.7f, 1f, 1f};
    private Listener listener;
    private int drag = -1;

    public CurveGraph(Context context, AttributeSet attrs) {
        super(context, attrs);
        int primary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary, 0xFF1B6B66);
        int outline = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutline, 0xFF7D7670);
        int onSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, 0xFF1C1B1A);
        axisPaint.setStyle(Paint.Style.STROKE);
        axisPaint.setStrokeWidth(dp(1.5f));
        axisPaint.setColor(outline);
        guidePaint.setStyle(Paint.Style.STROKE);
        guidePaint.setStrokeWidth(dp(1));
        guidePaint.setColor(outline);
        guidePaint.setAlpha(120);
        curvePaint.setStyle(Paint.Style.STROKE);
        curvePaint.setStrokeWidth(dp(3));
        curvePaint.setStrokeCap(Paint.Cap.ROUND);
        curvePaint.setColor(primary);
        handlePaint.setColor(primary);
        labelPaint.setColor(onSurface);
        labelPaint.setTextSize(dp(13));
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setPoints(float[] xy) {
        if (xy == null || xy.length < 8) return;
        System.arraycopy(xy, 0, pts, 0, 8);
        invalidate();
    }

    public float[] getPoints() {
        return pts.clone();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        layoutPlot();
        canvas.drawText("轻", plot.left, getHeight() - dp(6), labelPaint);
        labelPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("重", plot.right, getHeight() - dp(6), labelPaint);
        labelPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("输出", plot.left, plot.top - dp(8), labelPaint);
        canvas.drawLine(plot.left, plot.bottom, plot.right, plot.bottom, axisPaint);
        canvas.drawLine(plot.left, plot.bottom, plot.left, plot.top, axisPaint);
        canvas.drawLine(plot.left, plot.bottom, plot.right, plot.top, guidePaint);

        float x0 = plotX(pts[0]);
        float y0 = plotY(pts[1]);
        float x3 = plotX(pts[6]);
        float y3 = plotY(pts[7]);
        canvas.drawLine(plot.left, y0, x0, y0, curvePaint);
        canvas.drawLine(x3, y3, plot.right, y3, curvePaint);

        path.reset();
        path.moveTo(x0, y0);
        path.cubicTo(plotX(pts[2]), plotY(pts[3]), plotX(pts[4]), plotY(pts[5]), x3, y3);
        canvas.drawPath(path, curvePaint);
        for (int i = 0; i < 4; i++) {
            canvas.drawCircle(plotX(pts[i * 2]), plotY(pts[i * 2 + 1]), dp(i == 0 || i == 3 ? 9 : 7), handlePaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        layoutPlot();
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            getParent().requestDisallowInterceptTouchEvent(true);
            drag = hit(event.getX(), event.getY());
            if (drag < 0) return false;
            move(event.getX(), event.getY());
            return true;
        }
        if (drag < 0) return false;
        if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP) {
            move(event.getX(), event.getY());
            if (action == MotionEvent.ACTION_UP) {
                drag = -1;
                getParent().requestDisallowInterceptTouchEvent(false);
            }
            return true;
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            drag = -1;
            getParent().requestDisallowInterceptTouchEvent(false);
            return true;
        }
        return false;
    }

    private void move(float x, float y) {
        float nx = clamp((x - plot.left) / plot.width(), 0f, 1f);
        float ny = clamp(1f - (y - plot.top) / plot.height(), 0f, 1f);
        float gap = 0.02f;
        float min = drag == 0 ? 0f : pts[(drag - 1) * 2] + gap;
        float max = drag == 3 ? 1f : pts[(drag + 1) * 2] - gap;
        if (max < min) max = min;
        pts[drag * 2] = clamp(nx, min, max);
        pts[drag * 2 + 1] = ny;
        if (listener != null) listener.onCurveChanged(getPoints());
        invalidate();
    }

    private int hit(float x, float y) {
        float slop = dp(28);
        for (int i = 3; i >= 0; i--) {
            float hx = plotX(pts[i * 2]);
            float hy = plotY(pts[i * 2 + 1]);
            if (Math.hypot(x - hx, y - hy) <= slop) return i;
        }
        return -1;
    }

    private void layoutPlot() {
        float left = getPaddingLeft() + dp(12);
        float right = getWidth() - getPaddingRight() - dp(12);
        float top = getPaddingTop() + dp(28);
        float bottom = getHeight() - getPaddingBottom() - dp(28);
        float size = Math.min(right - left, bottom - top);
        float cx = (left + right) / 2f;
        float cy = (top + bottom) / 2f;
        plot.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f);
    }

    private float plotX(float t) {
        return plot.left + plot.width() * t;
    }

    private float plotY(float t) {
        return plot.bottom - plot.height() * t;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
