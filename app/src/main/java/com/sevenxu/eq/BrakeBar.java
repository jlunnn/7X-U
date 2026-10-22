package com.sevenxu.eq;

/**
 * 腳煞燈（F1 車尾燈那種）：填幾長、什麼色、幾時閃。
 *
 * <h3>用户 2026-10-08 的要求</h3>
 *
 * <ul>
 *   <li><b>0%：無框、透明</b> —— 整條完全不畫（之前那個「最深灰外框」撤走）；</li>
 *   <li><b>1–25%：紅 → 深紅</b>（踩得越深越深色）；</li>
 *   <li><b>最大 %：紅色閃燈</b>（踩到底就一直閃，像 F1 車尾燈）；</li>
 *   <li><b>一踩落去：閃一兩下</b>（「放 brake 都會閃動一兩吓，好似 F1 賽車咁」）。</li>
 * </ul>
 *
 * <p>純邏輯：顏色、透明度、閃動時序都在 JVM 上釘住。</p>
 */
public final class BrakeBar {

    private BrakeBar() {
    }

    /** 闊 : 高 = 9 : 1（用户指定）。 */
    public static final float WIDTH_HEIGHT_RATIO = 9f;

    /**
     * 向下梯形：底邊每邊縮入闊度的這個比例。
     *
     * <p>用户 2026-10-08：「改為向下的梯型設計」—— 上邊保持同車速錶直徑一樣闊，
     * 下邊收窄，看起來像燈條向下收。</p>
     */
    public static final float BOTTOM_INSET_RATIO = 0.12f;

    /** 向內漸變最淡的位置（中間）剩幾多。 */
    public static final float FADE_MIN = 0.35f;

    /** 底邊左右各縮入幾多 px。 */
    public static float bottomInsetPx(float widthPx) {
        return widthPx * BOTTOM_INSET_RATIO;
    }

    /** 梯形在高度 {@code fraction}（0 = 上、1 = 下）處，左右各縮入幾多 px。 */
    public static float insetAtHeightFraction(float widthPx, float fraction) {
        float f = fraction < 0f ? 0f : (fraction > 1f ? 1f : fraction);
        return bottomInsetPx(widthPx) * f;
    }

    /**
     * 向內漸變的倍率：<b>兩端最實、中間最淡</b>（用户：「有向內漸變效果」）。
     *
     * @param fraction 由左到右 0–1
     * @return {@link #FADE_MIN}（中間）到 1（兩端）
     */
    public static float inwardFadeAt(float fraction) {
        float f = fraction < 0f ? 0f : (fraction > 1f ? 1f : fraction);
        // 端點距 = 1、正中間 = 0
        float distanceFromEnd = Math.abs(2f * f - 1f);
        return FADE_MIN + (1f - FADE_MIN) * distanceFromEnd;
    }

    /** 把一個顏色的透明度乘上漸變倍率。 */
    public static int withFade(int color, float factor) {
        float f = factor < 0f ? 0f : (factor > 1f ? 1f : factor);
        return withAlpha(color, Math.round(alphaOf(color) * f));
    }

    /** 梯形頂邊 ＝ 車速錶直徑（對齊用）。 */
    public static float topWidthPx(float signDiameterPx) {
        return Math.max(1f, signDiameterPx);
    }

    /** 讀數的上限（超過當滿）。 */
    public static final float MAX_PERCENT = 100f;

    /** 1–25% 由紅走到深紅；25% 之後一路深紅，直到踩滿才轉閃燈。 */
    public static final float DEEP_RED_PERCENT = 25f;

    /** 到這個百分比就當「踩到底」（閃燈）。門檻不放 100：實車未必踩得到滿值。 */
    public static final float AT_MAX_PERCENT = 90f;

    /** 紅（剛踩）。 */
    public static final int COLOR_RED = 0xFFFF3B30;

    /** 深紅（踩到 25% 以上）。 */
    public static final int COLOR_DEEP_RED = 0xFF8B0000;

