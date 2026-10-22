package com.sevenxu.eq;

import com.sevenxu.eq.R;

/**
 * 車上能讀到的信號，一張表：怎麼讀（功能號 / 帶區域的功能號 / 傳感器事件 / 傳感器數值）、
 * 讀到的數怎麼解、在車上驗證到什麼程度。
 *
 * <p>信息條（{@link VehicleStateMapper}）和「系統信息」頁都從這張表取，加一個信號就是加一行。
 * 號碼和含義來自 zeekr-shortcut-lab 在 7X 上的實測：它的信號手冊 {@code docs/signals.md} 是唯一依據
 * （分工：Lab 把信號找準，這裏把找到的用準）。</p>
 *
 * <p>每條信號有一個可信程度（{@link Trust}）：實驗中觀察一致的、先用着的、沒驗證的。
 * 信息條默認只啓用信號都{@linkplain #usable() 能用}的欄目，其餘劃掉。</p>
 *
 * <p><b>全都是實驗結論</b>：號碼是在車外試出來的，一個操作常常讓好幾個號一起變（掛 R 一次動六七個），
 * 聯動車外無從得知。所以「確認」只是「實驗中觀察一致」，Lab 每次運行都在交叉驗證，對不上的在手冊裏降級，
 * 這裏跟着改。用的時候只拿一個號當它本來的意思：踩沒踩剎車看踏板不看剎車燈，倒沒倒車看檔位不看倒車燈。</p>
 *
 * <p>解碼是純函數（{@code SignalDecodeTest}）：佔位值 255 / 254 / 253 / -1 / -65535，浮點 255 / -65535 和
 * 絕對值小於 1e-6 的非零數，都算「沒數據」。</p>
 */
public enum Signal {

    // ---- 行駛
    GEAR(Group.DRIVE, Kind.SENSOR_EVENT, 0x00200200, 0, R.string.vi_gear, Trust.CONFIRMED, Format.GEAR),
    SPEED(Group.DRIVE, Kind.SENSOR_VALUE, 0x00100100, 0, R.string.vi_speed, Trust.CONFIRMED, Format.MPS),
    IGNITION(Group.DRIVE, Kind.FUNCTION, 0x20259000, 0, R.string.vi_ignition, Trust.CONFIRMED, Format.IGNITION),
    BRAKE_PEDAL(Group.DRIVE, Kind.FUNCTION, 0x20317A00, 0, R.string.vi_brake_pedal, Trust.CONFIRMED, Format.ON_OFF),
    /** 跟着變（停車踩下 11–15，開車最大見過 17.4），踩到底是多少沒測；先按 0–100 畫。用户定：保留。 */
    BRAKE_DEPTH(Group.DRIVE, Kind.SENSOR_VALUE, 0x00101300, 0, R.string.vi_brake_depth, Trust.PROVISIONAL, Format.PERCENT),
    /** 行駛中跟着變（開車最大見過 64，像 %），踩到底是多少沒測；先按 0–100 畫。用户定：保留。 */
    THROTTLE_DEPTH(Group.DRIVE, Kind.SENSOR_VALUE, 0x00101400, 0, R.string.vi_throttle_depth, Trust.PROVISIONAL, Format.PERCENT),
    /**
     * 開車時跟着變。倒車入庫一段讀到 -8.9 … 7.3，Lab 判斷很可能是弧度（度 = 讀數 × 57.3），但左右哪邊為負、
     * 滿舵讀數還沒測（Lab 0.14.0），比例先不改（{@link #STEERING_DEGREES_PER_UNIT}）。用户定：能用，開放。
     */
    STEERING(Group.DRIVE, Kind.SENSOR_VALUE, 0x00101000, 0, R.string.vi_steering, Trust.PROVISIONAL, Format.DEGREES),
    /** 自動駐車的功能開關（設置項），不是「正在駐車」——信息條不用它。 */
    AUTO_HOLD(Group.DRIVE, Kind.FUNCTION, 0x20060400, 0, R.string.vi_auto_hold, Trust.CONFIRMED, Format.ON_OFF),
    /**
     * 駕駛模式（舒適／運動／節能…）。用户 2026-10-11 用 HermesProbe 實測到：功能號
     * {@code 0x22010100}，回報值 = {@code 0x22010100 | 模式編號}（低字節才是編號）。
     * 解碼在 {@link DriveMode}（純函數）。
     */
    DRIVE_MODE(Group.DRIVE, Kind.FUNCTION, 0x22010100, 0, R.string.vi_drive_mode, Trust.PROVISIONAL, Format.RAW),
    /**
     * 動能回收等級（標準／強）。用户 2026-10-11 在 HermesProbe 實測：功能號
     * {@code 0x20020500}，低字節 2 = 標準、3 = 強。解碼在 {@link RegenLevel}。
     */
    REGEN(Group.DRIVE, Kind.FUNCTION, 0x20020500, 0, R.string.vi_regen, Trust.PROVISIONAL, Format.RAW),
    /** 自動駐車「正在駐車」（車輛保持時的燈光請求）：停下被接管時 1，起步回 0；中間踩放踏板不變（Lab 0.13.0）。 */
    AUTO_HOLD_ACTIVE(Group.DRIVE, Kind.FUNCTION, 0x20320600, 0, R.string.vi_auto_hold_active, Trust.CONFIRMED, Format.ON_OFF),

