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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hk.tikhang.champions.core.DamageEngine
import hk.tikhang.champions.core.DamageEngine.Field
import hk.tikhang.champions.core.DamageEngine.GameType
import hk.tikhang.champions.core.DamageEngine.Status
import hk.tikhang.champions.core.DamageEngine.Terrain
import hk.tikhang.champions.core.SpeedEngine.Weather
import hk.tikhang.champions.core.Stat
import hk.tikhang.champions.core.StatsEngine
import hk.tikhang.champions.core.TeamEngine
import hk.tikhang.champions.core.TeamSlot
import hk.tikhang.champions.core.Verification
import hk.tikhang.champions.data.AppViewModel
import hk.tikhang.champions.data.DataState

/** One side of the calculator: a team-style slot plus battle state. */
data class CalcSide(
    val slot: TeamSlot,
    val status: Status = Status.NONE,
    val boosts: Map<Stat, Int> = emptyMap(),
    val hpPercent: Int = 100,
    val abilityActive: Boolean = false,
)

private enum class DefPreset(val zh: String) { NONE("無投資"), HP("HP 32"), BULK("HP 32＋防禦／特防 32") }

/** Damage Calculator (spec §12). teamId = 0 → without a team. */
@Composable
fun DamageCalcScreen(vm: AppViewModel, state: DataState, teamId: Long, onBack: () -> Unit) {
    val s = state.snapshot
    val teams by vm.teams.collectAsStateWithLifecycle()
    val team = if (teamId == 0L) null else teams.firstOrNull { it.id == teamId }?.team()
    val reg = team?.let { s.regulationById[it.regulationId] } ?: state.regulation
    val allowed = reg.allowedPokemon?.toSet()

    var advanced by rememberSaveable { mutableStateOf(false) }
    var attacker by remember { mutableStateOf<CalcSide?>(team?.members?.firstOrNull()?.let { CalcSide(it) }) }
    var defender by remember { mutableStateOf<CalcSide?>(null) }
    var moveId by remember { mutableStateOf<String?>(attacker?.slot?.moves?.firstOrNull()) }
    var field by remember { mutableStateOf(Field()) }
    var picking by remember { mutableStateOf<String?>(null) }   // "atk" / "def"
    var movePicker by remember { mutableStateOf(false) }
    var itemPickerFor by remember { mutableStateOf<String?>(null) }

    fun defaultAttacker(id: String): CalcSide {
        val p = s.pokemonById.getValue(id)
        val base = if (p.isMega) s.pokemonById[p.megaOf] ?: p else p
        val physical = p.baseStats.atk >= p.baseStats.spa
        var slot = TeamEngine.newSlot(s, base.id).copy(nature = if (physical) "adamant" else "modest",
            sp = mapOf((if (physical) Stat.ATK else Stat.SPA) to 32, Stat.HP to 32, Stat.SPE to 2))
        if (p.isMega) slot = TeamEngine.toggleMega(s, slot, p.id)
        return CalcSide(slot)
    }
    fun defaultDefender(id: String, preset: DefPreset = DefPreset.HP): CalcSide {
        val p = s.pokemonById.getValue(id)
        val base = if (p.isMega) s.pokemonById[p.megaOf] ?: p else p
        var slot = TeamEngine.newSlot(s, base.id)
        if (p.isMega) slot = TeamEngine.toggleMega(s, slot, p.id)
        return CalcSide(applyPreset(slot, preset, null))
    }

    val move = moveId?.let { s.moveById[it] }
    val atkSide = attacker?.let { DamageEngine.sideFromSlot(s, it.slot, it.status, it.boosts, it.hpPercent, it.abilityActive) }
    val defSide = defender?.let { DamageEngine.sideFromSlot(s, it.slot, it.status, it.boosts, it.hpPercent, it.abilityActive) }
    val result = if (atkSide != null && defSide != null && move != null) DamageEngine.calculate(s, atkSide, defSide, move, field) else null

    Screen("傷害計算", onBack, subtitle = (team?.name?.let { "$it・" } ?: "") + reg.id + "・Lv50") { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = !advanced, onClick = { advanced = false }, label = { Text("基本") })
                    FilterChip(selected = advanced, onClick = { advanced = true }, label = { Text("進階") })
                    if (attacker != null && defender != null) TextButton(onClick = {
                        val a = attacker; attacker = defender; defender = a; moveId = attacker?.slot?.moves?.firstOrNull()
                    }) { Text("⇄ 對調") }
                }
            }
            // Result first, so it stays visible while changing settings below.
            result?.let { r -> item { ResultCard(state, r, atkSide!!, defSide!!, move!!, field, attacker!!, defender!!) } }
            item {
                SideCard(state, "攻擊方", attacker, team?.members.orEmpty(), advanced, isAttacker = true,
                    onPick = { picking = "atk" }, onTeam = { attacker = CalcSide(it); moveId = it.moves.firstOrNull() },
                    onChange = { attacker = it }, onItem = { itemPickerFor = "atk" })
            }
            item {
                SectionCard("招式") {
                    OutlinedButton(onClick = { movePicker = true }, enabled = attacker != null, modifier = Modifier.fillMaxWidth()) {
                        Text(move?.let { "${it.nameZh ?: it.name}（${it.name}）" } ?: "選擇招式")
                    }
                    attacker?.slot?.moves?.takeIf { it.isNotEmpty() }?.let { ms ->
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ms.forEach { id -> FilterChip(selected = moveId == id, onClick = { moveId = id }, label = { Text(s.moveById[id]?.let { it.nameZh ?: it.name } ?: id) }) }
                        }
                    }
                    move?.let { MoveSummary(it) }
                }
            }
            item {
                SideCard(state, "防守方", defender, team?.members.orEmpty(), advanced, isAttacker = false,
                    onPick = { picking = "def" }, onTeam = { defender = CalcSide(it) },
                    onChange = { defender = it }, onItem = { itemPickerFor = "def" })
            }
            item { FieldCard(field, move, advanced) { field = it } }
            item {
                Text("傷害公式：第 9 世代流程（Champions 沿用），已用 Smogon Damage Calculator 的測試結果驗證；數值使用 Champions 資料（Lv50、Stat Points、Champions 招式調整）。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    picking?.let { which ->
        PokemonSearchDialog(state, allowed, onPick = { id ->
            if (which == "atk") { attacker = defaultAttacker(id); moveId = null } else defender = defaultDefender(id)
            picking = null
        }) { picking = null }
    }
    if (movePicker) attacker?.let { a ->
        MovePickerDialog(state, a.slot.speciesId, exclude = emptySet(), onPick = { moveId = it; movePicker = false }) { movePicker = false }
    }
    itemPickerFor?.let { which ->
        ItemPickerDialog(state, onPick = { id ->
            if (which == "atk") attacker = attacker?.let { it.copy(slot = it.slot.copy(item = id)) } else defender = defender?.let { it.copy(slot = it.slot.copy(item = id)) }
            itemPickerFor = null
        }) { itemPickerFor = null }
    }
}

