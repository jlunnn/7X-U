package com.sevenxu.eq;

/**
 * 油門轉數燈帶要亮幾格、每格是甚麼顏色 —— F1 方向盤那排燈。
 *
 * <h3>為甚麼按位置定顏色，而不是按數值定</h3>
 *
 * <p>F1 方向盤上的燈是「越靠右越危險」：左邊綠、中間黃、右邊紅，最後一格紫。
 * 那是<b>燈的位置</b>決定的，跟當時讀數無關 —— 所以第 7 格在 50% 時是黃的，
 * 在 100% 時也是黃的。按數值決定顏色的話，同一格會隨讀數變色，看起來就不像燈帶了。</p>
 *
 * <h3>量程</h3>
 *
 * <p>油門深度 0x00101400 是浮點傳感器，實測「行駛中一直在變」，但<b>踩到底是幾多沒測過</b>
 * （見平台筆記「剎車 / 油門深度」）。所以這裏跟信息條一樣按 0–100 畫，
 * {@code Trust.PROVISIONAL}；真實範圍由診斷報告裏的 min / max 定案後再調。</p>
 *
 * <p>純邏輯，不碰 Android：燈數、顏色帶、範圍裁剪都可以在 JVM 上釘住。</p>
 */
public final class ThrottleBar {

    /** 默認燈數：F1 方向盤 10–15 顆，這裏取中間，一行看得清。 */
    public static final int SEGMENTS = 12;

    /** 綠：正常行駛用的一段。 */
    public static final int BAND_GREEN = 0;
    /** 黃：留意油門開始深。 */
    public static final int BAND_AMBER = 1;
    /** 紅：大油門。 */
    public static final int BAND_RED = 2;
    /** 紫：最後一段（F1 尾燈就是這色）。 */
    public static final int BAND_PURPLE = 3;
    /** 橙色（用戶指定：油門 70–85%）。 */
    public static final int BAND_ORANGE = 4;

    // ---- 牌面尺寸（跟 TempCard / RoundSign 一樣：比例都放在純邏輯裏，View 只負責畫）----

    /** 闊度 = 懸浮按鈕直徑嘅幾多倍（F1 燈帶係橫條）。 */
    public static final float WIDTH_RATIO = 3.2f;
    /** 高度比例。 */
    public static final float HEIGHT_RATIO = 0.3f;
    /** 罅隙佔每格嘅比例 —— 有罅隙才數得出「亮咗幾格」。 */
    public static final float GAP_RATIO = 0.18f;
    /** 圓角佔每格高度嘅比例。 */
    public static final float CORNER_RATIO = 0.3f;

    private ThrottleBar() {
    }

    public static float widthPx(float buttonSizePx) {
        return Math.max(48f, buttonSizePx * WIDTH_RATIO);
    }

    public static float heightPx(float buttonSizePx) {
        return Math.max(8f, buttonSizePx * HEIGHT_RATIO);
    }

    /** 每格「格位」闊度（含罅隙）。 */
    public static float slotWidthPx(int total, float widthPx) {
        return total <= 0 ? widthPx : widthPx / total;
    }

    public static float gapPx(int total, float widthPx) {
        if (total <= 0) {
            // 沒有格就沒有罅隙（也免得 widthPx/total 這裏先炸掉）
            return 0f;
        }
        return Math.max(0f, slotWidthPx(total, widthPx) * GAP_RATIO);
    }

    public static float segmentWidthPx(int total, float widthPx, float gapPx) {
        return Math.max(1f, slotWidthPx(total, widthPx) - gapPx);
    }

    /** 第 index 格嘅左邊界（罅隙兩邊各半）。 */
    public static float segmentLeftPx(int index, int total, float widthPx, float gapPx) {
        return index * slotWidthPx(total, widthPx) + gapPx / 2f;
    }

    public static float cornerPx(float heightPx) {
        return Math.max(0f, heightPx * CORNER_RATIO);
    }

    /**
     * 到這個顯示比例就算「踩滿」——到了這裏燈帶會閃（F1 換擋燈的做法）。
     *
     * <p>顯示比例本身已經按 {@link #FULL_SCALE_PERCENT} 歸一過，所以「踩滿」就是 100。</p>
     */
    public static final float AT_MAX_PERCENT = 100f;

    /** 滿油門閃動的半周期（毫秒）：亮 120 ms、滅 120 ms。 */
    public static final long BLINK_HALF_PERIOD_MS = 120L;

    /** 是不是踩滿了（按顯示比例算）。 */
    public static boolean isAtMax(float displayPercent) {
        return clampPercent(displayPercent) >= AT_MAX_PERCENT;
    }

