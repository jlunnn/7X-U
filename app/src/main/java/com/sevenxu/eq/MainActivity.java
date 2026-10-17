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
 * Zeekr EQ 主畫面：31 段 DSP 等化器 + 測試音／檔案／咪三個來源。
 *
 * <p>無網絡、無帳號、無上傳：所有設定存喺本機 SharedPreferences。</p>
 */
public class MainActivity extends AppCompatActivity {

    private static final String PREF = "zeekreq";
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
    private static final String K_AUTO_SWEEP = "auto_sweep";
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
    private boolean floatOn;                         // 懸浮窗開／關（UI 用）
    private boolean autoBoot;                       // 開機自動開
    private boolean autoOnOpen = true;              // 開 app 自動設 EQ 為 ON
    private boolean autoSweep = false;              // 開 app 自動逐段自檢（預設關：會影響音效）
    private LinearLayout mainPage, settingsPage;
    private android.widget.ScrollView settingsPageWrap;
    private Button gearBtn;
    private androidx.appcompat.widget.SwitchCompat swBoot, swAutoOpen, swEngine, swAutoSweep;
    private Button sweepUp, sweepDown;
    private TextView sweepSum, sweepOut, sweepLine;
    private LinearLayout sweepPage;
    private boolean settingsOn;
    private volatile boolean sweeping = false;
    private boolean syncing;
    private TextView setStatusTv;
    private TextView bootDiagTv;
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
        EqView.showReal = sp.getBoolean("show_real", false);   // 金框預設唔顯示
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
        View r2 = row2();
        r2.setVisibility(View.GONE);              // EQ 已改去懸浮窗，主頁唔再顯示曲線／preset
        mainPage.addView(r2);
        View eqc = eqCard();
        eqc.setVisibility(View.GONE);             // 14 段圖只喺懸浮窗
        mainPage.addView(eqc, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        View r3 = row3();
        r3.setVisibility(View.GONE);              // 測試音／引擎掣收埋（行車用唔到）
        mainPage.addView(r3);
        View r4 = row4();
        r4.setVisibility(View.GONE);              // 前級／音量已喺懸浮窗
        mainPage.addView(r4);
        mainPage.addView(rowFloat());             // 大掣：開／關 EQ 懸浮窗
        mainPage.addView(row5());

        settingsPage = buildSettingsPage();
        // 設定頁內容多（4 行設定 ＋ 現況 ＋ 車機原生音場 ＋ 逐段自檢）→ 一定要可以捲，否則尾段撳唔到
        {
            android.widget.ScrollView ssc = new android.widget.ScrollView(this);
            ssc.setFillViewport(true);
            ssc.addView(settingsPage, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            settingsPageWrap = ssc;
        }
        settingsPageWrap.setVisibility(View.GONE);

        // 設定頁內容搬上主頁（單頁 app：主頁 = 設定）
        try {
            java.util.List<View> moved = new java.util.ArrayList<>();
            for (int i = 0; i < settingsPage.getChildCount(); i++) {
                moved.add(settingsPage.getChildAt(i));
            }
            for (View v : moved) {
                if (v.getParent() instanceof ViewGroup) {
                    ((ViewGroup) v.getParent()).removeView(v);
                }
                mainPage.addView(v);
            }
        } catch (Throwable ignored) {
        }

        sweepPage = (LinearLayout) buildSweepPage();
        sweepPage.setVisibility(View.GONE);

        // 主頁而家載住設定內容 → 要可捲
        android.widget.ScrollView mainWrap = new android.widget.ScrollView(this);
        mainWrap.setFillViewport(true);
        mainWrap.addView(mainPage, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        container.addView(mainWrap, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, 0));
        container.addView(settingsPageWrap, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, 0));
        container.addView(sweepPage, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
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
        TextView sub = Ui.text(this, "7X=U · 14 段媒體 EQ", 14f, Ui.DIM);
        head.addView(sub);
        head.addView(space(0, 1f));
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
        card.addView(settingRow(3, "開 app 自動逐段自檢（會影響音效）",
                "⚠️ 自檢會即時改動每一段，部分車機 bypass 唔到 → 會聽到雜聲。預設關。"
                        + "需要時手動按「▶ 逐段 +12 dB」；上次結果會照用（唔會出聲）。",
                autoSweep, v -> {
                    autoSweep = v;
                    savePrefs();
                    if (v) startAutoSweep();
                    flash(v ? "開 app 自動逐段自檢：已開" : "開 app 自動逐段自檢：已關");
                }));

        // 懸浮圓掣大小（VOLUME 風格 VolBar；40–192dp）
        LinearLayout szRow = Ui.row(this);
        szRow.addView(Ui.label(this, "懸浮圓掣大小"));
        final TextView szVal = Ui.text(this, "", 13f, Ui.TEXT);
        szVal.setMinWidth(Ui.dp(this, 76));
        szVal.setGravity(Gravity.CENTER);
        final VolBar szBar = new VolBar(this);
        szBar.setMax(100);
        int curDp = sp.getInt("icon_dp", 0);
        int curPct = curDp > 0 ? Math.max(0, Math.min(100, Math.round((curDp - 40) / 152f * 100f)))
                : Math.round((58 - 40) / 152f * 100f);
        szBar.setProgress(curPct);
        szVal.setText(curDp > 0 ? curDp + " dp" : "自動");
        szBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(android.widget.SeekBar s, int v, boolean fromUser) {
                int dpv = 40 + Math.round(v * 1.52f);          // 0→40dp、100→192dp
                szVal.setText(dpv + " dp");
                sp.edit().putInt("icon_dp", dpv).apply();
            }

            public void onStartTrackingTouch(android.widget.SeekBar s) {
            }

            public void onStopTrackingTouch(android.widget.SeekBar s) {
                int dpv = 40 + Math.round(s.getProgress() * 1.52f);
                if (floatOn) FloatEqService.show(MainActivity.this);
                flash("圓掣大小：" + dpv + " dp");
            }
        });
        LinearLayout.LayoutParams sblp = new LinearLayout.LayoutParams(0, Ui.dp(this, 46), 1f);
        sblp.leftMargin = Ui.dp(this, 10);
        sblp.rightMargin = Ui.dp(this, 10);
        szRow.addView(szBar, sblp);
        szRow.addView(szVal);
        Button autoBtn = Ui.segment(this, "自動");
        autoBtn.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 96), Ui.dp(this, 46)));
        autoBtn.setOnClickListener(v -> {
            sp.edit().putInt("icon_dp", 0).apply();
            szVal.setText("自動");
            if (floatOn) FloatEqService.show(MainActivity.this);
            flash("圓掣大小：自動（跟屏幕）");
        });
        szRow.addView(autoBtn);
        card.addView(szRow);



        p.addView(card);

        // 車機原生音場（反射層）—— 對應 AppVenture ④「車機整合」
        // 車機原生音場卡已移除（簽名權限，第三方不可用）

        LinearLayout st = Ui.card(this);
        st.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        st.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        st.addView(Ui.label(this, "現況"));
        setStatusTv = Ui.text(this, "", 14f, Ui.DIM);
        setStatusTv.setPadding(0, Ui.dp(this, 6), 0, 0);
        st.addView(setStatusTv);
        p.addView(st);

        // 開機套用診斷（本機記錄，唔上報、唔上網）：一眼睇到今次著車有冇真係套用成功
        LinearLayout dg = Ui.card(this);
        dg.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        dg.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        dg.addView(Ui.label(this, "開機套用診斷（只存本機）"));
        bootDiagTv = Ui.text(this, BootDiag.summary(this), 13f, Ui.DIM);
        bootDiagTv.setPadding(0, Ui.dp(this, 6), 0, 0);
        dg.addView(bootDiagTv);
        LinearLayout dgr = Ui.row(this);
        dgr.setPadding(0, Ui.dp(this, 10), 0, 0);
        Button dgRe = Ui.segment(this, "↻ 重新整理");
        dgRe.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 170), Ui.dp(this, 46)));
        dgRe.setOnClickListener(v -> {
            if (bootDiagTv != null) bootDiagTv.setText(BootDiag.summary(this));
            flash("診斷已更新");
        });
        dgr.addView(dgRe);
        dgr.addView(space(10));
        Button dgCl = Ui.segment(this, "清除記錄");
        dgCl.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 150), Ui.dp(this, 46)));
        dgCl.setOnClickListener(v -> {
            BootDiag.clear(this);
            if (bootDiagTv != null) bootDiagTv.setText(BootDiag.summary(this));
            flash("診斷記錄已清除");
        });
        dgr.addView(dgCl);
        dg.addView(dgr);
        p.addView(dg);

        // 逐段自檢：逐段單獨 ±12 dB → 讀返每段實際生效值（測完自動復原曲線）
        LinearLayout swc = Ui.card(this);
        swc.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        swc.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        swc.addView(Ui.label(this, "逐段自檢"));
        TextView sh = Ui.text(this, "逐段單獨推 +12 / −12 dB（其餘歸零），再讀返每段實際生效值 —— 結果會開全屏頁逐行顯示，測完自動復原你嘅曲線。", 12f, Ui.DIM);
        sh.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        swc.addView(sh);
        LinearLayout srow = Ui.row(this);
        sweepUp = Ui.segment(this, "▶ 逐段 +12 dB");
        sweepUp.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 210), Ui.dp(this, 50)));
        sweepUp.setOnClickListener(v -> runSweep(12f));
        srow.addView(sweepUp);
        srow.addView(space(10));
        sweepDown = Ui.segment(this, "▶ 逐段 −12 dB");
        sweepDown.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 210), Ui.dp(this, 50)));
        sweepDown.setOnClickListener(v -> runSweep(-12f));
        srow.addView(sweepDown);
        srow.addView(space(10));
        Button showRes = Ui.segment(this, "睇上次結果");
        showRes.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 170), Ui.dp(this, 50)));
        showRes.setOnClickListener(v -> showSweep(true));
        srow.addView(showRes);
        swc.addView(srow);
        sweepLine = Ui.text(this, "未跑過。", 15f, Ui.DIM);
        sweepLine.setPadding(0, Ui.dp(this, 8), 0, 0);
        swc.addView(sweepLine);
        p.addView(swc);
        return p;
    }

    // ---------------------------------------------- 車機原生音場（反射層）

    private TextView carLog;
    private LinearLayout carOpsBox;
    private final StringBuilder carBuf = new StringBuilder();

    /** 車機原生音場卡：探測車機自家 DSP 介面（音場／bass-mid-treble／EQ preset／音效增強）。 */
    private View buildCarCard() {
        LinearLayout card = Ui.card(this);
        card.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        card.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
        card.addView(Ui.label(this, "🎛 車機原生音場（車機自家 DSP）"));
        TextView hint = Ui.text(this, "用反射叫車機自家音效介面（音場前後左右／bass-mid-treble／EQ preset／音效增強）。"
                + "呢層同上面 14 段媒體 EQ 係兩層，可以並存。而家先「探測」睇車機有咩方法、權限畀唔畀。", 12f, Ui.DIM);
        hint.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        card.addView(hint);

        LinearLayout r = Ui.row(this);
        Button b1 = Ui.segment(this, "🔍 探測介面");
        b1.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 165), Ui.dp(this, 46)));
        b1.setOnClickListener(v -> probeCarAudio());
        r.addView(b1);
        r.addView(space(8));
        Button b2 = Ui.segment(this, "▶ 試叫（安全值 0）");
        b2.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 205), Ui.dp(this, 46)));
        b2.setOnClickListener(v -> tryCarOps(0f));
        r.addView(b2);
        r.addView(space(8));
        Button b3 = Ui.segment(this, "🔍 逐喇叭／DSP（探針功能）");
        b3.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 250), Ui.dp(this, 46)));
        b3.setOnClickListener(v -> scanCarSpeakers());
        r.addView(b3);
        r.addView(space(8));
        Button b4 = Ui.segment(this, "🔊 試逐區音量");
        b4.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 230), Ui.dp(this, 46)));
        b4.setOnClickListener(v -> tryCarDeviceVolume());
        r.addView(b4);
        card.addView(r);

        carOpsBox = Ui.col(this);
        // log 區用獨立 ScrollView → 可以自己上下捲（唔會同整頁捲動打架）
        android.widget.ScrollView logScroll = new android.widget.ScrollView(this);
        logScroll.setFillViewport(false);
        logScroll.setVerticalScrollBarEnabled(true);
        logScroll.addView(carOpsBox, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams lslp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 280));
        lslp.topMargin = Ui.dp(this, 6);
        card.addView(logScroll, lslp);

        android.widget.ScrollView sc = new android.widget.ScrollView(this);
        sc.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 250)));
        carLog = Ui.text(this, "未探測。", 12f, Ui.DIM);
        carLog.setTypeface(android.graphics.Typeface.MONOSPACE);
        sc.addView(carLog);
        card.addView(sc);
        return card;
    }

    private void carSay(String s) {
        carBuf.append(s).append('\n');
        final String t = s;
        if (carLog != null) ui.post(() -> carLog.append(t + "\n"));
    }

    /** 探測：權限 ＋ 車機音效介面方法表 ＋ 可叫 setter。 */
    private void probeCarAudio() {
        carBuf.setLength(0);
        if (carLog != null) carLog.setText("");
        new Thread(() -> {
            carSay("=== 車機原生音效探測 ===");
            StringBuilder log = new StringBuilder();
            Object audio = CarAudioLayer.manager(this, log);
            carSay(log.toString().trim());
            carSay("— 權限 —");
            StringBuilder sb = new StringBuilder();
            CarAudioLayer.perms(this, sb);
            carSay(sb.toString().trim());
            carSay("— 車機音效介面方法 —");
            log.setLength(0);
            CarAudioLayer.listMethods(audio, log);
            carSay(log.toString().trim());
            final Object a = audio;
            ui.post(() -> buildCarOps(a));
        }, "car-audio-probe").start();
    }

    /** 按探測結果砌控制行（每行一個方法 ＋ 拉桿 ＋ 寫入掣）。 */
    private void buildCarOps(final Object audio) {
        if (carOpsBox == null) return;
        carOpsBox.removeAllViews();
        java.util.List<CarAudioLayer.Op> ops = CarAudioLayer.ops(audio);
        if (ops.isEmpty()) {
            carOpsBox.addView(Ui.text(this, audio == null
                    ? "⛔ 拎唔到車機音效介面（睇下面 log 嘅原因）"
                    : "車機冇提供可叫嘅音場／EQ setter（睇下面方法表）", 13f, Ui.WARN));
            return;
        }
        for (final CarAudioLayer.Op op : ops) {
            LinearLayout row = Ui.row(this);
            row.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
            TextView name = Ui.text(this, op.label(), 13f, Ui.TEXT);
            row.addView(name, Ui.lp(Ui.dp(this, 250), ViewGroup.LayoutParams.WRAP_CONTENT, 0f));
            final android.widget.SeekBar bar = new android.widget.SeekBar(this);
            bar.setMax(10);
            bar.setProgress(0);
            row.addView(bar, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            final TextView res = Ui.text(this, "", 11f, Ui.DIM);
            res.setMinWidth(Ui.dp(this, 200));
            Button w = Ui.segment(this, "寫");
            w.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 80), Ui.dp(this, 46)));
            w.setOnClickListener(v -> {
                float val = op.isFloat ? bar.getProgress() / 10f : bar.getProgress();
                String r2 = CarAudioLayer.call(audio, op, val);
                res.setText(r2);
                res.setTextColor(r2.startsWith("✅") ? Ui.OK : (r2.startsWith("⛔") ? Ui.DANGER : Ui.WARN));
                carSay(r2);
            });
            row.addView(w);
            carOpsBox.addView(row);
            carOpsBox.addView(res);
        }
    }

    /** 全部可叫方法都試一次（安全值）。 */
    private void tryCarOps(final float v) {
        new Thread(() -> {
            StringBuilder log = new StringBuilder();
            Object audio = CarAudioLayer.manager(this, log);
            java.util.List<CarAudioLayer.Op> ops = CarAudioLayer.ops(audio);
            if (ops.isEmpty()) {
                carSay("（冇可叫嘅方法 —— 先按「探測介面」睇原因）");
                return;
            }
            for (CarAudioLayer.Op op : ops) {
                carSay(CarAudioLayer.call(audio, op, v));
            }
            carSay("=== 試叫完 ===");
        }, "car-audio-try").start();
    }

    /** 探針：逐個 BUS 試按裝置調音量（判斷逐區音量通唔通）。 */
    private void tryCarDeviceVolume() {
        new Thread(() -> {
            carSay("");
            carSay("=== 試逐區音量（setDeviceVolume）===");
            StringBuilder sb = new StringBuilder();
            CarAudioLayer.tryDeviceVolume(this, sb);
            for (String l : sb.toString().split("\n")) {
                carSay(l);
            }
            carSay("=== 試完 ===");
        }, "car-devvol").start();
    }

    /** 探針功能：逐喇叭／DSP 掃描（結果顯示喺卡內 log，可截圖留存）。 */
    private void scanCarSpeakers() {
        new Thread(() -> {
            carSay("");
            carSay("=== 逐喇叭／車機 DSP 掃描 ===");
            StringBuilder sb = new StringBuilder();
            CarAudioLayer.scanSpeakers(this, sb);
            for (String l : sb.toString().split("\n")) {
                carSay(l);
            }
            carSay("=== 掃描完 ===");
        }, "car-spk-scan").start();
    }

    /** 主頁大掣：開啟 EQ 懸浮窗（EQ 全部操作都喺懸浮窗）。 */
    /** 主頁大掣：一按即開 EQ 懸浮窗（預設全屏放大 mode）。 */
    /** 主頁：懸浮窗入口 = Equalizer 圖示（一按即開全屏放大）。 */
    private View rowFloat() {
        LinearLayout r = Ui.col(this);
        r.setGravity(Gravity.CENTER_HORIZONTAL);
        r.setPadding(0, Ui.dp(this, 20), 0, Ui.dp(this, 14));
        android.widget.ImageView ic = new android.widget.ImageView(this);
        try {
            try {
                ic.setImageResource(R.drawable.app_icon_big);       // 高清版（512px）
            } catch (Throwable ignored) {
                ic.setImageDrawable(getPackageManager().getApplicationIcon(getPackageName()));
            }
        } catch (Throwable ignored) {
        }
        ic.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(Ui.dp(this, 240), Ui.dp(this, 240));
        ilp.gravity = Gravity.CENTER_HORIZONTAL;
        ic.setLayoutParams(ilp);
        ic.setOnClickListener(v -> {
            sp.edit().putBoolean("ffull", true).apply();          // 一按 = 全屏放大
            sp.edit().putBoolean("fmin", false).apply();          // 全屏展開（唔好記住上次收埋）
            if (floatOn) {
                FloatEqService.show(this);
            } else {
                toggleFloat();
            }
            flash("懸浮窗：已開（全屏放大）");
            // 主頁自動退到背景（app／服務繼續運作）；要再開主頁 = 撳懸浮窗上嘅 ⚙
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                try {
                    moveTaskToBack(true);
                } catch (Throwable ignored) {
                }
            }, 900);
        });
        r.addView(ic);
        TextView t = Ui.text(this, "7X=U EQ", 24f, Ui.TEXT);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.gravity = Gravity.CENTER_HORIZONTAL;
        t.setLayoutParams(tlp);
        r.addView(t);
        TextView t2 = Ui.text(this, "一按圖示即開 EQ 懸浮窗（全屏放大）· 行車中都用得", 13f, Ui.DIM);
        t2.setGravity(Gravity.CENTER);
        r.addView(t2);
        return r;
    }

    /** 全屏「逐段自檢結果」頁（大字總結 ＋ 逐行結果）。 */
    private View buildSweepPage() {
        LinearLayout p = Ui.col(this);
        p.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 10));
        LinearLayout head = Ui.row(this);
        head.addView(Ui.title(this, "逐段自檢結果"));
        head.addView(space(16));
        head.addView(Ui.text(this, "逐段單獨 ±12 dB → 讀返每段實際生效值", 14f, Ui.DIM));
        head.addView(space(0, 1f));
        Button back = Ui.segment(this, "← 設定");
        back.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 150), Ui.dp(this, 48)));
        back.setOnClickListener(v -> showSweep(false));
        head.addView(back);
        p.addView(head);
        p.addView(Ui.divider(this));
        sweepSum = Ui.text(this, "未跑過。\n按「▶ 逐段 +12 dB」開始。", 20f, Ui.TEXT);
        sweepSum.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 8));
        p.addView(sweepSum);
        android.widget.ScrollView sc = new android.widget.ScrollView(this);
        sc.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        sweepOut = Ui.text(this, "", 15f, Ui.DIM);
        sweepOut.setTypeface(android.graphics.Typeface.MONOSPACE);
        sc.addView(sweepOut);
        p.addView(sc);
        return p;
    }

    private void showSweep(boolean on) {
        if (mainPage == null || settingsPage == null || sweepPage == null) return;
        mainPage.setVisibility(on || settingsOn ? View.GONE : View.VISIBLE);
        if (settingsPageWrap != null) settingsPageWrap.setVisibility(on ? View.GONE : View.VISIBLE);
        if (on) {
            settingsPage.setVisibility(View.GONE);
            sweepPage.setVisibility(View.VISIBLE);
        } else {
            sweepPage.setVisibility(View.GONE);
            settingsPage.setVisibility(View.VISIBLE);
        }
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
        // EQ-Control 風格：開 = 霓虹綠 track ＋ 深色 thumb；關 = 暗綠 track ＋ 灰 thumb
        int[][] states = new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{-android.R.attr.state_checked}};
        sw.setTrackTintList(new android.content.res.ColorStateList(states, new int[]{0xFFB6FF3B, 0xFF2A3025}));
        sw.setThumbTintList(new android.content.res.ColorStateList(states, new int[]{0xFF0D110D, 0xFFA0A89A}));
        try {
            sw.setSwitchPadding(Ui.dp(this, 6));
        } catch (Throwable ignored) {
        }
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
        else if (which == 2) swEngine = sw;
        else swAutoSweep = sw;
        r.addView(sw);
        return r;
    }

    /** 開 app 讀完 prefs → 把設定頁嘅開關同步做真實狀態（唔會觸發 onChange）。 */
    private void syncSettings() {
        syncing = true;
        try {
            if (bootDiagTv != null) bootDiagTv.setText(BootDiag.summary(this));
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
            if (swAutoSweep != null) {
                swAutoSweep.setChecked(autoSweep);
                swAutoSweep.setText(autoSweep ? "開" : "關");
            }
        } finally {
            syncing = false;
        }
    }

    private void showSettings(boolean on) {
        if (mainPage == null || settingsPage == null) return;
        settingsOn = on;
        mainPage.setVisibility(on ? View.GONE : View.VISIBLE);
        settingsPage.setVisibility(on ? View.VISIBLE : View.GONE);
        if (settingsPageWrap != null) settingsPageWrap.setVisibility(on ? View.VISIBLE : View.GONE);
        if (sweepPage != null) sweepPage.setVisibility(View.GONE);
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
        // EQ 開關只放喺懸浮窗（避免主頁／懸浮窗兩處重複，睇落唔一致）
        eqSwitch.setVisibility(View.GONE);
        r.addView(space(18));

        sysHead = Ui.text(this, "媒體音樂 EQ：未掛上", 17f, Ui.DIM);
        sysHead.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        r.addView(sysHead);
        r.addView(space(18));

        // 齒輪 → 設定頁
        gearBtn = Ui.segment(this, "⚙️ 設定");
        gearBtn.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 132), Ui.dp(this, 46)));
        gearBtn.setOnClickListener(v -> showSettings(true));
        gearBtn.setVisibility(View.GONE);      // 設定已搬上主頁
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
        saveBtn.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 92), Ui.dp(this, 46)));
        saveBtn.setOnClickListener(v -> savePresetDialog());
        r.addView(saveBtn);
        buildPresetChips();
        return r;
    }

    private View eqCard() {
        LinearLayout card = Ui.card(this);
        card.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
        eqView = new EqView(this, dsp);
        eqView.setOnLongClickListener(v2 -> {   // 長按 EQ 圖 = 開／關「真受控」金框
            EqView.showReal = !EqView.showReal;
            sp.edit().putBoolean("show_real", EqView.showReal).apply();
            flash(EqView.showReal ? "已顯示『車機真受控』金框" : "已取消『車機真受控』金框");
            eqView.invalidate();
            return true;
        });
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
            btn.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 96), Ui.dp(this, 46)));
            btn.setOnClickListener(v -> toggleKind(k));
            r.addView(btn);
            toneBtn[k] = btn;
        }
        r.addView(space(12));

        // 1 kHz 單頻參考音（舊「跟手指試聽」位置）
        followBtn = Ui.segment(this, "1K Tone");
        followBtn.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 140), Ui.dp(this, 46)));
        followBtn.setOnClickListener(v -> toggleOneK());
        r.addView(followBtn);
        r.addView(space(12));

        r.addView(space(14));

        reapplyBtn = Ui.segment(this, "🔄 重新掛上");
        reapplyBtn.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 150), Ui.dp(this, 46)));
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
        engineBtn.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 170), Ui.dp(this, 46)));
        engineBtn.setOnClickListener(v -> {
            halOnly = !halOnly;
            savePrefs();
            engineBtn.setText(halOnly ? "引擎：車機原生" : "引擎：自訂");
            sysMsg = sys.attach(halOnly)
                    ? "已切換引擎：" + sys.status()
                    : "⚠️ 呢個引擎掛唔到：" + sys.status();
            if (sys.ready()) EqService.start(this);
            doSysApply(true);
            sweepReal = null;                        // 換引擎：舊自檢結果唔再適用
            if (autoSweep) startAutoSweep();         // 自動重新逐段自檢
            flash(sysMsg);
        });
        r.addView(engineBtn);

        // 懸浮 EQ 窗（行車中用；保留車機上下左右 info bar）
        Button floatBtn = Ui.segment(this, "🔳 懸浮窗");
        floatBtn.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 130), Ui.dp(this, 46)));
        floatBtn.setOnClickListener(v -> toggleFloat());
        r.addView(floatBtn);
        return hbar(r, 46);
    }

    /** 開／關懸浮 EQ 窗；未授權就先跳去授權頁。 */
    private void toggleFloat() {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            flash("要先授權「顯示在其他應用程式上層」");
            try {
                startActivity(new android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + getPackageName())));
            } catch (Throwable t) {
                flash("去唔到授權頁，請喺車機設定開「懸浮窗／顯示在其他應用程式上層」");
            }
            return;
        }
        if (floatOn) {
            FloatEqService.hide(this);
            floatOn = false;
            sp.edit().putBoolean("float_on", false).apply();   // 記住狀態（開機回復用）
            flash("懸浮窗：已關");
        } else {
            FloatEqService.show(this);
            floatOn = true;
            sp.edit().putBoolean("float_on", true).apply();
            flash("懸浮窗：已開（拖標題列搬位；▁ 收埋剩 icon）");
        }
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
        preBar.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 190), Ui.dp(this, 46)));
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
                doSysApply(true);          // 拖完即套用＋讀返核實（含左右聲道一致性檢查）
            }
        });
        r.addView(preBar);
        r.addView(preVal);
        r.addView(space(16));

        r.addView(Ui.label(this, "音量"));
        volBar = new SeekBar(this);
        volBar.setMax(100);
        volBar.setProgress(70);
        volBar.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 240), Ui.dp(this, 46)));
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
        flat.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 130), Ui.dp(this, 46)));
        flat.setOnClickListener(v -> {
            dsp.setGainsNow(Presets.zeros());
            eqView.markDirty();
            eqView.invalidate();
            savePrefs();
            flash("14 段已歸零");
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
            boolean ok = sys.attach(halOnly);       // 跟返用戶揀嘅引擎（車機原生／自訂）
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

    // --------------------------------------------------- 逐段自檢

    /** 頻率寫法：20 Hz、31.5 Hz、1.25k、20k。 */
    private static String hz5(float f) {
        if (f >= 1000f) {
            float k = f / 1000f;
            String s = (Math.abs(k - Math.round(k)) < 0.05f)
                    ? String.valueOf(Math.round(k))
                    : String.format(java.util.Locale.US, "%.2f", k);
            return s + "k";
        }
        if (f == Math.rint(f)) return String.valueOf((int) f);
        return String.format(java.util.Locale.US, "%.1f", f);
    }

    /**
     * 逐段自檢：每段單獨寫 testDb、其餘歸零 → 讀返全部段，檢查「本段有變、鄰段冇跟住變」（= 段段獨立可調）。
     * 測完自動復原原本曲線。結果逐行顯示；聽住都會一聲聲咁變（可以順便聽得出邊段有反應）。
     */
    private void runSweep(final float testDb) {
        if (sweeping) {
            flash("自檢進行中…");
            return;
        }
        if (sys == null || !sys.ready()) {
            flash("未掛上系統 EQ，先按 EQ 開");
            return;
        }
        final int n = sys.bands();
        final float[] cut = sys.readCutoffs();
        final boolean custom = sys.isCustom();
        final float[] keep = dsp.gains().clone();
        final double keepPre = dsp.preampDb();
        sweeping = true;
        showSweep(true);                                   // 開全屏結果頁，逐行即時顯示
        if (sweepOut != null) sweepOut.setText("");
        if (sweepSum != null) sweepSum.setText("⏳ 逐段試進行中… 0 / " + n);
        if (sweepLine != null) sweepLine.setText("⏳ 進行中… 0 / " + n);
        if (sweepUp != null) sweepUp.setEnabled(false);
        if (sweepDown != null) sweepDown.setEnabled(false);
        final long t0 = System.currentTimeMillis();
        new Thread(new Runnable() {
            @Override
            public void run() {
                int ok = 0;
                boolean[] passed = new boolean[n];
                for (int i = 0; i < n; i++) {
                    float[] back = sys.sweepStep(i, testDb, true);
                    float got = back[i];
                    float nb = 0f;
                    for (int k = 0; k < n; k++) {
                        if (k != i && Math.abs(back[k]) > Math.abs(nb)) nb = back[k];
                    }
                    boolean pass = Math.abs(got - testDb) <= 0.6f && Math.abs(nb) <= 0.6f;
                    if (pass) ok++;
                    passed[i] = pass;
                    final String line = String.format(java.util.Locale.US,
                            "#%02d %-7s 目標 %+.0f → 讀返 %+.1f  鄰段 %+.1f  %s",
                            i + 1, hz5(cut[i]), testDb, got, nb, pass ? "✅" : "⚠️");
                    final int done = i + 1;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (sweepOut != null) sweepOut.append(line + "\n");
                            if (sweepSum != null) sweepSum.setText("⏳ 逐段試進行中… " + done + " / " + n);
                        }
                    });
                }
                sys.applyCurve(keep, keepPre, false);              // 復原用家曲線
                final int okF = ok;
                final int nF = n;
                final boolean cs = custom;
                final long ms = System.currentTimeMillis() - t0;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        String eng = cs ? ("自訂 " + Bands.N + " 段") : ("車機 EQ " + nF + " 段");
                        String verdict = (okF == nF)
                                ? "✅ " + okF + "/" + nF + " 段逐段可調（段段獨立）"
                                : "⚠️ 只有 " + okF + "/" + nF + " 段逐段可調";
                        sweepSum.setText(verdict + "\n引擎：" + eng
                                + "　頻率 " + hz5(cut[0]) + " → " + hz5(cut[nF - 1])
                                + "　用時 " + (ms / 1000.0) + " s");
                        if (sweepOut != null) {
                            sweepOut.append("\n—— 已復原原本曲線 ——\n");
                            sweepOut.append(cs
                                    ? "自訂引擎：31 段各自一條 peaking 濾波，理論上段段可獨立 +/-。\n"
                                    : "車機原生：只有車機自己嘅段數真受控，其餘係映射出嚟，所以會有 ⚠️。\n");
                        }
                        sweeping = false;
                        sweepReal = sys.mapHit(passed);      // 手動自檢結果同樣用嚟標金框
                        if (sweepUp != null) sweepUp.setEnabled(true);
                        if (sweepDown != null) sweepDown.setEnabled(true);
                        if (sweepLine != null) {
                            sweepLine.setText(verdict + "　（" + eng + " · 用時 " + (ms / 1000.0) + " s）");
                        }
                        flash(verdict);
                        refreshSysUi();
                    }
                });
            }
        }).start();
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
        // 邊幾段係車機真受控：優先用「自動逐段自檢」實測結果，冇就用引擎報返嘅段
        boolean[] real = (ok && on) ? (sweepReal != null ? sweepReal : sys.realBands()) : null;
        if (eqView != null) eqView.setRealBands(real);
        if (ok && on && real != null) {
            int rc = 0;
            for (boolean b : real) {
                if (b) rc++;
            }
            if (rc < Bands.N && sysHead != null) {
                String s = sysHead.getText().toString();
                if (!s.contains("真受控")) {
                    sysHead.setText(s + "　｜　真受控 " + rc + "/" + Bands.N + " 段"
                            + (sweepReal != null ? "（自檢）" : "（引擎報）"));
                }
            }
        }
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
        // 開機自動開 EQ：預設「開」（一次性遷移，令舊裝都跟新預設）
        if (!sp.getBoolean("boot_default_on_v1", false)) {
            sp.edit().putBoolean(K_BOOT, true).putBoolean("boot_default_on_v1", true).apply();
        }
        autoBoot = sp.getBoolean(K_BOOT, true);
        autoOnOpen = sp.getBoolean(K_AUTO_OPEN, true);
        autoSweep = sp.getBoolean(K_AUTO_SWEEP, false);
        halOnly = false;                            // 唔再用車機原生 EQ 引擎（用戶要求：只要自訂 14 段）
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
        if (autoSweep) {
            ui.postDelayed(() -> startAutoSweep(), 1800);   // 開 app 自動靜默逐段自檢
        }
    }

    // --------------------------------------------------- 自動逐段自檢（靜默）

    private boolean[] sweepReal;                    // 自檢得出：31 段邊幾段真受控
    private volatile boolean autoSweeping = false;

    /** 讀返上次自檢快取（唔會出聲）：引擎指紋一樣就照用，圖上照樣標金框。 */
    private void loadSweepCache() {
        try {
            if (sys == null || !sys.ready()) return;
            String fp = sweepFingerprint();
            String cached = sp.getString("sweep_cache", "");
            if (fp.isEmpty() || !fp.equals(sp.getString("sweep_fp", "")) || cached.length() != Bands.N) return;
            boolean[] r = new boolean[Bands.N];
            for (int i = 0; i < Bands.N; i++) r[i] = cached.charAt(i) == '1';
            sweepReal = r;
        } catch (Throwable ignored) {
        }
    }

    /** 引擎指紋（自訂/車機原生 + 段數 + 各段 cutoff）：變咗就要重新自檢。 */
    private String sweepFingerprint() {
        try {
            if (sys == null || !sys.ready()) return "";
            float[] cu = sys.readCutoffs();
            StringBuilder sb = new StringBuilder();
            sb.append(sys.isCustom() ? "C" : "H").append(sys.bands());
            for (float f : cu) sb.append(':').append(Math.round(f));
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 開 app 自動跑一次：bypass 住逐段寫入＋讀返（唔出聲），幾秒後圖上自動標金框。 */
    private void startAutoSweep() {
        if (autoSweeping || sweeping) return;
        if (sys == null || !sys.ready()) return;
        // 已經測過而且引擎（段數／cutoff）冇變 → 用返上次結果，唔使每次開機都測
        final String fp = sweepFingerprint();
        String cached = sp.getString("sweep_cache", "");
        if (!fp.isEmpty() && fp.equals(sp.getString("sweep_fp", "")) && cached.length() == Bands.N) {
            boolean[] r = new boolean[Bands.N];
            for (int i = 0; i < Bands.N; i++) r[i] = cached.charAt(i) == '1';
            sweepReal = r;
            refreshSysUi();
            return;
        }
        autoSweeping = true;
        final float[] keep = dsp.gains().clone();
        final double keepPre = dsp.preampDb();
        if (statusTv != null) statusTv.setText("⏳ 自動逐段自檢中（唔會出聲）…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean[] r = sys.silentSweep(12f, keep, keepPre);
                int n = 0;
                for (boolean b : r) {
                    if (b) n++;
                }
                final int cnt = n;
                autoSweeping = false;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        sweepReal = r;
                        StringBuilder cs = new StringBuilder();
                        for (boolean b : r) cs.append(b ? '1' : '0');
                        sp.edit().putString("sweep_cache", cs.toString())
                                .putString("sweep_fp", fp).apply();
                        // 自動自檢結果都寫入「逐段自檢結果」頁，方便隨時翻睇
                        if (sweepOut != null) {
                            sweepOut.setText("");
                            for (int i = 0; i < Bands.N; i++) {
                                sweepOut.append(String.format(java.util.Locale.US,
                                        "#%02d %-7s %s\n", i + 1, Bands.LABEL[i],
                                        r[i] ? "✅ 真受控" : "⚠️ 映射（車機唔收）"));
                            }
                        }
                        String verdict = (cnt == Bands.N)
                                ? "✅ " + cnt + "/" + Bands.N + " 段逐段可調（段段獨立）"
                                : "⚠️ 只有 " + cnt + "/" + Bands.N + " 段逐段可調";
                        if (sweepSum != null) {
                            sweepSum.setText(verdict + "\n（開 app 自動靜默自檢）");
                        }
                        if (sweepLine != null) {
                            sweepLine.setText(verdict + "　（開 app 自動自檢）");
                        }
                        refreshSysUi();
                        flash("自動自檢完成：車機真受控 " + cnt + "/" + Bands.N + " 段");
                    }
                });
            }
        }).start();
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
        e.putBoolean(K_AUTO_SWEEP, autoSweep);
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
        // 懸浮窗改過曲線／前級 → 返到 app 時重新讀返（共用同一份 prefs）
        reloadFromPrefsIfChanged();
        // 返到 app：確認系統 EQ 仲掛住，並套返現時曲線（車機／系統殺過都要自己接返）
        if (sys != null) {
            if (autoOnOpen) sysOnPref = true;        // 開 app 自動設 EQ 為開
            if (!sys.ready() && sys.attach(halOnly)) EqService.start(this);
            doSysApply(false);
            if (sweepReal == null) loadSweepCache();     // 照標金框，但唔會再跑自檢
        }
    }

    /** 懸浮窗（另一個 process 內同一個 prefs）改過 → 同步返主畫面。 */
    private void reloadFromPrefsIfChanged() {
        try {
            String s = sp.getString("gains", "");
            if (s.isEmpty()) return;
            String now = gainsToCsv(dsp.gains());
            if (s.equals(now)) return;
            String[] p = s.split(",");
            float[] g = new float[Bands.N];
            for (int i = 0; i < Bands.N && i < p.length; i++) {
                try {
                    g[i] = Float.parseFloat(p[i].trim());
                } catch (Throwable ignored) {
                }
            }
            dsp.setGainsNow(g);
            double pre = sp.getInt("preamp", 12) - 12;
            dsp.setPreampDb(pre);
            if (preBar != null) preBar.setProgress((int) Math.round(pre) + 12);
            if (preVal != null) preVal.setText(Presets.Fmt.sign((float) pre) + " dB");
            eqView.markDirty();
            eqView.invalidate();
            refreshButtons();
        } catch (Throwable ignored) {
        }
    }

    private static String gainsToCsv(float[] g) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < g.length; i++) {
            if (i > 0) sb.append(',');
            sb.append((int) g[i]);
        }
        return sb.toString();
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
