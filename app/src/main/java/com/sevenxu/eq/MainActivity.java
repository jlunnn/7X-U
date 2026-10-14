package com.sevenxu.eq;

import android.Manifest;
import androidx.appcompat.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 7X=U 主畫面：31 段 DSP 等化器 + 測試音／檔案／咪三個來源。
 *
 * <p>無網絡、無帳號、無上傳：所有設定存喺本機 SharedPreferences。</p>
 */
public class MainActivity extends AppCompatActivity {

    private static final String PREF = "sevenxu_eq";
    private static final String K_GAINS = "gains";
    private static final String K_USER = "user_presets";
    private static final String K_VOL = "vol";
    private static final String K_PRE = "preamp";
    private static final String K_ROUTE = "route";
    private static final String K_MODE = "mode";
    private static final String K_TONE = "tone";
    private static final String K_FOLLOW = "follow";
    private static final String K_SYS_ON = "sys_on";
    private static final String K_BOOT = "boot_auto";
    private static final String K_AUTO_OPEN = "auto_on_open";
    private static final String K_FILE = "file";

    private SharedPreferences sp;
    private Routes routes;
    private Engine engine;
    private Dsp dsp;
    private EqView eqView;

    private final Button[] routeBtn = new Button[2];
    private final Button[] modeBtn = new Button[3];
    private Button playBtn, followBtn, abBtn, saveBtn, pickBtn;
    private SeekBar volBar, preBar;
    private TextView volVal, preVal, statusTv, hintTv;
    private LinearLayout presetRow;

    private int route = Routes.INNER;
    private int mode = Engine.M_TONE;
    private boolean follow;
    private final Map<String, float[]> user = new LinkedHashMap<>();
    private String notice;
    private long noticeAt;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SysEq sys;                      // 系統（媒體音樂）全域 EQ
    private Button sysBtn, reapplyBtn, engineBtn, eqSwitch, bootBtn;
    private boolean halOnly;
    private boolean sysOnPref = true;               // EQ 總開關（記住上次狀態）
    private boolean autoBoot;                       // 開機自動開
    private boolean autoOnOpen = true;              // 開 app 自動設 EQ 為 ON
    private LinearLayout mainPage, settingsPage;
    private Button gearBtn;
    private androidx.appcompat.widget.SwitchCompat swBoot, swAutoOpen, swEngine;
    private boolean syncing;
    private TextView setStatusTv;
    private EqFrame eqFrame;
    private android.animation.ValueAnimator pulseAnim;
    private TextView sysTv, sysHead;
    private String sysMsg = "";
    private Runnable sysPending;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences(PREF, MODE_PRIVATE);
        routes = new Routes(this);
        engine = new Engine(this, routes);
        dsp = engine.dsp();
        sys = EqService.get(this);
        user.putAll(Presets.readUser(sp.getString(K_USER, "")));

