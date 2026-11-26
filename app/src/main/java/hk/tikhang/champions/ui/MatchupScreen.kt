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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import hk.tikhang.champions.core.MatchupEngine
import hk.tikhang.champions.core.MatchupEngine.Threat
import hk.tikhang.champions.core.TEAM_SIZE
import hk.tikhang.champions.core.TeamAnalysisEngine
import hk.tikhang.champions.core.toId
import hk.tikhang.champions.data.AppViewModel
import hk.tikhang.champions.data.DataState

private fun threatColor(t: Threat) = when (t) { Threat.HIGH -> Color(0xFFC62828); Threat.MEDIUM -> Color(0xFFB26A00); Threat.LOW -> Color(0xFF2E7D32) }

/** Team Matchup Research (spec §11). */
@Composable
fun MatchupScreen(vm: AppViewModel, state: DataState, teamId: Long, onBack: () -> Unit, openPokemon: (String) -> Unit) {
    val s = state.snapshot
    val teams by vm.teams.collectAsStateWithLifecycle()
    val team = teams.firstOrNull { it.id == teamId }?.team()
    if (team == null) {
        Screen("Matchup 研究", onBack) { pad -> Text("載入中…", Modifier.padding(pad).padding(16.dp)) }
        return
    }
    val opponentTeams = teams.filter { it.team()?.opponent == true }
    var opponentTeamId by rememberSaveable { mutableStateOf<Long?>(null) }
    val picks = remember { mutableStateListOf<String>() }
    var picking by remember { mutableStateOf(false) }
    var trickRoom by rememberSaveable { mutableStateOf(false) }
    var expanded by remember { mutableStateOf<Int?>(null) }

    val reg = s.regulationById[team.regulationId] ?: state.regulation
    val mine = remember(team) { TeamAnalysisEngine.members(s, team) }
    val oppTeam = opponentTeamId?.let { id -> opponentTeams.firstOrNull { it.id == id }?.team() }
    val opponents = remember(oppTeam, picks.toList()) {
        if (oppTeam != null) MatchupEngine.opponentsFromTeam(s, oppTeam)
        else picks.mapIndexedNotNull { i, id -> MatchupEngine.quickOpponent(s, id, i) }
    }
    val report = remember(mine, opponents, trickRoom) { if (opponents.isEmpty()) null else MatchupEngine.report(s, state.typeEngine, mine, opponents, trickRoom) }

    Screen("Matchup 研究", onBack, subtitle = "${team.name}・${team.regulationId}") { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                SectionCard("對手") {
                    Text("選擇已儲存的對手隊伍（招式已知），或自選最多 6 隻 Pokémon（招式未知，以其屬性估算）。", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(selected = opponentTeamId == null, onClick = { opponentTeamId = null }, label = { Text("自選 Pokémon") })
                        opponentTeams.forEach { e ->
                            FilterChip(selected = opponentTeamId == e.id, onClick = { opponentTeamId = e.id }, label = { Text(e.name) })
                        }
                    }
                    if (opponentTeams.isEmpty()) Text("未有對手隊伍：可在 Team Builder 的「對手隊伍」建立或匯入。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (opponentTeamId == null) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            picks.forEach { id ->
                                AssistChip(onClick = { picks.remove(id) }, label = { Text((s.pokemonById[id]?.let { s.displayName(it) } ?: id) + " ✕") })
                            }
                            if (picks.size < TEAM_SIZE) OutlinedButton(onClick = { picking = true }) { Text("＋ 加入") }
                        }
                    }
                    FilterChip(selected = trickRoom, onClick = { trickRoom = !trickRoom }, label = { Text("戲法空間") })
                }
            }
            if (mine.isEmpty()) item { Text("隊伍未有成員。") }
            report?.let { r ->
                item {
                    SectionCard("Team Matchup Matrix") {
                        Row(Modifier.fillMaxWidth()) {
                            listOf("對手" to 1.3f, "威脅" to 0.6f, "我方應對" to 1.3f).forEach { (h, w) -> Text(h, Modifier.weight(w), fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                        }
                        r.results.forEachIndexed { i, res ->
                            Column(Modifier.fillMaxWidth().clickable { expanded = if (expanded == i) null else i }.padding(vertical = 4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(res.opponent.name, Modifier.weight(1.3f), style = MaterialTheme.typography.bodyMedium)
                                    Row(Modifier.weight(0.6f)) { Badge(res.threat.zh, threatColor(res.threat)) }
                                    Text(res.bestResponse?.mine?.name ?: "—", Modifier.weight(1.3f), style = MaterialTheme.typography.bodyMedium)
                                }
                                Text(res.explanation, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (!res.movesKnown) Text("招式未知：以對手屬性（本系）估算；假設最速攻擊配置", fontSize = 11.sp, color = Color(0xFFB26A00))
                                res.abilityNote?.let { Text(it, fontSize = 11.sp, color = Color(0xFFB26A00)) }
                                if (expanded == i) PairingTable(res)
                            }
                            HorizontalDivider()
                        }
                        Text("點擊對手查看與每位成員的配對。威脅評級由屬性倍率（已計特性）、實際招式、速度計算，屬「Analysis／Estimated」，不代表必然結果。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item { MatchupReport(r) }
            }
        }
    }

    if (picking) {
        PokemonSearchDialog(state, reg.allowedPokemon?.toSet(), onPick = { id -> if (id !in picks && picks.size < TEAM_SIZE) picks.add(id); picking = false }) { picking = false }
    }
}

@Composable
private fun PairingTable(res: MatchupEngine.OpponentResult) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        res.flags.forEach { Text("・$it", fontSize = 11.sp, color = Color(0xFF6D4C41)) }
        res.bestDefensive?.let { Text("最佳防守應對：${it.mine.name}（對手最佳攻擊 ×${TeamAnalysisEngine.fmt(it.theirBest)}）", fontSize = 12.sp) }
        res.bestOffensive?.let { Text("最佳進攻應對：${it.mine.name}（${it.myBestMove?.let { m -> m.nameZh ?: m.name }} ×${it.myBest?.let(TeamAnalysisEngine::fmt)}）", fontSize = 12.sp) }
        Row(Modifier.fillMaxWidth()) {
            listOf("我方" to 1.2f, "我方攻擊" to 1.3f, "對手攻擊" to 1.3f, "速度" to 0.9f, "分數" to 0.6f).forEach { (h, w) -> Text(h, Modifier.weight(w), fontSize = 11.sp, fontWeight = FontWeight.Bold) }
        }
        res.pairings.sortedByDescending { it.score }.forEach { p ->
            Row(Modifier.fillMaxWidth()) {
                Text(p.mine.name, Modifier.weight(1.2f), fontSize = 11.sp)
                Text(p.myBestMove?.let { "${it.nameZh ?: it.name} ×${p.myBest?.let(TeamAnalysisEngine::fmt)}" } ?: "沒有攻擊招式", Modifier.weight(1.3f), fontSize = 11.sp,
                    color = if ((p.myBest ?: 0.0) > 1.0) Color(0xFF2E7D32) else Color.Unspecified)
                Text("${p.theirBestAttack} ×${TeamAnalysisEngine.fmt(p.theirBest)}", Modifier.weight(1.3f), fontSize = 11.sp,
                    color = if (p.theirBest > 1.0) Color(0xFFC62828) else Color.Unspecified)
                Text("${p.mySpeed}/${p.theirSpeed}", Modifier.weight(0.9f), fontSize = 11.sp,
                    color = when (p.speed) { 0.5 -> Color(0xFF2E7D32); -0.5 -> Color(0xFFC62828); else -> Color.Unspecified })
                Text("%.1f".format(p.score), Modifier.weight(0.6f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text("分數 = 我方攻擊（log₂倍率）＋ 防守（−log₂對手倍率）＋ 速度（±0.5）。實際傷害範圍可用「傷害計算」查看。",
            fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MatchupReport(r: MatchupEngine.Report) {
    SectionCard("Matchup Report") {
        Text("對手概覽", style = MaterialTheme.typography.titleSmall)
        Text(r.results.joinToString("、") { it.opponent.name }, style = MaterialTheme.typography.bodySmall)
        Text("潛在威脅", style = MaterialTheme.typography.titleSmall)
        if (r.threats.isEmpty()) Text("沒有高／中威脅", style = MaterialTheme.typography.bodySmall)
        r.threats.forEach { Text("・${it.opponent.name}（${it.threat.zh}）", style = MaterialTheme.typography.bodySmall, color = threatColor(it.threat)) }
        Text("隊伍應對", style = MaterialTheme.typography.titleSmall)
        r.results.forEach { res ->
            Text("・${res.opponent.name} → ${res.bestResponse?.mine?.name ?: "沒有"}" +
                (res.bestDefensive?.takeIf { it != res.bestResponse }?.let { "（接招：${it.mine.name}）" } ?: ""), style = MaterialTheme.typography.bodySmall)
        }
        Text("覆蓋缺口", style = MaterialTheme.typography.titleSmall)
        Text(if (r.unhittable.isEmpty()) "我方可效果絕佳打擊全部對手" else "沒有效果絕佳招式：" + r.unhittable.joinToString("、") { it.name }, style = MaterialTheme.typography.bodySmall)
        Text("速度比較", style = MaterialTheme.typography.titleSmall)
        r.speedLines.forEach { Text("・$it", style = MaterialTheme.typography.bodySmall) }
        Text("對手共同弱點", style = MaterialTheme.typography.titleSmall)
        if (r.opponentSharedWeakness.isEmpty()) Text("沒有", style = MaterialTheme.typography.bodySmall)
        r.opponentSharedWeakness.forEach { Text("・$it", style = MaterialTheme.typography.bodySmall) }
        Text("傷害考慮", style = MaterialTheme.typography.titleSmall)
        Text("Matchup 評級未計實際傷害數字；可在隊伍頁的「傷害計算」查看具體傷害範圍及 KO 機率。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("可能策略（分析建議）", style = MaterialTheme.typography.titleSmall)
        r.strategy.forEach { Text("・$it", style = MaterialTheme.typography.bodySmall) }
        if (r.leadSuggestion.size == 2) Text("・按分數估算的先發組合（雙打）：${r.leadSuggestion.joinToString(" ＋ ") { it.name }}", style = MaterialTheme.typography.bodySmall)
        Text("以上為分析建議（Analysis／Estimated），不代表必然最佳。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Search dialog for any Pokémon (Megas included) in a Regulation. */
@Composable
fun PokemonSearchDialog(state: DataState, allowed: Set<String>?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val s = state.snapshot
    var q by remember { mutableStateOf("") }
    val list = remember(q) {
        s.pokemon.filter { it.teamSelectable && (allowed == null || it.id in allowed) }
            .filter { q.isBlank() || s.displayName(it).contains(q.trim()) || toId(it.name).contains(toId(q)) }
            .sortedWith(compareBy({ it.num }, { it.isMega }))
    }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("選擇 Pokémon") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = q, onValueChange = { q = it }, singleLine = true, label = { Text("搜尋") }, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(list, key = { it.id }) { p ->
                        Row(Modifier.fillMaxWidth().clickable { onPick(p.id) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(s.displayName(p), Modifier.weight(1f))
                            TypeRow(p.types)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("關閉") } },
    )
}
