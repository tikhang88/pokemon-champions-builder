# Pokémon Champions Team Builder & Battle Simulator
## Phase 0 — Research & Feasibility Report

版本：Phase 0 / 對應開發指令 V1.1
日期：2026-11-26（香港時間）
資料快照：`champions_data_v1.json`（dataVersion `2026.11.26-sdc7fa6f1`）

---

## 0. 摘要（Executive Summary）

| 項目 | 結論 |
|---|---|
| 遊戲狀態 | Pokémon Champions 已推出（Switch 2026-04-08；iOS / Android 2026-06-17），是 2026 年起 VGC 官方平台 |
| 目前 Regulation | **M-C**（2026-09-09 02:00 UTC → 2026-12-02 01:59 UTC） |
| 下一個 Regulation | **M-D** 已於 2026-11-24 官方公布，**2026-12-02 02:00 UTC 生效**（至 2027-03-03），新增 24 隻 Pokémon、4 個 Mega；詳細名單及平衡調整未進入任何資料來源 → Pending Verification |
| 官方 API / 官方可下載資料 | **沒有**。官方只有遊戲內公告、官網新聞及社交媒體 |
| 最佳可用資料來源 | Pokémon Showdown `champions` mod（社群維護、以遊戲 data dump 為基礎，2026-11-25 仍在更新） |
| 能否直接用 SV 資料 | **不能**。已確認 Champions 改變了能力值公式、PP、麻痺／睡眠／冰凍機制，以及 34 個可用招式、14 個特性 |
| Offline 可行性 | 完全可行。整個 M-B + M-C 資料約 790 KB JSON |
| 本環境建置 APK | **目前不可行**：本工作環境封鎖 Google Maven 及 Android SDK 下載，無法編譯 Android App（見 §7.6 及 §8） |

Phase 0 交付：本報告（含 §3.4 規定的 7 份報告）、資料快照 `champions_data_v1.json`、資料建置腳本 `build_snapshot.py`。

---

## 1. Data Source Report

### 1.1 已研究的來源

| 優先 | 來源 | 類型 | 內容 | 使用方式 | 狀態標籤 |
|---|---|---|---|---|---|
| 1–3 | Pokémon Champions 遊戲內公告、Pokémon 官方新聞 | Official | Regulation 日期、新增 Pokémon／Mega、Mega 規則 | 人手核對規則及日期 | OFFICIAL |
| 3 | 官方 API／可下載資料 | — | **不存在**（未找到任何公開 API 或下載檔） | — | — |
| 4 | **Pokémon Showdown**（`smogon/pokemon-showdown`，mod `champions`、`championsregmb`） | Verified Community | 圖鑑、能力值、招式、特性、道具、learnsets、合法性、戰鬥機制程式碼、繁體中文名稱 | **主要資料來源** | COMMUNITY_SOURCE |
| 4 | Smogon「Champions Battle Mechanics Research」討論串 | Verified Community | 遊戲 data dump 機制研究（能力值公式、PP 公式） | 機制核對 | VERIFIED（配合第二來源時） |
| 4 | **Smogon damage-calc**（`smogon/damage-calc`，MIT） | Verified Community | 已支援 Champions（gen 0），有 Champions 測試案例 | Damage Engine 移植參考及測試對照 | COMMUNITY_SOURCE |
| 5 | Serebii、Bulbapedia、Game8、pokemon-zone、PocketMonsters.net | Other Reliable | Regulation 頁面、狀態變更、M-D 公告 | 交叉核對 | — |
| ✗ | `otterlyclueless/pokemon-champions-data` | Community | 2026 年 4 月後未更新、只有 3 個 commit、README 自稱數值仍以主系列為基礎 | **不採用** | — |

### 1.2 主要資料來源版本

- Repository：`https://github.com/smogon/pokemon-showdown`
- Commit：`c7fa6f16503de510c319239b5b2d29d4f7abd8c4`（2026-11-25）
- 讀取方式：只把 TypeScript 資料檔當作文字解析（TypeScript AST），只保留字面值，**沒有執行任何下載的程式碼**。

