package com.sevenxu.eq;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * 31 段圖形等化器（自繪）：每段一條可拖嘅豎條 + 級聯響應曲線 +（咪模式）實時頻譜。
 *
 * <p>即時反饋設計：拖邊一段 → 條柱即刻跟手、右上大字顯示「1.0k +6.0 dB」、
 * 曲線同步更新；值等於 0 附近有輕微磁吸（容易返到 0.0）。</p>
 *
 * <p>支援多點觸控：兩隻手指同時拖兩段（造 V 型曲線唔使逐段嚟）。</p>
 */
public class EqView extends View {

    public interface Listener {
        /** 拖動中（每個 block 可以叫幾次）。 */
        void onGain(int band, float db);

        /** 放手／完成。 */
        void onCommit(int band, float db);

        /** 手指去咗另一段（跟手指試聽用）。 */
        void onActive(int band);
    }

    private static final int PTS = 160;             // 曲線取樣點
    private static final long CURVE_MS = 100;       // 拖動中曲線重算間隔

    private final Dsp dsp;
    private Listener listener;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path curve = new Path();
    private final RectF r = new RectF();
    private final float[] curveY = new float[PTS];
    private long curveAt;
    private boolean curveDirty = true;

    private float gutter, padTop, padBottom, top, bottom, plotW, colW;
    private int activeBand = -1;

    private float[] spec;                            // 頻譜（dBFS），null = 唔畫
    private boolean showCurve = true;
    private float[] sysFreqs;                        // 車機系統 EQ 各段中心頻率
    /** 有冇顯示「車機真受控」金框（預設關：自檢已關，金框變多餘）。 */
    public static boolean showReal = false;
    private boolean[] real;                          // 14 段：true = 車機真受控
    private String sysName = "";

    /** 系統（媒體音樂）EQ 嘅段中心頻率 + 引擎名（畫上圖，等用戶見到車機實際得幾段、喺邊）。 */
    public void setSysInfo(float[] freqs, String name) {
        sysFreqs = (freqs == null || freqs.length == 0) ? null : freqs;
        sysName = name == null ? "" : name;
        invalidate();
    }

    /** 31 段邊幾段係車機真受控（null 或者全部 true = 全部都真受控，唔需要標示）。 */
    public void setRealBands(boolean[] r) {
        real = (r != null && r.length == Bands.N) ? r : null;
        invalidate();
    }

    /** 有冇「部分段係映射、唔受控」嘅情況（先值得畫標示）。 */
    private boolean partlyReal() {
        if (real == null) return false;
        for (boolean b : real) {
            if (!b) return true;
        }
        return false;
    }

    private int realCount() {
        if (real == null) return Bands.N;
        int n = 0;
        for (boolean b : real) {
            if (b) n++;
        }
        return n;
    }

