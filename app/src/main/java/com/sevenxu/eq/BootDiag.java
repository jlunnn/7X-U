package com.sevenxu.eq;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 開機／著車套用 EQ 嘅本機診斷記錄（**完全唔上網、唔上報**）。
 *
 * <p>目的：車機要靠「開機自動套用」先有聲，但呢個動作係背景發生、車主睇唔到。
 * 所以每次嘗試都記低：時間、來源（開機廣播／著車喚醒）、成功定失敗、用邊個引擎、失敗原因。
 * 設定頁會原樣顯示，方便一眼睇到「今次著車有冇套用成功」。</p>
 */
public final class BootDiag {

    private static final String P = "zeekreq";
    private static final String K_COUNT = "bd_count";
    private static final String K_OK_COUNT = "bd_ok_count";
    private static final String K_TS = "bd_ts";
    private static final String K_OK = "bd_ok";
    private static final String K_MSG = "bd_msg";
    private static final String K_SRC = "bd_src";
    private static final String K_ENGINE = "bd_engine";

    private BootDiag() {
    }

    /** 記一筆。src = "boot"（BOOT_COMPLETED）／"wake"（著車喚醒）；msg 只放中性原因，唔放網址／IP。 */
    public static void record(Context c, String src, boolean ok, String engine, String msg) {
        try {
            SharedPreferences sp = c.getSharedPreferences(P, Context.MODE_PRIVATE);
            int n = sp.getInt(K_COUNT, 0) + 1;
            int okN = sp.getInt(K_OK_COUNT, 0) + (ok ? 1 : 0);
            sp.edit()
                    .putInt(K_COUNT, n)
                    .putInt(K_OK_COUNT, okN)
                    .putLong(K_TS, System.currentTimeMillis())
                    .putBoolean(K_OK, ok)
                    .putString(K_MSG, msg == null ? "" : trim(msg, 160))
                    .putString(K_SRC, src == null ? "" : src)
                    .putString(K_ENGINE, engine == null ? "" : trim(engine, 60))
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    /** 設定頁顯示用：多行純文字；未試過就講清楚未試過。 */
    public static String summary(Context c) {
        try {
            SharedPreferences sp = c.getSharedPreferences(P, Context.MODE_PRIVATE);
            int n = sp.getInt(K_COUNT, 0);
            if (n == 0) return "未試過開機套用（重啟車機或再開一次 app 就會有記錄）";
            long ts = sp.getLong(K_TS, 0);
            boolean ok = sp.getBoolean(K_OK, false);
            int okN = sp.getInt(K_OK_COUNT, 0);
            String when = ts == 0 ? "—"
                    : new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date(ts));
            String src = sp.getString(K_SRC, "");
            String srcTxt = "boot".equals(src) ? "開機廣播" : ("wake".equals(src) ? "著車喚醒" : src);
            String eng = sp.getString(K_ENGINE, "");
            String msg = sp.getString(K_MSG, "");

            StringBuilder sb = new StringBuilder();
            sb.append(ok ? "上次：成功" : "上次：失敗");
            sb.append("　").append(when).append("（").append(srcTxt).append("）");
            if (!eng.isEmpty()) sb.append("\n引擎：").append(eng);
            if (!msg.isEmpty()) sb.append("\n細節：").append(msg);
            sb.append("\n累計 ").append(n).append(" 次").append("（成功 ").append(okN).append("）");
            return sb.toString();
        } catch (Throwable t) {
            return "讀取診斷失敗：" + t.getClass().getSimpleName();
        }
    }

    public static void clear(Context c) {
        try {
            c.getSharedPreferences(P, Context.MODE_PRIVATE).edit()
                    .remove(K_COUNT).remove(K_OK_COUNT).remove(K_TS).remove(K_OK)
                    .remove(K_MSG).remove(K_SRC).remove(K_ENGINE).apply();
        } catch (Throwable ignored) {
        }
    }

    private static String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
