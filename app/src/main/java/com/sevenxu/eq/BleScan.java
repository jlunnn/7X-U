package com.sevenxu.eq;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.Locale;

/**
 * BLE 掃描引擎（由 CarPiano BleProbe 裁剪 — 只留「掃描」同「常駐廣播監聽」）。
 *
 * <p>Zeekr button（TI 公版，無 HID profile）唔使連接：手勢寫喺廣播資料，
 * FF 後面第 2 個 byte（第 3 byte）＝ 手勢類型（00 單擊／02 雙擊／01 長按）。
 * 常駐監聽只聽廣播，唔搶連接（車機/手機照常配對用）。</p>
 */
final class BleScan {

    interface L {
        void onDevice(String name, String mac, int rssi, String uuids, int txPower);

        void onLog(String line);

        void onDone(String summary);
    }

    /** 常駐監聽 callback：每個（過濾後）廣播都會收到。 */
    interface R {
        void onAdvert(String mac, String name, int rssi, String rawHex, long at);
    }

    private static Context appCtx;
    private static volatile boolean scanning;
    private static volatile int seenCount;
    private static ScanCallback currentCb;
    private static ScanCallback rawCb;
    private static BluetoothLeScanner scanner;
    /** 最近一次常駐掃描錯誤（空字串＝正常）— 藍牙頁狀態卡顯示用。 */
    static volatile String rawError = "";
    // 3.16 活性探針：ECARX stack 會「靜默殺掃描」— 無 onScanFailed、無例外，3.15 心跳
    // 重新註冊都救唔返（11-05 實車：心跳有做嘢但全聾，kill app 先翻生）。唔 kill app 嘅自癒梯：
    // ① rawCb 未過濾前記低「任何廣播」時間（stack 有冇嘢講嘅唯一 observable 訊號）；
    // ② 心跳改探針制：停掃 → 3 秒全頻 LOW_LATENCY 探測 → 有包＝stack 生（重建全新監聽）；
    //    連續 3 輪 0 包＝死透 → 軟件重啟藍牙（disable→enable；STATE_ON receiver 自動重掛）
    //    ＝人手「熄返開藍牙」自動化，app process 唔死、EQ／浮窗全程唔郁。
    static volatile long lastAnyAdvertAt;
    static volatile long anyAdvertCount;
    static volatile String lastProbeNote = "";
    static volatile long btCycleCount;
    private static volatile int probeZeroStreak;
    private static final long PROBE_MS = 3_000L;         // 探針掃描時長
    private static final long BT_BACK_MS = 3_000L;       // disable 後幾耐 enable
    private static final long BT_BACK2_MS = 8_000L;      // 後備 enable（防第一刀食咗）
    // 3.15 auto-rearm：onScanFailed 後唔可以死咗唔自癒（問③根因）— backoff 自動重掛起；
    // 5 分鐘心跳定期開新掃描 session（BT stack 靜默死掃描都會翻生）；
    // BT 開關 receiver：車機熄／開藍牙即刻重新掛起監聽。
    static volatile long rawRearmCount;          // auto-rearm 次數（診斷顯示用）
    static volatile long lastRearmAt;            // 最近一次 auto-rearm 時間
    private static final long[] REARM_DELAYS = {1_000L, 3_000L, 10_000L, 30_000L};   // ms backoff
    private static volatile int rearmAttempt;
    private static final Handler mainH = new Handler(Looper.getMainLooper());
    private static Runnable heartbeat;
    private static Runnable pendingRearm;
    private static final android.content.BroadcastReceiver btStateRx =
            new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context c, android.content.Intent i) {
                    String a = i == null ? "" : i.getAction();
                    if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(a)) {
                        int st = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
                        if (st == BluetoothAdapter.STATE_ON) {
                            AppLog.i("BleScan", "BT 開返 → 重新掛起常駐監聽");
                            rearmNow();
                        } else if (st == BluetoothAdapter.STATE_OFF
                                || st == BluetoothAdapter.STATE_TURNING_OFF) {
                            mainH.removeCallbacks(heartbeat);
                            mainH.removeCallbacks(pendingRearm);
                        }
                    } else if ("android.bluetooth.adapter.action.BLE_STATE_CHANGED".equals(a)) {
                        int st2 = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
                        if (st2 == BluetoothAdapter.STATE_ON) {
                            AppLog.i("BleScan", "BLE 開返 → 重新掛起常駐監聽");
                            rearmNow();
                        }
                    }
                }
            };

    static boolean scanning() {
        return scanning;
    }

    private static BluetoothAdapter adapter(Context c) {
        try {
            BluetoothManager bm = (BluetoothManager) c.getSystemService(Context.BLUETOOTH_SERVICE);
            return bm == null ? BluetoothAdapter.getDefaultAdapter() : bm.getAdapter();
        } catch (Throwable t) {
            return null;
        }
    }

    // ============================================================ 一次過掃描（綁定用）

    static void startScan(final Context ctx, final L l) {
        startScan(ctx, l, 10);
    }

    static void startScan(final Context ctx, final L l, final int seconds) {
        appCtx = ctx.getApplicationContext();
        if (scanning) {
            return;
        }
        try {
            BluetoothAdapter ad = adapter(ctx);
            if (ad == null || !ad.isEnabled()) {
                l.onLog("❌ 藍牙未開");
                l.onDone("❌ 藍牙未開 — 開咗藍牙再掃");   // 早退都要收 dialog，唔係永遠卡住「掃描中」
                return;
            }
            seenCount = 0;
            scanner = ad.getBluetoothLeScanner();
            if (scanner == null) {
                l.onLog("❌ 拎唔到 BLE scanner");
                l.onDone("❌ 拎唔到 BLE scanner — 藍牙未開或者未就緒");
                return;
            }
            scanning = true;
            l.onLog("📡 BLE 掃描中（" + seconds + " 秒）…");
            ScanSettings st = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .setReportDelay(0)
                    .build();
            currentCb = new ScanCallback() {
                @Override
                public void onScanResult(int type, ScanResult r) {
                    if (r == null || r.getDevice() == null) {
                        return;
                    }
                    BluetoothDevice d = r.getDevice();
                    String nm;
                    try {
                        nm = d.getName();
                    } catch (Throwable t) {
                        nm = null;
                    }
                    StringBuilder uu = new StringBuilder();
                    try {
                        if (r.getScanRecord() != null && r.getScanRecord().getServiceUuids() != null) {
                            for (android.os.ParcelUuid pu : r.getScanRecord().getServiceUuids()) {
                                if (pu != null && pu.getUuid() != null) {
                                    uu.append(pu.getUuid().toString()).append(' ');
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    seenCount++;
                    l.onDevice(nm == null ? "（無名）" : nm, d.getAddress(), r.getRssi(),
                            uu.toString().trim(), r.getTxPower());
                }

                @Override
                public void onScanFailed(int errorCode) {
                    scanning = false;
                    l.onLog("❌ 掃描失敗 code=" + errorCode);
                }
            };
            scanner.startScan(null, st, currentCb);
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                BleScan.stopScan();
                l.onDone("🔎 掃描完（" + seconds + " 秒）：收到 " + seenCount + " 個廣播");
            }, seconds * 1000L);
        } catch (Throwable t) {
            scanning = false;
            l.onLog("❌ " + t.getClass().getSimpleName() + "：" + t.getMessage());
            l.onDone("❌ 掃描出錯：" + t.getClass().getSimpleName());   // 收 dialog＋顯示原因
        }
    }

    static void stopScan() {
        scanning = false;
        try {
            if (scanner != null && currentCb != null) {
                scanner.stopScan(currentCb);
            }
        } catch (Throwable ignored) {
        } finally {
            currentCb = null;
        }
    }

    // ============================================================ 常駐監聽廣播（唔連接）

    private static String[] rawMacs;             // 最近一次 startRaw 參數（auto-rearm 用）
    private static R rawListen;
    private static int rawSeconds;
    private static volatile boolean btRxRegistered;
    private static final long HEARTBEAT_MS = 300_000L;   // 5 分鐘心跳：強制開新掃描 session

    /** auto-rearm：即刻停掃再開過（新 session）。BT 熄咗／冇綁定 → 唔重掃。 */
    private static void rearmNow() {
        mainH.post(() -> {
            if (rawListen == null) {
                return;                          // 冇常駐監聽要救
            }
            stopRaw();
            startRaw(appCtx, rawMacs, rawSeconds, rawListen);
        });
    }

    /** auto-rearm（backoff 延遲）：1s → 3s → 10s → 30s 上限，直到掃返着為止。 */
    private static void rearmLater() {
        mainH.removeCallbacks(pendingRearm);
        final int n = rearmAttempt;
        long d = REARM_DELAYS[Math.min(n, REARM_DELAYS.length - 1)];
        rearmAttempt = Math.min(n + 1, REARM_DELAYS.length - 1);
        pendingRearm = () -> {
            if (rawListen != null) {
                rawRearmCount++;                 // 診斷計數：呢個 loop 真係翻生過幾多次
                lastRearmAt = System.currentTimeMillis();
                AppLog.w("BleScan", "auto-rearm（延遲 " + d + "ms）");
                stopRaw();
                startRaw(appCtx, rawMacs, rawSeconds, rawListen);
            }
        };
        mainH.postDelayed(pendingRearm, d);
    }

    /** 心跳（3.16 探針制）：每 5 分鐘停掃 → 3 秒全頻探測 → 有包＝stack 生，重建全新 session。 */
    private static void scheduleHeartbeat() {
        mainH.removeCallbacks(heartbeat);
        heartbeat = () -> {
            if (rawListen != null) {             // 冇 listener＝已停止 → 唔好續命
                probeCycle();
            }
        };
        mainH.postDelayed(heartbeat, HEARTBEAT_MS);
    }

    /** 3.16 探針：停掃 → 3 秒全頻 LOW_LATENCY 探測。有包＝stack 生 → 重建監聽（零副作用）；
     *  0 包入静默計數，連續 3 輪＝死透 → 升級軟件重啟藍牙。 */
    private static void probeCycle() {
        if (rawListen == null) {
            return;
        }
        if (scanning) {                          // 綁定掃描用緊 → 唔好搶，下輪先
            scheduleHeartbeat();
            return;
        }
        AppLog.i("BleScan", "心跳探針：停掃 → 3 秒全頻探測");
        stopRaw();
        try {
            BluetoothAdapter ad = adapter(appCtx);
            BluetoothLeScanner sc = ad == null ? null : ad.getBluetoothLeScanner();
            if (ad == null || !ad.isEnabled() || sc == null) {
                rawError = "藍牙未開（等 BT 開返自動重掛）";
                rearmLater();
                return;
            }
            ScanSettings st = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .setReportDelay(0)
                    .build();
            final int[] hits = {0};
            final ScanCallback pcb = new ScanCallback() {
                @Override
                public void onScanResult(int type, ScanResult r) {
                    hits[0]++;
                    lastAnyAdvertAt = System.currentTimeMillis();   // 探針收到都算「stack 有嘢講」
                    probeZeroStreak = 0;
                }

                @Override
                public void onScanFailed(int errorCode) {
                    AppLog.w("BleScan", "探針失敗 code=" + errorCode);
                }
            };
            sc.startScan(null, st, pcb);
            mainH.postDelayed(() -> {
                try {
                    sc.stopScan(pcb);
                } catch (Throwable ignored) {
                }
                if (hits[0] > 0) {
                    probeZeroStreak = 0;
                    lastProbeNote = "探針 " + hits[0] + " 包 → 重建監聽";
                    AppLog.i("BleScan", lastProbeNote);
                    rawError = "";
                    startRaw(appCtx, rawMacs, rawSeconds, rawListen);   // 全新 session（L2）
                } else {
                    probeZeroStreak++;
                    lastProbeNote = "探針 0 包（連續 " + probeZeroStreak + " 輪）";
                    AppLog.w("BleScan", lastProbeNote);
                    maybeBtCycle();
                }
            }, PROBE_MS);
        } catch (Throwable t) {
            rawError = "探針出錯：" + t.getClass().getSimpleName();
            AppLog.w("BleScan", rawError);
            rearmLater();
        }
    }

    /** 3.16 死透升級：連續 3 輪探針 0 包 → 軟件重啟藍牙（＝人手熄返開，唔使 kill app、EQ 唔郁）。 */
    private static void maybeBtCycle() {
        if (probeZeroStreak < 3) {
            startRaw(appCtx, rawMacs, rawSeconds, rawListen);   // 未夠證據 → 照 3.15 底線重建
            return;
        }
        try {
            android.media.AudioManager am =
                    (android.media.AudioManager) appCtx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null && am.isBluetoothA2dpOn() && am.isMusicActive()) {
                lastProbeNote += "｜藍牙音樂播緊 → 跳過重啟";
                AppLog.w("BleScan", "0 包 ×" + probeZeroStreak + " 但藍牙音樂播緊 → 跳過，落輪先算");
                startRaw(appCtx, rawMacs, rawSeconds, rawListen);
                return;
            }
            BluetoothAdapter ad = adapter(appCtx);
            if (ad == null || !ad.isEnabled()) {
                startRaw(appCtx, rawMacs, rawSeconds, rawListen);
                return;
            }
            btCycleCount++;
            rawError = "掃描死透（探針 0 包 ×3）→ 自動重啟藍牙（第 " + btCycleCount + " 次）";
            AppLog.w("BleScan", rawError);
            try {
                ad.disable();
            } catch (Throwable t) {
                rawError = "自動重啟藍牙被拒（" + t.getClass().getSimpleName() + "）→ 請人手熄返開藍牙";
                AppLog.w("BleScan", rawError);
                startRaw(appCtx, rawMacs, rawSeconds, rawListen);
                return;
            }
            probeZeroStreak = 0;
            // disable → 3 秒後 enable（後備 8 秒再一刀，防食咗）；STATE_ON receiver 會自動重掛
            mainH.postDelayed(BleScan::backEnable, BT_BACK_MS);
            mainH.postDelayed(BleScan::backEnable, BT_BACK2_MS);
            // container 冇派 STATE_ON 嘅後備：12 秒後 BT 開住但冇 session 就重掛
            mainH.postDelayed(() -> {
                try {
                    BluetoothAdapter a4 = adapter(appCtx);
                    if (a4 != null && a4.isEnabled() && rawListen != null && rawCb == null) {
                        AppLog.i("BleScan", "BT 重啟後備重掛");
                        rearmNow();
                    }
                } catch (Throwable ignored) {
                }
            }, 12_000L);
        } catch (Throwable t) {
            AppLog.w("BleScan", "btCycle 例外：" + t.getClass().getSimpleName());
            startRaw(appCtx, rawMacs, rawSeconds, rawListen);
        }
    }

    private static void backEnable() {
        try {
            BluetoothAdapter a = adapter(appCtx);
            if (a != null && !a.isEnabled()) {
                a.enable();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void registerBtRx(Context c) {
        if (btRxRegistered || c == null) {
            return;
        }
        try {
            android.content.IntentFilter f = new android.content.IntentFilter();
            f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
            f.addAction("android.bluetooth.adapter.action.BLE_STATE_CHANGED");
            c.registerReceiver(btStateRx, f);
            btRxRegistered = true;
        } catch (Throwable ignored) {
        }
    }

    static void startRaw(final Context ctx, final String[] filterMacs, final int seconds, final R r) {
        appCtx = ctx.getApplicationContext();
        stopRaw();
        rawMacs = filterMacs;                    // 記低參數 — auto-rearm／心跳開新 session 用
        rawListen = r;
        rawSeconds = seconds;
        try {
            registerBtRx(appCtx);
            BluetoothAdapter ad = adapter(ctx);
            if (ad == null || !ad.isEnabled()) {
                rawError = "藍牙未開（等 BT 開返自動重掛）";
                rearmLater();                    // BT stack 未就緒 → backoff 等翻生（BT rx 開返都會即刻觸發）
                return;
            }
            BluetoothLeScanner sc = ad.getBluetoothLeScanner();
            if (sc == null) {
                rawError = "拎唔到 BLE scanner（等重試）";
                rearmLater();
                return;
            }
            // 有 MAC（常駐監聽，3.12 起可以多粒）→ BALANCED：夠快捉 Zeekr button 廣播
            // （延遲 <1s），又唔會好似 LOW_LATENCY 咁 100% duty 長期霸住藍牙電台，
            // 影響車機其他 BT 功能。冇 MAC → LOW_LATENCY 搶速，反正一陣就停。
            ScanSettings.Builder sb = new ScanSettings.Builder()
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .setReportDelay(0);
            sb.setScanMode(filterMacs != null && filterMacs.length > 0
                    ? ScanSettings.SCAN_MODE_BALANCED
                    : ScanSettings.SCAN_MODE_LOW_LATENCY);
            ScanSettings st = sb.build();
            rawCb = new ScanCallback() {
                @Override
                public void onScanResult(int type, ScanResult res) {
                    if (res == null || res.getDevice() == null) {
                        return;
                    }
                    lastAnyAdvertAt = System.currentTimeMillis();   // 3.16 活性：未過濾先記
                    anyAdvertCount++;
                    probeZeroStreak = 0;
                    String mac = res.getDevice().getAddress();
                    if (filterMacs != null && filterMacs.length > 0) {
                        boolean hit = false;
                        for (String fm : filterMacs) {         // 軟件比對 MAC（3.12 多粒）：任何一粒中就算
                            if (fm != null && !fm.isEmpty() && fm.equalsIgnoreCase(mac)) {
                                hit = true;
                                break;
                            }
                        }
                        if (!hit) {
                            return;                // 非綁定廣播喺度食掉
                        }
                    }
                    String nm;
                    try {
                        nm = res.getDevice().getName();
                    } catch (Throwable t) {
                        nm = null;
                    }
                    byte[] raw = res.getScanRecord() == null ? null : res.getScanRecord().getBytes();
                    try {
                        r.onAdvert(mac, nm == null ? "" : nm, res.getRssi(), hex(raw),
                                System.currentTimeMillis());
                    } catch (Throwable ignored) {   // callback 炸唔可以殺死成個掃描
                    }
                }

                @Override
                public void onScanFailed(int errorCode) {
                    // 掃描註冊失敗唔可以再靜默（3.8–3.10 全聾時狀態卡完全睇唔出）；
                    // 3.15：仲要 auto-rearm — 死掃唔自癒就係問③「一段時間後 button 失效」根因
                    rawError = "掃描失敗 code=" + errorCode + "（自動重掛中）";
                    AppLog.w("BleScan", rawError);
                    rearmLater();
                }
            };
            // 3.11 教訓：唔好用 hardware MAC ScanFilter（setDeviceAddress）—
            // 3.8–3.10 實車全聾（綁定掃描冇 filter 收得到、常駐監聽有 hardware
            // filter 就零廣播），ECARX 藍牙 stack 對呢種 filter 唔中。CarPiano
            // proven 做法＝null filter＋callback 入面軟件比對 MAC（下面已做，
            // 3.12 多粒只係比對清單多行）；非目標廣播喺 compare 已 return，剩餘工作近零。
            rawError = "";
            sc.startScan(null, st, rawCb);
            rearmAttempt = 0;                    // 掃描著返 → backoff 歸零
            probeZeroStreak = 0;                 // 3.16：新 session 生 → 静默計數歸零
            scheduleHeartbeat();                 // 5 分鐘後探針體檢
            if (lastAnyAdvertAt == 0) {          // 3.16 開場體檢：一分鐘都未收過嘢 → 早探針（捉開車時 BT 未就緒嘅静默死）
                mainH.postDelayed(() -> {
                    if (rawListen != null && lastAnyAdvertAt == 0 && !scanning) {
                        AppLog.i("BleScan", "開場 60 秒零廣播 → 早探針");
                        probeCycle();
                    }
                }, 60_000L);
            }
            if (seconds > 0) {
                new Handler(Looper.getMainLooper()).postDelayed(BleScan::stopRaw, seconds * 1000L);
            }
        } catch (Throwable t) {
            rawError = "掃描出錯：" + t.getClass().getSimpleName();
            AppLog.w("BleScan", rawError);
            rearmLater();                        // 例外都唔可以死掃 — backoff 翻生
        }
    }

    /** 完全停止：唔止停掃描，連 auto-rearm／心跳／BT receiver 一齊拆（BtBridge.stop 用呢個）。 */
    static void stopRawFully() {
        rawListen = null;                        // 令 rearmNow／heartbeat／rearmLater 全部唔做嘢
        mainH.removeCallbacks(heartbeat);
        mainH.removeCallbacks(pendingRearm);
        rawMacs = null;
        stopRaw();
        if (btRxRegistered && appCtx != null) {
            try {
                appCtx.unregisterReceiver(btStateRx);
            } catch (Throwable ignored) {
            }
            btRxRegistered = false;
        }
        rawError = "";
    }

    static void stopRaw() {
        try {
            BluetoothAdapter ad = adapter(appCtx);
            if (ad != null && rawCb != null && ad.getBluetoothLeScanner() != null) {
                ad.getBluetoothLeScanner().stopScan(rawCb);
            }
        } catch (Throwable ignored) {
        } finally {
            rawCb = null;
        }
    }

    static String hex(byte[] v) {
        if (v == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : v) {
            sb.append(String.format(Locale.US, "%02X ", b));
        }
        return sb.toString().trim();
    }
}
