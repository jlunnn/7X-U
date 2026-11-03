

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