    // ---- 燈光
    INDICATOR(Group.LAMPS, Kind.FUNCTION, 0x2A091500, 0, R.string.vi_indicator, Trust.CONFIRMED, Format.INDICATOR),
    /** 轉向指示顯示：跟着燈閃（左 0 / 1、右 0 / 2、雙閃 0 / 3）。信息條用不閃的 {@link #INDICATOR}。 */
    INDICATOR_DISPLAY(Group.LAMPS, Kind.FUNCTION, 0x2A091400, 0, R.string.vi_indicator_display, Trust.CONFIRMED, Format.INDICATOR),
    TURN_LEFT(Group.LAMPS, Kind.FUNCTION, 0x21051100, 0, R.string.vi_turn_left, Trust.CONFIRMED, Format.ON_OFF),
    TURN_RIGHT(Group.LAMPS, Kind.FUNCTION, 0x21051200, 0, R.string.vi_turn_right, Trust.CONFIRMED, Format.ON_OFF),
    LOW_BEAM(Group.LAMPS, Kind.FUNCTION, 0x21050100, 0, R.string.vi_low_beam, Trust.CONFIRMED, Format.ON_OFF),
    HIGH_BEAM(Group.LAMPS, Kind.FUNCTION, 0x21050200, 0, R.string.vi_high_beam, Trust.CONFIRMED, Format.ON_OFF),
    /** 近光沒亮時才是 1：切到位置燈檔 0 → 1，關燈或近光一亮回 0；晚上前燈帶亮着時報的是前位置燈（Lab 0.13.0，用户確認）。 */
    DRL(Group.LAMPS, Kind.FUNCTION, 0x21050900, 0, R.string.vi_drl, Trust.CONFIRMED, Format.ON_OFF),
    /** 一直 0，車機報 notavailable：這台車多半沒裝前霧燈。 */
    FRONT_FOG(Group.LAMPS, Kind.FUNCTION, 0x21050400, 0, R.string.vi_front_fog, Trust.UNVERIFIED, Format.ON_OFF),
    /** 0 關 / 1 開；要先開近光（Lab 0.13.0）。 */
    REAR_FOG(Group.LAMPS, Kind.FUNCTION, 0x21050500, 0, R.string.vi_rear_fog, Trust.CONFIRMED, Format.ON_OFF),
    FRONT_POSITION_LAMP(Group.LAMPS, Kind.FUNCTION, 0x21050800, 0, R.string.vi_front_position, Trust.CONFIRMED, Format.ON_OFF),
    REAR_POSITION_LAMP(Group.LAMPS, Kind.FUNCTION, 0x21050C00, 0, R.string.vi_rear_position, Trust.CONFIRMED, Format.ON_OFF),
    LIGHT_SWITCH(Group.LAMPS, Kind.FUNCTION, 0x20040E00, 0, R.string.vi_light_switch, Trust.CONFIRMED, Format.LIGHT_SWITCH),
    REVERSE_LAMP(Group.LAMPS, Kind.FUNCTION, 0x21050E00, 0, R.string.vi_reverse_lamp, Trust.CONFIRMED, Format.ON_OFF),
    STOP_LAMP(Group.LAMPS, Kind.FUNCTION, 0x21050D00, 0, R.string.vi_stop_lamp, Trust.CONFIRMED, Format.ON_OFF),

