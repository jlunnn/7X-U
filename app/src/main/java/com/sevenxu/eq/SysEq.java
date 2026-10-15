package com.sevenxu.eq;

import android.content.Context;
import android.media.audiofx.DynamicsProcessing;
import android.media.audiofx.Equalizer;

import java.util.Locale;

/**
 * 系統（媒體音樂）EQ：掛喺**全域 audio session 0**，即係改到車機播嘅所有聲（藍牙／原廠音樂／任何 app），
 * 唔止 app 自己播嘅音。做法同 hermes probe 一樣。
 *
 * <p>兩條路，按實測優先次序：</p>
 * <ol>
 *   <li><b>DynamicsProcessing</b>（API 28+）：postEq 可以**自己指定段數同每段 cutoff 頻率**
 *       → 可以砌出多段（試 31 → 逐級退）。車機／HAL 段數上限要實測。</li>
 *   <li><b>Equalizer(0, 0)</b> 後備：段數同頻率由 HAL 固定（本車實測 5 段：最後一段 14 kHz），
 *       只可以逐段調 dB。</li>
 * </ol>
 *
 * <p>⚠️ 效果要 app 進程活住（由 {@link EqService} 前台服務揸住）；app 一死／release 就還原。</p>
 */
public final class SysEq {

    /** DynamicsProcessing 段數由大試到細。 */
    private static final int[] TRY = {31, 24, 20, 16, 12, 10, 8, 6, 5, 4, 3, 2};

    private DynamicsProcessing dp;
    private Equalizer eq;
    private int bands;
    private float[] freqs = new float[0];
    private int minMb = -1500;
    private int maxMb = 1500;
    private float[] applied = new float[0];
    private String engine = "未連上";
    private String error = "";
    private boolean on;

    /** 對數間隔切 N 段（20 Hz → 20 kHz）。 */
    static float[] logCutoffs(int n) {
        float[] c = new float[n];
        double lo = Math.log10(20.0), hi = Math.log10(20000.0);
        for (int i = 0; i < n; i++) {
            double t = (n == 1) ? 0.5 : (i + 0.5) / n;
            c[i] = (float) Math.pow(10, lo + t * (hi - lo));
        }
        return c;
    }

    /** 掛上全域 EQ（先試自訂多段，唔得就退 HAL Equalizer）。 */
    public synchronized boolean attach() {
        return attach(false);
    }

    /**
     * @param halOnly true = 只用車機原生 HAL Equalizer（自訂多段可能「掛得上但唔改聲」時用嚟對照）
     */
    public synchronized boolean attach(boolean halOnly) {
        release();
        error = "";
        if (!halOnly && tryDyn()) return true;
        if (tryHal()) return true;
        engine = "❌ 掛唔到";
        return false;
    }

    private boolean tryDyn() {
        StringBuilder errs = new StringBuilder();
        for (int ch : new int[]{2, 1}) {
            for (int n : TRY) {
                DynamicsProcessing d = null;
                try {
                    float[] cut = logCutoffs(n);
                    DynamicsProcessing.Config cfg = new DynamicsProcessing.Config.Builder(
                            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                            ch,
                            false, 0,        // preEq 唔用
                            false, 0,        // MBC 唔用
                            true, n,         // postEq：N 段
                            false            // limiter 唔用
                    ).build();
                    d = new DynamicsProcessing(1, 0, cfg);          // session 0 = 全域
                    DynamicsProcessing.Eq e = new DynamicsProcessing.Eq(true, true, n);
                    for (int i = 0; i < n; i++) {
                        e.setBand(i, new DynamicsProcessing.EqBand(true, cut[i], 0f));
                    }
                    d.setPostEqAllChannelsTo(e);
                    d.setEnabled(true);
                    // 讀返核實（唔可以只信 set 成功）
                    DynamicsProcessing.EqBand b0 = d.getPostEqBandByChannelIndex(0, 0);
                    if (b0 == null) throw new IllegalStateException("讀唔返 band");
                    dp = d;
                    bands = n;
                    freqs = cut;
                    minMb = -1500;
                    maxMb = 1500;
                    applied = new float[n];
                    engine = String.format(Locale.US, "自訂 %d 段（20 Hz–20 kHz）", n);
                    AppLog.d("EQ", "系統 EQ = DynamicsProcessing " + n + " 段 ch=" + ch);
                    return true;
                } catch (Throwable t) {
                    if (d != null) {
                        try {
                            d.release();
                        } catch (Throwable ignored) {
                        }
                    }
                    errs.append(n).append("段:").append(t.getClass().getSimpleName()).append(' ');
                }
            }
        }
        error = "自訂多段唔支援（" + errs.toString().trim() + "）";
        return false;
    }

