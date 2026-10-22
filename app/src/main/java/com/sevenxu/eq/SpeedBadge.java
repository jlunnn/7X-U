package com.sevenxu.eq;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationManager;
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
 * 車速圓牌（懸浮）：跟住圓掣旁邊顯示 GPS 車速；超過門檻變紅＋閃。
 * 只用 GPS（LocationManager），唔需要車機權限。
 */
public class SpeedBadge {

    private final Context ctx;
    private final SharedPreferences sp;
    private final WindowManager wm;
    private View root;
    private TextView numTv;
    private WindowManager.LayoutParams lp;
    private LocationManager lm;
    private float kmh = -1f;
    private long lastFixAt = 0;
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean running = false;

    public SpeedBadge(Context c) {
        this.ctx = c.getApplicationContext();
        this.sp = ctx.getSharedPreferences("zeekreq", Context.MODE_PRIVATE);
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        this.lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
    }

    public static boolean enabled(SharedPreferences sp) {
        return sp.getBoolean("speed_badge", true);
    }

    /** 建窗（在圓掣右邊／左邊，按圓掣位置自動避開）。 */
    @SuppressLint("MissingPermission")
    public void show() {
        if (running) {
            return;
        }
        if (wm == null) {
            return;
        }
        try {
            int size = dp(sp.getInt("speed_size", 100));          // 100% = 74dp
            int w = Math.max(60, size);
            int hgt = Math.max(60, size);

            LinearLayout box = new LinearLayout(ctx);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xE60D110D);
            bg.setCornerRadius(w / 2f);
            bg.setStroke(Math.max(2, dp(3)), 0xFF2C3526);
            box.setBackground(bg);

            numTv = new TextView(ctx);
            numTv.setTextColor(0xFFFFFFFF);
            numTv.setGravity(Gravity.CENTER);
            numTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            numTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(18, w * 0.40f));
            numTv.setText("--");
            box.addView(numTv, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            TextView unit = new TextView(ctx);
            unit.setText("km/h");
            unit.setTextColor(0xFFA0A89A);
            unit.setGravity(Gravity.CENTER);
            unit.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(9, w * 0.16f));
            box.addView(unit);

