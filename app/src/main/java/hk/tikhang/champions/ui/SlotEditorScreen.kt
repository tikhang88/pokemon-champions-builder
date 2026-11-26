@file:OptIn(ExperimentalLayoutApi::class)

package hk.tikhang.champions.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hk.tikhang.champions.core.LegalityEngine
import hk.tikhang.champions.core.MAX_MOVES
import hk.tikhang.champions.core.Move
import hk.tikhang.champions.core.RegulationEngine
import hk.tikhang.champions.core.Stat
import hk.tikhang.champions.core.StatsEngine
import hk.tikhang.champions.core.TeamEngine
import hk.tikhang.champions.core.TeamSlot
import hk.tikhang.champions.core.toId
import hk.tikhang.champions.data.AppViewModel
import hk.tikhang.champions.data.DataState
import java.time.Instant

/** Official Traditional Chinese nature names (Showdown has no zh-tw nature text). */
val NatureZh: Map<String, String> = mapOf(
    "hardy" to "勤奮", "lonely" to "怕寂寞", "brave" to "勇敢", "adamant" to "固執", "naughty" to "頑皮",
    "bold" to "大膽", "docile" to "坦率", "relaxed" to "悠閒", "impish" to "淘氣", "lax" to "樂天",
    "timid" to "膽小", "hasty" to "急躁", "serious" to "認真", "jolly" to "爽朗", "naive" to "天真",
    "modest" to "內斂", "mild" to "慢吞吞", "quiet" to "冷靜", "bashful" to "害羞", "rash" to "馬虎",
    "calm" to "溫和", "gentle" to "溫順", "sassy" to "自大", "careful" to "慎重", "quirky" to "浮躁",
)

@Composable
fun SlotEditorScreen(vm: AppViewModel, state: DataState, teamId: Long, index: Int, onBack: () -> Unit, changeSpecies: () -> Unit) {
    val teams by vm.teams.collectAsStateWithLifecycle()
    val team = teams.firstOrNull { it.id == teamId }?.team()
    val slot = team?.slots?.getOrNull(index)
    val s = state.snapshot
    if (team == null || slot == null) {
        Screen("隊員", onBack) { pad -> Text("載入中…", Modifier.padding(pad).padding(16.dp)) }
        return
    }
    val species = TeamEngine.species(s, slot)
    val form = TeamEngine.effectiveForm(s, slot)
    if (species == null || form == null) {
        Screen("隊員", onBack) { pad -> Text("資料內沒有「${slot.speciesId}」", Modifier.padding(pad).padding(16.dp)) }
        return
    }
    fun update(f: (TeamSlot) -> TeamSlot) = vm.saveTeam(teamId, team.withSlot(index, f(slot)))
    val report = LegalityEngine.check(s, team).forSlot(index)
    val allowed = s.regulationById[team.regulationId]?.allowedPokemon?.toSet()
    var itemPicker by remember { mutableStateOf(false) }
    var movePickerFor by remember { mutableStateOf<Int?>(null) }

    Screen(s.displayName(form), onBack, subtitle = "${team.name}・第 ${index + 1} 位") { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (report.isNotEmpty()) item {
                SectionCard { report.forEach { Text("・${it.message}", color = issueColor(it.level), style = MaterialTheme.typography.bodySmall) } }
            }
            item {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.displayName(species), style = MaterialTheme.typography.titleMedium)
                            Text("${dex(species.num)}・${species.name}", style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedButton(onClick = changeSpecies) { Text("更換") }
                    }
                    TypeRow(form.types)
                }
            }
            // Mega Toggle (spec §6.1, §6.5): updates stone, types, ability and stats together.
            val megas = s.megaOptions(species)
            if (megas.isNotEmpty()) item {
                SectionCard("Mega Evolution") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = slot.megaFormId == null, onClick = { update { TeamEngine.toggleMega(s, it, null) } }, label = { Text("不 Mega") })
                        megas.forEach { m ->
                            val ok = allowed == null || m.id in allowed
                            FilterChip(
                                selected = slot.megaFormId == m.id, onClick = { update { TeamEngine.toggleMega(s, it, m.id) } },
                                label = { Text(s.displayName(m) + if (ok) "" else "（不可用）") },
                            )
                        }
                    }
                    if (slot.megaFormId != null) Text("已自動裝備 ${s.itemById[slot.item ?: ""]?.let { it.nameZh ?: it.name } ?: slot.item}", style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                SectionCard("特性・道具・性格") {
                    if (form.isMega) {
                        LabelValue("Mega 後特性", form.abilities.joinToString { s.ability(it)?.nameZh ?: it })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (form.isMega) "Mega 前特性" else "特性", Modifier.width(96.dp))
                        Picker(slot.ability?.let { a -> s.ability(a)?.nameZh ?: a } ?: "選擇", species.abilities, { a -> s.ability(a)?.nameZh?.let { "$it（$a）" } ?: a }) { a ->
                            update { it.copy(ability = a) }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("道具", Modifier.width(96.dp))
                        OutlinedButton(onClick = { itemPicker = true }) { Text(slot.item?.let { s.itemById[it]?.let { i -> i.nameZh ?: i.name } ?: it } ?: "沒有道具") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("性格", Modifier.width(96.dp))
                        Picker(natureLabel(s, slot.nature), sortedNatureIds(s), { natureLabel(s, it) }) { n -> update { it.copy(nature = n) } }
                    }
                }
            }
            item { SpEditor(state, slot) { st, v -> update { it.copy(sp = it.sp + (st to v)) } } }
            item {
                SectionCard("招式（${slot.moves.size}/$MAX_MOVES）") {
                    if (species.hasPlaceholderLearnset) Text("此 Pokémon 的招式表暫用 Scarlet/Violet 資料，待 Champions 資料確認。", color = Color(0xFFB26A00), style = MaterialTheme.typography.bodySmall)
                    (0 until MAX_MOVES).forEach { i ->
                        val m = slot.moves.getOrNull(i)?.let { s.moveById[it] }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = { movePickerFor = i }, Modifier.weight(1f)) {
                                Text(m?.let { it.nameZh ?: it.name } ?: slot.moves.getOrNull(i) ?: "選擇招式 ${i + 1}")
                            }
                            if (i < slot.moves.size) TextButton(onClick = { update { it.copy(moves = it.moves.filterIndexed { j, _ -> j != i }) } }) { Text("移除") }
                        }
                        m?.let { MoveSummary(it) }
                    }
                }
            }
        }
    }

    if (itemPicker) ItemPickerDialog(state, onPick = { id -> update { it.copy(item = id) }; itemPicker = false }) { itemPicker = false }
    movePickerFor?.let { i ->
        MovePickerDialog(state, species.id, exclude = slot.moves.toSet(), onPick = { id ->
            update { sl -> sl.copy(moves = if (i < sl.moves.size) sl.moves.mapIndexed { j, mv -> if (j == i) id else mv } else (sl.moves + id).take(MAX_MOVES)) }
            movePickerFor = null
        }) { movePickerFor = null }
    }
}

