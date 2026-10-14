package com.sevenxu.eq;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 輸出通道（車機每條 BUS 都要用 AudioDeviceInfo.getAddress() 點名）。
 *
 * <p>已實測（車機）：車外廣播認 <b>BUS12_OUTER_NOTIFY</b>（配 USAGE_MEDIA）；
 * 車內要明選 BUS00_MEDIA（唔指定裝置＝容器內冇聲）。</p>
 *
 * <p>⚠️ UI 一律只出「車內／車外」，BUS 名只可以落 log／診斷報告。</p>
 */
public final class Routes {

    public static final int INNER = 0;
    public static final int OUTER = 1;

    private static final String BUS_OUTER = "BUS12_OUTER_NOTIFY";
    private static final String BUS_MEDIA = "BUS00_MEDIA";

    private final Context ctx;
    private final AudioManager am;

    public Routes(Context c) {
        this.ctx = c.getApplicationContext();
        this.am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
    }

    public List<AudioDeviceInfo> outputs() {
        List<AudioDeviceInfo> out = new ArrayList<>();
        if (am == null) return out;
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) out.add(d);
        return out;
    }

    private AudioDeviceInfo byAddr(String needle) {
        for (AudioDeviceInfo d : outputs()) {
            String a = d.getAddress();
            if (a != null && a.toUpperCase().contains(needle.toUpperCase())) return d;
        }
        return null;
    }

    /** 車外喇叭。 */
    public AudioDeviceInfo outer() {
        return byAddr(BUS_OUTER);
    }

    /** 車內（媒體 BUS → 本身喇叭）。 */
    public AudioDeviceInfo inner() {
        AudioDeviceInfo d = byAddr(BUS_MEDIA);
        if (d != null) return d;
        for (AudioDeviceInfo x : outputs()) {
            String a = x.getAddress() == null ? "" : x.getAddress().toUpperCase();
            if (a.startsWith("BUS00")) return x;
        }
        for (AudioDeviceInfo x : outputs()) {
            if (x.isSink() && x.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) return x;
        }
        return null;
    }

    public AudioDeviceInfo device(int route) {
        return route == OUTER ? outer() : inner();
    }

    /** 兩條路都用 USAGE_MEDIA（BUS12 實測配媒體 usage 出得到聲）。 */
    public int usage(int route) {
        return AudioAttributes.USAGE_MEDIA;
    }

    public int contentType(int route) {
        return AudioAttributes.CONTENT_TYPE_MUSIC;
    }

    /** UI 用嘅名（唔含 BUS 名）。 */
    public static String friendly(int route) {
        return route == OUTER ? "車外" : "車內";
    }

    /** 診斷／log 用嘅詳情（可以含 BUS 名，唔可以落畫面）。 */
    public String detail(int route) {
        AudioDeviceInfo d = device(route);
        if (d == null) return friendly(route) + "（搵唔到輸出）";
        return friendly(route) + " / addr=" + d.getAddress() + " type=" + d.getType()
                + " id=" + d.getId();
    }

    public AudioManager am() {
        return am;
    }

    public int maxVolume(int stream) {
        return am == null ? 15 : am.getStreamMaxVolume(stream);
    }

    public int volume(int stream) {
        return am == null ? 0 : am.getStreamVolume(stream);
    }

    public void setVolume(int stream, int v) {
        try {
            if (am != null) am.setStreamVolume(stream, Math.max(0, Math.min(v, maxVolume(stream))), 0);
        } catch (Throwable ignored) {
        }
    }

    /** 一條易讀嘅輸出清單（診斷用）。 */
    public String dumpOutputs() {
        StringBuilder sb = new StringBuilder();
        for (AudioDeviceInfo d : outputs()) {
            AppLog.d("EQ", "out addr=" + d.getAddress() + " name=" + d.getProductName()
                    + " type=" + d.getType() + " id=" + d.getId());
            if (sb.length() > 0) sb.append(" | ");
            sb.append(d.getAddress());
        }
        return sb.toString();
    }
}
