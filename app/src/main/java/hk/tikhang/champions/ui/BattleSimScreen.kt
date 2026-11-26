@file:OptIn(ExperimentalLayoutApi::class)

package hk.tikhang.champions.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hk.tikhang.champions.core.BattleEngine
import hk.tikhang.champions.core.BattleEngine.Accuracy
import hk.tikhang.champions.core.BattleEngine.Action
import hk.tikhang.champions.core.BattleEngine.Battle
import hk.tikhang.champions.core.BattleEngine.Kind
import hk.tikhang.champions.core.BattleEngine.Target
import hk.tikhang.champions.core.BattleFormat
import hk.tikhang.champions.core.DamageEngine
import hk.tikhang.champions.core.SpeedEngine
import hk.tikhang.champions.core.Stat
import hk.tikhang.champions.core.Team
import hk.tikhang.champions.core.TeamEngine
import hk.tikhang.champions.data.AppViewModel
import hk.tikhang.champions.data.DataState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun accColor(a: Accuracy) = when (a) {
    Accuracy.VERIFIED -> Color.Unspecified; Accuracy.FORMULA -> Color.Unspecified
    Accuracy.ESTIMATED -> Color(0xFFB26A00); Accuracy.UNSUPPORTED -> Color(0xFFC62828)
}

