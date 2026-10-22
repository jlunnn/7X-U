package com.sevenxu.eq;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 行車卡（獨立懸浮，可自由拖）：車速 ＋ 波段 ＋ 油門條（12 段）＋ 腳煞。
 * 數據來自 ECARX（Telemetry），外觀沿用 camera app 嘅 ThrottleBar／BrakeBar 比例。
 */
public class DriveCardOverlay {

    private final Context ctx;
    private final SharedPreferences sp;
    private final WindowManager wm;
    private View root;

    private InfoBarView infoView;
    /** 「=」拖動把手。 */
    private View dragHandle;
    private RoundSignView sign;
    private BrakeBarView brakeBar;
    private WindowManager.LayoutParams lp;
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean running = false;
    /** 只喺相關設定真係變咗才重建（避免不停閃動）。 */
    private String lastSig = null;
    /** 同 camera 一樣：油門滿值閃爍 ＋ 腳煞尾燈狀態機。 */
    private boolean throttleFlashOff = false;
    private boolean throttleAtMax = false;
    private final android.os.Handler hudH = new android.os.Handler(android.os.Looper.getMainLooper());
    /** 照抄 camera：滿油門時每 BLINK_HALF_PERIOD_MS 切換一次閃。 */
    private final Runnable throttleBlinkRunnable = new Runnable() {
        @Override
        public void run() {
            if (!throttleAtMax || sign == null) {
                return;
            }
            throttleFlashOff = !throttleFlashOff;
            sign.setThrottleFlashOff(throttleFlashOff);
            hudH.postDelayed(this, ThrottleBar.BLINK_HALF_PERIOD_MS);
        }
    };
    private boolean brakeFlashOff = false;
    private final BrakeBar.Lamp brakeLamp = new BrakeBar.Lamp();
    /** 限速提示音（超速時每 2 秒一響）。 */
    private android.media.ToneGenerator beeper;
    private long lastBeepAt = 0;

    public DriveCardOverlay(Context c) {
        this.ctx = c.getApplicationContext();
        this.sp = ctx.getSharedPreferences("zeekreq", Context.MODE_PRIVATE);
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
    }

    public static boolean enabled(SharedPreferences sp) {
        return sp.getBoolean("throttle_tach", true) || sp.getBoolean("brake_show", true);
    }

    @SuppressLint("MissingPermission")
    public void show() {
        if (wm == null) {
            return;
        }
        String sig = sp.getInt("hud_dp", 92) + "|" + sp.getInt("sign_pct", 130)
                + "|" + sp.getBoolean("throttle_tach", true)
                + "|" + sp.getBoolean("brake_show", true)
                + "|" + sp.getBoolean("info_collapsed", false)
                + "|" + sp.getInt("hud_alpha", 95)
                + "|" + sp.getBoolean("show_tempcard", true)
                + "|" + sp.getInt("throttle_full_pct", 80);
        if (running) {
            if (sig.equals(lastSig)) {
                return;              // 設定冇變 → 唔重建（唔會閃）
            }
            hide();                  // 設定真係變咗 → 重建
        }
        lastSig = sig;
        try {
            LinearLayout card = new LinearLayout(ctx);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(2), dp(2), dp(2), dp(2));
            // camera app 嘅 HUD 冇背景卡（透明懸浮）→ 跟返一樣
            card.setBackground(null);
            // camera: floatingContainer.setAlpha(appConfig.getFloatingWindowAlpha()/100f)
            card.setAlpha(sp.getInt("hud_alpha", 95) / 100f);

            // ==== 完全跟 camera app 排版 ====
            // horizontalContainer：[ signColumn（車速圓牌 + 腳煞長條）] [ InfoBarView（車內/電池溫度 + 檔位 + 模式 + 回收）]
            int buttonSize = dp(Math.max(56, Math.min(140, sp.getInt("hud_dp", 92))));
            LinearLayout hud = new LinearLayout(ctx);
            // ==== 「=」雙橫線拖動把手（放喺車速圓牌左手邊，相距 2px）====
            final android.graphics.Paint hp = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            dragHandle = new View(ctx) {
                @Override
                protected void onDraw(android.graphics.Canvas cv) {
                    hp.setColor(0xFFB6FF3B);
                    float w = getWidth(), h = getHeight();
                    float bw = w * 0.60f, bh = Math.max(3f, h * 0.09f);
                    float x0 = (w - bw) / 2f;
                    float y1 = h * 0.40f, y2 = h * 0.56f;
                    cv.drawRoundRect(x0, y1 - bh / 2, x0 + bw, y1 + bh / 2, bh / 2, bh / 2, hp);
                    cv.drawRoundRect(x0, y2 - bh / 2, x0 + bw, y2 + bh / 2, bh / 2, bh / 2, hp);
                }
            };
            // 把手垂直對準「車速圓牌」中心（唔計腳煞條高度）
            int brakeExtra = Math.round(BrakeBar.heightPx(dp(92) * 130 / 100))
                    + Math.max(2, dp(92) / 32);
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(26), dp(46));
            hlp.rightMargin = 2;                 // 2px
            hlp.gravity = Gravity.CENTER_VERTICAL;
            hlp.topMargin = -(brakeExtra / 2);   // 上移半條腳煞 → 對正圓心
            hud.addView(dragHandle, 0, hlp);
            hud.setOrientation(LinearLayout.HORIZONTAL);
            hud.setGravity(Gravity.CENTER_VERTICAL);
            hud.setPadding(0, 0, 0, 0);   // camera horizontalContainer 冇 padding

