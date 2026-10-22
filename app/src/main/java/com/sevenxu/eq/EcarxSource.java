package com.sevenxu.eq;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import com.sevenxu.eq.AppLog;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 車輛信號這一路：車機系統自帶的 ECARX 車輛接口（{@code com.ecarx.xui.adaptapi}），全反射，只讀。
 *
 * <h3>為什麼是它、怎麼讀（zeekr-shortcut-lab 在 7X 上、在 App Lab 容器裏實測）</h3>
 *
 * <p>{@code ecarx.adaptapi.impl.jar} 在 BOOTCLASSPATH 上，任何應用都能 {@code Car.create(context)}，不要任何車輛權限；
 * 安卓標準的 {@code android.car} 在容器裏結構性拿不到，極氪自家的 {@code com.zeekr.coreservice} 回「對當前應用不可用」。</p>
 *
 * <p><b>訂閲，不輪詢</b>（Lab 0.9.0：回調比輪詢早 50–165 ms，189 次變化輪詢漏 80 次、回調漏 1 次，只訂閲的 CPU 和基線分不出來）：</p>
 * <ul>
 *   <li>離散信號（功能號、傳感器事件）：{@code registerFunctionValueWatcher(int[], 監聽)} 一次掛全部功能號，
 *       {@code registerListener(監聽, 類型)} 逐個掛傳感器；只認帶值的三個回調
 *       {@code onFunctionValueChanged(id, zone, value)} / {@code onSensorEventChanged} / {@code onSensorValueChanged}。
 *       回調在車的 binder 線程上來，轉到自己的線程處理。</li>
 *   <li>註冊後立刻把每一項讀一遍當起點（註冊時車不推當前值）；之後每 {@link #RECONCILE_MS} 再讀一遍對賬，
 *       防車機服務重啓後回調斷了。</li>
 *   <li>浮點傳感器（車速、轉角、深度、温度……）不訂閲 —— 變一點就推，訂了只是噪聲 —— 按 {@link #FLOAT_POLL_MS} 讀。</li>
 *   <li><b>傳感器監聽疑似註銷不掉</b>（註銷再註冊，回調會變成兩條三條），所以傳感器只在進程裏註冊一次、永遠留着，
 *       停了就不理它的回調；功能號的監聽每次停都註銷。車輛對象整個進程只建一次（{@code Car.create} 要一秒）。</li>
 * </ul>
 *
 * <p>信號表在 {@link Signal}；讀數進 {@link Readings}，信息條那份快照由 {@link VehicleStateMapper} 推出來。</p>
 */
final public class EcarxSource {

    private static final String TAG = "EcarxSource";

    static final String CAR_CLASS = "com.ecarx.xui.adaptapi.car.Car";

    /** 浮點傳感器多久讀一次。 */
    static final long FLOAT_POLL_MS = 200L;

    /**
     * 變化慢的那些浮點信號的節拍。
     *
     * <p>實測證據（車機自帶診斷日誌，2026-10-05）：我們每秒向車廠 SDK 問 45 次傳感器值
     * （9 個浮點信號 × 5 次/秒），每次都是「反射 + Binder」，而 SDK 自己也每條打兩行
     * DEBUG 日誌 —— 於是車機的日誌緩衝區被這種噪聲刷滿，油温、電量、裏程這些
     * 半分鐘才變一點的量卻在按 5 Hz 追。</p>
     *
     * <p>所以只把「真的在動」的那幾個留在 {@link #FLOAT_POLL_MS}：
     * 車速、剎車深度、油門深度、轉向角。温度、電量、續航、總裏程按這個節拍讀。</p>
     */
    static final long SLOW_FLOAT_POLL_MS = 1000L;

    /** 慢信號每多少個快節拍讀一次。 */
    static final int SLOW_EVERY = (int) (SLOW_FLOAT_POLL_MS / FLOAT_POLL_MS);

    /**
     * 這個信號變化慢不慢（温度、電量、續航、總裏程）。
     *
     * <p>判斷依據是「人眼在這個讀數上能看出的變化要多久」：車內温度不會在 200 ms 裏變，
     * 車速會。分錯的代價只是讀數更新慢一點，不是讀錯。</p>
     */
    public static boolean isSlowFloat(Signal signal) {
        switch (signal) {
            case ODOMETER:
            case BATTERY:
            case RANGE:
            case TEMP_OUTSIDE:
            case TEMP_INSIDE:
            case BATTERY_TEMP:
                return true;
            default:
                return false;
        }
    }

    /**
     * 當前輪詢負荷，給診斷報告用。
     *
     * <p>刻意<b>只回 ASCII 的數字</b>（{@code fast=4/200ms slow=6/1000ms callsPerSecond=26}）：
     * Java 裏不許寫死中文（{@code HardcodedTextTest} 會捉），中文説明由診斷報告那邊套上去。</p>
     */
    public static String pollLoad() {
        int fast = 0;
        int slow = 0;
        for (Signal s : Signal.values()) {
            if (!s.isFloat()) {
                continue;
            }
            if (isSlowFloat(s)) {
                slow++;
            } else {
                fast++;
            }
        }
        int perSecond = (int) (fast * 1000L / FLOAT_POLL_MS + slow * 1000L / SLOW_FLOAT_POLL_MS);
        return "fast=" + fast + "/" + FLOAT_POLL_MS + "ms slow=" + slow + "/"
                + SLOW_FLOAT_POLL_MS + "ms callsPerSecond=" + perSecond;
    }
    /** 離散信號多久對一次賬（回調在的時候這一遍幾乎讀不到變化）。 */
    static final long RECONCILE_MS = 3000L;
    /** 一次變化引起的發佈合併在這麼長的窗口裏。 */
    private static final long PUBLISH_COALESCE_MS = 40L;
    /** 車輛信息：主駕在哪一邊（ICarInfo.INT_INFO_DRIVER_SIDE），讀到 0x00100302 = 右、0x00100301 = 左。 */
    private static final int INFO_DRIVER_SIDE = 0x00100300;
    private static final int DRIVER_SIDE_LEFT = 0x00100301;
    private static final int DRIVER_SIDE_RIGHT = 0x00100302;

    /** 車輛對象整個進程只建一次。 */
    private static volatile Object cachedCar;
    /** 傳感器監聽：進程裏只註冊一次、永不註銷（註銷不掉）；回調轉給此刻活着的那個來源。 */
    private static final Object SENSOR_LOCK = new Object();
    private static Object sensorProxy;
    private static final Set<Integer> sensorTypesRegistered = new HashSet<>();
    private static volatile EcarxSource active;

    private final Telemetry telemetry;
    private final VehicleStateMapper mapper = new VehicleStateMapper();
    private final Map<Signal, Object> values = new EnumMap<>(Signal.class);
    /** 讀法 + 號碼 + 區域 → 用它的信號（同一個號碼可能幾條信號都用）。 */
    private final Map<Long, List<Signal>> byKey = new HashMap<>();
    private HandlerThread thread;
    private volatile Handler handler;
    private volatile String status = "not started";
    private volatile boolean stopped;
    private long readingsVersion;
    private boolean publishPending;
    private boolean firstRoundReported;
    private boolean driverOnRight = true;

    private Object function;
    private Object sensor;
    private Method getFunctionValue;
    private Method getFunctionValueZoned;
    private Method getSensorEvent;
    private Method getSensorLatestValue;
    private Object functionWatcher;
    private int[] functionIds = new int[0];

    private final Runnable floatPoll = this::pollFloats;
    private final Runnable reconcile = this::reconcileDiscrete;
    private final Runnable publish = this::publishNow;

    /** 輪詢節拍計數器，決定這一拍要不要順帶讀慢信號。 */
    private int floatTick;

    /** 每個信號最後讀到的**原始**值（未經解碼）。診斷報告要看「車到底報了什麼」。 */
    private static final java.util.Map<Signal, Object> LAST_RAW =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 浮點信號的 min / max / 樣本數 —— 「永遠 30°」這種問題只能靠範圍看出來。 */
    private static final java.util.Map<Signal, float[]> RANGES =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 最後讀到的原始值；沒讀到回 null。 */
    public static Object lastRaw(Signal signal) {
        return LAST_RAW.get(signal);
    }

    /**
     * 浮點信號的取值範圍：{@code 標籤 min=.. max=.. n=..}（ASCII，給診斷報告用）。
     *
     * <p>為什麼需要它：車機某個信號一直報同一個數（例如電池温度永遠是 30）時，
     * 單看一次讀數是看不出來的 —— 是環境真的一直 30 度，還是這個信號根本沒在更新？
     * min 和 max 一樣、樣本上百，就説明它沒動過。</p>
     */
    public static String describeFloatRanges() {
        StringBuilder sb = new StringBuilder();
        for (Signal signal : Signal.values()) {
            float[] range = RANGES.get(signal);
            if (range == null) {
                continue;
            }
            sb.append("    ").append(signal.name())
                    .append(" min=").append(trim(range[0]))
                    .append(" max=").append(trim(range[1]))
                    .append(" n=").append((int) range[2])
                    .append(range[0] == range[1] ? " [never changed]" : "")
                    .append('\n');
        }
        if (sb.length() == 0) {
            return "    (no readings yet)\n";
        }
        return sb.toString();
    }

    /**
     * 剎車與油門的<b>當前原始值</b>（一行，ASCII）。
     *
     * <p>用户 2026-10-08：「是否已清楚提取腳煞的訊號？probe 顯示最大只有 30%」——
     * 車上踩住腳煞再上載報告，這一行就答得到：讀到 0–30 就代表擷取正常，
     * 讀到 {@code -} 就代表這條 ID 根本沒報。</p>
     */
    public static String describeDriveRaw() {
        com.sevenxu.eq.Readings readings =
                com.sevenxu.eq.Telemetry.get().readings();
        return "BRAKE_DEPTH=" + rawOf(readings, Signal.BRAKE_DEPTH)
                + " BRAKE_PEDAL=" + rawOf(readings, Signal.BRAKE_PEDAL)
                + " THROTTLE_DEPTH=" + rawOf(readings, Signal.THROTTLE_DEPTH);
    }

    private static String rawOf(com.sevenxu.eq.Readings readings, Signal signal) {
        Object value = readings.get(signal);
        return value == null ? "-" : String.valueOf(value);
    }

    /** 所有輔助駕駛信號的原始值（一行，ASCII）：用來在車上校準哪一條是什麼含義。 */
    public static String describeAssistRaw() {
        StringBuilder sb = new StringBuilder();
        for (Signal signal : Signal.values()) {
            if (signal.group != Signal.Group.ASSIST) {
                continue;
            }
            Object raw = LAST_RAW.get(signal);
            sb.append(signal.name()).append('=')
                    .append(raw == null ? "-" : String.valueOf(raw)).append(' ');
        }
        return sb.length() == 0 ? "(none)" : sb.toString().trim();
    }

    /** 記下原始值，浮點信號另外累計 min / max / 樣本數。 */
    private static void recordRaw(Signal signal, Object raw) {
        if (raw == null) {
            return;
        }
        LAST_RAW.put(signal, raw);
        if (!(raw instanceof Number)) {
            return;
        }
        float value = ((Number) raw).floatValue();
        if (Float.isNaN(value)) {
            return;
        }
        float[] range = RANGES.get(signal);
        if (range == null) {
            RANGES.put(signal, new float[]{value, value, 1f});
            return;
        }
        synchronized (range) {
            range[0] = Math.min(range[0], value);
            range[1] = Math.max(range[1], value);
            range[2] += 1f;
        }
    }

    private static String trim(float value) {
        if (value == Math.rint(value)) {
            return String.valueOf((int) value);
        }
        return String.format(java.util.Locale.US, "%.1f", value);
    }

    EcarxSource(Telemetry telemetry) {
        this.telemetry = telemetry;
        for (Signal s : Signal.values()) {
            long key = key(s.kind, s.id, s.zone);
            List<Signal> list = byKey.get(key);
            if (list == null) {
                list = new ArrayList<>();
                byKey.put(key, list);
            }
            list.add(s);
        }
    }

    private static long key(Signal.Kind kind, int id, int zone) {
        // 讀法 2 位 | 號碼 32 位 | 區域 30 位（後備箱的區域是 0x20000000）
        return ((long) kind.ordinal() << 62) | ((id & 0xFFFFFFFFL) << 30) | (zone & 0x3FFFFFFFL);
    }

    void start(Context context) {
        final Context app = context.getApplicationContext();
        thread = new HandlerThread("Telemetry-Ecarx");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(() -> connect(app));
    }

    void stop() {
        stopped = true;
        if (active == this) {
            active = null;
        }
        Handler h = handler;
        HandlerThread t = thread;
        handler = null;
        thread = null;
        if (h != null) {
            h.removeCallbacksAndMessages(null);
            // 功能號的監聽每次都註銷；傳感器的留着（註銷不掉，見類説明）
            final Object watcher = functionWatcher;
            functionWatcher = null;
            if (watcher != null) {
                h.post(() -> unregisterFunctions(watcher));
            }
        }
        if (t != null) {
            t.quitSafely();
        }
        status = "stopped";
    }

    String status() {
        return status;
    }

    // ================================================================= 連接（在自己的線程上）

    private void connect(Context context) {
        long start = SystemClock.elapsedRealtime();
        try {
            Class<?> carClass = Class.forName(CAR_CLASS);
            Object car = cachedCar;
            if (car == null) {
                Method create = match(carClass.getMethods(), "create", Context.class);
                if (create == null) {
                    status = "no Car.create(Context)";
                    report();
                    return;
                }
                car = create.invoke(null, context);
                if (car == null) {
                    status = "Car.create returned null";
                    report();
                    return;
                }
                cachedCar = car;
            }
            function = call(car, "getICarFunction");
            sensor = call(car, "getSensorManager");
            if (function != null) {
                getFunctionValue = findMethod(function, "getFunctionValue", int.class);
                getFunctionValueZoned = findMethod(function, "getFunctionValue", int.class, int.class);
            }
            if (sensor != null) {
                getSensorEvent = findMethod(sensor, "getSensorEvent", int.class);
                getSensorLatestValue = findMethod(sensor, "getSensorLatestValue", int.class);
            }
            if (getFunctionValue == null && getSensorEvent == null) {
                status = "no readable managers (function=" + (function != null) + ", sensor=" + (sensor != null) + ")";
                report();
                return;
            }
            String side = readDriverSide(car);
            String subscribed = subscribe();
            if (stopped) {
                unregisterFunctions(functionWatcher);
                functionWatcher = null;
                return;
            }
            active = this;
            readAll();
            status = String.format(Locale.US,
                    "connected in %d ms; function=%s zoned=%s sensorEvent=%s sensorValue=%s; driver %s; %s",
                    SystemClock.elapsedRealtime() - start, getFunctionValue != null, getFunctionValueZoned != null,
                    getSensorEvent != null, getSensorLatestValue != null, side, subscribed);
            report();
            Handler h = handler;
            if (h != null && !stopped) {
                h.postDelayed(floatPoll, FLOAT_POLL_MS);
                h.postDelayed(reconcile, RECONCILE_MS);
            }
        } catch (ClassNotFoundException e) {
            status = "no " + CAR_CLASS + " on this head unit";
            report();
        } catch (Throwable t) {
            status = "connect failed: " + describe(t);
            report();
        }
    }

    private void report() {
        AppLog.i(TAG, status);
        telemetry.sourceReported("ecarx", status);
    }

    /** 主駕在哪一邊：車輛信息 INT_INFO_DRIVER_SIDE。讀不到按右舵（這台車）。 */
    private String readDriverSide(Object car) {
        try {
            Object info = call(car, "getCarInfoManager");
            Method get = info == null ? null : findMethod(info, "getCarInfoInt", int.class);
            if (get == null) {
                return "right (no car info)";
            }
            Object v = get.invoke(info, INFO_DRIVER_SIDE);
            int code = v instanceof Number ? ((Number) v).intValue() : 0;
            if (code == DRIVER_SIDE_RIGHT) {
                driverOnRight = true;
                return "right";
            }
            if (code == DRIVER_SIDE_LEFT) {
                driverOnRight = false;
                return "left";
            }
            return "right (info read 0x" + Integer.toHexString(code) + ")";
        } catch (Throwable t) {
            return "right (info failed: " + describe(t) + ")";
        }
    }

    // ================================================================= 訂閲

    /** 掛監聽；結果匯成一句話進狀態。 */
    private String subscribe() {
        StringBuilder out = new StringBuilder();
        if (function != null) {
            out.append(subscribeFunctions());
        }
        if (sensor != null) {
            if (out.length() > 0) {
                out.append("; ");
            }
            out.append(subscribeSensors());
        }
        return out.toString();
    }

    /** 功能號：一次掛全部；這台車不認數組就逐個掛。 */
    private String subscribeFunctions() {
        Method byArray = findMethodWithInterface(function, "registerFunctionValueWatcher", int[].class);
        Method byOne = findMethodWithInterface(function, "registerFunctionValueWatcher", int.class);
        Method any = byArray != null ? byArray : byOne;
        if (any == null) {
            return "functions: no registerFunctionValueWatcher";
        }
        Class<?> type = any.getParameterTypes()[1];
        Object proxy = Proxy.newProxyInstance(EcarxSource.class.getClassLoader(), new Class<?>[]{type}, new FunctionCallback());
        Set<Integer> ids = new LinkedHashSet<>();
        for (Signal s : Signal.values()) {
            if (s.kind == Signal.Kind.FUNCTION || s.kind == Signal.Kind.FUNCTION_ZONE) {
                ids.add(s.id);
            }
        }
        functionIds = new int[ids.size()];
        int i = 0;
        for (Integer id : ids) {
            functionIds[i++] = id;
        }
        if (byArray != null && invokeOk(byArray, function, functionIds, proxy)) {
            functionWatcher = proxy;
            return "functions: watching " + functionIds.length;
        }
        if (byOne != null) {
            int done = 0;
            for (int id : functionIds) {
                if (invokeOk(byOne, function, id, proxy)) {
                    done++;
                }
            }
            if (done > 0) {
                functionWatcher = proxy;
            }
            return "functions: per-id " + done + "/" + functionIds.length;
        }
        return "functions: refused";
    }

    /** 傳感器事件：進程裏每個類型只註冊一次。 */
    private String subscribeSensors() {
        synchronized (SENSOR_LOCK) {
            Method register = findMethodWithInterfaceFirst(sensor, "registerListener", int.class);
            if (register == null) {
                return "sensors: no registerListener";
            }
            Class<?> type = register.getParameterTypes()[0];
            if (sensorProxy == null) {
                sensorProxy = Proxy.newProxyInstance(EcarxSource.class.getClassLoader(), new Class<?>[]{type}, new SensorCallback());
            }
            int added = 0;
            int refused = 0;
            for (Signal s : Signal.values()) {
                if (s.kind != Signal.Kind.SENSOR_EVENT || sensorTypesRegistered.contains(s.id)) {
                    continue;
                }
                if (invokeOk(register, sensor, sensorProxy, s.id)) {
                    sensorTypesRegistered.add(s.id);
                    added++;
                } else {
                    refused++;
                }
            }
            String line = "sensors: " + sensorTypesRegistered.size() + " listening";
            if (added > 0 || refused > 0) {
                line += " (+" + added + (refused > 0 ? ", " + refused + " refused" : "") + ")";
            }
            return line;
        }
    }

    private void unregisterFunctions(Object watcher) {
        if (watcher == null || function == null) {
            return;
        }
        try {
            Method one = findMethodWithInterface(function, "unregisterFunctionValueWatcher");
            if (one != null) {
                one.invoke(function, watcher);
                return;
            }
            Method withIds = findMethodWithInterface(function, "unregisterFunctionValueWatcher", int[].class);
            if (withIds != null) {
                withIds.invoke(function, functionIds, watcher);
                return;
            }
            Method perId = findMethodWithInterface(function, "unregisterFunctionValueWatcher", int.class);
            if (perId != null) {
                for (int id : functionIds) {
                    perId.invoke(function, id, watcher);
                }
            }
        } catch (Throwable e) {
            AppLog.w(TAG, "unregisterFunctionValueWatcher failed: " + describe(e));
        }
    }

    /** 功能號的回調：只認帶值的 onFunctionValueChanged(id, zone, value)。 */
    private final class FunctionCallback implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if ("onFunctionValueChanged".equals(method.getName()) && args != null && args.length == 3
                    && args[0] instanceof Integer && args[1] instanceof Integer && args[2] instanceof Number) {
                final int id = (Integer) args[0];
                final int zone = (Integer) args[1];
                final Object value = args[2];
                Handler h = handler;
                if (h != null && !stopped) {
                    h.post(() -> onFunctionValue(id, zone, value));
                }
                return null;
            }
            return proxyDefault(proxy, method, args, "EcarxSource.FunctionCallback");
        }
    }

    /** 傳感器的回調（進程級的一個代理）：轉給此刻活着的來源。 */
    private static final class SensorCallback implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            EcarxSource target = active;
            if (target != null && args != null && args.length == 2 && args[0] instanceof Integer
                    && args[1] instanceof Number) {
                String name = method.getName();
                Signal.Kind kind = "onSensorEventChanged".equals(name) ? Signal.Kind.SENSOR_EVENT
                        : "onSensorValueChanged".equals(name) ? Signal.Kind.SENSOR_VALUE : null;
                if (kind != null) {
                    final int type = (Integer) args[0];
                    final Object value = args[1];
                    Handler h = target.handler;
                    if (h != null && !target.stopped) {
                        h.post(() -> target.onSensor(kind, type, value));
                    }
                    return null;
                }
            }
            return proxyDefault(proxy, method, args, "EcarxSource.SensorCallback");
        }
    }

    /** 代理上其餘的方法：Object 的三個照常，別的回個零值。 */
    private static Object proxyDefault(Object proxy, Method method, Object[] args, String label) {
        String name = method.getName();
        if ("toString".equals(name)) {
            return label;
        }
        if ("hashCode".equals(name)) {
            return System.identityHashCode(proxy);
        }
        if ("equals".equals(name)) {
            return args != null && args.length == 1 && proxy == args[0];
        }
        Class<?> r = method.getReturnType();
        if (r == boolean.class) {
            return false;
        }
        if (r == int.class || r == short.class || r == byte.class) {
            return 0;
        }
        if (r == long.class) {
            return 0L;
        }
        if (r == float.class) {
            return 0f;
        }
        if (r == double.class) {
            return 0d;
        }
        if (r == char.class) {
            return '\0';
        }
        return null;
    }

    // ================================================================= 讀數進表（都在自己的線程上）

    private void onFunctionValue(int id, int zone, Object value) {
        // 不帶區域的信號：回調裏的 zone 不管是什麼都收；帶區域的按區域對
        List<Signal> global = byKey.get(key(Signal.Kind.FUNCTION, id, 0));
        if (global != null) {
            for (Signal s : global) {
                apply(s, value);
            }
        }
        List<Signal> zoned = byKey.get(key(Signal.Kind.FUNCTION_ZONE, id, zone));
        if (zoned != null) {
            for (Signal s : zoned) {
                apply(s, value);
            }
        }
    }

    private void onSensor(Signal.Kind kind, int type, Object value) {
        List<Signal> list = byKey.get(key(kind, type, 0));
        if (list != null) {
            for (Signal s : list) {
                apply(s, value);
            }
        }
    }

    /** 歸一、比對、有變化才記；發佈合併在一個小窗口裏。 */
    private void apply(Signal s, Object raw) {
        recordRaw(s, raw);
        Object decoded = s.decode(raw);
        Object before = values.get(s);
        boolean changed = decoded == null ? before != null : !decoded.equals(before);
        if (!changed) {
            return;
        }
        if (decoded == null) {
            values.remove(s);
        } else {
            values.put(s, decoded);
        }
        schedulePublish(0);
        if (s == Signal.TURN_LEFT || s == Signal.TURN_RIGHT) {
            // 閃爍保持到點要再算一次，否則鬆手後雙閃要等下一次變化才滅
            schedulePublish(TurnSignalHold.HOLD_MS + 50);
        }
    }

    private void schedulePublish(long delayMs) {
        Handler h = handler;
        if (h == null || stopped) {
            return;
        }
        if (delayMs > 0) {
            h.postDelayed(publish, delayMs);
            return;
        }
        if (!publishPending) {
            publishPending = true;
            h.postDelayed(publish, PUBLISH_COALESCE_MS);
        }
    }

    private void publishNow() {
        publishPending = false;
        if (stopped) {
            return;
        }
        final Readings snapshot = new Readings(values, ++readingsVersion);
        final long now = SystemClock.uptimeMillis();
        if (snapshot.number(Signal.SPEED) != null) {
            telemetry.noteCarSpeed();
            // 報「剛讀到車速」，車速牌靠它判斷讀數有沒有過期（見 Telemetry.ageMs）
            telemetry.noteReading(Signal.SPEED);
        }
        // 温度牌一樣要判過期：讀數停了就畫 --，不要掛着舊温度
        if (snapshot.number(Signal.BATTERY_TEMP) != null) {
            telemetry.noteReading(Signal.BATTERY_TEMP);
        }
        if (snapshot.number(Signal.TEMP_INSIDE) != null) {
            telemetry.noteReading(Signal.TEMP_INSIDE);
        }
        if (snapshot.number(Signal.TEMP_OUTSIDE) != null) {
            telemetry.noteReading(Signal.TEMP_OUTSIDE);
        }
        // 非開發者：不能用的信號先濾掉再映射，信息條上不出現猜的東西
        final Readings forBar = telemetry.infoBarAllActive() ? snapshot : snapshot.usableOnly();
        telemetry.edit(b -> mapper.apply(b, forBar, now, driverOnRight));
        telemetry.publishReadings(snapshot);
        if (!firstRoundReported) {
            firstRoundReported = true;
            AppLog.note("行駛信息 ecarx 第一輪讀數：" + snapshot.describe());
        }
    }

    /** 每一項讀一遍：起點。 */
    private void readAll() {
        for (Signal s : Signal.values()) {
            apply(s, read(s));
        }
        schedulePublish(0);
    }

    private void reconcileDiscrete() {
        if (stopped) {
            return;
        }
        for (Signal s : Signal.values()) {
            if (!s.isFloat()) {
                apply(s, read(s));
            }
        }
        Handler h = handler;
        if (h != null) {
            h.postDelayed(reconcile, RECONCILE_MS);
        }
    }

    private void pollFloats() {
        if (stopped) {
            return;
        }
        // 慢信號每 SLOW_EVERY 個節拍讀一次（見 SLOW_FLOAT_POLL_MS 的實測依據）
        boolean slowRound = floatTick++ % SLOW_EVERY == 0;
        for (Signal s : Signal.values()) {
            if (!s.isFloat()) {
                continue;
            }
            if (isSlowFloat(s) && !slowRound) {
                continue;
            }
            apply(s, read(s));
        }
        Handler h = handler;
        if (h != null) {
            h.postDelayed(floatPoll, FLOAT_POLL_MS);
        }
    }

    /** 直接讀一次；讀不到返回 null。 */
    private Object read(Signal s) {
        try {
            switch (s.kind) {
                case FUNCTION:
                    return getFunctionValue == null ? null : getFunctionValue.invoke(function, s.id);
                case FUNCTION_ZONE:
                    return getFunctionValueZoned == null ? null : getFunctionValueZoned.invoke(function, s.id, s.zone);
                case SENSOR_EVENT:
                    return getSensorEvent == null ? null : getSensorEvent.invoke(sensor, s.id);
                case SENSOR_VALUE:
                    return getSensorLatestValue == null ? null : getSensorLatestValue.invoke(sensor, s.id);
                default:
                    return null;
            }
        } catch (Throwable t) {
            return null;
        }
    }

    // ================================================================= 反射小工具

    private static Object call(Object target, String name) {
        Method m = findMethod(target, name);
        if (m == null) {
            return null;
        }
        try {
            return m.invoke(target);
        } catch (Throwable t) {
            AppLog.w(TAG, name + " failed: " + describe(t));
            return null;
        }
    }

    /** 調一下算不算成功：沒拋異常，而且（返回 boolean 的話）返回 true。 */
    private static boolean invokeOk(Method m, Object target, Object... args) {
        try {
            Object r = m.invoke(target, args);
            return !(r instanceof Boolean) || (Boolean) r;
        } catch (Throwable t) {
            AppLog.w(TAG, m.getName() + " failed: " + describe(t));
            return false;
        }
    }

    /**
     * 找公開方法：先在對象實現的<b>公開接口</b>上找（實現類 {@code com.zeekrlife.adaptapi.car.impl.*}
     * 不是公開的，拿它的 Method 去調會被訪問檢查攔下），找不到再退回類本身。
     */
    static Method findMethod(Object target, String name, Class<?>... params) {
        for (Class<?> type : publicInterfaces(target)) {
            Method m = match(type.getMethods(), name, params);
            if (m != null) {
                return m;
            }
        }
        Method m = match(target.getClass().getMethods(), name, params);
        if (m != null) {
            try {
                m.setAccessible(true);
            } catch (Throwable ignored) {
                // 設不上就算了，調的時候再看
            }
        }
        return m;
    }

    /** 找「(first, 某個接口)」形狀的方法：註冊 / 註銷監聽的那種，接口類型從籤名上取。 */
    private static Method findMethodWithInterface(Object target, String name, Class<?> first) {
        for (Class<?> type : publicInterfaces(target)) {
            for (Method m : type.getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().equals(name) && p.length == 2 && p[0] == first && p[1].isInterface()) {
                    return m;
                }
            }
        }
        return null;
    }

    /** 找「(某個接口)」形狀的方法。 */
    private static Method findMethodWithInterface(Object target, String name) {
        for (Class<?> type : publicInterfaces(target)) {
            for (Method m : type.getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().equals(name) && p.length == 1 && p[0].isInterface()) {
                    return m;
                }
            }
        }
        return null;
    }

    /** 找「(某個接口, second)」形狀的方法：傳感器的 registerListener(listener, type)。 */
    private static Method findMethodWithInterfaceFirst(Object target, String name, Class<?> second) {
        for (Class<?> type : publicInterfaces(target)) {
            for (Method m : type.getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().equals(name) && p.length == 2 && p[0].isInterface() && p[1] == second) {
                    return m;
                }
            }
        }
        return null;
    }

    private static List<Class<?>> publicInterfaces(Object target) {
        List<Class<?>> types = new ArrayList<>();
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            collectInterfaces(c.getInterfaces(), types);
        }
        List<Class<?>> pub = new ArrayList<>();
        for (Class<?> t : types) {
            if (Modifier.isPublic(t.getModifiers())) {
                pub.add(t);
            }
        }
        return pub;
    }

    private static void collectInterfaces(Class<?>[] interfaces, List<Class<?>> into) {
        for (Class<?> i : interfaces) {
            if (!into.contains(i)) {
                into.add(i);
                collectInterfaces(i.getInterfaces(), into);
            }
        }
    }

    private static Method match(Method[] methods, String name, Class<?>... params) {
        for (Method m : methods) {
            if (!m.getName().equals(name)) {
                continue;
            }
            Class<?>[] types = m.getParameterTypes();
            if (types.length != params.length) {
                continue;
            }
            boolean same = true;
            for (int i = 0; i < types.length; i++) {
                if (types[i] != params[i]) {
                    same = false;
                    break;
                }
            }
            if (same) {
                return m;
            }
        }
        return null;
    }

    /** 反射那一層剝掉，露出真正的原因。 */
    private static String describe(Throwable e) {
        Throwable t = e;
        while ((t instanceof InvocationTargetException || t instanceof UndeclaredThrowableException)
                && t.getCause() != null) {
            t = t.getCause();
        }
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
