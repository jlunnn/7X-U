package com.sevenxu.eq;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * 揸住系統（媒體音樂）EQ 嘅前台服務。
 *
 * <p>點解要服務：{@link SysEq} 掛嘅 audio effect 只喺 app 進程活住嗰陣有效 ——
 * 用戶撳完 EQ 會轉去聽音樂（app 退到背景），冇前台服務就隨時被系統殺 → EQ 靜靜還原。</p>
 *
 * <p>⚠️ 車機容器未必准 promote 前台服務：startForeground 失敗唔可以當冇事，
 * 要喺畫面老實講「只能開住 app 時生效」。</p>
 */
public final class EqService extends Service {

    public static volatile boolean RUNNING;
    public static volatile String LAST_ERROR = "";

    private static SysEq sysEq;

    private static final String CH = "sevenxu_eq_sys";
    private static final int NOTI_ID = 41;

    /** 全 app 共用一個 SysEq（Activity 同 Service 揸住同一個）。 */
    public static synchronized SysEq get(Context c) {
        if (sysEq == null) sysEq = SysEq.create();
        return sysEq;
    }

    public static synchronized void drop() {
        if (sysEq != null) sysEq.release();
        sysEq = null;
    }

    public static void start(Context c) {
        try {
            Intent i = new Intent(c, EqService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Throwable t) {
            LAST_ERROR = "起服務失敗：" + t.getClass().getSimpleName();
            AppLog.e("EQ", LAST_ERROR);
        }
    }

    public static void stop(Context c) {
        try {
            c.stopService(new Intent(c, EqService.class));
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        RUNNING = true;
        foreground();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        RUNNING = false;
        super.onDestroy();
    }

    /** 前台通知（靜音、低優先，唔彈頭）。 */
    private void foreground() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(CH, "EQ 狀態",
                        NotificationManager.IMPORTANCE_MIN);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            PendingIntent pi = PendingIntent.getActivity(this, 0,
                    new Intent(this, MainActivity.class),
                    Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
            Notification n = new Notification.Builder(this, CH)
                    .setContentTitle("7X=U")
                    .setContentText("媒體音樂 EQ 生效中")
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setOngoing(true)
                    .setContentIntent(pi)
                    .build();
            startForeground(NOTI_ID, n);
            LAST_ERROR = "";
        } catch (Throwable t) {
            // 容器唔准前台服務 → 只在 app 開住時有效，要畫面顯示，唔可以靜默
            LAST_ERROR = "前台服務開唔到（" + t.getClass().getSimpleName() + "）→ 只喺 app 開住時生效";
            AppLog.e("EQ", LAST_ERROR);
        }
    }
}
