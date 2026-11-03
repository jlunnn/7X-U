package com.sevenxu.eq;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.lang.reflect.Method;

/**
 * 紅色螢光懸浮制（後霧燈快掣）。
 *
 * <p>規格（用戶 2026-10-31）：「紅色螢光懸浮制，size 比例和原有的 EQ 相同；
 * 按下觸發 21050500 開關三次；操作方式和 hermes probe 12.22 一樣做開關。」</p>
 *
 * <ul>
 *   <li>size：同 EQ 圓掣一樣跟 {@code iconSizePx()}（短邊 7.5%，48–84dp，icon_dp 可調）。</li>
 *   <li>樣式：深底圓掣＋霓虹紅光環（#FF3B4E），掣面一粒紅核。</li>
 *   <li>一下 = 寫 0x21050500 三次 0↔1（1→0→1→0→1→0，每步 350 ms，完結回 1），
 *       反射走 {@code Car.create → getICarFunction → setFunctionValue}，
 *       跟 hermes-probe v12.22 寫入 path。</li>
 *   <li>可拖、記位（fog_fx / fog_fy），設定頁開關（fog_red_enabled，預設開）。</li>
 * </ul>
 */
final class RedButtonOverlay {

    /** 後霧燈功能號（Signal.REAR_FOG；wscan 實測 before=0 test=1 after=1 可寫）。 */
    static final int FOG_ID = 0x21050500;
    /** 舊預設（一步 350ms、三吓）；而家每吓＝閃開+閃關兩段可調。 */

    // ================================================================= 節奏（可調，讀 zeekreq prefs）

    /** 閃開時間 ms（fog_on_ms，預設 350）。 */
    static long onMs(android.content.SharedPreferences sp) {
        return clampMs(sp.getInt("fog_on_ms", 350));
    }

    /** 閃關時間 ms（fog_off_ms，預設 350）。 */
    static long offMs(android.content.SharedPreferences sp) {
        return clampMs(sp.getInt("fog_off_ms", 350));
    }

    /** 閃動次數 2–10（fog_count，預設 3）。 */
    static int count(android.content.SharedPreferences sp) {
        return Math.max(2, Math.min(10, sp.getInt("fog_count", 3)));
    }

    private static long clampMs(int v) {
        return Math.max(100, Math.min(2000, v));
    }

    static boolean enabled(android.content.SharedPreferences sp) {
        return sp.getBoolean("fog_red_enabled", true);
    }

    private final Context ctx;
    private final android.content.SharedPreferences sp;
    private final WindowManager wm;
    private final Handler h = new Handler(Looper.getMainLooper());

    private View icon;
    private WindowManager.LayoutParams lp;
    private int screenW;
    private int screenH;
    private int size;
    private boolean showing;
    private boolean busy;
    private int downX;
    private int downY;
    private boolean dragged;

    RedButtonOverlay(Context ctx) {
        this.ctx = ctx;
        this.sp = ctx.getSharedPreferences("zeekreq", Context.MODE_PRIVATE);
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        wm.getDefaultDisplay().getMetrics(dm);
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;
    }

    // ================================================================= 顯示 / 收起

    void show() {
        if (showing) {
            return;
        }
        size = iconSizePx();
        icon = buildIcon();
        int sz = size;
        icon.setLayoutParams(new FrameLayout.LayoutParams(sz, sz));
        lp = new WindowManager.LayoutParams(
                sz, sz,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = clampX(sp.getInt("fog_fx", screenW - sz - dp(12)));
        lp.y = clampY(sp.getInt("fog_fy", (int) (screenH * 0.30)));
        try {
            wm.addView(icon, lp);
            showing = true;
            AppLog.i("RedButton", "show " + lp.x + "," + lp.y + " size=" + sz);
        } catch (Throwable t) {
            AppLog.w("RedButton", "addView failed: " + EcarxSource.describe(t));
            icon = null;
        }
    }

    void hide() {
        if (!showing) {
            return;
        }
        try {
            wm.removeView(icon);
        } catch (Throwable ignored) {
        }
        showing = false;
    }

    boolean isShowing() {
        return showing;
    }

    /** 設定頁改咗大細之後重套。 */
    void refreshSize() {
        if (!showing) {
            return;
        }
        int sz = iconSizePx();
        if (sz == size) {
            return;
        }
        size = sz;
        icon.setLayoutParams(new FrameLayout.LayoutParams(sz, sz));
        lp.width = sz;
        lp.height = sz;
        lp.x = clampX(lp.x);
        lp.y = clampY(lp.y);
        try {
            wm.updateViewLayout(icon, lp);
        } catch (Throwable ignored) {
        }
    }

    // ================================================================= 樣式：深底＋霓虹紅光環＋紅核

    private View buildIcon() {
        FrameLayout root = new FrameLayout(ctx);
        View glow = new View(ctx);
        glow.setBackground(ring(dp(4), 0xFF2A0A0E, 0xFFFF3B4E));
        View core = new View(ctx);
        int coreDp = size / 3;
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(coreDp, coreDp);
        cp.gravity = Gravity.CENTER;
        core.setLayoutParams(cp);
        core.setBackground(disc(0xFFFF3B4E, 0xFFB01225));
        root.addView(glow, new FrameLayout.LayoutParams(-1, -1));
        root.addView(core);
        root.setAlpha(0.94f);
        attachTouch(root);
        return root;
    }

    private GradientDrawable ring(int inset, int fillColor, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(fillColor);
        g.setAlpha(150);
        g.setStroke(Math.max(2, dp(2)), strokeColor);
        return g;
    }

    private GradientDrawable disc(int from, int to) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColors(new int[]{from, to});
        g.setOrientation(GradientDrawable.Orientation.TL_BR);
        return g;
    }

