package com.sevenxu.eq;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 藍牙 Zeekr button 常駐監聽 ＋ 手勢 → 動作（由 CarPiano AdvButton/GestureMap 移植）。
 *
 * <p>只聽廣播唔連接（唔搶車機配對）：綁定 MAC 後常駐 BLE 掃描，
 * 手勢由廣播資料 FF 後第 3 byte 判定（00 單擊／02 雙擊／01 長按）。
 * 動作包括紅掣（同浮窗紅掣完全一樣）、EQ 開關、Bass Boost、Limiter、A/B/C 曲線等。</p>
 */
final class BtBridge {

    /** 手勢 index（長按 3.13 起停用：廣播照收、顯示，但唔觸發動作）。 */
    static final int SINGLE = 0;
    static final int DOUBLE = 1;
    static final String[] GESTURE_NAMES = {"單擊", "雙擊"};

    /** 動作清單（index 對應 LABELS）。3.14 起只剩紅掣一項。 */
    static final String[] ACTIONS = {
            "red_button",     // 紅掣（後霧燈快掣）
    };
    static final String[] LABELS = {
            "🔴 紅掣",
    };

    private static final String PF = "zeekreq";       // 同 app 共用 prefs（MAC／開關）
    private static final String GP = "zeekreq_bt";    // 手勢 → 動作

    static final int MAXB = 2;                        // 最多綁 2 粒 button（3.12）
    static volatile boolean running;
    static volatile String state = "未啟動";
    static volatile String lastCode = "";
    static volatile int lastCodeSlot;                 // 最近手勢由邊粒發（0 = 粒1）
    static volatile int count;
    static volatile long lastAt;
    private static Context appCtx;
    private static final String[] lastRawCode = new String[MAXB];   // per-粒 burst 去重
    private static final long[] lastRawAt = new long[MAXB];
    // 3.15 診斷（分「button 冇出聲」vs「app 食包」）：rawCount＝綁定粒嘅所有廣播（去重前）；
    // swallowCount＝被 800ms 滑動去重窗食掉嘅連續廣播；lastRawAnyAt＝最後一次見到綁定粒廣播時間。
    static volatile long rawCount;
    static volatile long swallowCount;
    static volatile long lastRawAnyAt;

    private static SharedPreferences gp(Context c) {
        return c.getSharedPreferences(GP, Context.MODE_PRIVATE);
    }

    // ---- 多粒綁定（3.12）：粒1 沿用舊 keys bt_mac/bt_name（3.11 前嘅綁定零遷移自動變粒1），
    // 粒2 用 bt_mac2/bt_name2。粒 key 淨係呢幾個 method 摸 —— 改 key 要連 macAt/nameAt 一齊改。

    private static String keyMac(int slot) {
        return slot == 0 ? "bt_mac" : "bt_mac" + (slot + 1);
    }

    private static String keyName(int slot) {
        return slot == 0 ? "bt_name" : "bt_name" + (slot + 1);
    }

    /** 第 slot 粒嘅 MAC（空字串 = 呢粒未綁）。 */
    static String macAt(Context ctx, int slot) {
        try {
            return ctx.getSharedPreferences(PF, Context.MODE_PRIVATE).getString(keyMac(slot), "");
        } catch (Throwable t) {
            return "";
        }
    }

    static String nameAt(Context ctx, int slot) {
        try {
            return ctx.getSharedPreferences(PF, Context.MODE_PRIVATE).getString(keyName(slot), "");
        } catch (Throwable t) {
            return "";
        }
    }

    static void bindAt(Context ctx, int slot, String mac, String name) {
        ctx.getSharedPreferences(PF, Context.MODE_PRIVATE).edit()
                .putString(keyMac(slot), mac).putString(keyName(slot), name).apply();
    }

    static void unbindAt(Context ctx, int slot) {
        ctx.getSharedPreferences(PF, Context.MODE_PRIVATE).edit()
                .remove(keyMac(slot)).remove(keyName(slot)).apply();
    }

