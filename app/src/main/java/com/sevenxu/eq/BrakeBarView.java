package com.sevenxu.eq;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import androidx.core.content.ContextCompat;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.view.Gravity;
import android.animation.ValueAnimator;
import android.os.SystemClock;
import java.util.Locale;

public class BrakeBarView extends View {

        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        /** 0% 時那一條淡底框。 */
        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        /** 向下梯形的形狀。 */
        private final android.graphics.Path shape = new android.graphics.Path();
        /** 條中心那個百分比（用户 2026-10-08 要求；同時是診斷：一直 -- 代表車機沒報）。 */
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private int barWidth;
        private float percent;
        private int lastColor;
        private boolean lastBright;
        private String label = com.sevenxu.eq.RoundSign.NO_READING;

        BrakeBarView(Context context, int widthPx) {
            super(context);
            this.barWidth = Math.max(1, widthPx);
            fillPaint.setStyle(Paint.Style.FILL);
            // ⚠️ 必須是不透明白（alpha 255）。Skia 的規矩：paint 有 shader 時用 shader 的顏色，
            // 但會被 paint 自己的 alpha 調變（SkShader.h 原文："they are modulated by the paint's
            // alpha"）。這裏原本寫 BrakeBar.colorFor(0f) —— 那是 alpha = 0 的顏色 ——
            // 於是整條漸變被乘 0：紅色永遠畫不出來，而條中心那個百分比（另一支 paint，
            // 每次自己 setColor）照樣顯示。實車「只有 0%–100% 數字、冇紅色」的元兇（2026-10-11）。
            // 透明度只可以由漸變自己的 alpha（BrakeBar.withFade 給）決定。
            fillPaint.setColor(android.graphics.Color.WHITE);
            fillPaint.setAlpha(255);
            // 淡底框：用中間灰（跟車速牌上半圈同一隻灰）填一層薄薄的水印，
            // 深色同淺色畫面都見得到「這條東西在哪」
            trackPaint.setStyle(Paint.Style.FILL);
            trackPaint.setColor(com.sevenxu.eq.BrakeBar.withAlpha(
                    ContextCompat.getColor(context, R.color.text_tertiary), 130));
            // 條中心的數字：置中、粗體；顏色在 onDraw 按「有沒有踩」決定
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setFakeBoldText(true);
        }

