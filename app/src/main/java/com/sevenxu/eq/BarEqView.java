package com.sevenxu.eq;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * EQ-Control 款式柱狀 EQ（Figma「EQ-Control」）：
 * 14 條柱、深底、霓虹綠強調、左側 +12/-12 標籤、下面頻率標籤（用番車機真實 14 個 Hz）。
 * API 同 {@link EqView} 一致（可以直接替換）。
 */
public class BarEqView extends View {

    /** 同 EqView.Listener 一樣。 */
    public interface Listener {
        void onGain(int band, float db);

        void onCommit(int band, float db);

        void onActive(int band);
    }

    public static final int BG = 0xFF0D110D;
    public static final int PANEL = 0xFF1A1F16;
    public static final int LINE = 0xFF1E2418;
    public static final int TRACK = 0xFF2A3025;
    public static final int NEON = 0xFFB6FF3B;      // 強調（霓虹綠）
    public static final int BAR_DIM = 0xFF4B5A3A;   // 一般柱（暗綠）
    public static final int TXT = 0xFFFFFFFF;
    public static final int TXT_DIM = 0xFFA0A89A;
    public static final int BADGE_LINE = 0xFF3A4A2A;

    private final Dsp dsp;
    private Listener lis;
    private float[] freqs;                       // 系統段位（可 null）
    private String sysName = "";
    private boolean[] real;
    private boolean engOn = true;                    // 引擎開／關（關 = 柱變暗）                      // 真受控段
    private float[] spec;                        // 頻譜（可 null）
    private int active = -1;
    private boolean dragging;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private float d, gutter, padTop, padBottom, top, bottom, plotW, colW, zeroY;

    public BarEqView(Context c, Dsp dsp) {
        super(c);
        this.dsp = dsp;
        setWillNotDraw(false);
    }

    public void setListener(Listener l) {
        this.lis = l;
    }

    public void setSysInfo(float[] freqs, String name) {
        this.freqs = freqs;
        this.sysName = name == null ? "" : name;
        invalidate();
    }

    public void setRealBands(boolean[] r) {
        this.real = r;
        invalidate();
    }

    public void setSpectrum(float[] s) {
        this.spec = s;
        invalidate();
    }

    public void setShowCurve(boolean b) {
        /* EQ-Control 版面冇曲線 */
    }

    public void setMicMode(boolean mic) {
        /* 無 mic 模式 */
    }

    /** EQ 開／關（關 = 柱變暗，一眼睇出）。 */
    public void setEngineOn(boolean on) {
        this.engOn = on;
        invalidate();
    }

    public void markDirty() {
        invalidate();
    }

    public int activeBand() {
        return active;
    }

    public void setActiveBand(int band) {
        active = band;
        invalidate();
    }

    // ------------------------------------------------------------------ 畫

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        float dd = getResources().getDisplayMetrics().density;
        d = dd;
        gutter = 78 * d;                          // 左邊留位（字放大後）
        padTop = 18 * d;
        padBottom = 46 * d;                       // 頻率標籤（字放大後留位）
        top = padTop;
        bottom = h - padBottom;
        plotW = w - gutter - 10 * d;
        colW = plotW / Bands.N;
        zeroY = (top + bottom) / 2f;              // 0 dB 中線
    }

    private float dbY(float db) {
        return zeroY - (db / Math.max(1f, Bands.MAX_DB)) * ((bottom - top) / 2f);
    }

    private float bandX(int i) {
        return gutter + colW * i + colW / 2f;
    }

    @Override
    protected void onDraw(Canvas cv) {
        super.onDraw(cv);
        if (colW <= 0) return;

        // 格線（每 3 dB）
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, d * 0.6f));
        p.setColor(LINE);
        for (float db = Bands.MAX_DB; db >= Bands.MIN_DB - 0.01f; db -= 3) {
            float y = dbY(db);
            cv.drawLine(gutter, y, gutter + plotW, y, p);
        }
        // 0 dB 線（稍亮）
        p.setColor(0xFF2F3A2A);
        cv.drawLine(gutter, zeroY, gutter + plotW, zeroY, p);

        // 左側刻度：+12 … -12
        p.setStyle(Paint.Style.FILL);
        p.setTextAlign(Paint.Align.LEFT);
        p.setTextSize(19 * d);                        // 同 Hz 字一樣大
        p.setColor(TXT_DIM);
        cv.drawText(String.format(java.util.Locale.US, "+%.0f", Bands.MAX_DB), 6 * d, top + 12 * d, p);
        cv.drawText(String.format(java.util.Locale.US, "%.0f", Bands.MIN_DB), 6 * d, bottom, p);
        cv.drawText("0", 6 * d, zeroY + 4 * d, p);

        // 14 條柱
        float bw = colW * 0.58f;
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < Bands.N; i++) {
            float db = dsp.get(i);
            float x = bandX(i);
            float y = dbY(db);
            boolean on = (i == active);
            p.setColor(on ? NEON : BAR_DIM);
            r.set(x - bw / 2f, Math.min(y, zeroY), x + bw / 2f, Math.max(y, zeroY));
            float rr = 3 * d;
            float minH = 6 * d;                          // 每段最少一截，睇落成排一致
            if (r.height() < minH) {
                float mid = (r.top + r.bottom) / 2f;
                r.top = mid - minH / 2f;
                r.bottom = mid + minH / 2f;
            }
            if (!engOn) {
                p.setColor(on ? 0xFF6E8A46 : 0xFF2E3626); // EQ 關：柱變暗
            }
            cv.drawRoundRect(r, rr, rr, p);
            // （已取消柱頂／柱底圓點：之前遮住 Hz 標籤）
        }

        // 每段 dB 值：+ 畫喺柱頂上面、− 畫喺柱底下面（間距 2px）
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(19 * d);                        // dB 字放大兩倍
        for (int i = 0; i < Bands.N; i++) {
            float db = dsp.get(i);
            if (Math.abs(db) < 0.5f) continue;
            float x = bandX(i);
            float bx = x;
            float by = db >= 0 ? dbY(db) - 2 * d : dbY(db) + 2 * d;
            p.setColor(db >= 0 ? NEON : 0xFFFF8A5B);
            float yy = db >= 0 ? by - 3 * d : by + 9 * d;
            cv.drawText(String.format(java.util.Locale.US, db >= 0 ? "+%.0f" : "%.0f", db), bx, yy, p);
        }

        // 頻率標籤（車機真實 14 個 Hz）
        p.setColor(TXT_DIM);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(19 * d);                        // Hz 字放大兩倍
        for (int i = 0; i < Bands.N; i++) {
            cv.drawText(Bands.LABEL[i], bandX(i), bottom + 16 * d, p);
        }
    }

    // ------------------------------------------------------------------ 觸控

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (colW <= 0) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE: {
                int b = (int) ((e.getX() - gutter) / colW);
                if (b < 0) b = 0;
                if (b >= Bands.N) b = Bands.N - 1;
                float half = Math.max(1f, (bottom - top) / 2f);
                float db = (zeroY - e.getY()) / half * Math.max(1f, Bands.MAX_DB);
                db = Math.max(Bands.MIN_DB, Math.min(Bands.MAX_DB, db));
                db = Math.round(db * 2f) / 2f;                 // 每 0.5 dB 一級
                boolean moved = (b != active);
                active = b;
                dragging = true;
                if (lis != null) {
                    lis.onGain(b, db);
                    if (moved) lis.onActive(b);
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (dragging && active >= 0 && lis != null) {
                    lis.onCommit(active, dsp.get(active));
                }
                dragging = false;
                return true;
            }
            default:
                return false;
        }
    }
}
