package com.sevenxu.eq;

/**
 * 31 段圖形等化器 DSP：每段一個 peaking biquad（RBJ Audio-EQ-Cookbook），
 * 串聯 31 個 filter 逐個 sample 處理。
 *
 * <p>純 Java（冇 android import）→ 可以喺 server 用 javac/java 直接驗證頻率響應，
 * 唔使開模擬器。Android 側只負責餵 buffer。</p>
 *
 * <p>重點設計：</p>
 * <ul>
 *   <li><b>平滑</b>：拖滑桿時 target 會逐個 block 迫近（約 25 ms），避免係數突變造成「啪」聲。</li>
 *   <li><b>軟限幅</b>：31 段一齊推 +12 dB 會爆（相加可達 +36 dB），所以輸出過 tanh 軟限幅，
 *       一路推只會「厚」，唔會削波破音（同鋼琴 app 學到嘅做法一致）。</li>
 *   <li><b>採樣率</b>：係數跟採樣率算，換 source（44.1k / 48k）要再叫 {@link #setRate(int)}。</li>
 * </ul>
 */
public final class Dsp {

    public static final int N = Bands.N;

    /** A/B 直接比較（bypass = 唔行 filter，但仍過前級同限幅）。 */
    private volatile boolean bypass = false;

    private int rate = 44100;

    /** 目標增益（dB）／平滑後現行增益（dB）。 */
    private final float[] target = new float[N];
    private final float[] cur = new float[N];

    /** 每段正規化後嘅係數（a0 已除）。 */
    private final double[] b0 = new double[N];
    private final double[] b1 = new double[N];
    private final double[] b2 = new double[N];
    private final double[] a1 = new double[N];
    private final double[] a2 = new double[N];

    /** DF2T 狀態：[聲道][段]。 */
    private final double[][] s1 = new double[2][N];
    private final double[][] s2 = new double[2][N];

    /** 每次 block 迫近嘅比例（0–1）：0.62 ≈ 96 ms 收斂 @1024 frame / 44.1 kHz。 */
    private double smoothStep = 0.62;

    /** 前級增益（dB，通常 ≤ 0，用嚟預留 headroom）。 */
    private volatile double preampDb = 0;

    /** 輸出量測：最近一個 block 嘅 RMS／峯值（dBFS）。 */
    private volatile float outRmsDb = -120f;
    private volatile float outPeakDb = -120f;

    public Dsp() {
        setRate(44100);
    }

    // ------------------------------------------------------------------ 設定

    public void setRate(int r) {
        if (r < 8000) r = 44100;
        this.rate = r;
        recomputeAll();
    }

    public int rate() {
        return rate;
    }

    public void setBypass(boolean b) {
        bypass = b;
    }

    public boolean bypass() {
        return bypass;
    }

    public void setPreampDb(double db) {
        preampDb = Math.max(-24, Math.min(12, db));
    }

    public double preampDb() {
        return preampDb;
    }

    public void set(int band, float db) {
        target[Bands.clampBand(band)] = clamp(db);
    }

    public float get(int band) {
        return target[Bands.clampBand(band)];
    }

    public void setGains(float[] db) {
        for (int i = 0; i < N; i++) target[i] = clamp(i < db.length ? db[i] : 0);
    }

    /** 立即生效（開 preset／重置用；唔要平滑）。 */
    public void setGainsNow(float[] db) {
        setGains(db);
        System.arraycopy(target, 0, cur, 0, N);
        recomputeAll();
        clearState();
    }

    public float[] gains() {
        float[] out = new float[N];
        System.arraycopy(target, 0, out, 0, N);
        return out;
    }

    private static float clamp(float db) {
        return db < Bands.MIN_DB ? Bands.MIN_DB : (db > Bands.MAX_DB ? Bands.MAX_DB : db);
    }

    public void clearState() {
        for (int c = 0; c < 2; c++) {
            java.util.Arrays.fill(s1[c], 0);
            java.util.Arrays.fill(s2[c], 0);
        }
    }

    // ------------------------------------------------------------ 係數計算

    private void recomputeAll() {
        for (int i = 0; i < N; i++) recompute(i, cur[i]);
    }

    /** 單段 peaking EQ 係數（RBJ）。 */
    private void recompute(int i, double gainDb) {
        double f0 = Bands.FREQ[i];
        double nyq = rate * 0.5;
        if (f0 > nyq * 0.92) f0 = nyq * 0.92;          // 貼近 Nyquist 會唔穩定
        double A = Math.pow(10.0, gainDb / 40.0);
        double w0 = 2.0 * Math.PI * f0 / rate;
        double cs = Math.cos(w0);
        double sn = Math.sin(w0);
        double alpha = sn / (2.0 * Bands.Q);
        double a0 = 1.0 + alpha / A;
        b0[i] = (1.0 + alpha * A) / a0;
        b1[i] = (-2.0 * cs) / a0;
        b2[i] = (1.0 - alpha * A) / a0;
        a1[i] = (-2.0 * cs) / a0;
        a2[i] = (1.0 - alpha / A) / a0;
    }

