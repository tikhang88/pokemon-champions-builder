@file:OptIn(ExperimentalLayoutApi::class)

package hk.tikhang.champions.ui

import android.content.Intent
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hk.tikhang.champions.core.BattleFormat
import hk.tikhang.champions.core.Legality
import hk.tikhang.champions.core.LegalityEngine
import hk.tikhang.champions.core.LegalityIssue
import hk.tikhang.champions.core.LegalityReport
import hk.tikhang.champions.core.TEAM_SIZE
import hk.tikhang.champions.core.Team
import hk.tikhang.champions.core.TeamEngine
import hk.tikhang.champions.data.AppViewModel
import hk.tikhang.champions.data.DataState

fun legalityColor(l: Legality): Color = when (l) {
    Legality.LEGAL -> Color(0xFF2E7D32); Legality.ILLEGAL -> Color(0xFFC62828)
    Legality.PENDING -> Color(0xFFB26A00); Legality.INCOMPLETE -> Color(0xFF757575)
}

fun issueColor(l: LegalityIssue.Level): Color = when (l) {
    LegalityIssue.Level.ERROR -> Color(0xFFC62828); LegalityIssue.Level.PENDING -> Color(0xFFB26A00)
    LegalityIssue.Level.WARNING -> Color(0xFF1565C0); LegalityIssue.Level.INCOMPLETE -> Color(0xFF757575)
}