    private boolean tryHal() {
        try {
            Equalizer e = new Equalizer(0, 0);            // session 0 = 全域
            short n = e.getNumberOfBands();
            short[] r = e.getBandLevelRange();
            e.setEnabled(true);
            Thread.sleep(60);
            if (!e.getEnabled()) {
                e.release();
                error = "HAL Equalizer 掛得上但啟用唔到";
                return false;
            }
            eq = e;
            bands = n;
            freqs = new float[n];
            for (short i = 0; i < n; i++) freqs[i] = e.getCenterFreq(i) / 1000f;
            minMb = r[0];
            maxMb = r[1];
            applied = new float[n];
            engine = String.format(Locale.US, "車機 EQ %d 段（%.0f Hz–%.0f Hz，±%.0f dB）",
                    n, freqs[0], freqs[n - 1], maxMb / 100f);
            AppLog.d("EQ", "系統 EQ = HAL Equalizer " + n + " 段，範圍 " + r[0] + "~" + r[1] + " mB");
            return true;
        } catch (Throwable t) {
            String e = "HAL Equalizer 都掛唔到（" + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : "：" + t.getMessage()) + "）";
            error = error.isEmpty() ? e : error + " ｜ " + e;
            return false;
        }
    }

    /** 讀返每段實際生效嘅 dB（唔信 set 成功，一律讀返）。 */
    public synchronized float[] readAll() {
        float[] o = new float[bands];
        for (int i = 0; i < bands; i++) o[i] = read(i);
        return o;
    }

    /** 讀返每段實際 cutoff 頻率（Hz）—— DynamicsProcessing 先有得讀返。 */
    public synchronized float[] readCutoffs() {
        float[] o = new float[bands];
        for (int i = 0; i < bands; i++) {
            float f = freqs[i];
            if (dp != null) {
                try {
                    DynamicsProcessing.EqBand b = dp.getPostEqBandByChannelIndex(0, i);
                    if (b != null && b.getCutoffFrequency() > 0) f = b.getCutoffFrequency();
                } catch (Throwable ignored) {
                }
            }
            o[i] = f;
        }
        return o;
    }

    /** 逐段自檢用：只寫一段、其餘歸零，然後讀返全部段。 */
    public synchronized float[] sweepStep(int band, float db, boolean sleep) {
        float[] t = new float[bands];
        for (int k = 0; k < bands; k++) t[k] = 0f;
        if (band >= 0 && band < bands) t[band] = db;
        apply(t, false);
        if (sleep) {
            try {
                Thread.sleep(90);
            } catch (InterruptedException ignored) {
            }
        }
        return readAll();
    }

    /** 直接由 31 段曲線套用（Activity／BootReceiver 共用）：系統段目標 = 曲線喺該段中心頻率嘅值 ＋ 前級。 */
    public synchronized String applyCurve(float[] gains31, double preampDb, boolean verify) {
        if (!ready()) return "未掛上";
        float[] fr = freqs();
        float[] t = new float[fr.length];
        for (int i = 0; i < fr.length; i++) {
            double db = Dsp.responseDb(gains31, fr[i], 44100) + preampDb;
            t[i] = (float) Math.max(-15, Math.min(15, db));
        }
        return apply(t, verify);
    }

    public synchronized boolean ready() {
        return (dp != null || eq != null) && bands > 0;
    }

    public synchronized int bands() {
        return bands;
    }

    /** 系統各段中心頻率（Hz）—— UI 畫標記用。 */
    public synchronized float[] freqs() {
        float[] o = new float[freqs.length];
        System.arraycopy(freqs, 0, o, 0, freqs.length);
        return o;
    }

    public synchronized float[] appliedDb() {
        float[] o = new float[applied.length];
        System.arraycopy(applied, 0, o, 0, applied.length);
        return o;
    }

    /** 係咪用自訂引擎（DynamicsProcessing，段數自己砌）；false = 車機原生 HAL Equalizer。 */
    public synchronized boolean isCustom() {
        return dp != null;
    }

    public synchronized boolean isOn() {
        return on;
    }

    /**
     * 把 31 段曲線映射落系統段（每段取曲線喺佢中心頻率嘅值）。
     *
     * @param target 每段目標 dB（長度 = {@link #bands()}）
     * @param verify true = 寫完讀返核實（拖動中唔好做，commit 才做）
     */
    public synchronized String apply(float[] target, boolean verify) {
        if (!ready()) return "未掛上";
        if (target == null || target.length < bands) return "段數唔對（要 " + bands + "）";
        try {
            if (dp != null) {
                for (int i = 0; i < bands; i++) {
                    float db = clampDb(target[i]);
                    DynamicsProcessing.EqBand old = dp.getPostEqBandByChannelIndex(0, i);
                    float cut = old != null ? old.getCutoffFrequency() : freqs[i];
                    dp.setPostEqBandByChannelIndex(0, i,
                            new DynamicsProcessing.EqBand(true, cut, db));
                    applied[i] = db;
                }
            } else {
                for (int i = 0; i < bands; i++) {
                    float db = clampDb(target[i]);
                    int mb = Math.round(db * 100f);
                    mb = Math.max(minMb, Math.min(maxMb, mb));
                    eq.setBandLevel((short) i, (short) mb);
                    applied[i] = mb / 100f;
                }
            }
            if (!verify) return "已套用";
            Thread.sleep(120);
            StringBuilder sb = new StringBuilder();
            int bad = 0;
            for (int i = 0; i < bands; i++) {
                float back = read(i);
                if (Math.abs(back - applied[i]) > 0.35f) bad++;
                if (i < 4) {
                    if (sb.length() > 0) sb.append(" ｜ ");
                    sb.append(String.format(Locale.US, "%.0fHz %.1f→%.1f", freqs[i], applied[i], back));
                }
            }
            return bad == 0
                    ? String.format(Locale.US, "✅ %d 段全部讀返一致", bands)
                    : "⚠️ " + bad + "/" + bands + " 段讀返唔一致（" + sb + "）";
        } catch (Throwable t) {
            return "⚠️ 套用失敗：" + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : "：" + t.getMessage());
        }
    }

    private float read(int i) {
        try {
            if (dp != null) {
                DynamicsProcessing.EqBand b = dp.getPostEqBandByChannelIndex(0, i);
                return b == null ? 0 : b.getGain();
            }
            return eq.getBandLevel((short) i) / 100f;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private float clampDb(float db) {
        float lo = minMb / 100f, hi = maxMb / 100f;
        return db < lo ? lo : (db > hi ? hi : db);
    }

    public synchronized boolean setEnabled(boolean enable) {
        try {
            if (dp != null) dp.setEnabled(enable);
            else if (eq != null) eq.setEnabled(enable);
            else return false;
            Thread.sleep(60);
            on = (dp != null) ? dp.getEnabled() : eq.getEnabled();
            return on == enable;
        } catch (Throwable t) {
            error = "開關失敗：" + t.getClass().getSimpleName();
            return false;
        }
    }

    /** 一行 UI 狀態（唔含 IP／網域／BUS 名）。 */
    public synchronized String status() {
        if (!ready()) return engine.isEmpty() ? "未掛上" : engine;
        return engine + (on ? " · 生效中" : " · 已停用");
    }

    public synchronized String detail() {
        if (!ready()) return engine + (error.isEmpty() ? "" : "：" + error);
        StringBuilder sb = new StringBuilder(engine);
        for (int i = 0; i < bands; i++) {
            sb.append(String.format(Locale.US, "\n  段%d %.0f Hz = %.1f dB", i, freqs[i], appliedDbSafe(i)));
        }
        return sb.toString();
    }

    private float appliedDbSafe(int i) {
        return (i >= 0 && i < applied.length) ? applied[i] : 0;
    }

    public synchronized void release() {
        try {
            if (dp != null) dp.release();
        } catch (Throwable ignored) {
        }
        try {
            if (eq != null) eq.release();
        } catch (Throwable ignored) {
        }
        dp = null;
        eq = null;
        bands = 0;
        on = false;
    }

    public static SysEq create() {
        return new SysEq();
    }

    public static Context appContext(Context c) {
        return c.getApplicationContext();
    }
}