    /** 閃燈的半周期：亮 120 ms、滅 120 ms。 */
    public static final long FLASH_HALF_PERIOD_MS = 120L;

    /** 剛踩落去那個提示閃幾多個半相位：4 = 亮滅亮滅 = 兩下。 */
    public static final int BLIP_HALF_PHASES = 4;

    /**
     * 讀數 {@code null}（車機還沒報、或者熄火）時回 0，<b>不要拆箱</b>。
     *
     * <p>油門那邊就是這一條把前景服務炸掉過（實車 2026-10-08）。煞車信號同樣是
     * {@code Float}，同一個坑不能再踩一次。</p>
     */
    public static float safePercent(Float raw) {
        if (raw == null || raw.isNaN() || raw < 0f) {
            return 0f;
        }
        return clampPercent(raw);
    }

    public static float clampPercent(float percent) {
        if (Float.isNaN(percent) || percent < 0f) {
            return 0f;
        }
        return Math.min(percent, MAX_PERCENT);
    }

    /** 到「踩到底」沒有（閃燈的判據）。 */
    public static boolean isAtMax(float percent) {
        return clampPercent(percent) >= AT_MAX_PERCENT;
    }

    /** 輕踩時的最低亮度（46/255 ≈ 18%）—— 再暗就睇唔到，再亮就唔似「輕踩」。 */
    public static final int MIN_VISIBLE_ALPHA = 46;

    /**
     * 亮度（透明度）：0% 完全透明，1% → 25% 由 {@link #MIN_VISIBLE_ALPHA} 升到全亮，
     * 25% 之後一直全亮。
     *
     * <p>用户 2026-10-08 看過之後選的：<b>輕踩較暗、越深越亮</b>（1% 開始，到 25% 升到全亮）。
     * 之前 1% 和 25% 一樣亮，只有顏色深淺之別 —— 車上遠處看，亮度比色相容易分。</p>
     */
    public static int alphaFor(float percent) {
        float pct = clampPercent(percent);
        if (pct <= 0f) {
            return 0;
        }
        float t = pct / DEEP_RED_PERCENT;
        if (t > 1f) {
            t = 1f;
        }
        return Math.round(MIN_VISIBLE_ALPHA + (255f - MIN_VISIBLE_ALPHA) * t);
    }

    /** 這一刻畫不畫（0% 完全透明 → 不畫）。 */
    public static boolean drawsAnything(float percent) {
        return alphaFor(percent) > 0;
    }

    /** 現時的燈色（含透明度）：紅 → 深紅，25% 之後一路深紅。 */
    public static int colorFor(float percent) {
        float pct = clampPercent(percent);
        if (pct <= 0f) {
            return withAlpha(COLOR_DEEP_RED, 0);
        }
        float t = pct / DEEP_RED_PERCENT;
        if (t > 1f) {
            t = 1f;
        }
        // 顏色深淺 ＋ 亮度都跟着踏板走（亮度由 alphaFor 給）
        return withAlpha(lerpColor(COLOR_RED, COLOR_DEEP_RED, t), alphaFor(pct));
    }

