package com.sevenxu.eq;

/**
 * 懸浮按鈕旁邊那些圓牌：一個純粹的算法盒子。
 *
 * <h3>它負責什麼</h3>
 *
 * <p>把讀數變成牌子上的文字，並且算出「這個文字放到這個圓裏，字號該多大、該擺在
 * 哪個位置」。全部是加減乘除，不碰 {@code Paint}、不碰 {@code View}，
 * 所以能在 JVM 上直接測（項目規矩：幾何和解碼寫成純函數，見 {@code HkPlateFormat}）。</p>
 *
 * <p>目前用它畫車速牌（{@link #speedText}）。温度那兩個數走 {@link TempCard}：
 * 它們要帶標籤（{@code 電池：30°}），圓形牌裏塞不下標籤。</p>
 *
 * <h3>為什麼要有「放得落」這條硬規矩</h3>
 *
 * <p>牌子長成國際限速標誌的樣子（白底圓 + 圈 + 黑數字），圈裏那圈白就是全部可用的
 * 地方。車速會從 {@code 0} 一直跳到三位數，如果只按「一兩位時的字號」畫，到了
 * {@code 120} 就會頂出圈。所以字號取兩者之中小的那個：上限
 * {@link #MAX_DIGIT_RATIO}，以及由下面的約束解出來的值。</p>
 *
 * <h3>約束怎麼算</h3>
 *
 * <p>文字是個矩形。矩形四個角落進圓裏，才算真的放得落 —— 圓最窄的地方就在角落。
 * 於是約束寫成：</p>
 *
 * <pre>    sqrt((w/2)² + (h/2)²) ≤ 內圈半徑 × SAFE_K</pre>
 *
 * <p>{@code w}、{@code h} 是文字墨水框的寬高，都隨字號線性變化，所以這個不等式
 * 有封閉解，不用一步步試：</p>
 *
 * <pre>    字號 ≤ 2 × 內圈半徑 × SAFE_K / sqrt(wₑ² + hₑ²)</pre>
 *
 * <h3>讀數過期</h3>
 *
 * <p>兩個來源都會安靜地停（GPS 進隧道、ECARX 在車機睡覺時），停了之後沒人會把舊值
 * 擦掉。所以「多久沒新讀數」在這裏判：超過 {@link #STALE_MS} 就畫
 * {@link #NO_READING}，寧可不顯示，也不要顯示一個不知道多舊的數字。</p>
 */
public final class RoundSign {

    /** 車速牌直徑 = 按鈕大小 × 這個比例。比按鈕略小一圈，看起來是同一組東西。 */
    /**
     * 圓牌直徑 ÷ 按鈕尺寸。
     *
     * <p>2026-10-08 用户：「可將車速的外框加大 20%」→ 由 0.90 加到 1.08（＝×1.2）。
     * 上面的油門弧要佔半個圈，圈細了格數就看不清。</p>
     */
    public static final float DIAMETER_RATIO = 1.08f;

    /** 圈粗 = 直徑 × 這個比例。真限速標誌約在 1/9–1/10 之間。 */
    public static final float RING_RATIO = 0.11f;

    /** 字號上限 = 直徑 × 這個比例。實際能不能用滿，由 {@link #SAFE_K} 決定。 */
    public static final float MAX_DIGIT_RATIO = 0.55f;

    /** 文字外框最多佔到內圈半徑的這個比例；剩下的全是白邊。 */
    public static final float SAFE_K = 0.85f;

    /**
     * 字和內圈之間最少留這個距離（px）。
     *
     * <p>用户 2026-10-08：「車速三位數自動收窄在圓框的內部 offset 1px」——
     * 三位數本來就會自動縮字（見 {@link #fitSizePx}），這裡再把可用的內圈半徑收 1 px，
     * 保證字的墨水框<b>不會貼着</b>圈的內邊。</p>
     */
    public static final float INNER_MARGIN_PX = 1f;

    /**
     * 圓牌與左右鄰居（按鈕、溫度牌）之間留的邊距 = 按鈕尺寸 × 這個比例。
     *
     * <p>用户 2026-10-08：「顯示溫度的懸浮窗遮擋了圓形的車速顯示」。
     * 圓牌直徑現在是按鈕的 1.404 倍（1.08 × 130%），原本 buttonSize/16 的邊距在圓邊那裏
     * 看起來就是貼住 —— 圓形視覺上比實際邊界「肥」，要留明顯一點的呼吸位。</p>
     */
    public static final float LABEL_GAP_RATIO = 0.11f;

    /** 圓牌與鄰居之間應留的邊距（px）。 */
    public static float labelGapPx(float buttonSizePx) {
        return Math.max(6f, buttonSizePx * LABEL_GAP_RATIO);
    }

    /** 畫字時真正可用的內圈半徑：內圈再收 {@link #INNER_MARGIN_PX}。 */
    public static float usableInnerRadiusPx(float diameterPx, float strokePx) {
        return Math.max(0f, innerRadiusPx(diameterPx, strokePx) - INNER_MARGIN_PX);
    }

    /** 沒有讀數（沒定位、沒信號、讀數過期）時畫的字。 */
    public static final String NO_READING = "--";

    /**
     * 讀數超過這個年齡就當作沒有：畫 {@link #NO_READING}。
     *
     * <p>5 秒的依據：GPS 大約 1 秒一次，ECARX 的浮點信號是輪詢（更快），
     * 所以正常運行時年齡不會超過兩三秒；5 秒足夠寬鬆到不會誤判，
     * 又短到不會讓一個假的數字在屏幕上停留太久。</p>
     */
    public static final long STALE_MS = 5000L;

    /** 車速讀數不可能到四位；真給了也按這個上限顯示，免得頂出圈。 */
    public static final int MAX_DISPLAY = 999;

