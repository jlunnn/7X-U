package com.sevenxu.eq;

/**
 * 懸浮按鈕旁的温度牌：兩行文字 {@code 電池：30°} / {@code 車外：28°}，方形圓角。
 *
 * <h3>為什麼不是第二個圓牌</h3>
 *
 * <p>圓牌只有一個數，兩個温度就要兩張牌，行車中一眼要分辨「哪個圓是哪個温度」——
 * 而它們形狀一樣、只有大小之分。寫成兩行文字，標籤直接寫在數字旁邊，
 * 不用猜、不用記位置。</p>
 *
 * <h3>它是純的</h3>
 *
 * <p>跟 {@link RoundSign} 一樣：只有加減乘除和字符串拼接，不碰 {@code Paint}、
 * 不碰 {@code View}。標籤文字由外面傳進來（Android 那邊是 {@code strings.xml}，
 * 中英馬三份），所以這裏能在 JVM 上直接測。</p>
 */
public final class TempCard {

    /** 字號 = 按鈕大小 × 這個比例（92 dp 的按鈕 → 約 15.6 sp，跟錄影時長那行差不多大）。 */
    public static final float FONT_RATIO = 0.17f;

    /** 圓角半徑 = 按鈕大小 × 這個比例。方牌，但不是尖角 —— 尖角在車機上看起來像沒做完。 */
    public static final float CORNER_RATIO = 0.09f;

    /** 行距 = 字號 × 這個比例。兩行之間留一點縫，但不要寬到像兩段。 */
    public static final float LINE_GAP_RATIO = 0.25f;

    /**
     * 温度的合理範圍。外的值當作解碼出錯，畫 {@link #NO_READING}。
     *
     * <p>下限取 −60：車外温度在寒區會到 −30 左右，−60 已經寬到不會誤殺。
     * 上限取 150：電池再熱也不會到；真到了，那是車的問題，不是牌子該顯示的讀數。</p>
     */
    public static final float MIN_CELSIUS = -60f;
    public static final float MAX_CELSIUS = 150f;

    /** 沒有讀數／讀數過期時畫的字，跟圓牌同一套。 */
    public static final String NO_READING = RoundSign.NO_READING;

    private TempCard() {
    }

    /**
     * Info Bar 檔位那個字母的字號 ÷ 車速數字的字號。
     *
     * <p>用户 2026-10-11：「將 Info Bar 的檔位文字放大，與車速數值比它小 20%，
     * 自動跟隨而調節 size」—— 所以檔位不是用卡片自己那套字號，而是**跟着車速數字走**：
     * 車速牌調大／調小、車速數字因位數而縮，檔位就按同一個比例跟着變。</p>
     */
    public static final float GEAR_SIZE_RATIO = 0.8f;

    /**
     * 檔位字母的字號（＝車速數字字號 × {@link #GEAR_SIZE_RATIO}）。
     *
     * <p>車速那個字號還沒量到（牌子未畫過、或者牌子關掉了）時回 0 —— 調用方要
     * 保留自己原本的字號，不要把檔位縮成 0。</p>
     */
    public static float gearFontPx(float speedNumberSizePx) {
        if (speedNumberSizePx <= 0f || Float.isNaN(speedNumberSizePx)) {
            return 0f;
        }
        return speedNumberSizePx * GEAR_SIZE_RATIO;
    }

    /**
     * 兩支字號共用一條基線時，卡片要預留的最上高度。
     *
     * <p>ascent 在 Android 是負數（越大支越負），所以要取<b>更負</b>那個 —— 否則
     * 大字會被卡片頂框削掉（用户 2026-10-11：「P 字被削頂」）。</p>
     */
    public static float sharedAscentPx(float firstAscentPx, float secondAscentPx) {
        return Math.min(firstAscentPx, secondAscentPx);
    }

    /** 共用基線下的行高：以兩支之中較高的那支為準（高度＝descent − ascent）。 */
    public static float sharedLineHeightPx(float firstAscentPx, float firstDescentPx,
                                           float secondAscentPx, float secondDescentPx) {
        return Math.max(firstDescentPx - firstAscentPx, secondDescentPx - secondAscentPx);
    }

