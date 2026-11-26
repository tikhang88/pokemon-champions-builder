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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hk.tikhang.champions.core.SpeedEngine
import hk.tikhang.champions.core.SpeedLineEngine
import hk.tikhang.champions.core.SpeedLineEngine.Line
import hk.tikhang.champions.core.Stat
import hk.tikhang.champions.core.TeamAnalysisEngine
import hk.tikhang.champions.core.toId
import hk.tikhang.champions.data.AppViewModel
import hk.tikhang.champions.data.DataState

private val FasterColor = Color(0xFF2E7D32)
private val TieColor = Color(0xFFB26A00)
private val SlowerColor = Color(0xFFC62828)

/** My-side boost applied to the chosen team member's actual Speed. */
private enum class MyBoost(val zh: String, val c: SpeedEngine.Conditions) {
    NONE("無", SpeedEngine.Conditions()),
    SCARF("講究圍巾", SpeedEngine.Conditions(choiceScarf = true)),
    PLUS1("+1", SpeedEngine.Conditions(stage = 1)),
    PLUS2("+2", SpeedEngine.Conditions(stage = 2)),
    TAILWIND("順風", SpeedEngine.Conditions(tailwind = true)),
    ABILITY("速度特性發動（天氣／輕裝等）", SpeedEngine.Conditions(abilityActive = true)),
    MINUS1("−1", SpeedEngine.Conditions(stage = -1)),
    PARALYSIS("麻痺", SpeedEngine.Conditions(paralysis = true)),
}

/**
 * Speed lines (速度線): every Pokémon's common Speed benchmarks in one ordered line, so you can
 * see which turn orders are possible. teamId = 0 → research mode without a team.
 */