    /** 滿油門閃動的相位：由「踩滿那一刻」起算，每 {@link #BLINK_HALF_PERIOD_MS} 翻一次。 */
    public static boolean blinkPhase(long nowMs, long sinceMs) {
        if (BLINK_HALF_PERIOD_MS <= 0) {
            return true;
        }
        long elapsed = nowMs - sinceMs;
        if (elapsed < 0) {
            elapsed = 0;
        }
        return (elapsed / BLINK_HALF_PERIOD_MS) % 2 == 0;
    }

    /** 收進 0–100；讀不到（NaN）當 0。 */
    public static float clampPercent(float percent) {
        if (Float.isNaN(percent)) {
            return 0f;
        }
        return Math.max(0f, Math.min(100f, percent));
    }

    /**
     * 亮幾格。
     *
     * <p>0% 全滅、100% 全亮；<b>有讀數但很少也至少亮一格</b> —— 否則輕輕踩着油門
     * 卻整排燈全滅，看起來像壞掉。</p>
     */
    public static int litSegments(float percent, int total) {
        if (total <= 0) {
            return 0;
        }
        float clamped = clampPercent(percent);
        if (clamped <= 0f) {
            return 0;
        }
        int lit = Math.round(clamped / 100f * total);
        return Math.max(1, Math.min(total, lit));
    }

    public static int litSegments(float percent) {
        return litSegments(percent, SEGMENTS);
    }

    /** 第 index 格（0 起）屬於哪條色帶 —— 看位置，不看讀數。 */
    public static int bandOf(int index, int total) {
        if (total <= 0) {
            return BAND_GREEN;
        }
        int i = Math.max(0, Math.min(total - 1, index));
        float fraction = (i + 1f) / total;      // 該段上緣
        // 用戶指定比例：綠 50% ／黃 20%（→70）／橙 15%（→85）／紅 10%（→95）／紫 5%
        if (fraction <= 0.50f) {
            return BAND_GREEN;
        }
        if (fraction <= 0.70f) {
            return BAND_AMBER;
        }
        if (fraction <= 0.85f) {
            return BAND_ORANGE;
        }
        if (fraction <= 0.95f) {
            return BAND_RED;
        }
        return BAND_PURPLE;
    }


    /**
     * 車機報到這個比例就算「踩滿」，燈帶隨之畫滿。
     *
     * <p>2026-10-06 用户指定：<b>85 % 當踩滿</b>。這條車機的油門讀數量程沒人量過
     * （信號表裏是 {@code Trust.PROVISIONAL}），實測踩到底只到 85 左右，
     * 所以按 85 歸一 —— 不然最後一段永遠不亮。真值由診斷 §1.4 的 min / max 説話，
     * 以後量到別的數字，改這一個常數就好。</p>
     */
    public static final float FULL_SCALE_PERCENT = 85f;

    /**
     // ------------------------------------------------------------------ 圓弧（用户 2026-10-08）

     /**
      * 用户 2026-10-08 改的設計：油門不再是一條橫燈帶，而是<b>畫在車速錶的上半圓</b>。
      *
      * <p>上半圓（左邊 9 點 → 12 點 → 右邊 3 點）＝ 油門，分成 {@link #SEGMENTS} 格，
      * 一格一格亮；未亮的部分只有一條灰色的底弧，<b>格與格之間沒有底盤</b>（用户要求「每一格背後是透明的」）。
      * 下半圓留給超速提示的開 / 關狀態（紅＝開、藍＝關）。</p>
      */
     public static final float ARC_HALF_SWEEP_DEGREES = 180f;

     /** 上半圓的起角：Android 的 drawArc 以 3 點鐘方向為 0°、順時針為正 → 180° 就是 9 點鐘。 */
     public static final float ARC_TOP_START_DEGREES = 180f;

     /** 第 {@code index} 格（0 = 最左）在上半圓的起角。 */
     public static float arcSegmentStartDegrees(int index, int total) {
         int count = Math.max(1, total);
         return ARC_TOP_START_DEGREES + Math.max(0, index) * (ARC_HALF_SWEEP_DEGREES / count);
     }

     /** 每一格的掃角。 */
     public static float arcSegmentSweepDegrees(int total) {
         return ARC_HALF_SWEEP_DEGREES / Math.max(1, total);
     }