    // ================================================================= 觸控：拖 + 撳

    @SuppressLint("ClickableViewAccessibility")
    private void attachTouch(View v) {
        v.setOnTouchListener((view, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = (int) ev.getRawX();
                    downY = (int) ev.getRawY();
                    dragged = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = (int) ev.getRawX() - downX;
                    int dy = (int) ev.getRawY() - downY;
                    if (dragged || Math.abs(dx) > dp(8) || Math.abs(dy) > dp(8)) {
                        dragged = true;
                        lp.x = clampX(lp.x + dx);
                        lp.y = clampY(lp.y + dy);
                        try {
                            wm.updateViewLayout(icon, lp);
                        } catch (Throwable ignored) {
                        }
                        downX = (int) ev.getRawX();
                        downY = (int) ev.getRawY();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!dragged) {
                        sp.edit().putInt("fog_fx", lp.x).putInt("fog_fy", lp.y).apply();
                        onTapped();
                    } else {
                        sp.edit().putInt("fog_fx", lp.x).putInt("fog_fy", lp.y).apply();
                    }
                    return true;
                default:
                    return false;
            }
        });
    }

    // ================================================================= 撳 = 21050500 開關三次

    private void onTapped() {
        if (busy) {
            return;
        }
        busy = true;
        pulse();
        h.post(this::runToggles);
    }

    /**
     * 一下 = 開關三次（v12.22 安全循環）：讀現值 → 1→0→1→0→1→0，
     * 每步寫完等 350 ms 讀返核對，收工同 log 全落 AppLog。
     */
    private void runToggles() {
        final long onMs = onMs(sp), offMs = offMs(sp);
        final int n = count(sp);
        new Thread(() -> {
            int cur = readFog();
            StringBuilder log = new StringBuilder("fog run before=").append(cur)
                    .append(" on=").append(onMs).append(" off=").append(offMs).append(" n=").append(n);
            for (int i = 0; i < n; i++) {
                boolean ok = writeFog(1);                        // 閃開
                sleepMs(onMs);
                int mid = readFog();
                boolean ok2 = writeFog(0);                       // 閃關
                sleepMs(offMs);
                int after = readFog();
                log.append(" | w1").append(ok ? "y" : "n").append("->").append(mid)
                        .append(" w0").append(ok2 ? "y" : "n").append("->").append(after);
            }
            busy = false;
            AppLog.i("RedButton", log.toString());
        }, "fog-toggle").start();
    }

    private static void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ================================================================= ECARX 反射（v12.22 path）

    private int readFog() {
        try {
            Object fn = icarFunction();
            if (fn == null) {
                return -1;
            }
            Method m = EcarxSource.findMethod(fn, "getFunctionValue", int.class);
            if (m == null) {
                return -1;
            }
            Object r = m.invoke(fn, FOG_ID);
            return r instanceof Number ? ((Number) r).intValue() : -1;
        } catch (Throwable t) {
            AppLog.w("RedButton", "read failed: " + EcarxSource.describe(t));
            return -1;
        }
    }

    private boolean writeFog(int value) {
        try {
            Object fn = icarFunction();
            if (fn == null) {
                return false;
            }
            Method m = EcarxSource.findMethod(fn, "setFunctionValue", int.class, int.class);
            if (m == null) {
                AppLog.w("RedButton", "no setFunctionValue(int,int)");
                return false;
            }
            m.invoke(fn, FOG_ID, value);
            return true;
        } catch (Throwable t) {
            AppLog.w("RedButton", "write failed: " + EcarxSource.describe(t));
            return false;
        }
    }

    private Object carObj;
    private boolean carTried;

    /** Car.create → getICarFunction；整個 process 只建一次（要一秒）。 */
    private Object icarFunction() {
        if (carTried && carObj != null) {
            try {
                Object fn = EcarxSource.call(carObj, "getICarFunction");
                if (fn != null) {
                    return fn;
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            Class<?> cls = Class.forName(EcarxSource.CAR_CLASS);
            Method create = EcarxSource.match(cls.getMethods(), "create", Context.class);
            if (create == null) {
                return null;
            }
            carObj = create.invoke(null, ctx);
            carTried = true;
            return carObj == null ? null : EcarxSource.call(carObj, "getICarFunction");
        } catch (Throwable t) {
            AppLog.w("RedButton", "car failed: " + EcarxSource.describe(t));
            carTried = true;
            return null;
        }
    }

    // ================================================================= 撳落閃一下

    private void pulse() {
        if (icon == null) {
            return;
        }
        ValueAnimator a = ValueAnimator.ofFloat(0.94f, 0.45f, 0.94f);
        a.setDuration(onMs(sp) + offMs(sp));
        a.addUpdateListener(anim -> icon.setAlpha((Float) anim.getAnimatedValue()));
        a.start();
    }

    // ================================================================= 工具

    private int iconSizePx() {
        int idp = sp.getInt("icon_dp", 0);
        return idp > 0 ? dp(idp) : autoIconSize();
    }

    /** 同 FloatEqService.autoIconSize 一樣：短邊 7.5%，夾喺 48–84dp。 */
    private int autoIconSize() {
        int s = (int) (Math.min(screenW, screenH) * 0.075f);
        return Math.max(dp(48), Math.min(dp(84), s));
    }

    private int clampX(int x) {
        return Math.max(0, Math.min(screenW - size, x));
    }

    private int clampY(int y) {
        return Math.max(0, Math.min(screenH - size, y));
    }

    private int dp(int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    // ============================================================ BLE 手勢觸發（static 路徑）

    private static Object sCar;
    private static boolean sTried;

    /** BLE 手勢「紅掣」：同浮窗紅掣完全一樣（21050500 閃 N 吓，閃開/閃關可調）。 */
    static void fireRedButton(Context c) {
        final Context ctx = c.getApplicationContext();
        final android.content.SharedPreferences sp =
                ctx.getSharedPreferences("zeekreq", Context.MODE_PRIVATE);
        final long onMs = onMs(sp), offMs = offMs(sp);
        final int n = count(sp);
        new Thread(() -> {
            int cur = sReadFog(ctx);
            StringBuilder log = new StringBuilder("fog run(BLE) before=").append(cur)
                    .append(" on=").append(onMs).append(" off=").append(offMs).append(" n=").append(n);
            for (int i = 0; i < n; i++) {
                boolean ok = sWriteFog(ctx, 1);                  // 閃開
                sleepMs(onMs);
                int mid = sReadFog(ctx);
                boolean ok2 = sWriteFog(ctx, 0);                 // 閃關
                sleepMs(offMs);
                int after = sReadFog(ctx);
                log.append(" | w1").append(ok ? "y" : "n").append("->").append(mid)
                        .append(" w0").append(ok2 ? "y" : "n").append("->").append(after);
            }
            AppLog.i("RedButton", log.toString());
        }, "fog-toggle-ble").start();
    }

    private static int sReadFog(Context ctx) {
        try {
            Object fn = sIcarFunction(ctx);
            if (fn == null) {
                return -1;
            }
            Method m = EcarxSource.findMethod(fn, "getFunctionValue", int.class);
            if (m == null) {
                return -1;
            }
            Object r = m.invoke(fn, FOG_ID);
            return r instanceof Number ? ((Number) r).intValue() : -1;
        } catch (Throwable t) {
            AppLog.w("RedButton", "ble read failed: " + EcarxSource.describe(t));
            return -1;
        }
    }

    private static boolean sWriteFog(Context ctx, int value) {
        try {
            Object fn = sIcarFunction(ctx);
            if (fn == null) {
                return false;
            }
            Method m = EcarxSource.findMethod(fn, "setFunctionValue", int.class, int.class);
            if (m == null) {
                AppLog.w("RedButton", "ble no setFunctionValue(int,int)");
                return false;
            }
            m.invoke(fn, FOG_ID, value);
            return true;
        } catch (Throwable t) {
            AppLog.w("RedButton", "ble write failed: " + EcarxSource.describe(t));
            return false;
        }
    }

    /** 同 icarFunction 一樣（Car.create → getICarFunction）；static 路徑用自己嘅 cache。 */
    private static Object sIcarFunction(Context ctx) {
        if (sTried && sCar != null) {
            try {
                Object fn = EcarxSource.call(sCar, "getICarFunction");
                if (fn != null) {
                    return fn;
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            Class<?> cls = Class.forName(EcarxSource.CAR_CLASS);
            Method create = EcarxSource.match(cls.getMethods(), "create", Context.class);
            if (create == null) {
                return null;
            }
            sCar = create.invoke(null, ctx);
            sTried = true;
            return sCar == null ? null : EcarxSource.call(sCar, "getICarFunction");
        } catch (Throwable t) {
            AppLog.w("RedButton", "ble car failed: " + EcarxSource.describe(t));
            sTried = true;
            return null;
        }
    }
}
