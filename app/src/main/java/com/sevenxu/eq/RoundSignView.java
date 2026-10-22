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

public class RoundSignView extends View {

        /** 量「車速數字字號」用的固定樣本（ASCII，唔會被 HardcodedTextTest 捉）。 */
        private static final String NUMBER_SAMPLE = "88";

        /**
         * 這塊牌現時嘅「車速數字字號」（用 {@link #NUMBER_SAMPLE} 量，穩定）。
         *
         * <p>Info Bar 嘅檔位字號 ＝ 這個數 × {@code TempCard.GEAR_SIZE_RATIO}（用户 2026-10-11）。</p>
         */
        private float referenceNumberSizePx;

        private final Paint discPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        /** 量墨水框用的，復用一份，別在 onDraw 裏 new。 */
        private final Rect inkBounds = new Rect();

        /** 有讀數時圈的顏色 / 沒有讀數（或過期）時圈的顏色。 */
        private final int readingRingColorRes;
        private final int noReadingRingColorRes;

        private String text = RoundSign.NO_READING;
        private boolean hasReading = false;

        /** 超速提示的三個狀態：在提示中、已被點靜音、這一刻是亮的相位。 */
        private boolean alert;
        private boolean muted;
        private boolean blinkOn;

        /**
         * 上半圓那條油門弧（用户 2026-10-08 改的設計）。
         *
         * <p>沒開這個顯示時，上半圓就用原本那隻圈色 —— 圓牌本身仍然是「車速錶」。</p>
         */
        private boolean throttleVisible;
        private float throttlePercent;
        private boolean throttleAvailable;
        private boolean throttleFlashOff;
        private int litSegments = -1;

        /** 量過一次的「每 em 墨水寬高」，避免每幀重新量。 */
        private String measuredText;
        private float measuredWidthPerEm;
        private float measuredHeightPerEm;

        RoundSignView(Context context, int readingRingColorRes, int noReadingRingColorRes) {
            super(context);
            this.readingRingColorRes = readingRingColorRes;
            this.noReadingRingColorRes = noReadingRingColorRes;

            discPaint.setStyle(Paint.Style.FILL);
            discPaint.setColor(ContextCompat.getColor(context, R.color.surface));

            ringPaint.setStyle(Paint.Style.STROKE);

            textPaint.setColor(ContextCompat.getColor(context, R.color.text_primary));
            textPaint.setTextAlign(Paint.Align.LEFT);
            // 車上離得遠、字號又小（最小那檔只有十幾 sp），筆畫粗一點才認得清
            textPaint.setFakeBoldText(true);
        }

        /** 這一幀要畫的字（已經是最終結果：數字，或者過期的 {@code --}）。 */
        void setReading(String next) {
            String wanted = next == null ? RoundSign.NO_READING : next;
            boolean wantedHasReading = RoundSign.hasReading(wanted);
            if (wanted.equals(text) && wantedHasReading == hasReading) {
                return;
            }
            text = wanted;
            hasReading = wantedHasReading;
            invalidate();
        }

        /** 油門百分比（0–100）＋ 有沒有讀數：畫在上半圓，一格一格亮。 */
        void setThrottle(float percent, boolean available) {
            float wanted = available
                    ? com.sevenxu.eq.ThrottleBar.clampPercent(percent) : 0f;
            int wantedLit = available
                    ? com.sevenxu.eq.ThrottleBar.litSegments(
                            wanted, com.sevenxu.eq.ThrottleBar.SEGMENTS)
                    : 0;
            if (wanted == throttlePercent && available == throttleAvailable
                    && wantedLit == litSegments) {
                return;
            }
            throttlePercent = wanted;
            throttleAvailable = available;
            litSegments = wantedLit;
            invalidate();
        }

        /** 開 / 關上半圓那條油門弧。 */
        void setThrottleVisible(boolean visible) {
            if (visible == throttleVisible) {
                return;
            }
            throttleVisible = visible;
            invalidate();
        }

        /** 踩滿油門時閃動：全亮 / 全熄交替（用户 2026-10-07 指定）。 */
        void setThrottleFlashOff(boolean off) {
            if (off == throttleFlashOff) {
                return;
            }
            throttleFlashOff = off;
            invalidate();
        }

