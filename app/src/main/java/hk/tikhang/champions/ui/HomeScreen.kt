@file:OptIn(ExperimentalMaterial3Api::class)

package hk.tikhang.champions.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hk.tikhang.champions.Routes
import hk.tikhang.champions.data.AppViewModel
import hk.tikhang.champions.data.DataState
import hk.tikhang.champions.data.SnapshotStore

/** Screen frame with a title bar and, when [onBack] is given, a back button. */
@Composable
fun Screen(title: String, onBack: (() -> Unit)?, subtitle: String? = null, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, style = MaterialTheme.typography.titleLarge)
                        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
            )
        },
        content = content,
    )
}

@Composable
fun HomeScreen(vm: AppViewModel, state: DataState, navigate: (String) -> Unit) {
    val online by vm.online.collectAsStateWithLifecycle()
    val meta = state.snapshot.meta
    Screen("Pokémon Champion 隊伍模擬器", onBack = null) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                // Offline indicator (spec §16.7)
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("資料狀態", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Badge(if (online) "在線" else "離線", if (online) Color(0xFF2E7D32) else Color(0xFF757575))
                        Spacer(Modifier.width(6.dp))
                        Badge("離線可用", Color(0xFF1565C0))
                    }
                    LabelValue("目前 Regulation", state.regulation.id + if (state.regulationIsManual) "（手動選擇）" else "")
                    LabelValue("資料版本", meta.dataVersion)
                    LabelValue("最後更新", localTime(meta.generatedAt) + if (state.loaded.origin == SnapshotStore.Origin.BUNDLED) "（App 內置）" else "（已匯入）")
                    LabelValue("資料來源", "Pokémon Showdown（${meta.source.commitDate}）")
                }
            }
            state.needsUpdateFor?.let { r ->
                item {
                    Card(
                        Modifier.fillMaxWidth().clickable { navigate(Routes.DATA) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                if (r.provisional) "${r.id} 已生效，但名單只包括已公布的 Pokémon" else "${r.id} 已生效，但目前資料未有 ${r.id} 名單",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text("現正使用 ${state.regulation.id}。點此匯入新資料。", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            item { MenuItem("Team Builder", "建立／匯入隊伍、隊伍碼、合法性檢查、隊伍分析、Speed Tier、Matchup、傷害計算、對戰模擬") { navigate(Routes.TEAMS) } }
            item { MenuItem("Pokémon 圖鑑", "搜尋、篩選、能力值、招式、Mega 比較") { navigate(Routes.POKEDEX) } }
            item { MenuItem("傷害計算", "Champions 傷害計算器：傷害範圍、KO 機率、生存分析（基本／進階）") { navigate(Routes.calc(0)) } }
            item { MenuItem("Speed Tier 研究", "目前 Regulation 所有 Pokémon 的速度排位、條件（順風・戲法空間・圍巾等）") { navigate(Routes.speed(0)) } }
            item { MenuItem("Regulation", "目前及歷史規則、可用 Pokémon、切換 Regulation") { navigate(Routes.REGULATIONS) } }
            item { MenuItem("資料更新", "匯入新資料（例如 12 月 2 日的 M-D）、還原、更新紀錄") { navigate(Routes.DATA) } }
            item {
                SectionCard("之後的版本") {
                    listOf(
                        "完整離線整合・測試及發佈" to "Phase 7–8",
                    ).forEach { (name, phase) ->
                        Row(Modifier.fillMaxWidth()) {
                            Text(name, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(phase, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuItem(title: String, desc: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(desc, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
