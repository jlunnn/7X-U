package com.sevenxu.eq;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * 全部信號此刻的（歸一後的）讀數：一份不可變快照。沒數據的信號不在表裏。
 */
public final class Readings {

    private static final Readings EMPTY = new Readings(new EnumMap<>(Signal.class), 0);

    private final Map<Signal, Object> values;
    /** 每發佈一份 +1。 */
    public final long version;

    Readings(Map<Signal, Object> values, long version) {
        this.values = Collections.unmodifiableMap(new EnumMap<>(values));
        this.version = version;
    }

    public static Readings empty() {
        return EMPTY;
    }

    /** 歸一後的值（Boolean / Integer / String / Float），沒數據為 null。 */
    public Object get(Signal signal) {
        return values.get(signal);
    }

    public Boolean bool(Signal signal) {
        Object v = values.get(signal);
        return v instanceof Boolean ? (Boolean) v : null;
    }

    public Float number(Signal signal) {
        Object v = values.get(signal);
        return v instanceof Float ? (Float) v : null;
    }

    public Integer code(Signal signal) {
        Object v = values.get(signal);
        return v instanceof Integer ? (Integer) v : null;
    }

    public String text(Signal signal) {
        Object v = values.get(signal);
        return v instanceof String ? (String) v : null;
    }

    /** 只留能用的信號（{@link Signal#usable()}；非開發者的信息條用它，猜的東西不畫進錄像）。版本號不變。 */
    public Readings usableOnly() {
        Map<Signal, Object> kept = new EnumMap<>(Signal.class);
        for (Map.Entry<Signal, Object> e : values.entrySet()) {
            if (e.getKey().usable()) {
                kept.put(e.getKey(), e.getValue());
            }
        }
        return new Readings(kept, version);
    }

    /** 有數據的信號個數。 */
    public int knownCount() {
        return values.size();
    }

    /** 日誌用：每個信號一個短記號。 */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (Signal s : Signal.values()) {
            Object v = values.get(s);
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(s.name().toLowerCase(java.util.Locale.US)).append('=');
            if (v == null) {
                sb.append('?');
            } else if (v instanceof Boolean) {
                sb.append((Boolean) v ? '1' : '0');
            } else if (v instanceof Float) {
                sb.append(String.format(java.util.Locale.US, "%.2f", (Float) v));
            } else if (v instanceof Integer) {
                sb.append(Integer.toHexString((Integer) v));
            } else {
                sb.append(v);
            }
        }
        return sb.toString();
    }
}