        /** 圓牌直徑變了就跟着改闊（設定頁調大小、按鈕調大小都會走到）。 */
        void setBarWidth(int widthPx) {
            int wanted = Math.max(1, widthPx);
            if (wanted == barWidth) {
                return;
            }
            barWidth = wanted;
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) getLayoutParams();
            if (params != null) {
                params.width = barWidth;
                params.height = Math.round(com.sevenxu.eq.BrakeBar.heightPx(barWidth));
                setLayoutParams(params);
            }
            requestLayout();
            invalidate();
        }

        /**
         * 餵一次腳煞狀態。
         *
         * @param next     腳煞百分比
         * @param flashOff 閃燈的「滅」半拍（F1 車尾燈那樣整條熄一下）
         * @param bright   踩到底：用最亮的紅（不是深紅）
         */
        void setPercent(float next, boolean flashOff, boolean bright, String nextLabel) {
            float wanted = com.sevenxu.eq.BrakeBar.clampPercent(next);
            int color = flashOff ? 0
                    : (bright ? com.sevenxu.eq.BrakeBar.COLOR_RED
                            : com.sevenxu.eq.BrakeBar.colorFor(wanted));
            String wantedLabel = nextLabel == null
                    ? com.sevenxu.eq.RoundSign.NO_READING : nextLabel;
            if (wanted == percent && color == lastColor && bright == lastBright
                    && wantedLabel.equals(label)) {
                return;
            }
            percent = wanted;
            lastColor = color;
            lastBright = bright;
            label = wantedLabel;
            applyGradient(color);
            invalidate();
        }

        /**
         * 橫向漸變：兩端實、中間淡（用户「向內漸變」）。
         *
         * <p>用 shader 而不是畫三段 —— 漸變要連續才像燈條；顏色本身透明（閃燈的滅半拍
         * 是 alpha 0）時整條自然不出現。</p>
         */
        private void applyGradient(int color) {
            final float width = Math.max(1f, getWidth());
            final int[] colors = new int[]{
                    com.sevenxu.eq.BrakeBar.withFade(
                            color, com.sevenxu.eq.BrakeBar.inwardFadeAt(0f)),
                    com.sevenxu.eq.BrakeBar.withFade(
                            color, com.sevenxu.eq.BrakeBar.inwardFadeAt(0.5f)),
                    com.sevenxu.eq.BrakeBar.withFade(
                            color, com.sevenxu.eq.BrakeBar.inwardFadeAt(1f)),
            };
            fillPaint.setShader(new android.graphics.LinearGradient(0f, 0f, width, 0f, colors,
                    new float[]{0f, 0.5f, 1f}, android.graphics.Shader.TileMode.CLAMP));
            // 每次換漸變都把 paint 的 alpha 拉回 255 —— 它會乘落 shader 的輸出。
            // 上面那個 alpha = 0 的教訓：這一條是防止再有人「順手」把顏色設成透明的。
            fillPaint.setAlpha(255);
        }

        /** 梯形：上邊 = 全闊（與車速錶直徑對齊）、下邊左右各縮入。 */
        private void rebuildShape() {
            final float width = getWidth();
            final float height = getHeight();
            final float inset = com.sevenxu.eq.BrakeBar.bottomInsetPx(width);
            shape.reset();
            shape.moveTo(0f, 0f);
            shape.lineTo(width, 0f);
            shape.lineTo(width - inset, height);
            shape.lineTo(inset, height);
            shape.close();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            rebuildShape();
            applyGradient(lastColor);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            setMeasuredDimension(
                    resolveSize(barWidth, widthSpec),
                    resolveSize(Math.round(com.sevenxu.eq.BrakeBar.heightPx(barWidth)),
                            heightSpec));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            final float width = getWidth();
            final float height = getHeight();
            if (width <= 0f || height <= 0f) {
                return;
            }
            // 0%（無踩）：填充透明，但保留一條好淡的底框 ——
            // 用户 2026-10-08「腳煞顯示提示 bar 消失了」：完全唔畫的話，車上看起來像壞了，
            // 也分不出「沒踩」還是「這個功能沒開」。底框用 background 色、alpha 很低。
            if (!com.sevenxu.eq.BrakeBar.drawsAnything(percent)) {
                if (shape.isEmpty()) {
                    rebuildShape();
                }
                canvas.drawPath(shape, trackPaint);
                drawLabel(canvas, false);
                return;
            }
            // 向下梯形 ＋ 向內漸變（用户 2026-10-08）：上邊與車速錶直徑對齊
            if (shape.isEmpty()) {
                rebuildShape();
            }
            canvas.drawPath(shape, fillPaint);
            drawLabel(canvas, true);
        }

        /**
         * 條中心的百分比（用户 2026-10-08：「請在腳煞 bar 中心顯示百分比數值」）。
         *
         * <p>字號跟條高走（9:1 的條很扁，字是疊在條上面的）。有踩時用白字壓在紅底上，
         * 沒踩時用灰字 —— 這樣連「車機完全沒報訊號（{@code --}）」都看得出來。</p>
         */
        private void drawLabel(Canvas canvas, boolean pressed) {
            if (label == null || label.isEmpty()) {
                return;
            }
            final float barHeight = com.sevenxu.eq.BrakeBar.heightPx(getWidth());
            textPaint.setTextSize(Math.max(9f, barHeight * 0.95f));
            textPaint.setColor(pressed ? 0xFFFFFFFF
                    : ContextCompat.getColor(getContext(), R.color.text_tertiary));
            canvas.drawText(label, getWidth() / 2f,
                    getHeight() / 2f - (textPaint.getFontMetrics().ascent
                            + textPaint.getFontMetrics().descent) / 2f, textPaint);
        }
    }
