@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package hk.tikhang.champions.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import hk.tikhang.champions.core.Pokemon
import hk.tikhang.champions.core.Regulation
import hk.tikhang.champions.core.Verification
import hk.tikhang.champions.core.Stat
import hk.tikhang.champions.core.toId
import hk.tikhang.champions.data.DataState

private enum class SortBy(val zh: String) {
    DEX("圖鑑編號"), NAME("名稱"), BST("種族值總和"), HP("HP"), ATK("攻擊"), DEF("防禦"), SPA("特攻"), SPD("特防"), SPE("速度")
}

@Composable
fun PokemonListScreen(
    state: DataState, onBack: () -> Unit,
    /** Picking for a team: only out-of-battle forms of [regulation] are shown. */
    pickMode: Boolean = false, regulation: Regulation = state.regulation,
    open: (String) -> Unit,
) {
    val s = state.snapshot
    var query by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf<String?>(null) }
    var type2 by rememberSaveable { mutableStateOf<String?>(null) }
    var sort by rememberSaveable { mutableStateOf(SortBy.DEX) }
    var showMega by rememberSaveable { mutableStateOf(!pickMode) }
    var megaOnly by rememberSaveable { mutableStateOf(false) }

    // Spec §4.3: the list only shows Pokémon allowed in the selected Regulation.
    val allowed = remember(regulation) { regulation.allowedPokemon?.toSet() }
    val pool = remember(s, allowed) {
        s.pokemon.filter { it.teamSelectable && (allowed == null || it.id in allowed) }
    }
    val q = query.trim()
    val list = remember(pool, q, type, type2, sort, showMega, megaOnly) {
        val qid = toId(q)
        pool.asSequence()
            .filter { if (megaOnly) it.isMega else showMega || !it.isMega }
            .filter { !pickMode || !it.isMega }
            .filter { (type == null || type in it.types) && (type2 == null || type2 in it.types) }
            .filter { p ->
                q.isEmpty() || s.displayName(p).contains(q) || toId(p.name).contains(qid) ||
                    q.toIntOrNull() == p.num ||
                    p.abilities.any { a -> a.contains(q, ignoreCase = true) || s.ability(a)?.nameZh?.contains(q) == true }
            }
            .sortedWith(comparatorFor(sort, s::displayName))
            .toList()
    }

    Screen(if (pickMode) "選擇 Pokémon" else "Pokémon 圖鑑", onBack, subtitle = "${regulation.name}・${list.size} / ${pool.size}") { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text("搜尋名稱、編號或特性") }, modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Picker("屬性：" + (type?.let { TypeZh[it] } ?: "全部"), listOf<String?>(null) + s.types, { it?.let { t -> TypeZh[t] } ?: "全部屬性" }) { type = it }
                    Picker("第二屬性：" + (type2?.let { TypeZh[it] } ?: "全部"), listOf<String?>(null) + s.types, { it?.let { t -> TypeZh[t] } ?: "全部屬性" }) { type2 = it }
                    Picker("排序：${sort.zh}", SortBy.entries.toList(), { it.zh }) { sort = it }
                    if (!pickMode) {
                        FilterChip(selected = showMega && !megaOnly, onClick = { showMega = !showMega; megaOnly = false }, label = { Text("包括 Mega") })
                        FilterChip(selected = megaOnly, onClick = { megaOnly = !megaOnly }, label = { Text("只看 Mega") })
                    }
                }
            }
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.id }) { p -> PokemonRow(state, p, sort) { open(p.id) } }
            }
        }
    }
}

private fun comparatorFor(sort: SortBy, name: (Pokemon) -> String): Comparator<Pokemon> {
    val byDex = compareBy<Pokemon>({ it.num }, { it.isMega }, { it.id })
    fun stat(st: Stat) = compareByDescending<Pokemon> { it.baseStats[st] }.then(byDex)
    return when (sort) {
        SortBy.DEX -> byDex
        SortBy.NAME -> compareBy(name)
        SortBy.BST -> compareByDescending<Pokemon> { it.baseStats.total }.then(byDex)
        SortBy.HP -> stat(Stat.HP); SortBy.ATK -> stat(Stat.ATK); SortBy.DEF -> stat(Stat.DEF)
        SortBy.SPA -> stat(Stat.SPA); SortBy.SPD -> stat(Stat.SPD); SortBy.SPE -> stat(Stat.SPE)
    }
}

@Composable
fun <T> Picker(label: String, options: List<T>, text: (T) -> String, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text(label) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(text(o)) }, onClick = { onPick(o); open = false }) }
        }
    }
}

@Composable
private fun PokemonRow(state: DataState, p: Pokemon, sort: SortBy, onClick: () -> Unit) {
    val s = state.snapshot
    val highlight = when (sort) {
        SortBy.HP -> Stat.HP; SortBy.ATK -> Stat.ATK; SortBy.DEF -> Stat.DEF
        SortBy.SPA -> Stat.SPA; SortBy.SPD -> Stat.SPD; else -> Stat.SPE
    }
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(dex(p.num), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(s.displayName(p), style = MaterialTheme.typography.titleSmall)
                    if (p.isMega) Badge("Mega", Color(0xFF8E24AA))
                    if (p.verification == Verification.PENDING) Badge("待驗證", Color(0xFFB26A00))
                }
                Text(p.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TypeRow(p.types)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("總和 ${p.baseStats.total}", style = MaterialTheme.typography.bodySmall)
                Text("${highlight.zh} ${p.baseStats[highlight]}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