    private RoundSign() {
    }

    /**
     * 車速度數 → 牌子上的字。
     *
     * <p>{@code null}、負數、NaN 都是「沒有讀數」，畫 {@link #NO_READING}。
     * 四捨五入到整數 —— 車速牌上不該出現小數。</p>
     */
    public static String speedText(Float speedKmh) {
        if (speedKmh == null || speedKmh.isNaN() || speedKmh < 0f) {
            return NO_READING;
        }
        int value = Math.round(speedKmh);
        if (value > MAX_DISPLAY) {
            value = MAX_DISPLAY;
        }
        return Integer.toString(value);
    }

    /**
     * 車速度數 ＋ 讀數年齡 → 牌子上的字。
     *
     * <p>超過 {@link #STALE_MS} 就當沒有，畫 {@link #NO_READING} ——
     * 寧可不顯示，也不要顯示一個不知道多舊的數字。邊界是閉的：剛滿還顯示。</p>
     */
    public static String speedText(Float speedKmh, long ageMs) {
        if (!fresh(ageMs)) {
            return NO_READING;
        }
        return speedText(speedKmh);
    }

    /** 有讀數（包括停車時的 0）就是 true；{@code --} 是 false。 */
    public static boolean hasReading(String text) {
        return !NO_READING.equals(text);
    }

    /** 這個年齡的讀數還算新鮮嗎（年齡為負 = 時鐘回撥，當作新鮮，不誤判）。 */
    public static boolean fresh(long ageMs) {
        return ageMs <= STALE_MS;
    }

    /** 牌子直徑。 */
    public static float diameterPx(float buttonSizePx) {
        return buttonSizePx * DIAMETER_RATIO;
    }

    /**
     * 用户 2026-10-08：車速圓牌原本「跟按鈕 × 0.90」，在車上看着不夠大 ——
     * 預設放大 30%（＝按鈕 1.17 倍），比例可在設定頁 50–250% 之間調。
     */
    public static final int DEFAULT_SCALE_PERCENT = 130;
    public static final int MIN_SCALE_PERCENT = 50;
    public static final int MAX_SCALE_PERCENT = 250;

    /** 壞值回默認 130%：夾到邊界會變成「用户沒選過卻變了樣」。 */
    public static int clampScalePercent(int percent) {
        if (percent < MIN_SCALE_PERCENT || percent > MAX_SCALE_PERCENT) {
            return DEFAULT_SCALE_PERCENT;
        }
        return percent;
    }

    /** 車速牌直徑 ＝ 按鈕尺寸 × {@link #DIAMETER_RATIO} × 顯示比例。 */
    public static float scaledDiameterPx(float buttonSizePx, int scalePercent) {
        return diameterPx(buttonSizePx) * clampScalePercent(scalePercent) / 100f;
    }

    /** 圈粗度。 */
    public static float ringStrokePx(float diameterPx) {
        return diameterPx * RING_RATIO;
    }

    /** 圈內緣半徑：所有可用空間都在這個圓裏。 */
    public static float innerRadiusPx(float diameterPx, float strokePx) {
        return diameterPx / 2f - strokePx;
    }

    /** 字號上限（還沒跟「放得落」約束比過）。 */
    public static float maxSizePx(float diameterPx) {
        return diameterPx * MAX_DIGIT_RATIO;
    }

    /**
     * 文字墨水框佔內圈半徑多少（0.85 是上限，≤1 就是沒頂到圈）。
     *
     * <p>給測試和設計核對用：0 位/1 位/2 位/3 位都應該 ≤ {@link #SAFE_K}。</p>
     */
    public static float boxRatio(float sizePx, float innerRadiusPx,
                                 float widthPerEm, float heightPerEm) {
        if (innerRadiusPx <= 0f) {
            return Float.POSITIVE_INFINITY;
        }
        float halfW = widthPerEm * sizePx / 2f;
        float halfH = heightPerEm * sizePx / 2f;
        return (float) (Math.hypot(halfW, halfH) / innerRadiusPx);
    }

    /**
     * 放得落的最大字號（封閉解）。
     *
     * @param innerRadiusPx 內圈半徑
     * @param widthPerEm    每 em 的文字墨水寬度（量一次傳進來）
     * @param heightPerEm   每 em 的文字墨水高度
     * @param maxSizePx     字號上限（{@link #MAX_DIGIT_RATIO} 算出來那個）
     * @param safeK         文字外框最多佔內圈半徑的比例（{@link #SAFE_K}）
     * @return 實際用的字號；參數不成立時回 0
     */
    public static float fitSizePx(float innerRadiusPx, float widthPerEm, float heightPerEm,
                                  float maxSizePx, float safeK) {
        if (innerRadiusPx <= 0f || maxSizePx <= 0f || safeK <= 0f) {
            return 0f;
        }
        double diag = Math.hypot(widthPerEm, heightPerEm);
        if (diag <= 0d) {
            return 0f;
        }
        float limit = (float) (2d * innerRadiusPx * safeK / diag);
        return Math.max(1f, Math.min(maxSizePx, limit));
    }

    /**
     * 置中：把文字墨水框的中心放到給定位置，需要偏移多少。
     *
     * <p>為什麼不是直接用字體度量置中：數字沒有下伸部（descender），按
     * ascent/descent 的幾何中點擺，墨水框會落在中線下方約 7.6% 字號的位置 ——
     * 看久了就是「數字有點偏下」。所以置中要用墨水框自己的上下邊界。</p>
     *
     * @param inkLow  墨水框在該軸上的最小坐標（相對文字原點）
     * @param inkHigh 墨水框在該軸上的最大坐標
     */
    public static float centerOffsetPx(float inkLow, float inkHigh) {
        return -(inkLow + inkHigh) / 2f;
    }
}