    /**
     * 一個 peaking 段喺頻率 f 嘅幅度（dB） —— 畫響應曲線用（級聯響應 = 各段 dB 相加）。
     */
    public static double peakResponseDb(double gainDb, double f0, int rate, double f) {
        if (gainDb == 0) return 0;
        double nyq = rate * 0.5;
        if (f0 > nyq * 0.92) f0 = nyq * 0.92;
        double A = Math.pow(10.0, gainDb / 40.0);
        double w0 = 2.0 * Math.PI * f0 / rate;
        double cs = Math.cos(w0);
        double sn = Math.sin(w0);
        double alpha = sn / (2.0 * Bands.Q);
        double a0 = 1.0 + alpha / A;
        double b0 = (1.0 + alpha * A) / a0;
        double b1 = (-2.0 * cs) / a0;
        double b2 = (1.0 - alpha * A) / a0;
        double a1 = (-2.0 * cs) / a0;
        double a2 = (1.0 - alpha / A) / a0;
        double w = 2.0 * Math.PI * f / rate;
        // H(e^{jw}) = (b0 + b1 e^{-jw} + b2 e^{-2jw}) / (1 + a1 e^{-jw} + a2 e^{-2jw})
        double cw = Math.cos(w), c2 = Math.cos(2 * w);
        double sw = Math.sin(w), s2 = Math.sin(2 * w);
        double nr = b0 + b1 * cw + b2 * c2;
        double ni = -(b1 * sw + b2 * s2);
        double dr = 1 + a1 * cw + a2 * c2;
        double di = -(a1 * sw + a2 * s2);
        double num = Math.sqrt(nr * nr + ni * ni);
        double den = Math.sqrt(dr * dr + di * di);
        if (den == 0) return 0;
        return 20.0 * Math.log10(num / den);
    }

    /** 級聯 31 段後喺頻率 f 嘅總響應（dB，唔計前級）。 */
    public double responseDb(double f) {
        return responseDb(target, f, rate);
    }

    /** 用指定增益陣列計級聯響應（畫曲線／測試用）。 */
    public static double responseDb(float[] gains, double f, int rate) {
        double sum = 0;
        for (int i = 0; i < N; i++) {
            if (gains[i] != 0) sum += peakResponseDb(gains[i], Bands.FREQ[i], rate, f);
        }
        return sum;
    }

    // ---------------------------------------------------------------- 處理

    /**
     * 處理一個 block（in-place）。L／R 可以係同一個 array（單聲道）。
     *
     * @param l     左聲道
     * @param r     右聲道（可 = l）
     * @param frames 樣本數
     */
    public void process(float[] l, float[] r, int frames) {
        tick();
        float pre = (float) Math.pow(10.0, preampDb / 20.0);
        boolean same = (l == r);
        boolean skipFilters = bypass;
        if (skipFilters) {
            if (pre != 1f) {
                for (int i = 0; i < frames; i++) {
                    l[i] *= pre;
                    r[i] *= pre;
                }
            }
            finish(l, r, frames, same);
            return;
        }
        for (int i = 0; i < frames; i++) {
            double xl = l[i] * pre;
            double xr = same ? 0 : r[i] * pre;
            for (int k = 0; k < N; k++) {
                double y = b0[k] * xl + s1[0][k];
                s1[0][k] = b1[k] * xl - a1[k] * y + s2[0][k];
                s2[0][k] = b2[k] * xl - a2[k] * y;
                xl = y;
                if (!same) {
                    double yr = b0[k] * xr + s1[1][k];
                    s1[1][k] = b1[k] * xr - a1[k] * yr + s2[1][k];
                    s2[1][k] = b2[k] * xr - a2[k] * yr;
                    xr = yr;
                }
            }
            l[i] = (float) xl;
            if (!same) r[i] = (float) xr;
        }
        finish(l, r, frames, same);
    }

    /** 軟限幅 + 量測（RMS／峯值 dBFS）。 */
    private void finish(float[] l, float[] r, int frames, boolean same) {
        double acc = 0;
        float peak = 0;
        for (int i = 0; i < frames; i++) {
            double a = soft(l[i]);
            double b = same ? a : soft(r[i]);
            l[i] = (float) a;
            if (!same) r[i] = (float) b;
            acc += a * a;
            float ap = (float) Math.abs(a);
            if (ap > peak) peak = ap;
        }
        double rms = Math.sqrt(acc / Math.max(1, frames));
        outRmsDb = (float) (rms <= 1e-9 ? -120 : 20 * Math.log10(rms));
        outPeakDb = (float) (peak <= 1e-9 ? -120 : 20 * Math.log10(peak));
    }

    /** tanh 軟限幅：≤ -3 dBFS 幾乎線性，> 0 就壓。 */
    public static double soft(double x) {
        return Math.tanh(x * 1.15) * 0.94;
    }

    /** 每個 block 叫一次：把 cur 迫近 target，有變就重算係數。 */
    private void tick() {
        for (int i = 0; i < N; i++) {
            float d = target[i] - cur[i];
            if (d == 0) continue;
            if (Math.abs(d) < 0.02f) {
                cur[i] = target[i];
            } else {
                cur[i] += (float) (d * smoothStep);
            }
            recompute(i, cur[i]);
        }
    }

    public float outRmsDb() {
        return outRmsDb;
    }

    public float outPeakDb() {
        return outPeakDb;
    }

    /** 有冇未追到嘅 band（UI 顯示「處理中」用）。 */
    public boolean settling() {
        for (int i = 0; i < N; i++) if (target[i] != cur[i]) return true;
        return false;
    }
}
