package com.sevenxu.eq;

/**
 * 14 段（車機實測真正有反應嘅頻率：50 Hz → 12 kHz）。
 *
 * <p>純 Java（唔 import android）→ 可以直接用 javac/java 喺 server 上跑單元測試，
 * 唔使等車機／模擬器。</p>
 */
public final class Bands {

    /** 14 段中心頻率（Hz）—— 用戶實測：其餘頻率調咗都冇反應，所以只出呢 14 段。 */
    public static final double[] FREQ = {
            50, 63, 160, 250, 400, 630, 1000, 1600, 2500, 4000, 6300, 8000, 10000, 12000
    };

    /** 顯示標籤（短、要夠位擺喺柱下面）。 */
    public static final String[] LABEL = {
            "50", "63", "160", "250", "400", "630", "1k", "1.6k", "2.5k", "4k", "6.3k", "8k", "10k", "12k"
    };

    public static final int N = FREQ.length;          // 14
    public static final float MIN_DB = -10f;          // 每段可調範圍
    public static final float MAX_DB = 10f;

    /** 14 段（相鄰約 2/3 八度）對應嘅 Q。 */
    public static final double Q = 1.8;

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
