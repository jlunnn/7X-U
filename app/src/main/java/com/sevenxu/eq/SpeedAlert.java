package com.sevenxu.eq;

/**
 * 超速提示的規矩：什麼時候算超速、什麼時候該響、什麼時候閃。
 *
 * <h3>為什麼單獨放一個類</h3>
 *
 * <p>這裏全是「什麼時候該吵醒開車的人」的判斷。吵錯一次比不響更糟 ——
 * 在 100 km/h 上下抖一下就開始嗶，人會把提示關掉，那這個功能就等於沒有。
 * 所以它寫成純邏輯（不碰 Android API），用單測把每條邊都釘住，
 * 而不是散在 Service 的刷新循環裏。</p>
 *
 * <h3>2026-10-07 用户定的三條（改動都在這個類裏）</h3>
 *
 * <ol>
 *   <li><b>只響一聲</b>（原來是 beep beep 兩聲，更早是每秒一下）。</li>
 *   <li><b>一直超速就一直提</b>：響完第一聲之後，只要還沒掉回門檻以下，
 *       每隔 {@link #DEFAULT_REPEAT_MS}（設定頁可選）再響一聲，
 *       直到人自己把提示關掉為止。</li>
 *   <li><b>沒有「臨時靜音」這種狀態</b>。點一下車速牌就是把「超速提示」這個功能本身
 *       關掉（設定頁那個開關，寫進配置、關掉就一直關着），再點一下才開回來。
 *       以前靜音是記憶體裏的臨時狀態、掉回門檻以下就自己解除，
 *       所以減速後再加速又會彈回紅色警示 —— 用户明確不要這種行為。</li>
 * </ol>
 */
public final class SpeedAlert {

    private SpeedAlert() {
    }

    /** 超速門檻的默認值（km/h）。 */
    public static final float DEFAULT_THRESHOLD_KMH = 100f;

    /** 門檻的合法範圍：太小等於一直響，太大等於永遠不響。 */
    public static final float MIN_THRESHOLD_KMH = 30f;
    public static final float MAX_THRESHOLD_KMH = 200f;

    /**
     * 一次超速提示響幾聲。
     *
     * <p>2026-10-07 用户指定：<b>只出一聲 beep</b>（2.7.2 是兩聲）。
     * 聲音由 {@code SpeedBeeper}/{@code CarAudioChannel} 合成，
     * 所以狀態機這邊只管「這一刻該不該響」。</p>
     */
    public static final int BEEPS_PER_ALERT = 1;

    /** 持續超速時，重複響的間隔默認值：3 秒（設定頁可選 1–10 秒）。 */
    public static final long DEFAULT_REPEAT_MS = 3_000L;

    /** 重複間隔的合法範圍。太小會吵，太大等於只響一聲。 */
    public static final long MIN_REPEAT_MS = 1_000L;
    public static final long MAX_REPEAT_MS = 10_000L;

    /** 閃動的半周期：紅 / 白各 400 ms，一秒閃一次多。 */
    public static final long BLINK_HALF_PERIOD_MS = 400L;

    /** 重畫閃動的步長。比半周期小，才看得出兩種相位。 */
    public static final long BLINK_STEP_MS = 200L;

    /**
     * 遲滯：已經超速之後，要掉到門檻以下這麼多才算解除。
     *
     * <p>車速是跳的，卡在門檻上時讀數會在 100.0 / 99.5 之間來回。沒有這一檔，
     * 提示會跟着讀數一閃一停，看着像壞了。</p>
     */
    public static final float HYSTERESIS_KMH = 2f;

    /** 門檻夾進合法範圍；NaN（配置壞了）一律回默認值。 */
    public static float clampThreshold(float kmh) {
        if (Float.isNaN(kmh)) {
            return DEFAULT_THRESHOLD_KMH;
        }
        if (kmh < MIN_THRESHOLD_KMH) {
            return MIN_THRESHOLD_KMH;
        }
        if (kmh > MAX_THRESHOLD_KMH) {
            return MAX_THRESHOLD_KMH;
        }
        return kmh;
    }

