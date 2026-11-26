# 已知問題及限制 — V1.6

## 資料

| 項目 | 說明 |
|---|---|
| M-D 名單 | 12 月 2 日前只有暫定名單（M-C ＋ 官方已公布的 Heatran、Dondozo、Tatsugiri 及其 Mega），標示「待驗證」。Showdown 更新後由每日自動檢查取得正式名單。 |
| M-D 新 Pokémon 招式表 | Showdown 未有 Champions 招式表前，Heatran、Dondozo、Tatsugiri 暫用主系列（SV）招式表，並清楚標示；正式資料到位後會被取代。 |
| M-D Mega 能力值 | Mega Heatran、Mega Tatsugiri 的特性按官方公布；種族值來自 Showdown 資料，標示「待驗證」，直至正式資料到位。 |
| 12 月 2 日平衡調整 | 只收錄官方已公布的項目，顯示為「即將生效」。 |
| 資料來源 | 主要來源為社群維護的 Pokémon Showdown champions mod；官方未提供公開資料。 |
| 中文名稱 | 部分形態、招式及道具未有官方繁體中文名稱時顯示英文。 |

## 隊伍碼

遊戲的隊伍碼（隊伍 ID）由伺服器解讀，碼本身不包含隊伍資料，App 無法用隊伍碼讀取隊伍。請用 Showdown／PokePaste 文字匯入（Game8、dexdb 等網站提供）。

## 傷害計算

- 公式通過 Smogon Damage Calculator 的第 9 世代測試案例；Champions 與主系列的差異（Stat Points、Lv50、狀態規則）已按 Phase 0 研究處理。
- 未支援的特性、道具或招式效果會標示「估算」或「未支援」，例如部分罕見特性互動、Z／極巨化相關（Champions 沒有）。

## 對戰模擬

- AI 為簡單規則（選擇預計傷害最高或有用的招式），勝率只作參考。
- 未模擬的招式效果會列出；部分複雜機制（例如 Illusion、Transform、Perish Song 計數、部分場地互動）未支援。
- 雙打的目標選擇、分散傷害已模擬；同時換人的細節及部分「先手／後手」互動為簡化處理。

## 線上更新

- 更新來源為本 GitHub repository 的 `data/` 資料夾（raw.githubusercontent.com）。如該網站在你的網絡無法連接，可在 Releases 下載資料檔，用「選擇資料檔並匯入」。
- 自動更新只在開啟 App 時檢查，App 不會在背景執行。
- 資料格式升級（schemaVersion）時，需先更新 App 才能套用新資料，App 會提示。

## 測試

- UI 測試在 GitHub Actions 的 Android 模擬器（API 34）上執行；實機只作人工測試。
- 效能測試在 CI 的電腦上量度，手機會較慢，所以上限設得較寬。
