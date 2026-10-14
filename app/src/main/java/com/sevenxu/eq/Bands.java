package com.sevenxu.eq;

/**
 * 31 段 ISO 1/3 八度頻段（20 Hz → 20 kHz）。
 *
 * <p>純 Java（唔 import android）→ 可以直接用 javac/java 喺 server 上跑單元測試，
 * 唔使等車機／模擬器。</p>
 */
public final class Bands {

    /** 31 段中心頻率（Hz），ISO 1/3 八度標準序列。 */
    public static final double[] FREQ = {
            20, 25, 31.5, 40, 50, 63, 80,
            100, 125, 160, 200, 250, 315, 400, 500, 630, 800,
            1000, 1250, 1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000,
            10000, 12500, 16000, 20000
    };

    /** 顯示標籤（短、要夠位擺喺 31 條柱下面）。 */
    public static final String[] LABEL = {
            "20", "25", "31", "40", "50", "63", "80",
            "100", "125", "160", "200", "250", "315", "400", "500", "630", "800",
            "1k", "1.2k", "1.6k", "2k", "2.5k", "3.1k", "4k", "5k", "6.3k", "8k",
            "10k", "12k", "16k", "20k"
    };

    public static final int N = FREQ.length;          // 31
    public static final float MIN_DB = -12f;          // 每段可調範圍
    public static final float MAX_DB = 12f;

    /** 1/3 八度帶寬對應嘅 Q（= 4.3185）。 */
    public static final double Q = 4.3185;

    private Bands() {
    }

    /** 檢查索引安全（UI 層攞 band 時用）。 */
    public static int clampBand(int i) {
        return i < 0 ? 0 : (i >= N ? N - 1 : i);
    }

    /** 由頻率反查最近嘅段（試聽／量測用）。 */
    public static int nearest(double hz) {
        int best = 0;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < N; i++) {
            double d = Math.abs(Math.log(FREQ[i] / hz));
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        return best;
    }
}
