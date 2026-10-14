package com.sevenxu.eq;

/**
 * 測試訊號產生器 ＋ 頻譜量測（純 Java，冇 android import）。
 *
 * <p>四種訊號：正弦（單頻試聽）、粉紅噪音（全頻聽感最接近音樂能量分佈，調 EQ 最有用）、
 * 白噪音、對數掃頻（20 Hz → 20 kHz 循環，用耳聽邊段有問題）。</p>
 */
public final class Gen {

    public static final int SINE = 0;
    public static final int PINK = 1;
    public static final int WHITE = 2;
    public static final int SWEEP = 3;

    public static final String[] KIND_NAME = {"單頻", "粉紅噪音", "白噪音", "掃頻"};

    private double phase = 0;
    private double sweepT = 0;
    private final double[] pk = new double[7];        // 粉紅噪音 filter 狀態
    private final java.util.Random rnd = new java.util.Random(20261014L);

    public void reset() {
        phase = 0;
        sweepT = 0;
        java.util.Arrays.fill(pk, 0);
    }

    /**
     * 產生一個 block（in-place 寫入 l／r；兩聲道用同一 generator = 相關噪音，聽感集中）。
     *
     * @param kind  {@link #SINE}／{@link #PINK}／{@link #WHITE}／{@link #SWEEP}
     * @param freq  單頻頻率（SINE 用）
     * @param amp   輸出幅度（0–1）
     */
    public void fill(float[] l, float[] r, int frames, int rate, int kind, double freq, float amp) {
        boolean same = (l == r);
        for (int i = 0; i < frames; i++) {
            double x;
            switch (kind) {
                case SINE:
                    x = Math.sin(phase);
                    phase += 2 * Math.PI * freq / rate;
                    if (phase > 2 * Math.PI) phase -= 2 * Math.PI;
                    break;
                case SWEEP: {
                    // 10 秒由 20 Hz 掃到 20 kHz（對數）
                    double t = sweepT / (10.0 * rate);
                    if (t > 1) {
                        t = 0;
                        sweepT = 0;
                        phase = 0;
                    }
                    double f = 20.0 * Math.pow(1000.0, t);
                    x = Math.sin(phase);
                    phase += 2 * Math.PI * f / rate;
                    if (phase > 2 * Math.PI) phase -= 2 * Math.PI;
                    sweepT++;
                    break;
                }
                case WHITE:
                    x = rnd.nextDouble() * 2 - 1;
                    break;
                case PINK:
                default: {
                    double w = rnd.nextDouble() * 2 - 1;
                    pk[0] = 0.99886 * pk[0] + w * 0.0555179;
                    pk[1] = 0.99332 * pk[1] + w * 0.0750759;
                    pk[2] = 0.96900 * pk[2] + w * 0.1538520;
                    pk[3] = 0.86650 * pk[3] + w * 0.3104856;
                    pk[4] = 0.55000 * pk[4] + w * 0.5329522;
                    pk[5] = -0.7616 * pk[5] - w * 0.0168980;
                    x = (pk[0] + pk[1] + pk[2] + pk[3] + pk[4] + pk[5] + pk[6] + w * 0.5362) * 0.11;
                    pk[6] = w * 0.115926;
                    break;
                }
            }
            float v = (float) (x * amp);
            l[i] = v;
            if (!same) r[i] = v;
        }
    }

    // ---------------------------------------------------------------- 量測

    /**
     * Goertzel：算 buffer 內某一條頻率嘅幅度（dBFS 相對值）。
     *
     * <p>31 段 × 每幀 4096 sample 對車機 CPU 完全冇壓力，可以做到 ~5 Hz 更新。</p>
     */
    public static double magDb(short[] x, int n, double freq, int rate) {
        if (n <= 0) return -120;
        double w = 2 * Math.PI * freq / rate;
        double c = 2 * Math.cos(w);
        double s1 = 0, s2 = 0;
        for (int i = 0; i < n; i++) {
            double s0 = x[i] + c * s1 - s2;
            s2 = s1;
            s1 = s0;
        }
        double power = s1 * s1 + s2 * s2 - c * s1 * s2;
        if (power < 0) power = 0;
        double amp = 2.0 * Math.sqrt(power) / n / 32768.0;
        return amp <= 1e-9 ? -120 : 20 * Math.log10(amp);
    }

    /** float buffer 版本（引擎內部用）。 */
    public static double magDb(float[] x, int n, double freq, int rate) {
        if (n <= 0) return -120;
        double w = 2 * Math.PI * freq / rate;
        double c = 2 * Math.cos(w);
        double s1 = 0, s2 = 0;
        for (int i = 0; i < n; i++) {
            double s0 = x[i] + c * s1 - s2;
            s2 = s1;
            s1 = s0;
        }
        double power = s1 * s1 + s2 * s2 - c * s1 * s2;
        if (power < 0) power = 0;
        double amp = 2.0 * Math.sqrt(power) / n;
        return amp <= 1e-9 ? -120 : 20 * Math.log10(amp);
    }

    public static float rmsDb(short[] x, int n) {
        double acc = 0;
        for (int i = 0; i < n; i++) {
            double v = x[i] / 32768.0;
            acc += v * v;
        }
        double rms = Math.sqrt(acc / Math.max(1, n));
        return (float) (rms <= 1e-9 ? -120 : 20 * Math.log10(rms));
    }
}
