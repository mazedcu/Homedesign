package com.mazedcu.screenrec;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** Draggable crop rectangle drawn over the video. n = normalized l,t,r,b. */
public class CropOverlay extends View {
    public int vw = 1, vh = 1;
    public final RectF n = new RectF(0, 0, 1, 1);
    public float ratio = 0f;
    public Runnable onChange;
    private final RectF vr = new RectF();
    private final Paint dim = new Paint(), line = new Paint(Paint.ANTI_ALIAS_FLAG), hp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float d;
    private int mode = 0; // 1 move, 2 TL, 3 TR, 4 BL, 5 BR
    private float lx, ly;

    public CropOverlay(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        dim.setColor(0xAA000000);
        line.setColor(0xFFFFFFFF);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2 * d);
        hp.setColor(0xFFFFFFFF);
    }

    public void setVideo(int w, int h) {
        vw = Math.max(1, w);
        vh = Math.max(1, h);
        reset();
    }

    public void reset() {
        ratio = 0f;
        n.set(0, 0, 1, 1);
        invalidate();
        if (onChange != null) onChange.run();
    }

    public void setRatio(float r) {
        ratio = r;
        if (r <= 0) {
            invalidate();
            return;
        }
        float nw, nh;
        if ((float) vw / vh >= r) {
            nh = 1f;
            nw = r * vh / vw;
        } else {
            nw = 1f;
            nh = vw / (r * vh);
        }
        float cx = 0.5f, cy = 0.5f;
        n.set(cx - nw / 2, cy - nh / 2, cx + nw / 2, cy + nh / 2);
        invalidate();
        if (onChange != null) onChange.run();
    }

    public boolean isFull() {
        return n.left < 0.004f && n.top < 0.004f && n.right > 0.996f && n.bottom > 0.996f;
    }

    private void fit() {
        float W = getWidth(), H = getHeight();
        float sc = Math.min(W / vw, H / vh);
        float w = vw * sc, h = vh * sc;
        vr.set((W - w) / 2, (H - h) / 2, (W + w) / 2, (H + h) / 2);
    }

    private RectF screen() {
        return new RectF(vr.left + n.left * vr.width(), vr.top + n.top * vr.height(),
            vr.left + n.right * vr.width(), vr.top + n.bottom * vr.height());
    }

    @Override
    protected void onDraw(Canvas c) {
        fit();
        RectF s = screen();
        c.drawRect(vr.left, vr.top, vr.right, s.top, dim);
        c.drawRect(vr.left, s.bottom, vr.right, vr.bottom, dim);
        c.drawRect(vr.left, s.top, s.left, s.bottom, dim);
        c.drawRect(s.right, s.top, vr.right, s.bottom, dim);
        c.drawRect(s, line);
        Paint g = new Paint(line);
        g.setStrokeWidth(1 * d);
        g.setAlpha(110);
        for (int i = 1; i < 3; i++) {
            c.drawLine(s.left + s.width() * i / 3, s.top, s.left + s.width() * i / 3, s.bottom, g);
            c.drawLine(s.left, s.top + s.height() * i / 3, s.right, s.top + s.height() * i / 3, g);
        }
        float r = 9 * d;
        c.drawCircle(s.left, s.top, r, hp);
        c.drawCircle(s.right, s.top, r, hp);
        c.drawCircle(s.left, s.bottom, r, hp);
        c.drawCircle(s.right, s.bottom, r, hp);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        fit();
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                RectF s = screen();
                float t = 34 * d;
                if (Math.hypot(x - s.left, y - s.top) < t) mode = 2;
                else if (Math.hypot(x - s.right, y - s.top) < t) mode = 3;
                else if (Math.hypot(x - s.left, y - s.bottom) < t) mode = 4;
                else if (Math.hypot(x - s.right, y - s.bottom) < t) mode = 5;
                else if (s.contains(x, y)) mode = 1;
                else mode = 0;
                lx = x;
                ly = y;
                return mode != 0;
            }
            case MotionEvent.ACTION_MOVE: {
                if (mode == 1) {
                    float dx = (x - lx) / vr.width(), dy = (y - ly) / vr.height();
                    float w = n.width(), h = n.height();
                    float l = Math.max(0, Math.min(1 - w, n.left + dx));
                    float t = Math.max(0, Math.min(1 - h, n.top + dy));
                    n.set(l, t, l + w, t + h);
                    lx = x;
                    ly = y;
                } else if (mode >= 2) {
                    float px = Math.max(0, Math.min(1, (x - vr.left) / vr.width()));
                    float py = Math.max(0, Math.min(1, (y - vr.top) / vr.height()));
                    boolean left = mode == 2 || mode == 4, top = mode == 2 || mode == 3;
                    float fx = left ? n.right : n.left, fy = top ? n.bottom : n.top;
                    float roomX = left ? fx : 1 - fx, roomY = top ? fy : 1 - fy;
                    float nw = Math.min(Math.abs(px - fx), roomX), nh = Math.min(Math.abs(py - fy), roomY);
                    if (ratio > 0) {
                        float nh2 = nw * vw / (ratio * vh);
                        if (nh2 > roomY) {
                            nh2 = roomY;
                            nw = nh2 * ratio * vh / vw;
                        }
                        nh = nh2;
                    }
                    nw = Math.max(nw, 0.08f);
                    if (ratio <= 0) nh = Math.max(nh, 0.08f);
                    if (left) n.left = fx - nw; else n.right = fx + nw;
                    if (top) n.top = fy - nh; else n.bottom = fy + nh;
                    n.left = Math.max(0, n.left);
                    n.top = Math.max(0, n.top);
                    n.right = Math.min(1, n.right);
                    n.bottom = Math.min(1, n.bottom);
                }
                invalidate();
                if (onChange != null) onChange.run();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mode = 0;
                return true;
        }
        return false;
    }
}