        routes.dumpOutputs();                        // 診斷：把可用輸出寫入 Logcat
        buildUi();
        loadPrefs();
        refreshButtons();
        AppLog.d("EQ", "app 開：" + android.os.Build.MODEL + " / SDK " + Build.VERSION.SDK_INT);
    }

    // --------------------------------------------------------------- UI 建立

    private void buildUi() {
        LinearLayout container = Ui.col(this);
        container.setBackground(Ui.bg());
        container.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6));

        mainPage = Ui.col(this);
        mainPage.addView(row1());
        mainPage.addView(row2());
        mainPage.addView(eqCard(), Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        mainPage.addView(row3());
        mainPage.addView(row4());
        mainPage.addView(row5());

        settingsPage = buildSettingsPage();
        settingsPage.setVisibility(View.GONE);

        container.addView(mainPage, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, 0));
        container.addView(settingsPage, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, 0));
        setContentView(container);
    }

    // ------------------------------------------------------------- 設定頁

    /** 設定頁：齒輪 logo ＋ 文字標題 ＋ 開關選項 ＋ 現況。 */
    private LinearLayout buildSettingsPage() {
        LinearLayout p = Ui.col(this);
        p.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));

        // 標題列：齒輪 logo ＋「設定」＋ 返主頁
        LinearLayout head = Ui.row(this);
        TextView logo = Ui.text(this, "⚙️", 40f, Ui.ACCENT);
        head.addView(logo);
        head.addView(space(10));
        head.addView(Ui.title(this, "設定"));
        head.addView(space(16));
        TextView sub = Ui.text(this, "7X=U · 31 段媒體 EQ", 14f, Ui.DIM);
        head.addView(sub);
        head.addView(space(0, 1f));
        Button back = Ui.segment(this, "← 主頁");
        back.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 130), Ui.dp(this, 42)));
        back.setOnClickListener(v -> showSettings(false));
        head.addView(back);
        p.addView(head);
        p.addView(Ui.divider(this));

        LinearLayout card = Ui.card(this);
        card.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        card.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));

        card.addView(settingRow(0, "開機自動打開 EQ",
                "車機開機時自動掛上媒體 EQ 並開。⚠️ 車機要派開機廣播才會自動；唔派就要開一次 app（開 app 一樣會自動開）。",
                autoBoot, v -> {
                    autoBoot = v;
                    savePrefs();
                    flash(v ? "開機自動打開 EQ：已開" : "開機自動打開 EQ：已關");
                }));
        card.addView(Ui.divider(this));
        card.addView(settingRow(1, "開 app 自動設 EQ 為開",
                "每次開 app 都自動把 EQ 設為 ON（唔跟上次熄咗嘅狀態）。關掉就可以記住上次狀態。",
                autoOnOpen, v -> {
                    autoOnOpen = v;
                    savePrefs();
                    if (v) {
                        sysOnPref = true;
                        doSysApply(false);
                    }
                    flash(v ? "開 app 自動開 EQ：已開" : "開 app 自動開 EQ：已關（會記住上次狀態）");
                }));
        card.addView(Ui.divider(this));
        card.addView(settingRow(2, "EQ 引擎",
                "自訂 = 自己砌段數（可到 31 段）；車機原生 = 用車機 HAL 嘅段數（通常 5 段）。",
                !halOnly, v -> {
                    halOnly = !v;
                    if (sys != null) sys.attach(halOnly);
                    if (sys != null && sys.ready()) EqService.start(this);
                    doSysApply(true);
                    flash("引擎：" + (halOnly ? "車機原生" : "自訂"));
                }));

        p.addView(card);

        LinearLayout st = Ui.card(this);
        st.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        st.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        st.addView(Ui.label(this, "現況"));
        setStatusTv = Ui.text(this, "", 14f, Ui.DIM);
        setStatusTv.setPadding(0, Ui.dp(this, 6), 0, 0);
        st.addView(setStatusTv);
        p.addView(st);
        return p;
    }

    /** 一行設定：左邊標題＋說明、右邊開關。which：0=開機自動開 1=開 app 自動開 2=引擎 */
    private View settingRow(int which, String title, String hint, boolean checked,
                            java.util.function.Consumer<Boolean> onChange) {
        LinearLayout r = Ui.row(this);
        r.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        LinearLayout left = Ui.col(this);
        left.addView(Ui.text(this, title, 17f, Ui.TEXT));
        TextView h = Ui.text(this, hint, 12f, Ui.DIM);
        h.setPadding(0, Ui.dp(this, 2), 0, 0);
        left.addView(h);
        r.addView(left, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        androidx.appcompat.widget.SwitchCompat sw = new androidx.appcompat.widget.SwitchCompat(this);
        sw.setChecked(checked);
        sw.setText(checked ? "開" : "關");
        sw.setTextSize(15f);
        sw.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
        sw.setOnCheckedChangeListener((b, v) -> {
            b.setText(v ? "開" : "關");
            if (syncing) return;              // 由程式設定（開 app 讀返設定）→ 唔當用戶改
            onChange.accept(v);
        });
        if (which == 0) swBoot = sw;
        else if (which == 1) swAutoOpen = sw;
        else swEngine = sw;
        r.addView(sw);
        return r;
    }

    /** 開 app 讀完 prefs → 把設定頁嘅開關同步做真實狀態（唔會觸發 onChange）。 */
    private void syncSettings() {
        syncing = true;
        try {
            if (swBoot != null) {
                swBoot.setChecked(autoBoot);
                swBoot.setText(autoBoot ? "開" : "關");
            }
            if (swAutoOpen != null) {
                swAutoOpen.setChecked(autoOnOpen);
                swAutoOpen.setText(autoOnOpen ? "開" : "關");
            }
            if (swEngine != null) {
                swEngine.setChecked(!halOnly);
                swEngine.setText(!halOnly ? "開" : "關");
            }
        } finally {
            syncing = false;
        }
    }

    private void showSettings(boolean on) {
        if (mainPage == null || settingsPage == null) return;
        mainPage.setVisibility(on ? View.GONE : View.VISIBLE);
        settingsPage.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) stopPulse();
        else refreshSysUi();
        flash(on ? "設定頁（⚠️ = 有車機限制）" : "主頁");
    }

    /** 第 1 行：標題 + 系統（媒體音樂）EQ 狀態。 */
    private View row1() {
        LinearLayout r = Ui.row(this);
        r.setLayoutParams(new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        r.addView(Ui.title(this, "🎚 7X=U"));
        r.addView(space(18));

        // EQ 總開關（大掣、狀態寫喺掣面）—— 放喺狀態字**之前**：狀態字長短變都唔會令個掣走位
        eqSwitch = Ui.button(this, "EQ 開");
        eqFrame = new EqFrame(Ui.dp(this, 4), Ui.dp(this, 10));
        eqFrame.setOn(true);
        eqSwitch.setBackground(eqFrame);
        eqSwitch.setTextColor(EqFrame.YELLOW_HOT);
        eqSwitch.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 150), Ui.dp(this, 46)));
        eqSwitch.setOnClickListener(v -> toggleSys());
        r.addView(eqSwitch);
        r.addView(space(18));

        sysHead = Ui.text(this, "媒體音樂 EQ：未掛上", 17f, Ui.DIM);
        sysHead.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        r.addView(sysHead);
        r.addView(space(18));

        // 齒輪 → 設定頁
        gearBtn = Ui.segment(this, "⚙️ 設定");
        gearBtn.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 132), Ui.dp(this, 42)));
        gearBtn.setOnClickListener(v -> showSettings(true));
        r.addView(gearBtn);
        return hbar(r, 50);
    }

    /** 一行（內容比畫面闊都撳得到）＋ 固定高度，唔會被切走。 */
    private View hbar(View inner, int hDp) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, hDp)));
        hs.addView(inner);
        return hs;
    }

    /** 第 2 行：preset chips + 儲存。 */
    private View row2() {
        LinearLayout r = Ui.row(this);
        r.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 44)));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        presetRow = Ui.row(this);
        // ⚠️ 一定要 WRAP_CONTENT：ScrollView 嘅子 View 預設 MATCH_PARENT → 內容超出都計唔到
        //    可滾動範圍（scroll range = 0），第 13 個 chip 之後嘅自訂 preset 永遠撳唔到（實測踩過）。
        presetRow.setLayoutParams(new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        hs.addView(presetRow);
        r.addView(hs, Ui.lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        saveBtn = Ui.segment(this, "💾 儲存");
        saveBtn.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 92), Ui.dp(this, 40)));
        saveBtn.setOnClickListener(v -> savePresetDialog());
        r.addView(saveBtn);
        buildPresetChips();
        return r;
    }

    private View eqCard() {
        LinearLayout card = Ui.card(this);
        card.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
        eqView = new EqView(this, dsp);
        eqView.setListener(new EqView.Listener() {
            @Override
            public void onGain(int band, float db) {
                if (follow) engine.setToneBand(band);
                scheduleSysApply();                 // 拖動即時反映落車機 EQ
            }

            @Override
            public void onCommit(int band, float db) {
                savePrefs();
                doSysApply(true);                   // 放手讀返核實
            }

            @Override
            public void onActive(int band) {
                if (follow) engine.setToneBand(band);
            }
        });
        card.addView(eqView, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return card;
    }

    /** 第 3 行：測試音（撳一下播、再撳停）＋ 1K Tone ＋ 媒體 EQ 對照 ＋ 重新掛上 ＋ 引擎。 */
    private View row3() {
        LinearLayout r = Ui.row(this);
        r.setLayoutParams(new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        r.addView(Ui.label(this, "測試音"));
        for (int i = 0; i < Gen.KIND_NAME.length; i++) {
            final int k = i;
            Button btn = Ui.segment(this, Gen.KIND_NAME[i]);
            btn.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 96), Ui.dp(this, 40)));
            btn.setOnClickListener(v -> toggleKind(k));
            r.addView(btn);
            toneBtn[k] = btn;
        }
        r.addView(space(12));

        // 1 kHz 單頻參考音（舊「跟手指試聽」位置）
        followBtn = Ui.segment(this, "1K Tone");
        followBtn.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 140), Ui.dp(this, 40)));
        followBtn.setOnClickListener(v -> toggleOneK());
        r.addView(followBtn);
        r.addView(space(12));

        r.addView(space(14));

        reapplyBtn = Ui.segment(this, "🔄 重新掛上");
        reapplyBtn.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 150), Ui.dp(this, 40)));
        reapplyBtn.setOnClickListener(v -> {
            sys.attach(halOnly);
            sysMsg = sys.ready() ? "重新掛上成功：" + sys.status() : "⚠️ " + sys.status();
            if (sys.ready()) EqService.start(this);
            doSysApply(true);
        });
        r.addView(reapplyBtn);
        r.addView(space(8));

        // 引擎切換：自訂多段（DynamicsProcessing）／車機原生（HAL Equalizer）
        engineBtn = Ui.segment(this, "引擎：自訂");
        engineBtn.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 170), Ui.dp(this, 40)));
        engineBtn.setOnClickListener(v -> {
            halOnly = !halOnly;
            engineBtn.setText(halOnly ? "引擎：車機原生" : "引擎：自訂");
            sysMsg = sys.attach(halOnly)
                    ? "已切換引擎：" + sys.status()
                    : "⚠️ 呢個引擎掛唔到：" + sys.status();
            if (sys.ready()) EqService.start(this);
            doSysApply(true);
            flash(sysMsg);
        });
        r.addView(engineBtn);
        return hbar(r, 46);
    }

    private final Button[] toneBtn = new Button[4];

    /** 第 4 行：前級 + 音量。 */
    private View row4() {
        LinearLayout r = Ui.row(this);
        r.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 48)));
        r.addView(Ui.label(this, "前級"));
        preBar = new SeekBar(this);
        preBar.setMax(24);
        preBar.setProgress(12);
        preBar.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 190), Ui.dp(this, 40)));
        preVal = Ui.text(this, "0 dB", 15f, Ui.TEXT);
        preVal.setMinWidth(Ui.dp(this, 62));
        preBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int v, boolean fromUser) {
                double db = v - 12;
                dsp.setPreampDb(db);
                preVal.setText(Presets.Fmt.sign((float) db) + " dB");
                if (fromUser) savePrefs();
            }

            public void onStartTrackingTouch(SeekBar s) {
            }

            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        r.addView(preBar);
        r.addView(preVal);
        r.addView(space(16));

        r.addView(Ui.label(this, "音量"));
        volBar = new SeekBar(this);
        volBar.setMax(100);
        volBar.setProgress(70);
        volBar.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 240), Ui.dp(this, 40)));
        volVal = Ui.text(this, "70%", 15f, Ui.TEXT);
        volVal.setMinWidth(Ui.dp(this, 56));
        volBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int v, boolean fromUser) {
                engine.setVolPct(v);
                volVal.setText(v + "%");
                if (fromUser) savePrefs();
            }

            public void onStartTrackingTouch(SeekBar s) {
            }

            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        r.addView(volBar);
        r.addView(volVal);
        r.addView(space(0, 1f));

        Button flat = Ui.segment(this, "↺ 全段歸零");
        flat.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 130), Ui.dp(this, 40)));
        flat.setOnClickListener(v -> {
            dsp.setGainsNow(Presets.zeros());
            eqView.markDirty();
            eqView.invalidate();
            savePrefs();
            flash("31 段已歸零");
        });
        r.addView(flat);
        return r;
    }

    /** 第 5 行：狀態（單行、封頂，唔遮住控制區）。 */
    private View row5() {
        LinearLayout c = Ui.col(this);
        c.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 40)));
        statusTv = Ui.text(this, "未播放", 13f, Ui.DIM);
        statusTv.setMaxLines(1);
        statusTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        hintTv = Ui.text(this, "", 12f, Ui.WARN);
        hintTv.setMaxLines(1);
        hintTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        c.addView(statusTv);
        c.addView(hintTv);
        return c;
    }

    private View space(int wDp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, wDp), 1));
        return v;
    }

    private View space(int wDp, float weight) {
        View v = new View(this);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(Ui.dp(this, wDp), 1);
        p.weight = weight;
        v.setLayoutParams(p);
        return v;
    }

    private LinearLayout.LayoutParams size(int wDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(Ui.dp(this, wDp), Ui.dp(this, 40));
        p.leftMargin = Ui.dp(this, 4);
        return p;
    }

    // ------------------------------------------------------------------ 行為

    private void switchMode(int m) {
        if (m == Engine.M_MIC && !hasPerm(Manifest.permission.RECORD_AUDIO)) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 2);
            flash("撳「🎤 咪」要先批准錄音權限，批完再撳一次");
            return;
        }
        if (m == Engine.M_FILE) ensureMediaPerm();
        boolean wasPlaying = engine.playing();
        mode = m;
        refreshButtons();
        savePrefs();
        if (wasPlaying) engine.start(mode, route);
    }

    private void togglePlay() {
        if (engine.playing()) {
            engine.stop();
            flash("已停止");
        } else {
            if (mode == Engine.M_FILE && engine.file() == null) {
                pickFile();
                return;
            }
            if (mode == Engine.M_MIC && !hasPerm(Manifest.permission.RECORD_AUDIO)) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 2);
                flash("要批准錄音權限先可以用咪");
                return;
            }
            boolean ok = engine.start(mode, route);
            flash(ok ? "開始播放（" + Routes.friendly(route) + "）" : "❌ 開唔到：睇下面狀態行");
        }
        refreshButtons();
    }

    // --------------------------------------------------- 系統（媒體音樂）EQ

    /** 現時 31 段曲線 → 系統各段目標 dB（取曲線喺該段中心頻率嘅值）。 */
    private float[] sysTarget() {
        if (sys == null || !sys.ready()) return null;
        float[] freqs = sys.freqs();
        float[] g = dsp.gains();
        float[] t = new float[freqs.length];
        double pre = dsp.preampDb();                    // 前級都算入媒體 EQ 嘅整體增益
        for (int i = 0; i < freqs.length; i++) {
            double db = Dsp.responseDb(g, freqs[i], 44100) + pre;
            t[i] = (float) Math.max(-15, Math.min(15, db));
        }
        return t;
    }

    /** 掛上（未掛就試掛）＋ 套用現時曲線。 */
    private void doSysApply(boolean verify) {
        if (sys == null) return;
        if (!sys.ready()) {
            boolean ok = sys.attach();
            sysMsg = ok ? "系統 EQ 已掛上：" + sys.status() : "⚠️ " + sys.status();
            if (ok) {
                EqService.start(this);
                if (!EqService.LAST_ERROR.isEmpty()) sysMsg += "（" + EqService.LAST_ERROR + "）";
            }
        }
        if (sys.ready()) {
            // 跟 EQ 總開關：關 = 完全 bypass（原聲），開 = 套用曲線
            if (sysOnPref && !sys.isOn()) sys.setEnabled(true);
            if (!sysOnPref && sys.isOn()) sys.setEnabled(false);
            float[] t = sysTarget();
            if (t != null) {
                String res = sys.applyCurve(dsp.gains(), dsp.preampDb(), verify);
                sysMsg = sysOnPref ? res : "EQ 關（原聲）";
                if (verify && sysOnPref) flash("系統 EQ：" + res);
            }
        }
        refreshSysUi();
    }

    /** 拖動中：合併成 ~160 ms 一次，唔好每個 block 打爆 HAL。 */
    private void scheduleSysApply() {
        if (sysPending != null) ui.removeCallbacks(sysPending);
        sysPending = new Runnable() {
            @Override
            public void run() {
                sysPending = null;
                doSysApply(false);
            }
        };
        ui.postDelayed(sysPending, 160);
    }

    /** EQ 開＝粗線框做動態黃（脈動 0→1→0，900 ms 一循環）。 */
    private void startPulse() {
        if (eqFrame == null) return;
        if (pulseAnim == null) {
            pulseAnim = android.animation.ValueAnimator.ofFloat(0f, 1f);
            pulseAnim.setDuration(900);
            pulseAnim.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            pulseAnim.setRepeatMode(android.animation.ValueAnimator.REVERSE);
            pulseAnim.setInterpolator(new android.view.animation.LinearInterpolator());
            pulseAnim.addUpdateListener(a -> eqFrame.setPulse((float) a.getAnimatedValue()));
        }
        if (!pulseAnim.isRunning()) pulseAnim.start();
    }

    private void stopPulse() {
        if (pulseAnim != null && pulseAnim.isRunning()) pulseAnim.cancel();
        if (eqFrame != null) eqFrame.setPulse(0f);
    }

    private void toggleSys() {
        if (sys == null) return;
        if (!sys.ready()) {
            doSysApply(true);
            return;
        }
        boolean want = !sys.isOn();
        sysOnPref = want;
        boolean ok = sys.setEnabled(want);
        if (!ok) {
            sysMsg = "⚠️ 開關唔成功：" + sys.status();
        } else if (want) {
            doSysApply(true);
            sysMsg = "EQ 已開 — 拖任何一段都會即刻改到車機播緊嘅音樂";
        } else {
            sysMsg = "EQ 已關（原聲直出，唔經 EQ）";
        }
        savePrefs();
        flash(sysMsg);
        refreshSysUi();
    }

    private void refreshSysUi() {
        if (sys == null) return;
        boolean ok = sys.ready();
        boolean on = ok && sys.isOn();
        // 頂欄大字：媒體音樂 EQ 已開／已關
        if (sysHead != null) {
            String s;
            if (!ok) {
                s = "媒體音樂 EQ：❌ 未掛上";
            } else if (on) {
                s = "媒體音樂 EQ：✅ 已開 ｜ " + sys.status();
            } else {
                s = "媒體音樂 EQ：⏸ 已關（原聲）";
            }
            sysHead.setText(s);
            sysHead.setTextColor(!ok ? Ui.DANGER : (on ? Ui.OK : Ui.WARN));
        }
        if (eqSwitch != null) {
            eqSwitch.setText(on ? "EQ 開" : "EQ 關");
            eqSwitch.setTextColor(on ? EqFrame.YELLOW_HOT : Ui.TEXT);
            if (eqFrame != null) eqFrame.setOn(on);
            if (on) startPulse();
            else stopPulse();
        }
        if (bootBtn != null) {
            bootBtn.setText(autoBoot ? "開機自動開：開" : "開機自動開：關");
            Ui.setAccent(bootBtn, autoBoot);
        }
        if (setStatusTv != null) {
            setStatusTv.setText(sys.status() + "\n"
                    + (sys.ready() ? ("EQ：" + (on ? "開" : "關") + "　·　開 app 自動開："
                    + (autoOnOpen ? "開" : "關") + "　·　開機自動打開：" + (autoBoot ? "開" : "關"))
                    : "掛唔到：" + sys.detail()));
        }
        if (abBtn != null) {
            abBtn.setText(on ? "🅰🅱 EQ 已開" : "🅰🅱 EQ 已關");
            Ui.setAccent(abBtn, on);
        }
        if (eqView != null) eqView.setSysInfo(ok ? sys.freqs() : null, on ? sys.status() : "已關");
    }

    private void setFollow(boolean on) {
        follow = on;
        if (on) {
            engine.setToneKind(Gen.SINE);
            int b = eqView.activeBand() >= 0 ? eqView.activeBand() : engine.toneBand();
            engine.setToneBand(b);
            if (!engine.playing() || engine.mode() != Engine.M_TONE) {
                mode = Engine.M_TONE;
                engine.start(Engine.M_TONE, route);
                flash("👂 跟手指：拖邊一段就試聽嗰段（單頻）");
            } else {
                engine.restartIfPlaying();
                flash("👂 跟手指試聽已開");
            }
        } else {
            flash("跟手指試聽已關");
            if (engine.mode() == Engine.M_TONE && engine.playing()) engine.stop();
        }
        refreshButtons();
        savePrefs();
    }

    private void refreshButtons() {
        for (int i = 0; i < toneBtn.length; i++) {
            if (toneBtn[i] == null) continue;
            boolean sel = engine.playing() && engine.mode() == Engine.M_TONE && engine.toneKind() == i;
            Ui.setActive(toneBtn[i], sel);
        }
        if (followBtn != null) {
            boolean oneK = engine.playing() && engine.mode() == Engine.M_TONE
                    && engine.toneKind() == Gen.SINE && engine.toneBand() == BAND_1K;
            Ui.setAccent(followBtn, oneK);
        }
        refreshSysUi();
        String hint;
        if (sys == null || !sys.ready()) {
            hint = "⚠️ 掛唔到車機 EQ — 睇下面狀態行（可以撳「🔄 重新掛上」再試）";
        } else if (!sys.isOn()) {
            hint = "媒體音樂 EQ：已關（原聲）— 撳「🅰🅱 對照」即刻開返";
        } else {
            hint = "媒體音樂 EQ：已開 — 拖任何一段都會即刻改到車機播緊嘅音樂";
        }
        hintTv.setText(hint);
    }

    private static final int BAND_1K = 17;              // Bands.FREQ[17] == 1000 Hz

    /** 測試音種：撳一下播、再撳同一粒就停。 */
    private void toggleKind(int kind) {
        boolean same = engine.playing() && engine.mode() == Engine.M_TONE && engine.toneKind() == kind;
        if (same) {
            engine.stop();
            flash(Gen.KIND_NAME[kind] + " 已停");
        } else {
            engine.setToneKind(kind);
            startTone();
            flash(Gen.KIND_NAME[kind] + " 播放中");
        }
        savePrefs();
        refreshButtons();
    }

    /** 1K Tone：播／停 1 kHz 單頻參考音。 */
    private void toggleOneK() {
        boolean playing1k = engine.playing() && engine.mode() == Engine.M_TONE
                && engine.toneKind() == Gen.SINE && engine.toneBand() == BAND_1K;
        if (playing1k) {
            engine.stop();
            flash("1K Tone 已停");
        } else {
            engine.setToneKind(Gen.SINE);
            engine.setToneBand(BAND_1K);
            startTone();
            flash("1K Tone 播放中（1 kHz）");
        }
        savePrefs();
        refreshButtons();
    }

    /** 開本機測試音（輸出固定用車內媒體通道）。 */
    private void startTone() {
        route = Routes.INNER;
        if (!engine.start(Engine.M_TONE, Routes.INNER)) flash("❌ 開唔到音頻輸出");
    }

    private static String shortName(String s) {
        return s.length() > 12 ? s.substring(0, 11) + "…" : s;
    }

    private void flash(String s) {
        notice = s;
        noticeAt = System.currentTimeMillis();
        tick();
    }

    private Runnable ticker;

    private void tick() {
        if (ticker != null) return;
        ticker = new Runnable() {
            @Override
            public void run() {
                StringBuilder sb = new StringBuilder();
                if (notice != null && System.currentTimeMillis() - noticeAt < 5000) {
                    sb.append(notice).append(" ｜ ");
                }
                sb.append(engine.status());
                if (engine.playing()) {
                    sb.append(" · ").append(engine.trackInfo());
                    sb.append(String.format(java.util.Locale.US, " · 輸出 %.0f dBFS", engine.outRmsDb()));
                }
                if (mode == Engine.M_MIC && engine.playing()) {
                    sb.append(String.format(java.util.Locale.US, " · 咪輸入 %.0f dBFS", engine.inLevelDb()));
                }
                if (sys != null && !sys.ready()) {
                    sb.append(" ｜ ⚠️ 系統 EQ：").append(sys.status());
                } else if (sysMsg != null && !sysMsg.isEmpty()) {
                    sb.append(" ｜ ").append(sysMsg);
                }
                statusTv.setText(sb.toString());
                refreshSysUi();
                eqView.setMicMode(mode == Engine.M_MIC);
                eqView.setSpectrum(mode == Engine.M_MIC && engine.playing() ? engine.spectrum() : null);
                refreshButtons();
                ui.postDelayed(this, 300);
            }
        };
        ui.post(ticker);
    }

    // ------------------------------------------------------------------ 檔案

    private void ensureMediaPerm() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (!hasPerm(Manifest.permission.READ_MEDIA_AUDIO)) {
                requestPermissions(new String[]{Manifest.permission.READ_MEDIA_AUDIO}, 1);
            }
        } else if (!hasPerm(Manifest.permission.READ_EXTERNAL_STORAGE)) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
        }
    }

    private boolean hasPerm(String p) {
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean isAudio(File f) {
        String n = f.getName().toLowerCase();
        return n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".aac") || n.endsWith(".wav")
                || n.endsWith(".ogg") || n.endsWith(".flac") || n.endsWith(".opus") || n.endsWith(".wma");
    }

    private List<File> audioFiles() {
        List<File> out = new ArrayList<>();
        List<File> dirs = new ArrayList<>();
        dirs.add(new File(Environment.getExternalStorageDirectory(), "Music"));
        dirs.add(new File("/sdcard/Music"));
        dirs.add(new File("/sdcard/Download"));
        dirs.add(new File("/sdcard/Podcasts"));
        dirs.add(new File("/sdcard"));
        dirs.add(getFilesDir());
        for (File d : dirs) {
            File[] fs = d.listFiles();
            if (fs == null) continue;
            for (File f : fs) if (f.isFile() && isAudio(f) && !out.contains(f)) out.add(f);
        }
        return out;
    }

    private void pickFile() {
        ensureMediaPerm();
        final List<File> files = audioFiles();
        if (files.isEmpty()) {
            flash("搵唔到音檔（可以放檔案入 Music 或 Download 資料夾）");
            return;
        }
        final String[] names = new String[files.size()];
        for (int i = 0; i < files.size(); i++) {
            names[i] = files.get(i).getName() + "   (" + (files.get(i).length() / 1024) + " KB)";
        }
        new AlertDialog.Builder(this)
                .setTitle("揀音檔（" + files.size() + " 個）")
                .setItems(names, (d, which) -> {
                    engine.setFile(files.get(which));
                    mode = Engine.M_FILE;
                    refreshButtons();
                    savePrefs();
                    if (engine.playing()) engine.start(Engine.M_FILE, route);
                    else flash("已選：" + files.get(which).getName() + "（撳 ▶ 播放）");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ------------------------------------------------------------------ preset

    private void buildPresetChips() {
        presetRow.removeAllViews();
        for (final Presets.P p : Presets.builtin()) {
            Button b = chip(p.name);
            b.setOnClickListener(v -> {
                dsp.setGainsNow(p.gains);
                eqView.markDirty();
                eqView.invalidate();
                savePrefs();
                flash("已套用：" + p.name);
            });
            presetRow.addView(b);
        }
        for (final Map.Entry<String, float[]> e : user.entrySet()) {
            Button b = chip("★ " + e.getKey());
            b.setOnClickListener(v -> {
                dsp.setGainsNow(e.getValue());
                eqView.markDirty();
                eqView.invalidate();
                savePrefs();
                flash("已套用自訂：" + e.getKey());
            });
            b.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this)
                        .setTitle("刪除自訂曲線「" + e.getKey() + "」？")
                        .setPositiveButton("刪除", (d, w) -> {
                            user.remove(e.getKey());
                            sp.edit().putString(K_USER, Presets.writeUser(user)).apply();
                            buildPresetChips();
                            flash("已刪除：" + e.getKey());
                        })
                        .setNegativeButton("取消", null)
                        .show();
                return true;
            });
            presetRow.addView(b);
        }
    }

    private Button chip(String name) {
        Button b = Ui.segment(this, name);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 40));
        p.rightMargin = Ui.dp(this, 6);
        b.setLayoutParams(p);
        b.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        b.setMinWidth(Ui.dp(this, 78));
        return b;
    }

    private void savePresetDialog() {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setHint("曲線名稱（例：高速公路）");
        et.setTextColor(Ui.TEXT);
        et.setHintTextColor(Ui.DIM);
        LinearLayout box = Ui.col(this);
        box.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), 0);
        box.addView(et);
        new AlertDialog.Builder(this)
                .setTitle("儲存現時 31 段曲線")
                .setView(box)
                .setPositiveButton("儲存", (d, w) -> {
                    String n = et.getText().toString().trim();
                    if (n.isEmpty()) n = "自訂" + (user.size() + 1);
                    user.put(n, dsp.gains());
                    sp.edit().putString(K_USER, Presets.writeUser(user)).apply();
                    buildPresetChips();
                    flash("已儲存曲線：" + n);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ------------------------------------------------------------------ 儲存

    private void loadPrefs() {
        float[] g = Presets.zeros();
        String s = sp.getString(K_GAINS, "");
        if (!s.isEmpty()) {
            String[] parts = s.split(",");
            for (int i = 0; i < Bands.N && i < parts.length; i++) {
                try {
                    g[i] = Float.parseFloat(parts[i]);
                } catch (Throwable ignored) {
                }
            }
        }
        dsp.setGainsNow(g);
        engine.setVolPct(sp.getInt(K_VOL, 70));
        volBar.setProgress(engine.volPct());
        volVal.setText(engine.volPct() + "%");
        int pre = sp.getInt(K_PRE, 12);
        preBar.setProgress(pre);
        dsp.setPreampDb(pre - 12);
        preVal.setText(Presets.Fmt.sign((float) (pre - 12)) + " dB");
        route = Routes.INNER;                       // 車外選項已取消（app 只做車內／媒體 EQ）
        mode = sp.getInt(K_MODE, Engine.M_TONE);
        if (mode < 0 || mode > 2) mode = Engine.M_TONE;
        engine.setToneKind(sp.getInt(K_TONE, Gen.PINK));
        follow = sp.getBoolean(K_FOLLOW, false);
        sysOnPref = sp.getBoolean(K_SYS_ON, true);
        autoBoot = sp.getBoolean(K_BOOT, false);
        autoOnOpen = sp.getBoolean(K_AUTO_OPEN, true);
        if (autoOnOpen) sysOnPref = true;           // 開 app 自動設 EQ 為 ON
        String fp = sp.getString(K_FILE, "");
        if (!fp.isEmpty()) {
            File f = new File(fp);
            if (f.isFile()) engine.setFile(f);
        }
        eqView.markDirty();
        syncSettings();                             // 開關同步做 prefs 嘅真實狀態
        tick();
        flash("31 段 EQ 就緒");
    }

    private void savePrefs() {
        StringBuilder sb = new StringBuilder();
        float[] g = dsp.gains();
        for (int i = 0; i < Bands.N; i++) {
            if (i > 0) sb.append(',');
            sb.append(Presets.Fmt.db1(g[i]));
        }
        SharedPreferences.Editor e = sp.edit();
        e.putString(K_GAINS, sb.toString());
        e.putInt(K_VOL, engine.volPct());
        e.putInt(K_PRE, preBar.getProgress());
        e.putInt(K_ROUTE, route);
        e.putInt(K_MODE, mode);
        e.putInt(K_TONE, engine.toneKind());
        e.putBoolean(K_FOLLOW, follow);
        e.putBoolean(K_SYS_ON, sysOnPref);
        e.putBoolean(K_BOOT, autoBoot);
        e.putBoolean(K_AUTO_OPEN, autoOnOpen);
        e.putBoolean("hal_only", halOnly);
        File f = engine.file();
        e.putString(K_FILE, f == null ? "" : f.getAbsolutePath());
        e.apply();
    }

    // ------------------------------------------------------------------ 生命週期

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        boolean ok = res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED;
        if (code == 2) {
            if (ok) {
                mode = Engine.M_MIC;
                refreshButtons();
                flash("錄音權限已批准，可以揀「🎤 咪」");
            } else {
                flash("錄音權限被拒 — 咪模式用唔到");
            }
        } else if (code == 1) {
            flash(ok ? "已批准讀取音檔，再撳「📂 選檔」" : "未批准讀取音檔權限（可以放檔案入 app 內部資料夾）");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshButtons();
        tick();
        // 返到 app：確認系統 EQ 仲掛住，並套返現時曲線（車機／系統殺過都要自己接返）
        if (sys != null) {
            if (autoOnOpen) sysOnPref = true;        // 開 app 自動設 EQ 為開
            if (!sys.ready() && sys.attach(halOnly)) EqService.start(this);
            doSysApply(false);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        savePrefs();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (ticker != null) ui.removeCallbacks(ticker);
        ticker = null;
        stopPulse();
        engine.stop();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
