package com.sevenxu.eq;

/**
 * 讀數表（{@link Readings}）→ 信息條要的快照（{@link VehicleState}）。
 *
 * <p>規則都在這裏，純函數（{@code VehicleStateMapperTest}）：</p>
 * <ul>
 *   <li>轉向燈和雙閃優先用不閃的「轉向指示狀態」（0 關 1 左 2 右 3 雙閃）；讀不到時退回兩盞燈的
 *       閃爍保持判定（雙閃 = 兩盞同時在閃；專用的雙閃功能號一直讀 255，不用）。</li>
 *   <li>車門和安全帶按「主駕在哪一邊」落到左右：右舵車主駕門 / 主駕安全帶在右前。</li>
 *   <li>剎車 / 油門深度 0–100 → 0..1；霧燈 = 後霧燈（前霧燈這台車多半沒裝）。車速在 {@link Signal#decode} 裏已從 m/s 換成 km/h。</li>
 *   <li>自動駐車看「正在駐車」{@link Signal#AUTO_HOLD_ACTIVE}（停下被接管 1、起步回 0），
 *       不看 {@link Signal#AUTO_HOLD}：那是功能開關，開車全程都是 1。「正在駐車」只在車停着時成立：
 *       這個號原名是「車輛保持時的剎車燈請求」，別的保持也可能讓它變，車在走就不算（{@link #holding}）。</li>
 *   <li>非開發者拿到的讀數已經濾掉了沒驗證的信號（{@link Readings#usableOnly()}），這裏不再分辨。</li>
 * </ul>
 */
public final class VehicleStateMapper {

    private final TurnSignalHold hold = new TurnSignalHold();

    /**
     * @param driverOnRight 主駕在右（右舵）；決定主駕門 / 主駕安全帶畫在哪一邊
     */
    public void apply(VehicleState.Builder b, Readings r, long nowMs, boolean driverOnRight) {
        TurnSignalHold.Result blink = hold.update(nowMs, r.bool(Signal.TURN_LEFT), r.bool(Signal.TURN_RIGHT));
        Integer indicator = r.code(Signal.INDICATOR);
        if (indicator != null) {
            b.turnSignal(indicator == 1 ? VehicleState.TURN_LEFT : indicator == 2 ? VehicleState.TURN_RIGHT : VehicleState.TURN_NONE);
            b.hazard(indicator == 3);
        } else {
            b.turnSignal(blink.turn);
            b.hazard(blink.hazard);
        }

        b.steeringDegrees(r.number(Signal.STEERING));
        b.gear(r.text(Signal.GEAR));
        b.brake(percent(r.number(Signal.BRAKE_DEPTH)));
        b.throttle(percent(r.number(Signal.THROTTLE_DEPTH)));
        b.speedKmh(r.number(Signal.SPEED));
        b.autoHold(holding(r.bool(Signal.AUTO_HOLD_ACTIVE), r.number(Signal.SPEED)));
        b.laneCentering(r.bool(Signal.LCC));
        b.stockSurroundShown(r.bool(Signal.STOCK_360));
        b.lowBeam(r.bool(Signal.LOW_BEAM));
        b.highBeam(r.bool(Signal.HIGH_BEAM));
        b.fogLights(r.bool(Signal.REAR_FOG));
        b.daytimeRunningLights(r.bool(Signal.DRL));
        b.odometerKm(r.number(Signal.ODOMETER));
        b.aeb(r.bool(Signal.AEB));
        b.forwardCollisionWarning(r.bool(Signal.FCW));
        b.laneDepartureWarning(r.bool(Signal.LDW));
        b.laneKeepingAid(r.bool(Signal.LKA));
        b.blindSpotAssist(r.bool(Signal.BSD));
        b.rearCollisionWarning(r.bool(Signal.RCW));

        int driverBit = driverOnRight ? VehicleState.FRONT_RIGHT : VehicleState.FRONT_LEFT;
        int passengerBit = driverOnRight ? VehicleState.FRONT_LEFT : VehicleState.FRONT_RIGHT;
        b.doorsOpen(mask(
                new Boolean[]{r.bool(Signal.DOOR_DRIVER), r.bool(Signal.DOOR_PASSENGER),
                        r.bool(Signal.DOOR_REAR_LEFT), r.bool(Signal.DOOR_REAR_RIGHT)},
                new int[]{driverBit, passengerBit, VehicleState.REAR_LEFT, VehicleState.REAR_RIGHT}));
        // 安全帶表裏記的是「沒系」
        b.beltsUnbuckled(mask(
                new Boolean[]{not(r.bool(Signal.BELT_DRIVER)), not(r.bool(Signal.BELT_PASSENGER)),
                        not(r.bool(Signal.BELT_REAR_LEFT)), not(r.bool(Signal.BELT_REAR_CENTER)),
                        not(r.bool(Signal.BELT_REAR_RIGHT))},
                new int[]{driverBit, passengerBit, VehicleState.REAR_LEFT, VehicleState.REAR_CENTER,
                        VehicleState.REAR_RIGHT}));
    }

    /** 車停着的界限：0.1 m/s。 */
    static final float STANDSTILL_KMH = 0.36f;

    /** 正在駐車：車説在保持，而且車停着（車速讀不到時只看車説的）。 */
    static Boolean holding(Boolean active, Float kmh) {
        if (Boolean.TRUE.equals(active) && kmh != null && kmh >= STANDSTILL_KMH) {
            return Boolean.FALSE;
        }
        return active;
    }

    static Float percent(Float v) {
        return v == null ? null : v / 100f;
    }

    static Boolean not(Boolean v) {
        return v == null ? null : !v;
    }

    /** 各位置的開 / 沒系 → 位掩碼；一個都不知道時為 null。 */
    static Integer mask(Boolean[] flags, int[] bits) {
        int mask = 0;
        boolean any = false;
        for (int i = 0; i < flags.length; i++) {
            if (flags[i] == null) {
                continue;
            }
            any = true;
            if (flags[i]) {
                mask |= bits[i];
            }
        }
        return any ? mask : null;
    }
}
