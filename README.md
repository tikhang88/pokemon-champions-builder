# Pokémon Champion 隊伍模擬器

個人用 Android App，用於 Pokémon Champions 的競技資料查閱、隊伍構築及分析。完全離線可用。

目前版本：**V1.5（Phase 6：Battle Simulator）**

## 功能

### V1.5 新增
- **對戰模擬**（隊伍頁「對戰模擬」）：選對手隊伍（對手隊伍或自己其他隊伍）、單打／雙打、按次序選出戰成員（首 1／2 隻先發）。
  - **Mode A 單一回合**：為每隻 Pokémon 選招式、目標、Mega Evolution 或換人，執行一回合，查看行動次序、傷害、HP、狀態及能力變化。
  - **Mode B 多回合**：逐回合操作（對手可交由 AI），或「AI 自動對戰至結束」。
  - **Mode C 情境測試**：設定開始時的天氣、場地、戲法空間、順風、反射壁／光牆、每隻 Pokémon 的 HP 及異常狀態。
  - **多次模擬**：第一回合 ×200（先行動機率、被擊倒機率、平均剩餘 HP）；完整對戰 ×100（雙方 AI 的勝率，屬估算）。
  - 傷害隨機數可選隨機／最低／平均／最高；擊中要害可選隨機／不擊中／必定擊中。
- 已模擬：優先度、速度（順風、麻痺、圍巾、天氣特性）、戲法空間、Mega Evolution（每場一次）、命中、擊中要害、傷害（已驗證公式）、屬性相性、Champions 異常狀態規則（麻痺 1/8、睡眠 2–3 回合、冰凍第 3 回合必定解凍）、能力變化、追加效果、守住、會心一擊、Fake Out、注目／幫助、天氣／場地／順風／反射壁／光牆／極光幕、回復、吸取、反作用力、生命寶珠、氣勢披帶／結實、文柚果、弱點保險、威嚇、天氣／場地特性、回合結束傷害及回復。
- 每項記錄都標示可靠程度：已驗證機制、已驗證公式、估算、未支援（例如未模擬的招式效果會清楚列出）。

### V1.4 新增
- **傷害計算**（主頁或隊伍頁）：基本模式（攻擊方、招式、防守方＋常用防守配置）及進階模式（Mega、特性、道具、性格、Stat Points、能力等級、狀態、HP、特性發動；場地：單／雙打、範圍招式、天氣、場地、反射壁／光牆／極光幕、幫助、友情防守、擊中要害、連續攻擊次數）。
- 結果：傷害範圍及百分比、16 個隨機數、KO 機率（OHKO／2HKO…）、生存分析（要承受需要多少 HP／防禦 SP；要確定一擊擊倒需要多少攻擊 SP）。
- 支援：本系、屬性相性（Freeze-Dry、Flying Press、Thousand Arrows、Scrappy）、-ate 特性、天氣、場地、擊中要害、灼傷、道具（講究系列、生命寶珠、達人帶、屬性強化道具、抗性樹果、突擊背心等）、常見特性（Huge Power、Technician、Multiscale、Filter、Thick Fat、Levitate 等）、特殊威力招式（Low Kick、Heavy Slam、Facade、Knock Off、Foul Play、Body Press 等）、多次攻擊、親子愛。
- **驗證**：傷害公式通過 Smogon Damage Calculator 的第 9 世代測試案例（傷害範圍及 KO 機率完全一致），另有 Champions Lv50 測試；未支援的機制會標示 Estimated。
- 圖鑑及選擇 Pokémon 加入「第二屬性」篩選；性格按提升的能力排列，無影響的放最後。

### V1.3 新增
- **隊伍碼（Replica Team ID）**：每個隊伍可記錄 10 位隊伍碼，可複製。隊伍碼由遊戲伺服器解讀、本身不含隊伍資料，所以 App 不能用隊伍碼直接讀取隊伍。
- **匯入隊伍文字**：貼上 Pokémon Showdown／PokePaste 格式（Game8、dexdb 等網站提供），支援英文及繁體中文名稱；EV 會按 HOME 規則轉換為 Stat Points；Mega Stone 自動開啟 Mega。匯出文字亦可在 Showdown 匯入。
- **Coverage Matrix**（隊伍分析內）：屬性｜防守｜攻擊｜備註，點擊查看哪些成員弱／抵抗、哪些成員及招式可攻擊；Mega 即時同步。
- **Speed Tier**：Regulation 全部 Pokémon（四種配置）＋ 我方實際速度排位；條件：順風、戲法空間、講究圍巾、麻痺、天氣特性、能力等級、輕裝／飛毛腿；與任何 Pokémon 比較；速度摘要（優勢、劣勢、缺口）。
- **Matchup 研究**：對手隊伍（可建立或匯入）或自選 Pokémon；Matchup Matrix（對手｜威脅｜我方應對），每個評級列出計算依據；最佳防守／進攻應對、速度比較、對手共同弱點、可能策略、先發組合（全部標示為分析建議）。

### V1.2
- **隊伍分析**（在隊伍頁按「隊伍分析」）：
  - 總覽：屬性分佈、防守／攻擊摘要、物理／特殊／變化分佈、每位成員角色、速度分佈。
  - 防守矩陣：18 種攻擊屬性 × 每位成員的倍率，已計特性（Levitate、Thick Fat、Filter 等）；標示共同弱點、無人抵抗的屬性。
  - 攻擊覆蓋：以實際招式計算，已計 Pixilate／Dragonize 等特性及 Freeze-Dry 等特殊招式；列出覆蓋缺口及難以打擊的 Pokémon。
  - 配合：速度控制、先制、輔助、強化、Pivot、異常狀態、守住、天氣、場地。
  - 報告：優勢、弱點、覆蓋缺口、配合、改善方向（每個建議都列出原隊伍問題、理由、優勢、代價及可能新弱點，並提供不更換成員的做法）。
  - 可切換以 Mega 形態或原本形態分析。
