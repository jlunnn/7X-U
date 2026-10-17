package com.sevenxu.eq;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.widget.SeekBar;

/**
 * VOLUME 滑桿（DB GAIN 同款風格）：幼 track 4dp，**已填部分實色霓虹綠、未填灰色**，
 * 100% = 全條變色；70% = 至 70% 變色，餘下灰色。圓形 thumb。
 */
public class VolBar extends SeekBar {

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float d;

    public VolBar(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        setMax(100);
        setProgressDrawable(null);
        setThumb(null);
        setSplitTrack(false);
        setPadding((int) (10 * d), 0, (int) (10 * d), 0);
    }

    @Override
    protected synchronized void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        if (w <= 0) return;
        float cy = h / 2f;
        float l = getPaddingLeft(), r = w - getPaddingRight();
        float trackH = 4 * d;
        float x = l + (r - l) * (getProgress() / (float) getMax());

        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF6B7689);                              // 未填（灰）
        cv.drawRoundRect(new RectF(l, cy - trackH / 2f, r, cy + trackH / 2f), 2 * d, 2 * d, p);
        p.setColor(0xFFB6FF3B);                              // 已填（霓虹綠）
        cv.drawRoundRect(new RectF(l, cy - trackH / 2f, x, cy + trackH / 2f), 2 * d, 2 * d, p);

        p.setColor(0xFFB6FF3B);                              // 圓 thumb
        cv.drawCircle(x, cy, 7 * d, p);
        p.setColor(0xFF0D110D);
        cv.drawCircle(x, cy, 2.5f * d, p);
    }
}