private fun applyPreset(slot: TeamSlot, preset: DefPreset, physical: Boolean?): TeamSlot = when (preset) {
    DefPreset.NONE -> slot.copy(sp = emptyMap(), nature = "serious")
    DefPreset.HP -> slot.copy(sp = mapOf(Stat.HP to 32), nature = "serious")
    DefPreset.BULK -> if (physical != false) slot.copy(sp = mapOf(Stat.HP to 32, Stat.DEF to 32), nature = "impish")
    else slot.copy(sp = mapOf(Stat.HP to 32, Stat.SPD to 32), nature = "careful")
}

@Composable
private fun SideCard(
    state: DataState, title: String, side: CalcSide?, teamSlots: List<TeamSlot>, advanced: Boolean, isAttacker: Boolean,
    onPick: () -> Unit, onTeam: (TeamSlot) -> Unit, onChange: (CalcSide) -> Unit, onItem: () -> Unit,
) {
    val s = state.snapshot
    SectionCard(title) {
        if (teamSlots.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                teamSlots.forEach { sl ->
                    val f = TeamEngine.effectiveForm(s, sl)
                    FilterChip(selected = side?.slot == sl, onClick = { onTeam(sl) }, label = { Text(f?.let { s.displayName(it) } ?: sl.speciesId) })
                }
            }
        }
        OutlinedButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
            Text(side?.let { sd -> TeamEngine.effectiveForm(s, sd.slot)?.let { s.displayName(it) } } ?: "選擇 Pokémon")
        }
        if (side == null) return@SectionCard
        val species = TeamEngine.species(s, side.slot) ?: return@SectionCard
        val form = TeamEngine.effectiveForm(s, side.slot) ?: return@SectionCard
        val stats = TeamEngine.finalStats(s, side.slot)
        TypeRow(form.types)
        val megas = s.megaOptions(species)
        if (megas.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = side.slot.megaFormId == null, onClick = { onChange(side.copy(slot = TeamEngine.toggleMega(s, side.slot, null))) }, label = { Text("不 Mega") })
                megas.forEach { m -> FilterChip(selected = side.slot.megaFormId == m.id, onClick = { onChange(side.copy(slot = TeamEngine.toggleMega(s, side.slot, m.id))) }, label = { Text(s.displayName(m)) }) }
            }
        }
        Text(
            "特性 ${TeamEngine.effectiveAbility(s, side.slot)?.let { s.ability(it)?.nameZh ?: it } ?: "—"}・道具 ${side.slot.item?.let { s.itemById[it]?.let { i -> i.nameZh ?: i.name } ?: it } ?: "沒有"}・" +
                natureLabel(s, side.slot.nature),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(Stat.entries.joinToString("　") { "${it.zh} ${stats[it]}" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!isAttacker && !advanced) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DefPreset.entries.forEach { p -> FilterChip(selected = false, onClick = { onChange(side.copy(slot = applyPreset(side.slot, p, null))) }, label = { Text(p.zh) }) }
            }
        }
        if (advanced) {
            HorizontalDivider()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!form.isMega) Picker("特性：${side.slot.ability ?: "—"}", species.abilities, { a -> s.ability(a)?.nameZh?.let { "$it（$a）" } ?: a }) { onChange(side.copy(slot = side.slot.copy(ability = it))) }
                OutlinedButton(onClick = onItem) { Text("道具") }
                Picker("性格", sortedNatureIds(s), { natureLabel(s, it) }) { onChange(side.copy(slot = side.slot.copy(nature = it))) }
                Picker("狀態：${side.status.zh}", Status.entries.toList(), { it.zh }) { onChange(side.copy(status = it)) }
                Picker("HP ${side.hpPercent}%", listOf(100, 90, 75, 66, 50, 33, 25, 10), { "$it%" }) { onChange(side.copy(hpPercent = it)) }
                FilterChip(selected = side.abilityActive, onClick = { onChange(side.copy(abilityActive = !side.abilityActive)) }, label = { Text("特性發動（引火／古代活性等）") })
            }
            val stagesFor = if (isAttacker) listOf(Stat.ATK, Stat.SPA, Stat.SPE) else listOf(Stat.DEF, Stat.SPD, Stat.SPE)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                stagesFor.forEach { st ->
                    val v = side.boosts[st] ?: 0
                    Picker("${st.zh} ${if (v > 0) "+" else ""}$v", (-6..6).toList(), { (if (it > 0) "+" else "") + it }) { onChange(side.copy(boosts = side.boosts + (st to it))) }
                }
            }
            SpEditor(state, side.slot) { st, v -> onChange(side.copy(slot = side.slot.copy(sp = side.slot.sp + (st to v)))) }
        }
    }
}

