package com.sevenxu.eq;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 信息條上放哪幾格、各放在哪：按視頻寬度定。
 *
 * <p>每一格有固定的寬（高都是 100）和一個優先級。寬度不夠時從優先級低的開始去掉
 * （裏程、經緯度先走，車速、轉向燈最後走），留下的按固定的順序從左排起。
 * 環視（2560 寬）放得下全部；座艙那種窄一點的流放得下多少放多少 ——
 * 同一條規則，不按流分別配。</p>
 *
 * <p>每一格還寫明它靠哪幾個信號（{@link Signal}）：信號都{@linkplain Signal#usable() 能用}的格才算能用。
 * 格子永遠全放；不能用的格默認不啓用，畫成沒數據（斜槓劃掉），開發者「激活所有欄目信息」後
 * 才啓用（{@link #live}）。</p>
 *
 * <p>純函數，{@code InfoBarLayoutTest} 裏測。</p>
 */
public final class InfoBarLayout {

    /** 沒有車上信號的格：能不能用直接寫死。 */
    public enum Verdict {
        /** 來源不是車輛接口（定位），車上看到過它在動 */
        YES,
        /** 還沒找到讀數來源，先畫着 */
        NO
    }

    /**
     * 一格：寬度（像素）、優先級（小的先保留）、靠哪幾個信號。聲明的順序就是從左到右的顯示順序。
     */
    public enum Cell {
        TURN_LEFT(70, 1, Signal.INDICATOR),
        HAZARD(80, 1, Signal.INDICATOR),
        TURN_RIGHT(70, 1, Signal.INDICATOR),
        /** 方向盤隨讀數轉；先用着，滿舵幾圈、最大讀數多少等 Lab（{@link Signal#STEERING_DEGREES_PER_UNIT}）。 */
        STEERING(130, 2, Signal.STEERING),
        /** 駕駛員手在不在方向盤上（還沒找到車上的讀數，先畫着）。 */
        HANDS(80, 2, Verdict.NO),
        GEAR(70, 3, Signal.GEAR),
        /** 剎車（上）和油門（下）兩根橫條，左邊帶深度數字。先按 0–100 畫，踩到底是多少等 Lab。 */
        PEDALS(170, 4, Signal.BRAKE_DEPTH, Signal.THROTTLE_DEPTH),
        SPEED(210, 0, Signal.SPEED),
        /** 自動駐車「正在駐車」：停下被接管時亮，起步滅（不是功能開關 0x20060400，那個開車全程都亮）。 */
        AUTO_HOLD(70, 8, Signal.AUTO_HOLD_ACTIVE),
        ACC(80, 8, Verdict.NO),
        LCC(80, 8, Signal.LCC),
        /** 原廠 360 畫面顯示中：它佔着相機，我們的錄像會斷，所以優先級高。 */
        STOCK_360(80, 3, Signal.STOCK_360),
        /** 俯視的車：四扇門 + 五個座位的安全帶（看四扇門和主駕安全帶；其餘安全帶沒驗證前濾掉）。 */
        CABIN(170, 5, Signal.DOOR_DRIVER, Signal.DOOR_PASSENGER, Signal.DOOR_REAR_LEFT,
                Signal.DOOR_REAR_RIGHT, Signal.BELT_DRIVER),
        /** 日行燈：以 7X 正臉為底的那一格，整條裏唯一帶光暈的圖標，所以寬一些。近光亮着時日行燈信號是 0，這一格就滅。 */
        DRL(130, 7, Signal.DRL),
        LOW_BEAM(80, 6, Signal.LOW_BEAM),
        HIGH_BEAM(80, 6, Signal.HIGH_BEAM),
        /** 霧燈：只看後霧燈（前霧燈這台車多半沒裝）。 */
        FOG(80, 7, Signal.REAR_FOG),
        /** 六項安全輔助的開關：AEB、前碰預警、車道偏離、車道保持、盲區、後碰預警。 */
        ASSIST(200, 6, Signal.AEB, Signal.FCW, Signal.LDW, Signal.LKA, Signal.BSD, Signal.RCW),
        ODOMETER(160, 9, Signal.ODOMETER),
        /** 經緯度來自系統定位，1.72.0 車上看到過。 */
        POSITION(170, 9, Verdict.YES);

        public final int width;
        public final int priority;
        private final Signal[] signals;
        private final Verdict verdict;

        Cell(int width, int priority, Signal... signals) {
            this.width = width;
            this.priority = priority;
            this.signals = signals;
            this.verdict = null;
        }

        Cell(int width, int priority, Verdict verdict) {
            this.width = width;
            this.priority = priority;
            this.signals = new Signal[0];
            this.verdict = verdict;
        }

        /** 這一格用到的信號都能用（沒有車上信號的格按寫死的結論）。 */
        public boolean usable() {
            if (verdict != null) {
                return verdict == Verdict.YES;
            }
            for (Signal s : signals) {
                if (!s.usable()) {
                    return false;
                }
            }
            return true;
        }
    }

    /** 左右留白。 */
    public static final int MARGIN = 16;
    /** 格與格之間。 */
    public static final int GAP = 12;

    /** 放好的一格：哪一格、左邊緣在哪。 */
    public static final class Placed {
        public final Cell cell;
        public final int x;

        Placed(Cell cell, int x) {
            this.cell = cell;
            this.x = x;
        }
    }

    private InfoBarLayout() {
    }

    /**
     * 這一格啓不啓用：能用的都啓用；不能用的只有開發者「激活所有欄目信息」之後才啓用。
     * 沒啓用的格照樣放在條上，畫成沒數據（斜槓劃掉）。
     */
    public static boolean live(Cell cell, InfoBar.Options options) {
        return options.all || cell.usable();
    }

    /**
     * @param width 信息條（= 視頻）的寬度
     * @return 放得下的格，按顯示順序，帶位置
     */
    public static List<Placed> fit(int width) {
        List<Cell> candidates = Arrays.asList(Cell.values());
        // 先按優先級挑（同級按顯示順序），再按顯示順序擺
        List<Cell> byPriority = new ArrayList<>(candidates);
        Collections.sort(byPriority, new Comparator<Cell>() {
            @Override
            public int compare(Cell a, Cell b) {
                if (a.priority != b.priority) {
                    return a.priority - b.priority;
                }
                return a.ordinal() - b.ordinal();
            }
        });
        int available = width - 2 * MARGIN;
        int used = 0;
        List<Cell> kept = new ArrayList<>();
        for (Cell cell : byPriority) {
            int need = cell.width + (kept.isEmpty() ? 0 : GAP);
            if (used + need > available) {
                continue;
            }
            used += need;
            kept.add(cell);
        }
        List<Placed> placed = new ArrayList<>();
        int x = MARGIN;
        for (Cell cell : candidates) {
            if (!kept.contains(cell)) {
                continue;
            }
            placed.add(new Placed(cell, x));
            x += cell.width + GAP;
        }
        return placed;
    }
}
