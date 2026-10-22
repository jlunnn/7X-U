package com.sevenxu.eq;

import java.util.Locale;

/**
 * 駕駛模式（用户 2026-10-11 在 HermesProbe 上實測到的功能號 {@code 0x22010100}）。
 *
 * <p>車機回報的原值 = {@code 0x22010100 | 模式編號}：{@code 0x22010101} = 模式 1、
 * {@code 0x22010102} = 模式 2、{@code 0x22010103} = 模式 3 —— <b>低字節才是模式編號</b>。</p>
 *
 * <p>純邏輯（跟 {@link RoundSign}/{@link TempCard} 同一套規矩）：不碰 View、不碰 Paint，
 * 標籤與「模式 N」那句都由外面（strings.xml，中英馬三份）傳進來，所以能在 JVM 上釘住。</p>
 */
public final class DriveMode {

    private DriveMode() {
    }

    /** Prode 實測的功能號。 */
    public static final int FUNCTION_ID = 0x22010100;

    /** 沒有讀數／未學到標籤時跟 {@link RoundSign} 同一套。 */
    public static final String NO_READING = RoundSign.NO_READING;

    /**
     * 由原值取模式編號（1、2、3…）。
     *
     * @return 讀不到（null、0、低字節 0）回 −1
     */
    public static int indexOf(Integer raw) {
        if (raw == null || raw == 0) {
            return -1;
        }
        int idx = raw & 0xFF;
        return idx == 0 ? -1 : idx;
    }

    /**
     * 顯示文字。
     *
     * @param raw            車機原值
     * @param labels         按編號排好的標籤（{@code labels[0]} = 模式 1）；未知／null 的格留空
     * @param unknownPattern 「模式 %1$d」那句話（由 strings.xml 來）；null 就只用編號
     */
    public static String text(Integer raw, String[] labels, String unknownPattern) {
        int idx = indexOf(raw);
        if (idx < 0) {
            return NO_READING;
        }
        if (labels != null && idx <= labels.length) {
            String label = labels[idx - 1];
            if (label != null && !label.isEmpty()) {
                return label;
            }
        }
        if (unknownPattern == null || unknownPattern.isEmpty()) {
            return String.valueOf(idx);
        }
        try {
            return String.format(Locale.US, unknownPattern, idx);
        } catch (RuntimeException e) {
            return String.valueOf(idx);
        }
    }
}
