# Pokémon Champions 隊伍研究（Team Builder & Battle Simulator）

個人用 Android App，用於 Pokémon Champions 的競技資料查閱、隊伍構築及分析。完全離線可用。

目前版本：**V1.1（Phase 2：Team Builder ＋ M-D 暫定資料）**

## 功能

### V1.1 新增
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
| 3 | Team Analysis | 下一步 |
| 4 | Coverage Matrix、Speed Tier、Matchup | |
| 5 | Damage Calculator | |
| 6 | Battle Simulator | |
| 7–8 | 完整離線整合、測試及發佈 | |

Pokémon 及相關名稱為 Nintendo、Creatures、GAME FREAK、The Pokémon Company 的商標。本 App 為非官方個人工具，不包含官方圖像。