    // ---- 車身。區域值按 Lab 實測：0x1 = 主駕門（右舵車上是右前），0x4 = 副駕門，0x10 = 左後，0x40 = 右後，
    // 0x10000000 = 前備箱（ROOF_TOP），0x20000000 = 後備箱（VehicleZone 裏沒名字，直接傳值）
    DOOR_DRIVER(Group.BODY, Kind.FUNCTION_ZONE, 0x21020100, 0x1, R.string.vi_door_driver, Trust.CONFIRMED, Format.DOOR),
    DOOR_PASSENGER(Group.BODY, Kind.FUNCTION_ZONE, 0x21020100, 0x4, R.string.vi_door_passenger, Trust.CONFIRMED, Format.DOOR),
    DOOR_REAR_LEFT(Group.BODY, Kind.FUNCTION_ZONE, 0x21020100, 0x10, R.string.vi_door_rear_left, Trust.CONFIRMED, Format.DOOR),
    DOOR_REAR_RIGHT(Group.BODY, Kind.FUNCTION_ZONE, 0x21020100, 0x40, R.string.vi_door_rear_right, Trust.CONFIRMED, Format.DOOR),
    DOOR_FRUNK(Group.BODY, Kind.FUNCTION_ZONE, 0x21020100, 0x10000000, R.string.vi_door_frunk, Trust.CONFIRMED, Format.DOOR),
    DOOR_TRUNK(Group.BODY, Kind.FUNCTION_ZONE, 0x21020100, 0x20000000, R.string.vi_door_trunk, Trust.CONFIRMED, Format.DOOR),
    CHARGE_PORT(Group.BODY, Kind.FUNCTION, 0x21020500, 0, R.string.vi_charge_port, Trust.CONFIRMED, Format.DOOR),
    SUNROOF_SHADE(Group.BODY, Kind.FUNCTION_ZONE, 0x20080100, 0x8, R.string.vi_sunroof_shade, Trust.CONFIRMED, Format.RAW),
    BELT_DRIVER(Group.BODY, Kind.SENSOR_EVENT, 0x00201200, 0, R.string.vi_belt_driver, Trust.CONFIRMED, Format.BELT),
    BELT_PASSENGER(Group.BODY, Kind.SENSOR_EVENT, 0x00201300, 0, R.string.vi_belt_passenger, Trust.UNVERIFIED, Format.BELT),
    BELT_REAR_LEFT(Group.BODY, Kind.SENSOR_EVENT, 0x00201800, 0, R.string.vi_belt_rear_left, Trust.UNVERIFIED, Format.BELT),
    BELT_REAR_CENTER(Group.BODY, Kind.SENSOR_EVENT, 0x00201A00, 0, R.string.vi_belt_rear_center, Trust.UNVERIFIED, Format.BELT),
    BELT_REAR_RIGHT(Group.BODY, Kind.SENSOR_EVENT, 0x00201900, 0, R.string.vi_belt_rear_right, Trust.UNVERIFIED, Format.BELT),
    SEAT_DRIVER(Group.BODY, Kind.SENSOR_EVENT, 0x00203300, 0, R.string.vi_seat_driver, Trust.CONFIRMED, Format.SEAT),
    SEAT_PASSENGER(Group.BODY, Kind.SENSOR_EVENT, 0x00203400, 0, R.string.vi_seat_passenger, Trust.CONFIRMED, Format.SEAT),
    /** 後視鏡倒車下翻：1 平常，4 正在下翻，2 翻下去了，3 正在回位（掛 R 1 → 4 → 2，出 R 2 → 3 → 1）。 */
    MIRROR_DIP_DRIVER(Group.BODY, Kind.FUNCTION_ZONE, 0x2031EF00, 0x1, R.string.vi_mirror_dip_driver, Trust.CONFIRMED, Format.MIRROR_DIP),
    MIRROR_DIP_PASSENGER(Group.BODY, Kind.FUNCTION_ZONE, 0x2031EF00, 0x4, R.string.vi_mirror_dip_passenger, Trust.CONFIRMED, Format.MIRROR_DIP),

    // ---- 原廠界面
    STOCK_360(Group.STOCK, Kind.FUNCTION, 0x2031FE00, 0, R.string.vi_stock_360, Trust.CONFIRMED, Format.SHOWN),
    /** 原廠畫面彈出：原廠 360 或側方小窗彈着時 1，都收起來回 0（Lab 0.13.0）。 */
    STOCK_POPUP(Group.STOCK, Kind.FUNCTION, 0x2031B200, 0, R.string.vi_stock_popup, Trust.CONFIRMED, Format.POPUP),
    PARK_ASSIST(Group.STOCK, Kind.FUNCTION, 0x23030100, 0, R.string.vi_park_assist, Trust.CONFIRMED, Format.ON_OFF),