            lp = new WindowManager.LayoutParams(w, hgt,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    android.graphics.PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            // 跟圓掣位置：圓掣右邊；貼右邊就放左邊
            int iconSize = sp.getInt("icon_dp", 0) > 0 ? dp(sp.getInt("icon_dp", 0)) : dp(84);
            int ix = sp.getInt("icon_fx", 40);
            int iy = sp.getInt("icon_fy", 400);
            int screenW = wm.getDefaultDisplay() != null ? ctx.getResources().getDisplayMetrics().widthPixels : 1080;
            boolean putLeft = (ix + iconSize + w + dp(16)) > screenW;
            lp.x = putLeft ? Math.max(0, ix - w - dp(10)) : Math.min(screenW - w, ix + iconSize + dp(10));
            lp.y = Math.max(0, iy + (iconSize - hgt) / 2);
            // 用家拖過就以記住嘅位置為準
            if (sp.contains("speed_fx")) {
                lp.x = sp.getInt("speed_fx", lp.x);
                lp.y = sp.getInt("speed_fy", lp.y);
            }

            box.setOnTouchListener(new View.OnTouchListener() {
                float speedDx, speedDy;
                int sx, sy;
                boolean moved;

                @Override
                public boolean onTouch(View v, MotionEvent e) {
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            speedDx = e.getRawX();
                            speedDy = e.getRawY();
                            sx = lp.x;
                            sy = lp.y;
                            moved = false;
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            lp.x = sx + (int) (e.getRawX() - speedDx);
                            lp.y = sy + (int) (e.getRawY() - speedDy);
                            moved = true;
                            try {
                                wm.updateViewLayout(root, lp);
                            } catch (Throwable ignored) {
                            }
                            return true;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            if (moved) {
                                sp.edit().putInt("speed_fx", lp.x).putInt("speed_fy", lp.y).apply();
                            }
                            return true;
                        default:
                            return false;
                    }
                }
            });
            wm.addView(box, lp);
            root = box;
            running = true;
            startGps();
        } catch (Throwable ignored) {
        }
    }

    public void hide() {
        running = false;
        try {
            if (lm != null) {
                lm.removeUpdates(listener);
            }
        } catch (Throwable ignored) {
        }
        try {
            if (root != null) {
                wm.removeView(root);
            }
        } catch (Throwable ignored) {
        }
        root = null;
    }

    /** 圓掣移動後，車速牌跟住行。 */
    public void follow() {
        if (!running || lp == null || root == null) {
            return;
        }
        try {
            int w = lp.width;
            int iconSize = sp.getInt("icon_dp", 0) > 0 ? dp(sp.getInt("icon_dp", 0)) : dp(84);
            int ix = sp.getInt("icon_fx", 40);
            int iy = sp.getInt("icon_fy", 400);
            int screenW = ctx.getResources().getDisplayMetrics().widthPixels;
            boolean putLeft = (ix + iconSize + w + dp(16)) > screenW;
            lp.x = putLeft ? Math.max(0, ix - w - dp(10)) : Math.min(screenW - w, ix + iconSize + dp(10));
            lp.y = Math.max(0, iy + (iconSize - lp.height) / 2);
            wm.updateViewLayout(root, lp);
        } catch (Throwable ignored) {
        }
    }

    private final android.location.LocationListener listener = new android.location.LocationListener() {
        @Override
        public void onLocationChanged(Location l) {
            if (l == null) {
                return;
            }
            if (l.hasSpeed()) {
                kmh = Math.max(0f, l.getSpeed() * 3.6f);
            } else {
                kmh = -1f;
            }
            lastFixAt = System.currentTimeMillis();
            render();
        }

        @Override
        public void onProviderEnabled(String p) {
        }

        @Override
        public void onProviderDisabled(String p) {
        }

        @Override
        public void onStatusChanged(String p, int s, android.os.Bundle b) {
        }
    };

    @SuppressLint("MissingPermission")
    private void startGps() {
        try {
            if (lm == null) {
                return;
            }
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500, 0f, listener, Looper.getMainLooper());
        } catch (Throwable ignored) {
        }
        h.post(tick);
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
            h.postDelayed(this, 1000);
        }
    };

    private void render() {
        if (numTv == null || sp == null) {
            return;
        }
        boolean stale = (System.currentTimeMillis() - lastFixAt) > 8000L;
        int threshold = sp.getInt("speed_limit", 80);
        boolean over = kmh >= 0 && kmh > threshold && !stale
                && sp.getBoolean("speed_alert", true);

        if (kmh < 0 || stale) {
            numTv.setText("--");
            numTv.setTextColor(0xFFA0A89A);
        } else {
            numTv.setText(String.valueOf(Math.round(kmh)));
            if (over) {
                // 超速：紅字 ＋ 閃（每 500ms 切換 alpha）
                boolean on = (System.currentTimeMillis() / 500L) % 2 == 0;
                numTv.setTextColor(on ? 0xFFFF2D55 : 0xFFFFFFFF);
                if (root != null) {
                    GradientDrawable bg = new GradientDrawable();
                    bg.setColor(0xE60D110D);
                    bg.setCornerRadius(lp.width / 2f);
                    bg.setStroke(Math.max(2, dp(3)), 0xFFFF2D55);
                    root.setBackground(bg);
                }
            } else {
                numTv.setTextColor(0xFFFFFFFF);
                if (root != null) {
                    GradientDrawable bg = new GradientDrawable();
                    bg.setColor(0xE60D110D);
                    bg.setCornerRadius(lp.width / 2f);
                    bg.setStroke(Math.max(1, dp(2)), 0xFF2C3526);
                    root.setBackground(bg);
                }
            }
        }
    }

    private int dp(int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }
}
