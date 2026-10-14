package com.sevenxu.eq;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * EQ 開關掣嘅外框：**粗線框**。
 *
 * <ul>
 *   <li>關（EQ OFF）：還原成原有粗線（灰 {@link #BASE}）。</li>
 *   <li>開（EQ ON）：粗線變成**動態黃色**（脈動 + 外圈黃色光暈），由 {@link #setPulse} 驅動。</li>
 * </ul>
 */
public final class EqFrame extends Drawable {

    public static final int BASE = 0xFF7A828E;          // 原有粗線（灰）
    public static final int YELLOW = 0xFFFFC24B;        // 動態黃
    public static final int YELLOW_HOT = 0xFFFFF0A8;    // 亮黃（脈動高點）
    private static final int FILL = 0xFF14171C;         // 掣底（深色，字／框才夠 contrast）

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float stroke;
    private final float radius;
    private boolean on;
    private float pulse;                                 // 0–1

    public EqFrame(float strokePx, float radiusPx) {
        stroke = Math.max(2f, strokePx);
        radius = Math.max(0f, radiusPx);
    }

    public void setOn(boolean b) {
        on = b;
        invalidateSelf();
    }

    public boolean isOn() {
        return on;
    }

    /** 動態相位（0–1）。 */
    public void setPulse(float v) {
        pulse = Math.max(0f, Math.min(1f, v));
        invalidateSelf();
    }

    @Override
    public void draw(Canvas cv) {
        r.set(getBounds());
        float pad = stroke * 0.75f;
        r.inset(pad, pad);

        // 底
        p.setStyle(Paint.Style.FILL);
        p.setColor(FILL);
        cv.drawRoundRect(r, radius, radius, p);

        // 外圈光暈（只在開嘅時候）
        if (on) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(stroke * 2.4f);
            p.setColor(Ui.alpha(YELLOW, 0.10f + 0.22f * pulse));
            cv.drawRoundRect(r, radius, radius, p);
        }

        // 粗線框：開＝動態黃、關＝原有灰
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(stroke);
        p.setColor(on ? Ui.mix(YELLOW, YELLOW_HOT, pulse) : BASE);
        cv.drawRoundRect(r, radius, radius, p);
    }

    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