/** Natures grouped by the stat they raise (攻擊, 防禦, 特攻, 特防, 速度); neutral natures last. */
fun sortedNatureIds(s: hk.tikhang.champions.core.Snapshot): List<String> {
    val order = Stat.entries.map { it.key }
    return s.natures.sortedWith(compareBy(
        { n -> if (n.plus == null || n.plus == n.minus) 99 else order.indexOf(n.plus) },
        { n -> if (n.minus == null) 99 else order.indexOf(n.minus) },
        { n -> NatureZh[n.id] ?: n.name },
    )).map { it.id }
}

fun natureLabel(s: hk.tikhang.champions.core.Snapshot, id: String): String {
    val n = s.natures.firstOrNull { it.id == id } ?: return id
    val zh = NatureZh[id] ?: n.name
    if (n.plus == null || n.plus == n.minus) return "$zh（無影響）"
    val plus = Stat.entries.firstOrNull { it.key == n.plus }?.zh; val minus = Stat.entries.firstOrNull { it.key == n.minus }?.zh
    return "$zh（$plus↑ $minus↓）"
}

@Composable
fun SpEditor(state: DataState, slot: TeamSlot, set: (Stat, Int) -> Unit) {
    val s = state.snapshot
    val stats = TeamEngine.finalStats(s, slot)
    val remaining = StatsEngine.MAX_SP_TOTAL - slot.spTotal
    SectionCard("Stat Points（${slot.spTotal}/${StatsEngine.MAX_SP_TOTAL}，每項最多 ${StatsEngine.MAX_SP_PER_STAT}）") {
        Row(Modifier.fillMaxWidth()) {
            listOf("能力", "SP", "", "Lv50").forEachIndexed { i, h ->
                Text(h, Modifier.weight(if (i == 2) 2.4f else 1f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
            }
        }
        Stat.entries.forEach { st ->
            val v = slot.spOf(st)
            val effect = TeamEngine.natureEffect(s, slot.nature, st)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(st.zh + effect.zh, Modifier.weight(1f), color = when (effect) {
                    StatsEngine.NatureEffect.PLUS -> Color(0xFFC62828); StatsEngine.NatureEffect.MINUS -> Color(0xFF1565C0); else -> Color.Unspecified
                })
                Text("$v", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Row(Modifier.weight(2.4f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    SmallButton("0") { set(st, 0) }
                    SmallButton("−") { if (v > 0) set(st, v - 1) }
                    SmallButton("+") { if (v < StatsEngine.MAX_SP_PER_STAT && remaining > 0) set(st, v + 1) }
                    SmallButton("最大") { set(st, minOf(StatsEngine.MAX_SP_PER_STAT, v + remaining)) }
                }
                Text("${stats[st] ?: "—"}", Modifier.weight(1f))
            }
        }
        HorizontalDivider()
        Text("剩餘 $remaining SP。Lv50 數值已計算 Mega、性格及 SP。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SmallButton(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) { Text(label) }
}

@Composable
fun MoveSummary(m: Move) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)) {
        TypeChip(m.type)
        Text(
            "${CategoryZh[m.category] ?: m.category}・威力 ${if (m.basePower > 0) m.basePower else "—"}・命中 ${m.accuracyPercent ?: "必中"}" +
                if (m.priority != 0) "・優先度 ${m.priority}" else "",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun SearchDialog(title: String, onDismiss: () -> Unit, content: @Composable (query: String) -> Unit) {
    var q by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = q, onValueChange = { q = it }, singleLine = true, label = { Text("搜尋") }, modifier = Modifier.fillMaxWidth())
                Column(Modifier.heightIn(max = 420.dp)) { content(q.trim()) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("關閉") } },
    )
}

@Composable
fun ItemPickerDialog(state: DataState, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    val s = state.snapshot
    SearchDialog("選擇道具", onDismiss) { q ->
        val list = remember(q) {
            s.items.filter { q.isEmpty() || it.name.contains(q, true) || it.nameZh?.contains(q) == true }
                .sortedWith(compareBy({ it.isMegaStone }, { it.nameZh ?: it.name }))
        }
        LazyColumn {
            item { Text("沒有道具", Modifier.fillMaxWidth().clickable { onPick(null) }.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.primary) }
            items(list, key = { it.id }) { entry ->
                Column(Modifier.fillMaxWidth().clickable { onPick(entry.id) }.padding(vertical = 8.dp)) {
                    Text((entry.nameZh ?: entry.name) + if (entry.nameZh != null) "（${entry.name}）" else "", style = MaterialTheme.typography.bodyMedium)
                    (entry.shortDescZh ?: entry.shortDesc)?.let { d -> Text(d, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

@Composable
fun MovePickerDialog(state: DataState, speciesId: String, exclude: Set<String>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val s = state.snapshot
    val p = s.pokemonById[speciesId]
    val learnset = (s.learnsets[speciesId] ?: s.learnsets[p?.baseId ?: ""]).orEmpty()
    val now = remember { Instant.now() }
    val upcoming = remember(speciesId) { RegulationEngine.upcomingNewMoves(s, speciesId, now).map { it.second }.toSet() + RegulationEngine.upcomingNewMoves(s, p?.baseId ?: "", now).map { it.second } }
    SearchDialog("選擇招式（${learnset.size}）", onDismiss) { q ->
        val qid = toId(q)
        val list = remember(q) {
            learnset.mapNotNull { s.moveById[it] }.filter { it.id !in exclude }
                .filter { q.isEmpty() || it.nameZh?.contains(q) == true || toId(it.name).contains(qid) || (TypeZh[it.type] ?: "").contains(q) }
                .sortedWith(compareBy({ it.category == "Status" }, { it.type }, { -it.basePower }))
        }
        LazyColumn {
            if (upcoming.isNotEmpty()) item {
                Text("12 月 2 日起新增（未可選）：" + upcoming.joinToString("、") { id -> s.moveById[id]?.let { it.nameZh ?: it.name } ?: id },
                    style = MaterialTheme.typography.bodySmall, color = Color(0xFFB26A00))
            }
            items(list, key = { it.id }) { m ->
                Column(Modifier.fillMaxWidth().clickable { onPick(m.id) }.padding(vertical = 8.dp)) {
                    Text((m.nameZh ?: m.name) + "（${m.name}）", style = MaterialTheme.typography.bodyMedium)
                    MoveSummary(m)
                }
            }
        }
    }
}
