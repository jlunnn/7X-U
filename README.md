# 7X=U — 14 段媒體 EQ（Android 車機）

14 段圖形等化器（50 Hz – 12 kHz、每段 ±10 dB），**直接改車機播放嘅媒體音**（掛喺全域 audio session，
即藍牙／內建播放器／任何 app 出嘅聲都會經呢個 EQ），唔需要 app 自己播歌。

介面係**全屏懸浮窗**（車機 overlay），可以收成一個圓形捷徑，長期停留喺車機畫面。

## 功能

- **14 段 EQ**：`50 / 63 / 160 / 250 / 400 / 630 / 1k / 1.6k / 2.5k / 4k / 6.3k / 8k / 10k / 12k Hz`，
  每段 **−10 ~ +10 dB**；柱上／柱下顯示每段 dB，並有霓虹綠**級聯響應曲線**。
- **BASS BOOST**：`0–12 dB`，**每格 1 dB**；80 Hz 以下平滑低架（80 Hz 全量、160 Hz 約 1/5、250 Hz 更少）。
- **DB GAIN**：前級 `±12 dB`（整體 headroom 控制）。
- **LIMITER**（預設關）：開啟後若峰值超過 0 dBFS，會自動扣前級避免削波（屬 headroom 保護，非動態壓縮）。
- **Preset**：`Flat / Vocal / Rock / Pop / V-Shape / Cabin`；可 `💾 儲存` 自訂曲線，
  已存曲線以 chip 顯示 —— **短撳套用**、**長撳確認刪除**。
- **測試音**（收於 `more`）：`SINE / PINK / WHITE / SWEEP / 1K`。
- **懸浮窗**：全屏、保留車機 info bar 安全區；`✕` 收成圓掣（螢光紅框、每 10 秒閃一下）。
- **圓掣**：記住拖動後位置；大小可調 `40–192 dp`（預設跟屏幕短邊 7.5%）。
- **15 秒無操作自動收埋**（設定頁可關）。
- **著車／解鎖自動開啟**：背景起服務、套用上次曲線，只顯示圓掣。
- **完全離線**：冇 `INTERNET` 權限、冇帳號、冇上傳、冇分析；設定只存本機 `SharedPreferences`。

## 版面（懸浮窗）

```
✕（紅框閃）              Equalizer   ·   EQ ON   ·   ⚙ SETTINGS
  [ 14 條 EQ 柱 ＋ 霓虹綠曲線 ]
  BASS BOOST [====●====] 6 dB  │ more ▾ │  DB GAIN [··●··] +0.0 dB
  LIMITER  [ON／OFF]
  Flat Vocal Rock Pop V-Shape Cabin  💾 儲存  (自訂 chip)
  （more ▾ 展開：SINE / PINK / WHITE / SWEEP / 1K）
```

## 技術

- `SysEq`：掛全域 audio effect —— 優先 `DynamicsProcessing`（postEq，內部多段解析度），
  唔得就退 `Equalizer(session 0)`；每段目標響應由 `Dsp` 計出再寫入。
- `Dsp`：peaking biquad 級聯響應計算（RBJ cookbook）＋逐 block 平滑＋tanh 軟限幅。
- `FloatEqService`：懸浮窗 UI ＋ EQ 引擎狀態 ＋ 測試音（`AudioTrack`）＋ 圓掣。
- `Watchdog`：`AlarmManager` 每 60 秒檢查，服務唔在生就自動起返（唔受 Doze 影響）。
- `EqService`：前台服務揸住 effect，app 退背景都保持生效。

## 已知限制

- **唔可以逐喇叭（每隻喇叭獨立）EQ**：車機分區／DSP 屬 `signature|privileged` 權限
  （例如 `android.car.permission.CAR_ZEEKR_CONTROL_AUDIO_EFFECT`），第三方側載 app 取唔到。
- **唔可以改車機媒體音量**：部分車機封鎖 `setStreamVolume`／`adjustStreamVolume`；
  本版已移除此滑桿，請用車機本身音量掣。
- 「Limiter」為**前級 headroom 保護**，唔係動態壓縮器。
- 逐喇叭／DSP 掃描僅供診斷（預設唔會執行）。

## 安裝

- **arm64-v8a**、Android 9（API 28）以上；車機側載（App Lab）或 `adb install`。
- 首次使用需要授予「顯示在其他 app 上層」（`SYSTEM_ALERT_WINDOW`）權限。

## 建置

```bash
./gradlew :app:assembleDebug        # 需要 JDK 17 + Android SDK
```

離線 DSP 驗證（唔需要 Android）：

```bash
javac -encoding UTF-8 -d /tmp/dspcheck \
  app/src/main/java/com/sevenxu/eq/{Bands,Dsp,Gen,Presets}.java tools/DspCheck.java
java -cp /tmp/dspcheck DspCheck
```

## 私隱

- **冇 `INTERNET` 權限**、冇帳號、冇上傳、冇分析、冇廣告 SDK。
- 所有設定（曲線、preset、圓掣位置、開關）只存本機 `SharedPreferences`。

## 授權

All rights reserved（私人車機工具）。
