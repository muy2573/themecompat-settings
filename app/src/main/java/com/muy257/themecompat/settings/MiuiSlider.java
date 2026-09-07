package com.muy257.themecompat.settings;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

/**
 * HyperOS-style slider: a slim capsule track, HyperOS blue progress and a
 * white thumb.  Reports continuous changes so the preview label can update
 * live while the value is committed by the owner of the callback.
 */
final class MiuiSlider extends View {
    interface OnChange { void onChanged(float value); }

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF trackRect = new RectF();
    private float value = 0.5f;
    private boolean dragging;

    MiuiSlider(Context context) {
        super(context);
        trackPaint.setColor(Ui.trackColor(context));
        progressPaint.setColor(Ui.ACCENT);
        thumbPaint.setColor(0xFFFFFFFF);
        thumbBorderPaint.setStyle(Paint.Style.STROKE);
        thumbBorderPaint.setStrokeWidth(dp(1));
        thumbBorderPaint.setColor(0x1F000000);
        setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE:
                    dragging = true;
                    setValueFromTouch(event.getX());
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    setValueFromTouch(event.getX());
                    return true;
                default:
                    return dragging;
            }
        });
        setClickable(true);
    }

    void setValue(float newValue) {
        value = Math.max(0f, Math.min(1f, newValue));
        invalidate();
    }

    float getValue() { return value; }

    private void setValueFromTouch(float x) {
        float fraction = (x - getPaddingLeft())
                / Math.max(1, getWidth() - getPaddingLeft() - getPaddingRight());
        setValue(fraction);
        if (listener != null) listener.onChanged(getValue());
    }

    private OnChange listener;

    void setListener(OnChange listener) { this.listener = listener; }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float trackHeight = dp(6);
        float left = getPaddingLeft();
        float right = getWidth() - getPaddingRight();
        float centerY = getHeight() / 2f;
        trackRect.set(left, centerY - trackHeight / 2f, right, centerY + trackHeight / 2f);
        canvas.drawRoundRect(trackRect, trackHeight / 2f, trackHeight / 2f, trackPaint);
        float thumbRadius = dp(dragging ? 12 : 10);
        float progressEnd = Math.max(left + thumbRadius,
                trackRect.left + (trackRect.width() * value));
        canvas.drawRoundRect(trackRect.left, centerY - trackHeight / 2f,
                progressEnd, centerY + trackHeight / 2f,
                trackHeight / 2f, trackHeight / 2f, progressPaint);
        thumbPaint.setShadowLayer(dp(dragging ? 5 : 3), 0, dp(1), 0x33000000);
        if (getLayerType() != View.LAYER_TYPE_SOFTWARE) {
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }
        canvas.drawCircle(progressEnd, centerY, thumbRadius, thumbPaint);
        canvas.drawCircle(progressEnd, centerY, thumbRadius, thumbBorderPaint);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int height = dp(44);
        setMeasuredDimension(getDefaultSize(getSuggestedMinimumWidth(), widthMeasureSpec), height);
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }
}