    /**
     * 檔位外框（用户 2026-10-11：「檔位加外框設計」）。
     *
     * <p>Info Bar 整個已經改成全透明，所以檔位那個字母用一個<b>線框</b>去框住它
     * —— 框的左右分別是「檔位欄的左邊／右邊」再各讓 {@link #GEAR_FRAME_MARGIN_RATIO}
     * 的呼吸位，上下由卡片高度決定（畫圖那裏用同一個值）。</p>
     */
    public static final float GEAR_FRAME_MARGIN_RATIO = 0.10f;

    /** 檔位框與文字之間最少的呼吸位（用户 2026-10-12：「確保外框包裹著檔位的文字」）。 */
    public static final float MIN_GEAR_FRAME_MARGIN_PX = 2f;

    /**
     * 檔位框的呼吸位：{@code max(2px, 內邊距 × 比例)}。
     *
     * <p>框要包住字母，所以呼吸位不可以細過 2px —— 用户 2026-10-12 見到 {@code D}
     * 明明在框內但貼邊（加埋左對齊的 bug 就索性畫出框外）。</p>
     */
    public static float gearFrameMarginPx(float paddingPx) {
        return Math.max(MIN_GEAR_FRAME_MARGIN_PX,
                Math.max(0f, paddingPx) * GEAR_FRAME_MARGIN_RATIO);
    }

    /**
     * 外框線的闊度（實際值由按鈕大小推）。
     *
     * <p>用户 2026-10-11：「將檔位框加粗」—— 由 0.012 加到 0.024（≈ 加倍）。</p>
     */
    public static final float GEAR_FRAME_STROKE_RATIO = 0.040f;

    /** 檔位框與右邊資料之間最少要留幾多 px（用户 2026-10-11：保持間距 2px）。 */
    public static final float MIN_GAP_FROM_GEAR_FRAME_PX = 2f;

    /**
     * 內容（字母 ＋ 標籤）在框內**垂直置中**時，它的上邊在哪裡。
     *
     * <p>用户 2026-10-11：「Gear 文字和檔位是否放在框內的中心點？」—— 之前是貼着
     * 卡片上內邊距畫，框比內容高的時候就看着偏上。現在由框的上下邊算中心。</p>
     */
    public static float centredContentTopPx(float frameTopPx, float frameBottomPx,
                                            float contentHeightPx) {
        float frameHeight = frameBottomPx - frameTopPx;
        if (contentHeightPx >= frameHeight) {
            return frameTopPx;
        }
        return frameTopPx + (frameHeight - contentHeightPx) / 2f;
    }

    /**
     * 右邊資料（DriveMode 那幾行）的左邊應該由哪裡開始。
     *
     * <p>用户 2026-10-11：「確保沒與檔位重疊，保持間距 2px」—— 取「原本那個位置」與
     * 「檔位框右邊 ＋ {@link #MIN_GAP_FROM_GEAR_FRAME_PX}」兩者較右那個，所以無論
     * 框加粗幾多、按鈕大小幾多，都一定唔會疊住檔位。</p>
     */
    public static float rowsLeftPx(float[] columnWidths, float paddingPx) {
        float pad = Math.max(0f, paddingPx);
        float original = pad + (columnWidths == null || columnWidths.length == 0
                ? 0f : Math.max(0f, columnWidths[0]))
                + 2f * DIVIDER_MARGIN_PX + DIVIDER_WIDTH_PX;
        float afterFrame = gearFrameXPx(columnWidths, pad)[1] + MIN_GAP_FROM_GEAR_FRAME_PX;
        return Math.max(original, afterFrame);
    }