@Composable
private fun FieldCard(field: Field, move: hk.tikhang.champions.core.Move?, advanced: Boolean, onChange: (Field) -> Unit) {
    SectionCard("場地及條件") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FilterChip(selected = field.gameType == GameType.DOUBLES, onClick = { onChange(field.copy(gameType = GameType.DOUBLES)) }, label = { Text("雙打") })
            FilterChip(selected = field.gameType == GameType.SINGLES, onClick = { onChange(field.copy(gameType = GameType.SINGLES)) }, label = { Text("單打") })
            if (field.gameType == GameType.DOUBLES && move?.target in setOf("allAdjacentFoes", "allAdjacent")) {
                FilterChip(selected = field.spreadHit, onClick = { onChange(field.copy(spreadHit = !field.spreadHit)) }, label = { Text("同時命中兩個目標（×0.75）") })
            }
            FilterChip(selected = field.critical, onClick = { onChange(field.copy(critical = !field.critical)) }, label = { Text("擊中要害") })
            Picker("天氣：${field.weather.zh}", Weather.entries.toList(), { it.zh }) { onChange(field.copy(weather = it)) }
            if (advanced) {
                Picker("場地：${field.terrain.zh}", Terrain.entries.toList(), { it.zh }) { onChange(field.copy(terrain = it)) }
                FilterChip(selected = field.reflect, onClick = { onChange(field.copy(reflect = !field.reflect)) }, label = { Text("反射壁") })
                FilterChip(selected = field.lightScreen, onClick = { onChange(field.copy(lightScreen = !field.lightScreen)) }, label = { Text("光牆") })
                FilterChip(selected = field.auroraVeil, onClick = { onChange(field.copy(auroraVeil = !field.auroraVeil)) }, label = { Text("極光幕") })
                FilterChip(selected = field.helpingHand, onClick = { onChange(field.copy(helpingHand = !field.helpingHand)) }, label = { Text("幫助") })
                FilterChip(selected = field.friendGuard, onClick = { onChange(field.copy(friendGuard = !field.friendGuard)) }, label = { Text("友情防守") })
            }
            if (move?.multihit is kotlinx.serialization.json.JsonArray) {
                Picker("攻擊次數：${field.hits ?: "預設"}", listOf<Int?>(null, 2, 3, 4, 5), { it?.toString() ?: "預設（3；連續攻擊特性 5）" }) { onChange(field.copy(hits = it)) }
            }
        }
    }
}