### 1.3 每筆資料的 metadata（符合指令 §3.3 / §15）

快照內每筆 Pokémon、Move、Ability、Item 及每個 Regulation 都帶有：`source`、`verification`；整個快照帶有 `dataVersion`、`generatedAt`、`source.commit`、`source.commitDate`。

驗證等級（對應指令 §15）：

| 標籤 | 意思 |
|---|---|
| OFFICIAL | The Pokémon Company 公告或遊戲內文字確認 |
| VERIFIED | 遊戲 data dump ＋ 獨立第二來源一致 |
| COMMUNITY_SOURCE | Showdown 社群資料（datamine 為基礎），未逐項再核對 |
| PENDING_VERIFICATION | 已公布或只有部分資料，未進入任何資料來源 |

### 1.4 授權及版權注意

- Showdown 程式碼為 MIT；Pokémon 名稱、數值、圖像屬 Nintendo / Creatures / GAME FREAK / The Pokémon Company。
- 本 APP 定位為**個人使用**工具。建議：**不打包官方圖像／Sprite**，改用屬性顏色＋名稱卡片；之後可加「匯入 Sprite 包」功能讓使用者自行提供圖片。指令 §5.2 的「Image」欄位因此改為「可選，使用者自行匯入」。

---

## 2. Regulation Verification Report

### 2.1 Regulation 一覽

| Regulation | 生效（UTC） | 結束（UTC） | 快照內合法 Pokémon 名單 | 驗證 |
|---|---|---|---|---|
| M-A | 2026-04-08 02:00 | 2026-06-17 01:59 | 不包括（Showdown 已無 M-A mod；只保留歷史紀錄） | PENDING |
| M-B | 2026-06-17 02:00 | 2026-09-09 01:59 | 310 個可選條目（234 非 Mega ＋ 76 Mega） | COMMUNITY_SOURCE |
| **M-C（目前）** | 2026-09-09 02:00 | 2026-12-02 01:59 | **345 個可選條目（263 非 Mega ＋ 82 Mega），231 個物種** | COMMUNITY_SOURCE |
| M-D（下一個） | 2026-12-02 02:00 | 2027-03-03 01:59 | 不包括：只知道 M-C 全部保留、新增 24 隻 Pokémon ＋ 4 個 Mega；已點名 Heatran、Mega Heatran、Dondozo、Tatsugiri、Mega Tatsugiri | PENDING |

註：M-B 的結束日期在不同來源有出入（Serebii 寫 9 月 2 日；Bulbapedia、pokemon-zone 寫 9 月 9 日）。快照採用 9 月 9 日，因為 M-C 由 9 月 9 日開始。

### 2.2 共通規則（Flat Rules）

| 規則 | 內容 | 來源 |
|---|---|---|
| 等級 | 所有 Pokémon 調整為 Lv50 | Serebii、Showdown「Flat Rules」 |
| 隊伍 | Singles：3–6 隻，選 3；Doubles：4–6 隻，選 4 | Vandal、Showdown |
| Species Clause | 同一物種不可重複 | Showdown |
| Item Clause | 同一道具不可重複 | Showdown |
| 禁止 | Mythical、Restricted Legendary | Showdown |
| Mega | 需持有對應 Mega Stone；**每場對戰只可 Mega Evolution 一次** | 官方 M-D 公告（經 PocketMonsters.net） |
| Terastallization | 不可用 | Showdown champions mod |
| 計時（Singles，M-A 參考） | Your Time 7 分鐘（M-A 第 1 季）／10 分鐘（第 2 季），Team Preview 90 秒 | Serebii |

### 2.3 核對結果

- M-C 相對 M-B 新增 35 個條目，其中 6 個 Mega：Absol-Mega-Z、Salamence-Mega、Garchomp-Mega-Z、Lucario-Mega-Z、Golisopod-Mega、Baxcalibur-Mega。與 pokemon-zone 列出的 6 個新 Mega Stone **完全一致**。
- pokemon-zone 寫「32 個新 Pokémon」，快照為 35 個條目；差異來自外觀形態（Squawkabilly 三種顏色、Toxtricity Low Key、Indeedee♀）是否分開計算。
- MetaVGC 寫 M-C 共 229 隻；快照為 231 個物種（差 2，應為計算方式不同）。→ 建議在 Phase 1 用遊戲內名單再逐一核對。
- M-B → M-C 沒有移除任何 Pokémon，與「名單只增不減」的官方說法一致。