    /**
     * 要畫在哪個 x，墨水的中心才會落在 {@code centrePx}。
     *
     * <p>用户 2026-10-12：「檔位顯示在框的左右上下正中心位置」—— {@code D} 這類字的墨水
     * 左右不對稱（左邊承白比右邊闊），只把<b>字寬</b>置中的話，墨水會偏一邊、貼到框線。</p>
     *
     * <p>{@code Paint.Align.CENTER} 之下，畫在 {@code x} 等於把「字寬」的中心放在 {@code x}，
     * 即字的起點在 {@code x − advance/2}，墨心就落在 {@code x − advance/2 + (inkLeft+inkRight)/2}。
     * 要令墨心 ＝ {@code centrePx}，就要畫在 {@code centrePx + advance/2 − (inkLeft+inkRight)/2}。</p>
     */
    public static float inkCentredXPx(float centrePx, float advancePx, float inkLeftPx,
                                      float inkRightPx) {
        return centrePx + advancePx / 2f - (inkLeftPx + inkRightPx) / 2f;
    }

    /** 「Gear」標籤離框頂幾多 px（用户 2026-10-11：「Gear 放在頂部的框內中間位」）。 */
    public static final float GEAR_LABEL_TOP_INSET_PX = 3f;

    /**
     * Info Bar 右邊那堆資料（DriveMode／Regen／溫度）底下的浮板顏色。
     *
     * <p>用户 2026-10-11：先要 95%，睇過之後改成「右邊資料改 5% 透明」——
     * 即係只有 5% 不透明（alpha ≈ 13/255），浮板幾乎睇唔到，數字照樣清楚。</p>
     */
    public static final int ROWS_BACKGROUND_ARGB = 0x0DF0F0F0;

    /** 右邊浮板由第一行資料左邊再讓幾多 px（唔好貼到字）。 */
    public static final float ROWS_BACKGROUND_PAD_PX = 6f;

    /**
     * 右邊浮板的左邊。
     *
     * <p>取「資料左邊再讓 {@link #ROWS_BACKGROUND_PAD_PX}」與「檔位框右邊 ＋ 最少
     * 間距」兩者較右那個 —— 所以浮板永遠唔會壓到檔位框（用户：「確保沒與檔位重疊」）。</p>
     */
    public static float rowsBackgroundLeftPx(float[] columnWidths, float paddingPx) {
        float wanted = rowsLeftPx(columnWidths, paddingPx) - ROWS_BACKGROUND_PAD_PX;
        float afterFrame = gearFrameXPx(columnWidths, paddingPx)[1]
                + MIN_GAP_FROM_GEAR_FRAME_PX;
        return Math.max(wanted, afterFrame);
    }

    /** 外框左右兩條邊的 x：{@code [left, right]}。 */
    public static float[] gearFrameXPx(float[] columnWidths, float paddingPx) {
        if (columnWidths == null || columnWidths.length == 0) {
            return new float[]{0f, 0f};
        }
        float pad = Math.max(0f, paddingPx);
        float margin = gearFrameMarginPx(pad);
        return new float[]{pad - margin, pad + Math.max(0f, columnWidths[0]) + margin};
    }

    // ---------------------------------------------- 橫行（用户 2026-10-11 第二版）

    /**
     * Info Bar 右邊改成<b>橫行</b>：每一項一行，標籤在左、數值在右。
     *
     * <pre>   [P]   DriveMode  Comfort
     *         Regen      Strong
     *         Cabin 22.0°  Battery 30.0°</pre>
     *
     * <p>用户 2026-10-11：「DriveMode、Regen、Temperature 用橫行設計顯示」，同時文字
     * 一律用英文。檔位保持原本那個大字母 ＋ 外框（用戶另外指定）。</p>
     */
    public static final float ROW_GAP_RATIO = 0.35f;

    /** 標籤與數值之間的距離 ÷ 字號。 */
    public static final float ROW_LABEL_VALUE_GAP_RATIO = 0.5f;

    /** 一行裡兩組（例如 Cabin 22.0° 與 Battery 30.0°）之間的距離 ÷ 字號。 */
    public static final float ROW_PAIR_GAP_RATIO = 1.1f;

    /** 一行的闊度 ＝ 標籤闊 ＋ 間距 ＋ 數值闊。 */
    public static float rowWidthPx(float labelWidthPx, float valueWidthPx, float gapPx) {
        return Math.max(0f, labelWidthPx) + Math.max(0f, gapPx) + Math.max(0f, valueWidthPx);
    }

    /** 數值在那一行裡的 x（＝標籤之後）。 */
    public static float rowValueXPx(float labelWidthPx, float gapPx) {
        return Math.max(0f, labelWidthPx) + Math.max(0f, gapPx);
    }