/** Battle Simulator (spec §13): A single turn, B multi-turn, C scenario testing. */
@Composable
fun BattleSimScreen(vm: AppViewModel, state: DataState, teamId: Long, onBack: () -> Unit) {
    val s = state.snapshot
    val teams by vm.teams.collectAsStateWithLifecycle()
    val myTeam = teams.firstOrNull { it.id == teamId }?.team()
    if (myTeam == null) {
        Screen("對戰模擬", onBack) { pad -> Text("載入中…", Modifier.padding(pad).padding(16.dp)) }
        return
    }
    val others = teams.filter { it.id != teamId }.mapNotNull { e -> e.team()?.let { e.id to it } }
        .sortedByDescending { it.second.opponent }
    var format by remember { mutableStateOf(myTeam.format) }
    val bringCount = format.bring
    var oppId by remember { mutableStateOf(others.firstOrNull()?.first) }
    val oppTeam = others.firstOrNull { it.first == oppId }?.second
    val myBring = remember { mutableStateListOf<Int>() }
    val oppBring = remember(oppId) { mutableStateListOf<Int>() }
    var rollMode by remember { mutableStateOf(BattleEngine.RollMode.RANDOM) }
    var critMode by remember { mutableStateOf(BattleEngine.CritMode.RANDOM) }
    var scenario by remember { mutableStateOf(BattleEngine.Scenario()) }
    var showScenario by remember { mutableStateOf(false) }
    var battle by remember { mutableStateOf<Battle?>(null) }
    var version by remember { mutableIntStateOf(0) }
    val choices = remember { mutableStateMapOf<Pair<Int, Int>, Action>() }
    var aiOpponent by remember { mutableStateOf(true) }
    var mcText by remember { mutableStateOf<List<String>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun brought(team: Team, picks: List<Int>): List<hk.tikhang.champions.core.TeamSlot> {
        val members = team.slots.withIndex().filter { it.value != null }
        val chosen = picks.mapNotNull { i -> members.firstOrNull { it.index == i }?.value }
        return (chosen + members.map { it.value!! }.filter { it !in chosen }).take(bringCount)
    }
    fun setup(): BattleEngine.Setup? {
        val opp = oppTeam ?: return null
        return BattleEngine.Setup(format, listOf(brought(myTeam, myBring), brought(opp, oppBring)),
            names = listOf("我方", "對手"), scenario = scenario, rollMode = rollMode, critMode = critMode)
    }

    Screen("對戰模擬", onBack, subtitle = "${myTeam.name}・${format.zh}") { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (battle == null) {
                item {
                    SectionCard("設定") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            BattleFormat.entries.forEach { f -> FilterChip(selected = format == f, onClick = { format = f; myBring.clear(); oppBring.clear() }, label = { Text(f.zh) }) }
                            Picker("傷害隨機數：${rollMode.zh}", BattleEngine.RollMode.entries.toList(), { it.zh }) { rollMode = it }
                            Picker("擊中要害：${critMode.zh}", BattleEngine.CritMode.entries.toList(), { it.zh }) { critMode = it }
                        }
                        Text("對手隊伍", style = MaterialTheme.typography.labelLarge)
                        if (others.isEmpty()) Text("未有其他隊伍。請在 Team Builder 建立或匯入對手隊伍。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            others.forEach { (id, t) -> FilterChip(selected = oppId == id, onClick = { oppId = id }, label = { Text((if (t.opponent) "對手：" else "") + t.name) }) }
                        }
                    }
                }
                item { BringCard(state, "我方出戰（按次序選 $bringCount 隻，首 ${if (format == BattleFormat.DOUBLES) 2 else 1} 隻先發）", myTeam, myBring, bringCount) }
                oppTeam?.let { t -> item { BringCard(state, "對手出戰", t, oppBring, bringCount) } }
                item {
                    SectionCard("情境設定（Scenario Testing）") {
                        OutlinedButton(onClick = { showScenario = !showScenario }) { Text(if (showScenario) "收起" else "設定開始時的天氣、場地、HP、狀態…") }
                        if (showScenario) ScenarioEditor(state, scenario, oppTeam?.let { listOf(brought(myTeam, myBring), brought(it, oppBring)) }) { scenario = it }
                    }
                }
                item {
                    Button(onClick = {
                        setup()?.let { st -> battle = Battle(s, st, System.currentTimeMillis()); choices.clear(); mcText = emptyList(); version++ }
                    }, enabled = oppTeam != null, modifier = Modifier.fillMaxWidth()) { Text("開始模擬") }
                }
            }

            battle?.let { b ->
                item(key = "state$version") { BattleStateCard(state, b) }
                if (!b.over) {
                    item(key = "actions$version") {
                        SectionCard("第 ${b.turn + 1} 回合行動") {
                            FilterChip(selected = aiOpponent, onClick = { aiOpponent = !aiOpponent }, label = { Text("對手由 AI 選擇") })
                            for (side in 0..1) {
                                if (side == 1 && aiOpponent) continue
                                b.sides[side].active.forEachIndexed { pos, idx ->
                                    if (idx >= 0 && !b.sides[side].mons[idx].fainted) ActionPicker(state, b, side, pos, choices)
                                }
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Button(onClick = {
                                    val c = HashMap(choices)
                                    if (aiOpponent) c.putAll(BattleEngine.aiChoices(b, 1))
                                    b.runTurn(c); choices.clear(); version++
                                }) { Text("執行回合") }
                                OutlinedButton(onClick = { choices.putAll(BattleEngine.aiChoices(b, 0)); version++ }) { Text("我方用 AI 建議") }
                                OutlinedButton(onClick = {
                                    while (!b.over) b.runTurn(BattleEngine.aiChoices(b, 0) + BattleEngine.aiChoices(b, 1))
                                    choices.clear(); version++
                                }) { Text("AI 自動對戰至結束") }
                            }
                            Text("AI：選擇預期傷害最高的招式及目標（簡單策略），只供參考。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item {
                    SectionCard("多次模擬（以開始時狀態計算）") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedButton(enabled = !running, onClick = {
                                val st = b.setup; val c = HashMap(choices).also { if (aiOpponent) it.putAll(BattleEngine.aiChoices(Battle(s, st, 0), 1)) }
                                running = true
                                scope.launch {
                                    val r = withContext(Dispatchers.Default) { BattleEngine.simulateTurn(s, st, c, 200) }
                                    mcText = listOf("第一回合 × ${r.runs} 次（用上方已選行動；未選的用第一個招式）") +
                                        r.firstMover.entries.sortedByDescending { it.value }.map { "先行動：${it.key} ${"%.0f".format(it.value * 100)}%" } +
                                        r.faintChance.entries.sortedByDescending { it.value }.map { "被擊倒：${it.key} ${"%.1f".format(it.value * 100)}%" } +
                                        r.avgHpLeft.entries.map { "平均剩餘 HP：${it.key} ${"%.0f".format(it.value)}%" }
                                    running = false
                                }
                            }) { Text("第一回合 ×200") }
                            OutlinedButton(enabled = !running, onClick = {
                                val st = b.setup
                                running = true
                                scope.launch {
                                    val r = withContext(Dispatchers.Default) { BattleEngine.simulateBattles(s, st, 100) }
                                    mcText = listOf(
                                        "完整對戰 × ${r.runs} 次（雙方 AI）",
                                        "我方勝率 ${"%.0f".format(r.winRate[0] * 100)}%・對手勝率 ${"%.0f".format(r.winRate[1] * 100)}%・和局 ${"%.0f".format(r.drawRate * 100)}%",
                                        "平均 ${"%.1f".format(r.avgTurns)} 回合",
                                        "勝率取決於簡單 AI 的選擇，屬估算（Estimated Simulation）。",
                                    )
                                    running = false
                                }
                            }) { Text("完整對戰 ×100（AI）") }
                        }
                        if (running) Text("計算中…", style = MaterialTheme.typography.bodySmall)
                        mcText.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { battle = Battle(s, b.setup, System.currentTimeMillis()); choices.clear(); version++ }) { Text("重新開始") }
                        OutlinedButton(onClick = { battle = null; choices.clear(); mcText = emptyList() }) { Text("返回設定") }
                    }
                }
                item { AccuracyLegend() }
                items(b.log.reversed()) { e -> LogRow(e) }
            }
        }
    }
}