     /**
      * 讀數可以是 {@code null}（車機還沒報、或者熄火）—— 這一版要能在那一刻不炸掉。
      *
      * <p>用户 2026-10-08 報「更新後要清一次資料才能用」：真正的原因是
      * {@code updateThrottleBar} 拿到 null 油門讀數之後<b>當場拆箱</b>（{@code Float -> float}），
      * NullPointerException 把前景服務炸掉；服務又會被拉起來、再炸一次。
      * 清資料之所以「有效」，只是把油門燈帶這個開關回復成預設的「關」，那條路就不再走。</p>
      *
      * @return 有讀數時回畫出來的百分比；沒有讀數回 0（配合 {@code available=false} 顯示暗條）
      */
     public static float safeDisplayPercent(Float raw) {
         if (raw == null || raw.isNaN()) {
             return 0f;
         }
         return displayPercent(percentFromRaw(raw));
     }

     /** 燈帶的顯示比例（大細）：設定頁可選，100 = 跟着按鈕尺寸走。
     *
     * <p>用户 2026-10-07 要求「顯示比例可以在設定頁選擇」——
     * 燈帶本身的尺寸是從按鈕尺寸算出來的（{@link #WIDTH_RATIO} 那幾個比例），
     * 所以「比例」就是再乘一個係數，不動按鈕。</p>
     */
    public static final int MIN_SCALE_PERCENT = 50;
    public static final int MAX_SCALE_PERCENT = 200;
    public static final int DEFAULT_SCALE_PERCENT = 100;

    /** 壞值／null 一律回默認 100%：夾到邊界會變成「用户沒選過卻變了樣」。 */
    public static int clampScalePercent(int percent) {
        if (percent < MIN_SCALE_PERCENT || percent > MAX_SCALE_PERCENT) {
            return DEFAULT_SCALE_PERCENT;
        }
        return percent;
    }

    /** 燈帶自己的基準尺寸：按鈕尺寸 × 顯示比例。 */
    public static int barSizePx(float buttonSizePx, int scalePercent) {
        float scaled = buttonSizePx * clampScalePercent(scalePercent) / 100f;
        return Math.max(1, Math.round(scaled));
    }

    /** 燈帶位置：按鈕右邊（默認，跟錄製時長同一行）。 */
    public static final String POSITION_RIGHT = "right";
    /** 燈帶位置：按鈕左邊（同一行的最左）。 */
    public static final String POSITION_LEFT = "left";
    /** 燈帶位置：按鈕上方（貼着按鈕）。 */
    public static final String POSITION_ABOVE = "above";
    /** 燈帶位置：按鈕下方（貼着按鈕）。 */
    public static final String POSITION_BELOW = "below";

    public static final String DEFAULT_POSITION = POSITION_RIGHT;

    /** 壞值／null 回默認位置（按鈕右邊）。 */
    public static String clampPosition(String position) {
        if (POSITION_LEFT.equals(position) || POSITION_ABOVE.equals(position)
                || POSITION_BELOW.equals(position)) {
            return position;
        }
        return DEFAULT_POSITION;
    }

    /** 上下兩個位置：燈帶要跟着按鈕那一列走，不是排在按鈕右邊。 */
    public static boolean isVertical(String position) {
        String p = clampPosition(position);
        return POSITION_ABOVE.equals(p) || POSITION_BELOW.equals(p);
    }

    /**
     * 車機報的油門原始值 → 0–100 %。
     *
     * <p>量程沒人量過（信號表裏是 {@code Trust.PROVISIONAL}）。2026-10-06 實車反饋：
     * 踩着油門時燈帶只亮一格綠色 —— 兩種可能：讀到的是百分之一 %（0–10000，除以 100 之後
     * 只剩 0.幾），或者讀到的是小數值。所以這裏按讀數判：</p>
     *
     * <ul>
     *   <li>{@code ≤ 100} → 當它報的就是百分比，原樣用；</li>
     *   <li>{@code > 100} → 當它報的是百分之一 %，除以 100。</li>
     * </ul>
     *
     * <p>這個判斷只影響「踩得很輕」那一小段；真實量程由診斷 §1.4 的 min / max 定案，
     * 定了就把這裏換成一個常數。</p>
     */
    public static float percentFromRaw(float raw) {
        if (Float.isNaN(raw)) {
            return 0f;
        }
        float value = raw <= 100f ? raw : raw / 100f;
        return clampPercent(value);
    }

    /**
     * 真正畫出來用嘅百分比：以 {@link #FULL_SCALE_PERCENT} 當滿，
     * 即 {@code received / 85 × 100}，超過就當滿（夾在 0–100）。
     */
    public static float displayPercent(float receivedPercent) {
        float received = clampPercent(receivedPercent);
        return clampPercent(received / FULL_SCALE_PERCENT * 100f);
    }
}