    /** 標籤與數值之間最少的距離（用户 2026-10-12：「自動保持 1px 間距」）。 */
    public static final float MIN_LABEL_VALUE_GAP_PX = 1f;

    /**
     * 一行裡面「數值」可以用的闊度：欄位闊度減去標籤闊度，再減最少間距。
     *
     * <p>數值右對齊到欄位右邊之後，就不用怕它撞到右邊那一組（用户 2026-10-12：
     * 「溫度顯示的顯示位置不要 overlap」）。</p>
     */
    public static float valueFieldWidthPx(float fieldWidthPx, float labelWidthPx) {
        return Math.max(0f, Math.max(0f, fieldWidthPx) - Math.max(0f, labelWidthPx)
                - MIN_LABEL_VALUE_GAP_PX);
    }

    /** 右對齊時數值要由哪個 x 開始畫。 */
    public static float rightAlignedXPx(float fieldRightPx, float valueWidthPx) {
        return fieldRightPx - Math.max(0f, valueWidthPx);
    }

    /** 標籤與數值之間的距離。 */
    public static float rowLabelValueGapPx(float fontSizePx) {
        return Math.max(2f, fontSizePx * ROW_LABEL_VALUE_GAP_RATIO);
    }

    /** 一行裡兩組之間的距離。 */
    public static float rowPairGapPx(float fontSizePx) {
        return Math.max(4f, fontSizePx * ROW_PAIR_GAP_RATIO);
    }

    /** 行與行之間的距離。 */
    public static float rowGapPx(float fontSizePx) {
        return Math.max(1f, fontSizePx * ROW_GAP_RATIO);
    }

    /** n 行連行距的總高度。 */
    public static float rowsHeightPx(float rowLineHeightPx, float rowGapPx, int rows) {
        if (rows <= 0) {
            return 0f;
        }
        float line = Math.max(0f, rowLineHeightPx);
        float gap = Math.max(0f, rowGapPx);
        return rows * line + (rows - 1) * gap;
    }

    /**
     * 量闊度用的溫度樣本。
     *
     * <p>用户 2026-10-08 嫌牌太闊：「不要留下太多空間」—— 樣本由 {@code -888.8°}
     * 收窄到 {@code -88.8°}（仍然覆蓋負號，香港不會有 888 度）。</p>
     */
    public static final String WIDEST_TEMP_SAMPLE = "-88.8°";

    /** 字號。 */
    public static float fontSizePx(float buttonSizePx) {
        return buttonSizePx * FONT_RATIO;
    }

    /** 圓角半徑。 */
    public static float cornerPx(float buttonSizePx) {
        return Math.max(4f, buttonSizePx * CORNER_RATIO);
    }

    /** 牌內邊距。跟錄影時長那張卡同一條規矩（{@code max(8, 按鈕/8)}）。 */
    public static float paddingPx(float buttonSizePx) {
        return Math.max(8f, buttonSizePx / 8f);
    }

    /** 兩行之間的距離。 */
    public static float lineGapPx(float fontSizePx) {
        return fontSizePx * LINE_GAP_RATIO;
    }

    // ------------------------------------- 牌面（用户 2026-10-08：上温度、下文字）

    /** 下部文字（Cabin / Battery）的字號 ÷ 上部溫度的字號。 */
    public static final float LABEL_FONT_RATIO = 0.62f;

    /**
     * 兩欄文字與中間那條分界線之間留幾多（每邊）。
     *
     * <p>用户 2026-10-08：「溫度窗不要留下太多空間，把它們緊貼，中間留一條淺色分界線」——
     * 這條就是「緊貼但有分界」的那個距離：文字不貼到線，線也不貼到文字。</p>
     */
    public static final float DIVIDER_MARGIN_PX = 6f;

    /** 分界線本身的闊度。 */
    public static final float DIVIDER_WIDTH_PX = 1f;

    /** 分界線的上下端（佔牌內內容高度的比例）—— 不頂到上下邊，看起來輕一點。 */
    public static final float DIVIDER_TOP_FRACTION = 0.16f;
    public static final float DIVIDER_BOTTOM_FRACTION = 0.84f;