    /** 已綁 MAC 清單（順序 = 粒1、粒2）。 */
    static String[] boundMacs(Context ctx) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        for (int s = 0; s < MAXB; s++) {
            String m = macAt(ctx, s);
            if (!m.isEmpty()) {
                out.add(m);
            }
        }
        return out.toArray(new String[0]);
    }

    /** 呢個 MAC 係第幾粒（-1 = 未綁）。 */
    static int slotOfMac(Context ctx, String mac) {
        if (mac == null) {
            return -1;
        }
        for (int s = 0; s < MAXB; s++) {
            String m = macAt(ctx, s);
            if (!m.isEmpty() && m.equalsIgnoreCase(mac)) {
                return s;
            }
        }
        return -1;
    }

    static boolean hasAnyBound(Context ctx) {
        return boundMacs(ctx).length > 0;
    }

    /** 下一個空位（-1 = 兩粒都滿）。 */
    static int firstFreeSlot(Context ctx) {
        for (int s = 0; s < MAXB; s++) {
            if (macAt(ctx, s).isEmpty()) {
                return s;
            }
        }
        return -1;
    }

    /** 用戶想唔想常駐監聽（預設開）。 */
    static boolean wanted(Context ctx) {
        try {
            return ctx.getSharedPreferences(PF, Context.MODE_PRIVATE).getBoolean("bt_listen", true);
        } catch (Throwable t) {
            return true;
        }
    }

    static void setWanted(Context ctx, boolean on) {
        try {
            ctx.getSharedPreferences(PF, Context.MODE_PRIVATE)
                    .edit().putBoolean("bt_listen", on).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 手勢綁定咗邊個動作（-1 = 未設定）。key = b<粒>_g<手勢>；
     * 粒1 讀唔到新 key 時 fallback 舊版 "g<手勢>"（3.11 前設定自動帶過嚟，零遷移）。 */
    static int action(Context c, int slot, int g) {
        try {
            int v = gp(c).getInt("b" + slot + "_g" + g, Integer.MIN_VALUE);
            if (v != Integer.MIN_VALUE) {
                return v;
            }
            return slot == 0 ? gp(c).getInt("g" + g, -1) : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    static void setAction(Context c, int slot, int g, int a) {
        // 同一粒入面一個動作只可以俾一個手勢用（唔同粒可以各自用同一動作）
        for (int gg = 0; gg < GESTURE_NAMES.length; gg++) {
            if (gg != g && action(c, slot, gg) == a) {
                gp(c).edit().putInt("b" + slot + "_g" + gg, -1).apply();
            }
        }
        gp(c).edit().putInt("b" + slot + "_g" + g, a).apply();
    }

    // ---- 藍牙頁「設定對象」記憶 ----

    static int loadUiSlot(Context c) {
        try {
            return gp(c).getInt("ui_slot", 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    static void saveUiSlot(Context c, int slot) {
        try {
            gp(c).edit().putInt("ui_slot", slot).apply();
        } catch (Throwable ignored) {
        }
    }

    // ============================================================ 常駐監聽

    static void start(final Context ctx) {
        if (running) {
            return;
        }
        appCtx = ctx.getApplicationContext();
        final String[] macs = boundMacs(ctx);
        if (macs.length == 0) {
            state = "未綁定";
            return;
        }
        setWanted(ctx, true);
        for (int s = 0; s < MAXB; s++) {
            lastRawCode[s] = "";
            lastRawAt[s] = 0;
        }
        rawCount = 0;                                // 3.15：開新監聽就重掃診斷數
        swallowCount = 0;
        lastRawAnyAt = 0;
        BleScan.rawError = "";                       // 清走上一輪錯誤，唔好顯示舊嘢
        state = "● 常駐監聽中（" + macs.length + " 粒）";
        running = true;
        // 3.11 教訓照守：照樣一條 null-filter 掃描 session（唔用 hardware MAC filter），
        // 多粒只係 software compare 條清單長咗 — 冇多開掃描，唔多耗電。
        BleScan.startRaw(ctx, macs, 0, (m, name, rssi, raw, at) -> {
            if (!running) {
                return;
            }
            final int slot = slotOfMac(appCtx, m);
            if (slot < 0) {
                return;                        // 唔係綁咗嘅粒（附近第粒 button／其他 BLE）→ 食掉
            }
            rawCount++;                        // 3.15 診斷：綁定粒嘅廣播（去重前）都計
            lastRawAnyAt = System.currentTimeMillis();
            String code = codeOf(raw);
            if (code.equals(lastRawCode[slot]) && System.currentTimeMillis() - lastRawAt[slot] < 800) {
                lastRawAt[slot] = System.currentTimeMillis();   // 滑動窗（per-粒）：burst 繼續就重設時限 → 單擊唔會 fire 兩次；兩粒互唔干擾
                swallowCount++;                // 3.15 診斷：呢個包被去重窗食掉
                return;                        // 同一次按壓嘅連續廣播 → 食掉（burst 去重）
            }
            lastRawCode[slot] = code;
            lastRawAt[slot] = System.currentTimeMillis();
            count++;
            lastAt = lastRawAt[slot];
            lastCode = code;
            lastCodeSlot = slot;
            final int g = gestureOf(code);
            if (g < 0) {
                state = "粒" + (slot + 1) + " 長按（停用）  " + code;   // 3.13 起長按唔觸發，狀態卡照顯示做診斷
                return;
            }
            state = "粒" + (slot + 1) + " " + GESTURE_NAMES[g] + "  " + code;
            int a = action(appCtx, slot, g);
            if (a >= 0 && a < ACTIONS.length) {
                AppLog.i("BtBridge", "[粒" + (slot + 1) + "手勢] " + GESTURE_NAMES[g] + " → " + LABELS[a]);
                run(appCtx, ACTIONS[a]);
            } else {
                AppLog.i("BtBridge", "[粒" + (slot + 1) + "手勢] " + GESTURE_NAMES[g] + "（未綁動作）");
            }
        });
    }

    static void stop(Context ctx) {
        if (ctx != null) {
            setWanted(ctx, false);
        }
        running = false;
        state = "⏹ 已停";
        BleScan.stopRawFully();                  // 3.15：連 auto-rearm／心跳／BT receiver 一齊拆
    }

    // ============================================================ 廣播解碼

    /** 由廣播 hex 抽出 "FF xx yy"（協議：手勢 byte 喺 FF 後第 2 個）。 */
    static String codeOf(String raw) {
        try {
            String[] p = raw.trim().split(" ");
            for (int i = 0; i + 2 < p.length; i++) {
                if (p[i].equalsIgnoreCase("FF")) {
                    return "FF " + p[i + 1] + " " + p[i + 2];
                }
            }
            if (p.length >= 8) {
                return "FF " + p[6] + " " + p[7];
            }
        } catch (Throwable ignored) {
        }
        return "?";
    }

    /** 第 3 byte（FF 後第 2 個）＝ 手勢：00 單擊／02 雙擊／01 長按（3.13 起長按回 -1＝停用）。 */
    static int gestureOf(String code) {
        try {
            String[] p = code.trim().split(" ");
            int t = Integer.parseInt(p[p.length - 1], 16);
            switch (t) {
                case 0x00: return SINGLE;
                case 0x02: return DOUBLE;
                default: return -1;        // 01 長按（停用）／其他未知 → 唔觸發
            }
        } catch (Throwable ignored) {
            return -1;
        }
    }

    // ============================================================ 動作執行

    /** 執行動作（BLE callback／任何入口）。 */
    static void run(Context ctx, String act) {
        if (ctx == null || act == null || act.equals("none")) {
            return;
        }
        Context c = ctx.getApplicationContext();
        SharedPreferences sp = c.getSharedPreferences("zeekreq", Context.MODE_PRIVATE);
        switch (act) {
            case "red_button":                       // 🔴 紅掣（同浮窗紅掣完全一樣）
                RedButtonOverlay.fireRedButton(c);
                break;
            case "eq_toggle": {                      // 🎚 EQ 開／關（唔起浮窗 UI）
                try {
                    SysEq sys = EqService.get(c);
                    if (!sys.ready() && !sys.attach(false)) {
                        AppLog.w("BtBridge", "EQ 掛唔上：" + sys.status());
                        return;
                    }
                    boolean want = !sys.isOn();
                    sys.setEnabled(want);
                    sp.edit().putBoolean("sys_on", want).apply();
                    if (want) {
                        String g = sp.getString("gains", "");
                        if (!g.isEmpty()) {
                            sys.applyCurve(Presets.parseCsv(g),
                                    sp.getInt("preamp", 12) - 12, false);
                        }
                    }
                    AppLog.i("BtBridge", "EQ " + (want ? "開" : "關"));
                } catch (Throwable t) {
                    AppLog.w("BtBridge", "EQ toggle 失敗：" + t.getClass().getSimpleName());
                }
                break;
            }
            case "bass_toggle": {                    // 🎵 Bass Boost 0 ↔ 6 dB
                try {
                    int cur = sp.getInt("bass_boost", 0);
                    int want = cur >= 6 ? 0 : 6;
                    sp.edit().putInt("bass_boost", want).apply();
                    SysEq.bassBoostDb = want;
                    SysEq sys = EqService.get(c);
                    if (sys.ready() || sys.attach(false)) {
                        sys.applyCurve(Presets.parseCsv(sp.getString("gains", "")),
                                sp.getInt("preamp", 12) - 12, false);
                    }
                    AppLog.i("BtBridge", "Bass Boost " + want + " dB");
                } catch (Throwable t) {
                    AppLog.w("BtBridge", "bass toggle 失敗：" + t.getClass().getSimpleName());
                }
                break;
            }
            case "lim_toggle": {                     // 📐 Limiter 開／關
                try {
                    boolean on = !sp.getBoolean("limiter", false);
                    sp.edit().putBoolean("limiter", on).apply();
                    boolean ok = DynLimiter.set(on);
                    SysEq.limiterOn = on && !ok;
                    AppLog.i("BtBridge", "Limiter " + (on ? "開" : "關")
                            + (on ? (ok ? "（DP）" : "（靜態）") : ""));
                } catch (Throwable t) {
                    AppLog.w("BtBridge", "limiter toggle 失敗：" + t.getClass().getSimpleName());
                }
                break;
            }
            case "slot_a":
            case "slot_b":
            case "slot_c": {
                String key = act.substring(5).toUpperCase(java.util.Locale.US);   // slot_a → A
                FloatEqService s = svc;
                if (s != null) {
                    s.bridgeRecallSlot(key);         // 浮窗在 → 行返原本 recallSlot（UI 同步）
                } else {
                    applySlotHeadless(c, sp, key);   // 冇浮窗 → 直接套
                }
                break;
            }
            case "eq_plus":
            case "eq_minus": {
                try {
                    int now = sp.getInt("preamp", 12);
                    int want = Math.max(0, Math.min(24, now + (act.endsWith("plus") ? 1 : -1)));
                    sp.edit().putInt("preamp", want).apply();
                    SysEq sys = EqService.get(c);
                    if (sys.ready() || sys.attach(false)) {
                        sys.applyCurve(Presets.parseCsv(sp.getString("gains", "")), want - 12, false);
                    }
                    AppLog.i("BtBridge", "前級 " + (want - 12) + " dB");
                } catch (Throwable t) {
                    AppLog.w("BtBridge", "preamp 失敗：" + t.getClass().getSimpleName());
                }
                break;
            }
            case "float_min": {
                FloatEqService s = svc;
                if (s != null) {
                    s.bridgeMinimize();
                }
                break;
            }
            default:
                break;
        }
    }

    /** 冇浮窗時套用 A/B/C 曲線（直接寫 SysEq；未儲存就唔做）。 */
    private static void applySlotHeadless(Context c, SharedPreferences sp, String key) {
        try {
            java.util.Map<String, float[]> u = Presets.readUser(sp.getString("user_presets", ""));
            float[] g = u.get(key);
            if (g == null || g.length < 14) {
                AppLog.w("BtBridge", "曲線 " + key + " 未儲存（浮窗長按 A/B 存入）");
                return;
            }
            StringBuilder csv = new StringBuilder();
            for (int i = 0; i < g.length; i++) {
                if (i > 0) {
                    csv.append(',');
                }
                csv.append(String.format(java.util.Locale.US, "%.2f", g[i]));
            }
            sp.edit().putString("gains", csv.toString()).apply();
            SysEq sys = EqService.get(c);
            if (sys.ready() || sys.attach(false)) {
                sys.applyCurve(g, sp.getInt("preamp", 12) - 12, false);
            }
            AppLog.i("BtBridge", "已套用曲線 " + key);
        } catch (Throwable t) {
            AppLog.w("BtBridge", "slot 失敗：" + t.getClass().getSimpleName());
        }
    }

    // ============================================================ 浮窗服務橋

    private static volatile FloatEqService svc;

    /** 浮窗服務起動時登記（收埋浮窗／套曲線要用 UI thread 同步返掣面）。 */
    static void attachService(FloatEqService s) {
        svc = s;
    }

    static void detachService() {
        svc = null;
    }
}
