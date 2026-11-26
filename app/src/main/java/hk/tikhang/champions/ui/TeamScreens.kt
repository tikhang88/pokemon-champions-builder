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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
    val allTeams by vm.teams.collectAsStateWithLifecycle()
    var showOpponents by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val teams = allTeams.filter { (it.team()?.opponent ?: false) == showOpponents }
    var renaming by remember { mutableStateOf<Pair<Long, Team>?>(null) }
    var deleting by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var importing by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Screen("Team Builder", onBack, subtitle = "${teams.size} 個${if (showOpponents) "對手" else ""}隊伍") { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.FilterChip(selected = !showOpponents, onClick = { showOpponents = false }, label = { Text("我的隊伍") })
                    androidx.compose.material3.FilterChip(selected = showOpponents, onClick = { showOpponents = true }, label = { Text("對手隊伍（Matchup 用）") })
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.createTeam(showOpponents, open) }, Modifier.weight(1f)) { Text(if (showOpponents) "建立對手隊伍" else "建立新隊伍") }
                    OutlinedButton(onClick = { importing = true }, Modifier.weight(1f)) { Text("匯入隊伍文字") }
                }
            }
            if (teams.isEmpty()) item {
                Text(if (showOpponents) "未有對手隊伍。建立或匯入對手隊伍後，可在自己隊伍的「Matchup 研究」中使用。" else "未有隊伍。建立第一個隊伍開始構築。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
    if (importing) ImportTeamDialog(vm, showOpponents, onDone = { id -> importing = false; id?.let(open) }) { importing = false }
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
                if (team.opponent) Badge("對手", Color(0xFF6D4C41))
                Badge(report.result.zh, legalityColor(report.result))
                Badge(team.regulationId, MaterialTheme.colorScheme.secondary)
                Badge(team.format.zh, MaterialTheme.colorScheme.tertiary)
            }
            Text(
                team.members.mapNotNull { sl -> TeamEngine.effectiveForm(s, sl)?.let { s.displayName(it) } }.joinToString("、").ifEmpty { "（空）" },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (team.teamCode.isNotBlank()) Text("隊伍碼 ${team.teamCode}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    speedTier: () -> Unit = {}, matchup: () -> Unit = {}, damage: () -> Unit = {}, battle: () -> Unit = {},
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
    var editingCode by remember { mutableStateOf(false) }
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("隊伍碼：" + team.teamCode.ifBlank { "未設定" }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { editingCode = true }) { Text("編輯") }
                        if (team.teamCode.isNotBlank()) TextButton(onClick = { copyText(context, team.teamCode) }) { Text("複製") }
                    }
                    if (team.opponent) Text("這是對手隊伍，用於 Matchup 研究。", style = MaterialTheme.typography.bodySmall, color = Color(0xFF6D4C41))
                }
            }
            hk.tikhang.champions.core.TeamImpact.outdatedRegulation(s, team, java.time.Instant.now())?.let { cur ->
                item {
                    SectionCard {
                        Text("此隊伍使用 ${team.regulationId}（已結束）。目前是 ${cur.id}。", color = Color(0xFFB26A00), style = MaterialTheme.typography.bodyMedium)
                        val after = LegalityEngine.check(s, team.copy(regulationId = cur.id))
                        Text("改用 ${cur.id} 後：${after.result.zh}", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { save(team.copy(regulationId = cur.id)) }) { Text("改用 ${cur.id}") }
                    }
                }
            }
            item { LegalityCard(report) }
            item { Column {
                androidx.compose.material3.Button(onClick = analyse, Modifier.fillMaxWidth(), enabled = team.members.isNotEmpty()) {
                    Text("隊伍分析（Coverage Matrix・弱點・配合・報告）")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedButton(onClick = speedTier, Modifier.weight(1f), enabled = team.members.isNotEmpty()) { Text("Speed Tier") }
                    if (!team.opponent) OutlinedButton(onClick = matchup, Modifier.weight(1f), enabled = team.members.isNotEmpty()) { Text("Matchup 研究") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedButton(onClick = damage, Modifier.weight(1f), enabled = team.members.isNotEmpty()) { Text("傷害計算") }
                    if (!team.opponent) OutlinedButton(onClick = battle, Modifier.weight(1f), enabled = team.members.isNotEmpty()) { Text("對戰模擬") }
                }
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
    if (editingCode) TeamCodeDialog(team.teamCode, { save(team.copy(teamCode = it)); editingCode = false }) { editingCode = false }
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


fun copyText(context: android.content.Context, text: String) {
    val cm = context.getSystemService(android.content.ClipboardManager::class.java)
    cm?.setPrimaryClip(android.content.ClipData.newPlainText("隊伍碼", text))
}

/** The Replica Team ID is kept with the team for use in the game; it can't be decoded offline. */
@Composable
fun TeamCodeDialog(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(current) }
    val valid = text.isBlank() || hk.tikhang.champions.core.TeamTextImporter.isValidTeamCode(text)
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("隊伍碼（Replica Team ID）") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = text, onValueChange = { text = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(10) },
                    singleLine = true, isError = !valid, label = { Text("10 位英文字母／數字") })
                Text("隊伍碼由遊戲伺服器解讀，本身不包含隊伍資料，所以 App 不能用隊伍碼讀取隊伍。這裏只作記錄，方便你在遊戲內「以隊伍 ID 建立隊伍」。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text.trim()) }, enabled = valid) { Text("儲存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun ImportTeamDialog(vm: AppViewModel, opponent: Boolean, onDone: (Long?) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<Pair<hk.tikhang.champions.core.TeamTextImporter.Result, Long?>?>(null) }
    val r = result
    if (r != null) {
        AlertDialog(
            onDismissRequest = { onDone(r.second) },
            title = { Text(if (r.first.imported > 0) "已匯入 ${r.first.imported} 隻" else "未能匯入") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    if (r.first.warnings.isEmpty()) Text("全部資料已讀取。")
                    r.first.warnings.forEach { Text("・$it", style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = { TextButton(onClick = { onDone(r.second) }) { Text(if (r.second != null) "開啟隊伍" else "好") } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (opponent) "匯入對手隊伍" else "匯入隊伍") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("貼上 Pokémon Showdown／PokePaste 格式的隊伍文字（例如 Game8、dexdb 等網站提供的隊伍）。支援英文及繁體中文名稱。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, singleLine = true, label = { Text("隊伍名稱") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = code, onValueChange = { code = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(10) }, singleLine = true,
                    label = { Text("隊伍碼（可選）") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("隊伍文字") }, minLines = 6, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { vm.importTeam(text, name, code, opponent) { res, id -> result = res to id } }, enabled = text.isNotBlank()) { Text("匯入") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