### 2.4 M-D 處理方案（替代方案，符合指令 §3.4「先提出替代方案」）

M-D 在 6 天後生效，但完整名單和 12 月 2 日的平衡調整（招式、命中率、特性）未進入任何資料來源。
APP 第一版會：
1. 以 M-C 為「目前 Regulation」出貨。
2. M-D 顯示為「即將生效・Pending Verification」，只列出已確認的 5 個名稱。
3. 12 月 2 日後 Showdown 更新，重新執行 `build_snapshot.py` 產生 v2 快照，經 Offline Update 推送；舊隊伍保留並重新做合法性檢查（指令 §4.5）。

---

## 3. Pokémon Data Availability Report

| 指令要求欄位（§5.2） | 可取得？ | 備註 |
|---|---|---|
| 名稱（英文） | ✅ | |
| 名稱（繁體中文） | ✅ 344 / 349 | Showdown zh-tw 文字；缺 Floette-Eternal、Floette-Mega、Toxtricity（兩形態）、Alcremie → 暫用英文 |
| National Dex | ✅ | |
| Type | ✅ | |
| Ability | ✅ | 216 個特性，含繁中名稱及說明 |
| Base Stats ／ Total | ✅ | |
| Form ／ Form Variation | ✅ | 地區形態、Rotom 等；戰鬥中才出現的形態（Aegislash-Blade、Castform 天氣形態）標記為 battleOnly、不可直接選入隊伍 |
| Mega Availability | ✅ | `isMega`、`megaStone`、`megaOf` |
| Regulation Eligibility | ✅ | `legalIn: ["M-B","M-C"]` |
| Height ／ Weight | ✅ | 影響 Low Kick、Heavy Slam 等，Damage Engine 需要 |
| Gender | ✅ | |
| Evolution | ⚠️ 不建議展示 | Champions 隊伍以最終形態為主，進化鏈對構築意義不大 |
| Image | ⚠️ 不打包 | 見 §1.4 |
| Moves（Learnset） | ✅ 348 / 349 → 修正後 349 / 349 | Champions 專用 learnset；Mega 繼承原形態；Mega Floette 由 Eternal Floette 繼承 |
| Moves 詳細資料 | ✅ 510 個招式 | Type、Category、Power、Accuracy、PP（Champions 上限）、Priority、Effect（英／繁） |
| Items | ✅ 166 個合法道具，其中 81 個 Mega Stone | Meowstic ♂／♀ 共用一顆 Meowsticite |

---

## 4. Mega Evolution Verification Report

### 4.1 數量
M-C 共有 **82 個 Mega 形態**、81 顆 Mega Stone。包括 Legends Z-A 的新 Mega（例如 Mega Raichu X／Y、Mega Meganium、Mega Baxcalibur）及「Z Mega」（Mega Absol Z、Mega Garchomp Z、Mega Lucario Z）。

### 4.2 Original vs Mega 範例（快照數據）

| Pokémon | 屬性 | 特性 | HP | Atk | Def | SpA | SpD | Spe | Total |
|---|---|---|---|---|---|---|---|---|---|
| Absol | Dark | Pressure / Super Luck / Justified | 65 | 130 | 60 | 75 | 60 | 75 | 465 |
| Mega Absol Z | **Dark / Ghost** | **Sharpness** | 65 | 154 (+24) | 60 | 75 | 60 | **151 (+76)** | 565 |
| Garchomp | Dragon / Ground | Sand Veil / Rough Skin | 108 | 130 | 95 | 80 | 85 | 102 | 600 |
| Mega Garchomp Z | **Dragon** | **Levitate** | 108 | 130 | 85 (−10) | **141 (+61)** | 85 | **151 (+49)** | 700 |
| Lucario | Fighting / Steel | Steadfast / Inner Focus / Justified | 70 | 110 | 70 | 115 | 70 | 90 | 525 |
| Mega Lucario Z | Fighting / Steel | **Aura Guard** | 70 | 100 (−10) | 70 | **164 (+49)** | 70 | **151 (+61)** | 625 |