@Composable
fun TeamsScreen(vm: AppViewModel, state: DataState, onBack: () -> Unit, open: (Long) -> Unit) {
    val teams by vm.teams.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<Pair<Long, Team>?>(null) }
    var deleting by remember { mutableStateOf<Pair<Long, String>?>(null) }
    val context = LocalContext.current
    Screen("Team Builder", onBack, subtitle = "${teams.size} 個隊伍") { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Button(onClick = { vm.createTeam(open) }, Modifier.fillMaxWidth()) { Text("建立新隊伍") } }
            if (teams.isEmpty()) item { Text("未有隊伍。建立第一個隊伍開始構築。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(teams, key = { it.id }) { e ->
                val team = e.team()
                if (team == null) {
                    SectionCard { Text("「${e.name}」資料無法讀取", color = MaterialTheme.colorScheme.error) }
                } else {
                    val report = LegalityEngine.check(state.snapshot, team)
                    TeamCard(state, team, report, onClick = { open(e.id) },
                        onRename = { renaming = e.id to team }, onDuplicate = { vm.duplicateTeam(e.id) },
                        onDelete = { deleting = e.id to team.name }, onExport = { shareText(context, team.name, TeamEngine.exportText(state.snapshot, team)) })
                }
            }
        }
    }
    renaming?.let { (id, team) -> RenameDialog(team.name, { vm.saveTeam(id, team.copy(name = it)); renaming = null }) { renaming = null } }
    deleting?.let { (id, name) ->
        AlertDialog(
            onDismissRequest = { deleting = null }, title = { Text("刪除「$name」？") }, text = { Text("刪除後不能還原。") },
            confirmButton = { TextButton(onClick = { vm.deleteTeam(id); deleting = null }) { Text("刪除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun TeamCard(
    state: DataState, team: Team, report: LegalityReport,
    onClick: () -> Unit, onRename: () -> Unit, onDuplicate: () -> Unit, onDelete: () -> Unit, onExport: () -> Unit,
) {
    val s = state.snapshot
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(team.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Box {
                    TextButton(onClick = { menu = true }) { Text("⋯") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("重新命名") }, onClick = { menu = false; onRename() })
                        DropdownMenuItem(text = { Text("複製") }, onClick = { menu = false; onDuplicate() })
                        DropdownMenuItem(text = { Text("匯出文字") }, onClick = { menu = false; onExport() })
                        DropdownMenuItem(text = { Text("刪除") }, onClick = { menu = false; onDelete() })
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Badge(report.result.zh, legalityColor(report.result))
                Badge(team.regulationId, MaterialTheme.colorScheme.secondary)
                Badge(team.format.zh, MaterialTheme.colorScheme.tertiary)
            }
            Text(
                team.members.mapNotNull { sl -> TeamEngine.effectiveForm(s, sl)?.let { s.displayName(it) } }.joinToString("、").ifEmpty { "（空）" },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
fun RenameDialog(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("隊伍名稱") },
        text = { OutlinedTextField(value = text, onValueChange = { text = it.take(40) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onSave(text.trim().ifEmpty { current }) }) { Text("儲存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

fun shareText(context: android.content.Context, title: String, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, title); putExtra(Intent.EXTRA_TEXT, text) }
    context.startActivity(Intent.createChooser(intent, "匯出隊伍"))
}

@Composable
fun TeamEditorScreen(
    vm: AppViewModel, state: DataState, teamId: Long, onBack: () -> Unit,
    pickPokemon: (slot: Int) -> Unit, editSlot: (slot: Int) -> Unit, analyse: () -> Unit,
) {
    val teams by vm.teams.collectAsStateWithLifecycle()
    val entity = teams.firstOrNull { it.id == teamId }
    val team = entity?.team()
    if (team == null) {
        Screen("隊伍", onBack) { pad -> Text(if (entity == null) "載入中…" else "隊伍資料無法讀取", Modifier.padding(pad).padding(16.dp)) }
        return
    }
    val s = state.snapshot
    val report = LegalityEngine.check(s, team)
    var renaming by remember { mutableStateOf(false) }
    val context = LocalContext.current
    fun save(t: Team) = vm.saveTeam(teamId, t)

    Screen(team.name, onBack, subtitle = "${team.regulationId}・${team.format.zh}・${team.members.size}/$TEAM_SIZE") { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                SectionCard {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { renaming = true }) { Text("重新命名") }
                        Picker("Regulation：${team.regulationId}", s.regulations.filter { it.hasPokemonList }, { it.id + if (it.provisional) "（暫定）" else "" }) {
                            save(team.copy(regulationId = it.id))
                        }
                        Picker("格式：${team.format.zh}", BattleFormat.entries.toList(), { it.zh }) { save(team.copy(format = it)) }
                        OutlinedButton(onClick = { shareText(context, team.name, TeamEngine.exportText(s, team)) }) { Text("匯出文字") }
                    }
                }
            }
            item { LegalityCard(report) }
            item {
                androidx.compose.material3.Button(onClick = analyse, Modifier.fillMaxWidth(), enabled = team.members.isNotEmpty()) {
                    Text("隊伍分析（屬性覆蓋・弱點・配合・報告）")
                }
            }
            items(TEAM_SIZE) { i ->
                val slot = team.slots.getOrNull(i)
                if (slot == null) {
                    Card(Modifier.fillMaxWidth().clickable { pickPokemon(i) }) {
                        Text("＋ 加入 Pokémon（第 ${i + 1} 位）", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.primary)
                    }
                } else {
                    SlotCard(state, slot, report.forSlot(i), onClick = { editSlot(i) }, onRemove = { save(team.withSlot(i, null)) })
                }
            }
            item {
                Text(
                    "${team.format.zh}：帶 4–6 隻入場，對戰時選 ${team.format.bring} 隻。每場只可 Mega Evolution 一次。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (renaming) RenameDialog(team.name, { save(team.copy(name = it)); renaming = false }) { renaming = false }
}

@Composable
fun LegalityCard(report: LegalityReport) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("合法性檢查", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Badge(report.result.zh, legalityColor(report.result))
        }
        if (report.issues.isEmpty()) Text("隊伍符合目前 Regulation 的所有規則。", style = MaterialTheme.typography.bodySmall)
        report.teamIssues.forEach { Text("・${it.message}", color = issueColor(it.level), style = MaterialTheme.typography.bodySmall) }
        val slotIssues = report.issues.count { it.slotIndex != null }
        if (slotIssues > 0) Text("各隊員共有 $slotIssues 項提示，見下方。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SlotCard(state: DataState, slot: hk.tikhang.champions.core.TeamSlot, issues: List<LegalityIssue>, onClick: () -> Unit, onRemove: () -> Unit) {
    val s = state.snapshot
    val form = TeamEngine.effectiveForm(s, slot)
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(form?.let { s.displayName(it) } ?: slot.speciesId, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (slot.megaFormId != null) Badge("Mega", Color(0xFF8E24AA))
                TextButton(onClick = onRemove) { Text("移除") }
            }
            form?.let { TypeRow(it.types) }
            val ability = TeamEngine.effectiveAbility(s, slot)?.let { a -> s.ability(a)?.nameZh ?: a } ?: "未選特性"
            val item = slot.item?.let { s.itemById[it]?.let { i -> i.nameZh ?: i.name } ?: it } ?: "沒有道具"
            Text("$ability・$item", style = MaterialTheme.typography.bodySmall)
            Text(
                slot.moves.map { m -> s.moveById[m]?.let { it.nameZh ?: it.name } ?: m }.joinToString("、").ifEmpty { "未選招式" },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            issues.forEach { Text("・${it.message}", color = issueColor(it.level), style = MaterialTheme.typography.bodySmall) }
        }
    }
}
