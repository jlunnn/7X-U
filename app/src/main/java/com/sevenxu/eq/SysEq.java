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

    /** Bass Boost dB（0–12，由 UI 寫入）。 */
    public static int bassBoostDb = 0;
    /** Limiter 開關（由 UI 寫入）。 */
    public static boolean limiterOn = false;
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
                            false            // limiter stage 關（會爆聲道，已 revert）
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
                    engine = String.format(Locale.US, "自訂 %d 段（%.0f Hz–%.0f kHz）", Bands.N,
                            Bands.FREQ[0], Bands.FREQ[Bands.N - 1] / 1000.0);
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

    /**
     * 靜默逐段自檢（開 app 自動跑）：bypass（setEnabled(false)）住逐段寫入 + 讀返，
     * 過程唔會出聲；測完復原曲線同原本開關狀態。回傳 31 段邊幾段「真受控」。
     */
    public synchronized boolean[] silentSweep(float db, float[] gains31, double preampDb) {
        boolean[] out = new boolean[Bands.N];
        if (!ready()) return out;
        boolean wasOn = on;
        if (wasOn) setEnabled(false);                    // 靜音：bypass 住寫參數
        boolean[] hit = new boolean[bands];
        for (int i = 0; i < bands; i++) {
            float[] back = sweepStep(i, db, true);
            float got = back[i];
            float nb = 0f;
            for (int k = 0; k < bands; k++) {
                if (k != i && Math.abs(back[k]) > Math.abs(nb)) nb = back[k];
            }
            hit[i] = Math.abs(got - db) <= 0.6f && Math.abs(nb) <= 0.6f;
        }
        applyCurve(gains31, preampDb, false);             // 復原用家曲線
        if (wasOn) setEnabled(true);
        return mapHit(hit);
    }

    /** 把「逐段自檢」每段結果映射去 14 段（按頻率揀最近嘅介面段；自訂／車機原生都一樣）。 */
    public synchronized boolean[] mapHit(boolean[] hit) {
        boolean[] out = new boolean[Bands.N];
        for (int i = 0; i < hit.length; i++) {
            if (!hit[i]) continue;
            float fh = (i < freqs.length) ? freqs[i] : 0f;
            if (fh <= 0) {
                if (i < Bands.N) out[i] = true;
                continue;
            }
            int best = -1;
            double bd = Double.MAX_VALUE;
            for (int b = 0; b < Bands.N; b++) {
                double dd = Math.abs(Math.log(Bands.FREQ[b] / (double) fh));
                if (dd < bd) {
                    bd = dd;
                    best = b;
                }
            }
            if (best >= 0) out[best] = true;
        }
        return out;
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
    /** 用家音量（dB 數位偏移）：0 = 唔變；由懸浮窗 VOLUME 控制。 */
    public static volatile double userVolDb = 0;
    /** EQ 開啟後嘅整體音量補償（dB，0–12）：抵消「開 EQ 變細聲」。 */
    public static volatile double compDb = 0;
    /** 開關淡入淡出偏移（dB，-24→0）。 */
    private static volatile double fadeDb = 0;
    private float[] lastG31;
    private double lastPre;


    /** Bass Boost：80 Hz 以下平滑低架（0–12 dB）。 */
    private double bassTerm(float f) {
        int bb = bassBoostDb;
        if (bb <= 0) return 0;
        double w = 1.0 / (1.0 + Math.pow(f / 80.0, 2.0));
        return bb * w;
    }

    /** Limiter：峰值唔可以超過 -0.5 dBFS，超過就整體扣前級（防削波）。 */
    private double limTrim(float[] g31, double preamp, double vol) {
        if (!limiterOn) return 0;
        float[] fr = freqs();
        double peak = -999;
        for (float f : fr) {
            double d = Dsp.responseDb(g31, f, 44100) + preamp + vol + bassTerm(f);
            if (d > peak) peak = d;
        }
        return peak > 0 ? (-peak) : 0;
    }

    public synchronized String applyCurve(float[] gains31, double preampDb, boolean verify) {
        if (!ready()) return "未掛上";
        lastG31 = gains31;
        syncLimiterStage();
        lastPre = preampDb;
        float[] fr = freqs();
        float[] t = new float[fr.length];
        for (int i = 0; i < fr.length; i++) {
            double db = Dsp.responseDb(gains31, fr[i], 44100) + preampDb + userVolDb
                    + bassTerm(fr[i]) + fadeDb + limTrim(gains31, preampDb, userVolDb);
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

    /**
     * 31 段入面，邊幾段係車機「真受控」。
     * 自訂引擎 = 全部 31 段真受控；車機原生 = 只有最接近車機段中心頻率嘅嗰幾段，
     * 其餘段係「映射」（撳咗唔會單獨變）。
     */
    public synchronized boolean[] realBands() {
        boolean[] r = new boolean[Bands.N];
        if (!ready()) return r;
        if (dp != null) {
            for (int i = 0; i < Bands.N; i++) r[i] = true;
            return r;
        }
        for (float fh : freqs) {
            int best = -1;
            double bd = Double.MAX_VALUE;
            for (int i = 0; i < Bands.N; i++) {
                double dd = Math.abs(Math.log(Bands.FREQ[i] / (double) fh));
                if (dd < bd) {
                    bd = dd;
                    best = i;
                }
            }
            if (best >= 0) r[best] = true;
        }
        return r;
    }

    /** 車機真受控段數（自訂引擎 = 31；車機原生 = 車機自己嘅段數）。 */
    public synchronized int realCount() {
        int n = 0;
        for (boolean b : realBands()) if (b) n++;
        return n;
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
                // ⚠️ 一定要寫「全部聲道」：舊版只寫 setPostEqBandByChannelIndex(0, …)
                // → 只有左聲道跟到曲線／前級，右聲道唔動（實測：拖前級會搞到左邊冇聲）。
                DynamicsProcessing.Eq ne = new DynamicsProcessing.Eq(true, true, bands);
                for (int i = 0; i < bands; i++) {
                    float db = clampDb(target[i]);
                    float cut = freqs[i];
                    try {
                        DynamicsProcessing.EqBand old = dp.getPostEqBandByChannelIndex(0, i);
                        if (old != null && old.getCutoffFrequency() > 0) cut = old.getCutoffFrequency();
                    } catch (Throwable ignored) {
                    }
                    ne.setBand(i, new DynamicsProcessing.EqBand(true, cut, db));
                    applied[i] = db;
                }
                dp.setPostEqAllChannelsTo(ne);
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
            String lr = lrCheck();
            if (verify) AppLog.d("EQ", "套用 " + bands + " 段："
                    + (bad == 0 ? "讀返一致" : ("唔一致 " + bad + " 段")) + " " + lr);

            return (bad == 0
                    ? String.format(Locale.US, "✅ %d 段全部讀返一致", bands)
                    : "⚠️ " + bad + "/" + bands + " 段讀返唔一致（" + sb + "）") + lr;
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

    /**
     * 左右聲道一致性自檢：右聲道（channel 1）每段係唔係同左聲道一樣？
     * 防止再出現「只寫 channel 0 → 一邊聲道唔跟曲線」嘅 bug。
     */
    private String lrCheck() {
        if (dp == null) return "";
        try {
            int bad = 0;
            for (int i = 0; i < bands; i++) {
                DynamicsProcessing.EqBand r = dp.getPostEqBandByChannelIndex(1, i);
                if (r == null) continue;
                if (Math.abs(r.getGain() - applied[i]) > 0.35f) bad++;
            }
            return bad == 0 ? "（左右一致 ✅）" : "（⚠️ 右聲道 " + bad + " 段唔一致）";
        } catch (Throwable t) {
            return "";
        }
    }

    private float clampDb(float db) {
        float lo = minMb / 100f, hi = maxMb / 100f;
        return db < lo ? lo : (db > hi ? hi : db);
    }


    /** 同步 limiter stage（開／關），唔需要重掛 effect。 */
    private void syncLimiterStage() {
        // 已停用：DP limiter stage 會整死右聲道
    }

    public synchronized boolean setEnabled(boolean enable) {
        try {
            boolean cur = (dp != null) ? dp.getEnabled() : (eq != null && eq.getEnabled());
            if (cur != enable && lastG31 != null) {
                if (enable) {
                    fadeTo(-24);                       // 先靜音
                    if (dp != null) dp.setEnabled(true);
                    else eq.setEnabled(true);
                    fadeTo(0);                         // 再淡入
                } else {
                    fadeTo(-24);                       // 淡出
                    if (dp != null) dp.setEnabled(false);
                    else eq.setEnabled(false);
                    fadeTo(0);                         // 還原曲線值
                }
            }
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


    /** 8 步 × 18ms ≈ 150ms 淡變到指定 offset，避免開關時「嘭」一聲。 */
    private void fadeTo(double target) {
        if (lastG31 == null) {
            fadeDb = target;
            return;
        }
        int steps = 8;
        double from = fadeDb;
        for (int k = 1; k <= steps; k++) {
            fadeDb = from + (target - from) * k / steps;
            applyCurve(lastG31, lastPre, false);
            try {
                Thread.sleep(18);
            } catch (InterruptedException ignored) {
            }
        }
        fadeDb = target;
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