重點：Mega 可以**改變屬性**（Garchomp Z 失去 Ground；Absol Z 增加 Ghost）及**只有一個特性**，所以 Mega Toggle 必須同步更新 Coverage、弱點、Speed Tier（指令 §6.5）。

### 4.3 已確認的 Mega 規則
- 需持有對應 Mega Stone → Item Clause 下，同一隊伍不能有兩隻用同一顆石的 Mega。
- 每場對戰每位玩家只可 Mega 一次 → Legality Checker 不會禁止隊伍有多隻 Mega，但 Team Analysis 要提示「只能選一隻 Mega」。
- Mega 形態在瀕死後**不會**還原（Showdown champions mod）。
- Mega Evolution 算作一個行動（影響 Truant）。

---

## 5. Battle Mechanics Verification Report

### 5.1 能力值公式（Lv50）— VERIFIED

| 能力 | 公式 |
|---|---|
| HP | 基礎 + SP + 75 |
| 其他 | ⌊(基礎 + SP + 20) × 性格⌋，性格 1.1 / 1.0 / 0.9 |

- Stat Points（SP）：每隻最多 66 點，每項最多 32 點；**沒有 IV**（等同全 31）。
- 三個獨立來源一致：Showdown `champions/scripts.ts`、Smogon damage-calc `calcStatChampions`、Smogon data dump 研究串。
- 例：Garchomp HP 108 + 32 SP + 75 = 215（與主系列 252 EV 的 Lv50 數值相同）。

→ **指令 §7.4 需要修改**：「EV / IV」改為「Stat Points（SP）」；IV 欄位取消。「Nature」在遊戲內稱為「Stat Alignment」，效果相同。

### 5.2 PP — VERIFIED
基礎 PP 上限 20；所有招式都是最大 PP：最大 PP = 4 × (基礎 PP ÷ 5 + 1)，即 5→8、10→12、15→16、20→20。

### 5.3 狀態異常（與主系列不同）

| 狀態 | Champions | 主系列 | 驗證 |
|---|---|---|---|
| 麻痺 | 1/8 機會不能行動，速度 ×0.5 | 1/4 | VERIFIED（Serebii、Showdown、Polygon） |
| 睡眠 | 第 1 回合必定睡；第 2 回合 1/3 機會醒；第 3 回合必醒 | 1–3 回合 | VERIFIED（Showdown、op.gg） |
| 冰凍 | 每次嘗試行動 25% 解凍，第 3 回合必定解凍 | 20%，可無限 | VERIFIED（Serebii、Bulbapedia、Showdown） |
| Freeze-Dry | 不會再令對手冰凍 | 10% | COMMUNITY（Bulbapedia） |
| Salt Cure | 每回合 1/16（水／鋼 1/8），減半 | 1/8（1/4） | COMMUNITY（Bulbapedia） |

### 5.4 招式變更（快照內 34 個，節錄）

| 招式 | 主系列 → Champions |
|---|---|
| Protect／Spiky Shield／King's Shield／Baneful Bunker／Wish | 基礎 PP 10 → 5（最大 8） |
| Moonblast | 降特攻機會 30% → 10% |
| Iron Head | 畏縮機會 30% → 20% |
| Dire Claw | 異常機會 50% → 30% |
| Make It Rain | 命中 100 → 95 |
| Crabhammer | 命中 90 → 95 |
| Psyshield Bash | 威力 70 → 90 |
| Meteor Assault | 威力 150 → 170 |
| Trop Kick | 威力 70 → 85 |
| Snap Trap | 屬性 Grass → Steel |
| Growth | 屬性 Normal → Grass |

注意：Polygon 報道「Protect PP 改為 10」，與 Showdown（基礎 5、最大 8）不同。可能是指最大 PP 或報道有誤 → 標記待遊戲內確認。

