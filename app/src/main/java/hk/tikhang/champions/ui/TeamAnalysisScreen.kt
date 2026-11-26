@file:OptIn(ExperimentalLayoutApi::class)

package hk.tikhang.champions.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hk.tikhang.champions.core.TeamAnalysisEngine
import hk.tikhang.champions.core.TeamAnalysisEngine.Analysis
import hk.tikhang.champions.core.TeamAnalysisEngine.Effect
import hk.tikhang.champions.data.AppViewModel
import hk.tikhang.champions.data.DataState

private val AnalysisTabs = listOf("總覽", "防守", "攻擊", "配合", "報告")

/** Team Analysis Dashboard (spec §8.5) and Report (spec §8.6). */
@Composable
fun TeamAnalysisScreen(vm: AppViewModel, state: DataState, teamId: Long, onBack: () -> Unit, openPokemon: (String) -> Unit) {
    val teams by vm.teams.collectAsStateWithLifecycle()
    val team = teams.firstOrNull { it.id == teamId }?.team()
    if (team == null) {
        Screen("隊伍分析", onBack) { pad -> Text("載入中…", Modifier.padding(pad).padding(16.dp)) }
        return
    }
    var useMega by rememberSaveable { mutableStateOf(true) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val a = remember(team, useMega, state) { TeamAnalysisEngine.analyse(state.snapshot, state.typeEngine, team, useMega) }

    Screen("隊伍分析", onBack, subtitle = "${team.name}・${team.regulationId}") { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (team.members.any { it.megaFormId != null }) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("分析形態", style = MaterialTheme.typography.labelLarge)
                    FilterChip(selected = useMega, onClick = { useMega = true }, label = { Text("Mega 形態") })
                    FilterChip(selected = !useMega, onClick = { useMega = false }, label = { Text("原本形態") })
                }
            }
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                AnalysisTabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
            }
            if (a.members.isEmpty()) {
                Text("隊伍未有成員。", Modifier.padding(16.dp))
                return@Column
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (tab) {
                    0 -> item { Overview(a) }
                    1 -> item { DefenseMatrix(a) }
                    2 -> {
                        item { OffenseTable(a) }
                        item { ThreatList("隊伍招式難以打擊的 Pokémon", "按屬性計算，未計對手特性及道具", a.walls, state, openPokemon) }
                    }
                    3 -> {
                        item { SynergyCard(a) }
                        item { ThreatList("屬性上威脅較大的 Pokémon", "對手本身屬性（STAB）可效果絕佳打擊一半或以上成員；未計實際招式", a.stabThreats, state, openPokemon) }
                    }
                    else -> item { ReportCard(a) }
                }
                item {
                    Text(
                        "所有結論由隊伍的實際形態、特性、招式、能力值及屬性相性表計算。標示「分析建議」的內容是估算，不代表必然最佳。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ---- 總覽 --------------------------------------------------------------------------------

@Composable
private fun Overview(a: Analysis) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("隊伍成員") {
            a.members.forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(m.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    if (m.form.isMega) Badge("Mega", Color(0xFF8E24AA))
                    TypeRow(m.form.types)
                }
            }
            if (a.megaCount > 1) Text("有 ${a.megaCount} 隻設定了 Mega；每場只可 Mega 一次。", color = Color(0xFFB26A00), style = MaterialTheme.typography.bodySmall)
        }
        SectionCard("隊伍屬性分佈") {
            val counts = a.members.flatMap { it.form.types }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                counts.forEach { (t, n) -> Row(verticalAlignment = Alignment.CenterVertically) { TypeChip(t); Text(" ×$n", style = MaterialTheme.typography.bodySmall) } }
            }
        }
        SectionCard("防守摘要") {
            val shared = a.defense.filter { it.isShared }
            val gaps = a.defense.filter { it.isGap && it.weak > 0 }
            LabelValue("共同弱點", shared.joinToString("、") { "${TypeZh[it.type]}（${it.weak}）" }.ifEmpty { "沒有" })
            LabelValue("無人抵抗", gaps.joinToString("、") { TypeZh[it.type] ?: it.type }.ifEmpty { "沒有" })
            LabelValue("無效屬性", a.defense.filter { it.immune > 0 }.joinToString("、") { "${TypeZh[it.type]}（${it.immune}）" }.ifEmpty { "沒有" })
        }
        SectionCard("攻擊摘要") {
            val se = a.offense.count { it.superEffective.isNotEmpty() }
            LabelValue("效果絕佳覆蓋", "$se / ${a.offense.size} 種屬性")
            LabelValue("覆蓋缺口", a.offense.filter { it.isGap }.joinToString("、") { TypeZh[it.type] ?: it.type }.ifEmpty { "沒有" })
            LabelValue("難以打擊", "${a.walls.size} 隻可用 Pokémon")
        }
        SectionCard("物理／特殊／輔助") {
            val phys = a.roles.sumOf { it.physical }; val spec = a.roles.sumOf { it.special }; val stat = a.roles.sumOf { it.status }
            DistributionBar(listOf("物理" to phys to Color(0xFFE64A19), "特殊" to spec to Color(0xFF1E88E5), "變化" to stat to Color(0xFF757575)))
            a.roles.forEach { r -> LabelValue(r.member.name, "${r.role.zh}（物理 ${r.physical}・特殊 ${r.special}・變化 ${r.status}）") }
        }
        SectionCard("速度分佈（Lv50，已計 SP／性格／Mega）") {
            val max = (a.speedOrder.maxOfOrNull { it.second } ?: 1).coerceAtLeast(1)
            a.speedOrder.forEach { (m, spe) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(m.name, Modifier.width(110.dp), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                        Box(Modifier.fillMaxWidth(spe / max.toFloat()).height(10.dp).background(MaterialTheme.colorScheme.primary))
                    }
                    Text("$spe", Modifier.width(44.dp).padding(start = 6.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("詳細 Speed Tier 分析會在 Phase 4 加入。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DistributionBar(parts: List<Pair<Pair<String, Int>, Color>>) {
    val total = parts.sumOf { it.first.second }.coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp))) {
        parts.filter { it.first.second > 0 }.forEach { (p, c) -> Box(Modifier.weight(p.second.toFloat() / total).height(14.dp).background(c)) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        parts.forEach { (p, c) -> Text("${p.first} ${p.second}", color = c, style = MaterialTheme.typography.bodySmall) }
    }
}

// ---- 防守 Matrix ---------------------------------------------------------------------------

private fun effectColor(e: Effect): Color = when (e) {
    Effect.WEAK_4 -> Color(0xFFB71C1C); Effect.WEAK -> Color(0xFFE57373); Effect.NEUTRAL -> Color.Transparent
    Effect.RESIST -> Color(0xFF81C784); Effect.RESIST_4 -> Color(0xFF388E3C); Effect.IMMUNE -> Color(0xFF455A64)
}

@Composable
private fun DefenseMatrix(a: Analysis) {
    var selected by remember { mutableStateOf<String?>(null) }
    SectionCard("防守矩陣（攻擊屬性 → 每位成員受到的倍率）") {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            Column {
                Row {
                    Text("屬性", Modifier.width(64.dp), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    a.members.forEach { m -> Text(m.name.take(4), Modifier.width(40.dp), fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 1) }
                    listOf("弱", "抗", "無").forEach { Text(it, Modifier.width(28.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
                }
                a.defense.forEach { r ->
                    val rowBg = if (r.isShared) Color(0x22E53935) else Color.Transparent
                    Row(Modifier.background(rowBg).clickable { selected = if (selected == r.type) null else r.type }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(64.dp)) { TypeChip(r.type) }
                        r.perMember.forEach { m ->
                            val e = Effect.of(m)
                            Box(Modifier.width(40.dp).padding(2.dp).clip(RoundedCornerShape(4.dp)).background(effectColor(e)), contentAlignment = Alignment.Center) {
                                Text(e.symbol, fontSize = 12.sp, color = if (e == Effect.NEUTRAL) MaterialTheme.colorScheme.onSurfaceVariant else Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                        Text("${r.weak}", Modifier.width(28.dp), textAlign = TextAlign.Center, fontSize = 12.sp, color = if (r.weak >= 3) Color(0xFFC62828) else Color.Unspecified)
                        Text("${r.resist}", Modifier.width(28.dp), textAlign = TextAlign.Center, fontSize = 12.sp)
                        Text("${r.immune}", Modifier.width(28.dp), textAlign = TextAlign.Center, fontSize = 12.sp)
                    }
                }
            }
        }
        Text("紅底 = 共同弱點。已計特性（例如 Levitate、Thick Fat、Filter）。點擊屬性查看詳情。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        selected?.let { t ->
            val r = a.defense.first { it.type == t }
            HorizontalDivider()
            Text("${TypeZh[t]}屬性攻擊", style = MaterialTheme.typography.titleSmall)
            a.members.forEachIndexed { i, m ->
                val note = m.ability?.let { TeamAnalysisEngine.abilityLabel(hk.tikhang.champions.core.toId(it)) }?.let { "（特性 ${m.ability}：$it）" } ?: ""
                Text("${m.name}：×${TeamAnalysisEngine.fmt(r.perMember[i])}$note", style = MaterialTheme.typography.bodySmall)
            }
            val attackers = a.offense.first { it.type == t }
            Text("可效果絕佳打擊 ${TypeZh[t]} 的招式：" + attackers.superEffective.joinToString("、") { "${it.member.name}・${it.move.nameZh ?: it.move.name}" }.ifEmpty { "沒有" },
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---- 攻擊 ----------------------------------------------------------------------------------

@Composable
private fun OffenseTable(a: Analysis) {
    SectionCard("攻擊覆蓋（以實際招式計算，對單一屬性）") {
        if (a.members.none { it.damaging.isNotEmpty() }) {
            Text("未設定攻擊招式。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@SectionCard
        }
        a.offense.forEach { r ->
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Box(Modifier.width(64.dp)) { TypeChip(r.type) }
                val label = when {
                    r.superEffective.isNotEmpty() -> "×${TeamAnalysisEngine.fmt(r.best)}"
                    r.best == 1.0 -> "只有一般"
                    r.hits.isEmpty() -> "—"
                    else -> "最多 ×${TeamAnalysisEngine.fmt(r.best)}"
                }
                Text(label, Modifier.width(72.dp), fontWeight = FontWeight.Bold, fontSize = 13.sp,
                    color = if (r.isGap) Color(0xFFC62828) else Color(0xFF2E7D32))
                Text(
                    r.superEffective.distinctBy { it.member.index to it.move.id }.joinToString("、") { "${it.member.name.take(5)}・${it.move.nameZh ?: it.move.name}" },
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Text("紅色 = 覆蓋缺口（沒有效果絕佳的招式）。已計 Pixilate／Dragonize 等特性及 Freeze-Dry 等特殊招式。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ThreatList(title: String, note: String, threats: List<TeamAnalysisEngine.Threat>, state: DataState, open: (String) -> Unit) {
    SectionCard("$title（${threats.size}）") {
        Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        threats.take(15).forEach { t ->
            Column(Modifier.fillMaxWidth().clickable { open(t.pokemon.id) }.padding(vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(state.snapshot.displayName(t.pokemon), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TypeRow(t.pokemon.types)
                }
                Text(t.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (threats.size > 15) Text("…另有 ${threats.size - 15} 隻", style = MaterialTheme.typography.bodySmall)
        if (threats.isEmpty()) Text("沒有", style = MaterialTheme.typography.bodySmall)
    }
}

// ---- 配合 ----------------------------------------------------------------------------------

@Composable
private fun SynergyCard(a: Analysis) {
    SectionCard("隊伍配合（Synergy）") {
        a.synergy.forEach { e ->
            Column(Modifier.padding(vertical = 2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(e.kind.zh, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (e.providers.isEmpty()) Badge("沒有", Color(0xFF9E9E9E)) else Badge("${e.providers.size}", Color(0xFF2E7D32))
                }
                if (e.providers.isNotEmpty()) Text(e.providers.joinToString("、") { (m, w) -> "${m.name}（$w）" }, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("只分析 Pokémon Champions 支援的機制（天氣、場地、Trick Room、Tailwind 等）。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---- 報告 ----------------------------------------------------------------------------------

@Composable
private fun ReportCard(a: Analysis) {
    val r = a.report
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ReportSection("優勢（Strengths）", r.strengths, Color(0xFF2E7D32))
        ReportSection("弱點（Weaknesses）", r.weaknesses, Color(0xFFC62828))
        ReportSection("覆蓋缺口（Coverage Gaps）", r.coverageGaps, Color(0xFFB26A00))
        ReportSection("配合（Synergy）", r.synergy, MaterialTheme.colorScheme.secondary)
        SectionCard("改善方向（Potential Improvement）") {
            if (r.suggestions.isEmpty()) Text("未發現明顯需要改善的地方。", style = MaterialTheme.typography.bodySmall)
            r.suggestions.forEach { sg ->
                Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(sg.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Badge("分析建議", Color(0xFFB26A00))
                    }
                    LabelValue("原隊伍問題", sg.problem)
                    LabelValue("理由", sg.reason)
                    LabelValue("優勢", sg.benefit)
                    LabelValue("代價", sg.cost)
                    LabelValue("可能新弱點", sg.newWeaknesses)
                    HorizontalDivider(Modifier.padding(top = 6.dp))
                }
            }
            Text("替換建議只按屬性及特性估算，新成員的招式需要自行設定。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ReportSection(title: String, lines: List<String>, color: Color) {
    SectionCard(title) {
        if (lines.isEmpty()) Text("沒有", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        lines.forEach { Text("・$it", style = MaterialTheme.typography.bodyMedium, color = color) }
    }
}
