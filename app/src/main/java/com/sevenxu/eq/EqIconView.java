package com.sevenxu.eq;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/**
 * 圓形 EQ 捷徑圖示 —— 用 Canvas 直接畫（向量），任何 size 都清晰，唔會似 bitmap 放大矇。
 */
public class EqIconView extends View {

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 五條 bar 高度比例（0..1）。 */
    private static final float[] H = {0.42f, 0.62f, 0.88f, 0.52f, 0.72f};

    public EqIconView(Context c) {
        super(c);
    }

    @Override
    protected void onDraw(Canvas cv) {
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float r = Math.min(w, h) / 2f;

        // 底：深色圓
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF0D110D);
        cv.drawCircle(cx, cy, Math.max(0f, r - 1f), p);

        // 霓虹綠外環
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(2f, r * 0.075f));
        p.setColor(0xFFB6FF3B);
        cv.drawCircle(cx, cy, Math.max(0f, r - p.getStrokeWidth() / 2f - 1f), p);

        // 五條 pill bar：暗綠底 + 霓虹綠上半
        p.setStyle(Paint.Style.FILL);
        float barW = r * 0.20f;
        float gap = r * 0.145f;
        float totalW = 5 * barW + 4 * gap;
        float left = cx - totalW / 2f;
        float maxBar = r * 1.10f;
        for (int i = 0; i < 5; i++) {
            float bh = maxBar * H[i];
            float x = left + i * (barW + gap);
            float top = cy - bh / 2f;
            float rad = barW * 0.45f;
            p.setColor(0xFF4B5A3A);
            cv.drawRoundRect(x, top, x + barW, top + bh, rad, rad, p);
            p.setColor(0xFFB6FF3B);
            cv.drawRoundRect(x, top, x + barW, cy - bh * 0.05f, rad, rad, p);
        }
    }
}