### 5.5 特性變更（14 個）
新特性：Dragonize、Mega Sol、Piercing Drill、Spicy Spray、Fire Mane、Eelevate、Aura Guard 等；行為改變：Regenerator、Natural Cure、Healer、Emergency Exit、Berserk、Run Away、Unseen Fist。

### 5.6 傷害公式 — COMMUNITY_SOURCE
- Champions mod 沿用 Gen 9 傷害流程：基礎傷害 → 範圍招式 ×0.75 → 天氣 → 擊中要害 ×1.5 → 隨機 85–100% → STAB ×1.5 → 屬性相性 → 灼傷 ×0.5 → 其他修正；沒有 Terastallization。
- Smogon damage-calc 已有 Champions 模式（例如 Mega Sol 測試案例），可作為移植及測試對照。
- 傷害結果在 APP 內先標示「Community-verified formula」；Phase 5 用 damage-calc 的計算結果建立 Test Cases（指令 §12.7）。

### 5.7 Speed 相關機制（指令 §10.5）
支援：Priority、Trick Room、Tailwind、Paralysis（×0.5）、天氣／特性速度修正（Swift Swim、Chlorophyll、Sand Rush、Slush Rush、Unburden 等）、Choice Scarf、Stat stages。Champions mod 取消了 Trick Room 的速度下溢（speed overflow）特例。

### 5.8 Battle Simulator 範圍建議
- Mode A（單回合）：可做到 Verified／Community 級別。
- Mode B（多回合）：可做，但很多特性、道具互動需逐個實作；未實作的機制會顯示「Unsupported Mechanics」（指令 §13.6）。
- Doubles 的目標選擇、範圍招式、Follow Me／Fake Out 等，會在 Phase 6 分階段加入。

---

## 6. Offline Data Feasibility Report

| 項目 | 結論 |
|---|---|
| 資料量 | M-B + M-C：349 Pokémon、510 招式、216 特性、166 道具、349 learnsets；JSON 約 790 KB，Room 資料庫約 2–4 MB |
| 首次啟動 | 快照隨 APK 附帶 → 安裝後即可完全離線使用 |
| 更新來源 | 沒有官方 API。建議：每次 Showdown 更新後，用 `build_snapshot.py` 產生新快照，放在你自己的 GitHub repository（`version.json` ＋ 快照檔） |
| 更新流程 | 檢查 `version.json` → 下載 → 驗證 SHA-256 及 schema → 寫入暫存資料庫 → 整個替換（atomic swap） |
| 更新失敗 | 保留舊資料、不清空、仍可離線使用（指令 §14.5） |
| Change Log | 新舊快照比對：New／Changed／Removed Pokémon、Moves、Mega、Regulation |
| 歷史 Regulation | 每個快照保留所有已知 Regulation 名單 → 可離線查閱 |

---

## 7. Technical Architecture Proposal

### 7.1 技術
Kotlin、Jetpack Compose（Material 3）、Room、Kotlin Coroutines／Flow、WorkManager（自動更新）、kotlinx.serialization。介面以繁體中文為主，英文名稱並列。

### 7.2 Modules

```
:app                      UI（Compose）、Navigation、DI
:core:model               純 Kotlin 資料類別（Pokemon、Move、TeamSlot、Regulation…）
:core:data                Room、快照匯入、Repository、版本管理
:engine:stats             能力值計算（SP / Stat Alignment）
:engine:regulation        Regulation 查詢、目前 Regulation
:engine:legality          Team Legality（Legal / Illegal / Pending + 原因）
:engine:coverage          18 屬性攻防矩陣
:engine:speed             Speed Tier、速度條件
:engine:matchup           Matchup Threat（可解釋分數）
:engine:damage            傷害計算（移植 Smogon damage-calc Champions 模式）
:engine:battle            Battle Simulator（單回合 → 多回合）
:engine:update            Offline Update、驗證、Change Log
```

所有 `:engine:*` 為純 Kotlin（JVM）模組，不依賴 Android，可獨立 Unit Test（指令 §13.7、§17）。