- App 改名為「Pokémon Champion 隊伍模擬器」及更換 Icon。

### V1.1
- **Team Builder**：建立、儲存、重新命名、複製、刪除隊伍；6 個隊員位置；選擇 Regulation 及單打／雙打。
- **隊員設定**：特性、道具、性格（顯示↑↓）、Stat Points（66／32，即時顯示 Lv50 能力值）、4 個招式（只列 Champions 招式表內的招式）。
- **Mega Toggle**：選擇 Mega 形態會自動裝備 Mega Stone，並同步更新屬性、特性及能力值。
- **合法性檢查**：合法／不合法／待驗證／未完成，逐項列出原因（Regulation、Species Clause、Item Clause、Mega Stone、招式、特性、SP、隊伍人數；多於一隻 Mega 會提示）。
- **匯出隊伍文字**：分享到其他 App。
- **M-D 暫定資料**：M-C 全部 ＋ 官方已公布的 Heatran、Dondozo、Tatsugiri（含 Mega Heatran、三款 Mega Tatsugiri），以及官方公布的 Mega 特性；12 月 2 日平衡調整會在招式及特性頁顯示為「即將生效」。

### V1.0

- **Pokémon 圖鑑**：自動套用所選 Regulation；搜尋（中／英名稱、編號、特性），按屬性篩選，按編號、名稱、種族值、各項能力排序，可選擇包括或只看 Mega。
- **Pokémon 詳細頁**：概覽、能力值（Lv50 範圍）、招式（Champions 招式表）、特性、形態。
- **Mega Toggle**：即時切換原本／Mega（包括 X／Y／Z），並顯示「原本 vs Mega」能力值、屬性及特性的比較。
- **Regulation**：M-A 至 M-D，顯示狀態（目前／即將／歷史）、規則、數量及新增 Pokémon，可切換 App 使用的 Regulation。
- **資料更新**：匯入 `.json` 或 `.zip` 資料檔 → 檢查資料 → 列出改變（Change Log）→ 確認後才替換。可還原上一版本，或改回內置資料。
- **離線指示**：主頁顯示資料版本、目前 Regulation、在線／離線狀態及最後更新時間。

## 安裝

1. 打開 [Releases](../../releases)，找最新的 **App V1.0.x**。
2. 用手機下載 `PokemonChampions-v1.0.x.apk` 並安裝（需要允許「安裝未知來源的應用程式」）。

## 12 月 2 日匯入 M-D 資料

`Update data` workflow 每天 **03:00（香港時間）** 會自動執行：

1. 由 Pokémon Showdown 重新產生資料。
2. 如果 Champions 資料有改變（例如加入 M-D 名單）：
   - 發佈「**資料更新**」release，附 `champions_data.json`；
   - 同時建置一個內置新資料的 APK。
3. 在 App：**資料更新 → 選擇資料檔並匯入**，選下載的 `champions_data.json`。

想立即更新：GitHub → **Actions → Update data → Run workflow**。

注意：Showdown 一般在新 Regulation 開始後一至數天內更新。期間 App 會繼續使用 M-C，並提示 M-D 已生效但未有名單。

## 資料來源及驗證

- 主要來源：[Pokémon Showdown](https://github.com/smogon/pokemon-showdown) 的 `champions` mod（社群維護，以遊戲 data dump 為基礎）。
- 每筆資料都有 `source` 及 `verification`（官方確認／已驗證／社群資料／待驗證）。
- Showdown 的檔案只會被當作文字解析（`tools/data/extract.mjs`），不會執行。
- 研究報告：[`docs/Phase0_Research_Report.md`](docs/Phase0_Research_Report.md)。

## 專案結構

```
core/          純 Kotlin 引擎（能力值、屬性相性、Regulation、資料驗證、Change Log）＋ Unit Tests
app/           Android App（Jetpack Compose）；app/src/main/assets/champions_data.json 為內置資料
tools/data/    資料產生工具（build_snapshot.py、extract.mjs、regulations_meta.json）
data/          最新資料快照
.github/       Build APK、Update data workflows
```

新 Regulation 的日期／公告寫在 `tools/data/regulations_meta.json`；Pokémon 名單會由 Showdown 自動讀取。

## APK 簽署

在 repository **Settings → Secrets and variables → Actions** 加入 `SIGNING_KEYSTORE_B64`，之後每個 APK 都用同一條 key 簽署，可以直接覆蓋安裝更新。未設定時會用臨時 key，更新前需要先解除安裝。

## 開發階段

| Phase | 內容 | 狀態 |
|---|---|---|
| 0 | 研究及可行性 | ✅ |
| 1 | Data Foundation、資料匯入 | ✅ V1.0 |
| 2 | Team Builder、合法性檢查 | ✅ V1.1 |
| 3 | Team Analysis | ✅ V1.2 |
| 4 | Coverage Matrix、Speed Tier、Matchup | ✅ V1.3 |
| 5 | Damage Calculator | ✅ V1.4 |
| 6 | Battle Simulator | ✅ V1.5 |
| 7–8 | 完整離線整合、測試及發佈 | 下一步 |

Pokémon 及相關名稱為 Nintendo、Creatures、GAME FREAK、The Pokémon Company 的商標。本 App 為非官方個人工具，不包含官方圖像。
