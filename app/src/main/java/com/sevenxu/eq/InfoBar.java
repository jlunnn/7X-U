package com.sevenxu.eq;

import android.content.Context;

// AppConfig 唔需要（7X=U 用 SharedPreferences）

/**
 * 錄像下方的行駛信息條：開不開、顯示哪幾項。
 *
 * <h3>一條規則</h3>
 *
 * <p>開着信息條的錄像，畫面比視頻高 {@link #HEIGHT} 像素，最下面那一條是信息條
 * （{@code EncodeSize.withInfoBar}）。凡是按「四宮格」推算畫面幾何的地方
 * （回看的放大、魚眼校正）都先把這一條減掉（{@code PlaybackViewport.infoBarInset}）。
 * 信息條本身畫什麼，看 {@link InfoBarRenderer}；數據從哪來，看 {@link Telemetry}。</p>
 *
 * <h3>顯示哪幾項</h3>
 *
 * <p>試驗項目（1.68.0），對所有人開放，默認關。格子永遠全放；開了之後默認只有<b>能用</b>的格
 * 啓用（{@link InfoBarLayout.Cell#usable()}：這一格用到的信號都是確認了的或先用着的），其餘的畫斜槓劃掉；
 * 不能用的信號在映射前就濾掉（{@link Readings#usableOnly()}），不會把猜的東西畫進錄像。
 * 開發者模式裏的「激活所有欄目信息」啓用全部格、用全部讀數 —— 用來驗證各欄圖標真不真、能不能用。</p>
 *
 * <p>開着信息條時錄製走 MediaCodec 路徑（要用 GL 拼畫面，和四宮格同一條規則，
 * 見 {@code AppConfig.shouldUseCodecRecording}）。</p>
 */
public final class InfoBar {

    /** 信息條的高度（像素），和視頻寬度無關。 */
    public static final int HEIGHT = 100;

    private InfoBar() {
    }

    /** 這次錄製啓用哪些格：只啓用能用的，還是全部。 */
    public static final class Options {
        public final boolean all;

        public Options(boolean all) {
            this.all = all;
        }

        public static Options all() {
            return new Options(true);
        }

        public static Options usable() {
            return new Options(false);
        }
    }

    /** 這次錄製要不要信息條；不要返回 null。 */
    public static Options forRecording(Context context) {
        AppConfig config = new AppConfig(context);
        if (!config.isInfoBarEnabled()) {
            return null;
        }
        return new Options(config.isInfoBarAllActive());
    }
}