### 7.3 Mega 同步設計
每個 Team Slot 只保存 `speciesId` ＋ `megaEnabled`。所有引擎讀取同一個 `effectiveForm(slot)`（Mega 開啟 → 換成 Mega 的屬性、特性、能力值）。Toggle 一次，Coverage、Speed、Matchup、Damage、Battle 全部自動更新（指令 §6.5）。

### 7.4 Legality Engine 規則
Regulation 名單、隊伍人數、Species Clause、Item Clause、Mythical／Restricted、Mega Stone 與物種配對、招式在 Champions learnset 內、SP 總數 ≤ 66 及每項 ≤ 32、特性屬於該形態。資料標記為 PENDING 的項目 → 結果為「Pending Verification」。

### 7.5 Matchup Threat 分數（可解釋，非 AI 主觀判斷）
Threat = 加權總和：對我方的最高傷害百分比（Damage Engine）、速度比較、屬性相性、對方 Priority／Setup 招式、我方可用的抵抗及反擊者。每個分數都顯示各項貢獻，標示「Analysis／Estimated」（指令 §11.4–11.6）。

### 7.6 建置及交付
本工作環境**封鎖 Google Maven（`maven.google.com`）及 Android SDK 下載（`dl.google.com`）**，而 Kotlin／Compose Android App 必須依賴這兩個來源。所以 Phase 1 起的 APK 無法在這個環境內編譯。可行選項見 §8。

---

## 8. 需要你決定的事項

1. **APK 建置方式**（必須）
   - A. **GitHub Actions（建議）**：我在你的 GitHub repository 建立完整 Android 專案及 workflow，GitHub 雲端自動編譯，你在 Actions 頁面下載 APK。需要你提供或建立一個 repository。
   - B. **Android Studio**：我交付完整原始碼，你在電腦用 Android Studio 編譯。
2. **指令修改確認**
   - §7.4「EV / IV」→「Stat Points（66 / 32）」，取消 IV。
   - §5.2「Image」→ 不打包官方圖像，日後支援使用者匯入。
   - M-D 以 Pending 形式先出貨，12 月 2 日後經資料更新補齊。

---

## 9. Phase 1 預告（Data Foundation）
Room schema、快照匯入、Pokémon Database（搜尋、篩選、排序，自動套用 Regulation）、Pokémon Detail（Overview／Stats／Moves／Ability／Form＋Mega Toggle）、Regulation Database、Data Versioning、Offline Indicator。Stats Engine 及 Coverage Engine 的 Unit Tests 一併交付。

---

## 附錄 A：來源
- Showdown：https://github.com/smogon/pokemon-showdown（commit c7fa6f1，2026-11-25）
- Smogon damage-calc：https://github.com/smogon/damage-calc
- Smogon Champions Battle Mechanics Research：https://smogon.com/forums/threads/champions-battle-mechanics-research.3780372/
- Serebii Regulation M-A／M-B、Status Conditions：https://serebii.net/pokemonchampions/
- Bulbapedia Regulation Sets in Pokémon Champions：https://bulbapedia.bulbagarden.net/wiki/Regulation_Sets_in_Pok%C3%A9mon_Champions
- pokemon-zone Regulation M-C：https://www.pokemon-zone.com/champions/regulations/m-c/
- PocketMonsters.net Regulation M-D：https://www.pocketmonsters.net/news/9755
- Nintendo Life（M-D）：https://www.nintendolife.com/news/2026/11/pokemon-champions-is-adding-four-more-mega-evolutions-in-a-new-update-next-week
- Vandal（Stat Points、M-A／M-B 規則）：https://vandal.elespanol.com/en/pokemon-champions/
- MetaVGC M-C 名單：https://metavgc.com/regulations/regulationm-c

## 附錄 B：快照結構（`champions_data_v1.json`）
`meta`（版本、來源、驗證等級、數量）、`regulations`（M-A…M-D）、`mechanics`、`types`、`typeChart`（18×18）、`natures`、`pokemon`、`moves`、`abilities`、`items`、`learnsets`。
