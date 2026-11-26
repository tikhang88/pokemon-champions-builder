# 更新紀錄

## V1.7
- 新增「速度線」頁（主頁及隊伍頁）：每隻 Pokémon 的極速、準速、無投資、最慢、圍巾、+1、+2、−1、順風、速度特性（下雨悠游自如 ×2 等）；可選顯示哪些速度線、戲法空間、逐隻顯示；選隊員（可加圍巾／+1／順風等）或輸入數值，以顏色顯示先後手及同速。原有 Speed Tier 保留。
- 屬性篩選改為 18 屬性的格狀選單，不再超出畫面；其他下拉選單限制高度。
- 招式按屬性分組（該 Pokémon 本身屬性先列），同屬性內物理、特殊、變化，威力由高至低。
- 道具分組：此 Pokémon 的 Mega Stone、常用對戰道具、屬性強化道具、抗性樹果、其他樹果、天氣／場地、其他、其他 Mega Stone。

## V1.6 — Phase 7–8：完整離線整合、線上更新、測試及發佈
- 線上資料更新：檢查 `data/version.json`、下載、SHA-256 核對、資料檢查、Change Log、確認後套用。
- 自動更新設定：關閉／自動檢查並提示（預設）／自動下載並套用；主頁顯示可用更新。
- 更新或匯入後列出受影響的隊伍；Regulation 改變時提示；舊 Regulation 隊伍顯示提示及一按切換。
- `Update data` workflow 同時產生 `data/version.json`。
- 新測試：資料完整性、Regulation 日期及名單、更新檢查、效能、Android 模擬器 UI 測試（新 `UI tests` workflow）。
- 版本號改為 1.6.x；主頁顯示 App 及資料版本。
- 新文件：使用說明、測試報告、已知問題。

## V1.5 — Phase 6：Battle Simulator
- 對戰模擬：單一回合、多回合、情境測試；多次模擬（第一回合 ×200、完整對戰 ×100）。

## V1.4 — Phase 5：Damage Calculator
- 傷害計算（基本／進階）、KO 機率、生存分析；通過 Smogon 測試案例。
- 第二屬性篩選；性格按提升的能力排列。

## V1.3 — Phase 4
- Coverage Matrix、Speed Tier、Matchup 研究、對手隊伍；隊伍碼記錄及 Showdown 文字匯入。

## V1.2 — Phase 3
- 隊伍分析（總覽、防守、攻擊、配合、報告）；App 改名及新 Icon。

## V1.1 — Phase 2
- Team Builder、隊員設定、Mega Toggle、合法性檢查、匯出文字；M-D 暫定資料。

## V1.0 — Phase 1
- 資料基礎：圖鑑、Pokémon 詳細頁、Regulation、資料匯入／還原、離線指示。