    /** 上下兩行之間的距離 ÷ 字號。 */
    public static final float VALUE_LABEL_GAP_RATIO = 0.18f;

    /** 下部文字的字號。 */
    public static float labelFontPx(float fontSizePx) {
        return Math.max(8f, fontSizePx * LABEL_FONT_RATIO);
    }

    /**
     * 牌闊＝左右內邊距 ＋ 兩欄 ＋ 分界線兩邊的留白 ＋ 分界線本身。
     *
     * <p>少了這條式，牌就會用「兩欄 ＋ 一個大間隔」—— 就是用户嫌太空的那個版本。</p>
     */
    public static float cardWidthPx(float columnWidthPx, float paddingPx) {
        return 2f * Math.max(0f, paddingPx) + 2f * Math.max(0f, columnWidthPx)
                + 2f * DIVIDER_MARGIN_PX + DIVIDER_WIDTH_PX;
    }

    /** 第二欄的中心（由牌左邊起算）：第一欄、留白、分界線、留白之後。 */
    public static float secondColumnCentrePx(float columnWidthPx, float paddingPx) {
        return paddingPx + columnWidthPx + DIVIDER_MARGIN_PX + DIVIDER_WIDTH_PX
                + DIVIDER_MARGIN_PX + columnWidthPx / 2f;
    }

    /** 上部溫度與下部文字之間的距離。 */
    public static float valueLabelGapPx(float fontSizePx) {
        return Math.max(1f, fontSizePx * VALUE_LABEL_GAP_RATIO);
    }

    /**
     * n 欄牌闊＝左右內邊距 ＋ 各欄闊度之和 ＋ (n−1) 條分界線（連兩邊留白）。
     *
     * <p>用户 2026-10-11：「7X Info. Bar」四欄（檔位／駕駛模式／Cabin／Battery）——
     * 由兩欄推廣到 n 欄，算式只有這一條，畫圖那裏不會各自加減。</p>
     */
    public static float cardWidthPx(float[] columnWidths, float paddingPx) {
        if (columnWidths == null || columnWidths.length == 0) {
            return 2f * Math.max(0f, paddingPx);
        }
        float columns = 0f;
        for (float w : columnWidths) {
            columns += Math.max(0f, w);
        }
        int dividers = columnWidths.length - 1;
        return 2f * Math.max(0f, paddingPx) + columns
                + dividers * (2f * DIVIDER_MARGIN_PX + DIVIDER_WIDTH_PX);
    }

    /** 第 {@code index} 欄的中心（由牌左邊起算）。 */
    public static float columnCentrePx(float[] columnWidths, int index, float paddingPx) {
        if (columnWidths == null || index < 0 || index >= columnWidths.length) {
            return Math.max(0f, paddingPx);
        }
        float x = Math.max(0f, paddingPx);
        for (int i = 0; i < index; i++) {
            x += Math.max(0f, columnWidths[i]) + 2f * DIVIDER_MARGIN_PX + DIVIDER_WIDTH_PX;
        }
        return x + Math.max(0f, columnWidths[index]) / 2f;
    }

    /** 第 {@code index} 欄右邊那條分界線的 x（最後一欄之後沒有線，回 −1）。 */
    public static float dividerAfterPx(float[] columnWidths, int index, float paddingPx) {
        if (columnWidths == null || index < 0 || index >= columnWidths.length - 1) {
            return -1f;
        }
        return columnCentrePx(columnWidths, index, paddingPx)
                + Math.max(0f, columnWidths[index]) / 2f + DIVIDER_MARGIN_PX;
    }

    /** 一欄的闊度＝上（溫度）下（文字）兩者較闊那個。 */
    public static float columnWidthPx(float valueWidthPx, float labelWidthPx) {
        return Math.max(Math.max(0f, valueWidthPx), Math.max(0f, labelWidthPx));
    }