        /** 第幾格用什麼顏色的資源（跟舊燈帶同一套色，車上才一致）。 */
        private int bandColorRes(int band) {
            switch (band) {
                case com.sevenxu.eq.ThrottleBar.BAND_AMBER:
                    return R.color.throttle_amber;
                case com.sevenxu.eq.ThrottleBar.BAND_RED:
                    return R.color.throttle_red;
                case com.sevenxu.eq.ThrottleBar.BAND_ORANGE:
                    return R.color.throttle_orange;
                case com.sevenxu.eq.ThrottleBar.BAND_PURPLE:
                    return R.color.throttle_purple;
                default:
                    return R.color.throttle_green;
            }
        }

        /**
         * 超速提示的樣子。
         *
         * <p>三種畫法，一眼分得清：</p>
         * <ul>
         *   <li>提示關掉（{@code offNow}）：<b>白底藍框</b>、不閃 —— 用户點一下就變這樣
         *       （2026-10-08 用户指定：只換外框顏色，中間保留白色背景）</li>
         *   <li>警示中（{@code alertNow} ＋ {@code blinkOnNow}）：紅底白字，400 ms 一格地閃</li>
         *   <li>其餘：平常那副樣子（白底紅圈黑字）</li>
         * </ul>
         * <p>三個參數全都沒變就直接返回：閃動是 200 ms 一次的重畫，不必每次都真畫。</p>
         */
        void setAlertState(boolean alertNow, boolean offNow, boolean blinkOnNow) {
            if (alert == alertNow && muted == offNow && blinkOn == blinkOnNow) {
                return;
            }
            alert = alertNow;
            muted = offNow;
            blinkOn = blinkOnNow;
            invalidate();
        }

        /** 量當前文字的「每 em 墨水寬高」。文字沒變就直接用上次的。 */
        private void measureInkPerEm() {
            if (text.equals(measuredText)) {
                return;
            }
            final float referenceSize = 100f;
            textPaint.setTextSize(referenceSize);
            textPaint.getTextBounds(text, 0, text.length(), inkBounds);
            measuredWidthPerEm = inkBounds.width() / referenceSize;
            measuredHeightPerEm = inkBounds.height() / referenceSize;
            measuredText = text;
        }

        /** 車速數字字號（未畫過回 0）。 */
        float referenceNumberSizePx() {
            return referenceNumberSizePx;
        }

