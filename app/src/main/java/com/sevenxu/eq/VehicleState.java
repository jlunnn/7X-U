package com.sevenxu.eq;

/**
 * 車此刻的狀態：一份不可變的快照。
 *
 * <h3>為什麼每一項都可以是「不知道」</h3>
 *
 * <p>信號從哪來、來不來，由各個來源決定（{@link Telemetry}）：這台車機的容器裏車輛屬性
 * 多半讀不到（平台筆記 §「能拿到什麼信號」），定位要看有沒有給權限。畫信息條的人
 * 只看這份快照，不知道就畫成「沒數據」—— 而不是畫成「關」。「沒數據」和「關」
 * 在畫面上必須分得開，否則轉向燈亮着而我們讀不到，畫出來就是「沒打燈」。</p>
 *
 * <p>所以每個字段都是可空的包裝類型：{@code null} = 沒數據。</p>
 *
 * <p>純數據，不碰 Android，{@code VehicleStateTest} 裏測。</p>
 */
public final class VehicleState {

    public static final int TURN_NONE = 0;
    public static final int TURN_LEFT = 1;
    public static final int TURN_RIGHT = 2;

    /** 車門 / 座椅的位掩碼：位 0 左前、位 1 右前、位 2 左後、位 3 右後、位 4 後排中間（只有座椅有）。 */
    public static final int FRONT_LEFT = 1;
    public static final int FRONT_RIGHT = 1 << 1;
    public static final int REAR_LEFT = 1 << 2;
    public static final int REAR_RIGHT = 1 << 3;
    public static final int REAR_CENTER = 1 << 4;

    /** 每發佈一份就 +1，畫的人據此知道要不要重畫。 */
    public final long version;

    /** {@link #TURN_NONE} / {@link #TURN_LEFT} / {@link #TURN_RIGHT}。 */
    public final Integer turnSignal;
    public final Boolean hazard;
    /** 方向盤轉角（度），順時針為正。 */
    public final Float steeringDegrees;
    /** 檔位字母：P R N D S 等。 */
    public final String gear;
    /** 油門 / 剎車開度 0..1。 */
    public final Float throttle;
    public final Float brake;
    public final Float speedKmh;
    public final Boolean autoHold;
    public final Boolean adaptiveCruise;
    public final Boolean laneCentering;
    /** 原廠 360 畫面此刻顯示着（倒車時車機自己的環視；它開着時佔着相機）。 */
    public final Boolean stockSurroundShown;
    /** 駕駛員手在方向盤上（車上還沒找到讀數，先留着位置）。 */
    public final Boolean handsOnWheel;
    /** 六項安全輔助的開關：自動緊急制動、前碰預警、車道偏離預警、車道保持、盲區輔助、後碰預警。 */
    public final Boolean aeb;
    public final Boolean forwardCollisionWarning;
    public final Boolean laneDepartureWarning;
    public final Boolean laneKeepingAid;
    public final Boolean blindSpotAssist;
    public final Boolean rearCollisionWarning;
    /** 開着的門（位掩碼）。 */
    public final Integer doorsOpen;
    /** 沒系安全帶的座位（位掩碼）。 */
    public final Integer beltsUnbuckled;
    public final Boolean daytimeRunningLights;
    public final Boolean lowBeam;
    public final Boolean highBeam;
    public final Boolean fogLights;
    public final Float odometerKm;
    public final Double latitude;
    public final Double longitude;

    private VehicleState(Builder b) {
        this.version = b.version;
        this.turnSignal = b.turnSignal;
        this.hazard = b.hazard;
        this.steeringDegrees = b.steeringDegrees;
        this.gear = b.gear;
        this.throttle = b.throttle;
        this.brake = b.brake;
        this.speedKmh = b.speedKmh;
        this.autoHold = b.autoHold;
        this.adaptiveCruise = b.adaptiveCruise;
        this.laneCentering = b.laneCentering;
        this.stockSurroundShown = b.stockSurroundShown;
        this.handsOnWheel = b.handsOnWheel;
        this.aeb = b.aeb;
        this.forwardCollisionWarning = b.forwardCollisionWarning;
        this.laneDepartureWarning = b.laneDepartureWarning;
        this.laneKeepingAid = b.laneKeepingAid;
        this.blindSpotAssist = b.blindSpotAssist;
        this.rearCollisionWarning = b.rearCollisionWarning;
        this.doorsOpen = b.doorsOpen;
        this.beltsUnbuckled = b.beltsUnbuckled;
        this.daytimeRunningLights = b.daytimeRunningLights;
        this.lowBeam = b.lowBeam;
        this.highBeam = b.highBeam;
        this.fogLights = b.fogLights;
        this.odometerKm = b.odometerKm;
        this.latitude = b.latitude;
        this.longitude = b.longitude;
    }

    /** 什麼都不知道。 */
    public static VehicleState empty() {
        return new Builder().build();
    }

    /** 在這份之上改幾項，得到新的一份（版本號 +1）。 */
    public Builder edit() {
        return new Builder(this);
    }

    /** 有幾項是有數據的（黑匣子那一行用）。 */
    public int knownCount() {
        int n = 0;
        Object[] all = {turnSignal, hazard, steeringDegrees, gear, throttle, brake, speedKmh,
                autoHold, adaptiveCruise, laneCentering, stockSurroundShown, handsOnWheel,
                aeb, forwardCollisionWarning, laneDepartureWarning, laneKeepingAid, blindSpotAssist,
                rearCollisionWarning, doorsOpen, beltsUnbuckled,
                daytimeRunningLights, lowBeam, highBeam, fogLights, odometerKm, latitude, longitude};
        for (Object o : all) {
            if (o != null) {
                n++;
            }
        }
        return n;
    }

