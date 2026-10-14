package com.sevenxu.eq;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * 開機自動開 EQ（用戶可選）。
 *
 * <p>⚠️ 本車機容器（App Lab）**唔一定派 `BOOT_COMPLETED`** → 收唔到廣播就唔會自動開，
 * 用戶要手動開一次 app（開 app 一定會自動掛上）。所以呢個選項係「有就開」，
 * 唔可以當保證 —— UI 要老實講。</p>
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context c, Intent intent) {
        try {
            SharedPreferences sp = c.getSharedPreferences("sevenxu_eq", Context.MODE_PRIVATE);
            if (!sp.getBoolean("boot_auto", false)) return;
            boolean halOnly = sp.getBoolean("hal_only", false);
            float[] gains = Presets.parseCsv(sp.getString("gains", ""));
            double pre = sp.getInt("preamp", 12) - 12;

            SysEq sys = EqService.get(c);
            boolean ok = sys.ready() || sys.attach(halOnly);
            if (!ok) {
                AppLog.e("EQ", "開機自動開：掛唔到系統 EQ（" + sys.status() + "）");
                return;
            }
            EqService.start(c);
            sys.setEnabled(true);
            String res = sys.applyCurve(gains, pre, false);
            AppLog.d("EQ", "開機自動開：" + sys.status() + " ｜ " + res
                    + " ｜ 觸發=" + (intent == null ? "?" : intent.getAction()));
        } catch (Throwable t) {
            AppLog.e("EQ", "開機自動開失敗：" + t);
        }
    }
}
