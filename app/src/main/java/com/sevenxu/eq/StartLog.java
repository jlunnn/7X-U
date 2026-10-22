package com.sevenxu.eq;

import android.content.Context;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 自啟記錄：邊個機制、幾點叫起過浮窗服務（只存本機）。 */
public final class StartLog {

    private static final int MAX = 40;
    private static final String KEY = "start_log";

    private StartLog() {
    }

    public static synchronized void note(Context c, String reason) {
        try {
            String ts = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date());
            String line = ts + "  " + reason;
            String old = c.getSharedPreferences("zeekreq", Context.MODE_PRIVATE)
                    .getString(KEY, "");
            StringBuilder sb = new StringBuilder(line);
            int count = 1;
            if (old != null && !old.isEmpty()) {
                for (String s : old.split("\n")) {
                    if (s.isEmpty() || count >= MAX) {
                        continue;
                    }
                    sb.append('\n').append(s);
                    count++;
                }
            }
            c.getSharedPreferences("zeekreq", Context.MODE_PRIVATE)
                    .edit().putString(KEY, sb.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    public static synchronized String summary(Context c) {
        String s = c.getSharedPreferences("zeekreq", Context.MODE_PRIVATE).getString(KEY, "");
        return (s == null || s.isEmpty()) ? "（暫時未有記錄）" : s;
    }

    public static synchronized void clear(Context c) {
        c.getSharedPreferences("zeekreq", Context.MODE_PRIVATE).edit().remove(KEY).apply();
    }
}
