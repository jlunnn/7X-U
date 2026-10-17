package com.sevenxu.eq;

import android.content.Context;
import android.content.pm.PackageManager;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

/**
 * 車機原生音效層（reflection）—— 對應 AppVenture EQ 嘅「車機整合」做法：
 * {@code Class.forName("android.car.Car")} → {@code createCar(ctx)} → {@code getCarManager("audio")}，
 * 然後按名搵方法自己 call（setSoundField／setBassMidTreble／setEQMode／setSoundEnhanceMode／setASCMode…）。
 *
 * <p>呢層係車機自家 DSP（同 Android 媒體 EQ 唔同層）→ 可以同 14 段媒體 EQ 並存互補。</p>
 *
 * <p>全部 call 都係「最少郁」原則：只用 0／0.0f／false 試，並讀返同名 getter 對比，
 * SecurityException 會原文報告（就係話你知要邊個 android.car.permission.*）。</p>
 */
public final class CarAudioLayer {

    /** 想知道授權狀態嘅權限。 */
    private static final String[] PERMS = {
            "android.car.permission.CAR_CONTROL_AUDIO_SETTINGS",
            "android.car.permission.CAR_CONTROL_AUDIO_VOLUME",
            "android.car.permission.CAR_AUDIO_ROUTING",
            "android.car.permission.CAR_VENDOR_EXTENSION",
            "android.car.permission.CAR_CONTROL_AUDIO_SETTINGS_CAN_CONTROL_VOLUME",
    };

    /** 想試叫嘅 setter（音場／車機 EQ／音效增強）。 */
    private static final String[] WANT = {
            "setSoundField", "setBassMidTreble", "setEQMode", "setEqMode",
            "setSoundEnhanceMode", "setASCMode", "setBalance", "setFader",
            "setBass", "setMid", "setTreble", "setSoundEffect", "setSoundEnhance", "setSoundMode",
    };

    private CarAudioLayer() {
    }