    public EqView(Context c, Dsp dsp) {
        super(c);
        this.dsp = dsp;
        setFocusable(true);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setSpectrum(float[] s) {
        spec = s;
        invalidate();
    }

    public void setShowCurve(boolean b) {
        showCurve = b;
        invalidate();
    }

    public int activeBand() {
        return activeBand;
    }

    public void markDirty() {
        curveDirty = true;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        float d = getResources().getDisplayMetrics().density;
        gutter = 34 * d;
        padTop = 44 * d;                              // 讓大字讀數 + 系統 EQ 標示有位
        padBottom = 34 * d;                           // 頻率標籤（交錯兩行）
        top = padTop;
        bottom = h - padBottom;
        plotW = w - gutter - 6 * d;
        colW = plotW / Bands.N;
    }

    private float dbToY(float db) {
        float h = bottom - top;
        return top + (Bands.MAX_DB - db) / (Bands.MAX_DB - Bands.MIN_DB) * h;
    }

    private float yToDb(float y) {
        float h = bottom - top;
        float db = Bands.MAX_DB - (y - top) / h * (Bands.MAX_DB - Bands.MIN_DB);
        return Math.max(Bands.MIN_DB, Math.min(Bands.MAX_DB, db));
    }

    private float bandX(int i) {
        return gutter + colW * (i + 0.5f);
    }

    private int xToBand(float x) {
        int i = (int) Math.floor((x - gutter) / colW);
        return Bands.clampBand(i);
    }

    @Override
    protected void onDraw(Canvas cv) {
        super.onDraw(cv);
        float d = getResources().getDisplayMetrics().density;

        // ---- 格線（每 3 dB） ----
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, d));
        for (float db = Bands.MAX_DB; db >= Bands.MIN_DB - 0.01f; db -= 3) {
            float y = dbToY(db);
            boolean zero = Math.abs(db) < 0.01f;
            p.setColor(zero ? 0x66FFFFFF : 0x1AFFFFFF);
            cv.drawLine(gutter, y, gutter + plotW, y, p);
            if (Math.abs(db) % 6 < 0.01f || zero) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(Ui.DIM);
                p.setTextSize(10 * d);
                p.setTextAlign(Paint.Align.RIGHT);
                cv.drawText(Presets.Fmt.sign(db), gutter - 4 * d, y + 3.5f * d, p);
                p.setStyle(Paint.Style.STROKE);
            }
        }

        // ---- 頻譜（咪模式，畫喺柱後面） ----
        if (spec != null) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(Ui.SPEC);
            float bw = colW * 0.42f;
            for (int i = 0; i < Bands.N; i++) {
                if (spec[i] <= -119) continue;
                float norm = (spec[i] + 80f) / 60f;            // -80 dB → 0，-20 dB → 1
                norm = Math.max(0f, Math.min(1f, norm));
                float h = (bottom - top) * norm;
                r.set(bandX(i) - bw / 2, bottom - h, bandX(i) + bw / 2, bottom);
                cv.drawRoundRect(r, 2 * d, 2 * d, p);
            }
        }

        // ---- 車機系統 EQ 段位置（黃色虛線）：只有長按 EQ 圖開「真受控標示」時才畫 ----
        if (showReal && sysFreqs != null) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1f, d));
            p.setPathEffect(new android.graphics.DashPathEffect(new float[]{5 * d, 6 * d}, 0));
            p.setColor(0x88FFC24B);
            for (float f : sysFreqs) {
                double t = Math.log(f / 20.0) / Math.log(1000.0);
                if (t < 0 || t > 1) continue;
                float x = gutter + plotW * (float) t;
                // 只畫對得上某一段柱嘅（車機段位同 UI 柱唔一定對齊；全部畫會錯位重疊）
                boolean aligned = false;
                for (int i = 0; i < Bands.N; i++) {
                    if (Math.abs(bandX(i) - x) < colW * 0.45f) {
                        aligned = true;
                        break;
                    }
                }
                if (!aligned) continue;
                cv.drawLine(x, top, x, bottom, p);
                p.setStyle(Paint.Style.FILL);
                cv.drawCircle(x, top - 7 * d, 3 * d, p);
                p.setStyle(Paint.Style.STROKE);
            }
            p.setPathEffect(null);
            p.setStyle(Paint.Style.FILL);
            p.setTextAlign(Paint.Align.LEFT);
            p.setColor(Ui.WARN);
            p.setTextSize(12 * d);
            cv.drawText(partlyReal()
                            ? (showReal ? ("車機真受控 " + realCount() + "/" + Bands.N + " 段 ← 金框 = 真受控，灰 = 映射")
                            : ("14 段媒體 EQ（長按圖可顯示「真受控」金框）"))
                            : ("車機 EQ " + sysFreqs.length + " 段 ← " + sysName),
                    gutter + 2 * d, top - 12 * d, p);
        }

        // ---- 31 條柱 + 手柄 ----
        for (int i = 0; i < Bands.N; i++) {
            float db = dsp.get(i);
            float x = bandX(i);
            float y = dbToY(db);
            float y0 = dbToY(0);
            boolean act = (i == activeBand);
            float halfW = colW * 0.30f;
            boolean mapped = partlyReal() && !real[i];     // 映射段：撳咗唔會單獨變

            // 底軌
            p.setStyle(Paint.Style.FILL);
            p.setColor(mapped ? 0x18FFFFFF : 0x33FFFFFF);
            r.set(x - d, top, x + d, bottom);
            cv.drawRoundRect(r, d, d, p);

            // 值柱
            if (Math.abs(db) > 0.02f) {
                p.setColor(db >= 0 ? Ui.ACCENT : Ui.CUT);
                r.set(x - halfW, Math.min(y, y0), x + halfW, Math.max(y, y0));
                cv.drawRoundRect(r, 2 * d, 2 * d, p);
            }

            // 手柄
            p.setColor(act ? Ui.TEXT : (db >= 0 ? Ui.ACCENT : Ui.CUT));
            r.set(x - halfW - d, y - 5 * d, x + halfW + d, y + 5 * d);
            cv.drawRoundRect(r, 3 * d, 3 * d, p);
            if (act) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.5f * d);
                p.setColor(Ui.TEXT);
                r.set(x - halfW - 2 * d, y - 7 * d, x + halfW + 2 * d, y + 7 * d);
                cv.drawRoundRect(r, 4 * d, 4 * d, p);
                p.setStyle(Paint.Style.FILL);
            }

            // 數值（非 0 或者正在拖嗰段才顯示，免得 31 個數字亂）
            if (Math.abs(db) >= 0.5f || act) {
                p.setColor(db >= 0 ? Ui.ACCENT : Ui.CUT);
                p.setTextSize(10 * d);
                p.setTextAlign(Paint.Align.CENTER);
                cv.drawText(Presets.Fmt.sign(db), x, Math.min(y, y0) - 4 * d, p);
            }
            // 已加減嘅段加個小點喺頂（一眼睇出改過邊幾段）
            if (Math.abs(db) >= 0.5f) {
                p.setColor(db >= 0 ? Ui.ACCENT : Ui.CUT);
                cv.drawCircle(x, top + 3 * d, 2.2f * d, p);
            }
            // 車機真受控段：粗金框（一眼睇出邊幾段真正推得）
            if (showReal && partlyReal() && !mapped) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2f * d);
                p.setColor(0xFFFFC24B);
                r.set(x - colW * 0.42f, top - 3 * d, x + colW * 0.42f, bottom + 3 * d);
                cv.drawRoundRect(r, 3 * d, 3 * d, p);
                p.setStyle(Paint.Style.FILL);
            }
            // 映射段：底部一粒灰點（唔出框 = 撳咗唔會單獨變）
            if (mapped) {
                p.setColor(0x55FFFFFF);
                cv.drawCircle(x, bottom + 4 * d, 1.8f * d, p);
            }
        }

        // ---- 頻率標籤（交錯兩行，唔會撞） ----
        p.setTextSize(10 * d);
        p.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < Bands.N; i++) {
            boolean mapped = partlyReal() && !real[i];
            p.setColor(i == activeBand ? Ui.TEXT : (mapped ? 0x66D8DEE9 : Ui.DIM));
            float ly = bottom + 12 * d + ((i % 2 == 1) ? 11 * d : 0);
            cv.drawText(Bands.LABEL[i], bandX(i), ly, p);
        }

        // ---- 級聯響應曲線 ----
        if (true) {   // 曲線永遠顯示（之前被 showCurve 擋住）
            long now = System.currentTimeMillis();
            if (curveDirty || now - curveAt > CURVE_MS) {
                computeCurve();
                curveDirty = false;
                curveAt = now;
            }
            curve.reset();
            for (int i = 0; i < PTS; i++) {
                float x = gutter + plotW * i / (PTS - 1f);
                float y = dbToY(Math.max(Bands.MIN_DB, Math.min(Bands.MAX_DB, curveY[i])));
                if (i == 0) curve.moveTo(x, y);
                else curve.lineTo(x, y);
            }
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(11 * d);                // 深色描邊（任何底色都見到）
            p.setColor(0xCC000000);
            p.setStrokeCap(Paint.Cap.ROUND);
            cv.drawPath(curve, p);
            p.setStrokeWidth(9 * d);                 // 光暈
            p.setColor(0x55B6FF3B);
            p.setStrokeCap(Paint.Cap.ROUND);
            cv.drawPath(curve, p);
            p.setStrokeWidth(4.5f * d);              // 主線（霓虹綠、易見）
            p.setColor(0xFFB6FF3B);
            cv.drawPath(curve, p);
            p.setStrokeCap(Paint.Cap.BUTT);
            p.setStyle(Paint.Style.FILL);
        }

        // ---- 右上大字：正在調嗰段 ----
        int ab = activeBand;
        if (ab >= 0) {
            // 讀數放左邊（右邊被浮窗 EQ／✕ 掣遮蓋過）
            p.setTextAlign(Paint.Align.LEFT);
            p.setColor(Ui.DIM);
            p.setTextSize(19 * d);
            cv.drawText("正在調節", gutter, top - 30 * d, p);
            String s = Bands.LABEL[ab] + "　" + Presets.Fmt.sign(dsp.get(ab)) + " dB";
            p.setColor(dsp.get(ab) >= 0 ? Ui.ACCENT : Ui.CUT);
            p.setTextSize(19 * d);
            p.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            cv.drawText(s, gutter, top - 12 * d, p);
            p.setTypeface(android.graphics.Typeface.DEFAULT);
        }
    }

    private boolean micShown;

    public void setMicMode(boolean mic) {
        micShown = mic;
        invalidate();
    }

    private boolean isMicSpectrum() {
        return micShown;
    }

    /** 曲線取樣（對數頻率 20 Hz → 20 kHz）。 */
    private void computeCurve() {
        float[] g = dsp.gains();
        for (int i = 0; i < PTS; i++) {
            double t = i / (PTS - 1.0);
            double f = 20.0 * Math.pow(1000.0, t);
            curveY[i] = (float) Dsp.responseDb(g, f, dsp.rate());
        }
    }

    // ------------------------------------------------------------------ 觸控

    private final java.util.HashMap<Integer, Integer> pointerBand = new java.util.HashMap<>();

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        try {
            FloatEqService.pokeActivity();      // 拖 EQ 段時唔好自動收埋
        } catch (Throwable ignored) {
        }
        int action = e.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int idx = e.getActionIndex();
                int id = e.getPointerId(idx);
                int band = xToBand(e.getX(idx));
                pointerBand.put(id, band);
                activeBand = band;
                applyGain(band, e.getY(idx));
                if (listener != null) listener.onActive(band);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                for (int i = 0; i < e.getPointerCount(); i++) {
                    Integer band = pointerBand.get(e.getPointerId(i));
                    if (band == null) continue;
                    // y 相對該段：每隻手指控制自己嗰段
                    applyGain(band, e.getY(i));
                    if (band != activeBand) {
                        activeBand = band;
                        if (listener != null) listener.onActive(band);
                    }
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int idx = e.getActionIndex();
                int id = e.getPointerId(idx);
                Integer band = pointerBand.remove(id);
                if (band != null && listener != null) {
                    listener.onCommit(band, dsp.get(band));
                }
                if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                    pointerBand.clear();
                    curveDirty = true;
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                pointerBand.clear();
                invalidate();
                return true;
            }
            default:
                return super.onTouchEvent(e);
        }
    }

    private void applyGain(int band, float y) {
        float db = yToDb(y);
        if (Math.abs(db) < 0.4f) db = 0f;               // 磁吸返 0.0
        else db = Math.round(db * 10f) / 10f;
        dsp.set(band, db);
        curveDirty = true;
        if (listener != null) listener.onGain(band, db);
    }

    public void setActiveBand(int band) {
        activeBand = band;
        invalidate();
    }
}
