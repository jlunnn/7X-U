package com.sevenxu.eq;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 懸浮 EQ 窗（行車中用）：14 段曲線 ＋ 前級 ＋ EQ 開關。
 *
 * <ul>
 *   <li>{@code TYPE_APPLICATION_OVERLAY} ＋ {@code FLAG_NOT_FOCUSABLE} ＋ translucent
 *       （同車機已實測可行嘅做法一致：見 HermesProbe FloatService）。</li>
 *   <li><b>保留車機上下左右 info bar</b>：初始位置同拖動範圍都夾喺安全區（上下左右留 inset）。</li>
 *   <li><b>minimize</b>：標題列撳一下收埋 → 只剩 app icon 圓掣；撳返 icon 展開。</li>
 *   <li>改動即寫入媒體音樂 EQ（{@link SysEq}）＋存 prefs（同主畫面共用）。</li>
 * </ul>
 */
public class FloatEqService extends Service {

    private static final String PREF = "zeekreq";
    private static final String K_X = "fx";
    private static final String K_Y = "fy";
    private static final String K_MIN = "fmin";
    private static final String CH = "zeekreq_float";
    private static final int NOTI_ID = 42;

    private WindowManager wm;
    private WindowManager.LayoutParams lp;
    private FrameLayout root;
    private LinearLayout panel;
    private ImageView icon;
    private BarEqView eqv;
    private TextView preVal;
    private TextView engTv;
    private android.widget.ScrollView scrollBox;   // 記住滾動位置
    private TextView msgTv;                     // 段狀態訊息
    private TextView[] bandTvs;                 // 每段狀態
    private TextView outTv, out2Tv;             // Footer 讀數
    private LinearLayout footerRow;
    private View extrasHs, extrasEng, extrasFoot;
    private Button toneHdrRef;
    private LinearLayout toneRowRef;
    private Button moreHdrRef;
    private LinearLayout chipsRow;
    private int toneOn = -1;
    private final Button[] toneBtns = new Button[5];
    private final Tone tone = new Tone();
    private Button onBtn;
    private Dsp dsp;
    // ---- 快速音色旋鈕（SUB/BASS/MID/UPPER/AIR）＋ MASTER ----
    private static final String K_BASE = "base_gains";
    private static final String[] K_TONE = {"tone_sub", "tone_bass", "tone_mid", "tone_upper", "tone_air"};
    private float[] baseGains = new float[Bands.N];
    /** 最後互動時間（15 秒無操作自動收埋）。 */
    private static volatile long lastTouchAt = System.currentTimeMillis();

