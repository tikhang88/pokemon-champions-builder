# 測試報告 — V1.6（Phase 8）

測試日期：2026-11-26。執行環境：GitHub Actions（`Build APK`、`UI tests` workflows）。
測試版本：App V1.6.12，資料版本 2026.11.26-sdc7fa6f1。

## 結果摘要

| 類別 | 數量 | 結果 |
|---|---|---|
| Core unit tests（JVM） | 77 | ✅ 全部通過 |
| UI tests（Android 模擬器 API 34，Pixel 6） | 7 | ✅ 全部通過 |
| APK 簽署 | — | ✅ 使用 release key |
| 線上更新檔 | — | ✅ `data/version.json` 的 SHA-256 與網上的資料檔一致 |

Core tests 會在每次建置 APK 前執行；不通過就不會發佈 APK。

## 1. Core unit tests（77 項）

| 檔案 | 數量 | 範圍 |
|---|---|---|
| CoreTest | 18 | 能力值公式（SP、性格）、屬性相性、Regulation 狀態及日期、資料檢查、Change Log、匯入 |
| TeamTest | 11 | Team Builder、Mega Toggle、合法性（Species／Item Clause、名單、招式、特性、SP）、匯出 |
| TeamImportTest | 4 | Showdown／PokePaste 匯入（中英文名稱、EV→SP、Mega Stone）、隊伍碼格式 |
| TeamAnalysisTest | 8 | 防守矩陣（含特性）、攻擊覆蓋（-ate、特殊招式）、角色、配合、建議 |
| SpeedMatchupTest | 5 | Speed Tier 條件、百分位、Matchup 評級及報告 |
| DamageTest | 13 | Smogon Damage Calculator 第 9 世代案例 A–I（傷害範圍、KO 機率完全一致）；Champions Lv50 案例 J–M |
| BattleTest | 10 | 行動次序、優先度、戲法空間、Mega 一次、異常狀態規則、守住、天氣、回合結束、AI 對戰 |
| Phase78Test | 8 | 見下表 |

### Phase 7–8 新增測試

| 測試 | 檢查內容 |
|---|---|
| updateStatus | 有更新／已是最新／App 版本太舊（schemaVersion） |
| integrityCheck | 下載不完整、內容被改（同大小但 SHA-256 不符）、空檔案都會被拒絕 |
| publishedVersionFileMatchesBundledData | `data/version.json` 與 `data/champions_data.json` 及內置資料一致 |
| teamImpactWhenRegulationListChanges | 新資料移除某 Pokémon 時，相關隊伍顯示「→ 不合法」及原因；資料不變時沒有提示 |
| outdatedRegulationHint | 使用已結束 Regulation 的隊伍提示改用目前 Regulation |
| dataIntegrity | 名單內每隻 Pokémon 存在；屬性、特性、種族值、招式表齊全；每隻 Mega 有 Mega Stone 及原本形態；PP 規則；18×18 屬性表只含 0／0.5／1／2；25 種性格；每筆資料都有驗證標示 |
| regulationValidation | Regulation 日期順序、不重疊；名單只增不減（M-B ⊆ M-C ⊆ M-D 暫定）；抽查 Mewtwo、Mew、Kyogre 等幻之／禁止級傳說不在 M-C 名單 |
| performance | 見下節 |

## 2. 效能（CI 電腦上的 JVM；手機會較慢，上限已預留空間）

| 操作 | 時間 | 上限 |
|---|---|---|
| 讀取資料（約 800 KB） | 13 ms | 3000 ms |
| 資料檢查 | 15 ms | 1000 ms |
| 隊伍分析（6 隻） | 44 ms | 1500 ms |
| Speed Tier（M-C 全部 345 隻） | 1 ms | 500 ms |
| 1000 次傷害計算 | 4 ms | 2000 ms |
| 生存分析 | 少於 1 ms | 2000 ms |
| Matchup 報告 | 10 ms | 1500 ms |
| 100 場 AI 完整對戰 | 173 ms | 30000 ms |

## 3. UI tests（Android 模擬器，7 項）

App 以內置資料啟動（不需要網絡），逐一打開主要畫面：

| 測試 | 檢查內容 |
|---|---|
| homeLoadsBundledData | 主頁顯示 App 名稱、資料狀態、「離線可用」 |
| pokedexOpens | Pokémon 圖鑑載入名單 |
| createTeamAndOpenTools | 建立新隊伍 → 隊伍頁顯示合法性檢查 → 返回後列表有「隊伍 1」 |
| damageCalculatorOpens | 傷害計算（Lv50）載入 |
| speedTierOpens | Speed Tier 載入 |
| dataScreenShowsOnlineUpdate | 資料更新頁顯示「線上更新」及自動更新設定 |
| regulationScreenOpens | Regulation 頁列出 M-C |

## 4. 人工測試建議（實機）

1. 覆蓋安裝 V1.6，確認舊隊伍仍在。
2. 開啟飛航模式：所有功能可用；資料更新頁顯示「離線中」。
3. 關閉飛航模式：資料更新 → 立即檢查更新 → 應顯示「已是最新」。
4. 12 月 2 日後（Showdown 更新 M-D 後）：主頁出現「有可用資料更新」→ 下載並預覽 → 確認 → 查看受影響的隊伍。
5. 資料更新 → 還原上一版本，確認可回到舊資料。

## 5. 未包括

- 實機上的自動 UI 測試（只在模擬器執行）。
- 線上更新的完整下載流程只在 core 層測試（核對及匯入）；實際下載需要真實的新資料，會在下次資料更新時驗證。
