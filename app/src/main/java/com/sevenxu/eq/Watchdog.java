package com.sevenxu.eq;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;

/**
 * 看門狗：每 60 秒自我喚醒一次，浮窗服務唔在生就自動起返（只出圓掣）。
 * 用 AlarmManager.setAndAllowWhileIdle（唔受 doze／app standby 影響），比 START_STICKY 可靠。
 */
public class Watchdog extends BroadcastReceiver {

    private static final int REQ = 7391;

    @Override
    public void onReceive(Context c, Intent intent) {
        try {
            boolean want = c.getSharedPreferences("zeekreq", Context.MODE_PRIVATE)
                    .getBoolean("float_on", true);
            if (want) {
                if (Build.VERSION.SDK_INT >= 26) {
                    Intent si = new Intent(c, FloatEqService.class);
                    si.putExtra("why", "watchdog");
                    c.startForegroundService(si);
                } else {
                    Intent si2 = new Intent(c, FloatEqService.class);
                    si2.putExtra("why", "watchdog");
                    c.startService(si2);
                }
            }
        } catch (Throwable ignored) {
        }
        schedule(c);
        BootJob.schedule(c);
    }

    /** 排下一次（60 秒後）。任何入口都可以叫。 */
    public static void schedule(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getBroadcast(c, REQ,
                    new Intent(c, Watchdog.class), flags);
            long at = SystemClock.elapsedRealtime() + 60_000L;
            if (Build.VERSION.SDK_INT >= 23) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            }
        } catch (Throwable ignored) {
        }
    }
}
