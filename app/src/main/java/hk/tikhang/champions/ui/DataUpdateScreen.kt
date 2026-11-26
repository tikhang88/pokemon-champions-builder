package hk.tikhang.champions.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hk.tikhang.champions.core.ChangeLog
import hk.tikhang.champions.core.ImportCheck
import hk.tikhang.champions.core.Issue
import hk.tikhang.champions.data.AppViewModel
import hk.tikhang.champions.data.DataState
import hk.tikhang.champions.data.ImportUi
import hk.tikhang.champions.data.SnapshotStore

private const val RELEASES_URL = "github.com/tikhang88/pokemon-champions-builder/releases"

@Composable
fun DataUpdateScreen(vm: AppViewModel, state: DataState, onBack: () -> Unit) {
    val importState by vm.import.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    var confirmReset by remember { mutableStateOf(false) }
    var confirmRevert by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::checkImport) }
    val meta = state.snapshot.meta
    val history = remember(importState, state) { vm.history() }

    Screen("資料更新", onBack) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SectionCard("目前資料") {
                    LabelValue("資料版本", meta.dataVersion)
                    LabelValue("類型", if (state.loaded.origin == SnapshotStore.Origin.BUNDLED) "App 內置" else "已匯入")
                    LabelValue("產生時間", localTime(meta.generatedAt))
                    LabelValue("來源", "Showdown ${meta.source.commit.take(7)}（${meta.source.commitDate}）")
                    LabelValue("目前 Regulation", meta.currentRegulation + (meta.nextRegulation?.let { "（下一個：$it）" } ?: ""))
                    LabelValue("Pokémon／招式", "${state.snapshot.pokemon.size}／${state.snapshot.moves.size}")
                    LabelValue("SHA-256", state.loaded.sha256.take(16) + "…")
                    LabelValue("網絡", if (online) "在線" else "離線（所有功能仍可使用）")
                }
            }
            state.needsUpdateFor?.let { r ->
                item {
                    SectionCard {
                        Text("${r.id} 已經生效，但目前資料沒有 ${r.id} 的 Pokémon 名單。", color = MaterialTheme.colorScheme.error)
                        Text("請按下方步驟取得新資料。期間 App 會繼續使用 ${state.regulation.id}。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                SectionCard("匯入新資料") {
                    Text("支援 .json 資料檔，或 GitHub 下載的 .zip。匯入前會先檢查資料及列出改變，確認後才會替換；失敗不會影響現有資料。", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { picker.launch(arrayOf("application/json", "application/zip", "application/octet-stream", "text/plain", "*/*")) }, Modifier.fillMaxWidth()) {
                        Text("選擇資料檔並匯入")
                    }
                    if (vm.hasPrevious) OutlinedButton(onClick = { confirmRevert = true }, Modifier.fillMaxWidth()) { Text("還原上一版本") }
                    if (vm.hasImported) OutlinedButton(onClick = { confirmReset = true }, Modifier.fillMaxWidth()) { Text("改用 App 內置資料") }
                }
            }
            item {
                SectionCard("如何取得新資料（例如 12 月 2 日的 M-D）") {
                    Text(
                        "1. GitHub 每天會自動檢查資料來源；有更新時會在 Releases 發佈新資料檔。\n" +
                            "2. 用手機打開 $RELEASES_URL，找標題為「資料更新」的最新版本。\n" +
                            "3. 下載 champions_data.json。\n" +
                            "4. 回到這裏按「選擇資料檔並匯入」，選剛下載的檔案。\n" +
                            "提示：想立即更新，可在 GitHub 的 Actions →「Update data」按 Run workflow。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text("注意：資料來源（Pokémon Showdown）通常在新 Regulation 開始後一至數天內更新。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (history.isNotEmpty()) {
                item { Text("更新紀錄", style = MaterialTheme.typography.titleMedium) }
                items(history) { h ->
                    SectionCard {
                        Text("${localTime(h.time)}・${h.action}", style = MaterialTheme.typography.titleSmall)
                        h.dataVersion?.let { Text("版本 $it", style = MaterialTheme.typography.bodySmall) }
                        h.file?.let { Text("檔案 $it", style = MaterialTheme.typography.bodySmall) }
                        h.summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }

    ImportDialog(importState, onConfirm = vm::confirmImport, onDismiss = vm::dismissImport)

    if (confirmRevert) ConfirmDialog("還原上一版本？", "會改回上一次匯入前使用的資料。", { confirmRevert = false; vm.revertToPrevious() }) { confirmRevert = false }
    if (confirmReset) ConfirmDialog("改用 App 內置資料？", "已匯入的資料會保存為「上一版本」，之後仍可還原。", { confirmReset = false; vm.resetToBundled() }) { confirmReset = false }
}

@Composable
private fun ConfirmDialog(title: String, text: String, onYes: () -> Unit, onNo: () -> Unit) {
    AlertDialog(
        onDismissRequest = onNo, title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton(onClick = onYes) { Text("確定") } },
        dismissButton = { TextButton(onClick = onNo) { Text("取消") } },
    )
}

@Composable
private fun ImportDialog(ui: ImportUi, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    when (ui) {
        ImportUi.Idle -> Unit
        ImportUi.Checking -> AlertDialog(
            onDismissRequest = {}, confirmButton = {}, title = { Text("正在檢查資料…") },
            text = { CircularProgressIndicator() },
        )
        is ImportUi.Done -> AlertDialog(
            onDismissRequest = onDismiss, title = { Text(if (ui.success) "完成" else "未能完成") }, text = { Text(ui.message) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("好") } },
        )
        is ImportUi.Ready -> when (val c = ui.check) {
            is ImportCheck.Unreadable -> AlertDialog(
                onDismissRequest = onDismiss, title = { Text("無法匯入") }, text = { Text(c.reason) },
                confirmButton = { TextButton(onClick = onDismiss) { Text("好") } },
            )
            is ImportCheck.Ok -> AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(if (c.canImport) "確認匯入？" else "資料有錯誤") },
                text = { ImportPreview(c, ui.fileName) },
                confirmButton = { TextButton(onClick = onConfirm, enabled = c.canImport) { Text("確認匯入") } },
                dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
            )
        }
    }
}

@Composable
private fun ImportPreview(c: ImportCheck.Ok, fileName: String?) {
    val m = c.snapshot.meta
    Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        fileName?.let { LabelValue("檔案", it) }
        LabelValue("資料版本", m.dataVersion)
        LabelValue("目前 Regulation", m.currentRegulation)
        LabelValue("Regulation", c.snapshot.regulations.joinToString { it.id + if (it.hasPokemonList) "" else "（待驗證）" })
        LabelValue("SHA-256", c.sha256.take(16) + "…")
        val errors = c.issues.filter { it.severity == Issue.Severity.ERROR }
        val warnings = c.issues.filter { it.severity == Issue.Severity.WARNING }
        if (errors.isNotEmpty()) {
            Text("錯誤（${errors.size}）", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleSmall)
            errors.forEach { Text("・${it.message}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        } else {
            Text("✓ 資料檢查通過", color = Color(0xFF2E7D32), style = MaterialTheme.typography.titleSmall)
        }
        if (warnings.isNotEmpty()) {
            Text("提示（${warnings.size}）", color = Color(0xFFB26A00), style = MaterialTheme.typography.titleSmall)
            warnings.forEach { Text("・${it.message}", style = MaterialTheme.typography.bodySmall) }
        }
        c.changes?.let { ChangeLogView(it) }
    }
}

@Composable
private fun ChangeLogView(log: ChangeLog) {
    Text("改變（${log.fromVersion} → ${log.toVersion}）", style = MaterialTheme.typography.titleSmall)
    if (log.isEmpty) { Text("與目前資料相同", style = MaterialTheme.typography.bodySmall); return }
    fun names(l: List<String>) = l.take(30).joinToString("、") + if (l.size > 30) "…（共 ${l.size} 個）" else ""
    listOf(
        "Regulation" to log.regulationChanges, "新增 Pokémon" to log.newPokemon, "新增 Mega" to log.newMegas,
        "移除 Pokémon" to log.removedPokemon, "資料改變的 Pokémon" to log.changedPokemon,
        "新增招式" to log.newMoves, "改變的招式" to log.changedMoves, "移除招式" to log.removedMoves,
    ).forEach { (label, l) ->
        if (l.isNotEmpty()) {
            Text("$label（${l.size}）", style = MaterialTheme.typography.labelLarge)
            Text(if (label == "Regulation") l.joinToString("\n") else names(l), style = MaterialTheme.typography.bodySmall)
        }
    }
}