    /** 重複間隔夾進合法範圍；壞值回默認的 3 秒。 */
    public static long clampRepeatMs(long ms) {
        if (ms < MIN_REPEAT_MS) {
            return DEFAULT_REPEAT_MS;
        }
        if (ms > MAX_REPEAT_MS) {
            return MAX_REPEAT_MS;
        }
        return ms;
    }

    /**
     * 這一刻算不算超速。
     *
     * <p>三種情況不算：沒讀數（NaN）、壞值（負速度）、讀數過期
     * （超過 {@link RoundSign#STALE_MS}，那説明車機已經不報速度了）。</p>
     *
     * @param wasOver 上一刻是否已經超速 —— 決定要不要用遲滯那一檔
     */
    public static boolean isOver(float speedKmh, long ageMs, float thresholdKmh, boolean wasOver) {
        if (Float.isNaN(speedKmh) || speedKmh < 0f) {
            return false;
        }
        if (ageMs > RoundSign.STALE_MS) {
            return false;
        }
        float threshold = clampThreshold(thresholdKmh);
        float limit = wasOver ? threshold - HYSTERESIS_KMH : threshold;
        return speedKmh > limit;
    }

    /** 閃動相位：true = 亮那一格（紅底白字）。從開始閃那一刻算起。 */
    public static boolean blinkPhase(long nowMs, long sinceMs) {
        long elapsed = nowMs - sinceMs;
        if (elapsed < 0L) {
            // 時鐘回撥：亮着比黑着安全（黑着看起來像沒在提示）
            return true;
        }
        return (elapsed / BLINK_HALF_PERIOD_MS) % 2L == 0L;
    }

    /**
     * 一個純狀態機：餵速度和時刻，它回答「要不要響、要不要閃」。
     *
     * <p>用户的開關（超速提示啟用與否）<b>不在這裏</b> —— 那是配置，
     * 由 Service 讀 {@code AppConfig} 決定要不要餵讀數給這個狀態機。</p>
     */
    public static final class State {

        private boolean over;
        private boolean beeped;
        private long lastBeepMs;
        private long sinceMs;

        /**
         * 餵一次讀數。
         *
         * @return 這一刻是否處於超速（也就是該不該擺出警示的樣子）
         */
        public boolean update(float speedKmh, long ageMs, float thresholdKmh, long nowMs) {
            boolean was = over;
            // 加類名：State 自己也有一個沒參數的版本，不寫清楚會解析錯
            over = SpeedAlert.isOver(speedKmh, ageMs, thresholdKmh, was);
            if (!over) {
                if (was) {
                    // 解除超速：下一輪要重新響第一聲
                    beeped = false;
                    lastBeepMs = 0L;
                }
                sinceMs = 0L;
                return false;
            }
            if (!was) {
                // 剛開始超速：相位從這裏起算，而且第一聲立刻響
                sinceMs = nowMs;
                beeped = false;
                lastBeepMs = 0L;
            }
            return true;
        }

        public boolean isOver() {
            return over;
        }

        /** 這一輪超速從哪一刻開始（閃動的相位基準）。 */
        public long alertSinceMs() {
            return sinceMs;
        }

        /**
         * 要不要響這一聲 beep。
         *
         * <p>剛超速時立刻響第一聲；之後只要還沒掉回門檻以下，
         * 每隔 {@code repeatMs}（設定頁可選）再響一聲。</p>
         *
         * @param repeatMs 重複間隔；壞值走 {@link #DEFAULT_REPEAT_MS}
         */
        public boolean takeBeep(long nowMs, long repeatMs) {
            if (!over) {
                return false;
            }
            if (!beeped) {
                beeped = true;
                lastBeepMs = nowMs;
                return true;
            }
            long gap = SpeedAlert.clampRepeatMs(repeatMs);
            if (nowMs - lastBeepMs >= gap) {
                lastBeepMs = nowMs;
                return true;
            }
            return false;
        }

        /** 現在該不該畫成「亮」那一格。 */
        public boolean blinkOn(long nowMs) {
            return over && SpeedAlert.blinkPhase(nowMs, sinceMs);
        }

        /** 車速牌關掉、服務停掉時清乾淨。 */
        public void reset() {
            over = false;
            beeped = false;
            lastBeepMs = 0L;
            sinceMs = 0L;
        }
    }
}