            LinearLayout signCol = new LinearLayout(ctx);
            signCol.setOrientation(LinearLayout.VERTICAL);
            signCol.setGravity(Gravity.CENTER_HORIZONTAL);
            int signSize = Math.round(RoundSign.scaledDiameterPx(buttonSize,
                    sp.getInt("sign_pct", RoundSign.DEFAULT_SCALE_PERCENT)));
            sign = new RoundSignView(ctx, R.color.recording, R.color.text_tertiary);
            signCol.addView(sign, new LinearLayout.LayoutParams(signSize, signSize));
            brakeBar = new BrakeBarView(ctx, signSize);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(signSize,
                    Math.round(BrakeBar.heightPx(signSize)));
            bp.topMargin = Math.max(2, buttonSize / 32);
            // ---- 照抄 camera app applyRingIndicators() ----
            sign.setThrottleVisible(sp.getBoolean("throttle_show", true));
            brakeBar.setBarWidth(signSize);
            brakeBar.setVisibility(sp.getBoolean("throttle_tach", true)
                    && sp.getBoolean("brake_show", true) ? View.VISIBLE : View.GONE);
            signCol.addView(brakeBar, bp);
            signCol.setPadding(Math.max(2, buttonSize / 16),
                    Math.round(BrakeBar.columnTopPaddingPx(signSize, Math.max(2f, buttonSize / 32f))),
                    Math.max(2, buttonSize / 16), 0);
            LinearLayout.LayoutParams scp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            scp.leftMargin = Math.max(4, Math.round(buttonSize * RoundSign.LABEL_GAP_RATIO));
            signCol.setVisibility(sp.getBoolean("throttle_tach", true) ? View.VISIBLE : View.GONE);
            sign.setVisibility(sp.getBoolean("throttle_tach", true) ? View.VISIBLE : View.GONE);
            hud.addView(signCol, scp);

            sign.setOnClickListener(v -> {
                boolean on = !sp.getBoolean("speed_alert", true);
                sp.edit().putBoolean("speed_alert", on).apply();
                try {
                    render();
                } catch (Throwable ignored) {
                }
            });
            infoView = new InfoBarView(ctx, buttonSize,
                    R.string.floating_infobar_cabin_label, R.string.floating_temp_battery_label);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            ip.leftMargin = Math.max(4, buttonSize / 16);
            hud.addView(infoView, ip);
            // camera 預設係「摺起」小卡（同你相中右上角一樣）
            infoView.setGearFontPx(buttonSize * 0.52f);   // 波段字放大（框內居中）
            infoView.setCollapsed(sp.getBoolean("info_collapsed", false));
            infoView.setVisibility(sp.getBoolean("show_tempcard", true) ? View.VISIBLE : View.GONE);
            infoView.setOnClickListener(v -> {
                boolean c = !sp.getBoolean("info_collapsed", false);
                sp.edit().putBoolean("info_collapsed", c).apply();
                try {
                    infoView.setCollapsed(c);
                } catch (Throwable ignored) {
                }
            });
            card.addView(hud);

            lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    android.graphics.PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = sp.getInt("drive_fx", dp(24));
            lp.y = sp.getInt("drive_fy", dp(220));

            dragHandle.setOnTouchListener(new View.OnTouchListener() {
                float dx, dy;
                int sx, sy;
                boolean moved;

                @Override
                public boolean onTouch(View v, MotionEvent e) {
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            dx = e.getRawX();
                            dy = e.getRawY();
                            sx = lp.x;
                            sy = lp.y;
                            moved = false;
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            lp.x = sx + (int) (e.getRawX() - dx);
                            lp.y = sy + (int) (e.getRawY() - dy);
                            moved = true;
                            try {
                                wm.updateViewLayout(root, lp);
                            } catch (Throwable ignored) {
                            }
                            return true;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            if (moved) {
                                sp.edit().putInt("drive_fx", lp.x).putInt("drive_fy", lp.y).apply();
                            }
                            return true;
                        default:
                            return false;
                    }
                }
            });

            wm.addView(card, lp);
            root = card;
            running = true;
            try {
                Telemetry.get().acquire(ctx, "7X=U-drive");
            } catch (Throwable ignored) {
            }
            h.removeCallbacks(tick);
            h.post(tick);
        } catch (Throwable ignored) {
        }
    }

    public void hide() {
        running = false;
        try {
            if (beeper != null) {
                beeper.release();
                beeper = null;
            }
        } catch (Throwable ignored) {
        }
        h.removeCallbacks(tick);
        try {
            if (root != null) {
                wm.removeView(root);
            }
        } catch (Throwable ignored) {
        }
        root = null;
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            try {
                render();
            } catch (Throwable ignored) {
            }
            h.postDelayed(this, 200);
        }
    };

    private void render() {
        VehicleState st = Telemetry.get().latest();
        float thr = st != null && st.throttle != null ? st.throttle : 0f;
        float brk = st != null && st.brake != null ? st.brake : 0f;
        boolean over = st != null && st.speedKmh != null && st.speedKmh > sp.getInt("speed_limit", 80);
        boolean blinkOn = (System.currentTimeMillis() / 500L) % 2 == 0;
        try {
            // 油門滿值 → 閃爍（ThrottleBar.BLINK_HALF_PERIOD_MS）
            boolean thrAtMax = st != null && st.throttle != null && st.throttle >= 0.995f;
            if (thrAtMax) {
                throttleFlashOff = (System.currentTimeMillis() / Math.max(1, ThrottleBar.BLINK_HALF_PERIOD_MS)) % 2 == 1;
            } else {
                throttleFlashOff = false;
            }
            if (sign != null) {
                sign.setReading(st != null && st.speedKmh != null
                        ? String.valueOf(Math.round(st.speedKmh)) : RoundSign.NO_READING);
                // ==== 照抄 camera updateThrottleBar() ====
                Float rawThr = null;
                try {
                    rawThr = Telemetry.get().readings().number(Signal.THROTTLE_DEPTH);
                } catch (Throwable ignored) {
                }
                boolean thrAvail = rawThr != null && !rawThr.isNaN();
                float shown = ThrottleBar.safeDisplayPercent(rawThr);
                // 用戶指定：踩到 80% 就當 100%（之後維持滿）
                int fullPct = Math.max(50, Math.min(100, sp.getInt("throttle_full_pct", 80)));
                shown = Math.min(100f, shown / (fullPct / 100f));
                sign.setThrottle(shown, thrAvail);
                boolean thrMax = thrAvail && ThrottleBar.isAtMax(shown);
                if (thrMax && !throttleAtMax) {
                    throttleAtMax = true;
                    throttleFlashOff = false;
                    hudH.post(throttleBlinkRunnable);
                } else if (!thrMax && throttleAtMax) {
                    throttleAtMax = false;
                    hudH.removeCallbacks(throttleBlinkRunnable);
                    sign.setThrottleFlashOff(false);
                }
                sign.setThrottleFlashOff(throttleFlashOff);
                boolean alertOn = sp.getBoolean("speed_alert", true);
                sign.setAlertState(alertOn && over, !alertOn, blinkOn);
            }
        } catch (Throwable ignored) {
        }
        try {
            if (brakeBar != null) {
                // 訊號原始值 0–30 → 對應 0–90%
                Float rawBrake = null;
                try {
                    rawBrake = Telemetry.get().readings().number(Signal.BRAKE_DEPTH);
                } catch (Throwable ignored) {
                }
                // 實測：踩最深約 25 → 所以 0–25 映射成 0–100%
                float pct = rawBrake != null
                        ? Math.min(100f, Math.max(0f, rawBrake / 25f * 100f))
                        : Math.min(100f, Math.max(0f, brk * 100f));
                boolean atMax = BrakeBar.isAtMax(pct);
                try {
                    if (brakeLamp.shouldFlash()) {
                        brakeFlashOff = !brakeFlashOff;
                        brakeLamp.takeHalfPhase();
                    } else {
                        brakeFlashOff = false;
                    }
                } catch (Throwable ignored) {
                }
                // 冇讀數 → 畫「--」（同 camera 規格一致）；有讀數 → 百分比置中
                String label = (st == null || st.brake == null)
                        ? RoundSign.NO_READING : Math.round(pct) + "%";
                brakeBar.setPercent(pct, brakeFlashOff, atMax, label);
            }
        } catch (Throwable ignored) {
        }
        // 限速響聲
        try {
            // 藍色（off）時必須連提示音一齊停
            boolean alertOnNow = sp.getBoolean("speed_alert", true);
            if (over && alertOnNow && sp.getBoolean("speed_beep", true)) {
                long now = System.currentTimeMillis();
                if (now - lastBeepAt > 2000L) {
                    lastBeepAt = now;
                    if (beeper == null) {
                        beeper = new android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 70);
                    }
                    beeper.startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 160);
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            if (infoView != null) {
                Readings rd = Telemetry.get().readings();
                Float bt = rd == null ? null : rd.number(Signal.BATTERY_TEMP);
                String regen = rd == null ? null
                        : RegenLevel.text(rd.code(Signal.REGEN), "Standard", "Strong", "—");
                Float cabin = rd == null ? null : rd.number(Signal.TEMP_INSIDE);
                infoView.setReadings(bt, 0L, cabin, 0L,
                        st == null ? null : st.gear,
                        rd == null ? null : rd.code(Signal.DRIVE_MODE),
                        regen);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 12 段油門條 ＋ 腳煞條（比例跟 ThrottleBar／BrakeBar）。 */
    private static class BarsView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float thr, brk;
        private boolean showThr = true, showBrk = true;

        BarsView(Context c) {
            super(c);
        }

        void set(float thr, float brk, boolean showThr, boolean showBrk) {
            this.thr = Math.max(0f, Math.min(1f, thr));
            this.brk = Math.max(0f, Math.min(1f, brk));
            this.showThr = showThr;
            this.showBrk = showBrk;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas cv) {
            int segs = ThrottleBar.SEGMENTS;                 // 12
            float gap = getHeight() * 0.18f;
            float segH = (getHeight() - gap * (segs - 1)) / segs;
            float wBar = getWidth() * 0.55f;
            int lit = Math.round(thr * segs);
            for (int i = 0; i < segs; i++) {
                float y = getHeight() - (i + 1) * segH - i * gap;
                int col;
                if (i < segs * 0.5f) {
                    col = 0xFFB6FF3B;
                } else if (i < segs * 0.75f) {
                    col = 0xFFFFC107;
                } else {
                    col = 0xFFFF2D55;
                }
                p.setColor(i < lit ? col : 0xFF2A3025);
                cv.drawRoundRect(0, y, wBar, y + segH, segH * 0.3f, segH * 0.3f, p);
            }
            if (showBrk) {
                float bw = getWidth() * 0.30f;
                float x0 = wBar + getWidth() * 0.06f;
                p.setColor(0xFF2A3025);
                cv.drawRoundRect(x0, 0, x0 + bw, getHeight(), 6, 6, p);
                p.setColor(brk > 0.02f ? 0xFFFF2D55 : 0xFF4B5A3A);
                float hh = getHeight() * Math.min(1f, brk / (BrakeBar.MAX_PERCENT / 100f));
                cv.drawRoundRect(x0, getHeight() - hh, x0 + bw, getHeight(), 6, 6, p);
            }
        }
    }

    private int dp(int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }
}
