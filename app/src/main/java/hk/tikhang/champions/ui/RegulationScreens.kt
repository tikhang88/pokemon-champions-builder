@file:OptIn(ExperimentalLayoutApi::class)

package hk.tikhang.champions.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import hk.tikhang.champions.core.Regulation
import hk.tikhang.champions.core.RegulationEngine
import hk.tikhang.champions.core.RegulationStatus
import hk.tikhang.champions.data.AppViewModel
import hk.tikhang.champions.data.DataState
import java.time.Instant

@Composable
private fun StatusBadge(r: Regulation, state: DataState) {
    val st = RegulationEngine.status(r, Instant.now())
    val color = when (st) {
        RegulationStatus.CURRENT -> Color(0xFF2E7D32); RegulationStatus.UPCOMING -> Color(0xFF1565C0)
        RegulationStatus.PAST -> Color(0xFF757575); RegulationStatus.UNKNOWN -> Color(0xFFB26A00)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Badge(st.zh, color)
        if (state.regulation.id == r.id) Badge("App 使用中", MaterialTheme.colorScheme.primary)
        VerificationBadge(r.verification)
    }
}

@Composable
fun RegulationListScreen(vm: AppViewModel, state: DataState, onBack: () -> Unit, open: (String) -> Unit) {
    val regs = state.snapshot.regulations.sortedByDescending { it.effectiveDate ?: "" }
    Screen("Regulation", onBack, subtitle = "App 使用中：${state.regulation.name}") { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.regulationIsManual) {
                item {
                    SectionCard {
                        Text("你已手動選擇 ${state.regulation.id}。", style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = { vm.selectRegulation(null) }) { Text("改為自動跟隨目前 Regulation") }
                    }
                }
            }
            items(regs, key = { it.id }) { r ->
                SectionCard(modifier = Modifier.clickable { open(r.id) }) {
                    Text(r.name, style = MaterialTheme.typography.titleMedium)
                    StatusBadge(r, state)
                    Text("${localTime(r.effectiveDate)} → ${localTime(r.endDate)}", style = MaterialTheme.typography.bodySmall)
                    Text(
                        r.counts?.let { "可選 ${it.teamSelectable} 個（其中 Mega ${it.megaForms} 個）" } ?: (r.allowedPokemonNote ?: "未有 Pokémon 名單"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun RegulationDetailScreen(vm: AppViewModel, state: DataState, id: String, onBack: () -> Unit, openPokemon: (String) -> Unit) {
    val s = state.snapshot
    val r = s.regulationById[id]
    if (r == null) {
        Screen("找不到 Regulation", onBack) { pad -> Text(id, Modifier.padding(pad).padding(16.dp)) }
        return
    }
    Screen(r.name, onBack) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SectionCard {
                    StatusBadge(r, state)
                    LabelValue("生效", localTime(r.effectiveDate))
                    LabelValue("結束", localTime(r.endDate))
                    LabelValue("資料來源", r.source)
                    if (r.hasPokemonList && state.regulation.id != r.id) {
                        Button(onClick = { vm.selectRegulation(r.id) }) { Text("套用此 Regulation") }
                    }
                }
            }
            item {
                SectionCard("規則") {
                    r.level?.let { LabelValue("等級", "全部調整為 Lv50") }
                    LabelValue("隊伍", "單打 3–6 隻選 3；雙打 4–6 隻選 4")
                    LabelValue("Species Clause", if (r.speciesClause) "同一物種不可重複" else "沒有")
                    LabelValue("Item Clause", if (r.itemClause) "同一道具不可重複" else "沒有")
                    if (r.bannedCategories.isNotEmpty()) LabelValue("禁止", "幻之寶可夢、禁止級傳說寶可夢")
                    r.megaRules?.let { LabelValue("Mega", "需持有 Mega Stone；每場對戰只可 Mega 一次") }
                    r.statSystem?.let { LabelValue("能力培養", "Stat Points：共 66 點，每項最多 32；沒有 IV") }
                }
            }
            r.counts?.let { c ->
                item {
                    SectionCard("數量") {
                        LabelValue("可選條目", "${c.teamSelectable}")
                        LabelValue("非 Mega", "${c.nonMegaSelectable}")
                        LabelValue("Mega 形態", "${c.megaForms}")
                    }
                }
            }
            r.announced?.let { a ->
                item {
                    SectionCard("已公布資料（待驗證）") {
                        a.carriesOverFrom?.let { LabelValue("沿用", it) }
                        a.newPokemonCount?.let { LabelValue("新增 Pokémon", "$it 隻") }
                        a.newMegaCount?.let { LabelValue("新增 Mega", "$it 個") }
                        if (a.namedSoFar.isNotEmpty()) LabelValue("已公布名稱", a.namedSoFar.joinToString("、"))
                        a.unannounced?.let { LabelValue("未公布", it) }
                        a.balanceAdjustments?.let { LabelValue("平衡調整", it) }
                        if (r.provisional) Text("暫定名單 = 上一個 Regulation ＋ 已公布的 Pokémon。新 Pokémon 的招式表暫用 Scarlet/Violet 資料，待驗證。", style = MaterialTheme.typography.bodySmall)
                        Text("完整名單會在資料來源更新後，經「資料更新」匯入。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            s.balancePatches.filter { it.effectiveDate == r.effectiveDate }.forEach { patch ->
                item {
                    SectionCard("平衡調整（${localTime(patch.effectiveDate)} 起，${patch.appliesTo ?: ""}）") {
                        VerificationBadge(patch.verification)
                        Text("招式調整", style = MaterialTheme.typography.labelLarge)
                        patch.moveChanges.forEach { c -> Text("・${s.moveById[c.move]?.let { it.nameZh ?: it.name } ?: c.move}：${c.text}", style = MaterialTheme.typography.bodySmall) }
                        Text("新增可學招式", style = MaterialTheme.typography.labelLarge)
                        patch.newMoves.forEach { nm ->
                            val who = nm.pokemon.joinToString("、") { id -> s.pokemonById[id]?.let { s.displayName(it) } ?: id }
                            Text("・${s.moveById[nm.move]?.let { it.nameZh ?: it.name } ?: nm.move}：$who", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("特性調整", style = MaterialTheme.typography.labelLarge)
                        patch.abilityChanges.forEach { c -> Text("・${s.ability(c.ability)?.nameZh ?: c.ability}：${c.text}", style = MaterialTheme.typography.bodySmall) }
                        Text("來源：${patch.source}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            r.newVsPrevious?.takeIf { it.isNotEmpty() }?.let { ids ->
                item {
                    SectionCard("相比上一個 Regulation 新增（${ids.size}）" + if (r.provisional) "・暫定" else "") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ids.mapNotNull { s.pokemonById[it] }.forEach { p ->
                                AssistChip(onClick = { openPokemon(p.id) }, label = { Text(s.displayName(p)) })
                            }
                        }
                    }
                }
            }
            if (!r.hasPokemonList) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(r.allowedPokemonNote ?: "未有 Pokémon 名單", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