    // ---- 環境 / 車輛
    ODOMETER(Group.VEHICLE, Kind.SENSOR_VALUE, 0x00100700, 0, R.string.vi_odometer, Trust.CONFIRMED, Format.KM),
    BATTERY(Group.VEHICLE, Kind.SENSOR_VALUE, 0x00404000, 0, R.string.vi_battery, Trust.CONFIRMED, Format.PERCENT_RAW),
    RANGE(Group.VEHICLE, Kind.SENSOR_VALUE, 0x00100800, 0, R.string.vi_range, Trust.CONFIRMED, Format.KM),
    TEMP_OUTSIDE(Group.VEHICLE, Kind.SENSOR_VALUE, 0x00100B00, 0, R.string.vi_temp_outside, Trust.CONFIRMED, Format.CELSIUS),
    TEMP_INSIDE(Group.VEHICLE, Kind.SENSOR_VALUE, 0x00100C00, 0, R.string.vi_temp_inside, Trust.CONFIRMED, Format.CELSIUS),
    BATTERY_TEMP(Group.VEHICLE, Kind.SENSOR_VALUE, 0x00102A00, 0, R.string.vi_battery_temp, Trust.CONFIRMED, Format.CELSIUS),
    DAY_NIGHT(Group.VEHICLE, Kind.SENSOR_EVENT, 0x00201000, 0, R.string.vi_day_night, Trust.CONFIRMED, Format.DAY_NIGHT),
    /**
     * 哨兵模式（SETTING_FUNC_VSTD_MODE_STS）：0 / 1，用户下車前手動開關過一次時跟着變（Lab 0.13.0）。
     * 只見過一次，是「開關」還是「哨兵在工作」還沒分開，Lab 標待定。
     */
    SENTRY_MODE(Group.VEHICLE, Kind.FUNCTION, 0x20240100, 0, R.string.vi_sentry_mode, Trust.UNVERIFIED, Format.ON_OFF),

    // ---- 安全輔助（讀的是開關）
    AEB(Group.ASSIST, Kind.FUNCTION, 0x20070E00, 0, R.string.vi_aeb, Trust.CONFIRMED, Format.ON_OFF),
    /** 前碰預警的靈敏度：0 關，低 / 中 / 高是 0x200E0201–03（原來讀的 0x200E0100 一直 255）。 */
    FCW(Group.ASSIST, Kind.FUNCTION, 0x200E0200, 0, R.string.vi_fcw, Trust.CONFIRMED, Format.LEVEL),
    LDW(Group.ASSIST, Kind.FUNCTION, 0x28084100, 0, R.string.vi_ldw, Trust.UNVERIFIED, Format.ON_OFF),
    LKA(Group.ASSIST, Kind.FUNCTION, 0x20070100, 0, R.string.vi_lka, Trust.CONFIRMED, Format.ON_OFF),
    BSD(Group.ASSIST, Kind.FUNCTION, 0x28081600, 0, R.string.vi_bsd, Trust.UNVERIFIED, Format.ON_OFF),
    RCW(Group.ASSIST, Kind.FUNCTION, 0x20071000, 0, R.string.vi_rcw, Trust.CONFIRMED, Format.ON_OFF),
    LANE_CHANGE_ASSIST(Group.ASSIST, Kind.FUNCTION, 0x20070300, 0, R.string.vi_lane_change_assist, Trust.CONFIRMED, Format.ON_OFF),
    AUTO_LANE_CHANGE(Group.ASSIST, Kind.FUNCTION, 0x28040100, 0, R.string.vi_auto_lane_change, Trust.CONFIRMED, Format.ON_OFF),
    DOOR_OPEN_WARNING(Group.ASSIST, Kind.FUNCTION, 0x20120100, 0, R.string.vi_door_open_warning, Trust.CONFIRMED, Format.ON_OFF),
    LCC(Group.ASSIST, Kind.FUNCTION, 0x28085B00, 0, R.string.vi_lcc, Trust.UNVERIFIED, Format.ON_OFF);

    /** 可信到什麼程度。 */
    public enum Trust {
        /** Lab 手冊「實驗中觀察一致的」一節裏有它，這裏用到的含義、取值、量程都沒掛着「待測 / 待定」 */
        CONFIRMED,
        /** 車上跟着變、能用，但量程 / 比例 / 某個場景還等 Lab 測；用户點名先開放的（方向盤、油門剎車、日行燈） */
        PROVISIONAL,
        /** 讀得到，含義沒對上，或者還沒專門測過 */
        UNVERIFIED
    }

