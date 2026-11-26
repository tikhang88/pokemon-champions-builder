package hk.tikhang.champions.core

import kotlinx.serialization.Serializable

enum class BattleFormat(val zh: String, val bring: Int, val minTeam: Int) {
    DOUBLES("雙打", 4, 4), SINGLES("單打", 3, 3)
}

const val TEAM_SIZE = 6
const val MAX_MOVES = 4

/** A saved team. Stored as JSON, so new optional fields stay backward compatible. */
@Serializable
data class Team(
    val name: String,
    val regulationId: String,
    val format: BattleFormat = BattleFormat.DOUBLES,
    /** Always [TEAM_SIZE] entries; null = empty slot. */
    val slots: List<TeamSlot?> = List(TEAM_SIZE) { null },
    val notes: String = "",
    /** In-game Replica Team ID (10 characters). Kept for reference; it cannot be decoded offline. */
    val teamCode: String = "",
    /** True for opponent teams used in Matchup Research. */
    val opponent: Boolean = false,
) {
    val members: List<TeamSlot> get() = slots.filterNotNull()
    fun withSlot(index: Int, slot: TeamSlot?): Team = copy(slots = slots.toMutableList().also { it[index] = slot })
}

/**
 * One team member. [speciesId] is the out-of-battle form (never a Mega); [megaFormId] is the
 * Mega form chosen with the Mega Toggle, which also requires holding that Mega's stone.
 */
@Serializable
data class TeamSlot(
    val speciesId: String,
    val megaFormId: String? = null,
    val ability: String? = null,
    val item: String? = null,
    val nature: String = "serious",
    val moves: List<String> = emptyList(),
    val sp: Map<Stat, Int> = emptyMap(),
) {
    fun spOf(stat: Stat): Int = sp[stat] ?: 0
    val spTotal: Int get() = sp.values.sum()
}

object TeamEngine {
    fun species(s: Snapshot, slot: TeamSlot): Pokemon? = s.pokemonById[slot.speciesId]

    /** The form every engine should use (spec §6.5 Mega synchronization). */
    fun effectiveForm(s: Snapshot, slot: TeamSlot): Pokemon? =
        slot.megaFormId?.let { s.pokemonById[it] } ?: species(s, slot)

    /** Ability in effect: Megas have a fixed ability; otherwise the chosen one. */
    fun effectiveAbility(s: Snapshot, slot: TeamSlot): String? {
        val form = effectiveForm(s, slot) ?: return slot.ability
        return if (form.isMega) form.abilities.firstOrNull() else slot.ability ?: form.abilities.firstOrNull()
    }

    fun natureEffect(s: Snapshot, natureId: String, stat: Stat): StatsEngine.NatureEffect {
        val n = s.natures.firstOrNull { it.id == natureId } ?: return StatsEngine.NatureEffect.NEUTRAL
        return when {
            n.plus == n.minus -> StatsEngine.NatureEffect.NEUTRAL
            n.plus == stat.key -> StatsEngine.NatureEffect.PLUS
            n.minus == stat.key -> StatsEngine.NatureEffect.MINUS
            else -> StatsEngine.NatureEffect.NEUTRAL
        }
    }

    /** Final Lv50 stats of the effective form with the slot's SP and nature. */
    fun finalStats(s: Snapshot, slot: TeamSlot): Map<Stat, Int> {
        val form = effectiveForm(s, slot) ?: return emptyMap()
        return Stat.entries.associateWith { st ->
            StatsEngine.calc(st, form.baseStats[st], slot.spOf(st).coerceIn(0, StatsEngine.MAX_SP_PER_STAT), natureEffect(s, slot.nature, st))
        }
    }

    /** Selecting a Mega form puts its stone in the item slot; turning Mega off clears the stone. */
    fun toggleMega(s: Snapshot, slot: TeamSlot, megaId: String?): TeamSlot {
        if (megaId == null) {
            val stoneItem = slot.item?.let { s.itemById[it] }
            return slot.copy(megaFormId = null, item = if (stoneItem?.isMegaStone == true) null else slot.item)
        }
        val mega = s.pokemonById[megaId] ?: return slot
        return slot.copy(megaFormId = megaId, item = mega.megaStone?.let(::toId) ?: slot.item)
    }

    /** A new slot with sensible defaults for [speciesId]. */
    fun newSlot(s: Snapshot, speciesId: String): TeamSlot {
        val p = s.pokemonById.getValue(speciesId)
        return TeamSlot(speciesId = p.id, ability = p.abilities.firstOrNull())
    }

