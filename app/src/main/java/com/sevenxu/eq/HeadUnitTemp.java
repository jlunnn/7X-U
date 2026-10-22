package com.sevenxu.eq;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;

import com.sevenxu.eq.AppLog;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 車機自己的温度（SoC / CPU），讀給懸浮按鈕那張温度牌用。
 *
 * <h3>為什麼不是 ECARX 信號</h3>
 *
 * <p>電池温度和車內温度都有車輛信號（{@code 0x00102A00} / {@code 0x00100C00}），
 * 車機温度沒有 —— 它是這台 Android 盒子自己的事，跟車上的總線無關。
 * 所以這一路自己去找，找三個地方：</p>
 *
 * <ol>
 *   <li><b>{@code /sys/class/thermal/thermal_zone*}</b>：內核把各個温度傳感器的
 *       {@code type}（cpu / soc / gpu …）和 {@code temp}（毫攝氏度）攤在這裏。</li>
 *   <li><b>{@code /sys/devices/virtual/thermal/thermal_zone*}</b>：同一個東西的另一條路徑，
 *       有些車機只開放這一條。</li>
 *   <li><b>{@code /sys/class/hwmon/hwmon*}</b>：另一些內核把傳感器掛這裏
 *       （{@code temp1_input} ＋ {@code name}），常見的 SoC 名是 soc_thermal / coretemp。</li>
 *   <li>最後才是兜底 <b>{@link BatteryManager#EXTRA_TEMPERATURE}</b>：車機上不一定有電池，
 *       有也是個小備份電池，讀到的是它自己的温度，不是 SoC 的。</li>
 * </ol>
 *
 * <h3>0 度是「沒有讀數」，不是 0 度</h3>
 *
 * <p>第一版在車機上顯示 {@code 0°}：文件讀得到、值也解析得出來，但那個 zone 根本
 * 沒有在用（內核給未接傳感器的默認值就是 0）。這台盒子在跑的時候不可能接近 0 °C，
 * 所以 <b>{@code |值| < 1} 一律當作「這個傳感器沒數據」</b>，換下一個候選。
 * {@link #MIN_CELSIUS}–{@link #MAX_CELSIUS} 之外同理。</p>
 *
 * <h3>找不到就説找不到</h3>
 *
 * <p>候選全都讀不到 → 牌上寫 {@code --}，而不是猜一個數字。診斷報告 §1.1
 * （{@link #probeReport}）會把每個候選的原文和判斷結果抄下來，實車排查靠它。</p>
 */
public final class HeadUnitTemp {

    private static final String TAG = "HeadUnitTemp";

    /** 合理的車機温度範圍。外的值當作壞值不畫。125 °C 是 SoC 結温那類上限。 */
    public static final float MIN_CELSIUS = -40f;
    public static final float MAX_CELSIUS = 125f;

    /** 比這個還接近 0 的值當作「傳感器沒填值」（見類注釋）。 */
    public static final float ZERO_EPSILON = 1f;

    private static final String[] THERMAL_ROOTS = {
            "/sys/class/thermal",
            "/sys/devices/virtual/thermal",
    };

    private static final String HWMON_ROOT = "/sys/class/hwmon";

    /** 電池温度那條路（單位 0.1 °C，內核 ABI 如此）。名字各廠不同，兩個都試。 */
    private static final String POWER_SUPPLY_ROOT = "/sys/class/power_supply";

    private static final String[] POWER_SUPPLY_FILES = {"temp", "temperature"};

    /** 診斷報告裏最多列幾個候選（車機上十幾個，列全部太吵）。 */
    private static final int MAX_PROBE = 10;

    /**
     * 列不出 zone 時直接去試的編號上限：{@code thermal_zone0} … {@code thermal_zone7}。
     *
     * <p>實測踩過：Android 12 的 Pixel 3a 上 {@code /sys/class/thermal} 列得出來、但
     * {@code thermal_zone} 數量是 0 —— SELinux 把目錄列舉過濾掉了，不代表那個文件讀不到。
     * 拿不到名字就一個個已知路徑去開，開得到就是候選。</p>
     */
    private static final int BLIND_ZONE_PROBE = 8;

    /** 一秒鐘讀一次。 */
    private static final long POLL_MS = 1000L;

    /** 讀到的温度（°C）；沒讀到就是 NaN。 */
    private volatile float celsius = Float.NaN;

    /** 最後一次成功讀到的時刻（uptimeMillis）；0 = 從來沒讀到。 */
    private volatile long stampMs;

    /** 最後用的是哪一條路徑，給診斷報告看。刻意 ASCII：它是數據，不是界面文字。 */
    private volatile String source = "not started";

    /** 拿不到散熱狀態時的值。 */
    public static final int NO_STATUS = Integer.MIN_VALUE;

    /** 系統的散熱狀態（PowerManager，API 29+）；{@link #NO_STATUS} = 拿不到。 */
    private volatile int thermalStatus = NO_STATUS;

    private HandlerThread thread;
    private Handler handler;
    private Runnable tick;
    private Context appContext;
    /** 候選路徑（已按偏好排序）；第一次讀的時候探測一次就緩存。 */
    private List<String> candidates;
    private boolean candidatesResolved;

    /** 讀到的温度（°C）；沒讀到就是 NaN。 */
    public float celsius() {
        return celsius;
    }

    /** 這份讀數多久了；從來沒讀到就是 {@link Long#MAX_VALUE}。 */
    public long ageMs() {
        long stamp = stampMs;
        if (stamp == 0L) {
            return Long.MAX_VALUE;
        }
        long age = android.os.SystemClock.uptimeMillis() - stamp;
        return age < 0L ? 0L : age;
    }

    /** 診斷用：最後用的是哪一條路徑。 */
    public String describeSource() {
        return source;
    }

    /**
     * 系統的散熱狀態（0–6，越大越熱）。
     *
     * <p>這是<b>退而求其次</b>的信號：拿不到温度數字時，至少能告訴駕駛員
     * 「車機現在正常／偏熱／危急」。它不需要任何權限，API 29 起就有。</p>
     *
     * @return {@link #NO_STATUS} 表示拿不到
     */
    public int thermalStatus() {
        return thermalStatus;
    }

    /** 開始讀。重複調用無害。 */
    public void start(Context context) {
        if (thread != null) {
            return;
        }
        appContext = context.getApplicationContext();
        thread = new HandlerThread("head-unit-temp");
        thread.start();
        handler = new Handler(thread.getLooper());
        tick = new Runnable() {
            @Override
            public void run() {
                readOnce();
                Handler h = handler;
                if (h != null) {
                    h.postDelayed(this, POLL_MS);
                }
            }
        };
        handler.post(tick);
    }

    /** 停止讀取並放掉線程。 */
    public void stop() {
        Handler h = handler;
        handler = null;
        if (h != null && tick != null) {
            h.removeCallbacks(tick);
        }
        tick = null;
        HandlerThread t = thread;
        thread = null;
        if (t != null) {
            t.quitSafely();
        }
        celsius = Float.NaN;
        stampMs = 0L;
    }

    /**
     * 讀一次：沿候選列表找第一個給得出合理值的路徑。
     *
     * <p>候選順序就是偏好順序，所以第一個有合法值的贏；沒有一個合法就保持
     * 「沒有讀數」，讓年齡自己漲上去（牌那邊過了 5 秒就畫 {@code --}）。</p>
     */
    private void readOnce() {
        refreshThermalStatus();
        if (!candidatesResolved) {
            candidates = candidatePaths();
            candidatesResolved = true;
        }
        if (candidates != null) {
            for (String path : candidates) {
                Float value = parseFor(path, readRaw(path));
                if (value != null) {
                    celsius = value;
                    stampMs = android.os.SystemClock.uptimeMillis();
                    source = path;
                    return;
                }
            }
        }
        Float battery = readBatteryTemperature(appContext);
        if (battery != null) {
            celsius = battery;
            stampMs = android.os.SystemClock.uptimeMillis();
            source = "battery";
        }
    }

    // ------------------------------------------------------------------ 候選

    /**
     * 所有候選路徑，按「最像 SoC」排序。
     *
     * <p>排序靠 {@link #pickZone} 那套名字偏好：soc / cpu / ap / tsens / gpu …
     * 名字中不了的排在後面（保持目錄順序），最後是 hwmon。</p>
     */
    static List<String> candidatePaths() {
        List<String> paths = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (String root : THERMAL_ROOTS) {
            File dir = new File(root);
            File[] entries = dir.listFiles();
            if (entries != null) {
                for (File e : entries) {
                    if (!e.getName().startsWith("thermal_zone")) {
                        continue;
                    }
                    addCandidate(paths, names, new File(e, "temp"),
                            readTypeName(new File(e, "type")));
                }
            }
            // 列表被過濾掉的時候仍然可能讀得到具體文件，所以再盲探一輪編號
            for (int i = 0; i < BLIND_ZONE_PROBE; i++) {
                String blindName = "thermal_zone" + i;
                File zone = new File(root, blindName);
                addCandidate(paths, names, new File(zone, "temp"),
                        readTypeName(new File(zone, "type")));
            }
        }

        List<String> ordered = orderByPreference(paths, names);

        // hwmon：temp1_input ＋ name
        File hwmon = new File(HWMON_ROOT);
        File[] chips = hwmon.listFiles();
        if (chips != null) {
            List<String> hwPaths = new ArrayList<>();
            List<String> hwNames = new ArrayList<>();
            for (File chip : chips) {
                File input = new File(chip, "temp1_input");
                if (!input.isFile()) {
                    continue;
                }
                String name = readRaw(new File(chip, "name").getAbsolutePath());
                hwPaths.add(input.getAbsolutePath());
                hwNames.add(name == null ? chip.getName() : name);
            }
            ordered.addAll(orderByPreference(hwPaths, hwNames));
        }

        // 最後一條路：/sys/class/power_supply/*/temp（電池温度，單位是 0.1 °C）。
        // 它不是 SoC 温度，但很多機器只有這一條讀得到 —— 包括系統狀態欄顯示温度那種。
        // 所以排在整個清單的最後：有 SoC 就用 SoC。
        File powerSupply = new File(POWER_SUPPLY_ROOT);
        File[] supplies = powerSupply.listFiles();
        if (supplies != null) {
            List<String> psPaths = new ArrayList<>();
            List<String> psNames = new ArrayList<>();
            for (File supply : supplies) {
                for (String file : POWER_SUPPLY_FILES) {
                    File value = new File(supply, file);
                    if (!value.isFile()) {
                        continue;
                    }
                    String path = value.getAbsolutePath();
                    if (psPaths.contains(path)) {
                        continue;
                    }
                    psPaths.add(path);
                    psNames.add("battery-" + supply.getName());
                }
            }
            ordered.addAll(orderByPreference(psPaths, psNames));
        }
        return ordered;
    }

    /**
     * 記錄一個候選（文件真的存在才算）。名字讀不到就退回 zone 目錄名。
     *
     * <p>同一路徑只記一次：盲探和目錄列舉會撞到同一批 zone。</p>
     */
    private static void addCandidate(List<String> paths, List<String> names, File tempFile,
                                     String typeName) {
        if (!tempFile.isFile()) {
            return;
        }
        String path = tempFile.getAbsolutePath();
        if (paths.contains(path)) {
            return;
        }
        String name = typeName;
        if (name == null || name.isEmpty()) {
            File zoneDir = tempFile.getParentFile();
            name = zoneDir == null ? "" : zoneDir.getName();
        }
        paths.add(path);
        names.add(name);
    }

    /** 按名字偏好重排（偏好相同就保持原本順序）。 */
    private static List<String> orderByPreference(List<String> paths, List<String> names) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < paths.size(); i++) {
            order.add(i);
        }
        final List<String> lookup = names;
        Collections.sort(order, (a, b) -> {
            int ra = preferenceRank(lookup.get(a));
            int rb = preferenceRank(lookup.get(b));
            if (ra != rb) {
                return Integer.compare(ra, rb);
            }
            return Integer.compare(a, b);
        });
        List<String> out = new ArrayList<>();
        for (int i : order) {
            out.add(paths.get(i));
        }
        return out;
    }

    /**
     * 名字的偏好分：越小越像 SoC。
     *
     * <p>中不了任何關鍵字的名字給 100 + 位置，於是它們排在所有認得的名字後面，
     * 但彼此之間保持目錄順序。</p>
     */
    static int preferenceRank(String name) {
        String lower = name == null ? "" : name.toLowerCase();
        for (int i = 0; i < PREFERRED.length; i++) {
            if (lower.contains(PREFERRED[i])) {
                return i;
            }
        }
        return 100;
    }

    private static final String[] PREFERRED = {
            "soc", "cpu", "ap_", "ap-", "tsens", "mtktscpu", "cpu_thermal", "big", "little",
            "gpu", "coretemp", "soc_thermal", "battery", "pa", "board", "quiet", "skin",
    };

    /**
     * 從一堆 thermal zone 名字裏挑一個最像 SoC 的（保留給測試用）。
     *
     * @return 挑中的下標；一個都沒有就回 −1
     */
    public static int pickZone(String[] types) {
        if (types == null || types.length == 0) {
            return -1;
        }
        int best = -1;
        int bestRank = Integer.MAX_VALUE;
        for (int i = 0; i < types.length; i++) {
            int rank = preferenceRank(types[i]);
            if (rank < bestRank) {
                bestRank = rank;
                best = i;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ 解析

    /**
     * 順手拿系統的散熱狀態。API 29 以下沒有這個接口，讀不到不影響温度那條路。
     */
    private void refreshThermalStatus() {
        thermalStatus = powerManagerStatus(appContext);
    }

    /**
     * 問 PowerManager 當前的散熱狀態。
     *
     * @return 0–6；拿不到就是 {@link #NO_STATUS}
     */
    static int powerManagerStatus(Context context) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return NO_STATUS;
        }
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return pm == null ? NO_STATUS : pm.getCurrentThermalStatus();
        } catch (Throwable e) {
            return NO_STATUS;
        }
    }

    /**
     * 另一條可能的路：{@code HardwarePropertiesManager.getDeviceTemperatures}。
     *
     * <p>它要 {@code DEVICE_POWER} 權限（只有 priv-app 拿得到），普通側載 app 會吃
     * {@link SecurityException}。這裏把它試一遍寫進報告 —— 免得下次又猜「是不是漏了這個接口」。
     * 全反射，所以不需要編譯期依賴。</p>
     */
    private static String probeHardwareProperties(Context context) {
        if (context == null) {
            return "no context";
        }
        try {
            Class<?> clazz = Class.forName("android.os.HardwarePropertiesManager");
            Object manager = context.getSystemService(clazz);
            if (manager == null) {
                return "no service";
            }
            java.lang.reflect.Method method = clazz.getMethod(
                    "getDeviceTemperatures", int.class, int.class);
            // 0 = DEVICE_TEMPERATURE_SKIN，0 = TEMPERATURE_TYPE_CPU
            Object result = method.invoke(manager, 0, 0);
            return "ok: " + java.util.Arrays.toString((float[]) result);
        } catch (Throwable e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return "unavailable: " + cause.getClass().getSimpleName();
        }
    }

    /** 散熱狀態的 ASCII 名字（診斷報告、黑匣子心跳都用它）。 */
    public static String statusName(int status) {
        if (status == NO_STATUS) {
            return "unavailable";
        }
        switch (status) {
            case PowerManager.THERMAL_STATUS_NONE:
                return "none";
            case PowerManager.THERMAL_STATUS_LIGHT:
                return "light";
            case PowerManager.THERMAL_STATUS_MODERATE:
                return "moderate";
            case PowerManager.THERMAL_STATUS_SEVERE:
                return "severe";
            case PowerManager.THERMAL_STATUS_CRITICAL:
                return "critical";
            case PowerManager.THERMAL_STATUS_EMERGENCY:
                return "emergency";
            case PowerManager.THERMAL_STATUS_SHUTDOWN:
                return "shutdown";
            default:
                return "unknown(" + status + ")";
        }
    }

    /**
     * {@code /sys/.../temp} 的原文 → °C。
     *
     * <p>內核給的是毫攝氏度（{@code 41500} = 41.5 °C）。也有驅動給的是度，
     * 那時數值會很小（例如 {@code 41}），所以小於 1000 的一律當成「已經是度」。</p>
     *
     * <p>解析不出來、接近 0（傳感器沒填值）、或者落在 {@link #MIN_CELSIUS}–
     * {@link #MAX_CELSIUS} 之外，一律回 {@code null}（當作沒有讀數）。</p>
     */
    public static Float parseMilliCelsius(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return null;
        }
        float value;
        try {
            value = Float.parseFloat(text);
        } catch (NumberFormatException e) {
            return null;
        }
        float celsius = Math.abs(value) < 1000f ? value : value / 1000f;
        return sane(celsius);
    }

    /**
     * 電池温度那種寫法：{@code 0.1 °C}（{@code 180} = 18.0 °C）。
     *
     * <p>{@code /sys/class/power_supply/*&#47;temp} 是內核 ABI 規定的十分之一度。
     * 但偶爾有驅動直接給度，所以除完 10 之後若落在合理範圍外，就把它當成度再試一次 ——
     * 兩條都不合理才放棄。</p>
     */
    public static Float parseTenthsCelsius(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return null;
        }
        float value;
        try {
            value = Float.parseFloat(text);
        } catch (NumberFormatException e) {
            return null;
        }
        Float tenths = sane(value / 10f);
        if (tenths != null) {
            return tenths;
        }
        // 萬一那個驅動給的是度（180 是 180 °C 就不可能，但 18 會是 1.8 °C 這種怪值）
        return sane(value);
    }

    /**
     * 按路徑挑解析方式：{@code /sys/class/power_supply} 是 0.1 °C，其餘（thermal / hwmon）
     * 是毫攝氏度或度。
     */
    public static Float parseFor(String path, String raw) {
        if (path != null && path.contains("/power_supply/")) {
            return parseTenthsCelsius(raw);
        }
        return parseMilliCelsius(raw);
    }

    /** 合理範圍之外、或者接近 0（沒有填值），回 null。 */
    private static Float sane(float celsius) {
        if (Float.isNaN(celsius) || celsius < MIN_CELSIUS || celsius > MAX_CELSIUS) {
            return null;
        }
        if (Math.abs(celsius) < ZERO_EPSILON) {
            return null;
        }
        return celsius;
    }

    /** 兜底：設備電池温度。 */
    private static Float readBatteryTemperature(Context context) {
        if (context == null) {
            return null;
        }
        try {
            Intent intent = context.registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (intent == null) {
                return null;
            }
            int tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            if (tenths == Integer.MIN_VALUE) {
                return null;
            }
            return sane(tenths / 10f);
        } catch (Exception e) {
            AppLog.w(TAG, "讀設備電池温度失敗: " + e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------ 診斷

    /**
     * 診斷報告用：把每個候選的實況抄成幾行 ASCII（誰讀得到、讀出來是什麼）。
     *
     * <p>刻意全 ASCII：它進的是診斷報告，是數據不是界面文字（看
     * {@code HardcodedTextTest} 的規矩）。中文表頭由 {@code DiagnosticsCollector} 加。</p>
     */
    public static String probeReport(Context context) {
        StringBuilder sb = new StringBuilder();
        for (String root : THERMAL_ROOTS) {
            File dir = new File(root);
            sb.append("  ").append(root).append(": ");
            if (!dir.isDirectory()) {
                sb.append("absent\n");
                continue;
            }
            File[] entries = dir.listFiles();
            if (entries == null) {
                sb.append("cannot list (permission?)\n");
                continue;
            }
            int zones = 0;
            for (File e : entries) {
                if (e.getName().startsWith("thermal_zone")) {
                    zones++;
                }
            }
            sb.append("listable, thermal_zone count=").append(zones).append('\n');
        }
        File hwmon = new File(HWMON_ROOT);
        sb.append("  ").append(HWMON_ROOT).append(": ");
        if (!hwmon.isDirectory()) {
            sb.append("absent\n");
        } else {
            File[] chips = hwmon.listFiles();
            sb.append(chips == null ? "cannot list (permission?)\n"
                    : "listable, chips=" + chips.length + "\n");
        }
        File supplies = new File(POWER_SUPPLY_ROOT);
        sb.append("  ").append(POWER_SUPPLY_ROOT).append(": ");
        if (!supplies.isDirectory()) {
            sb.append("absent\n");
        } else {
            File[] chips = supplies.listFiles();
            sb.append(chips == null ? "cannot list (permission?)\n"
                    : "listable, supplies=" + chips.length + "\n");
        }
        sb.append("  blind probe: thermal_zone0..").append(BLIND_ZONE_PROBE - 1)
                .append(" under each root (listing is often filtered, reading a known path may not be)\n");
        int status = powerManagerStatus(context);
        sb.append("  power manager thermal status: ").append(statusName(status));
        if (status != NO_STATUS) {
            sb.append(" (").append(status).append(')');
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            sb.append(" [API < 29, no such call]");
        }
        sb.append('\n');
        sb.append("  HardwarePropertiesManager: ").append(probeHardwareProperties(context))
                .append('\n');

        List<String> paths = candidatePaths();
        sb.append("  candidates (preference order, first valid wins): ").append(paths.size())
                .append('\n');
        int shown = 0;
        for (String path : paths) {
            if (shown++ >= MAX_PROBE) {
                sb.append("    ... ").append(paths.size() - MAX_PROBE).append(" more\n");
                break;
            }
            String raw = readRaw(path);
            Float parsed = parseFor(path, raw);
            sb.append("    ").append(path)
                    .append(" raw=").append(raw == null ? "-" : raw)
                    .append(" valid=").append(parsed == null ? "no" : "yes")
                    .append(parsed == null ? "" :
                            String.format(java.util.Locale.US, " (%.1fC)", parsed))
                    .append('\n');
        }
        Float battery = readBatteryTemperature(context);
        sb.append("  battery fallback=").append(battery == null ? "unavailable"
                : String.format(java.util.Locale.US, "%.1fC", battery)).append('\n');
        return sb.toString();
    }

    // ------------------------------------------------------------------ 讀文件

    /** 原樣讀一行（診斷報告要看原文，所以不能只回解析結果）。 */
    private static String readRaw(String path) {
        if (path == null) {
            return null;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String line = reader.readLine();
            return line == null ? null : line.trim();
        } catch (Exception e) {
            return null;
        }
    }

    private static String readTypeName(File typeFile) {
        String name = readRaw(typeFile.getAbsolutePath());
        return name == null ? "" : name;
    }
}
