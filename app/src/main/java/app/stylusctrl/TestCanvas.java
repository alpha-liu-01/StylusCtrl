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

    private String describe(MotionEvent event) {
        String tool;
        switch (event.getToolType(0)) {
            case MotionEvent.TOOL_TYPE_STYLUS:
                tool = getContext().getString(R.string.tool_pen);
                break;
            case MotionEvent.TOOL_TYPE_ERASER:
                tool = getContext().getString(R.string.tool_eraser);
                break;
            case MotionEvent.TOOL_TYPE_FINGER:
                tool = getContext().getString(R.string.tool_finger);
                break;
            case MotionEvent.TOOL_TYPE_MOUSE:
                tool = getContext().getString(R.string.tool_mouse);
                break;
            default:
                tool = getContext().getString(R.string.tool_other);
                break;
        }
        int buttons = event.getButtonState();
        String up = getContext().getString(R.string.side_up);
        String side = up;
        if ((buttons & MotionEvent.BUTTON_STYLUS_PRIMARY) != 0) {
            side = getContext().getString(R.string.side_primary);
        }
        if ((buttons & MotionEvent.BUTTON_STYLUS_SECONDARY) != 0) {
            String second = getContext().getString(R.string.side_secondary);
            side = side.equals(up) ? second : getContext().getString(R.string.side_both, side, second);
        }
        return getContext().getString(R.string.sample, event.getPressure(), tool, side);
    }
}