    public static final class Builder {
        private long version;
        private Integer turnSignal;
        private Boolean hazard;
        private Float steeringDegrees;
        private String gear;
        private Float throttle;
        private Float brake;
        private Float speedKmh;
        private Boolean autoHold;
        private Boolean adaptiveCruise;
        private Boolean laneCentering;
        private Boolean stockSurroundShown;
        private Boolean handsOnWheel;
        private Boolean aeb;
        private Boolean forwardCollisionWarning;
        private Boolean laneDepartureWarning;
        private Boolean laneKeepingAid;
        private Boolean blindSpotAssist;
        private Boolean rearCollisionWarning;
        private Integer doorsOpen;
        private Integer beltsUnbuckled;
        private Boolean daytimeRunningLights;
        private Boolean lowBeam;
        private Boolean highBeam;
        private Boolean fogLights;
        private Float odometerKm;
        private Double latitude;
        private Double longitude;

        public Builder() {
        }

        private Builder(VehicleState s) {
            version = s.version + 1;
            turnSignal = s.turnSignal;
            hazard = s.hazard;
            steeringDegrees = s.steeringDegrees;
            gear = s.gear;
            throttle = s.throttle;
            brake = s.brake;
            speedKmh = s.speedKmh;
            autoHold = s.autoHold;
            adaptiveCruise = s.adaptiveCruise;
            laneCentering = s.laneCentering;
            stockSurroundShown = s.stockSurroundShown;
            handsOnWheel = s.handsOnWheel;
            aeb = s.aeb;
            forwardCollisionWarning = s.forwardCollisionWarning;
            laneDepartureWarning = s.laneDepartureWarning;
            laneKeepingAid = s.laneKeepingAid;
            blindSpotAssist = s.blindSpotAssist;
            rearCollisionWarning = s.rearCollisionWarning;
            doorsOpen = s.doorsOpen;
            beltsUnbuckled = s.beltsUnbuckled;
            daytimeRunningLights = s.daytimeRunningLights;
            lowBeam = s.lowBeam;
            highBeam = s.highBeam;
            fogLights = s.fogLights;
            odometerKm = s.odometerKm;
            latitude = s.latitude;
            longitude = s.longitude;
        }

        public Builder turnSignal(Integer v) { turnSignal = v; return this; }
        public Builder hazard(Boolean v) { hazard = v; return this; }
        public Builder steeringDegrees(Float v) { steeringDegrees = v; return this; }
        public Builder gear(String v) { gear = v; return this; }
        public Builder throttle(Float v) { throttle = clamp01(v); return this; }
        public Builder brake(Float v) { brake = clamp01(v); return this; }
        public Builder speedKmh(Float v) { speedKmh = v; return this; }
        public Builder autoHold(Boolean v) { autoHold = v; return this; }
        public Builder adaptiveCruise(Boolean v) { adaptiveCruise = v; return this; }
        public Builder laneCentering(Boolean v) { laneCentering = v; return this; }
        public Builder stockSurroundShown(Boolean v) { stockSurroundShown = v; return this; }
        public Builder handsOnWheel(Boolean v) { handsOnWheel = v; return this; }
        public Builder aeb(Boolean v) { aeb = v; return this; }
        public Builder forwardCollisionWarning(Boolean v) { forwardCollisionWarning = v; return this; }
        public Builder laneDepartureWarning(Boolean v) { laneDepartureWarning = v; return this; }
        public Builder laneKeepingAid(Boolean v) { laneKeepingAid = v; return this; }
        public Builder blindSpotAssist(Boolean v) { blindSpotAssist = v; return this; }
        public Builder rearCollisionWarning(Boolean v) { rearCollisionWarning = v; return this; }
        public Builder doorsOpen(Integer v) { doorsOpen = v; return this; }
        public Builder beltsUnbuckled(Integer v) { beltsUnbuckled = v; return this; }
        public Builder daytimeRunningLights(Boolean v) { daytimeRunningLights = v; return this; }
        public Builder lowBeam(Boolean v) { lowBeam = v; return this; }
        public Builder highBeam(Boolean v) { highBeam = v; return this; }
        public Builder fogLights(Boolean v) { fogLights = v; return this; }
        public Builder odometerKm(Float v) { odometerKm = v; return this; }
        public Builder position(Double lat, Double lon) { latitude = lat; longitude = lon; return this; }

        /** 改一個門 / 一個座位的那一位，其餘位不動；之前不知道就從 0 起。 */
        public Builder door(int bit, boolean open) {
            int mask = doorsOpen == null ? 0 : doorsOpen;
            doorsOpen = open ? (mask | bit) : (mask & ~bit);
            return this;
        }

        public Builder belt(int bit, boolean unbuckled) {
            int mask = beltsUnbuckled == null ? 0 : beltsUnbuckled;
            beltsUnbuckled = unbuckled ? (mask | bit) : (mask & ~bit);
            return this;
        }

        public VehicleState build() {
            return new VehicleState(this);
        }

        private static Float clamp01(Float v) {
            if (v == null) {
                return null;
            }
            return Math.max(0f, Math.min(1f, v));
        }
    }
}