@Composable
fun SpeedLinesScreen(vm: AppViewModel, state: DataState, teamId: Long, onBack: () -> Unit, openPokemon: (String) -> Unit) {
    val s = state.snapshot
    val teams by vm.teams.collectAsStateWithLifecycle()
    val team = if (teamId == 0L) null else teams.firstOrNull { it.id == teamId }?.team()
    val reg = team?.let { s.regulationById[it.regulationId] } ?: state.regulation
    val pool = remember(reg) { s.pokemon.filter { it.teamSelectable && (reg.allowedPokemon == null || it.id in reg.allowedPokemon!!) } }
    val abilityName: (String) -> String = { id -> s.abilityById[id]?.let { it.nameZh ?: it.name } ?: id }

    var selected by rememberSaveable { mutableStateOf(SpeedLineEngine.DEFAULT_LINES.map { it.name }.toSet()) }
    val lines = selected.map { Line.valueOf(it) }.toSet()
    var trickRoom by rememberSaveable { mutableStateOf(false) }
    var byPokemon by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var memberIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var boost by rememberSaveable { mutableStateOf(MyBoost.NONE) }
    var custom by rememberSaveable { mutableStateOf("") }

    val members = remember(team) { team?.let { TeamAnalysisEngine.members(s, it) }.orEmpty() }
    val member = members.firstOrNull { it.index == memberIndex }
    // My speed: a team member (with a boost) or a number typed in.
    val mySpeed: Int? = member?.let { m ->
        val c = if (boost == MyBoost.ABILITY) SpeedLineEngine.abilityConditions(toId(m.ability ?: "")) else boost.c
        SpeedEngine.effective(m.stats[Stat.SPE] ?: 0, m.ability, c)
    } ?: custom.toIntOrNull()
    val myLabel = member?.let { it.name + if (boost != MyBoost.NONE) "（${boost.zh}）" else "" } ?: "自訂速度"

    val all = remember(pool, selected, trickRoom) { SpeedLineEngine.lines(pool, lines, trickRoom, abilityName) }
    val q = query.trim()
    val matches: (hk.tikhang.champions.core.Pokemon) -> Boolean = { p -> q.isEmpty() || s.displayName(p).contains(q) || toId(p.name).contains(toId(q)) }
    val shown = all.filter { matches(it.pokemon) }
    val cmp = mySpeed?.let { SpeedLineEngine.compare(it, all) }

    fun colorFor(speed: Int): Color? = mySpeed?.let { m ->
        val iAmFirst = if (trickRoom) m < speed else m > speed
        when { m == speed -> TieColor; iAmFirst -> FasterColor; else -> SlowerColor }
    }

    Screen("速度線", onBack, subtitle = (team?.name?.let { "$it・" } ?: "") + "${reg.id}・${pool.size} 隻・Lv50") { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                SectionCard("顯示的速度線") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Line.entries.forEach { l ->
                            FilterChip(selected = l.name in selected, label = { Text(l.zh) }, onClick = {
                                selected = if (l.name in selected) selected - l.name else selected + l.name
                            })
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = trickRoom, onClick = { trickRoom = !trickRoom }, label = { Text("戲法空間") })
                        FilterChip(selected = byPokemon, onClick = { byPokemon = !byPokemon }, label = { Text("逐隻顯示") })
                        TextButton(onClick = { selected = SpeedLineEngine.DEFAULT_LINES.map { it.name }.toSet() }) { Text("預設") }
                        TextButton(onClick = { selected = Line.entries.map { it.name }.toSet() }) { Text("全部") }
                    }
                    Text(
                        "極速＝32 SP＋提升速度的性格；準速＝32 SP。「極速＋特性」只列出有速度特性的 Pokémon（例如下雨時的悠游自如 ×2）。" +
                            "同速時先後次序隨機（各 50%）。" + if (trickRoom) "戲法空間下慢者先行動，建議顯示「最慢」。" else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                SectionCard("我的速度") {
                    if (members.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            members.forEach { m ->
                                FilterChip(selected = memberIndex == m.index, onClick = { memberIndex = if (memberIndex == m.index) null else m.index; custom = "" },
                                    label = { Text("${m.name} ${m.stats[Stat.SPE] ?: 0}") })
                            }
                        }
                        if (member != null) Picker("我方條件：${boost.zh}", MyBoost.entries.toList(), { it.zh }) { boost = it }
                    }
                    OutlinedTextField(
                        value = custom, singleLine = true, label = { Text("或輸入速度數值") },
                        onValueChange = { v -> custom = v.filter(Char::isDigit).take(4); if (custom.isNotEmpty()) memberIndex = null },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                    )
                    if (mySpeed != null && cmp != null) {
                        Text("$myLabel：速度 $mySpeed", style = MaterialTheme.typography.titleSmall)
                        val (first, last) = if (trickRoom) cmp.slower to cmp.faster else cmp.faster to cmp.slower
                        Text("先於 $first 條速度線", color = FasterColor, style = MaterialTheme.typography.bodyMedium)
                        Text("同速 ${cmp.ties} 條（先後各 50%）", color = TieColor, style = MaterialTheme.typography.bodyMedium)
                        Text("後於 $last 條速度線", color = SlowerColor, style = MaterialTheme.typography.bodyMedium)
                        Text("顏色：綠＝我方先行動、橙＝同速、紅＝對方先行動。未計先制度。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text("選擇隊員或輸入數值，速度線會以顏色顯示誰先行動。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, label = { Text("搜尋 Pokémon") }, modifier = Modifier.fillMaxWidth())
            }
            if (lines.isEmpty()) {
                item { Text("請選擇最少一條速度線。", style = MaterialTheme.typography.bodyMedium) }
            } else if (byPokemon) {
                val groups = shown.groupBy { it.pokemon.id }
                val order = groups.keys.sortedByDescending { s.pokemonById[it]?.baseStats?.spe ?: 0 }
                items(order, key = { it }) { id ->
                    val entries = groups.getValue(id).sortedByDescending { it.speed }
                    PokemonLinesCard(state, entries, ::colorFor) { openPokemon(id) }
                }
            } else {
                item {
                    Text(
                        (if (trickRoom) "戲法空間：由慢至快（慢者先行動）" else "由快至慢") + "・${shown.size} 條",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // My speed is inserted as a marker where it falls in the line.
                val markerAt = mySpeed?.let { m -> shown.indexOfFirst { if (trickRoom) it.speed > m else it.speed < m }.let { if (it < 0) shown.size else it } }
                shown.forEachIndexed { i, e ->
                    if (i == markerAt) item(key = "me") { MyMarker(myLabel, mySpeed!!) }
                    item(key = "${e.pokemon.id}-${e.line.name}-${e.note}") { LineRow(state, e, colorFor(e.speed)) { openPokemon(e.pokemon.id) } }
                }
                if (markerAt == shown.size && mySpeed != null) item(key = "me") { MyMarker(myLabel, mySpeed) }
            }
        }
    }
}

@Composable
private fun MyMarker(label: String, speed: Int) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("$speed", Modifier.width(52.dp), fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text("◀ $label", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun LineRow(state: DataState, e: SpeedLineEngine.Entry, color: Color?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("${e.speed}", Modifier.width(52.dp), fontWeight = FontWeight.Bold, fontSize = 18.sp, color = color ?: MaterialTheme.colorScheme.onSurface)
        Column(Modifier.weight(1f)) {
            Text(state.snapshot.displayName(e.pokemon), style = MaterialTheme.typography.bodyMedium)
            Text(e.line.zh + (e.note?.let { "・$it" } ?: "") + "・種族值 ${e.pokemon.baseStats.spe}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TypeRow(e.pokemon.types)
    }
}

@Composable
private fun PokemonLinesCard(state: DataState, entries: List<SpeedLineEngine.Entry>, colorFor: (Int) -> Color?, onClick: () -> Unit) {
    val p = entries.first().pokemon
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.snapshot.displayName(p), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text("種族值 ${p.baseStats.spe}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TypeRow(p.types)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                entries.forEach { e ->
                    val c = colorFor(e.speed)
                    Column(
                        Modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("${e.speed}", fontWeight = FontWeight.Bold, color = c ?: MaterialTheme.colorScheme.onSurface)
                        Text(shortLabel(e), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun shortLabel(e: SpeedLineEngine.Entry): String = when (e.line) {
    Line.PLUS2 -> "+2"; Line.TAILWIND -> "順風"; Line.SCARF -> "圍巾"; Line.PLUS1 -> "+1"
    Line.MAX_PLUS -> "極速"; Line.MAX -> "準速"; Line.NEUTRAL -> "無投資"; Line.MINUS1 -> "−1"; Line.MIN -> "最慢"
    Line.ABILITY -> e.note?.substringAfter("・") ?: "特性"
}
