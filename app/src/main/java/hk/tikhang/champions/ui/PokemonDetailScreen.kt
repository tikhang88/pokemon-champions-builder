@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package hk.tikhang.champions.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hk.tikhang.champions.core.Move
import hk.tikhang.champions.core.Pokemon
import hk.tikhang.champions.core.Stat
import hk.tikhang.champions.core.StatsEngine
import hk.tikhang.champions.core.toId
import hk.tikhang.champions.data.DataState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val Tabs = listOf("概覽", "能力值", "招式", "特性", "形態")
private val MegaPurple = Color(0xFF8E24AA)

@Composable
fun PokemonDetailScreen(state: DataState, id: String, onBack: () -> Unit, open: (String) -> Unit) {
    val s = state.snapshot
    val requested = s.pokemonById[id]
    if (requested == null) {
        Screen("找不到 Pokémon", onBack) { pad -> Text("資料內沒有「$id」", Modifier.padding(pad).padding(16.dp)) }
        return
    }
    // The page is about the base form; Mega forms are reached with the Mega Toggle (spec §6.1).
    val base = if (requested.isMega) s.pokemonById[requested.megaOf] ?: requested else requested
    val megas = remember(base.id) { s.megasOf(base.id) }
    var formId by rememberSaveable(id) { mutableStateOf(requested.id) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val form = s.pokemonById[formId] ?: base

    Screen(s.displayName(form), onBack, subtitle = "${dex(form.num)}・${form.name}") { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (megas.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Mega", style = MaterialTheme.typography.labelLarge, color = MegaPurple)
                    FilterChip(selected = formId == base.id, onClick = { formId = base.id }, label = { Text("原本") })
                    megas.forEach { m ->
                        val label = m.forme?.substringAfter("Mega", "")?.trim('-')?.ifEmpty { null }?.let { "Mega $it" } ?: "Mega"
                        FilterChip(selected = formId == m.id, onClick = { formId = m.id }, label = { Text(label) })
                    }
                }
            }
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                Tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (tab) {
                    0 -> overview(state, form)
                    1 -> stats(form, base)
                    2 -> moves(state, form)
                    3 -> abilities(state, form)
                    else -> formes(state, base, megas, open)
                }
            }
        }
    }
}

// ---- Overview ---------------------------------------------------------------------------

