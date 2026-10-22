package com.sevenxu.eq;

import java.util.Locale;

/**
 * 動能回收等級（用户 2026-10-11 在 HermesProbe 實測到）。
 *
 * <p>功能號 {@code 0x20020500}，回報值 = {@code 0x20020500 | 等級}：低字節
 * <b>2 = 標準、3 = 強</b>（probe 10.23 版本說明實測；setting log 亦見到
 * {@code 0x20020500 = 537003266}（＝ {@code 0x20020502}）↔ {@code …03} 兩個值在跳）。</p>
 *
 * <p>跟 {@link DriveMode} 同一套寫法：純邏輯，標籤由 {@code strings.xml} 傳進來
 * （中英馬三份），未見過的等級顯示編號 —— 不假裝知道它是什麼。</p>
 */
public final class RegenLevel {

    private RegenLevel() {
    }

    /** 實測的功能號。 */
    public static final int FUNCTION_ID = 0x20020500;

    /** 標準（低字節 2）。 */
    public static final int STANDARD = 2;
    /** 強（低字節 3）。 */
    public static final int STRONG = 3;

    /** 讀唔到時的顯示（跟 {@link RoundSign} 同一套）。 */
    public static final String NO_READING = RoundSign.NO_READING;

    /** 由原值取等級（低字節）。讀唔到（null／0／低字節 0）回 −1。 */
    public static int indexOf(Integer raw) {
        if (raw == null || raw == 0) {
            return -1;
        }
        int level = raw & 0xFF;
        return level == 0 ? -1 : level;
    }

    /**
     * 顯示文字。
     *
     * @param raw              車機原值
     * @param standardLabel    2 的說法（標準）
     * @param strongLabel      3 的說法（強）
     * @param unknownPattern   「等級 %1$d」那句話；null 就只顯示編號
     */
    public static String text(Integer raw, String standardLabel, String strongLabel,
                              String unknownPattern) {
        int level = indexOf(raw);
        if (level < 0) {
            return NO_READING;
        }
        if (level == STANDARD && standardLabel != null && !standardLabel.isEmpty()) {
            return standardLabel;
        }
        if (level == STRONG && strongLabel != null && !strongLabel.isEmpty()) {
            return strongLabel;
        }
        if (unknownPattern == null || unknownPattern.isEmpty()) {
            return String.valueOf(level);
        }
        try {
            return String.format(Locale.US, unknownPattern, level);
        } catch (RuntimeException e) {
            return String.valueOf(level);
        }
    }
}
