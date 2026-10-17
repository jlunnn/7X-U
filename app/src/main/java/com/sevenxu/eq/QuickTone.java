package com.sevenxu.eq;

/**
 * 快速音色旋鈕（行車用）：SUB / BASS / MID / UPPER / AIR ＋ MASTER。
 *
 * <p>做法：旋鈕只係「加喺使用者曲線上面嘅偏移層」—— 每個旋鈕對應一組頻段，
 * 加減 dB 之後同 base 曲線相加（夾喺 {@link Bands#MIN_DB}～{@link Bands#MAX_DB}）。
 * 咁樣：(1) 旋鈕唔會洗走你嘅曲線；(2) 拖任何一條柱，base 會反向扣返偏移，
 * 下次再扭旋鈕仍然由你見到嘅曲線開始。</p>
 *
 * <p>純 Java（唔 import android）→ 可以喺 server 直接用 javac/java 驗數值。</p>
 */
public final class QuickTone {

    /** 5 個旋鈕（次序 = UI 次序）。 */
    public static final String[] LABEL = {"SUB", "BASS", "MID", "UPPER", "AIR"};

    /** 每組包含邊幾段（index 對應 {@link Bands#FREQ}，互不重疊、覆蓋全部 14 段）。 */
    public static final int[][] BANDS = {
            {0, 1},          // 50 / 63 Hz
            {2, 3, 4},       // 160 / 250 / 400 Hz
            {5, 6, 7},       // 630 / 1k / 1.6k
            {8, 9, 10},      // 2.5k / 4k / 6.3k
            {11, 12, 13}     // 8k / 10k / 12k
    };

    /** 每粒旋鈕可扭範圍（dB）。 */
    public static final float MAX = 6f;
    /** 一撳加減幾多 dB。 */
    public static final float STEP = 1f;

    private QuickTone() {
    }

    public static float clamp(float v) {
        return Math.max(-MAX, Math.min(MAX, v));
    }

    /** 某一段受邊個旋鈕影響（現時設計互不重疊 → 最多一個）。 */
    public static int knobOf(int band) {
        for (int k = 0; k < BANDS.length; k++) {
            for (int b : BANDS[k]) {
                if (b == band) return k;
            }
        }
        return -1;
    }

    /** 某一段嘅總偏移（dB）。 */
    public static float offsetFor(int band, float[] off) {
        float s = 0f;
        for (int k = 0; k < BANDS.length && k < off.length; k++) {
            for (int b : BANDS[k]) {
                if (b == band) s += off[k];
            }
        }
        return s;
    }

    /** base 曲線 ＋ 旋鈕偏移 → 實際生效曲線（已夾範圍）。 */
    public static float[] apply(float[] base, float[] off) {
        float[] g = new float[Bands.N];
        for (int i = 0; i < Bands.N; i++) {
            float b = (base == null || i >= base.length) ? 0f : base[i];
            float v = b + offsetFor(i, off);
            g[i] = Math.max(Bands.MIN_DB, Math.min(Bands.MAX_DB, v));
        }
        return g;
    }

    /** 由實際曲線反推 base（拖柱用）：base = 你拖到嘅值 − 該段旋鈕偏移。
     *  刻意唔夾範圍：加法在 apply() 才夾，免得失去 headroom（否則再扭旋鈕會跳值）。 */
    public static float baseFor(int band, float effectiveDb, float[] off) {
        return effectiveDb - offsetFor(band, off);
    }
}
