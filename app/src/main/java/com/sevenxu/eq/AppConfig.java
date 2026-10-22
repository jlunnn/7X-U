package com.sevenxu.eq;

import android.content.Context;

/** 7X=U 版 AppConfig stub：只保留 camera app telemetry 需要嘅查詢。 */
public class AppConfig {
    private final Context ctx;

    public AppConfig(Context c) {
        this.ctx = c.getApplicationContext();
    }

    /** 7X info bar 開關（跟設定頁 ON／OFF）。 */
    public boolean isInfoBarEnabled() {
        return ctx.getSharedPreferences("zeekreq", Context.MODE_PRIVATE)
                .getBoolean("info_bar", true);
    }

    /** 信息條是否顯示全部欄目（7X=U 固定 false：只顯示車速／時間）。 */
    public boolean isInfoBarAllActive() {
        return false;
    }
}
