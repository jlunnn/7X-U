package com.sevenxu.eq;

/**
 * 真 limiter：完全照第三方 EQ v0.30.1 嘅設定（反編譯 a/AbstractC0001b.java）。
 *   new DynamicsProcessing(0, 0, config)   // priority 0, session 0（全局媒體）
 *   new Limiter(true, true, 0, 1.0f, 60.0f, 10.0f, -1.0f, 0.0f)
 *   setLimiterAllChannelsTo(...)           // 全部聲道（唔會再出現右聲道靜音）
 */
public final class DynLimiter {
    private static android.media.audiofx.DynamicsProcessing dp;
    /** DP limiter 是否真正建立成功（失敗就交返 SysEq 靜態保護）。 */
    public static volatile boolean available = false;
    public static volatile boolean on = false;

    private DynLimiter() {}

    public static synchronized boolean set(boolean enable) {
        on = enable;
        if (enable && dp == null) init();
        if (dp == null) return false;
        try {
            dp.setEnabled(enable);
            return true;
        } catch (Throwable t) {
            android.util.Log.w("7XU", "DynLimiter.set " + t.getClass().getSimpleName());
            return false;
        }
    }

    private static void init() {
        try {
            // 只有 limiter：preEq off / mbc off / postEq off（唔同 14 段系統 EQ 打架）
            android.media.audiofx.DynamicsProcessing.Config cfg =
                    new android.media.audiofx.DynamicsProcessing.Config.Builder(
                            0, 2, false, 0, false, 0, false, 0, true).build();
            dp = new android.media.audiofx.DynamicsProcessing(0, 0, cfg);
            dp.setLimiterAllChannelsTo(new android.media.audiofx.DynamicsProcessing.Limiter(
                    true, true, 0, 1.0f, 60.0f, 10.0f, -1.0f, 0.0f));
            available = true;
        } catch (Throwable t) {
            android.util.Log.w("7XU", "DynLimiter.init " + t.getClass().getSimpleName());
            dp = null;
            available = false;
        }
    }

    public static synchronized void release() {
        try {
            if (dp != null) {
                dp.setEnabled(false);
                dp.release();
            }
        } catch (Throwable ignored) {
        }
        dp = null;
        available = false;
        on = false;
    }
}