    /** 怎麼讀。 */
    public enum Kind {
        /** {@code ICarFunction.getFunctionValue(id)} */
        FUNCTION,
        /** {@code ICarFunction.getFunctionValue(id, zone)} */
        FUNCTION_ZONE,
        /** {@code ISensor.getSensorEvent(type)}：枚舉，值 = 類型 + 序號 */
        SENSOR_EVENT,
        /** {@code ISensor.getSensorLatestValue(type)}：浮點 */
        SENSOR_VALUE
    }

    /** 頁面上的分組。 */
    public enum Group {
        DRIVE(R.string.vi_group_drive),
        LAMPS(R.string.vi_group_lamps),
        BODY(R.string.vi_group_body),
        STOCK(R.string.vi_group_stock),
        VEHICLE(R.string.vi_group_vehicle),
        ASSIST(R.string.vi_group_assist);

        public final int labelRes;

        Group(int labelRes) {
            this.labelRes = labelRes;
        }
    }

    /** 讀到的數是什麼意思；也決定頁面上怎麼寫。 */
    public enum Format {
        /** 0 關 1 開 → Boolean */
        ON_OFF,
        /** 0 關，其餘是檔位（枚舉）→ Boolean（不是 0 就算開） */
        LEVEL,
        /** 燈光開關的位置 → Integer（0 關、0x20040E01 位置燈、0x20040E03 自動） */
        LIGHT_SWITCH,
        /** 0 關着 1 開着 → Boolean（開着為 true）；開關過程中的 0x…01 算沒數據 */
        DOOR,
        /** 1 繫着 0 沒系 → Boolean（繫着為 true） */
        BELT,
        /** 低字節 1 沒人 2 有人 → Boolean（有人為 true） */
        SEAT,
        /** 1 顯示中 2 平時 → Boolean（顯示中為 true） */
        SHOWN,
        /** 0 收起 1 彈着 → Boolean（彈着為 true） */
        POPUP,
        /** 後視鏡下翻的四個狀態 → Integer（1 平常、4 正在下翻、2 翻下去了、3 正在回位） */
        MIRROR_DIP,
        /** 白天 / 夜晚的枚舉碼 → Integer（0x00201001 白天、0x00201002 夜晚） */
        DAY_NIGHT,
        /** 0 關 1 左 2 右 3 雙閃 → Integer */
        INDICATOR,
        /** 點火狀態的枚舉碼 → Integer（0x00200104 ACC、05 ON、07 DRIVING） */
        IGNITION,
        /** 檔位枚舉 → 字母 P / R / N / D */
        GEAR,
        /** 原樣的整數（量程待定） → Integer */
        RAW,
        /** 傳感器給的是 m/s（0.2778 = 1 km/h）→ 乘 3.6 記成 km/h（Float） */
        MPS,
        /** 浮點 → Float */
        KMH, KM, PERCENT, PERCENT_RAW, DEGREES, CELSIUS
    }

    /**
     * 方向盤轉角：讀數 → 方向盤轉過的度數（信息條上的數字和圖標的轉動都用它）。
     * 滿舵幾圈、滿舵時讀數多少還沒測（等 Lab），先按讀數就是度數（1:1）。
     */
    public static final float STEERING_DEGREES_PER_UNIT = 1f;

    public static final int LIGHT_SWITCH_POSITION = 0x20040E01;
    public static final int LIGHT_SWITCH_LOW_BEAM = 0x20040E02;
    public static final int LIGHT_SWITCH_AUTO = 0x20040E03;

    public static final int MIRROR_NORMAL = 1;
    public static final int MIRROR_DOWN = 2;
    public static final int MIRROR_RETURNING = 3;
    public static final int MIRROR_TILTING = 4;

    public static final int DAY = 0x00201001;
    public static final int NIGHT = 0x00201002;

    public static final int IGNITION_ACC = 0x00200104;
    public static final int IGNITION_ON = 0x00200105;
    public static final int IGNITION_DRIVING = 0x00200107;

    public final Group group;
    public final Kind kind;
    public final int id;
    /** 帶區域讀時的區域值；不帶區域的為 0。 */
    public final int zone;
    public final int labelRes;
    public final Trust trust;
    public final Format format;

