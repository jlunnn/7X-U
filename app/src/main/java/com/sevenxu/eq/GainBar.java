package com.sevenxu.eq;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.widget.SeekBar;

/**
 * DB GAIN 滑桿：**0 dB 喺正中**，可睇出 −／＋ 位置（左 −10、中 0、右 +10）。
 * 由中心向左右填色：+ = 霓虹綠、− = 橙；中心有一條刻度。
 */
public class GainBar extends SeekBar {

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float d;

    public GainBar(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        setMax(20);                                  // −10 … +10 dB（10 = 0 dB）
        setProgressDrawable(null);                   // 全部自己畫
        setThumb(null);
        setSplitTrack(false);
        setPadding((int) (12 * d), 0, (int) (12 * d), 0);
    }

    @Override
    protected synchronized void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        if (w <= 0) return;
        float cy = h / 2f;
        float l = getPaddingLeft(), r = w - getPaddingRight();
        float trackH = 4 * d;
        float mid = (l + r) / 2f;
        float x = l + (r - l) * (getProgress() / (float) getMax());

        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF2A3025);
        cv.drawRoundRect(new RectF(l, cy - trackH / 2f, r, cy + trackH / 2f), 2 * d, 2 * d, p);

        p.setColor(x >= mid ? 0xFFB6FF3B : 0xFFFF8A5B);      // + 綠 / − 橙
        cv.drawRoundRect(new RectF(Math.min(mid, x), cy - trackH / 2f, Math.max(mid, x), cy + trackH / 2f),
                2 * d, 2 * d, p);

        p.setColor(0xFF6B7689);                              // 0 刻度
        cv.drawRect(mid - d / 2f, cy - 7 * d, mid + d / 2f, cy + 7 * d, p);

        p.setColor(0xFFB6FF3B);                              // thumb
        cv.drawCircle(x, cy, 8 * d, p);
        p.setColor(0xFF0D110D);
        cv.drawCircle(x, cy, 3 * d, p);

        p.setColor(0xFFA0A89A);                              // 刻度字
        p.setTextSize(10 * d);
        p.setTextAlign(Paint.Align.LEFT);
        cv.drawText("-10", l, cy - 11 * d, p);
        p.setTextAlign(Paint.Align.CENTER);
        cv.drawText("0", mid, cy - 11 * d, p);
        p.setTextAlign(Paint.Align.RIGHT);
        cv.drawText("+10", r, cy - 11 * d, p);
    }
}