    /** Plain-text export (one block per Pokémon), for sharing or keeping a copy. */
    fun exportText(s: Snapshot, team: Team): String = buildString {
        appendLine("=== ${team.name} ===")
        appendLine("Regulation ${team.regulationId}・${team.format.zh}")
        if (team.teamCode.isNotBlank()) appendLine("Team ID: ${team.teamCode}")
        appendLine()
        for (slot in team.members) {
            val sp = species(s, slot) ?: continue
            val item = slot.item?.let { s.itemById[it]?.name ?: it }
            appendLine(sp.name + (item?.let { " @ $it" } ?: ""))
            // Showdown convention: the out-of-battle ability, even when the Pokémon Mega Evolves.
            (slot.ability ?: effectiveAbility(s, slot))?.let { appendLine("Ability: $it") }
            slot.megaFormId?.let { s.pokemonById[it]?.name }?.let { appendLine("Mega: $it") }
            appendLine("Level: 50")
            val spText = Stat.entries.filter { slot.spOf(it) > 0 }.joinToString(" / ") { "${slot.spOf(it)} ${SHORT[it]}" }
            // Showdown's Champions formats read the EVs line as Stat Points.
            if (spText.isNotEmpty()) appendLine("EVs: $spText")
            s.natures.firstOrNull { it.id == slot.nature }?.let { appendLine("${it.name} Nature") }
            slot.moves.forEach { m -> appendLine("- " + (s.moveById[m]?.name ?: m)) }
            appendLine()
        }
    }.trimEnd()

    private val SHORT = mapOf(Stat.HP to "HP", Stat.ATK to "Atk", Stat.DEF to "Def", Stat.SPA to "SpA", Stat.SPD to "SpD", Stat.SPE to "Spe")
}

// ---- Team Legality (spec §7.5) -------------------------------------------------------------

enum class Legality(val zh: String) { LEGAL("合法"), ILLEGAL("不合法"), PENDING("待驗證"), INCOMPLETE("未完成") }

data class LegalityIssue(val level: Level, val message: String, val slotIndex: Int? = null) {
    enum class Level { ERROR, PENDING, INCOMPLETE, WARNING }
}

data class LegalityReport(val result: Legality, val issues: List<LegalityIssue>) {
    fun forSlot(i: Int) = issues.filter { it.slotIndex == i }
    val teamIssues: List<LegalityIssue> get() = issues.filter { it.slotIndex == null }
}

object LegalityEngine {
    fun check(s: Snapshot, team: Team): LegalityReport {
        val out = mutableListOf<LegalityIssue>()
        fun err(m: String, i: Int? = null) { out += LegalityIssue(LegalityIssue.Level.ERROR, m, i) }
        fun pend(m: String, i: Int? = null) { out += LegalityIssue(LegalityIssue.Level.PENDING, m, i) }
        fun warn(m: String, i: Int? = null) { out += LegalityIssue(LegalityIssue.Level.WARNING, m, i) }
        fun incomplete(m: String, i: Int? = null) { out += LegalityIssue(LegalityIssue.Level.INCOMPLETE, m, i) }

        val reg = s.regulationById[team.regulationId]
        when {
            reg == null -> err("資料內沒有 Regulation ${team.regulationId}")
            !reg.hasPokemonList -> pend("${reg.id} 未有 Pokémon 名單，未能檢查")
            reg.provisional -> pend("${reg.id} 名單只包括已公布的 Pokémon（待驗證）")
        }
        val allowed = reg?.allowedPokemon?.toSet()

        val members = team.slots.withIndex().filter { it.value != null }.map { it.index to it.value!! }
        if (members.size < team.format.minTeam) incomplete("${team.format.zh}隊伍最少需要 ${team.format.minTeam} 隻（現有 ${members.size} 隻）")
        if (members.size > TEAM_SIZE) err("隊伍最多 $TEAM_SIZE 隻")

        // Species Clause: same National Dex number counts as the same species.
        members.groupBy { (_, sl) -> s.pokemonById[sl.speciesId]?.num ?: -1 }.filter { it.key > 0 && it.value.size > 1 }.forEach { (_, dup) ->
            val name = s.pokemonById[dup.first().second.speciesId]?.let { s.displayName(it) } ?: ""
            dup.forEach { (i, _) -> err("Species Clause：$name 重複", i) }
        }
        // Item Clause
        members.filter { it.second.item != null }.groupBy { it.second.item }.filter { it.value.size > 1 }.forEach { (item, dup) ->
            val name = s.itemById[item]?.let { it.nameZh ?: it.name } ?: item
            dup.forEach { (i, _) -> err("Item Clause：道具「$name」重複", i) }
        }
        val megaCount = members.count { it.second.megaFormId != null }
        if (megaCount > 1) warn("隊伍有 $megaCount 隻 Mega：每場對戰只可 Mega Evolution 一次，對戰時只能讓其中一隻 Mega")

        for ((i, slot) in members) checkSlot(s, slot, i, allowed, ::err, ::pend, ::warn, ::incomplete)

        val result = when {
            out.any { it.level == LegalityIssue.Level.ERROR } -> Legality.ILLEGAL
            out.any { it.level == LegalityIssue.Level.INCOMPLETE } -> Legality.INCOMPLETE
            out.any { it.level == LegalityIssue.Level.PENDING } -> Legality.PENDING
            else -> Legality.LEGAL
        }
        return LegalityReport(result, out)
    }

