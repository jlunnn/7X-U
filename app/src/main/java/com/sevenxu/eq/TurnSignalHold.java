package com.sevenxu.eq;

/**
 * 從兩盞轉向燈「此刻亮不亮」推出「在打哪邊」。
 *
 * <p>轉向燈一閃一閃（7X 上周期約 730 ms），熄滅的半拍不等於不打了：某一側在最近
 * {@link #HOLD_MS} 內亮過，就算那一側在打；兩側都在打就是雙閃（車上雙閃那個功能號讀出來
 * 是 255，只能這樣推）。1.5 秒能跨過熄滅的半拍，鬆手後也不至於拖太久。</p>
 *
 * <p>兩盞燈都讀不到時結果是「不知道」，不是「沒打」。純函數，{@code TurnSignalHoldTest} 裏測。</p>
 */
final class TurnSignalHold {

    static final long HOLD_MS = 1500L;

    /** 一次判定：轉向燈（{@link VehicleState#TURN_NONE} 等）和雙閃；讀不到時兩個都是 null。 */
    static final class Result {
        final Integer turn;
        final Boolean hazard;

        Result(Integer turn, Boolean hazard) {
            this.turn = turn;
            this.hazard = hazard;
        }
    }

    private boolean everLeft;
    private boolean everRight;
    private long lastLeftOnMs;
    private long lastRightOnMs;

    /**
     * @param nowMs   單調時鐘
     * @param leftOn  左燈此刻亮着；null = 讀不到
     * @param rightOn 右燈此刻亮着；null = 讀不到
     */
    Result update(long nowMs, Boolean leftOn, Boolean rightOn) {
        if (leftOn == null && rightOn == null) {
            return new Result(null, null);
        }
        if (Boolean.TRUE.equals(leftOn)) {
            everLeft = true;
            lastLeftOnMs = nowMs;
        }
        if (Boolean.TRUE.equals(rightOn)) {
            everRight = true;
            lastRightOnMs = nowMs;
        }
        boolean left = everLeft && nowMs - lastLeftOnMs < HOLD_MS;
        boolean right = everRight && nowMs - lastRightOnMs < HOLD_MS;
        if (left && right) {
            return new Result(VehicleState.TURN_NONE, true);
        }
        if (left) {
            return new Result(VehicleState.TURN_LEFT, false);
        }
        if (right) {
            return new Result(VehicleState.TURN_RIGHT, false);
        }
        return new Result(VehicleState.TURN_NONE, false);
    }
}
