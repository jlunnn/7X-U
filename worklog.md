

## 3.12 (194) — 2026-11-03 多粒 Zeekr button
- 需求：一粒 → 最多 2 粒 button（用戶揀「每粒獨立映射」）。
- BtBridge：粒1 沿用 bt_mac/bt_name（舊綁定零遷移自動變粒1）、粒2 bt_mac2/bt_name2；MAXB=2；
  macAt/nameAt/bindAt/unbindAt/boundMacs/slotOfMac/hasAnyBound/firstFreeSlot；
  手勢映射 per-粒 b<粒>_g<手勢>（粒1 fallback 舊 g<手勢> keys）；burst 去重窗 per-粒（兩粒唔互食）；
  ui_slot 記「設定對象」。start() 一條 null-filter session（3.11 教訓照守）＋slotOfMac 派發。
- BleScan：startRaw String → String[]，軟件比對任何一粒中就算；BALANCED/LOW_LATENCY 判斷改陣列長度。
- MainActivity 藍牙頁：卡1 兩粒狀態（粒1/粒2 名＋MAC）、「📡 加綁 button」（滿 2 粒 disabled）、
  解除揀粒（dialog）、監聽 guard hasAnyBound；卡2「設定對象：粒1/粒2」切換掣（●記憶）＋
  手勢列顯示當前對象綁定；pickAction 標題寫粒N；掃描綁定自動入 firstFreeSlot。
- FloatEqService:137 guard boundMac→hasAnyBound。
- versionCode 194 / versionName 3.12。實車 BLE 未驗（MuMu 測唔到 BLE）。
## 3.13 (195) — 2026-11-03
- 用戶要求：動作只需要單擊同雙擊 → 長按停用。
- BtBridge：GESTURE_NAMES 縮到 {單擊,雙擊}；gestureOf 01 長按（同未知 byte）回 -1；
  callback g<0 早退（state 顯示「長按（停用）」做診斷，唔爆 array、唔觸發動作）。
- 舊版已設長按動作嘅 prefs b?_g2 仍然存在但永遠讀唔到（停用）。
- MainActivity 藍牙頁手勢列自動跟 GESTURE_NAMES 變兩行；doc comment 更新。
- versionCode 195 / versionName 3.13。實車 BLE 未驗（MuMu 測唔到 BLE）。

## 3.14 (196) — 2026-11-03
- 藍牙動作清單只剩紅掣（ACTIONS/LABELS 縮到一項；文字「🔴 紅掣」，「後霧燈開關三次」移除）。
  舊 prefs 綁過第 2–11 個動作 → 自動變「未綁動作」，唔會爆 index。
- 綠 EQ 圓型懸浮 icon 上下 offset 限制取消：新增 clampIconY（限 0..screenH，實際經 inT/inB=0，
  即全屏上下都拖得）；拖放／pre-add／setMinimized 三路徑全用；X 與面板 clamp 照舊。
- MainActivity 手勢列顯示 LABELS[...] 已有 guard（981），pickAction 單項。
- 版號 196 / "3.14"。
- 收尾修正（MuMu regression 期間抓到）：FloatEqService.startForeground() 舊 call 用 3-arg
  startForeground(id, noti, 32)，32＝FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION，與 manifest
  申報 mediaPlayback 不符。Android 12（舊 MuMu）不驗證冇事；Android 14+ 驗證 → startForeground
  拋例外被 catch(Throwable) 吞 → 30 秒 FGS timeout → ForegroundServiceDidNotStartInTime crash
  loop（Watchdog 重啟再爆）。改 2-arg startForeground(id, noti) 跟 manifest 類型。
- MuMu 實測（install OK）：紅掣 overlay 拖放 (600,384)→(600,1184) 受控（clampY 底限 1184=
  1280-96 吻合）；crash 來源確認係 FGS type bug（非 3.14 改動）。EQ icon 拖動喺 MuMu 上
  未能重現（模擬器全屏 overlay 窗疑似食 touch；GPU 保護提前收工，實車為準）。

## 3.15 (197) — 2026-11-04
- zeekr button 問③ fix：BLE 常駐掃描 auto-rearm（「死掃唔自癒」＝問③根因：
  舊版 onScanFailed 只 log、無 BT state receiver、Watchdog 只救 service 唔救 scan）。
  - BleScan：onScanFailed／BT 未就緒／拎唔到 scanner／startScan 例外 → backoff 自動重掛
    （1s→3s→10s→30s 上限；掃返着 rearmAttempt 歸零）。
  - 5 分鐘心跳強制開新掃描 session（BT stack 靜默死掃描都會翻生）。
  - BT 開關 receiver（ACTION_STATE_CHANGED＋BLE_STATE_CHANGED）：BT 開返即刻重掛監聽，
    熄咗就收起心跳／重掛 callbacks。
  - BtBridge.stop → BleScan.stopRawFully：連 auto-rearm／心跳／BT receiver 一齊拆（唔會死灰復燃）。
  - 診斷計數：BtBridge.rawCount（綁定粒全部廣播，去重前）／swallowCount（800ms 滑動窗食掉）／
    lastRawAnyAt（最後收包時間）；BleScan.rawRearmCount／lastRearmAt（重掛次數＋時間）。
  - MainActivity 狀態卡：加「掃描健康」行＋「去重（食包）：食 N／收 N｜最後收包 X 秒前」＋
    重掛次數；onResume 起每秒自動更新（btUiTick，settingsOn 先做嘢；onDestroy 收工）—
    分辨「button 冇出聲」（收 0）vs「app 食包」（食多收多）vs「掃描死」（rawError／重掛次數升）。
