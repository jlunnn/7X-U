package com.sevenxu.eq;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 內建 EQ 曲線（preset）。純 Java，可單元測試。
 *
 * <p>單位一律 dB（-12 ~ +12）。曲線用公式砌出嚟（唔係硬寫 31 個數字），
 * 改動容易、亦方便解釋。</p>
 */
public final class Presets {

    public static final class P {
        public final String name;
        public final float[] gains;

        P(String name, float[] gains) {
            this.name = name;
            this.gains = gains;
        }
    }

    private Presets() {
    }

    public static float[] zeros() {
        return new float[Bands.N];
    }

    public static float[] flat() {
        return zeros();
    }

    /**
     * 低頻（f ≤ knee 全開，向上對數漸減）。
     */
    public static float[] lowShelf(double amount, double knee, double span) {
        float[] g = zeros();
        for (int i = 0; i < Bands.N; i++) {
            double f = Bands.FREQ[i];
            if (f <= knee) {
                g[i] = (float) amount;
            } else {
                double t = Math.log(f / knee) / Math.log(2) / span;
                g[i] = (float) (amount * Math.max(0, 1 - t));
            }
        }
        return g;
    }

    /** 高頻。 */
    public static float[] highShelf(double amount, double knee, double span) {
        float[] g = zeros();
        for (int i = 0; i < Bands.N; i++) {
            double f = Bands.FREQ[i];
            if (f >= knee) {
                g[i] = (float) amount;
            } else {
                double t = Math.log(knee / f) / Math.log(2) / span;
                g[i] = (float) (amount * Math.max(0, 1 - t));
            }
        }
        return g;
    }

    /** 鐘形（中間 f0 最大，向兩邊對數衰減）。 */
    public static float[] bell(double amount, double f0, double octaves) {
        float[] g = zeros();
        for (int i = 0; i < Bands.N; i++) {
            double d = Math.log(Bands.FREQ[i] / f0) / Math.log(2) / octaves;
            g[i] = (float) (amount * Math.exp(-d * d * 1.4));
        }
        return g;
    }

    /** 相加（多條曲線疊埋）。 */
    public static float[] add(float[]... parts) {
        float[] g = zeros();
        for (float[] p : parts) {
            for (int i = 0; i < Bands.N; i++) g[i] += p[i];
        }
        for (int i = 0; i < Bands.N; i++) {
            g[i] = Math.max(Bands.MIN_DB, Math.min(Bands.MAX_DB, g[i]));
        }
        return g;
    }

    /** 由 "0,0,3.1,…" 讀 31 段增益（缺值／壞值當 0 dB）。 */
    public static float[] parseCsv(String s) {
        float[] g = zeros();
        if (s == null || s.isEmpty()) return g;
        String[] parts = s.split(",");
        for (int i = 0; i < Bands.N && i < parts.length; i++) {
            try {
                g[i] = Math.max(Bands.MIN_DB, Math.min(Bands.MAX_DB,
                        Float.parseFloat(parts[i].trim())));
            } catch (Throwable ignored) {
            }
        }
        return g;
    }

    /** 反相（A/B 對照：聽下邊個方向啱）。 */
    public static float[] invert(float[] g) {
        float[] o = new float[Bands.N];
        for (int i = 0; i < Bands.N; i++) o[i] = -g[i];
        return o;
    }

    /** 內建 preset 清單（次序 = UI 上嘅次序）。 */
    public static List<P> builtin() {
        List<P> list = new ArrayList<>();
        list.add(new P("Flat", flat()));
        list.add(new P("Vocal", add(bell(-3.0, 315, 1.0), bell(3.5, 2500, 1.2))));
        list.add(new P("Rock", add(lowShelf(4.5, 120, 2.0), bell(-2.0, 500, 1.2), bell(3.0, 3200, 1.3))));
        list.add(new P("Pop", add(lowShelf(3.0, 90, 2.0), bell(-1.5, 400, 1.0), bell(2.5, 4000, 1.3))));
        list.add(new P("V-Shape", add(lowShelf(5.0, 120, 2.0), highShelf(5.0, 5000, 1.8), bell(-3.0, 1000, 1.6))));
        list.add(new P("Cabin", add(bell(-3.5, 250, 1.1), bell(2.5, 3150, 1.3), bell(-2.0, 10000, 1.0))));
        return list;
    }

    // ------------------------------------------------- 用戶自訂（A/B/C 三個位）

    public static final String[] SLOT = {"A", "B", "C"};

    /** 由 prefs 讀出所有自訂曲線（key = slot 字母）。 */
    public static Map<String, float[]> readUser(String blob) {
        Map<String, float[]> map = new LinkedHashMap<>();
        if (blob == null || blob.isEmpty()) return map;
        for (String line : blob.split(";")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String k = line.substring(0, eq).trim();
            float[] g = zeros();
            String[] parts = line.substring(eq + 1).split(",");
            for (int i = 0; i < Bands.N && i < parts.length; i++) {
                try {
                    g[i] = Math.max(Bands.MIN_DB, Math.min(Bands.MAX_DB, Float.parseFloat(parts[i].trim())));
                } catch (Throwable ignored) {
                }
            }
            map.put(k, g);
        }
        return map;
    }

    /** 序列化（畀 SharedPreferences 用；格式 `A=0.0,0.0,...;B=...`）。 */
    public static String writeUser(Map<String, float[]> map) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, float[]> e : map.entrySet()) {
            if (sb.length() > 0) sb.append(';');
            sb.append(e.getKey()).append('=');
            for (int i = 0; i < Bands.N; i++) {
                if (i > 0) sb.append(',');
                sb.append(Fmt.db1(e.getValue()[i]));
            }
        }
        return sb.toString();
    }

    /** 數字格式化（唔用 locale，避免用逗號做小數點）。 */
    public static final class Fmt {
        public static String db1(float v) {
            String s = String.format(java.util.Locale.US, "%.1f", v);
            if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
            return s;
        }

        public static String sign(float v) {
            String s = db1(v);
            return v > 0 ? "+" + s : s;
        }
    }
}
