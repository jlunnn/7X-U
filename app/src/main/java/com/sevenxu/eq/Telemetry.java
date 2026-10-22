package com.sevenxu.eq;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

// AppConfig 唔需要（7X=U 用 SharedPreferences）
import com.sevenxu.eq.AppLog;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 車輛信號的唯一入口：來源往這裏寫，用的人從這裏讀。
 *
 * <h3>框架</h3>
 *
 * <ul>
 *   <li>兩份快照，都不可變、版本號只往上走：{@link Readings}（每個信號的讀數，「系統信息」頁看它）和
 *       {@link VehicleState}（信息條要的那幾項，由 {@link VehicleStateMapper} 從讀數推出來，再加定位）。</li>
 *   <li>來源各管一條路：{@link EcarxSource}（車機自帶的 ECARX 接口，訂閲），{@link LocationSource}（經緯度，
 *       車不給車速時用 GPS 車速）。再有新的路就是再加一個來源。</li>
 *   <li><b>誰要用就登記</b>（{@link #acquire} / {@link #release}，和相機的登記表一個道理）：錄像開着信息條、
 *       「系統信息」頁開着，都算要用；沒人要了來源全停、快照清空 —— 關掉頁面就釋放全部資源，
 *       下次不會把舊值畫進新錄像。</li>
 * </ul>
 *
 * <p>每個來源連上（或連不上）都往黑匣子記一行「什麼拿得到、什麼拿不到」。</p>
 */
public final class Telemetry {

    private static final String TAG = "Telemetry";
    private static final Telemetry INSTANCE = new Telemetry();

    /** 來源改快照的方式：在最新一份上改幾項。 */
    public interface Edit {
        void apply(VehicleState.Builder builder);
    }

    /** 讀數變了誰來看（在主線程上回調）。 */
    public interface Listener {
        void onReadingsChanged(Readings readings);
    }

    private final Object lock = new Object();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> users = new LinkedHashSet<>();
    private final List<Listener> listeners = new ArrayList<>();
    private volatile VehicleState state = VehicleState.empty();
    private volatile Readings readings = Readings.empty();
    private boolean running;
    /** 車速有沒有從車輛接口來過：有的話定位那邊的 GPS 車速就不寫了（那個是推算的）。 */
    private volatile boolean carSpeedSeen;
    /**
     * 每個信號的最後一個讀數時刻（uptimeMillis）。
     *
     * <p>用一張表而不是一個字段：現在有兩張牌（車速、電池温度）要判過期，以後多半還會加
     * （車外温度、續航）。表裏沒有就是「這輪還沒讀到過」。</p>
     */
    private final java.util.Map<Signal, Long> readingStamps = new java.util.concurrent.ConcurrentHashMap<>();
    /** 信息條那份快照用全部讀數（開發者的「激活所有欄目信息」）還是只用驗證過的。 */
    private volatile boolean infoBarAll;
    private EcarxSource car;
    private LocationSource location;
    /** 車機上一次報車速的時刻（判斷「現在還有沒有」）。 */
    private long carSpeedAtMs;

    private Telemetry() {
    }

    public static Telemetry get() {
        return INSTANCE;
    }

    /** 登記：我要用車輛信號。第一個登記的把來源拉起來；重複登記無害。 */
    public void acquire(Context context, String who) {
        synchronized (lock) {
            users.add(who);
            if (running) {
                return;
            }
            running = true;
            carSpeedSeen = false;
            readingStamps.clear();
            state = VehicleState.empty();
            readings = Readings.empty();
            Context app = context.getApplicationContext();
            infoBarAll = new AppConfig(app).isInfoBarAllActive();
            car = new EcarxSource(this);
            location = new LocationSource(this);
            car.start(app);
            location.start(app);
        }
        AppLog.i(TAG, "車輛信號開始收集（" + who + "）");
    }

    /** 註銷：我不用了。最後一個註銷的把來源全停、快照清空；沒登記過也無害。 */
    public void release(String who) {
        EcarxSource c;
        LocationSource l;
        synchronized (lock) {
            users.remove(who);
            if (!running || !users.isEmpty()) {
                return;
            }
            running = false;
            c = car;
            l = location;
            car = null;
            location = null;
            readingStamps.clear();
            state = VehicleState.empty();
            readings = Readings.empty();
        }
        if (l != null) {
            l.stop();
        }
        if (c != null) {
            c.stop();
        }
        AppLog.i(TAG, "車輛信號停止收集（" + who + " 是最後一個）");
        notifyListeners(Readings.empty());
    }

    public boolean isRunning() {
        return running;
    }

    /** 信息條要的快照，永遠不為 null。 */
    public VehicleState latest() {
        return state;
    }

    /** 每個信號的讀數，永遠不為 null。 */
    public Readings readings() {
        return readings;
    }

    /** 來源改幾項並發佈。停了之後的寫入丟掉。 */
    public void edit(Edit edit) {
        synchronized (lock) {
            if (!running) {
                return;
            }
            VehicleState.Builder b = state.edit();
            edit.apply(b);
            state = b.build();
        }
    }

    /** 來源發佈一份讀數。停了之後的丟掉。 */
    void publishReadings(Readings snapshot) {
        synchronized (lock) {
            if (!running) {
                return;
            }
            readings = snapshot;
        }
        notifyListeners(snapshot);
    }

    public void addListener(Listener listener) {
        synchronized (listeners) {
            if (!listeners.contains(listener)) {
                listeners.add(listener);
            }
        }
    }

    public void removeListener(Listener listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }

    private void notifyListeners(Readings snapshot) {
        List<Listener> copy;
        synchronized (listeners) {
            if (listeners.isEmpty()) {
                return;
            }
            copy = new ArrayList<>(listeners);
        }
        main.post(() -> {
            for (Listener l : copy) {
                try {
                    l.onReadingsChanged(snapshot);
                } catch (RuntimeException e) {
                    AppLog.w(TAG, "listener failed: " + e);
                }
            }
        });
    }

    /** 信息條快照要不要沒驗證過的信號。 */
    boolean infoBarAllActive() {
        return infoBarAll;
    }

    void noteCarSpeed() {
        carSpeedSeen = true;
        carSpeedAtMs = android.os.SystemClock.uptimeMillis();
    }

    /** 車機到底有沒有報過車速（有過就一直 true）。 */
    boolean hasCarSpeed() {
        return carSpeedSeen;
    }

    /**
     * 車機「現在」還有沒有在報車速。
     *
     * <p>為什麼要分開：用户 2026-10-08 問「車速顯示有時變咗 --，點解」。
     * 原因就在這裡 —— 以前 GPS 車速的後備條件是 {@link #hasCarSpeed()}（<b>曾經</b>有過就
     * 不再用 GPS），於是車機一旦斷（ACC 循環、車機睡眠重連），兩邊都沒有讀數，
     * 5 秒之後車速牌就畫 --。<b>要的是「現在還有沒有」，不是「曾經有沒有」。</b></p>
     */
    boolean carSpeedFresh() {
        if (!carSpeedSeen) {
            return false;
        }
        long age = android.os.SystemClock.uptimeMillis() - carSpeedAtMs;
        return age >= 0L && age <= RoundSign.STALE_MS;
    }

    /** GPS 車速要不要補上：車機的車速已經不新鮮（或者從來沒有過）就由 GPS 接手。 */
    static boolean gpsSpeedShouldFillIn(boolean carSpeedFresh) {
        return !carSpeedFresh;
    }

    /**
     * GPS 這一筆要寫幾多 km/h。
     *
     * <p>用户 2026-10-08：「GPS 無數值就用 0」—— 有定位但速度欄位沒有值時當 0
     * （泊車、慢速時 GPS 正是這樣報），不要讓車速牌變成 --。</p>
     *
     * @param hasFixSpeed 定位有沒有帶速度欄位
     * @param fixSpeedMps 定位報的速度（米／秒）；沒有速度時這個值不會被用
     */
    static float gpsSpeedKmh(boolean hasFixSpeed, float fixSpeedMps) {
        return hasFixSpeed ? fixSpeedMps * 3.6f : 0f;
    }

    /**
     * 來源報一聲「我剛寫了這個信號的一個讀數」。
     *
     * <p>要的人（懸浮按鈕那兩張牌）靠它判斷讀數有沒有過期：兩個來源都會安靜地停
     * —— GPS 進隧道、ECARX 在車機睡覺時 —— 停了之後沒人會把舊值擦掉。</p>
     */
    void noteReading(Signal signal) {
        if (signal != null) {
            readingStamps.put(signal, android.os.SystemClock.uptimeMillis());
        }
    }

    /**
     * 這個信號的讀數已經多久了。
     *
     * <p>從來沒讀到過就是 {@link Long#MAX_VALUE}（調用方按「沒有讀數」處理），
     * 而不是 0 —— 0 是「剛剛才讀到」，兩者不能混。時鐘回撥導致的負數收成 0。</p>
     */
    public long ageMs(Signal signal) {
        if (signal == null) {
            return Long.MAX_VALUE;
        }
        Long stamp = readingStamps.get(signal);
        if (stamp == null) {
            return Long.MAX_VALUE;
        }
        long age = android.os.SystemClock.uptimeMillis() - stamp;
        return age < 0L ? 0L : age;
    }

    /** 來源報到：連上了什麼、連不上什麼。黑匣子裏一行，診斷報告也讀它。 */
    void sourceReported(String source, String status) {
        AppLog.i(TAG, source + ": " + status);
        AppLog.note("行駛信息來源 " + source + ": " + status);
        // 頁面上的狀態行跟着換
        notifyListeners(readings);
    }

    /** 診斷報告 / 頁面用的一段。 */
    public String describe() {
        EcarxSource c;
        LocationSource l;
        synchronized (lock) {
            c = car;
            l = location;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(running ? "running" : "stopped");
        sb.append(", known ").append(readings.knownCount()).append('/').append(Signal.values().length);
        if (c != null) {
            sb.append("; ecarx: ").append(c.status());
        }
        if (l != null) {
            sb.append("; location: ").append(l.status());
        }
        // 車速牌為什麼會畫 -- ：看這一行就知是「車機斷了」還是「兩邊都沒有」
        sb.append("; carSpeedSeen=").append(carSpeedSeen)
                .append(" carSpeedFresh=").append(carSpeedFresh())
                .append(" gpsCanFillIn=").append(gpsSpeedShouldFillIn(carSpeedFresh()));
        return sb.toString();
    }
}