- 綁定用一次過掃描照舊（startScan 內 stopScan 加 BleScan. 前綴，唔會撞新方法）。
- 版號 197 / "3.15"。實車 A/B 未做（要實車）：開 app → 熄返開車機藍牙 → 撳 button
  應 <10 秒內自動恢復；狀態卡見「重掛 N 次」。sha256 67c8ef2b…（2,737,843 bytes）；
  hesheeat.cc 三點已上載＋live hash 驗證；同日用戶明示「取消 GitHub 3.14、上載做 v3.4」→
  v3.14 Release＋tag 已刪、main 推至 7235678、tag v3.4＋Release 433834613（asset 7XU-3.15-arm64.apk 201）；releases/latest 回 v3.4 ✓。

## 3.16 (198) — 2026-11-05
- 11-05 實車情報：3.15 上掃描死照樣發生（auto-rearm 未救返）；用戶補關鍵證詞——
  「kill app 之後先可以連接／scan 到新藍牙裝置，kill 前搵唔到任何新 BT 裝置」
  ＝成個 process 嘅藍牙 binder session 死透，process 內重建 session（3.15 心跳）
  對住條死 binder 做嘢，所以心跳有做嘢、rawError 卻空、狀態卡「正常」實際全聾。
- ECARX stack「靜默殺掃描」：無 onScanFailed、無例外 → 3.15 全部翻生路徑都係
  錯誤訊號觸發，靜默死一個都捉唔到。根因定案。
- 用戶否決 kill-app 自癒（EQ effect 掛喺本 process，kill＝EQ 平幾秒；雖然會自動掛返，
  用戶揀 BLE 層自癒）。3.16＝BLE 自癒梯（唔 kill app、EQ 全程唔郁）：
  - BleScan：rawCb 未過濾前記 lastAnyAdvertAt／anyAdvertCount（stack 活性唯一 observable）。
  - 心跳改探針制：每 5 分鐘停掃 → 3 秒全頻 LOW_LATENCY 探測 → 有包＝stack 生 →
    重建全新 session（L1/L2，零副作用）；0 包入 probeZeroStreak。
  - 開場體檢：新 session 後 60 秒零廣播 → 早探針（捉著車自啟時 BT 未就緒嘅靜默死）。
  - 死透升級（L3）：連續 3 輪探針 0 包 → 軟件重啟藍牙 ad.disable() → 3 秒/8 秒後備
    enable → STATE_ON receiver 自動重掛＋12 秒後備重掛。＝人手「熄返開藍牙」自動化。
  - 保護：藍牙音樂播緊（isBluetoothA2dpOn＋isMusicActive）→ 跳過重啟落輪先算；
    disable 被拒（SecurityException）→ 狀態卡明示「請人手熄返開藍牙」。
  - 狀態卡加「探針：…」行（探針結果＋已重啟藍牙 N 次）。
- 版號 198 / "3.16"。實車 A/B 未做：升 3.16 → 出街等掃描死（或熄返開藍牙製造）→
  狀態卡應見「探針 0 包（連續 N 輪）→ 自動重啟藍牙」→ BT 返嚟後撳紅掣應復生，
  EQ／浮窗全程唔斷。sha256 ea8c21c4…（2,742,515 bytes，零 INTERNET ✓）。
  hesheeat.cc 三點已上載＋live hash 驗證；zeekr/index.html 主行 3.16（19 個版本）。

## 3.17 (199) — 2026-11-05
- 用戶要求：取消 7X=U 嘅藍牙功能（Zeekr button BLE 手勢）。3.15／3.16 兩輪自癒都救唔返實車掃描靜默死，索性整條鏈拆走。
- 刪 `BleScan.java`（530 行）＋ `BtBridge.java`（438 行）。
- `MainActivity`：藍牙頁 `buildBtPage()`、`showBt()`、掃描／綁定／手勢映射／狀態卡／`btUiTick` 全拆；標題列「🔵 藍牙」入口移除；`onRequestPermissionsResult` code 77 只留車速牌位置權限提示。
- `FloatEqService`：`attachService`／自動常駐／`detachService`／`bridgeRecallSlot`／`bridgeMinimize` 移除。
- `RedButtonOverlay`：BLE static 紅掣路徑（`fireRedButton` / `sReadFog` / `sWriteFog` / `sIcarFunction`）移除；浮窗紅掣本體不變。
- `AndroidManifest`：BLUETOOTH / BLUETOOTH_ADMIN / BLUETOOTH_SCAN / BLUETOOTH_CONNECT 四個權限移除（app 唔再有任何藍牙存取）。
- 版號 199 / "3.17"。`compileDebugJavaWithJavac` BUILD SUCCESSFUL。
