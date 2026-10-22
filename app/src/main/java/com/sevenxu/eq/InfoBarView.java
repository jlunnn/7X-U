package com.sevenxu.eq;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import androidx.core.content.ContextCompat;
import java.util.Locale;
import android.os.SystemClock;

    public class InfoBarView extends View {

    /** 由外層 service 搬入（原本係 RecordingFloatingService 的常數）。 */
    private static final float MIN_ROW_VALUE_SCALE = 0.88f;   // 溫度等同 Comfort/Standard 字級（唔再縮細）

        /** 檔位量寬用的樣本（ASCII）。 */
        private static final String WIDEST_GEAR = "D";
        /** 右邊幾行的行數：DriveMode、Regen、溫度。 */
        private static final int ROWS = 3;
        private static final int ROW_DRIVE_MODE = 0;
        private static final int ROW_REGEN = 1;
        private static final int ROW_TEMP = 2;

        /**
         * 三個駕駛模式的標籤（索引 0 = 模式 1）。
         *
         * <p>用户 2026-10-11 定案：1 = Comfort、2 = Standard、3 = Sport。⚠️ 一定要由
         * {@code strings.xml} 讀入（下面 constructor 做）—— 之前誤留成空陣列，車上就
         * 只會顯示「Mode 1」，名字冇出現。</p>
         */
        private final String[] driveModeLabels = new String[3];

        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        /** 檔位那個線框（用户 2026-10-11：「檔位加外框設計」）。 */
        private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        /** 檔位那一塊與右邊幾行之間那條淺色分界線。 */
        private final Paint dividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        /**
         * 右邊資料（DriveMode／Regen／溫度）底下那塊浮板。
         *
         * <p>用户 2026-10-11：「除檔位用透明外，其餘資料改做 95% 透明度設計」——
         * 檔位那一欄（連框）保持全透明，右邊幾行坐在 95% 的淺灰浮板上。</p>
         */
        private final Paint rowsBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private final String gearLabel;
        private final String cabinLabel;
        /** 溫度那行兩組（Cabin／Battery）各自的欄闊 —— 畫值時右對齊到各自的欄右邊。 */
        private final float[] tempFieldWidths = new float[2];
        /** Battery 標籤（細字）量出來的闊度。 */
        private float batteryLabelWidthPx;
        private final String batteryLabel;
        private final String[] rowLabels = new String[ROWS];

        private int buttonSizePx;

        private String gearValue = com.sevenxu.eq.RoundSign.NO_READING;
        private String modeValue = com.sevenxu.eq.RoundSign.NO_READING;
        private String regenValue = com.sevenxu.eq.RoundSign.NO_READING;
        private String cabinValue = com.sevenxu.eq.RoundSign.NO_READING;
        private String batteryValue = com.sevenxu.eq.RoundSign.NO_READING;
        private int cabinColour = TempCard.COLOR_NEUTRAL;
        private int batteryColour = TempCard.COLOR_NEUTRAL;

        /** 摺起了沒有：摺起只畫檔位那一塊。 */
        private boolean collapsed;

        private float fontSize;
        private float labelFontSize;
        /** 檔位那一欄的字號（用户：＝車速數字字號 × 0.8，跟住車速走）。 */
        private float gearFontSize;

        private float valueAscent;
        private float valueLineHeight;
        private float labelAscent;
        private float labelLineHeight;
        private float gearAscent;
        private float gearLineHeight;
        /**
         * 字母與「Gear」標籤各自的<b>墨框</b>（相對基線）。
         *
         * <p>用户 2026-10-11：「Gear 文字和檔位是否放在框內的中心點？」—— 只按「行盒」
         * 置中，因為大字那行盒比實際墨高很多，睇落仍然偏下。改用墨框（用固定樣本「D」
         * 量，讀數變成 P／N／R 都唔會跳）去算中心。</p>
         */
        private float gearInkTop;
        private float gearInkBottom;
        private float labelInkTop;
        private float labelInkBottom;
        private float gearInkHeight;
        /** 墨框左右與字寬（相對字的起點）—— 用來把「墨」置中，唔係把「字寬」置中。 */
        private float gearInkLeft;
        private float gearInkRight;
        private float gearInkAdvance;
        private float labelInkHeight;
        private final Rect inkBounds = new Rect();
        private float gearBlockWidth;
        private float rowsWidth;
        private final float[] labelWidths = new float[ROWS];

        InfoBarView(Context context, int buttonSizePx, int cabinLabelRes, int batteryLabelRes) {
            super(context);
            this.buttonSizePx = buttonSizePx;
            this.cabinLabel = context.getString(cabinLabelRes);
            this.batteryLabel = context.getString(batteryLabelRes);
            // 用户 2026-10-11：Info Bar 文字一律英文（三份語言檔都是英文）
            gearLabel = context.getString(R.string.floating_gear_label);
            rowLabels[ROW_DRIVE_MODE] = context.getString(R.string.floating_drivemode_label);
            rowLabels[ROW_REGEN] = context.getString(R.string.floating_regen_label);
            rowLabels[ROW_TEMP] = cabinLabel;
            driveModeLabels[0] = context.getString(R.string.drivemode_1);
            driveModeLabels[1] = context.getString(R.string.drivemode_2);
            driveModeLabels[2] = context.getString(R.string.drivemode_3);

            textPaint.setColor(ContextCompat.getColor(context, R.color.text_primary));
            textPaint.setFakeBoldText(true);

            framePaint.setStyle(Paint.Style.STROKE);
            framePaint.setColor(ContextCompat.getColor(context, R.color.text_tertiary));
            framePaint.setAlpha(200);

            rowsBackgroundPaint.setStyle(Paint.Style.FILL);
            rowsBackgroundPaint.setColor(TempCard.ROWS_BACKGROUND_ARGB);

            dividerPaint.setStyle(Paint.Style.FILL);
            dividerPaint.setColor(ContextCompat.getColor(context, R.color.text_tertiary));
            dividerPaint.setAlpha(150);

            applyTextMetrics();
        }

        private void applyTextMetrics() {
            fontSize = TempCard.fontSizePx(buttonSizePx);
            labelFontSize = TempCard.labelFontPx(fontSize);
            framePaint.setStrokeWidth(Math.max(1f,
                    TempCard.GEAR_FRAME_STROKE_RATIO * buttonSizePx));

            textPaint.setTextSize(fontSize);
            Paint.FontMetrics valueMetrics = textPaint.getFontMetrics();
            valueAscent = valueMetrics.ascent;
            valueLineHeight = valueMetrics.descent - valueMetrics.ascent;

            textPaint.setTextSize(labelFontSize);
            Paint.FontMetrics labelMetrics = textPaint.getFontMetrics();
            labelAscent = labelMetrics.ascent;
            labelLineHeight = labelMetrics.descent - labelMetrics.ascent;

            textPaint.setTextSize(gearTextSize());
            Paint.FontMetrics gearMetrics = textPaint.getFontMetrics();
            gearAscent = gearMetrics.ascent;
            gearLineHeight = gearMetrics.descent - gearMetrics.ascent;
            float glyphWidth = textPaint.measureText(WIDEST_GEAR);
            // 墨框：量「實際會畫的那個字」—— 讀數由 D 變 -- 都一樣置中
            measureGearInk();
            textPaint.setTextSize(labelFontSize);
            gearBlockWidth = TempCard.columnWidthPx(glyphWidth,
                    textPaint.measureText(gearLabel));
            textPaint.getTextBounds(gearLabel, 0, gearLabel.length(), inkBounds);
            labelInkTop = inkBounds.top;
            labelInkBottom = inkBounds.bottom;
            labelInkHeight = labelInkBottom - labelInkTop;

            // 每一行的標籤闊（用最闊的數值樣本量闊度 → 讀數怎麼變都不用重新佈局）
            textPaint.setTextSize(fontSize);
            // 樣本用「真的一次會出現的字」（Comfort／Standard／Sport、Standard／Strong），
            // 之前用「Mode 88」之類，實際數值一闊就疊落去隔鄰（用户 2026-10-12 的 overlap）。
            textPaint.setTextSize(fontSize);
            final String modeSample = widestOf(fontSize,
                    getContext().getString(R.string.drivemode_1),
                    getContext().getString(R.string.drivemode_2),
                    getContext().getString(R.string.drivemode_3));
            final String regenSample = widestOf(fontSize,
                    getContext().getString(R.string.regen_standard),
                    getContext().getString(R.string.regen_strong));
            final String[] valueSamples = {modeSample, regenSample, TempCard.WIDEST_TEMP_SAMPLE};
            final float labelValueGap = TempCard.rowLabelValueGapPx(fontSize);
            final float pairGap = TempCard.rowPairGapPx(fontSize);
            float widest = 0f;
            for (int i = 0; i < ROWS; i++) {
                textPaint.setTextSize(labelFontSize);
                labelWidths[i] = textPaint.measureText(rowLabels[i]);
                textPaint.setTextSize(fontSize);
                float valueWidth = textPaint.measureText(valueSamples[i]);
                widest = Math.max(widest,
                        TempCard.rowWidthPx(labelWidths[i], valueWidth, labelValueGap));
            }
            // 溫度那一行有兩組：Cabin 22.0°  Battery 30.0°
            textPaint.setTextSize(labelFontSize);
            float batteryLabelWidth = textPaint.measureText(batteryLabel);
            textPaint.setTextSize(fontSize);
            batteryLabelWidthPx = batteryLabelWidth;
            tempFieldWidths[0] = TempCard.rowWidthPx(labelWidths[ROW_TEMP],
                    textPaint.measureText(TempCard.WIDEST_TEMP_SAMPLE), labelValueGap);
            tempFieldWidths[1] = TempCard.rowWidthPx(batteryLabelWidth,
                    textPaint.measureText(TempCard.WIDEST_TEMP_SAMPLE), labelValueGap);
            float tempRow = tempFieldWidths[0] + pairGap + tempFieldWidths[1];
            rowsWidth = Math.max(widest, tempRow);
        }

        /** 幾個字串裡面最闊那個（量出來的，不是估）。 */
        private String widestOf(float textSize, String... candidates) {
            textPaint.setTextSize(textSize);
            String widest = "";
            float best = -1f;
            for (String c : candidates) {
                float w = textPaint.measureText(c);
                if (w > best) {
                    best = w;
                    widest = c;
                }
            }
            return widest;
        }

        /**
         * 量檔位那個字的墨框（相對基線）。
         *
         * <p>用户 2026-10-11：「字母與 Gear 標籤放在框內的中心點」—— 用<b>實際</b>會畫的
         * 字去量（{@code D} / {@code P} / {@code --} 各自不同），畫出來才會真置中。</p>
         */
        /** P=螢光藍、R=螢光紅、其他（D/N/S）=淺灰。 */
        private int gearColourFor(String g) {
            String v = g == null ? "" : g.trim().toUpperCase(java.util.Locale.US);
            if (v.startsWith("P")) {
                return 0xFF00E5FF;
            }
            if (v.startsWith("R")) {
                return 0xFFFF2D55;
            }
            return 0xFFC9CDD2;
        }

        private void measureGearInk() {
            final String text = gearValue == null || gearValue.isEmpty() ? WIDEST_GEAR : gearValue;
            textPaint.setTextSize(gearTextSize());
            textPaint.getTextBounds(text, 0, text.length(), inkBounds);
            gearInkTop = inkBounds.top;
            gearInkBottom = inkBounds.bottom;
            gearInkHeight = gearInkBottom - gearInkTop;
            gearInkLeft = inkBounds.left;
            gearInkRight = inkBounds.right;
            gearInkAdvance = textPaint.measureText(text);
        }

        /** 檔位那一欄實際用的字號（未收到車速字號就用卡片自己那套）。 */
        private float gearTextSize() {
            return gearFontSize > 0f ? gearFontSize : fontSize;
        }

        /**
         * 餵車速數字的字號（用户 2026-10-11：檔位 ＝ 它 × 0.8，跟着車速自動調節）。
         *
         * <p>只有真的變了才重量、重新佈局 —— 這條每 200 ms 來一次。</p>
         */
        void setGearFontPx(float px) {
            float wanted = TempCard.gearFontPx(px);
            if (wanted <= 0f || Math.abs(wanted - gearFontSize) < 0.5f) {
                return;
            }
            gearFontSize = wanted;
            applyTextMetrics();
            requestLayout();
            invalidate();
        }

        /** 摺起／展開（用户 2026-10-11：點一下摺起只留檔位；再點檔位展開）。 */
        void setCollapsed(boolean collapsed) {
            if (collapsed == this.collapsed) {
                return;
            }
            this.collapsed = collapsed;
            requestLayout();
            invalidate();
        }

        boolean isCollapsed() {
            return collapsed;
        }

        /** 按鈕大小變了：字號、外框粗細跟着變，重量一遍。 */
        void setButtonSize(int buttonSizePx) {
            if (buttonSizePx <= 0 || buttonSizePx == this.buttonSizePx) {
                return;
            }
            this.buttonSizePx = buttonSizePx;
            applyTextMetrics();
            requestLayout();
            invalidate();
        }

        /**
         * 餵一次讀數。
         *
         * @param batteryTemp 電池温度（可以 null／過期）
         * @param cabinTemp   車內温度
         * @param gear        檔位文字（{@code Signal.GEAR}；讀唔到傳 null）
         * @param driveMode   駕駛模式原值（{@code Signal.DRIVE_MODE}）
         * @param regen       動能回收的文字（{@code RegenLevel} 算好；讀唔到傳 null）
         */
        void setReadings(Float batteryTemp, long batteryAge, Float cabinTemp, long cabinAge,
                         String gear, Integer driveMode, String regen) {
            String nextGear = gear == null || gear.isEmpty()
                    ? com.sevenxu.eq.RoundSign.NO_READING : gear;
            String nextMode = com.sevenxu.eq.DriveMode.text(driveMode, driveModeLabels,
                    getContext().getString(R.string.floating_drivemode_unknown));
            String nextRegen = regen == null || regen.isEmpty()
                    ? com.sevenxu.eq.RoundSign.NO_READING : regen;
            String nextCabin = TempCard.temperature(cabinTemp, cabinAge);
            String nextBattery = TempCard.temperature(batteryTemp, batteryAge);
            int nextCabinColour = cabinTemp == null || cabinTemp.isNaN()
                    ? TempCard.COLOR_NEUTRAL : TempCard.colorForTemperature(cabinTemp);
            int nextBatteryColour = batteryTemp == null || batteryTemp.isNaN()
                    ? TempCard.COLOR_NEUTRAL : TempCard.colorForTemperature(batteryTemp);

            if (nextGear.equals(gearValue) && nextMode.equals(modeValue)
                    && nextRegen.equals(regenValue) && nextCabin.equals(cabinValue)
                    && nextBattery.equals(batteryValue) && nextCabinColour == cabinColour
                    && nextBatteryColour == batteryColour) {
                return;
            }
            if (!nextGear.equals(gearValue)) {
                gearValue = nextGear;
                measureGearInk();
            }
            modeValue = nextMode;
            regenValue = nextRegen;
            cabinValue = nextCabin;
            batteryValue = nextBattery;
            cabinColour = nextCabinColour;
            batteryColour = nextBatteryColour;
            // 只重畫，不重新佈局：闊度已經按最闊樣本預留好了
            invalidate();
        }

        /** 這一刻真正要畫哪幾塊（摺起就只有檔位那塊）。 */
        private float[] activeWidths() {
            return collapsed ? new float[]{gearBlockWidth}
                    : new float[]{gearBlockWidth, rowsWidth};
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            final float padding = TempCard.paddingPx(buttonSizePx);
            final float rowLine = Math.max(valueLineHeight, labelLineHeight);
            final float rows = TempCard.rowsHeightPx(rowLine, TempCard.rowGapPx(fontSize), ROWS);
            final float gearBlock = gearInkHeight
                    + TempCard.valueLabelGapPx(fontSize) + labelInkHeight;
            setMeasuredDimension(
                    resolveSize(Math.round(TempCard.cardWidthPx(activeWidths(), padding)),
                            widthSpec),
                    resolveSize(Math.round(2f * padding + Math.max(rows, gearBlock)), heightSpec));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            final float padding = TempCard.paddingPx(buttonSizePx);
            final float[] widths = activeWidths();

            // 檔位：大字母 ＋ 下面「Gear」 ＋ 一個線框（用户 2026-10-11）
            final float frameMargin = TempCard.gearFrameMarginPx(padding);
            final float[] gearFrame = TempCard.gearFrameXPx(widths, padding);
            if (gearFrame[1] > gearFrame[0]) {
                final float frameRadius = TempCard.cornerPx(buttonSizePx) * 0.6f;
                canvas.drawRoundRect(new RectF(gearFrame[0], frameMargin, gearFrame[1],
                        getHeight() - frameMargin), frameRadius, frameRadius, framePaint);
            }

            // 用户 2026-10-11 第二版：
            //   ① 檔位字母放在框的**左右上下正中心**（只看框，唔再跟標籤一齊排）
            //   ② 「Gear」標籤放在框內**頂部中間**
            final float gearCentre = TempCard.columnCentrePx(widths, 0, padding);
            final float frameTop = frameMargin;
            final float frameBottom = getHeight() - frameMargin;
            final float letterInkTop = TempCard.centredContentTopPx(frameTop, frameBottom,
                    gearInkHeight);
            // ⚠️ 一定要 CENTER：applyTextMetrics 把 paint 設成 LEFT，之前字母是由中心向右畫
            // → 直接爆出框外（用户 2026-10-12 見到 D 走出框）。
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTextSize(gearTextSize());
            textPaint.setColor(ContextCompat.getColor(getContext(), R.color.text_primary));
            // 墨心置中：D 這類字左右墨不對稱，只置中字寬會偏一邊、貼框線
            // 波段顏色：P 螢光藍、R 螢光紅、D 其他淺灰
            textPaint.setColor(gearColourFor(gearValue));
            canvas.drawText(gearValue,
                    TempCard.inkCentredXPx(gearCentre, gearInkAdvance, gearInkLeft, gearInkRight),
                    letterInkTop - gearInkTop, textPaint);
            textPaint.setTextSize(labelFontSize);
            textPaint.setColor(ContextCompat.getColor(getContext(), R.color.text_secondary));
            canvas.drawText(gearLabel, gearCentre,
                    frameTop + TempCard.GEAR_LABEL_TOP_INSET_PX - labelInkTop, textPaint);
            textPaint.setTextAlign(Paint.Align.LEFT);   // 下面幾行靠左排

            if (!collapsed) {
                // 右邊幾行坐喺 95% 淺灰浮板上（檔位那一塊唔畫底，保持透明）
                final float rowsBgLeft = TempCard.rowsBackgroundLeftPx(widths, padding);
                final float bgRadius = TempCard.cornerPx(buttonSizePx);
                if (rowsBgLeft < getWidth()) {
                    canvas.drawRoundRect(new RectF(rowsBgLeft, 0f, getWidth(), getHeight()),
                            bgRadius, bgRadius, rowsBackgroundPaint);
                }

                // 檔位那一塊與右邊幾行之間一條淺色線
                final float dividerX = TempCard.dividerAfterPx(widths, 0, padding);
                if (dividerX > 0f) {
                    final float contentTop = padding;
                    final float contentHeight = getHeight() - 2f * padding;
                    canvas.drawRect(dividerX,
                            contentTop + contentHeight * TempCard.DIVIDER_TOP_FRACTION,
                            dividerX + TempCard.DIVIDER_WIDTH_PX,
                            contentTop + contentHeight * TempCard.DIVIDER_BOTTOM_FRACTION,
                            dividerPaint);
                }
                // 右邊資料由「檔位框右邊 ＋ 最少 2px」開始（用户 2026-10-11）
                drawRows(canvas, padding, TempCard.rowsLeftPx(widths, padding));
            }
            textPaint.setTextSize(fontSize);
        }

        /** 右邊三行：每一行標籤在左、數值在右；最後一行有兩組溫度。 */
        private void drawRows(Canvas canvas, float padding, float left) {
            final float rowLine = Math.max(valueLineHeight, labelLineHeight);
            final float rowGap = TempCard.rowGapPx(fontSize);
            final float pairGap = TempCard.rowPairGapPx(fontSize);
            final int valueColour = 0xFFE8EAE6;   // Mode/Regen：螢光灰
            final int labelColour = ContextCompat.getColor(getContext(), R.color.text_secondary);

            for (int i = 0; i < ROWS; i++) {
                final float rowTop = padding + i * (rowLine + rowGap);
                // 兩支字（值、標籤）都在行內置中，行與行之間才對得直
                final float valueBaseline = rowTop + (rowLine - valueLineHeight) / 2f - valueAscent;
                final float labelBaseline = rowTop + rowLine / 2f
                        - (labelAscent + labelLineHeight / 2f);

                if (i == ROW_TEMP) {
                    float fieldLeft = left;
                    float fieldRight = left + tempFieldWidths[0];
                    drawRowLabel(canvas, cabinLabel, fieldLeft, labelBaseline, labelColour);
                    drawRowValueRight(canvas, cabinValue, fieldLeft, labelWidths[ROW_TEMP],
                            fieldRight, valueBaseline, cabinColour);
                    fieldLeft = fieldRight + pairGap;
                    fieldRight = fieldLeft + tempFieldWidths[1];
                    drawRowLabel(canvas, batteryLabel, fieldLeft, labelBaseline, labelColour);
                    drawRowValueRight(canvas, batteryValue, fieldLeft, batteryLabelWidthPx,
                            fieldRight, valueBaseline, batteryColour);
                } else {
                    drawRowLabel(canvas, rowLabels[i], left, labelBaseline, labelColour);
                    drawRowValueRight(canvas, i == ROW_DRIVE_MODE ? modeValue : regenValue, left,
                            labelWidths[i], left + rowsWidth, valueBaseline, valueColour);
                }
            }
        }

        /** 畫一行裡面的標籤（細字、灰），回它之後的 x（已加標籤—數值間距）。 */
        private float drawRowLabel(Canvas canvas, String text, float x, float baseline, int colour) {
            textPaint.setTextSize(labelFontSize);
            textPaint.setTextAlign(Paint.Align.LEFT);
            textPaint.setColor(colour);
            canvas.drawText(text, x, baseline, textPaint);
            return x + textPaint.measureText(text) + TempCard.rowLabelValueGapPx(fontSize);
        }

        /**
         * 畫一個<b>右對齊</b>的數值，保證與左邊標籤至少隔
         * {@link TempCard#MIN_LABEL_VALUE_GAP_PX}。
         *
         * <p>用户 2026-10-12：「溫度顯示的顯示位置不要 overlap，自動保持 1px 間距」——
         * 值右對齊到欄位右邊（所以永遠不會撞到右邊那一組）；萬一實際字太闊，
         * 就按比例縮字（最低六成），唔會壓到標籤。</p>
         */
        private void drawRowValueRight(Canvas canvas, String text, float labelLeft, float labelWidth,
                                       float fieldRight, float baseline, int colour) {
            if (text == null || text.isEmpty()) {
                return;
            }
            final float available = TempCard.valueFieldWidthPx(fieldRight - labelLeft, labelWidth);
            textPaint.setTextAlign(Paint.Align.RIGHT);
            textPaint.setColor(colour);
            textPaint.setTextSize(fontSize);
            final float needed = textPaint.measureText(text);
            if (available > 0f && needed > available) {
                textPaint.setTextSize(Math.max(fontSize * MIN_ROW_VALUE_SCALE,
                        fontSize * (available / needed)));
            }
            canvas.drawText(text, fieldRight, baseline, textPaint);
            textPaint.setTextAlign(Paint.Align.LEFT);
            textPaint.setTextSize(fontSize);
        }

        /** 畫一行裡面的數值（大字、粗，顏色跟內容）。 */
        private void drawRowValue(Canvas canvas, String text, float x, float baseline, int colour) {
            textPaint.setTextSize(fontSize);
            textPaint.setColor(colour);
            canvas.drawText(text, x, baseline, textPaint);
        }
    }

