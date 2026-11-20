package com.mazedcu.screenrec;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** Two-handle range selector. Values are fractions 0..1. */
public class RangeBar extends View {
    public interface Listener {
        void onChange(float s, float e, boolean startMoved);
    }

    public float s = 0f, e = 1f;
    public int accent = 0xFF2563EB;
    public Listener listener;
    private int active = 0;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float d;
    private static final float GAP = 0.01f;

    public RangeBar(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
    }

    public void set(float s0, float e0) {
        s = s0;
        e = e0;
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), Math.round(56 * d));
    }

    private float pad() {
        return 16 * d;
    }

    private float xOf(float f) {
        return pad() + f * (getWidth() - 2 * pad());
    }

    @Override
    protected void onDraw(Canvas c) {
        float cy = getHeight() / 2f, th = 6 * d;
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF2B3440);
        c.drawRoundRect(new RectF(xOf(0), cy - th / 2, xOf(1), cy + th / 2), th, th, p);
        p.setColor(accent);
        c.drawRect(xOf(s), cy - th / 2, xOf(e), cy + th / 2, p);
        for (int i = 0; i < 2; i++) {
            float x = xOf(i == 0 ? s : e);
            p.setColor(Color_WHITE);
            c.drawRoundRect(new RectF(x - 7 * d, cy - 20 * d, x + 7 * d, cy + 20 * d), 5 * d, 5 * d, p);
            p.setColor(accent);
            c.drawRect(x - 1 * d, cy - 10 * d, x + 1 * d, cy + 10 * d, p);
        }
    }

    private static final int Color_WHITE = 0xFFFFFFFF;

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        float f = (ev.getX() - pad()) / (getWidth() - 2 * pad());
        f = Math.max(0f, Math.min(1f, f));
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                active = Math.abs(f - s) <= Math.abs(f - e) ? 1 : 2;
                getParent().requestDisallowInterceptTouchEvent(true);
                // fall through
            case MotionEvent.ACTION_MOVE:
                if (active == 1) s = Math.min(f, e - GAP);
                else if (active == 2) e = Math.max(f, s + GAP);
                s = Math.max(0f, s);
                e = Math.min(1f, e);
                invalidate();
                if (listener != null) listener.onChange(s, e, active == 1);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                active = 0;
                return true;
        }
        return super.onTouchEvent(ev);
    }
}
