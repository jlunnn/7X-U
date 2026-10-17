package com.sevenxu.eq;

import java.util.ArrayDeque;

/** 極簡 log（Logcat + 最後幾行，畀狀態行顯示錯誤用）。 */
public final class AppLog {

    private static final int MAX = 4;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();

    private AppLog() {
    }

    public static void d(String tag, String msg) {
        android.util.Log.d("ZeekrEq/" + tag, msg);
        push(msg);
    }

    public static void e(String tag, String msg) {
        android.util.Log.e("ZeekrEq/" + tag, msg);
        push(msg);
    }

    private static void push(String msg) {
        synchronized (LINES) {
            LINES.addLast(msg);
            while (LINES.size() > MAX) LINES.removeFirst();
        }
    }

    public static String tail() {
        synchronized (LINES) {
            StringBuilder sb = new StringBuilder();
            for (String s : LINES) {
                if (sb.length() > 0) sb.append(" ｜ ");
                sb.append(s);
            }
            return sb.toString();
        }
    }
}
