# 7X=U — 31 段媒體 EQ（Android 車機）

31 段圖形等化器（20 Hz – 20 kHz、每段 ±12 dB），**直接改車機播放嘅媒體音**（掛喺全域 audio session
＝藍牙／內建播放器／任何 app 出嘅聲都會經呢個 EQ），唔需要 app 自己播歌。

## 功能

- **31 段 EQ**：ISO 1/3 八度、每段 −12 ~ +12 dB；拖動有平滑、軟限幅（全段推滿都唔破音）。
- **EQ 總開關**：粗線框掣 —— 開＝動態黃色脈動＋光暈，關＝原有灰粗線。
- **設定頁**（⚙️）：開機自動打開_EQ_、開 app 自動設 EQ 為開、EQ 引擎切換（自訂多段／車機原生）。
- **測試音**：單頻、粉紅噪音、白噪音、掃頻、1K Tone（本機播放，用嚟快速試聽）。
- **12 條內建曲線**（低音+／人聲／搖滾／V 型／車廂補償…）＋自訂曲線儲存。
- **逐段自檢**：設定頁一按，逐段單獨 ±12 dB、再讀返每段實際生效值＋鄰段有冇跟住動，全屏逐行列出；
  用嚟驗證車機係唔係 31 段段段獨立可調（測完自動復原你嘅曲線）。
- 完全離線；設定只存本機。

## 技術

- `Dsp`：31 個串聯 peaking biquad（RBJ cookbook、transposed DF2T）＋逐 block 平滑＋tanh 軟限幅（純 Java，可獨立測試）。
- `SysEq`：掛全域 audio effect —— 優先 `DynamicsProcessing`（可自訂段數／cutoff），唔得就退 `Equalizer(session 0)`。
- `EqService`：前台服務揸住 effect，令 app 退到背景都保持生效。
- `EqView`：自繪 31 條推桿 ＋ 級聯響應曲線 ＋ 車機實際生效段位置。
- `Engine`：本機測試音播放（音檔／咪模式程式碼仍保留，未接 UI）。

## 私隱

- **冇 `INTERNET` 權限**、冇帳號、冇上傳、冇分析。
- 只讀本機音檔（可選權限）同錄音（可選權限）。
- 所有設定存 `SharedPreferences`。

## 安裝

- arm64-v8a、Android 9（API 28）以上；車機側載（App Lab）或直接 adb install。
- 要 EQ 到「其他 app 嘅聲」需要系統／root 特權；本 app 用嘅係標準 `AudioEffect`（全域名稱空間），
  車機 HAL 是否支援要實測 —— app 內會顯示實際段數同狀態。

## 建置

```bash
./gradlew :app:assembleDebug        # 需要 JDK 17 + Android SDK 36
```

離線 DSP 驗證（唔需要 Android）：

```bash
javac -encoding UTF-8 -d /tmp/dspcheck \
  app/src/main/java/com/sevenxu/eq/{Bands,Dsp,Gen,Presets}.java tools/DspCheck.java
java -cp /tmp/dspcheck DspCheck
```

圖示／設計頁生成：

```bash
python3 tools/make_eq_icon.py            # 5 個密度 launcher icon（PIL）
python3 tools/make_design_page.py /tmp/out   # 設計頁（icon ＋ UI 規格）
```

## 授權

All rights reserved（私人車機工具）。