        /** 同一個圓、同一條規矩下，固定樣本 {@link #NUMBER_SAMPLE} 的字號。 */
        private float numberSizeForSample(float innerRadius, float diameter) {
            textPaint.setTextSize(100f);
            textPaint.getTextBounds(NUMBER_SAMPLE, 0, NUMBER_SAMPLE.length(), inkBounds);
            final float widthPerEm = inkBounds.width() / 100f;
            final float heightPerEm = inkBounds.height() / 100f;
            return RoundSign.fitSizePx(innerRadius, widthPerEm, heightPerEm,
                    RoundSign.maxSizePx(diameter), RoundSign.SAFE_K);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            final int width = getWidth();
            final int height = getHeight();
            if (width <= 0 || height <= 0) {
                return;
            }

            final float diameter = Math.min(width, height);
            final float radius = diameter / 2f;
            final float stroke = RoundSign.ringStrokePx(diameter);
            final float centerX = width / 2f;
            final float centerY = height / 2f;

            // 底色永遠是白的（用户 2026-10-08：中間保留白色背景、數字深色）。
            // 收 0.5px：圓正好畫到視圖邊界時，反鋸齒那一圈會被邊界切掉，
            // 放大看就像圓被削平了一邊（用户 2026-10-08：「右邊被削了」）。
            discPaint.setColor(ContextCompat.getColor(getContext(), R.color.surface));
            canvas.drawCircle(centerX, centerY, Math.max(0.5f, radius - 0.5f), discPaint);

            // 圈分成上下兩條半圓弧（用户 2026-10-08 的設計）：
            //   上半圓 = 油門（灰色底弧 ＋ 一格一格亮的彩色格）
            //   下半圓 = 超速提示的開 / 關（紅＝開、藍＝關；警示中會閃）
            ringPaint.setStrokeWidth(stroke);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeCap(Paint.Cap.BUTT);
            // 圓心擺在半徑減半個線寬的位置，弧的外緣才正好貼着視圖邊緣
            final float arcRadius = Math.max(0.5f, radius - stroke / 2f - 0.5f);
            final RectF ringRect = new RectF(centerX - arcRadius, centerY - arcRadius,
                    centerX + arcRadius, centerY + arcRadius);

            if (throttleVisible) {
                // 灰色底弧：0% 時看到的就是這一條。
                // 用户 2026-10-08「圓形的最右邊不是完整的圓形」—— 之前 alpha 120 太淡，
                // 上半圈在車機那種偏亮的畫面上幾乎看不見，3 點鐘位置就只剩紅色那一段，
                // 看起來像圓圈缺了一角。加到 210 之後任何底色都認得出整圈。
                ringPaint.setColor(ContextCompat.getColor(getContext(), R.color.text_tertiary));
                ringPaint.setAlpha(210);
                canvas.drawArc(ringRect, ThrottleBar.ARC_TOP_START_DEGREES,
                        ThrottleBar.ARC_HALF_SWEEP_DEGREES, false, ringPaint);
                ringPaint.setAlpha(255);
                // 亮的格：踩滿閃動時全部熄掉（看起來就是整條在閃）
                int lit = throttleFlashOff ? 0 : Math.max(0, litSegments);
                for (int i = 0; i < lit; i++) {
                    ringPaint.setColor(ContextCompat.getColor(getContext(),
                            bandColorRes(ThrottleBar.bandOf(i, ThrottleBar.SEGMENTS))));
                    canvas.drawArc(ringRect,
                            ThrottleBar.arcSegmentStartDegrees(i, ThrottleBar.SEGMENTS),
                            ThrottleBar.arcSegmentSweepDegrees(ThrottleBar.SEGMENTS),
                            false, ringPaint);
                }
            } else {
                // 沒開油門顯示：上半圓用平常那隻圈色，圓牌仍然像個車速錶
                ringPaint.setColor(ContextCompat.getColor(getContext(),
                        hasReading ? readingRingColorRes : noReadingRingColorRes));
                canvas.drawArc(ringRect, ThrottleBar.ARC_TOP_START_DEGREES,
                        ThrottleBar.ARC_HALF_SWEEP_DEGREES, false, ringPaint);
            }

            // 下半圓：提示開 / 關。警示中、閃到暗那半拍就調暗（不是熄掉，
            // 熄掉會被看成「提示關掉了」）
            ringPaint.setColor(ContextCompat.getColor(getContext(),
                    muted ? R.color.speed_alert_muted : R.color.recording));
            if (alert && !blinkOn) {
                ringPaint.setAlpha(70);
            }
            canvas.drawArc(ringRect, 0f, 180f, false, ringPaint);
            ringPaint.setAlpha(255);

            measureInkPerEm();
            // 可用內圈 ＝ 內圈再收 1 px：三位數自動收窄之後仍然留一條邊（用户 2026-10-08）
            final float innerRadius = RoundSign.usableInnerRadiusPx(diameter, stroke);
            // 用户 2026-10-11：Info Bar 的檔位字號要跟車速數字走 → 順手量一個**固定樣本**
            // （兩位數）在同一個圓、同一條規矩下的字號。用固定樣本，所以唔會因為車速
            // 由 99 變 100 而跳一下；但牌子大小／顯示比例改動時會跟着變。
            referenceNumberSizePx = numberSizeForSample(innerRadius, diameter);
            final float size = RoundSign.fitSizePx(innerRadius, measuredWidthPerEm,
                    measuredHeightPerEm, RoundSign.maxSizePx(diameter), RoundSign.SAFE_K);
            if (size <= 0f) {
                return;
            }

            textPaint.setTextSize(size);
            textPaint.getTextBounds(text, 0, text.length(), inkBounds);
            // 底色永遠是白，數字就永遠用深色（警示改由下半圓那條弧去表達）
            textPaint.setColor(ContextCompat.getColor(getContext(), R.color.text_primary));
            canvas.drawText(text,
                    centerX + RoundSign.centerOffsetPx(inkBounds.left, inkBounds.right),
                    centerY + RoundSign.centerOffsetPx(inkBounds.top, inkBounds.bottom),
                    textPaint);
        }
    }
