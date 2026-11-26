@file:OptIn(ExperimentalLayoutApi::class)

package hk.tikhang.champions.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hk.tikhang.champions.core.Pokemon
import hk.tikhang.champions.core.SpeedEngine
import hk.tikhang.champions.core.SpeedEngine.Conditions
import hk.tikhang.champions.core.SpeedEngine.Spread
import hk.tikhang.champions.core.SpeedEngine.Weather
import hk.tikhang.champions.core.Stat
import hk.tikhang.champions.core.TeamAnalysisEngine
import hk.tikhang.champions.core.toId
import hk.tikhang.champions.data.AppViewModel
import hk.tikhang.champions.data.DataState

/** Speed Tier Analysis (spec §10). teamId = 0 → research mode without a team. */
@Composable
fun SpeedTierScreen(vm: AppViewModel, state: DataState, teamId: Long, onBack: () -> Unit, openPokemon: (String) -> Unit) {
    val s = state.snapshot
    val teams by vm.teams.collectAsStateWithLifecycle()
    val team = if (teamId == 0L) null else teams.firstOrNull { it.id == teamId }?.team()
    val reg = team?.let { s.regulationById[it.regulationId] } ?: state.regulation
    val pool = remember(reg) { s.pokemon.filter { it.teamSelectable && (reg.allowedPokemon == null || it.id in reg.allowedPokemon!!) } }

    var spread by rememberSaveable { mutableStateOf(Spread.MAX_PLUS) }
    var stage by rememberSaveable { mutableIntStateOf(0) }
    var tailwind by rememberSaveable { mutableStateOf(false) }
    var trickRoom by rememberSaveable { mutableStateOf(false) }
    var paralysis by rememberSaveable { mutableStateOf(false) }
    var scarf by rememberSaveable { mutableStateOf(false) }
    var weather by rememberSaveable { mutableStateOf(Weather.NONE) }
    var abilityActive by rememberSaveable { mutableStateOf(false) }
    var oppScarf by rememberSaveable { mutableStateOf(false) }
    var useMega by rememberSaveable { mutableStateOf(true) }
    var query by rememberSaveable { mutableStateOf("") }
    var compareId by rememberSaveable { mutableStateOf<String?>(null) }

    val c = Conditions(stage, tailwind, paralysis, scarf, false, weather, abilityActive, trickRoom)
    val members = remember(team, useMega) { team?.let { TeamAnalysisEngine.members(s, it, useMega) }.orEmpty() }
    val list = remember(pool, spread, members, c, oppScarf) { SpeedEngine.tierList(pool, spread, members, c, oppScarf) }
    val shown = list.filter { e -> query.isBlank() || e.teamIndex != null || s.displayName(e.pokemon).contains(query.trim()) || toId(e.pokemon.name).contains(toId(query)) }

    Screen("Speed Tier", onBack, subtitle = (team?.name?.let { "$it・" } ?: "") + "${reg.id}・${pool.size} 隻") { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (members.isNotEmpty()) item {
                val syn = TeamAnalysisEngine.synergy(members)
                val sum = SpeedEngine.summary(s, pool, members, syn)
                SectionCard("速度摘要（Lv50）") {
                    sum.fastest?.let { (m, v) -> LabelValue("最快", "${m.name} $v") }
                    sum.slowest?.let { (m, v) -> LabelValue("最慢", "${m.name} $v") }
                    LabelValue("快（>75%）", sum.fast.joinToString("、") { it.name }.ifEmpty { "沒有" })
                    LabelValue("慢（<25%）", sum.slow.joinToString("、") { it.name }.ifEmpty { "沒有" })
                    LabelValue("速度控制", sum.speedControl.joinToString("、").ifEmpty { "沒有" })
                    sum.advantages.forEach { Text("・$it", style = MaterialTheme.typography.bodySmall, color = Color(0xFF2E7D32)) }
                    sum.disadvantages.forEach { Text("・$it", style = MaterialTheme.typography.bodySmall, color = Color(0xFFC62828)) }
                    sum.gaps.forEach { Text("・$it", style = MaterialTheme.typography.bodySmall, color = Color(0xFFB26A00)) }
                    Text("百分比以對方 ${Spread.MAX_PLUS.zh} 計算，未計戰鬥中的條件。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                SectionCard("條件") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Picker("對手配置：${spread.zh}", Spread.entries.toList(), { it.zh }) { spread = it }
                        FilterChip(selected = oppScarf, onClick = { oppScarf = !oppScarf }, label = { Text("對手講究圍巾") })
                        FilterChip(selected = trickRoom, onClick = { trickRoom = !trickRoom }, label = { Text("戲法空間") })
                    }
                    if (members.isNotEmpty()) {
                        Text("我方條件", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            FilterChip(selected = tailwind, onClick = { tailwind = !tailwind }, label = { Text("順風") })
                            FilterChip(selected = scarf, onClick = { scarf = !scarf }, label = { Text("講究圍巾") })
                            FilterChip(selected = paralysis, onClick = { paralysis = !paralysis }, label = { Text("麻痺") })
                            FilterChip(selected = abilityActive, onClick = { abilityActive = !abilityActive }, label = { Text("輕裝／飛毛腿發動") })
                            Picker("天氣：${weather.zh}", Weather.entries.toList(), { it.zh }) { weather = it }
                            Picker("能力等級：${if (stage > 0) "+" else ""}$stage", (-6..6).toList(), { (if (it > 0) "+" else "") + it }) { stage = it }
                            if (team?.members?.any { it.megaFormId != null } == true) {
                                FilterChip(selected = useMega, onClick = { useMega = !useMega }, label = { Text(if (useMega) "Mega 形態" else "原本形態") })
                            }
                        }
                        if (SpeedEngine.isEstimated(c)) Text("多個修正同時生效：數值為估算（Estimated）。", style = MaterialTheme.typography.bodySmall, color = Color(0xFFB26A00))
                        Text("天氣特性（Swift Swim 等）只在相應天氣生效。Quick Feet 會無視麻痺減速。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            compareId?.let { id -> s.pokemonById[id]?.let { p -> item { CompareCard(state, p, members, c) { compareId = null } } } }
            item {
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, label = { Text("搜尋 Pokémon") }, modifier = Modifier.fillMaxWidth())
            }
            item {
                Text(
                    (if (trickRoom) "戲法空間：由慢至快（慢者先行動）" else "由快至慢") + "・點擊 Pokémon 與隊伍比較速度",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(shown, key = { (it.teamIndex?.let { i -> "team$i" } ?: it.pokemon.id) }) { e -> TierRow(state, e) { if (e.teamIndex == null) compareId = e.pokemon.id else openPokemon(e.pokemon.id) } }
        }
    }
}

@Composable
private fun TierRow(state: DataState, e: SpeedEngine.TierEntry, onClick: () -> Unit) {
    val mine = e.teamIndex != null
    Row(
        Modifier.fillMaxWidth().background(if (mine) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("${e.speed}", Modifier.width(44.dp), fontWeight = FontWeight.Bold)
        Column(Modifier.weight(1f)) {
            Text(if (mine) "★ ${e.label}" else state.snapshot.displayName(e.pokemon), style = MaterialTheme.typography.bodyMedium, fontWeight = if (mine) FontWeight.Bold else FontWeight.Normal)
            Text("種族值 ${e.pokemon.baseStats.spe}" + if (mine) "・我方實際配置" else "", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TypeRow(e.pokemon.types)
    }
}

@Composable
private fun CompareCard(state: DataState, p: Pokemon, members: List<TeamAnalysisEngine.Member>, c: Conditions, onClose: () -> Unit) {
    SectionCard("速度比較：${state.snapshot.displayName(p)}") {
        Row(Modifier.fillMaxWidth()) {
            Text("對方配置", Modifier.weight(1.6f), fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text("速度", Modifier.weight(0.7f), fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text("我方較快", Modifier.weight(1.7f), fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        Spread.entries.forEach { sp ->
            val v = SpeedEngine.benchmark(p.baseStats.spe, sp)
            val faster = members.filter { m -> val mv = SpeedEngine.effective(m.stats[Stat.SPE] ?: 0, m.ability, c); if (c.trickRoom) mv < v else mv > v }
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(sp.zh, Modifier.weight(1.6f), fontSize = 12.sp)
                Text("$v", Modifier.weight(0.7f), fontSize = 12.sp)
                Text(if (members.isEmpty()) "—" else faster.joinToString("、") { it.name }.ifEmpty { "沒有" }, Modifier.weight(1.7f), fontSize = 12.sp)
            }
        }
        TextButton(onClick = onClose) { Text("關閉比較") }
    }
}
