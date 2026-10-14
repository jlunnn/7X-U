# 私隱政策（Privacy Policy）— 7X=U

**7X=U 完全離線運作。**

## 我哋收集咩資料？

**冇。** 呢個 app：

- ❌ **冇 `INTERNET` 權限** —— 技術上做唔到連網、上傳或者查更新
- ❌ 冇帳號、冇登入、冇裝置 ID
- ❌ 冇分析（analytics）／追蹤 SDK、冇廣告
- ❌ 唔會上傳任何錄音、設定或診斷資料

## 儲存咩嘢

- 只有你嘅 EQ 設定（31 段數值、曲線、開關狀態）同 app 偏好，存喺**本機** `SharedPreferences`。
- 冇任何資料離開裝置。卸載 app 即全部刪除。

## 權限用途

| 權限 | 用途 | 唔授權會點 |
|---|---|---|
| `MODIFY_AUDIO_SETTINGS` | 掛上系統 audio effect（媒體音樂 EQ） | EQ 用唔到 |
| `FOREGROUND_SERVICE` | 前台服務揸住 effect，令 app 退到背景都保持生效 | 只喺 app 開住時生效 |
| `POST_NOTIFICATIONS` | 顯示 EQ 生效中嘅通知 | 冇通知（功能照用） |
| `RECEIVE_BOOT_COMPLETED` | 開機自動打開 EQ（有啲車機唔派廣播） | 要自己開一次 app |
| `RECORD_AUDIO` | 咪模式（實時處理，可選） | 咪模式用唔到 |
| `READ_MEDIA_AUDIO` / `READ_EXTERNAL_STORAGE` | 讓你揀本機音檔播放（可選） | 選檔用唔到 |

## 車輛資料

本 app **唔會**讀取或寫入任何車輛設定／行車資料（冇 CAN、冇車輛 API）。

## 聯絡

有私隱相關問題，請喺專案嘅 issue tracker 提出。