@Composable
private fun ResultCard(
    state: DataState, r: DamageEngine.Result, atk: DamageEngine.Side, def: DamageEngine.Side,
    move: hk.tikhang.champions.core.Move, field: Field, attacker: CalcSide, defender: CalcSide,
) {
    val s = state.snapshot
    var showSurvival by remember { mutableStateOf(false) }
    SectionCard("結果") {
        Text("${s.displayName(atk.pokemon)} 的 ${move.nameZh ?: move.name} → ${s.displayName(def.pokemon)}", style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${r.min}–${r.max}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("（%.1f%%–%.1f%%）".format(r.minPercent, r.maxPercent), style = MaterialTheme.typography.titleMedium)
        }
        // HP bar: dark = guaranteed damage, light = possible extra.
        val hpFrac = def.curHp.toFloat() / def.maxHp
        Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            Box(Modifier.fillMaxWidth(hpFrac).height(14.dp).background(Color(0xFF43A047)))
            Box(Modifier.fillMaxWidth(((r.max.toFloat()) / def.maxHp).coerceAtMost(hpFrac)).height(14.dp).background(Color(0x99E53935)))
            Box(Modifier.fillMaxWidth(((r.min.toFloat()) / def.maxHp).coerceAtMost(hpFrac)).height(14.dp).background(Color(0xFFC62828)))
        }
        Text(r.ko.text(), style = MaterialTheme.typography.titleSmall, color = if (r.ko.hits == 1) Color(0xFFC62828) else MaterialTheme.colorScheme.onSurface)
        Text("對手 HP ${def.curHp}/${def.maxHp}・威力 ${r.basePower}・${TypeZh[r.moveType]}屬性 ×${hk.tikhang.champions.core.TeamAnalysisEngine.fmt(r.effectiveness)}" +
            if (r.critical) "・擊中要害" else "", style = MaterialTheme.typography.bodySmall)
        Text("16 個隨機數：" + r.rolls.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        r.notes.forEach { Text("・$it", style = MaterialTheme.typography.bodySmall, color = Color(0xFFB26A00)) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            VerificationBadge(r.verification)
            if (r.verification != Verification.VERIFIED) Badge("Estimated Calculation", Color(0xFFB26A00))
        }
        Text("KO 機率未計殘餘傷害、回復道具及特性（例如 Leftovers、Sitrus Berry）。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (r.max > 0 && move.category != "Status") {
            TextButton(onClick = { showSurvival = !showSurvival }) { Text(if (showSurvival) "隱藏生存分析" else "生存分析（Survival Analysis）") }
            if (showSurvival) SurvivalSection(state, atk, attacker, defender, move, field)
        }
    }
}

@Composable
private fun SurvivalSection(state: DataState, atk: DamageEngine.Side, attacker: CalcSide, defender: CalcSide, move: hk.tikhang.champions.core.Move, field: Field) {
    val s = state.snapshot
    val sv = remember(atk, defender, move, field) { DamageEngine.survival(s, atk, defender.slot, move, field) }
    // Least attack SP (keeping nature) for a guaranteed one-hit KO on the current defender.
    val needed = remember(attacker, defender, move, field) {
        val atkStat = if (move.category == "Physical") Stat.ATK else Stat.SPA
        val def = DamageEngine.sideFromSlot(s, defender.slot, defender.status, defender.boosts, defender.hpPercent, defender.abilityActive)
        def?.let { d ->
            (0..StatsEngine.MAX_SP_PER_STAT).firstOrNull { sp ->
                val a = DamageEngine.sideFromSlot(s, attacker.slot.copy(sp = attacker.slot.sp + (atkStat to sp)), attacker.status, attacker.boosts, attacker.hpPercent, attacker.abilityActive)
                a != null && DamageEngine.calculate(s, a, d, move, field).min >= d.curHp
            }?.let { atkStat to it }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        sv?.let {
            if (it.possible) Text("防守方要承受最高傷害（滿 HP）：HP ${it.hpSp} SP ＋ ${it.defStat.zh} ${it.defSp} SP（現有性格）→ 最高 ${it.maxDamage}／${it.hp}",
                style = MaterialTheme.typography.bodySmall)
            else Text("防守方即使 HP 及${it.defStat.zh}都投資到最高，仍會被最高傷害一擊擊倒（${it.maxDamage}／${it.hp}）。", style = MaterialTheme.typography.bodySmall, color = Color(0xFFC62828))
        }
        Text(
            needed?.let { (st, sp) -> "攻擊方要確定一擊擊倒：${st.zh} ${sp} SP（現有性格及其他設定）" } ?: "攻擊方即使${if (move.category == "Physical") "攻擊" else "特攻"}投資到最高，也不能確定一擊擊倒。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