    /** 兩個 ARGB 之間取比例 {@code t}（0 = from、1 = to）。 */
    public static int lerpColor(int from, int to, float t) {
        float k = t < 0f ? 0f : (t > 1f ? 1f : t);
        int a = Math.round(alphaOf(from) + (alphaOf(to) - alphaOf(from)) * k);
        int r = Math.round(redOf(from) + (redOf(to) - redOf(from)) * k);
        int g = Math.round(greenOf(from) + (greenOf(to) - greenOf(from)) * k);
        int b = Math.round(blueOf(from) + (blueOf(to) - blueOf(from)) * k);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public static int withAlpha(int color, int alpha) {
        int a = alpha < 0 ? 0 : (alpha > 255 ? 255 : alpha);
        return (a << 24) | (color & 0x00FFFFFF);
    }

    public static int alphaOf(int color) {
        return (color >> 24) & 0xFF;
    }

    public static int redOf(int color) {
        return (color >> 16) & 0xFF;
    }

    public static int greenOf(int color) {
        return (color >> 8) & 0xFF;
    }

    public static int blueOf(int color) {
        return color & 0xFF;
    }

    /** 長方形的高度（闊度由調用方給，車速錶直徑）。 */
    /**
     * 車機報的剎車深度「滿量程」＝ 20%。超過就當踩到底。
     *
     * <p>用户 2026-10-08 實測（Hermes probe）：「腳煞收到的訊號係 0%~30% maximum，
     * 你自己按比例顯示出來，即大概到 20% 已經可以當係 maximum」。</p>
     *
     * <p>之前所有刻度（1%→25% 由紅轉深紅、25% 全亮、90% 起閃燈）都當輸入是 0–100，
     * 於是輕輕一踩（3–5%）畫出來幾乎透明，看起來就像「紅燈冇顯示」。
     * 現在先換算：原始 20% 以上都當滿，3% 已經等於顯示 15%。</p>
     */
    public static final float RAW_MAX_PERCENT = 20f;

    /**
     * 把車機的原始深度（0–30%）換算成顯示百分比（0–100）。
     *
     * <p>純算術：這樣「輕踩看得見、踩到底閃燈」的設計才對得上真車。</p>
     */
    public static float toDisplayPercent(Float rawPercent) {
        if (rawPercent == null || Float.isNaN(rawPercent) || rawPercent <= 0f) {
            return 0f;
        }
        float scaled = rawPercent / RAW_MAX_PERCENT * MAX_PERCENT;
        return Math.min(scaled, MAX_PERCENT);
    }

    /**
     * 腳煞條<b>中心</b>顯示的文字。
     *
     * <p>用户 2026-10-08：「還是沒有紅色顯示，請在腳煞條中心顯示百分比數值」——
     * 這個數字同時是診斷工具：車上直接看得出車機到底有沒有報訊號。</p>
     *
     * <ul>
     *   <li>有得畫 → 顯示換算後的百分比（例如原始 3% → {@code 10%}）</li>
     *   <li>顯示值是 0，但車機<b>有</b>報深度 → {@code 0%}（有報，只是沒踩）</li>
     *   <li>車機<b>完全沒</b>報 → {@code --}（這樣才分得出是「沒踩」還是「收不到」）</li>
     * </ul>
     */
    public static String labelText(Float rawPercent, float displayPercent) {
        if (displayPercent > 0f) {
            return Math.round(clampPercent(displayPercent)) + "%";
        }
        if (rawPercent != null && !Float.isNaN(rawPercent)) {
            return "0%";
        }
        return com.sevenxu.eq.RoundSign.NO_READING;
    }

    /**
     * 深度讀數讀不到、但剎車燈開關說「踩住」時，畫這個中段值。
     *
     * <p>用户 2026-10-08：「腳煞的紅燈還沒有顯示出來」。深度信號
     * （{@code BRAKE_DEPTH 0x00101300}）只是<b>暫定</b>（PROVISIONAL）—— 車機沒報，
     * 讀數就是 null，燈自然不亮。剎車燈開關（{@code BRAKE_PEDAL 0x20317A00}）是
     * <b>已確認</b>的信號，所以退到它：只要有踩，就畫一段明顯的深紅。</p>
     *
     * <p>45% 是有意的：顏色 1%→25% 由紅轉深紅，45% 已經是全亮深紅；
     * 又未到 90% 的閃燈門檻 —— 開關只有 on/off，不該假裝踩到底。</p>
     */
    public static final float SWITCH_FALLBACK_PERCENT = 45f;

    /**
     * 腳煞該畫幾多：深度讀數優先（先換算成 0–100），讀不到就退到剎車燈開關。
     *
     * @param depthPercent   車機原始深度 0–30（可能 null —— 車機沒報）
     * @param switchPressed  剎車燈開關（{@code TRUE} = 踩住）
     */
    public static float effectivePercent(Float depthPercent, Boolean switchPressed) {
        if (depthPercent != null && !Float.isNaN(depthPercent) && depthPercent > 0f) {
            return toDisplayPercent(depthPercent);
        }
        return Boolean.TRUE.equals(switchPressed) ? SWITCH_FALLBACK_PERCENT : 0f;
    }

    /**
     * 測試掃描的百分比：{@code 0 → 100 → 0}（走 {@code steps} 格上去、同樣格數落返）。
     *
     * <p>掃描掣要「一眼看得出紅色畫不畫得出來」，而畫的規矩（顏色／透明度／閃燈）
     * 全部在這裏 —— 所以連時序都寫成純函數，測試釘住頭尾與單調性。</p>
     */
    public static float sweepPercent(int step, int steps) {
        if (steps <= 0) {
            return 0f;
        }
        int s = step < 0 ? 0 : step;
        if (s >= steps * 2) {
            return 0f;
        }
        if (s <= steps) {
            return (float) s / steps * MAX_PERCENT;
        }
        return (float) (steps * 2 - s) / steps * MAX_PERCENT;
    }

    /**
     * 車速圓牌那一列要補幾多<b>上方內邊距</b>，圓心才會同溫度牌的水平中心同一條線。
     *
     * <p>用户 2026-10-08：「水平與車速的中心點與溫度懸浮窗水平中心同一水平上」。
     * 那一列的內容是「圓牌 ＋ 腳煞條」—— 圓心在上面，列的中央在兩者之間。</p>
     *
     * <p>算式：列高 = 上內邊距 ＋ 直徑 ＋ 間距 ＋ 腳煞高；圓心 = 上內邊距 ＋ 直徑 ÷ 2。
     * 要兩者相等，上內邊距就要等於 <b>腳煞高 ＋ 間距</b>（不是一半 —— 因為補上去的
     * 內邊距同時把列拉高了，兩邊各算一次）。</p>
     */
    public static float columnTopPaddingPx(float signSizePx, float gapPx) {
        return heightPx(signSizePx) + Math.max(0f, gapPx);
    }

    public static float heightPx(float widthPx) {
        return Math.max(1f, widthPx / WIDTH_HEIGHT_RATIO);
    }

    /**
     * 尾燈的閃動狀態機。
     *
     * <p>三種情況：沒踩（不閃）、剛踩落去（閃兩下就定住）、踩到底（一直閃）。
     * 把時序抽出來是因為「閃幾下就停」最容易寫錯 —— 用測試釘住比在 Service 裏數
     * 一個 counter 可靠。</p>
     */
    public static final class Lamp {

        private boolean pressed;
        private boolean atMax;
        private int blipHalfPhases;

        /**
         * 餵一次讀數。
         *
         * @return 這一刻要不要閃
         */
        public boolean update(float percent) {
            float pct = clampPercent(percent);
            boolean nowPressed = pct > 0f;
            // 加類名：Lamp 自己也有一個沒參數的 isAtMax()，不寫清楚會解析到那個（同 SpeedAlert 一樣的坑）
            boolean nowAtMax = BrakeBar.isAtMax(pct);

            if (nowAtMax) {
                // 踩到滿：直接轉持續閃，不必先把「兩下」播完
                blipHalfPhases = 0;
            } else if (nowPressed && !pressed) {
                // 剛踩落去：閃兩下
                blipHalfPhases = BLIP_HALF_PHASES;
            }
            pressed = nowPressed;
            atMax = nowAtMax;
            return shouldFlash();
        }

        /** 這一刻要不要閃（踩滿、或者還有「兩下」沒播完）。 */
        public boolean shouldFlash() {
            return atMax || blipHalfPhases > 0;
        }

        /** 是不是踩到底（決定燈色用亮紅還是深紅）。 */
        public boolean isAtMax() {
            return atMax;
        }

        /** 閃過半個相位；由重畫那條 Runnable 叫。 */
        public void takeHalfPhase() {
            if (blipHalfPhases > 0) {
                blipHalfPhases--;
            }
        }

        /** 收手（車速牌關掉、服務停掉）。 */
        public void reset() {
            pressed = false;
            atMax = false;
            blipHalfPhases = 0;
        }
    }
}
