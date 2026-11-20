package com.mazedcu.screenrec;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Iterator;

/** Transparent full-screen canvas for drawing pen / marker / arrow annotations. */
public class AnnotationView extends View {
    public static final int PEN = 0, MARK = 1, ARROW = 2;
    private static final long FADE_START = 4000, FADE_DUR = 800;

    private static class Stroke {
        int type;
        Path path = new Path();
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        int baseAlpha;
        float x0, y0, x1, y1;
        long end = 0;
    }

    private final ArrayList<Stroke> strokes = new ArrayList<>();
    private Stroke cur;
    private float lx, ly;
    public int tool = PEN;
    public int color = 0xFFFF3B30;
    public boolean fade = false;
    private final float density;

    public AnnotationView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
    }

    public void undo() {
        if (!strokes.isEmpty()) strokes.remove(strokes.size() - 1);
        cur = null;
        invalidate();
    }

    public void clear() {
        strokes.clear();
        cur = null;
        invalidate();
    }

    public boolean hasStrokes() {
        return !strokes.isEmpty();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                Stroke s = new Stroke();
                s.type = tool;
                Paint p = s.paint;
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setStrokeJoin(Paint.Join.ROUND);
                if (tool == MARK) {
                    p.setStrokeWidth(22 * density);
                    p.setColor(color);
                    s.baseAlpha = 100;
                } else {
                    p.setStrokeWidth((tool == ARROW ? 5 : 4.5f) * density);
                    p.setColor(color);
                    s.baseAlpha = 255;
                }
                p.setAlpha(s.baseAlpha);
                s.path.moveTo(x, y);
                s.x0 = x; s.y0 = y; s.x1 = x; s.y1 = y;
                lx = x; ly = y;
                cur = s;
                strokes.add(s);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                if (cur != null) {
                    if (cur.type == ARROW) {
                        cur.x1 = x; cur.y1 = y;
                    } else {
                        cur.path.quadTo(lx, ly, (x + lx) / 2, (y + ly) / 2);
                        lx = x; ly = y;
                    }
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (cur != null) {
                    if (cur.type == ARROW) {
                        float dx = cur.x1 - cur.x0, dy = cur.y1 - cur.y0;
                        if (dx * dx + dy * dy < 100) strokes.remove(cur);
                    } else {
                        cur.path.lineTo(x + 0.01f, y);
                    }
                    cur.end = SystemClock.uptimeMillis();
                    cur = null;
                    invalidate();
                }
                return true;
        }
        return super.onTouchEvent(e);
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        boolean again = false;
        Iterator<Stroke> it = strokes.iterator();
        while (it.hasNext()) {
            Stroke s = it.next();
            float mult = 1f;
            if (fade && s.end > 0) {
                long age = now - s.end;
                if (age > FADE_START + FADE_DUR) { it.remove(); continue; }
                if (age > FADE_START) mult = 1f - (age - FADE_START) / (float) FADE_DUR;
                again = true;
            }
            s.paint.setAlpha(Math.max(0, Math.min(255, Math.round(s.baseAlpha * mult))));
            if (s.type == ARROW) {
                c.drawLine(s.x0, s.y0, s.x1, s.y1, s.paint);
                double a = Math.atan2(s.y1 - s.y0, s.x1 - s.x0);
                float len = 18 * density + s.paint.getStrokeWidth() * 2;
                for (int i = -1; i <= 1; i += 2) {
                    double b = a + i * 0.5;
                    c.drawLine(s.x1, s.y1, s.x1 - len * (float) Math.cos(b), s.y1 - len * (float) Math.sin(b), s.paint);
                }
            } else {
                c.drawPath(s.path, s.paint);
            }
        }
        if (again) postInvalidateOnAnimation();
    }
}
