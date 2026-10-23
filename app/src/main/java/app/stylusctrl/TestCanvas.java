package app.stylusctrl;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** A drawing surface that is not inside a scrolling parent, so a stroke is not cancelled. */
public class TestCanvas extends View {
    public interface SampleListener {
        void onSample(String text);
    }

    private static final class Segment {
        float x0, y0, x1, y1, width;
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Segment> segments = new ArrayList<>();
    private SampleListener listener;
    private float lastX;
    private float lastY;
    private boolean drawing;
    private int ink;

    public TestCanvas(Context context, AttributeSet attrs) {
        super(context, attrs);
        ink = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary, 0xFF1B6B66);
        int paper = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceVariant, 0xFFF4F1EA);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(ink);
        setBackgroundColor(paper);
    }

    public void setSampleListener(SampleListener listener) {
        this.listener = listener;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            getParent().requestDisallowInterceptTouchEvent(true);
            if (android.os.Build.VERSION.SDK_INT >= 31) requestUnbufferedDispatch(event);
            drawing = true;
            lastX = event.getX();
            lastY = event.getY();
            report(event);
            return true;
        }
        if (!drawing) return false;
        if (action == MotionEvent.ACTION_MOVE) {
            int history = event.getHistorySize();
            for (int i = 0; i < history; i++) addSegment(event.getHistoricalX(i), event.getHistoricalY(i), event.getHistoricalPressure(i));
            addSegment(event.getX(), event.getY(), event.getPressure());
            report(event);
            invalidate();
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (action == MotionEvent.ACTION_UP) addSegment(event.getX(), event.getY(), event.getPressure());
            drawing = false;
            getParent().requestDisallowInterceptTouchEvent(false);
            report(event);
            invalidate();
            return true;
        }
        return false;
    }

    private void addSegment(float x, float y, float pressure) {
        Segment segment = new Segment();
        segment.x0 = lastX;
        segment.y0 = lastY;
        segment.x1 = x;
        segment.y1 = y;
        segment.width = 2f + Math.max(0f, pressure) * 22f;
        segments.add(segment);
        if (segments.size() > 4000) segments.remove(0);
        lastX = x;
        lastY = y;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        for (Segment segment : segments) {
            paint.setStrokeWidth(segment.width);
            canvas.drawLine(segment.x0, segment.y0, segment.x1, segment.y1, paint);
        }
    }

    private void report(MotionEvent event) {
        if (listener == null) return;
        listener.onSample(describe(event));
    }

    private static String describe(MotionEvent event) {
        String tool;
        switch (event.getToolType(0)) {
            case MotionEvent.TOOL_TYPE_STYLUS:
                tool = "笔";
                break;
            case MotionEvent.TOOL_TYPE_ERASER:
                tool = "橡皮";
                break;
            case MotionEvent.TOOL_TYPE_FINGER:
                tool = "手指";
                break;
            case MotionEvent.TOOL_TYPE_MOUSE:
                tool = "鼠标";
                break;
            default:
                tool = "其他";
                break;
        }
        int buttons = event.getButtonState();
        String side = "松开";
        if ((buttons & MotionEvent.BUTTON_STYLUS_PRIMARY) != 0) side = "第一侧键";
        if ((buttons & MotionEvent.BUTTON_STYLUS_SECONDARY) != 0) {
            side = side.equals("松开") ? "第二侧键" : side + " + 第二侧键";
        }
        return String.format(Locale.US, "压力 %.3f    工具 %s    侧键 %s", event.getPressure(), tool, side);
    }
}
