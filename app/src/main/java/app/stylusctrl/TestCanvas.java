package app.stylusctrl;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.google.android.material.color.MaterialColors;

/** A drawing surface that is not inside a scrolling parent, so a stroke is not cancelled. */
public class TestCanvas extends View {
    public interface SampleListener {
        void onSample(String text);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private SampleListener listener;
    private Bitmap ink;
    private Canvas inkCanvas;
    private float lastX;
    private float lastY;
    private boolean drawing;

    public TestCanvas(Context context, AttributeSet attrs) {
        super(context, attrs);
        int inkColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary, 0xFF1B6B66);
        int paper = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceVariant, 0xFFF4F1EA);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(inkColor);
        setBackgroundColor(paper);
    }

    public void setSampleListener(SampleListener listener) {
        this.listener = listener;
    }

    public void clear() {
        drawing = false;
        if (ink != null) ink.eraseColor(Color.TRANSPARENT);
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0 || h <= 0) return;
        Bitmap next = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(next);
        if (ink != null) {
            canvas.drawBitmap(ink, 0, 0, null);
            ink.recycle();
        }
        ink = next;
        inkCanvas = canvas;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (ink != null) {
            ink.recycle();
            ink = null;
            inkCanvas = null;
        }
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
            for (int i = 0; i < history; i++) {
                addSegment(event.getHistoricalX(i), event.getHistoricalY(i), event.getHistoricalPressure(i));
            }
            addSegment(event.getX(), event.getY(), event.getPressure());
            invalidate();
            report(event);
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (action == MotionEvent.ACTION_UP) addSegment(event.getX(), event.getY(), event.getPressure());
            drawing = false;
            getParent().requestDisallowInterceptTouchEvent(false);
            invalidate();
            report(event);
            return true;
        }
        return false;
    }

    private void addSegment(float x, float y, float pressure) {
        if (inkCanvas == null) {
            lastX = x;
            lastY = y;
            return;
        }
        float width = 2f + Math.max(0f, pressure) * 22f;
        paint.setStrokeWidth(width);
        inkCanvas.drawLine(lastX, lastY, x, y, paint);
        lastX = x;
        lastY = y;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (ink != null) canvas.drawBitmap(ink, 0, 0, null);
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