    private fun checkSlot(
        s: Snapshot, slot: TeamSlot, i: Int, allowed: Set<String>?,
        err: (String, Int?) -> Unit, pend: (String, Int?) -> Unit, warn: (String, Int?) -> Unit, incomplete: (String, Int?) -> Unit,
    ) {
        val sp = s.pokemonById[slot.speciesId]
        if (sp == null) { err("資料內沒有 Pokémon「${slot.speciesId}」", i); return }
        val name = s.displayName(sp)
        if (sp.isMega) err("$name：隊伍內應選原本形態，Mega 用 Mega Toggle 切換", i)
        if (!sp.teamSelectable) err("$name：戰鬥中形態，不能直接加入隊伍", i)
        if (allowed != null && sp.id !in allowed) err("$name 不能在此 Regulation 使用", i)
        if (sp.verification == Verification.PENDING) pend("$name 的資料待驗證", i)

        // Mega
        slot.megaFormId?.let { mid ->
            val mega = s.pokemonById[mid]
            when {
                mega == null || mega !in s.megaOptions(sp) -> err("$name 沒有這個 Mega 形態", i)
                allowed != null && mega.id !in allowed -> err("${s.displayName(mega)} 不能在此 Regulation 使用", i)
                mega.megaStone?.let(::toId) != slot.item -> err("${s.displayName(mega)} 需要持有 ${mega.megaStone}", i)
                mega.verification == Verification.PENDING -> pend("${s.displayName(mega)} 的資料待驗證", i)
            }
        }
        // Item
        slot.item?.let { itemId ->
            val item = s.itemById[itemId]
            when {
                item == null -> err("道具「$itemId」不能在 Champions 使用", i)
                item.isMegaStone && slot.megaFormId == null -> {
                    val forThis = s.megaOptions(sp).any { m -> m.megaStone?.let(::toId) == itemId }
                    if (forThis) warn("$name 持有 ${item.name}，但未開啟 Mega", i)
                    else warn("${item.name} 不是 $name 的 Mega Stone，不會發揮作用", i)
                }
                item.verification == Verification.PENDING -> pend("道具「${item.name}」資料待驗證", i)
            }
        }
        // Ability (Megas have a fixed ability; the chosen one is the out-of-battle form's)
        val ability = slot.ability
        if (ability == null) incomplete("$name 未選特性", i)
        else if (sp.abilities.none { toId(it) == toId(ability) }) err("$name 不能擁有特性「$ability」", i)

        // Nature
        if (s.natures.none { it.id == slot.nature }) err("未知的性格「${slot.nature}」", i)

        // SP (Stat Points)
        StatsEngine.validateSpread(slot.sp).forEach { err("$name：$it", i) }

        // Moves
        if (slot.moves.isEmpty()) incomplete("$name 未選招式", i)
        if (slot.moves.size > MAX_MOVES) err("$name 最多 $MAX_MOVES 個招式", i)
        slot.moves.groupingBy { it }.eachCount().filter { it.value > 1 }.keys.forEach { m ->
            err("$name：招式「${s.moveById[m]?.let { it.nameZh ?: it.name } ?: m}」重複", i)
        }
        val learnset = (s.learnsets[sp.id] ?: s.learnsets[sp.baseId]).orEmpty().toSet()
        slot.moves.filter { it !in learnset }.forEach { m ->
            err("$name 不能學會「${s.moveById[m]?.let { it.nameZh ?: it.name } ?: m}」", i)
        }
        if (sp.hasPlaceholderLearnset && slot.moves.isNotEmpty()) pend("$name 的招式表暫用 Scarlet/Violet 資料（待 Champions 確認）", i)
    }
}