    Signal(Group group, Kind kind, int id, int zone, int labelRes, Trust trust, Format format) {
        this.group = group;
        this.kind = kind;
        this.id = id;
        this.zone = zone;
        this.labelRes = labelRes;
        this.trust = trust;
        this.format = format;
    }

    /** 能不能直接用：確認了的，或者先用着的。 */
    public boolean usable() {
        return trust != Trust.UNVERIFIED;
    }

    public boolean isSensor() {
        return kind == Kind.SENSOR_EVENT || kind == Kind.SENSOR_VALUE;
    }

    /** 浮點傳感器：不訂閲，按節拍讀（變一點就推，訂了只是噪聲）。 */
    public boolean isFloat() {
        return kind == Kind.SENSOR_VALUE;
    }

    // ================================================================= 解碼（純函數）

    /**
     * 原始讀數 → 按 {@link #format} 歸一的值；沒數據或解不開返回 null。
     *
     * @param raw Integer（功能號、傳感器事件）或 Float（傳感器數值）
     */
    public Object decode(Object raw) {
        if (raw == null) {
            return null;
        }
        if (format == Format.DEGREES) {
            Float units = raw instanceof Number ? floatOrNull(((Number) raw).floatValue()) : null;
            return units == null ? null : units * STEERING_DEGREES_PER_UNIT;
        }
        if (format == Format.MPS) {
            Float mps = raw instanceof Number ? floatOrNull(((Number) raw).floatValue()) : null;
            return mps == null ? null : mps * 3.6f;
        }
        if (format == Format.KMH || format == Format.KM || format == Format.PERCENT
                || format == Format.PERCENT_RAW || format == Format.DEGREES || format == Format.CELSIUS) {
            return raw instanceof Number ? floatOrNull(((Number) raw).floatValue()) : null;
        }
        if (!(raw instanceof Number)) {
            return null;
        }
        Integer v = intOrNull(((Number) raw).intValue());
        if (v == null) {
            return null;
        }
        switch (format) {
            case ON_OFF:
            case DOOR:
                return onOff(v);
            case BELT:
                return onOff(v);
            case SEAT:
                switch (v & 0xFF) {
                    case 1: return Boolean.FALSE;
                    case 2: return Boolean.TRUE;
                    default: return null;
                }
            case SHOWN:
                // 1 原廠 360 畫面；2 沒顯示；0 推測是打轉向燈彈的側方小窗 —— 對「360 畫面在不在」來説 0 和 2 都是不在
                return v == 1 ? Boolean.TRUE : (v == 2 || v == 0 ? Boolean.FALSE : null);
            case POPUP:
                return onOff(v);
            case MIRROR_DIP:
                return v >= MIRROR_NORMAL && v <= MIRROR_TILTING ? v : null;
            case DAY_NIGHT:
                return v == DAY || v == NIGHT ? v : null;
            case INDICATOR:
                return v >= 0 && v <= 3 ? v : null;
            case LEVEL:
                return v != 0;
            case IGNITION:
            case LIGHT_SWITCH:
            case RAW:
                return v;
            case GEAR:
                return gearLetter(v);
            default:
                return null;
        }
    }

    /** ECARX 的整數佔位值：255 未知、254 無、253 錯誤，車上還見過 -1 和 -65535。 */
    static Integer intOrNull(int v) {
        if (v == 255 || v == 254 || v == 253 || v == -1 || v == -65535) {
            return null;
        }
        return v;
    }

    /** 浮點佔位值：255、-65535，以及「沒數據」時那個極小的非零數。0 是真的 0。 */
    static Float floatOrNull(float v) {
        if (Float.isNaN(v) || v == 255f || v == -65535f) {
            return null;
        }
        if (v != 0f && Math.abs(v) < 1e-6f) {
            return null;
        }
        return v;
    }

    /** 0 關 / 1 開；別的（2 默認、開關過程中的中間態）算不知道。 */
    static Boolean onOff(Integer v) {
        if (v == null) {
            return null;
        }
        if (v == 0) {
            return Boolean.FALSE;
        }
        if (v == 1) {
            return Boolean.TRUE;
        }
        return null;
    }

    /** 檔位傳感器事件：P = 0x00200230、R = 0x00200240、N = 0x00200210、D = 0x00200220（車上實測）。 */
    static String gearLetter(int event) {
        switch (event) {
            case 0x00200230: return "P";
            case 0x00200240: return "R";
            case 0x00200210: return "N";
            case 0x00200220: return "D";
            default: return null;
        }
    }
}