    /** 任何互動（包括 EQ 圖拖動）都叫一次，刷新「無操作」計時。 */
    public static void pokeActivity() {
        lastTouchAt = System.currentTimeMillis();
    }
    private volatile boolean minimizedNow = false;
    /** service 是否在生（主頁用嚟決定可否真正關窗）。 */
    public static volatile boolean alive = false;
    /** 主頁（由 ⚙ 打開）喺前面時，暫停自動收埋。 */
    public static volatile boolean settingsOpen = false;
    private final float[] toneOff = new float[QuickTone.LABEL.length];
    private final TextView[] toneValTv = new TextView[QuickTone.LABEL.length];
    private TextView masterValTv;
    // ---- A/B 兩格快速曲線（一撳切、長按存）----
    private static final String[] AB = {"A", "B"};
    private final Button[] abBtn = new Button[AB.length];
    private String abActive = "";
    private SysEq sys;
    private SharedPreferences sp;
    private int screenW, screenH, panelW, panelH, iconSize;
    private int lastSavedX = Integer.MIN_VALUE, lastSavedY = Integer.MIN_VALUE;
    private int inL, inT, inR, inB;                  // 自動讀到嘅 info bar insets
    private float downRawX, downRawY;
    private int startX, startY;
    private boolean dragged;
    private boolean fullSize;                        // ⛶ 全屏（放到成個安全區）
    private Runnable pendingApply;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        sp = getSharedPreferences(PREF, MODE_PRIVATE);
        if (!Settings.canDrawOverlays(this)) {           // 未授權 → 唔開，主畫面會提示
            stopSelf();
            return;
        }
        dsp = new Dsp();
        loadState();
        sys = EqService.get(this);
        if (sys != null && !sys.ready()) {
            sys.attach(sp.getBoolean("hal_only", false));
        }
        if (sys != null && sys.ready()) EqService.start(this);
        startForeground();
        buildWindow();
    }



    /** 著車／喚醒：背景確保 app＋EQ 生效，並**只顯示 minimize 圓掣**。 */
    private void autoShowMinimized() {
        try {
            sp.edit().putBoolean("ffull", false)
                    .putBoolean("fmin", true)                    // 只出圓掣
                    .putBoolean("float_on", true).apply();
            if (root == null || root.getParent() == null) {
                buildWindow();
            } else {
                setMinimized(true);                              // 已存在 → 收埋成圓掣
            }
            // 背景把 EQ 掛返／套用（跟「開機自動打開 EQ」設定）
            if (sp.getBoolean("boot_auto", true)) {
                try {
                    EqService.start(this);
                    SysEq sy = EqService.get(this);
                    if (sy != null && !sy.ready()) {
                        sy.attach(false);
                    }
                    String g = sp.getString("gains", "");
                    if (sy != null && sy.ready() && g != null && !g.isEmpty()) {
                        String res = sy.applyCurve(Presets.parseCsv(g), sp.getInt("preamp", 12) - 12, false);
                        BootDiag.record(this, "wake", BootReceiver.isApplied(res),
                                sy.isCustom() ? ("自訂引擎 " + sy.bands() + " 段")
                                        : ("車機原生 " + sy.bands() + " 段"), res);
                    } else {
                        BootDiag.record(this, "wake", false, "—",
                                sy == null ? "EQ 服務未就緒" : (sy.ready() ? "未有曲線資料" : "掛唔到系統 EQ"));
                    }
                } catch (Throwable t) {
                    AppLog.d("EQ", "auto EQ failed: " + t);
                    BootDiag.record(this, "wake", false, "—", "例外：" + t.getClass().getSimpleName());
                }
            } else {
                BootDiag.record(this, "wake", true, "—", "開機自動套用已關閉，冇套用");
            }
            AppLog.d("EQ", "autoShowMinimized done");
        } catch (Throwable t) {
            AppLog.d("EQ", "autoShowMinimized failed: " + t);
        }
    }

    /** 確保懸浮窗存在（被車機清走 / service 重啟都會自動重掛）→ 圓掣長期顯示。 */
    private void ensureWindow() {
        // startup sync：開機／著車自動起浮窗時，由 prefs 還原 Bass Boost／Limiter
        try {
            SysEq.bassBoostDb = sp.getInt("bass_boost", 0);
            SysEq.limiterOn = sp.getBoolean("limiter", false);
        } catch (Throwable ignored) {
        }
        try {
            if (root == null || root.getParent() == null) {
                AppLog.d("EQ", "float window missing → rebuild");
                buildWindow();
            } else if (panel != null && panel.getVisibility() != View.VISIBLE) {
                setMinimized(true);          // 重讀 icon_dp 並即時套用新 size
            }
        } catch (Throwable t) {
            AppLog.d("EQ", "ensureWindow failed: " + t);
        }
        watchdog.postDelayed(watchdogTick, 8000);        // 每 8 秒檢查一次
    }

    private final android.os.Handler watchdog =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private final Runnable watchdogTick = new Runnable() {
        @Override
        public void run() {
            ensureWindow();
        }
    };

    private final android.content.BroadcastReceiver wakeUp = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context c, android.content.Intent i) {
            String a = i == null ? "?" : i.getAction();
            AppLog.d("EQ", "wakeUp: " + a);
            if (a != null && (a.equals(android.content.Intent.ACTION_SCREEN_ON)
                    || a.equals(android.content.Intent.ACTION_USER_PRESENT)
                    || a.equals(android.content.Intent.ACTION_BOOT_COMPLETED))) {
                autoShowMinimized();                              // 著車 → 背景開 app＋只出圓掣
            } else {
                ensureWindow();
            }
        }
    };

    private void startForeground() {
        try {
            watchdog.removeCallbacks(watchdogTick);
            watchdog.postDelayed(watchdogTick, 3000);
            android.content.IntentFilter f = new android.content.IntentFilter();
            f.addAction(android.content.Intent.ACTION_SCREEN_ON);
            f.addAction(android.content.Intent.ACTION_USER_PRESENT);
            f.addAction(android.content.Intent.ACTION_BOOT_COMPLETED);
            registerReceiver(wakeUp, f);
            AppLog.d("EQ", "float watchdog + wakeUp registered");
        } catch (Throwable ignored) {
        }
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26 && nm != null) {
                nm.createNotificationChannel(new NotificationChannel(CH, "7X=U 懸浮 EQ",
                        NotificationManager.IMPORTANCE_LOW));
            }
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, CH) : new Notification.Builder(this);
            b.setContentTitle("7X=U 懸浮 EQ").setContentText("行車 EQ 懸浮窗運作中")
                    .setSmallIcon(android.R.drawable.ic_menu_slideshow).setOngoing(true);
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTI_ID, b.build(), 32);
            else startForeground(NOTI_ID, b.build());
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ 窗

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private int insetTop() {
        return inT;
    }

    private int insetBottom() {
        return inB;
    }

    private int insetSide() {
        return inL;
    }

    /**
     * 自動讀車機 info bar 實際高度（唔再硬編碼）：
     * API 30+ 用 {@link WindowManager#getCurrentWindowMetrics()} 嘅 window insets
     * （systemBars ＋ displayCutout）；讀唔到就退回保守預設值。
     */
    private void readInsets() {
        int l = dp(24), t = dp(44), r = dp(24), b = dp(52);
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                android.view.WindowMetrics m = wm.getCurrentWindowMetrics();
                android.view.WindowInsets ins = m.getWindowInsets();
                if (ins != null) {
                    android.graphics.Insets sb = ins.getInsetsIgnoringVisibility(
                            android.view.WindowInsets.Type.systemBars()
                                    | android.view.WindowInsets.Type.displayCutout());
                    l = sb.left;
                    t = sb.top;
                    r = sb.right;
                    b = sb.bottom;
                }
            } else {
                android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
                t = Math.round(24 * dm.density);
                b = Math.round(48 * dm.density);
            }
        } catch (Throwable ignored) {
        }
        int gap = dp(6);                     // 唔好貼實 info bar
        inL = l + gap;
        inT = t + gap;
        inR = r + gap;
        inB = b + gap;
    }

    /** 讀完 insets 後重新調整面板大細 ＋ 位置（自動） */
    private void refit() {
        if (wm == null || lp == null || root == null) return;
        readInsets();
        int safeW = screenW - inL - inR;
        int safeH = screenH - inT - inB;
        panelW = fullSize ? safeW : Math.max(dp(320), Math.min(dp(600), safeW));
        panelH = fullSize ? safeH : Math.max(dp(240), Math.min(dp(430), safeH));
        applyPanelGeometry();
    }

    /**
     * 面板幾何（自動）：
     * full screen 模式 → 窗 = 全屏（可以蓋住成個畫面），面板就放喺安全區（上下左右留 info bar）；
     * 普通模式 → 窗 = 面板大細，可以拖。
     */
    private void applyPanelGeometry() {
        if (panel == null || lp == null || wm == null) return;
        boolean min = panel.getVisibility() != View.VISIBLE;
        if (min) {
            lp.width = iconSize;
            lp.height = iconSize;
            panel.setLayoutParams(new FrameLayout.LayoutParams(0, 0));
        } else if (fullSize) {
            lp.width = screenW;
            lp.height = screenH;
            FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
                    Math.max(dp(200), screenW - inL - inR),
                    Math.max(dp(200), screenH - inT - inB));
            flp.leftMargin = inL;
            flp.topMargin = inT;
            panel.setLayoutParams(flp);
        } else {
            lp.width = panelW;
            lp.height = panelH;
            panel.setLayoutParams(new FrameLayout.LayoutParams(panelW, panelH));
        }
        lp.x = clampX(lp.x);
        lp.y = clampY(lp.y);
        try {
            wm.updateViewLayout(root, lp);
        } catch (Throwable ignored) {
        }
    }


    /** 懸浮版測試音：正弦（單頻／1K）／白噪／粉紅／掃頻。 */

    /** 圓掣大小：跟屏幕自動（約短邊 7.5%，夾喺 48–84dp，密度已由 dp() 處理）。 */
    private int autoIconSize() {
        int base = (int) (Math.min(screenW, screenH) * 0.075f);
        return Math.max(dp(48), Math.min(dp(84), base));
    }

    /** 「更多」摺疊：一次過收／開 preset 列、測試音、重掛、歸零。 */
    private void applyExtras(boolean open) {
        pokeActivity();   // applyExtrasPoked
        int v = open ? View.VISIBLE : View.GONE;
        // preset 列固定顯示（唔再收埋）
        if (extrasHs != null) extrasHs.setVisibility(View.VISIBLE);
        if (extrasFoot != null) extrasFoot.setVisibility(View.VISIBLE);
        // 「自訂 14 段」狀態文字、測試音摺疊掣：已按用戶要求取消
        if (extrasEng != null) extrasEng.setVisibility(View.GONE);
        if (toneHdrRef != null) toneHdrRef.setVisibility(View.GONE);
        if (toneRowRef != null) toneRowRef.setVisibility(v);   // 測試音內容跟 more 開關
        if (moreHdrRef != null) {
            moreHdrRef.setText(open ? "\u25BE more" : "\u25B8 more");
        }
    }


    /** 圓掣大小：設定頁可調（icon_dp > 0）；0 = 跟屏幕自動。 */
    private int iconSizePx() {
        int idp = sp.getInt("icon_dp", 0);
        return idp > 0 ? dp(idp) : autoIconSize();
    }


    /** 真正改音量：① 試改車機媒體音量 ② 同時套 DSP 數位增益（車機唔准都聽得出分別）。 */
    private void applyVolume(int v, boolean commit) {
        // 70% = 0 dB 參考；100% = +6 dB；0% = −30 dB
        double volDb = (v >= 70) ? (v - 70) * (6.0 / 30.0) : -((70 - v) * (30.0 / 70.0));
        SysEq.userVolDb = 0;   // 只用系統音量，唔用數碼增益（避免音質變差）
        try {
            android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            if (am != null) {
                int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
                int cur = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
                int want = (int) Math.round(max * v / 100.0);
                if (want != cur) {
                    try {
                        am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, want, 0);
                    } catch (Throwable t1) {
                        // 車機唔准直接 set → 用相對調整（逐級）
                        int delta = want - cur;
                        int dir = delta > 0 ? android.media.AudioManager.ADJUST_RAISE
                                : android.media.AudioManager.ADJUST_LOWER;
                        for (int i = 0; i < Math.abs(delta); i++) {
                            am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, dir, 0);
                        }
                    }
                }
                int now = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
                if (commit) {
                    say(now == want ? ("VOLUME " + v + "%（系統 " + now + "/" + max + "）")
                            : ("車機唔准 app 改音量（系統仍 " + now + "/" + max + "）"));
                }
            }
        } catch (Throwable t) {
            AppLog.d("EQ", "setStreamVolume blocked: " + t);
        }
        if (commit) {
            schedule(true, true);
            say("音量 " + v + "%（" + String.format(java.util.Locale.US, "%+.1f", volDb) + " dB）");
        }
    }

    /** Toast 小提示。 */
    private void say(String s) {
        pokeActivity();      // 任何有反饋嘅操作都重置 idle 計時
        try {
            android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }


    /** 套用一條已儲存曲線（chip 用）。 */
    private void applyCurveChip(String name, float[] g) {
        pokeActivity();
        if (dsp == null || g == null) return;
        for (int i = 0; i < Bands.N && i < g.length; i++) dsp.set(i, g[i]);
        if (eqv != null) {
            eqv.markDirty();
            eqv.invalidate();
        }
        schedule(true, true);
        say("已套用曲線：" + name);
    }



    /** 為所有子 view 掛觸控監聽：撳 preset／拖 slider／任何觸控都刷新 idle 計時。 */
    private void hookAllTouches(View v) {
        if (v == null) return;
        v.setOnTouchListener((vv, ev) -> {
            pokeActivity();
            return false;                 // 唔攔截，click／drag 照常
        });
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) hookAllTouches(g.getChildAt(i));
        }
    }

    /** 刪除一條自訂曲線（同時移除 chip）。 */
    private void deleteUserPreset(String name, View chip) {
        try {
            java.util.Map<String, float[]> u = Presets.readUser(sp.getString("user_presets", ""));
            u.remove(name);
            sp.edit().putString("user_presets", Presets.writeUser(u)).apply();
            if (chip != null && chip.getParent() instanceof ViewGroup) {
                ((ViewGroup) chip.getParent()).removeView(chip);
            }
            say("已刪除曲線：" + name);
        } catch (Throwable t) {
            say("刪除失敗：" + t.getClass().getSimpleName());
        }
    }

    /** 長撳 chip → 彈確認是否需要刪除。 */
    private void confirmDelete(final String name, final Button chip) {
        try {
            android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this,
                    android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("刪除曲線")
                    .setMessage("確定刪除「" + name + "」？")
                    .setPositiveButton("刪除", (dd, w) -> deleteUserPreset(name, chip))
                    .setNegativeButton("取消", null)
                    .create();
            dlg.getWindow().setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
            dlg.show();
        } catch (Throwable t) {
            // 車機唔准 service 開 dialog：退而求其次，長撳直接刪（say 會顯示已刪除）
            deleteUserPreset(name, chip);
        }
    }

    /** 開窗時把已儲存曲線變成可撳 chip。 */
    private void loadUserChips() {
        if (chipsRow == null) return;
        java.util.Map<String, float[]> u = Presets.readUser(sp.getString("user_presets", ""));
        for (java.util.Map.Entry<String, float[]> e : u.entrySet()) {
            final String n = e.getKey();
            final float[] g = e.getValue();
            Button nb = Ui.segment(this, n);
            nb.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
            nb.setOnClickListener(v -> applyCurveChip(n, g));
            final Button fchip = nb;
            nb.setOnLongClickListener(v -> {
                confirmDelete(n, fchip);
                return true;
            });
            chipsRow.addView(nb);
        }
    }

    /** 真正寫入儲存（同主頁共用 prefs user_presets）。 */
    private String saveNow(String name) {
        java.util.Map<String, float[]> u = Presets.readUser(sp.getString("user_presets", ""));
        String n = (name == null ? "" : name.trim());
        if (n.isEmpty()) n = "自訂" + (u.size() + 1);
        u.put(n, Presets.parseCsv(sp.getString("gains", "")));
        sp.edit().putString("user_presets", Presets.writeUser(u)).apply();
        if (chipsRow != null) {                     // 即時加 chip（可即撳即用）
            Button nb = Ui.segment(this, n);
            nb.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
            final String fn = n;
            final float[] fg = u.get(n);
            nb.setOnClickListener(v -> applyCurveChip(fn, fg));
            final Button fchip2 = nb;
            nb.setOnLongClickListener(v -> {
                confirmDelete(fn, fchip2);
                return true;
            });
            chipsRow.addView(nb);
        }
        say("已儲存曲線：" + n + "（主頁預設列同樣見到）");
        return n;
    }

    /**
     * 💾 儲存自訂 EQ。
     * ⚠️ Service 冇 activity theme，直接開 AppCompat 對話框會 crash → 用主題化 Context；
     * 若連對話框都開唔到（部分車機禁 overlay dialog），就直接用自動名儲存，唔會再彈 app。
     */
    private void saveCustom() {
        try {
            android.view.ContextThemeWrapper wrap = new android.view.ContextThemeWrapper(
                    this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert);
            final LinearLayout box = Ui.col(wrap);
            box.setPadding(dp(16), dp(8), dp(16), 0);
            final android.widget.EditText et = new android.widget.EditText(wrap);
            et.setHint("曲線名（留空 = 自訂N）");
            et.setTextColor(0xFFE6EAF2);
            et.setHintTextColor(Ui.DIM);
            box.addView(et);
            final String[] picked = new String[]{""};
            androidx.appcompat.app.AlertDialog d = new androidx.appcompat.app.AlertDialog.Builder(wrap)
                    .setTitle("儲存現時 14 段曲線")
                    .setView(box)
                    .setPositiveButton("儲存", (dd, w) -> {
                        picked[0] = et.getText().toString();
                        saveNow(picked[0]);
                    })
                    .setNegativeButton("取消", null)
                    .create();
            android.view.Window wd = d.getWindow();
            if (wd != null) {
                wd.setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
            }
            d.show();
        } catch (Throwable t) {
            try {
                AppLog.d("EQ", "float save dialog failed: " + t);
            } catch (Throwable ignored) {
            }
            saveNow("");                            // 保險：直接儲存，唔會彈 app
        }
    }

    private static final class Tone {
        private volatile AudioTrack tr;
        private volatile boolean run;
        private Thread th;

        void start(final int kind, final double hz, final int volPct) {
            stop();
            final int rate = 44100;
            final int buf = 2048;
            try {
                tr = new AudioTrack.Builder()
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(rate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                                .build())
                        .setBufferSizeInBytes(buf * 4)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build();
                run = true;
                tr.play();
            } catch (Throwable t) {
                try {
                    if (tr != null) tr.release();
                } catch (Throwable ignored) {
                }
                tr = null;
                return;
            }
            final AudioTrack t0 = tr;
            th = new Thread(() -> {
                short[] out = new short[buf * 2];
                double ph = 0;
                double sweep = 20;
                final java.util.Random rnd = new java.util.Random();
                final double[] pink = new double[7];
                final double amp = Math.max(0, Math.min(100, volPct)) / 100.0 * 0.25;
                while (run && t0 != null) {
                    for (int i = 0; i < buf; i++) {
                        double smp;
                        switch (kind) {
                            case 1:
                                smp = 2 * rnd.nextDouble() - 1;
                                break;
                            case 2: {
                                double w = 2 * rnd.nextDouble() - 1;
                                double sum = 0;
                                for (int k = 0; k < 7; k++) {
                                    pink[k] = 0.98 * pink[k] + w * 0.05;
                                    sum += pink[k];
                                }
                                smp = sum * 0.2;
                                break;
                            }
                            case 3: {
                                smp = Math.sin(ph);
                                ph += 2 * Math.PI * sweep / rate;
                                sweep *= 1.00003;
                                if (sweep > 18000) sweep = 20;
                                break;
                            }
                            case 4:
                                smp = Math.sin(ph);
                                ph += 2 * Math.PI * 1000 / rate;
                                break;
                            default:
                                smp = Math.sin(ph);
                                ph += 2 * Math.PI * hz / rate;
                                break;
                        }
                        short v = (short) (smp * amp * 32767);
                        out[i * 2] = v;
                        out[i * 2 + 1] = v;
                    }
                    try {
                        t0.write(out, 0, out.length);
                    } catch (Throwable t) {
                        break;
                    }
                }
            }, "float-tone");
            th.start();
        }

        void stop() {
            run = false;
            try {
                if (th != null) th.join(250);
            } catch (Throwable ignored) {
            }
            th = null;
            try {
                if (tr != null) {
                    tr.stop();
                    tr.release();
                }
            } catch (Throwable ignored) {
            }
            tr = null;
        }
    }


    /** EQ 開關（SETTINGS 款式，唔再圓形）：開 = 深綠底＋霓虹綠粗框綠字、關 = 深底灰框灰字。 */
    private GradientDrawable roundBg(boolean on) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(on ? 0xFF1E2A12 : 0xFF1A1F16);
        g.setCornerRadius(dp(8));
        g.setStroke(dp(on ? 3 : 2), on ? 0xFFB6FF3B : 0xFF2C3526);
        return g;
    }







    /**
     * 💾 儲存自訂 EQ。
     * ⚠️ Service 冇 activity theme，直接開 AppCompat 對話框會 crash → 用主題化 Context；
     * 若連對話框都開唔到（部分車機禁 overlay dialog），就直接用自動名儲存，唔會再彈 app。
     */




    private GradientDrawable bg(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        g.setStroke(dp(1.5f), 0xFF333333);
        return g;
    }

    private void buildWindow() {
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        screenW = getResources().getDisplayMetrics().widthPixels;
        screenH = getResources().getDisplayMetrics().heightPixels;
        readInsets();                                     // 自動讀車機 info bar 實際 insets
        fullSize = sp.getBoolean("ffull", true);   // 預設 full screen（EQ 全部功能喺懸浮面）
        panelW = fullSize ? (screenW - inL - inR)
                : Math.max(dp(320), Math.min(dp(600), screenW - inL - inR));
        panelH = fullSize ? (screenH - inT - inB)
                : Math.max(dp(240), Math.min(dp(430), screenH - inT - inB));
        iconSize = iconSizePx();         // 設定頁可調（0 = 自動）

        root = new FrameLayout(this);

        // ---- 面板（內容包 ScrollView：full screen 都用得盡）----
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(bg(0xFF0D110D, dp(12)));
        panel.setPadding(dp(8), dp(8), dp(8), dp(8));

        android.widget.ScrollView ps = new android.widget.ScrollView(this);
        scrollBox = ps;
        scrollBox.setOnScrollChangeListener((vv, x0, y0, x1, y1) -> sp.edit().putInt("float_scroll", y1).apply());   // 記住滾動位置
        ps.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        ps.addView(content, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(ps, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("Equalizer");
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 30);   // ×2
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFFE6EAF2);
        title.setPadding(dp(4), dp(6), dp(4), dp(6));
        head.addView(title);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        onBtn = Ui.segment(this, "");
        onBtn.setBackground(roundBg(false));      // 圓形 EQ 開關
        onBtn.setLayoutParams(new LinearLayout.LayoutParams(dp(120), dp(46)));
        onBtn.setPadding(dp(10), 0, dp(10), 0);
        syncOnBtn();
        onBtn.setOnClickListener(v -> {
            boolean on = sys != null && sys.isOn();
            if (sys != null) {
                if (!sys.ready()) sys.attach(sp.getBoolean("hal_only", false));
                sys.setEnabled(!on);
                if (!on) applyNow(true);
                sp.edit().putBoolean("sys_on", !on).apply();
            }
            syncOnBtn();
        });
        head.addView(onBtn);

        Button min = Ui.segment(this, "");
        min.setText("✕");
        // ✕：螢光紅色外框＋每 10 秒閃一下（行車時容易搵）
        final int XRED = 0xFFFF2D55;
        android.graphics.drawable.GradientDrawable xbg = new android.graphics.drawable.GradientDrawable();
        xbg.setColor(0x00000000);
        xbg.setCornerRadius(dp(8));
        xbg.setStroke(Math.max(2, dp(3)), XRED);
        min.setBackground(xbg);
        min.setTextColor(XRED);
        min.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18);
        min.setAllCaps(false);
        min.setPadding(0, 0, 0, 0);
        min.setLayoutParams(new LinearLayout.LayoutParams(dp(56), dp(46)));
        final Button minRef = min;

        // ⚙ 掣：撳返先開主頁（平時主頁會退到背景，唔阻住車機畫面）
        // ==== EQ-Control 嘅 SETTINGS 掣（用 TextView，避免 Button 夾斷字）====
        final TextView gear = Ui.text(this, "\u2699  SETTINGS", 11f, 0xFFA0A89A);
        android.graphics.drawable.GradientDrawable sg = new android.graphics.drawable.GradientDrawable();
        sg.setColor(0xFF1A1F16);
        sg.setCornerRadius(dp(8));
        sg.setStroke(Math.max(1, dp(1)), 0xFF2C3526);
        gear.setBackground(sg);
        gear.setGravity(Gravity.CENTER);
        gear.setSingleLine(true);
        gear.setAllCaps(true);
        gear.setPadding(dp(12), 0, dp(12), 0);
        try {
            gear.setLetterSpacing(0.06f);
        } catch (Throwable ignored) {
        }
        gear.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(dp(160), dp(46));
        glp.leftMargin = dp(8);
        gear.setLayoutParams(glp);
        gear.setOnClickListener(v -> {
            try {
                pokeActivity();
                settingsOpen = false;
                setMinimized(true);                  // 開主頁時自動收埋浮窗（正確行為）
                android.content.Intent it = new android.content.Intent(this, MainActivity.class);
                it.putExtra("settings", true);
                it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        | android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(it);
            } catch (Throwable t) {
                say("開唔到主頁：" + t.getClass().getSimpleName());
            }
        });
        head.addView(gear);
        min.setOnClickListener(v -> setMinimized(true));
        // ---- 15 秒無操作自動收埋（設定頁可關）----
        try {
            root.setOnTouchListener((vv, ev) -> {
                lastTouchAt = System.currentTimeMillis();     // 觸控即刷新
                return false;                                // 唔攔截，交返俾子 view
            });
        } catch (Throwable ignored) {
        }
        final android.os.Handler idleH = new android.os.Handler(android.os.Looper.getMainLooper());
        final Runnable idleTask = new Runnable() {
            @Override
            public void run() {   // auto-min idle
                try {
                    if (sp.getBoolean("auto_min", true) && root != null && panel != null
                            && !minimizedNow && !settingsOpen && panel.getVisibility() == View.VISIBLE
                            && System.currentTimeMillis() - lastTouchAt > 15000L) {
                        setMinimized(true);
                    }
                } catch (Throwable ignored) {
                }
                idleH.postDelayed(this, 2000);
            }
        };
        idleH.postDelayed(idleTask, 2000);
        try {
            hookAllTouches(root);      // 任何觸控都 reset idle timer
        } catch (Throwable ignored) {
        }
        head.addView(min);
        // 每 10 秒閃一次（alpha 1 → 0.25 → 1，600ms）
        final android.os.Handler fh = new android.os.Handler(android.os.Looper.getMainLooper());
        final Runnable flash = new Runnable() {
            @Override
            public void run() {
                try {
                    if (minRef != null) {
                        android.animation.ObjectAnimator a = android.animation.ObjectAnimator
                                .ofFloat(minRef, "alpha", 1f, 0.25f, 1f);
                        a.setDuration(600);
                        a.start();
                    }
                } catch (Throwable ignored) {
                }
                fh.postDelayed(this, 10000);
            }
        };
        fh.postDelayed(flash, 10000);

        content.addView(head);

        // ---- 預設曲線（橫捲，同 app 一樣）----
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        for (final Presets.P pr : Presets.builtin()) {
            Button c = Ui.segment(this, "");
            c.setText(pr.name);
            c.setPadding(dp(6), 0, dp(6), 0);
            c.setOnClickListener(v -> {
                // 揀 preset = 用返原廠曲線；旋鈕偏移同時歸零（免得下次扭時重複加）
                java.util.Arrays.fill(toneOff, 0f);
                baseGains = pr.gains.clone();
                dsp.setGainsNow(QuickTone.apply(baseGains, toneOff));
                syncToneUi();
                if (bandTvs != null && dsp != null) {
                boolean any = false;
                for (int i = 0; i < Bands.N; i++) {
                    float g = dsp.get(i);
                    if (Math.abs(g) >= 0.05f) any = true;
                    if (bandTvs[i] != null) {
                        bandTvs[i].setText(Bands.LABEL[i] + "  " + Presets.Fmt.sign(g));
                        bandTvs[i].setTextColor(Math.abs(g) < 0.05f ? 0xFFA0A89A
                                : (g > 0 ? 0xFFB6FF3B : 0xFFFF8A5B));
                    }
                }
                if (msgTv != null) {
                    int ab2 = (eqv == null) ? -1 : eqv.activeBand();
                    msgTv.setText(ab2 >= 0
                            ? ("已調整 " + Bands.LABEL[ab2] + "  " + Presets.Fmt.sign(dsp.get(ab2)) + " dB")
                            : (any ? "曲線已套用（拖任何一段可即時調節）" : "全部 0 dB · 未有改動"));
                }
            }
            refreshReadout();
            if (eqv != null) {
                    eqv.markDirty();
                    eqv.invalidate();
                }
                schedule(true, true);
            });
            chips.addView(c);
        }
        // ---- 💾 儲存（同 app 主頁一樣，喺 preset 列最尾）----
        Button saveBtn = Ui.segment(this, "💾 儲存");
        saveBtn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        saveBtn.setOnClickListener(v -> saveCustom());
        chips.addView(saveBtn);
        chipsRow = chips;
        loadUserChips();   // 已儲存曲線 → 可撳 chip
        hs.addView(chips);
        hs.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        // ---- 更多（摺疊）：preset／測試音／重掛／歸零（主版面保持 EQ-Control 乾淨）----
        final Button moreHdr = Ui.segment(this, "更多 ▸");
        android.graphics.drawable.GradientDrawable mbg = new android.graphics.drawable.GradientDrawable();
        mbg.setColor(0xFF1A1F16);
        mbg.setCornerRadius(dp(8));
        mbg.setStroke(dp(3), 0xFFB6FF3B);                        // 粗線框
        moreHdr.setBackground(mbg);
        moreHdr.setTextColor(0xFFB6FF3B);
        moreHdr.setLayoutParams(new LinearLayout.LayoutParams(dp(120), dp(46)));
        moreHdr.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        final boolean[] more = {sp.getBoolean("more_open", false)};
        moreHdr.setText(more[0] ? "\u25BE more" : "\u25B8 more");
        moreHdr.setOnClickListener(v -> {
            pokeActivity();
            more[0] = !more[0];
            sp.edit().putBoolean("more_open", more[0]).apply();
            applyExtras(more[0]);
            moreHdr.setText(more[0] ? "\u25BE more" : "\u25B8 more");
        });
        moreHdrRef = moreHdr;
        extrasHs = hs;
        content.addView(hs);

        // SUB／BASS／MID／UPPER／AIR／MASTER 快速旋鈕已按用戶要求移除

        // ---- A/B 兩格：一撳切換、長按存入 ----
        LinearLayout abRow = new LinearLayout(this);
        abRow.setOrientation(LinearLayout.HORIZONTAL);
        abRow.setGravity(Gravity.CENTER_VERTICAL);
        abRow.setPadding(0, dp(2), 0, dp(4));
        for (int i = 0; i < AB.length; i++) {
            final String key = AB[i];
            Button ab = Ui.segment(this, key);
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(72), dp(40));
            alp.rightMargin = dp(6);
            abRow.addView(ab, alp);
            ab.setOnClickListener(v -> recallSlot(key));
            ab.setOnLongClickListener(v -> {
                saveSlot(key);
                return true;
            });
            abBtn[i] = ab;
        }
        TextView abHint = Ui.text(this, "A / B：一撳切換　·　長按 = 存入現時曲線", 12f, 0xFFA0A89A);
        abRow.addView(abHint, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        content.addView(abRow);

        // ---- 14 段 EQ ----
        eqv = new BarEqView(this, dsp);
        eqv.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        eqv.setListener(new BarEqView.Listener() {
            @Override
            public void onGain(int band, float db) {
                dsp.set(band, db);
                if (band >= 0 && band < baseGains.length) {
                    baseGains[band] = QuickTone.baseFor(band, db, toneOff);   // 拖柱 = 改基底，旋鈕偏移唔會被重複加
                }
                schedule(true, false);
            }

            @Override
            public void onCommit(int band, float db) {
                dsp.set(band, db);
                if (band >= 0 && band < baseGains.length) {
                    baseGains[band] = QuickTone.baseFor(band, db, toneOff);
                }
                schedule(true, true);
            }

            @Override
            public void onActive(int band) {
            }
        });
        content.addView(eqv);

        // ---- 引擎狀態 ＋ 重掛（EQ 引擎相關全部搬上懸浮面）----
        LinearLayout engRow = new LinearLayout(this);
        engRow.setOrientation(LinearLayout.HORIZONTAL);
        engRow.setGravity(Gravity.CENTER_VERTICAL);
        Button re = Ui.segment(this, "");
        re.setText("♻️ 重掛");
        re.setVisibility(View.GONE);      // 「重掛」已按用戶要求取消
        re.setOnClickListener(v -> {
            sys = null;
            EqService.drop();
            applyNow(true);
            syncEng();
        });
        engRow.addView(re);
        engTv = new TextView(this);
        engTv.setTextColor(0xFF9E9E9E);
        engRow.addView(engTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // ---- 測試音（同 app 主頁一樣，搬上懸浮面）----
        // ---- 測試音（可摺疊收藏：預設收埋）----
        final Button toneHdr = Ui.segment(this, "測試音");
        toneHdr.setLayoutParams(new LinearLayout.LayoutParams(dp(150), dp(46)));
        toneHdrRef = toneHdr;
        content.addView(toneHdr);      // 摺疊掣本身要加落內容（否則收埋之後冇得開返）
        final String[] toneNames = {"SINE", "PINK", "WHITE", "SWEEP", "1K"};
        final int[] toneKinds = {0, 2, 1, 3, 4};
        LinearLayout toneRow = new LinearLayout(this);
        toneRow.setOrientation(LinearLayout.HORIZONTAL);
        toneRow.setGravity(Gravity.CENTER_VERTICAL);
        for (int ti = 0; ti < toneNames.length; ti++) {
            final int idx = ti;
            Button tb = Ui.segment(this, toneNames[ti]);
            tb.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 19);   // 同 Hz 字級一致
            tb.setLayoutParams(new LinearLayout.LayoutParams(dp(126), dp(46)));
            tb.setOnClickListener(v -> {
                if (toneOn == idx) {
                    tone.stop();
                    toneOn = -1;
                    tb.setText(toneNames[idx]);
                    return;
                }
                tone.stop();
                for (int k = 0; k < toneBtns.length; k++) {
                    if (toneBtns[k] != null) toneBtns[k].setText(toneNames[k]);
                }
                toneOn = idx;
                double hz = 1000;
                if (toneKinds[idx] == 0 && eqv != null && eqv.activeBand() >= 0) {
                    hz = Bands.FREQ[eqv.activeBand()];
                }
                tone.start(toneKinds[idx], hz, sp.getInt("vol", 70));
                tb.setText("⏹ " + toneNames[idx]);
            });
            toneBtns[ti] = tb;
            toneRow.addView(tb);
            toneRow.addView(new View(this), new LinearLayout.LayoutParams(dp(6), 1));
        }
        toneRowRef = toneRow;
        content.addView(toneRow);
        // 摺疊：header 掣控制測試音列顯示／收起（記住狀態）
        final LinearLayout tRow = toneRow;
        final boolean[] tOpen = {sp.getBoolean("tone_open", false)};
        tRow.setVisibility(tOpen[0] ? View.VISIBLE : View.GONE);
        toneHdr.setText(tOpen[0] ? "測試音 ▾（收起）" : "測試音 ▸（展開）");
        toneHdr.setOnClickListener(v -> {
            tOpen[0] = !tOpen[0];
            tRow.setVisibility(tOpen[0] ? View.VISIBLE : View.GONE);
            toneHdr.setText(tOpen[0] ? "測試音 ▾（收起）" : "測試音 ▸（展開）");
            sp.edit().putBoolean("tone_open", tOpen[0]).apply();
        });
        // ---- 測試音 ＋ 重掛 同一 horizontal 行 ----
        LinearLayout toneEng = new LinearLayout(this);
        toneEng.setOrientation(LinearLayout.HORIZONTAL);
        toneEng.setGravity(Gravity.CENTER_VERTICAL);
        for (View cv : new View[]{toneHdr, engRow}) {
            if (cv != null && cv.getParent() instanceof ViewGroup) {
                ((ViewGroup) cv.getParent()).removeView(cv);
            }
        }
        toneEng.addView(toneHdr);
        toneEng.addView(new View(this), new LinearLayout.LayoutParams(dp(10), 1));
        toneEng.addView(engRow);
        extrasEng = toneEng;
        content.addView(toneEng);

        // ---- 前級 ＋ 音量 ＋ 全段歸零 ----
        LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        TextView pl = new TextView(this);
        pl.setText("DB GAIN");
        Ui.tagStyle(pl);
        pl.setTextColor(0xFFE6EAF2);
        foot.addView(pl);
        final GainBar pre = new GainBar(this);
        pre.setLayoutParams(new LinearLayout.LayoutParams(0, dp(46), 1f));
        
        pre.setProgress((int) Math.round(dsp.preampDb()) + 10);
        pre.setLayoutParams(new LinearLayout.LayoutParams(0, dp(46), 1f));
        pre.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(android.widget.SeekBar s, int v, boolean fromUser) {
                pokeActivity();
                dsp.setPreampDb(v - 10);
                syncPreamp();
            }

            public void onStartTrackingTouch(android.widget.SeekBar s) {
            }

            public void onStopTrackingTouch(android.widget.SeekBar s) {
                schedule(true, true);
            }
        });
        foot.addView(pre);
        preVal = new TextView(this);
        Ui.valStyle(preVal);
        preVal.setTextColor(0xFFE6EAF2);
        preVal.setMinWidth(dp(86));
        preVal.setGravity(Gravity.CENTER);
        foot.addView(preVal);
        
        extrasFoot = foot;
        content.addView(foot);

        LinearLayout volRow = new LinearLayout(this);
        volRow.setOrientation(LinearLayout.HORIZONTAL);
        volRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView vl = new TextView(this);
        vl.setText("VOLUME");
        Ui.tagStyle(vl);
        vl.setTextColor(0xFFE6EAF2);
        volRow.addView(vl);
        final VolBar vol = new VolBar(this);
        vol.setLayoutParams(new LinearLayout.LayoutParams(0, dp(46), 1f));
        vol.setMax(100);
        vol.setProgress(sp.getInt("vol", 70));
        vol.setLayoutParams(new LinearLayout.LayoutParams(0, dp(46), 1f));   // 自繪 bar 要固定高度（唔可以 WRAP_CONTENT）
        final TextView volVal = new TextView(this);
 Ui.valStyle(volVal);
        volVal.setTextColor(0xFFE6EAF2);
        volVal.setMinWidth(dp(86));
        volVal.setGravity(Gravity.CENTER);
        volVal.setText(sp.getInt("vol", 70) + "%");
        vol.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(android.widget.SeekBar s, int v, boolean fromUser) {
                pokeActivity();
                volVal.setText(v + "%");
                applyVolume(v, false);
            }

            public void onStartTrackingTouch(android.widget.SeekBar s) {
            }

            public void onStopTrackingTouch(android.widget.SeekBar s) {
                sp.edit().putInt("vol", s.getProgress()).apply();
                applyVolume(s.getProgress(), true);
            }
        });
        volRow.addView(vol);
        volRow.addView(volVal);
        content.addView(volRow);

        // ---- EQ-Control 分佈：VOLUME 同 DB GAIN 同一行，中間一條垂直分隔線 ----
        LinearLayout ctl = new LinearLayout(this);
        ctl.setOrientation(LinearLayout.HORIZONTAL);
        ctl.setGravity(Gravity.CENTER_VERTICAL);
        ctl.setPadding(0, dp(8), 0, dp(4));
        // 先由舊 parent 拆出（否則 addView 會 throw: child already has a parent）
        for (View cv : new View[]{vl, vol, volVal, pl, pre, preVal}) {
            if (cv != null && cv.getParent() instanceof ViewGroup) {
                ((ViewGroup) cv.getParent()).removeView(cv);
            }
        }
        // VOLUME 已取消（車機唔准 app 改媒體音量）；位置改放 BASS BOOST
        View vdiv = new View(this);
        vdiv.setBackgroundColor(0xFF1E2418);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(Math.max(1, dp(1)), dp(30));
        dlp.leftMargin = dp(16);
        dlp.rightMargin = dp(16);
        // 「more」掣放兩條 bar 中間，左右各留 2px
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(dp(120), dp(46));
        mlp.leftMargin = dp(10);                    // 框外左右各 10px
        mlp.rightMargin = dp(10);
        moreHdr.setLayoutParams(mlp);
        ctl.addView(moreHdr);
        ctl.addView(pl);
        ctl.addView(pre);
        ctl.addView(preVal);
        if (foot != null) foot.setVisibility(View.GONE);      // 舊兩行收埋（野唔會重複）
        content.addView(ctl);
        // ---- BASS BOOST（浮窗：0–12 dB，VOLUME 風格）----
        LinearLayout bassRow = new LinearLayout(this);
        bassRow.setOrientation(LinearLayout.HORIZONTAL);
        bassRow.setGravity(Gravity.CENTER_VERTICAL);
        bassRow.setPadding(0, dp(6), 0, dp(2));
        TextView bassLbl = Ui.text(this, "BASS BOOST", 12f, 0xFFA0A89A);
        bassLbl.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        bassLbl.setLetterSpacing(0.06f);
        bassLbl.setWidth(dp(96));
        final TextView bassVal = Ui.text(this, "", 13f, 0xFFFFFFFF);
        bassVal.setMinWidth(dp(70));
        bassVal.setGravity(Gravity.CENTER);
        final VolBar bassBar2 = new VolBar(this);
        bassBar2.setMax(12);                      // 一格 = 1 dB（0–12 dB）
        int bb0 = Math.max(0, Math.min(12, sp.getInt("bass_boost", 0)));
        bassBar2.setProgress(bb0);
        bassVal.setText(bb0 + " dB");
        bassBar2.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(android.widget.SeekBar s, int v, boolean fromUser) {
                pokeActivity();
                bassVal.setText(v + " dB");
            }

            public void onStartTrackingTouch(android.widget.SeekBar s) {
            }

            public void onStopTrackingTouch(android.widget.SeekBar s) {
                int dbv = s.getProgress();
                sp.edit().putInt("bass_boost", dbv).apply();
                SysEq.bassBoostDb = dbv;
                pokeActivity();
                try {                       // 直接套用，唔靠 fingerprint／cache
                    if (sys == null) sys = EqService.get(FloatEqService.this);
                    if (sys != null && dsp != null) {
                        sys.applyCurve(dsp.gains(), dsp.preampDb(), false);
                    }
                } catch (Throwable t) {
                    say("Bass Boost 套用失敗：" + t.getClass().getSimpleName());
                }
                schedule(true, true);
                say("Bass Boost：" + dbv + " dB");
            }
        });
        LinearLayout.LayoutParams bbLp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        bbLp.leftMargin = dp(8);
        bbLp.rightMargin = dp(8);
        // BASS BOOST 放去原本 VOLUME 嘅位置（同一 horizontal row，DB GAIN 左邊）
        LinearLayout.LayoutParams bbLp2 = new LinearLayout.LayoutParams(0, dp(46), 1f);
        bbLp2.leftMargin = dp(8);
        bbLp2.rightMargin = dp(8);
        ctl.addView(bassLbl, 0);
        ctl.addView(bassBar2, 1, bbLp2);
        ctl.addView(bassVal, 2);


        // ---- LIMITER（浮窗：防削波）----
        LinearLayout limRow = new LinearLayout(this);
        limRow.setOrientation(LinearLayout.HORIZONTAL);
        limRow.setGravity(Gravity.CENTER_VERTICAL);
        limRow.setPadding(0, dp(2), 0, dp(2));
        TextView limLbl = Ui.text(this, "LIMITER", 12f, 0xFFA0A89A);
        limLbl.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        limLbl.setLetterSpacing(0.06f);
        limLbl.setWidth(dp(96));
        final Button limBtn = Ui.segment(this, sp.getBoolean("limiter", false) ? "ON" : "OFF");
        limBtn.setLayoutParams(new LinearLayout.LayoutParams(dp(96), dp(46)));
        limBtn.setOnClickListener(v -> {
            boolean on = !sp.getBoolean("limiter", false);
            sp.edit().putBoolean("limiter", on).apply();
            SysEq.limiterOn = on;
            limBtn.setText(on ? "ON" : "OFF");
            schedule(true, true);
            say(on ? "Limiter：開（峰值 ≤ -0.5 dBFS）" : "Limiter：關");
        });
        limRow.addView(limLbl);
        limRow.addView(limBtn);
        TextView limHint = Ui.text(this, "峰值超過 -0.5 dBFS 自動扣前級", 11f, 0xFFA0A89A);
        LinearLayout.LayoutParams lhLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lhLp.leftMargin = dp(10);
        limRow.addView(limHint, lhLp);
        content.addView(limRow);

        // 所有子 view（含 preset／測試音／重掛／slider）都掛觸控 → 任何操作都 reset idle 計時
        try {
            hookAllTouches(root);      // 全部建好之後
        } catch (Throwable ignored) {
        }



        // ---- Footer（EQ-Control 款式）：綠點 + 段讀數 + OUTPUT -x.x dBFS ----
        LinearLayout fRow = new LinearLayout(this);
        fRow.setOrientation(LinearLayout.HORIZONTAL);
        fRow.setGravity(Gravity.CENTER_VERTICAL);
        fRow.setBackground(bg(0xFF1A1F16, dp(14)));
        fRow.setPadding(dp(12), dp(7), dp(12), dp(7));
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.topMargin = dp(8);
        fRow.setLayoutParams(flp);
        TextView fdot = Ui.text(this, "●", 12f, 0xFFB6FF3B);
        fRow.addView(fdot);
        outTv = Ui.text(this, "未有選段", 17f, 0xFFA0A89A);
        outTv.setSingleLine(true);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        olp.leftMargin = dp(8);
        fRow.addView(outTv, olp);
        out2Tv = Ui.text(this, "OUTPUT 0.0 dBFS", 17f, 0xFFA0A89A);
        out2Tv.setSingleLine(true);
        fRow.addView(out2Tv);
        content.addView(fRow);
        footerRow = fRow;

        root.addView(panel);

        // ---- 收埋後嘅 app icon 圓掣 ----
        icon = new ImageView(this);
        try {
            icon.setImageDrawable(getPackageManager().getApplicationIcon(getPackageName()));
        } catch (Throwable ignored) {
            icon.setImageResource(android.R.drawable.ic_menu_slideshow);
        }
        icon.setBackground(bg(0xCC1E1E1E, iconSize / 2f));
        icon.setPadding(dp(6), dp(6), dp(6), dp(6));
        icon.setLayoutParams(new FrameLayout.LayoutParams(iconSize, iconSize));
        icon.setOnClickListener(v -> {
            if (!dragged) setMinimized(false);
        });
        root.addView(icon);

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = clampX(sp.getInt(K_X, screenW - panelW - insetSide()));
        lp.y = clampY(sp.getInt(K_Y, dp(180)));

        View.OnTouchListener drag = new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = e.getRawX();
                        downRawY = e.getRawY();
                        startX = lp.x;
                        startY = lp.y;
                        dragged = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int nx = (int) (startX + (e.getRawX() - downRawX));
                        int ny = (int) (startY + (e.getRawY() - downRawY));
                        if (Math.abs(e.getRawX() - downRawX) > dp(6) || Math.abs(e.getRawY() - downRawY) > dp(6)) {
                            dragged = true;
                        }
                        lp.x = clampX(nx);
                        lp.y = clampY(ny);
                        if (lp.x != lastSavedX || lp.y != lastSavedY) {          // 拖動中即刻存
                            lastSavedX = lp.x;
                            lastSavedY = lp.y;
                            if (panel != null && panel.getVisibility() == View.VISIBLE) {
                                sp.edit().putInt(K_X, lp.x).putInt(K_Y, lp.y).apply();
                            } else {
                                sp.edit().putInt("icon_fx", lp.x).putInt("icon_fy", lp.y).apply();
                                AppLog.d("EQ", "icon pos saved " + lp.x + "," + lp.y);
                            }
                        }
                        if (wm != null) {
                            try {
                                wm.updateViewLayout(root, lp);
                            } catch (Throwable ignored) {
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (panel != null && panel.getVisibility() == View.VISIBLE) {
            sp.edit().putInt(K_X, lp.x).putInt(K_Y, lp.y).apply();          // 記面板位
        } else {
            sp.edit().putInt("icon_fx", lp.x).putInt("icon_fy", lp.y).apply();  // 記圓掣位
        }
                        // OnTouchListener 食咗事件 → OnClickListener 唔會 fire，所以自己判斷「撳一下」
                        if (!dragged && v == icon) setMinimized(false);
                        return true;
                    default:
                        return false;
                }
            }
        };
        head.setOnTouchListener(null);
        title.setText("Equalizer");      // 標題列 = 拖手（唔可以係按鈕嘅 parent，否則食咗掣嘅點擊）
        title.setOnTouchListener(drag);
        icon.setOnTouchListener(drag);       // 收埋後拖 icon 一樣搬得

        // ADD 前先定圓掣幾何（如果上次係 minimize）→ 唔會第一格喺左上再飛過去
        if (sp.getBoolean("fmin", true)) {
            iconSize = iconSizePx();
            lp.width = iconSize;
            lp.height = iconSize;
            lp.x = clampX(sp.getInt("icon_fx", screenW - inR - iconSize - dp(12)));
            lp.y = clampY(sp.getInt("icon_fy", (screenH - iconSize) / 2));
            AppLog.d("EQ", "pre-add icon at " + lp.x + "," + lp.y);
        }
        // ZERO-FLASH：先隱形 addView，定位好之後才顯示（避免半秒閃左上）
        root.setVisibility(View.INVISIBLE);
        try {
            wm.addView(root, lp);
            root.post(() -> root.setVisibility(View.VISIBLE));   // 定位好才顯示
        } catch (Throwable t) {
            stopSelf();
            return;
        }
        applyExtras(sp.getBoolean("more_open", false));   // 預設收埋
        setMinimized(sp.getBoolean(K_MIN, false));
        syncPreamp();
        syncToneUi();
        syncAbUi();
    }

        private int clampX(int x) {
        int min = inL;
        int max = Math.max(min, screenW - inR - (lp == null ? 0 : lp.width));
        return Math.max(min, Math.min(x, max));
    }

        private int clampY(int y) {
        int min = inT;
        int max = Math.max(min, screenH - inB - (lp == null ? 0 : lp.height));
        return Math.max(min, Math.min(y, max));
    }

    /** 收埋 = 只剩 app icon 圓掣（同 app icon 同一個圖）。 */
    private void setMinimized(boolean min) {
        minimizedNow = min;
        if (!min) lastTouchAt = System.currentTimeMillis();   // 展開即重新計時（否則即時再收埋）
        if (panel == null || icon == null) return;
        iconSize = iconSizePx();                                 // 每次收／開都跟設定
        icon.setLayoutParams(new FrameLayout.LayoutParams(iconSize, iconSize));
        icon.setBackground(bg(0xCC1E1E1E, iconSize / 2f));
        // ⚠️ 先定窗口大細，再 clamp 位置：否則 clamp 用全屏寬度計上限 → 位置必被壓去左上
        lp.width = min ? iconSize : Math.max(dp(320), panelW);
        lp.height = min ? iconSize : Math.max(dp(240), panelH);
        if (min) {                                               // 收埋 = 返去上次圓掣位置
            lp.x = clampX(sp.getInt("icon_fx", screenW - inR - iconSize - dp(12)));
            AppLog.d("EQ", "icon restored " + lp.x + "," + lp.y);
            lp.y = clampY(sp.getInt("icon_fy", (screenH - iconSize) / 2));
        } else {                                                 // 展開 = 返去上次面板位置
            lp.x = clampX(sp.getInt(K_X, lp.x));
            lp.y = clampY(sp.getInt(K_Y, lp.y));
        }
        if (scrollBox != null) {                                 // 還原上次滾動位置
            final int sy = sp.getInt("float_scroll", 0);
            scrollBox.post(() -> scrollBox.scrollTo(0, sy));
        }
        panel.setVisibility(min ? View.GONE : View.VISIBLE);
        icon.setVisibility(min ? View.VISIBLE : View.GONE);
        applyPanelGeometry();
        sp.edit().putBoolean(K_MIN, min).apply();
    }

    // ------------------------------------------------------------ EQ

    private void loadState() {
        // "gains" 永遠 = 實際生效曲線（主頁／開機／本服務都係寫呢個）→ base 由佢減返旋鈕偏移推出來，
        // 咁無論邊個寫 "gains" 都唔會出現「顯示同實際唔同」。
        float[] g = new float[Bands.N];
        String s = sp.getString("gains", "");
        if (s != null && !s.isEmpty()) {
            String[] p = s.split(",");
            for (int i = 0; i < Bands.N && i < p.length; i++) {
                try {
                    g[i] = Float.parseFloat(p[i].trim());
                } catch (Throwable ignored) {
                }
            }
        }
        for (int k = 0; k < toneOff.length; k++) {
            toneOff[k] = QuickTone.clamp(sp.getFloat(K_TONE[k], 0f));
        }
        baseGains = new float[Bands.N];
        for (int i = 0; i < Bands.N; i++) {
            baseGains[i] = QuickTone.baseFor(i, g[i], toneOff);
        }
        dsp.setGainsNow(QuickTone.apply(baseGains, toneOff));
        dsp.setPreampDb(sp.getInt("preamp", 12) - 12);      // 0 = 中間（同主畫面 SeekBar 一致）
    }

    /** 由 base ＋ 旋鈕偏移重算實際曲線（唔即刻寫入系統）。 */
    private void recomputeTone() {
        dsp.setGainsNow(QuickTone.apply(baseGains, toneOff));
        if (bandTvs != null) {
            for (int i = 0; i < Bands.N; i++) {
                if (bandTvs[i] != null) {
                    float v = dsp.get(i);
                    bandTvs[i].setText(Bands.LABEL[i] + "  " + Presets.Fmt.sign(v));
                    bandTvs[i].setTextColor(Math.abs(v) < 0.05f ? 0xFFA0A89A
                            : (v > 0 ? 0xFFB6FF3B : 0xFFFF8A5B));
                }
            }
        }
        if (eqv != null) {
            eqv.markDirty();
            eqv.invalidate();
        }
    }

    private void saveState() {
        StringBuilder sb = new StringBuilder();
        float[] g = dsp.gains();
        for (int i = 0; i < g.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(String.format(java.util.Locale.US, "%.1f", g[i]));
        }
        SharedPreferences.Editor e = sp.edit()
                .putString("gains", sb.toString())          // 實際生效（主頁／開機照用）
                .putInt("preamp", (int) Math.round(dsp.preampDb()) + 12);
        for (int k = 0; k < toneOff.length; k++) {
            e.putFloat(K_TONE[k], toneOff[k]);
        }
        e.apply();
    }

    private void stepPreamp(int d) {
        double v = Math.max(-12, Math.min(12, dsp.preampDb() + d));
        dsp.setPreampDb(v);
        syncPreamp();
        schedule(true, true);
    }

    // ------------------------------------------------------------ 快速音色旋鈕 / A-B 格

    /** 小加減掣（36dp 高，車機上仍然撳得中）。 */
    private Button miniBtn(String t) {
        Button b = Ui.button(this, t);
        b.setTextSize(20f);
        b.setMinHeight(dp(36));
        b.setMinimumHeight(dp(36));
        b.setPadding(0, 0, 0, 0);
        return b;
    }

    /** 一撳加減一個旋鈕；即刻改聲＋存 prefs。 */
    private void stepTone(int k, float d) {
        if (k < 0 || k >= toneOff.length) return;
        toneOff[k] = QuickTone.clamp(toneOff[k] + d);
        recomputeTone();
        syncToneUi();
        schedule(true, true);
        say(QuickTone.LABEL[k] + "  " + Presets.Fmt.sign(toneOff[k]) + " dB");
    }

    /** 把 5 個旋鈕嘅數值／顏色同步（0 = 灰、非 0 = 綠）。 */
    private void syncToneUi() {
        for (int k = 0; k < toneValTv.length; k++) {
            TextView t = toneValTv[k];
            if (t == null) continue;
            float v = toneOff[k];
            t.setText(Presets.Fmt.sign(v));
            t.setTextColor(Math.abs(v) < 0.05f ? 0xFFA0A89A : 0xFFB6FF3B);
        }
        if (masterValTv != null) {
            masterValTv.setText(String.format(java.util.Locale.US, "%+.0f", dsp.preampDb()));
            masterValTv.setTextColor(Math.abs(dsp.preampDb()) < 0.05 ? 0xFFA0A89A : 0xFFB6FF3B);
        }
    }

    /** A/B 格：讀返現有自訂曲線表。 */
    private java.util.Map<String, float[]> abSlots() {
        return Presets.readUser(sp.getString("user_presets", ""));
    }

    /** 存入 A/B（存你而家聽到嘅曲線；存完把偏移併入 base，免得再扭時重複加）。 */
    private void saveSlot(String key) {
        java.util.Map<String, float[]> u = abSlots();
        u.put(key, dsp.gains().clone());
        sp.edit().putString("user_presets", Presets.writeUser(u)).apply();
        baseGains = dsp.gains().clone();
        java.util.Arrays.fill(toneOff, 0f);
        recomputeTone();
        syncToneUi();
        abActive = key;
        syncAbUi();
        saveState();
        say("已存入 " + key + "（長按可以覆寫）");
    }

    /** 一撳切換 A/B；空格 = 直接存現時曲線。 */
    private void recallSlot(String key) {
        java.util.Map<String, float[]> u = abSlots();
        float[] g = u.get(key);
        if (g == null || g.length < Bands.N) {
            saveSlot(key);
            return;
        }
        baseGains = g.clone();
        java.util.Arrays.fill(toneOff, 0f);
        recomputeTone();
        syncToneUi();
        abActive = key;
        syncAbUi();
        schedule(true, true);
        say("已切換 " + key + " 曲線");
    }

    /** A/B 掣外觀：有存 = 綠框；現時用緊 = 加 ✓；空 = 暗。 */
    private void syncAbUi() {
        java.util.Map<String, float[]> u = abSlots();
        for (int i = 0; i < abBtn.length; i++) {
            if (abBtn[i] == null) continue;
            String k = AB[i];
            boolean filled = u.containsKey(k);
            boolean act = k.equals(abActive);
            abBtn[i].setText(filled ? (act ? k + " ✓" : k) : "+" + k);
            Ui.setAccent(abBtn[i], filled);
            abBtn[i].setAlpha(filled ? 1f : 0.55f);
        }
    }

    private void syncPreamp() {
        if (preVal != null) {
            preVal.setText(String.format(java.util.Locale.US, "%+.1f dB", dsp.preampDb()));
        }
        if (masterValTv != null) {
            masterValTv.setText(String.format(java.util.Locale.US, "%+.0f", dsp.preampDb()));
            masterValTv.setTextColor(Math.abs(dsp.preampDb()) < 0.05 ? 0xFFA0A89A : 0xFFB6FF3B);
        }
    }

    private void syncOnBtn() {
        if (onBtn == null) return;
        boolean on = sys != null && sys.isOn();
        onBtn.setText(on ? "EQ  ON" : "EQ  OFF");
        onBtn.setBackground(roundBg(on));      // 開 = 黃底、關 = 黑底
        if (eqv != null) eqv.setEngineOn(on);   // EQ 關 → 柱變暗
        onBtn.setTextColor(on ? 0xFFB6FF3B : 0xFFA0A89A);
        onBtn.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        try { onBtn.setLetterSpacing(0.06f); } catch (Throwable ignored) {}
    }

    /** 引擎狀態（段數／真受控）顯示喺懸浮面（EQ 引擎相關全部喺呢層）。 */
    private void syncEng() {
        if (engTv == null) return;
        try {
            if (sys == null) sys = EqService.get(this);
            String s = sys == null ? "引擎：未掛上" : sys.status();
            if (sys != null && sys.ready()) {
                int rc = 0;
                for (boolean b : sys.realBands()) {
                    if (b) rc++;
                }
                s += "　·　真受控 " + rc + "/" + Bands.N;
            }
            final String t = s;
            engTv.post(() -> engTv.setText(t));
        } catch (Throwable ignored) {
        }
    }

    /** 拖動中合併成 ~180 ms 一次；放手（verify）即讀返核實。 */
    private void schedule(final boolean sysToo, final boolean verify) {
        if (pendingApply != null) ui.removeCallbacks(pendingApply);
        pendingApply = new Runnable() {
            @Override
            public void run() {
                pendingApply = null;
                if (verify) applyNow(true);
                else applyNow(false);
            }
        };
        ui.postDelayed(pendingApply, verify ? 0 : 180);
    }

    /** 更新底部讀數（段 dB + OUTPUT dBFS）——拖任何一段都會即時更新。 */
    private void refreshReadout() {
        if (outTv == null || out2Tv == null || dsp == null) return;

                int ab = (eqv == null) ? -1 : eqv.activeBand();
                float peak = 0f;
                for (int i = 0; i < Bands.N; i++) {
                    peak = Math.max(peak, dsp.get(i));
                }
                peak += (float) dsp.preampDb();
                outTv.setText(ab >= 0
                        ? (Bands.LABEL[ab] + "  " + Presets.Fmt.sign(dsp.get(ab)) + " dB")
                        : "未有選段");
                out2Tv.setText("OUTPUT " + String.format(java.util.Locale.US, "%.1f", -(Math.max(0f, peak))) + " dBFS");
                }

    private void applyNow(boolean verify) {
        if (sys == null) sys = EqService.get(this);
        if (sys == null) return;
        refreshReadout();
        if (!sys.ready()) sys.attach(sp.getBoolean("hal_only", false));
        if (!sys.ready()) return;
        EqService.start(this);
        sys.applyCurve(dsp.gains(), dsp.preampDb(), verify);
        saveState();
        syncEng();
        if (verify) {
            if (eqv != null) {
                eqv.setSysInfo(sys.freqs(), sys.status());
                eqv.setRealBands(sys.realBands());
            }
        }
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        ui.post(this::refit);            // 螢幕／info bar 變（例如轉向、車機換版面）→ 自動重新夾位
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        alive = true;
        ensureWindow();
        Watchdog.schedule(this);
        // 設定改動（Bass Boost／Limiter／圓掣尺寸）後，重算並套用目前曲線
        try {
            if (dsp != null) schedule(true, true);
        } catch (Throwable ignored) {
        }
        return START_STICKY;   // 被系統殺都自動重開（圓掣長期顯示）
    }

    @Override
    public void onDestroy() {
        alive = false;
        try {
            if (sp.getBoolean("float_on", true)) {
                startForegroundService(new android.content.Intent(this, FloatEqService.class));
            }
        } catch (Throwable ignored) {
        }
        tone.stop();
        try {
            if (wm != null && root != null) wm.removeView(root);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static void show(Context c) {
        try {
            c.startService(new Intent(c, FloatEqService.class));
        } catch (Throwable ignored) {
        }
    }

    public static void hide(Context c) {
        try {
            c.stopService(new Intent(c, FloatEqService.class));
        } catch (Throwable ignored) {
        }
    }
}