    /** 牌高＝上下內邊距 ＋ 溫度一行 ＋ 細微間距 ＋ 文字一行。 */
    public static float cardHeightPx(float valueLineHeightPx, float labelLineHeightPx,
                                     float fontSizePx, float paddingPx) {
        return 2f * paddingPx + Math.max(0f, valueLineHeightPx)
                + valueLabelGapPx(fontSizePx) + Math.max(0f, labelLineHeightPx);
    }

    // --------------------------------------------------------------- 溫度顏色

    /** 冷（偏藍）／中性（灰）／熱（偏紅）三隻色，跟温度在中間連續過渡。 */
    public static final int COLOR_COOL = 0xFF3B82F6;
    public static final int COLOR_NEUTRAL = 0xFF9EA0A5;
    public static final int COLOR_WARM = 0xFFE0393E;

    /** 低過這個温度就純冷色、高過那個就純熱色（中間是漸變）。 */
    public static final float COOL_MAX_CELSIUS = 5f;
    public static final float WARM_MIN_CELSIUS = 35f;
    public static final float HOT_CELSIUS = 60f;

    /**
     * 温度對應的顏色：冷 → 藍、常温 → 灰、熱 → 紅，中間連續過渡。
     *
     * <p>顏色是最省位置的表達：同樣一個數字，藍色和紅色一眼分得開。</p>
     */
    public static int colorForTemperature(float celsius) {
        if (Float.isNaN(celsius)) {
            return COLOR_NEUTRAL;
        }
        if (celsius <= COOL_MAX_CELSIUS) {
            return COLOR_COOL;
        }
        if (celsius < WARM_MIN_CELSIUS) {
            return lerp(COLOR_COOL, COLOR_NEUTRAL,
                    (celsius - COOL_MAX_CELSIUS) / (WARM_MIN_CELSIUS - COOL_MAX_CELSIUS));
        }
        if (celsius >= HOT_CELSIUS) {
            return COLOR_WARM;
        }
        return lerp(COLOR_NEUTRAL, COLOR_WARM,
                (celsius - WARM_MIN_CELSIUS) / (HOT_CELSIUS - WARM_MIN_CELSIUS));
    }


    /**
     * 温度本身（帶度數符號），例如 {@code 30°}。
     *
     * <p>沒有讀數、讀數過期（超過 {@link RoundSign#STALE_MS}）、NaN、超出合理範圍，
     * 一律畫 {@link #NO_READING} —— 寧可不顯示，也不要顯示一個不知道多舊、
     * 或者明顯是壞了的數字。</p>
     */
    public static String temperature(Float celsius, long ageMs) {
        if (!RoundSign.fresh(ageMs) || celsius == null || celsius.isNaN()) {
            return NO_READING;
        }
        if (celsius < MIN_CELSIUS || celsius > MAX_CELSIUS) {
            return NO_READING;
        }
        return format(celsius) + "°";
    }

    /**
     * 温度的顯示文字：<b>一位小數</b>（用户 2026-10-08：「溫度要顯示埋小數點一個位」）。
     *
     * <p>以前是四捨五入到整數（{@code 30°}）。開車時温度變化慢，整數位看不出趨勢
     * —— 30.4 和 30.6 都是 30，只有小數位分得開。</p>
     *
     * <p>用 {@link Locale#US} 固定小數點：顯示不該跟着手機語言變成「30,5」。</p>
     */
    public static String format(float celsius) {
        String text = String.format(java.util.Locale.US, "%.1f", celsius);
        // 四捨五入之後的「負零」（-0.0°）看着像壞了，當 0.0
        if ("-0.0".equals(text)) {
            text = "0.0";
        }
        return text;
    }

    /**
     * 一整行：標籤 ＋ 温度。
     *
     * <p>標籤連冒號一起由調用方給（{@code 電池：} / {@code Battery: }）——
     * 不同語言的冒號、空格都不一樣，那是翻譯的事，不是這裏的事。</p>
     */
    /** 兩個 ARGB 之間取比例。 */
    public static int lerp(int from, int to, float t) {
        float k = t < 0f ? 0f : (t > 1f ? 1f : t);
        int a = Math.round(((from >> 24) & 0xFF) + (((to >> 24) & 0xFF) - ((from >> 24) & 0xFF)) * k);
        int r = Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * k);
        int g = Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * k);
        int b = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * k);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }


}