@Composable
private fun BringCard(state: DataState, title: String, team: Team, picks: MutableList<Int>, count: Int) {
    val s = state.snapshot
    SectionCard(title) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            team.slots.forEachIndexed { i, sl ->
                if (sl == null) return@forEachIndexed
                val order = picks.indexOf(i)
                FilterChip(selected = order >= 0, onClick = {
                    if (order >= 0) picks.remove(i) else if (picks.size < count) picks.add(i)
                }, label = { Text((if (order >= 0) "${order + 1}. " else "") + (TeamEngine.effectiveForm(s, sl)?.let { s.displayName(it) } ?: sl.speciesId)) })
            }
        }
        if (picks.size < count) Text("未選的位置會按隊伍次序補上。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        team.members.filter { it.moves.isEmpty() }.takeIf { it.isNotEmpty() }?.let {
            Text("注意：有成員未設定招式，模擬時會使用「掙扎」。", style = MaterialTheme.typography.bodySmall, color = Color(0xFFB26A00))
        }
    }
}

@Composable
private fun ScenarioEditor(state: DataState, sc: BattleEngine.Scenario, sides: List<List<hk.tikhang.champions.core.TeamSlot>>?, onChange: (BattleEngine.Scenario) -> Unit) {
    val s = state.snapshot
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Picker("天氣：${sc.weather.zh}", SpeedEngine.Weather.entries.toList(), { it.zh }) { onChange(sc.copy(weather = it)) }
            Picker("場地：${sc.terrain.zh}", DamageEngine.Terrain.entries.toList(), { it.zh }) { onChange(sc.copy(terrain = it)) }
            FilterChip(selected = sc.trickRoom, onClick = { onChange(sc.copy(trickRoom = !sc.trickRoom)) }, label = { Text("戲法空間") })
            listOf("我方", "對手").forEachIndexed { i, n ->
                FilterChip(selected = sc.tailwind[i], onClick = { onChange(sc.copy(tailwind = sc.tailwind.toMutableList().also { it[i] = !it[i] })) }, label = { Text("${n}順風") })
                FilterChip(selected = sc.reflect[i], onClick = { onChange(sc.copy(reflect = sc.reflect.toMutableList().also { it[i] = !it[i] })) }, label = { Text("${n}反射壁") })
                FilterChip(selected = sc.lightScreen[i], onClick = { onChange(sc.copy(lightScreen = sc.lightScreen.toMutableList().also { it[i] = !it[i] })) }, label = { Text("${n}光牆") })
            }
            FilterChip(selected = sc.skipSwitchInAbilities, onClick = { onChange(sc.copy(skipSwitchInAbilities = !sc.skipSwitchInAbilities)) }, label = { Text("略過出場特性（威嚇等）") })
        }
        sides?.forEachIndexed { side, slots ->
            Text(if (side == 0) "我方" else "對手", style = MaterialTheme.typography.labelLarge)
            slots.forEachIndexed { j, sl ->
                val hp = sc.hpPercent.getOrNull(side)?.getOrNull(j) ?: 100
                val st = sc.status.getOrNull(side)?.getOrNull(j) ?: DamageEngine.Status.NONE
                fun <T> setAt(list: List<List<T>>, def: T, v: T): List<List<T>> = list.mapIndexed { i, l ->
                    if (i != side) l else List(maxOf(l.size, j + 1)) { k -> if (k == j) v else l.getOrNull(k) ?: def }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(TeamEngine.effectiveForm(s, sl)?.let { s.displayName(it) } ?: sl.speciesId, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Picker("HP $hp%", listOf(100, 75, 50, 33, 25, 10, 1), { "$it%" }) { onChange(sc.copy(hpPercent = setAt(sc.hpPercent, 100, it))) }
                    Picker(st.zh, DamageEngine.Status.entries.toList(), { it.zh }) { onChange(sc.copy(status = setAt(sc.status, DamageEngine.Status.NONE, it))) }
                }
            }
        }
    }
}