private fun LazyListScope.overview(state: DataState, p: Pokemon) {
    val s = state.snapshot
    item {
        SectionCard {
            TypeRow(p.types)
            LabelValue("特性", p.abilities.joinToString("、") { s.ability(it)?.nameZh ?: it })
            p.forme?.let { LabelValue("形態", it) }
            if (p.isMega) LabelValue("Mega Stone", p.megaStone?.let { st -> s.itemById[toId(st)]?.nameZh?.let { "$it（$st）" } ?: st } ?: "—")
            p.heightM?.let { LabelValue("身高／體重", "$it m／${p.weightKg ?: "—"} kg") }
            LabelValue("驗證狀態", "")
            VerificationBadge(p.verification)
        }
    }
    item {
        SectionCard("Regulation 狀態") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                s.regulations.forEach { r ->
                    when {
                        !r.hasPokemonList -> Badge("${r.id} 未有名單", Color(0xFF9E9E9E))
                        r.id in p.legalIn -> Badge("${r.id} 可用", Color(0xFF2E7D32))
                        else -> Badge("${r.id} 不可用", Color(0xFFC62828))
                    }
                }
            }
            if (p.isMega) Text("每場對戰只可 Mega Evolution 一次，並需持有對應 Mega Stone。", style = MaterialTheme.typography.bodySmall)
        }
    }
    item {
        val profile = state.typeEngine.defensiveProfile(p.types)
        SectionCard("防守屬性相性") {
            listOf(4.0 to "4 倍", 2.0 to "2 倍", 0.5 to "½ 倍", 0.25 to "¼ 倍", 0.0 to "無效").forEach { (m, label) ->
                val ts = profile.filterValues { it == m }.keys.toList()
                if (ts.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, Modifier.width(56.dp), style = MaterialTheme.typography.bodyMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { ts.forEach { TypeChip(it) } }
                    }
                }
            }
            p.abilities.mapNotNull { a -> state.typeEngine.abilityImmunity(a)?.let { a to it } }.forEach { (a, t) ->
                Text("特性「${s.ability(a)?.nameZh ?: a}」可令 ${TypeZh[t]} 屬性招式無效（上表未計算特性）", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// ---- Stats (spec §5.3, §6.3) -----------------------------------------------------------

private fun LazyListScope.stats(form: Pokemon, base: Pokemon) {
    item {
        SectionCard("種族值（Lv50 範圍：0 SP 減益性格 → 32 SP 增益性格）") {
            Stat.entries.forEach { st -> StatBar(st, form.baseStats[st], StatsEngine.range(st, form.baseStats[st])) }
            HorizontalDivider()
            LabelValue("總和", form.baseStats.total.toString())
        }
    }
    if (form.isMega) {
        item {
            SectionCard("原本 vs Mega") {
                Row(Modifier.fillMaxWidth()) {
                    listOf("能力", "原本", "Mega", "差異").forEach { Text(it, Modifier.weight(1f), fontWeight = FontWeight.Bold) }
                }
                Stat.entries.forEach { st ->
                    val a = base.baseStats[st]; val b = form.baseStats[st]; val d = b - a
                    Row(Modifier.fillMaxWidth()) {
                        Text(st.zh, Modifier.weight(1f)); Text("$a", Modifier.weight(1f)); Text("$b", Modifier.weight(1f))
                        Text(if (d > 0) "+$d" else "$d", Modifier.weight(1f),
                            color = when { d > 0 -> Color(0xFF2E7D32); d < 0 -> Color(0xFFC62828); else -> MaterialTheme.colorScheme.onSurfaceVariant })
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth()) {
                    Text("總和", Modifier.weight(1f)); Text("${base.baseStats.total}", Modifier.weight(1f))
                    Text("${form.baseStats.total}", Modifier.weight(1f)); Text("+${form.baseStats.total - base.baseStats.total}", Modifier.weight(1f))
                }
                if (base.types != form.types) Text("屬性改變：${base.types.joinToString("/") { TypeZh[it] ?: it }} → ${form.types.joinToString("/") { TypeZh[it] ?: it }}", color = MegaPurple)
                if (base.abilities != form.abilities) Text("特性改為：${form.abilities.joinToString("、")}", color = MegaPurple)
            }
        }
    }
    item {
        Text(
            "Champions 能力值公式：HP = 種族值 + SP + 75；其他 = ⌊(種族值 + SP + 20) × 性格⌋。每隻最多 66 SP，每項最多 32 SP，沒有 IV。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatBar(st: Stat, value: Int, range: IntRange) {
    val color = when {
        value >= 130 -> Color(0xFF2E7D32); value >= 100 -> Color(0xFF7CB342); value >= 70 -> Color(0xFFFBC02D); else -> Color(0xFFE64A19)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(st.zh, Modifier.width(40.dp), style = MaterialTheme.typography.bodyMedium)
        Text("$value", Modifier.width(36.dp), fontWeight = FontWeight.Bold)
        Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            Box(Modifier.fillMaxWidth((value / 200f).coerceIn(0.02f, 1f)).height(10.dp).background(color))
        }
        Text("${range.first}–${range.last}", Modifier.width(72.dp).padding(start = 8.dp), style = MaterialTheme.typography.bodySmall)
    }
}

// ---- Moves (spec §5.3) ------------------------------------------------------------------

private fun LazyListScope.moves(state: DataState, p: Pokemon) {
    val s = state.snapshot
    val ids = s.learnsets[p.id] ?: s.learnsets[p.megaOf ?: ""] ?: emptyList()
    val list = ids.mapNotNull { s.moveById[it] }.sortedWith(compareBy({ it.category == "Status" }, { it.type }, { -it.basePower }))
    item {
        Text("${list.size} 個可學招式（Champions 招式表）・Mega 形態沿用原本形態的招式表", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    items(list, key = { it.id }) { MoveRow(it) }
}

@Composable
private fun MoveRow(m: Move) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(m.nameZh ?: m.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TypeChip(m.type)
            Badge(CategoryZh[m.category] ?: m.category, MaterialTheme.colorScheme.secondary)
        }
        Text(m.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("威力 ${if (m.basePower > 0) m.basePower else "—"}", style = MaterialTheme.typography.bodySmall)
            Text("命中 ${m.accuracyPercent?.toString() ?: "必中"}", style = MaterialTheme.typography.bodySmall)
            Text("PP ${m.maxPp}", style = MaterialTheme.typography.bodySmall)
            if (m.priority != 0) Text("優先度 ${if (m.priority > 0) "+" else ""}${m.priority}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        (m.shortDescZh ?: m.shortDesc)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (m.championsChanged) {
            Badge("Champions 已調整", Color(0xFFB26A00))
            mainSeriesText(m)?.let { Text("主系列：$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

private fun mainSeriesText(m: Move): String? {
    val o = m.mainSeriesValues as? JsonObject ?: return null
    val parts = o.mapNotNull { (k, v) ->
        val value = (v as? JsonPrimitive)?.content ?: return@mapNotNull if (k == "secondary") "追加效果不同" else null
        when (k) {
            "basePower" -> "威力 $value"; "accuracy" -> "命中 ${if (value == "true") "必中" else value}"
            "pp" -> "基礎 PP $value"; "type" -> "屬性 ${TypeZh[value] ?: value}"; else -> null
        }
    }
    return parts.joinToString("、").ifEmpty { null }
}

// ---- Abilities --------------------------------------------------------------------------

private fun LazyListScope.abilities(state: DataState, p: Pokemon) {
    val s = state.snapshot
    items(p.abilities) { name ->
        val a = s.ability(name)
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(a?.nameZh ?: name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (a?.championsChanged == true) Badge("Champions 新增／調整", Color(0xFFB26A00))
            }
            Text(name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(a?.shortDescZh ?: a?.shortDesc ?: "未有說明", style = MaterialTheme.typography.bodyMedium)
            if (a?.shortDescZh != null && a.shortDesc != null) Text(a.shortDesc!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---- Formes -----------------------------------------------------------------------------

private fun LazyListScope.formes(state: DataState, base: Pokemon, megas: List<Pokemon>, open: (String) -> Unit) {
    val s = state.snapshot
    val others = base.otherFormes.mapNotNull { s.pokemonById[toId(it)] }.filter { !it.isMega && it.teamSelectable }
    item { Text("原本形態", style = MaterialTheme.typography.titleMedium) }
    item { FormRow(state, base) { open(base.id) } }
    if (others.isNotEmpty()) {
        item { Text("其他形態", style = MaterialTheme.typography.titleMedium) }
        items(others, key = { it.id }) { f -> FormRow(state, f) { open(f.id) } }
    }
    if (megas.isNotEmpty()) {
        item { Text("Mega 形態", style = MaterialTheme.typography.titleMedium) }
        items(megas, key = { it.id }) { m -> FormRow(state, m) { open(m.id) } }
    }
    if (others.isEmpty() && megas.isEmpty()) item { Text("沒有其他形態", color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
private fun FormRow(state: DataState, p: Pokemon, onClick: () -> Unit) {
    val s = state.snapshot
    SectionCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(s.displayName(p), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TypeRow(p.types)
        }
        Text("${p.name}・總和 ${p.baseStats.total}" + (p.megaStone?.let { "・$it" } ?: ""), style = MaterialTheme.typography.bodySmall)
        if (state.regulation.id !in p.legalIn) Text("${state.regulation.id} 不可用", color = Color(0xFFC62828), style = MaterialTheme.typography.bodySmall)
    }
}
