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
 * 唔可以當保證 —— UI 要老實講，而且每次嘗試都記入 {@link BootDiag}（本機，唔上報）。</p>
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context c, Intent intent) {
        String action = (intent == null || intent.getAction() == null) ? "?" : intent.getAction();
        try {
            SharedPreferences sp = c.getSharedPreferences("zeekreq", Context.MODE_PRIVATE);
            // 懸浮窗上次係開住 → 一齊回復（位置／大細已經存喺 prefs，會自動跟返）
            sp.edit().putBoolean("ffull", false).putBoolean("fmin", true).apply();   // 開機只出圓掣
            if (sp.getBoolean("float_on", true)) {   // 預設開：圓掣長期顯示
                try {
                    FloatEqService.show(c);
                } catch (Throwable ignored) {
                }
            }
            if (!sp.getBoolean("boot_auto", true)) {   // 預設開
                BootDiag.record(c, "boot", true, "—", "開機自動套用已關閉，冇套用");
                return;
            }
            boolean halOnly = false;                    // 唔再用車機原生 EQ 引擎
            float[] gains = Presets.parseCsv(sp.getString("gains", ""));
            double pre = sp.getInt("preamp", 12) - 12;

            SysEq sys = EqService.get(c);
            boolean ok = sys.ready() || sys.attach(halOnly);
            if (!ok) {
                String why = "掛唔到系統 EQ（" + sys.status() + "）";
                AppLog.e("EQ", "開機自動開：" + why);
                BootDiag.record(c, "boot", false, "—", why);
                return;
            }
            EqService.start(c);
            sys.setEnabled(true);
            String res = sys.applyCurve(gains, pre, false);
            String eng = sys.isCustom() ? ("自訂引擎 " + sys.bands() + " 段") : ("車機原生 " + sys.bands() + " 段");
            AppLog.d("EQ", "開機自動開：" + sys.status() + " ｜ " + res
                    + " ｜ 觸發=" + action);
            BootDiag.record(c, "boot", isApplied(res), eng, res);
        } catch (Throwable t) {
            AppLog.e("EQ", "開機自動開失敗：" + t);
            BootDiag.record(c, "boot", false, "—", "例外：" + t.getClass().getSimpleName());
        }
    }

    /** applyCurve() 回傳字串係成唔成功（"已套用" / "✅ …" = 成功；含「失敗／未掛上／段數唔對」= 失敗）。 */
    static boolean isApplied(String res) {
        if (res == null || res.isEmpty()) return false;
        return !(res.contains("失敗") || res.contains("未掛上") || res.contains("段數唔對")
                || res.contains("未套用"));
    }
}