@Composable
private fun BattleStateCard(state: DataState, b: Battle) {
    SectionCard(if (b.winner != null) "結果" else "第 ${b.turn} 回合後") {
        val field = buildList {
            if (b.weather != SpeedEngine.Weather.NONE) add("${b.weather.zh}（${b.weatherTurns}）")
            if (b.terrain != DamageEngine.Terrain.NONE) add("${b.terrain.zh}（${b.terrainTurns}）")
            if (b.trickRoom > 0) add("戲法空間（${b.trickRoom}）")
            b.sides.forEachIndexed { i, sd ->
                val n = b.setup.names[i]
                if (sd.tailwind > 0) add("${n}順風（${sd.tailwind}）")
                if (sd.reflect > 0) add("${n}反射壁（${sd.reflect}）")
                if (sd.lightScreen > 0) add("${n}光牆（${sd.lightScreen}）")
                if (sd.auroraVeil > 0) add("${n}極光幕（${sd.auroraVeil}）")
                if (sd.megaUsed) add("${n}已 Mega")
            }
        }
        if (field.isNotEmpty()) Text(field.joinToString("・"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        b.winner?.let { w -> Text(if (w < 0) "和局" else "${b.setup.names[w]}勝出", style = MaterialTheme.typography.titleMedium, color = if (w == 0) Color(0xFF2E7D32) else Color(0xFFC62828)) }
        b.sides.forEachIndexed { i, sd ->
            Text(b.setup.names[i], style = MaterialTheme.typography.labelLarge)
            sd.mons.forEach { m ->
                val active = sd.posOf(m) >= 0
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text((if (active) "▶ " else "　") + m.displayName, Modifier.width(120.dp), fontSize = 12.sp, maxLines = 1,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal, color = if (m.fainted) Color.Gray else Color.Unspecified)
                    Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                        val frac = (m.hp.toFloat() / m.maxHp).coerceIn(0f, 1f)
                        if (frac > 0f) Box(Modifier.fillMaxWidth(frac).height(8.dp).background(when { frac > 0.5f -> Color(0xFF43A047); frac > 0.2f -> Color(0xFFFBC02D); else -> Color(0xFFE53935) }))
                    }
                    Text("${"%.0f".format(m.hpPercent)}%", Modifier.width(40.dp), fontSize = 11.sp)
                }
                val extra = buildList {
                    if (m.status != DamageEngine.Status.NONE) add(m.status.zh)
                    m.boosts.filterValues { it != 0 }.forEach { (st, v) -> add("${st.zh}${if (v > 0) "+" else ""}$v") }
                }
                if (extra.isNotEmpty() && !m.fainted) Text("　　" + extra.joinToString(" "), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ActionPicker(state: DataState, b: Battle, side: Int, pos: Int, choices: MutableMap<Pair<Int, Int>, Action>) {
    val m = b.sides[side].mons[b.sides[side].active[pos]]
    val cur = choices[side to pos]
    val curMove = (cur as? Action.UseMove)
    Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${b.setup.names[side]}・${m.displayName}", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            m.moves.forEach { mv ->
                FilterChip(selected = curMove?.moveId == mv.id, onClick = {
                    val foe = b.foesOf(m).firstOrNull()
                    choices[side to pos] = Action.UseMove(mv.id, curMove?.target ?: foe?.let { Target(it.side, b.sides[it.side].posOf(it)) }, curMove?.mega ?: false)
                }, label = { Text(mv.nameZh ?: mv.name) })
            }
        }
        if (curMove != null) {
            val mv = m.moves.firstOrNull { it.id == curMove.moveId }
            val single = mv != null && mv.target in setOf("normal", "any", "adjacentFoe", "adjacentAllyOrSelf", "adjacentAlly")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (single && b.activeCount == 2) {
                    val targets = b.foesOf(m) + listOfNotNull(b.allyOf(m))
                    targets.forEach { t ->
                        val tg = Target(t.side, b.sides[t.side].posOf(t))
                        FilterChip(selected = curMove.target == tg, onClick = { choices[side to pos] = curMove.copy(target = tg) },
                            label = { Text("→ ${if (t.side == side) "隊友" else ""}${t.displayName}") })
                    }
                }
                if (m.canMega && !b.sides[side].megaUsed) {
                    FilterChip(selected = curMove.mega, onClick = { choices[side to pos] = curMove.copy(mega = !curMove.mega) }, label = { Text("Mega Evolution") })
                }
            }
        }
        val bench = b.sides[side].bench()
        if (bench.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                bench.forEach { bm ->
                    FilterChip(selected = (cur as? Action.Switch)?.benchIndex == bm.index, onClick = { choices[side to pos] = Action.Switch(bm.index) },
                        label = { Text("換入 ${bm.displayName}") })
                }
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun AccuracyLegend() {
    Text("記錄（最新在上）・橙色 = 估算，紅色 = 未支援；傷害使用已驗證的傷害公式。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun LogRow(e: BattleEngine.LogEntry) {
    val bold = e.kind == Kind.TURN || e.kind == Kind.RESULT
    Row(Modifier.fillMaxWidth()) {
        Text(
            e.text + if (e.accuracy == Accuracy.ESTIMATED || e.accuracy == Accuracy.UNSUPPORTED) "〔${e.accuracy.zh}〕" else "",
            fontSize = if (bold) 13.sp else 12.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            color = if (e.kind == Kind.FAINT) Color(0xFFC62828) else accColor(e.accuracy),
        )
    }
}
