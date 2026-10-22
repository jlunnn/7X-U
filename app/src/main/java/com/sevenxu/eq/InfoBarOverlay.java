package com.sevenxu.eq;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 7X info bar（獨立懸浮）：車速 ＋ 時間；可自由拖動並記住位置。
 */
public class InfoBarOverlay {

    private final Context ctx;
    private final SharedPreferences sp;
    private final WindowManager wm;
    private View root;
    private TextView leftTv, rightTv;
    private WindowManager.LayoutParams lp;
    private LocationManager lm;
    private float kmh = -1f;
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean running = false;

    public InfoBarOverlay(Context c) {
        this.ctx = c.getApplicationContext();
        this.sp = ctx.getSharedPreferences("zeekreq", Context.MODE_PRIVATE);
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        this.lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
    }

    public static boolean enabled(SharedPreferences sp) {
        return sp.getBoolean("info_bar", true);
    }

    @SuppressLint("MissingPermission")
    public void show() {
        if (running || wm == null) {
            return;
        }
        try {
            int w = dp(Math.max(180, Math.min(420, sp.getInt("info_w", 280))));
            int hgt = dp(Math.max(40, Math.min(90, sp.getInt("info_h", 54))));

            LinearLayout bar = new LinearLayout(ctx);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.setPadding(dp(14), 0, dp(14), 0);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xE60D110D);
            bg.setCornerRadius(dp(12));
            bg.setStroke(Math.max(1, dp(2)), 0xFF2C3526);
            bar.setBackground(bg);

            leftTv = new TextView(ctx);
            leftTv.setTextColor(0xFFB6FF3B);
            leftTv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 19);
            leftTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            leftTv.setText("-- km/h");

            rightTv = new TextView(ctx);
            rightTv.setTextColor(0xFFA0A89A);
            rightTv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17);
            rightTv.setGravity(Gravity.END);

            LinearLayout.LayoutParams lpL = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            bar.addView(leftTv, lpL);
            bar.addView(rightTv, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            lp = new WindowManager.LayoutParams(w, hgt,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    android.graphics.PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            int screenW = ctx.getResources().getDisplayMetrics().widthPixels;
            lp.x = sp.getInt("info_fx", Math.max(0, (screenW - w) / 2));
            lp.y = sp.getInt("info_fy", dp(24));

            bar.setOnTouchListener(new View.OnTouchListener() {
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
                                sp.edit().putInt("info_fx", lp.x).putInt("info_fy", lp.y).apply();
                            }
                            return true;
                        default:
                            return false;
                    }
                }
            });

            wm.addView(bar, lp);
            root = bar;
            running = true;
            startGps();
            try {
                Telemetry.get().acquire(ctx, "7X=U-info");   // 起 ECARX 訊號源
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
            if (lm != null) {
                lm.removeUpdates(listener);
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

    private final android.location.LocationListener listener = new android.location.LocationListener() {
        @Override
        public void onLocationChanged(Location l) {
            if (l == null) {
                return;
            }
            kmh = l.hasSpeed() ? Math.max(0f, l.getSpeed() * 3.6f) : -1f;
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
            if (lm != null) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 800, 0f, listener, Looper.getMainLooper());
            }
        } catch (Throwable ignored) {
        }
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
        if (leftTv == null) {
            return;
        }
        int threshold = sp.getInt("speed_limit", 80);
        boolean over = kmh >= 0 && kmh > threshold && sp.getBoolean("speed_alert", true);
        leftTv.setText(kmh < 0 ? "-- km/h" : (Math.round(kmh) + " km/h"));
        leftTv.setTextColor(over ? 0xFFFF2D55 : 0xFFB6FF3B);
        // ECARX 實時數據（失敗唔影響車速顯示）
        try {
            VehicleState st = Telemetry.get().latest();
            if (st != null) {
                StringBuilder sb = new StringBuilder();
                if (st.gear != null && !st.gear.isEmpty()) sb.append(st.gear).append("  ");
                if (st.throttle != null) sb.append("油門 ").append(Math.round(st.throttle * 100)).append("%  ");
                if (st.brake != null) sb.append("腳煞 ").append(Math.round(st.brake * 100)).append("%");
                if (sb.length() > 0) {
                    leftTv.setText(sb.toString().trim());
                    leftTv.setTextColor(over ? 0xFFFF2D55 : 0xFFB6FF3B);
                    leftTv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
                }
            }
        } catch (Throwable ignored) {
        }
        if (rightTv != null) {
            rightTv.setText((kmh < 0 ? "--" : String.valueOf(Math.round(kmh))) + " km/h · "
                    + new SimpleDateFormat("HH:mm", Locale.US).format(new Date()));
        }
    }

    private int dp(int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }
}