    /**
     * 逐喇叭／車機 DSP 掃描（由探針搬入）：
     * ① AudioManager 全部輸出裝置（車機可能每隻喇叭 = 一個 BUS 裝置）
     * ② CarPropertyManager 全部屬性（filter 音效／DSP／喇叭／聲道／zone）＋逐個試讀值
     * ③ CarAudioManager 全部 zone／group 方法
     */
    public static void scanSpeakers(Context ctx, StringBuilder log) {
        // ① 輸出裝置
        log.append("--- ① 輸出裝置（可能 = 逐隻喇叭 BUS）---\n");
        try {
            android.media.AudioManager am = (android.media.AudioManager)
                    ctx.getSystemService(Context.AUDIO_SERVICE);
            int n = 0;
            for (android.media.AudioDeviceInfo d : am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)) {
                log.append(String.format(java.util.Locale.US, "  id=%d type=%d %s addr=%s\n",
                        d.getId(), d.getType(), d.getProductName(), d.getAddress()));
                n++;
            }
            log.append("  合共 ").append(n).append(" 個輸出裝置\n");
        } catch (Throwable t) {
            log.append("  ⚠️ ").append(t).append('\n');
        }
        // ② CarPropertyManager
        log.append("--- ② CarPropertyManager 音效／DSP 屬性 ---\n");
        try {
            Class<?> carCls = Class.forName("android.car.Car");
            Object car = carCls.getMethod("createCar", Context.class).invoke(null, ctx);
            Object pm = carCls.getMethod("getCarManager", String.class).invoke(car, "property");
            log.append("  property manager = ").append(pm == null ? "null" : pm.getClass().getName()).append('\n');
            if (pm != null) {
                java.util.List<?> props = (java.util.List<?>) pm.getClass().getMethod("getPropertyList").invoke(pm);
                log.append("  全部屬性數: ").append(props.size()).append('\n');
                java.util.regex.Pattern pat = java.util.regex.Pattern.compile(
                        "(?i)audio|dsp|speaker|sound|volume|eq_|bass|treble|balance|fader|surround|amp|channel|zone");
                int hit = 0;
                for (Object cfg : props) {
                    String nm = String.valueOf(cfg.getClass().getMethod("getName").invoke(cfg));
                    if (!pat.matcher(nm).find()) continue;
                    Object id = cfg.getClass().getMethod("getPropertyId").invoke(cfg);
                    Object ty = cfg.getClass().getMethod("getPropertyType").invoke(cfg);
                    Object area = cfg.getClass().getMethod("getAreaType").invoke(cfg);
                    String val = "（未讀）";
                    try {
                        java.lang.reflect.Method get = null;
                        for (java.lang.reflect.Method m : pm.getClass().getMethods()) {
                            if (m.getName().equals("getProperty") && m.getParameterTypes().length >= 2
                                    && m.getParameterTypes()[0] == int.class) {
                                get = m;
                                break;
                            }
                        }
                        if (get != null) val = String.valueOf(get.invoke(pm, id));
                    } catch (Throwable t) {
                        val = "（讀唔到：" + t.getClass().getSimpleName() + "）";
                    }
                    log.append(String.format("  %s id=%s type=%s area=%s value=%s%n", nm, id, ty, area, val));
                    hit++;
                }
                log.append("  音效相關命中: ").append(hit).append('\n');
            }
        } catch (Throwable t) {
            log.append("  ⚠️ CarProperty： ").append(t).append('\n');
        }
        // ③ zone／group
        log.append("--- ③ CarAudioManager zone／group ---\n");
        StringBuilder dummy = new StringBuilder();
        Object audio = manager(ctx, dummy);
        if (audio == null) {
            log.append("  ").append(dummy.toString().trim()).append('\n');
        } else {
            for (java.lang.reflect.Method m : audio.getClass().getMethods()) {
                String nm = m.getName().toLowerCase(java.util.Locale.US);
                if (!(nm.contains("zone") || nm.contains("group"))) continue;
                String v;
                try {
                    v = m.getParameterTypes().length == 0 ? String.valueOf(m.invoke(audio)) : "(要參數)";
                } catch (Throwable t) {
                    v = "(" + t.getClass().getSimpleName() + ")";
                }
                log.append("  ").append(m.getName()).append(" → ").append(v).append('\n');
            }
        }
    }


    /**
     * 逐個輸出裝置（BUS）試「按裝置調音量」：
     * ① AudioManager.setDeviceVolume(AudioDeviceInfo, int index, int value, int flags)（反射）
     * ② AudioManager.setVolumeIndexForAttributes(AudioAttributes, int, int)（反射，API 31）
     * 記錄每個裝置係 OK / DENIED / 冇此方法，方便一次過判斷「逐喇叭音量」通唔通。
     */
    public static void tryDeviceVolume(Context ctx, StringBuilder log) {
        android.media.AudioManager am = (android.media.AudioManager)
                ctx.getSystemService(Context.AUDIO_SERVICE);
        android.media.AudioDeviceInfo[] outs =
                am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS);
        log.append("=== 試逐裝置音量（").append(outs.length).append(" 個輸出）===\n");

        java.lang.reflect.Method setDev = null;
        java.lang.reflect.Method getDev = null;
        for (java.lang.reflect.Method m : android.media.AudioManager.class.getMethods()) {
            if (m.getName().equals("setDeviceVolume")) setDev = m;
            if (m.getName().equals("getDeviceVolume")) getDev = m;
        }
        log.append("setDeviceVolume 存在: ").append(setDev != null)
                .append("（").append(setDev == null ? "—" : java.util.Arrays.toString(setDev.getParameterTypes()))
                .append("）\n");

        int ok = 0, denied = 0, noMethod = 0;
        for (android.media.AudioDeviceInfo d : outs) {
            String tag = String.format(java.util.Locale.US, "  id=%d type=%d %s addr=%s → ",
                    d.getId(), d.getType(), d.getProductName(), d.getAddress());
            if (setDev == null) {
                log.append(tag).append("冇 setDeviceVolume（Android < 11）\n");
                noMethod++;
                continue;
            }
            try {
                Object r = setDev.invoke(am, d, 0, 1, 0);   // 純反射（編譯期唔可以直接 call）
                log.append(tag).append("OK（可以逐裝置調音量）").append(r == null ? "" : " " + r).append("\n");
                ok++;
            } catch (java.lang.reflect.InvocationTargetException e) {
                Throwable t = e.getCause() == null ? e : e.getCause();
                String cls = t.getClass().getSimpleName();
                if (t instanceof SecurityException || cls.contains("Security")) {
                    log.append(tag).append("DENIED（權限不足：").append(cls).append("）\n");
                    denied++;
                } else {
                    log.append(tag).append("失敗：").append(cls).append(" ").append(t.getMessage()).append("\n");
                    denied++;
                }
            } catch (Throwable t) {
                log.append(tag).append("失敗：").append(t.getClass().getSimpleName())
                        .append(" ").append(t.getMessage()).append("\n");
                denied++;
            }
        }
        log.append("--- 總結：OK ").append(ok).append(" 個、DENIED／失敗 ").append(denied)
                .append(" 個、冇方法 ").append(noMethod).append(" ---\n");
        if (ok > 0) {
            log.append("👉 有裝置通 → 可以做「逐區音量」\n");
        } else {
            log.append("👉 全部唔通 → 第三方 app 改唔到車機逐喇叭音量\n");
        }
        log.append("（註：EQ 仍然只能全局 2 聲道；逐喇叭 EQ 要車機 DSP，第三方做唔到）\n");
    }

    /** 拎 CarAudioManager（唔得就 null，msg 寫入 log）。 */
    public static Object manager(Context ctx, StringBuilder log) {
        try {
            Class<?> carCls = Class.forName("android.car.Car");
            Object car = carCls.getMethod("createCar", Context.class).invoke(null, ctx);
            if (car == null) {
                log.append("❌ createCar() 回傳 null\n");
                return null;
            }
            Object audio = carCls.getMethod("getCarManager", String.class).invoke(car, "audio");
            if (audio == null) {
                log.append("❌ getCarManager(\"audio\") 回傳 null\n");
                return null;
            }
            log.append("✅ CarAudioManager = ").append(audio.getClass().getName()).append('\n');
            return audio;
        } catch (InvocationTargetException ite) {
            log.append("❌ ").append(ite.getCause()).append('\n');
            return null;
        } catch (Throwable t) {
            log.append("❌ ").append(t).append('\n');
            return null;
        }
    }

    /** 權限狀態清單。 */
    public static void perms(Context ctx, StringBuilder log) {
        for (String p : PERMS) {
            boolean g;
            try {
                g = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
            } catch (Throwable t) {
                g = false;
            }
            log.append(g ? "✅ " : "⛔ ").append(p.replace("android.car.permission.", "")).append('\n');
        }
    }

    /** 列出車機音效介面全部公開方法（名＋參數＋return），setter 先。 */
    public static void listMethods(Object audio, StringBuilder log) {
        if (audio == null) return;
        TreeSet<String> setters = new TreeSet<>();
        TreeSet<String> others = new TreeSet<>();
        for (Class<?> c = audio.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!Modifier.isPublic(m.getModifiers())) continue;
                StringBuilder sb = new StringBuilder("  ").append(m.getName()).append('(');
                Class<?>[] ps = m.getParameterTypes();
                for (int i = 0; i < ps.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(ps[i].getSimpleName());
                }
                sb.append(") → ").append(m.getReturnType().getSimpleName());
                if (m.getName().startsWith("set")) setters.add(sb.toString());
                else others.add(sb.toString());
            }
        }
        log.append("【setter ").append(setters.size()).append(" 個】\n");
        for (String s : setters) log.append(s).append('\n');
        log.append("【其他 ").append(others.size()).append(" 個】\n");
        for (String s : others) log.append(s).append('\n');
    }

    /** 一個可以叫嘅方法（UI 用）。 */
    public static final class Op {
        public final String name;
        public final int argc;
        public final boolean isFloat;
        public final Method m;

        Op(String name, int argc, boolean isFloat, Method m) {
            this.name = name;
            this.argc = argc;
            this.isFloat = isFloat;
            this.m = m;
        }

        public String label() {
            String a = isFloat ? "0.0–1.0" : "0–10";
            return name + (argc == 2 ? "(x" + a + ", y" + a + ")" : "(" + a + ")");
        }
    }

    /** 搵出「可以安全叫」嘅 setter（1–2 個 primitive 參數）。 */
    public static List<Op> ops(Object audio) {
        List<Op> out = new ArrayList<>();
        if (audio == null) return out;
        for (String want : WANT) {
            for (Class<?> c = audio.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (!m.getName().equals(want)) continue;
                    if (!Modifier.isPublic(m.getModifiers())) continue;
                    Class<?>[] ps = m.getParameterTypes();
                    if (ps.length < 1 || ps.length > 2) continue;
                    boolean ok = true, fl = false;
                    for (Class<?> p : ps) {
                        if (p == float.class) fl = true;
                        else if (p != int.class && p != short.class && p != byte.class) ok = false;
                    }
                    if (!ok) continue;
                    String key = m.getName() + Arrays.toString(ps);
                    boolean dup = false;
                    for (Op o : out) {
                        if (o.label().startsWith(m.getName() + "(")) dup = true;
                    }
                    if (!dup) out.add(new Op(m.getName(), ps.length, fl, m));
                }
            }
        }
        return out;
    }

    /** 叫一個方法，回一句結果（✅ 值／⛔ 權限／⚠️ 其他）。 */
    public static String call(Object audio, Op op, float v) {
        if (audio == null || op == null) return "未連到車機音效介面";
        Object[] args = new Object[op.argc];
        for (int i = 0; i < op.argc; i++) args[i] = op.isFloat ? v : Math.round(v);
        String getter = op.name.replaceFirst("^set", "get");
        String before = read(audio, getter);
        try {
            op.m.setAccessible(true);
            // 型別感知：車機 zeekr API 多數收 int（之前傳 Float → IllegalArgumentException）
            Class<?>[] pt = op.m.getParameterTypes();
            Object[] conv = new Object[pt.length];
            for (int i2 = 0; i2 < pt.length; i2++) {
                float fv = (args != null && i2 < args.length && args[i2] instanceof Number)
                        ? ((Number) args[i2]).floatValue() : 0f;
                Class<?> t = pt[i2];
                if (t == int.class || t == Integer.class) {
                    conv[i2] = (int) Math.round(fv);
                } else if (t == float.class || t == Float.class) {
                    conv[i2] = fv;
                } else if (t == double.class || t == Double.class) {
                    conv[i2] = (double) fv;
                } else if (t == long.class || t == Long.class) {
                    conv[i2] = (long) Math.round(fv);
                } else if (t == boolean.class || t == Boolean.class) {
                    conv[i2] = Math.abs(fv) > 0.5f;
                } else if (t == String.class) {
                    conv[i2] = String.valueOf((int) Math.round(fv));
                } else {
                    conv[i2] = null;
                }
            }
            Object r = op.m.invoke(audio, conv);
            String after = read(audio, getter);
            return "✅ " + op.name + Arrays.toString(args) + " → " + r
                    + (before == null ? "" : "　（" + getter + " " + before + "→" + after + "）");
        } catch (InvocationTargetException ite) {
            Throwable c = ite.getCause() == null ? ite : ite.getCause();
            if (c instanceof SecurityException) {
                return "⛔ 權限被拒：" + c.getMessage();
            }
            return "⚠️ " + c.getClass().getSimpleName() + "：" + c.getMessage();
        } catch (Throwable t) {
            return "⚠️ " + t.getClass().getSimpleName() + "：" + t.getMessage();
        }
    }

    private static String read(Object audio, String name) {
        try {
            for (Class<?> c = audio.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getName().equals(name) && m.getParameterTypes().length == 0) {
                        m.setAccessible(true);
                        return String.valueOf(m.invoke(audio));
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
