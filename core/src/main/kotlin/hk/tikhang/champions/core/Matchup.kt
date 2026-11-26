package hk.tikhang.champions.core

import hk.tikhang.champions.core.TeamAnalysisEngine.Member
import kotlin.math.ln
import kotlin.math.max

/**
 * Team Matchup Research (spec §11). Every rating is a sum of named factors so the user can see
 * why it was given. Damage ranges arrive with the Damage Calculator (Phase 5); until then the
 * offensive/defensive factors use type multipliers (with abilities) and the speed factor uses
 * real Lv50 stats.
 */
object MatchupEngine {

    enum class Threat(val zh: String) { HIGH("高"), MEDIUM("中"), LOW("低") }

    /** One of my members against one opponent. */
    data class Pairing(
        val mine: Member,
        /** Best multiplier of my moves against the opponent; null = no damaging moves. */
        val myBest: Double?,
        val myBestMove: Move?,
        /** Best multiplier of the opponent's attacks against my member. */
        val theirBest: Double,
        val theirBestAttack: String,
        val mySpeed: Int,
        val theirSpeed: Int,
        val offense: Double,
        val defense: Double,
        val speed: Double,
    ) {
        val score: Double get() = offense + defense + speed
        val faster: Boolean? get() = when { mySpeed > theirSpeed -> true; mySpeed < theirSpeed -> false; else -> null }
    }

    data class OpponentResult(
        val opponent: Member,
        /** True when the opponent's moves are known; otherwise its own types (STAB) are assumed. */
        val movesKnown: Boolean,
        val abilityNote: String?,
        val pairings: List<Pairing>,
        val threat: Threat,
        val superEffectiveOnMe: Int,
        val bestResponse: Pairing?,
        val bestDefensive: Pairing?,
        val bestOffensive: Pairing?,
        val flags: List<String>,
        val explanation: String,
    )

    data class Report(
        val results: List<OpponentResult>,
        val threats: List<OpponentResult>,
        val unhittable: List<Member>,
        val speedLines: List<String>,
        val opponentSharedWeakness: List<String>,
        val strategy: List<String>,
        val leadSuggestion: List<Member>,
    )

    private fun log2(x: Double) = ln(x) / ln(2.0)
    private fun offScore(m: Double?) = when { m == null -> -1.0; m == 0.0 -> -3.0; else -> log2(m).coerceIn(-3.0, 2.0) }
    private fun defScore(m: Double) = if (m == 0.0) 3.0 else (-log2(m)).coerceIn(-2.0, 3.0)

    /** Attacks the opponent can use: its damaging moves, or its own types when moves are unknown. */
    private fun attacks(o: Member): List<Pair<String, Move?>> =
        if (o.damaging.isNotEmpty()) o.damaging.map { TeamAnalysisEngine.moveType(it, o.ability) to it }
        else o.form.types.map { it to null }

    fun pair(te: TypeEngine, mine: Member, o: Member, oAbilities: List<String?>, trickRoom: Boolean): Pairing {
        // My best move, assuming the opponent has its least favourable ability for me.
        var myBest: Double? = null
        var myBestMove: Move? = null
        for (mv in mine.damaging) {
            val type = TeamAnalysisEngine.moveType(mv, mine.ability)
            val raw = TeamAnalysisEngine.moveMultiplier(te, mv, mine.ability, o.form.types)
            val worst = oAbilities.minOf { a -> TeamAnalysisEngine.applyDefensiveAbility(raw, type, a) }
            if (myBest == null || worst > myBest!!) { myBest = worst; myBestMove = mv }
        }
        var theirBest = 0.0
        var theirLabel = "—"
        for ((type, mv) in attacks(o)) {
            val raw = if (mv != null) TeamAnalysisEngine.moveMultiplier(te, mv, o.ability, mine.form.types) else te.multiplier(type, mine.form.types)
            val m = TeamAnalysisEngine.applyDefensiveAbility(raw, type, mine.ability)
            if (m > theirBest || theirLabel == "—") {
                theirBest = m
                theirLabel = mv?.let { it.nameZh ?: it.name } ?: "${TeamAnalysisEngine.zhType(type)}屬性（本系）"
            }
        }
        val mySpe = mine.stats[Stat.SPE] ?: 0
        val theirSpe = o.stats[Stat.SPE] ?: 0
        val sp = when {
            mySpe == theirSpe -> 0.0
            (mySpe > theirSpe) != trickRoom -> 0.5
            else -> -0.5
        }
        return Pairing(mine, myBest, myBestMove, theirBest, theirLabel, mySpe, theirSpe, offScore(myBest), defScore(theirBest), sp)
    }

    fun analyseOpponent(te: TypeEngine, mine: List<Member>, o: Member, oAbilities: List<String?>, trickRoom: Boolean = false): OpponentResult {
        val pairings = mine.map { pair(te, it, o, oAbilities, trickRoom) }
        val se = pairings.count { it.theirBest > 1.0 }
        val best = pairings.maxByOrNull { it.score }
        val bestDef = pairings.maxWithOrNull(compareBy<Pairing> { it.defense }.thenBy { it.score })
        val bestOff = pairings.filter { it.myBest != null }.maxWithOrNull(compareBy<Pairing> { it.offense }.thenBy { it.speed })
        val bestScore = best?.score ?: -99.0
        val n = mine.size
        val threat = when {
            bestScore < 1.0 -> Threat.HIGH
            se >= max(3, (n + 1) / 2) && bestScore < 2.0 -> Threat.HIGH
            bestScore >= 2.5 || (bestScore >= 2.0 && se <= 1) -> Threat.LOW
            else -> Threat.MEDIUM
        }
        val flags = buildList {
            o.moves.filter { it.id in TeamAnalysisEngine.SETUP }.forEach { add("強化招式：${it.nameZh ?: it.name}") }
            o.moves.filter { it.priority > 0 && it.category != "Status" }.forEach { add("先制招式：${it.nameZh ?: it.name}（+${it.priority}）") }
            o.moves.filter { it.id in TeamAnalysisEngine.SPEED_CONTROL }.forEach { add("速度控制：${it.nameZh ?: it.name}") }
            o.ability?.let { a -> TeamAnalysisEngine.abilityLabel(toId(a))?.let { add("特性 $a：$it") } }
        }
        val explanation = buildString {
            append("對手可效果絕佳打擊我方 $se/$n 隻")
            if (best != null) {
                append("；最佳應對 ${best.mine.name}（分數 ${"%.1f".format(best.score)}：")
                append("我方 ${best.myBestMove?.let { it.nameZh ?: it.name } ?: "沒有攻擊招式"} ×${best.myBest?.let { TeamAnalysisEngine.fmt(it) } ?: "—"}、")
                append("對手 ${best.theirBestAttack} ×${TeamAnalysisEngine.fmt(best.theirBest)}、")
                append("速度 ${best.mySpeed} vs ${best.theirSpeed}）")
            }
        }
        val abilityNote = if (oAbilities.size > 1) "對手特性未知：以最不利我方的特性計算" else null
        return OpponentResult(o, o.damaging.isNotEmpty(), abilityNote, pairings, threat, se, best, bestDef, bestOff, flags, explanation)
    }

    /** Opponent members built from a saved opponent team (configured sets). */
    fun opponentsFromTeam(s: Snapshot, team: Team): List<Pair<Member, List<String?>>> =
        TeamAnalysisEngine.members(s, team).map { it to listOf(it.ability) }

    /**
     * A quick-pick opponent with no saved set: assumed fast attacker (32 Speed SP with a boosting
     * nature, 32 in its better attacking stat), moves unknown (its types are used), ability unknown.
     */
    fun quickOpponent(s: Snapshot, pokemonId: String, index: Int): Pair<Member, List<String?>>? {
        val p = s.pokemonById[pokemonId] ?: return null
        val base = if (p.isMega) s.pokemonById[p.megaOf] ?: p else p
        val physical = p.baseStats.atk >= p.baseStats.spa
        val slot = TeamSlot(
            speciesId = base.id, megaFormId = if (p.isMega) p.id else null, ability = base.abilities.firstOrNull(),
            item = p.megaStone?.let(::toId), nature = if (physical) "jolly" else "timid",
            sp = mapOf(Stat.SPE to 32, (if (physical) Stat.ATK else Stat.SPA) to 32, Stat.HP to 2),
        )
        val form = TeamEngine.effectiveForm(s, slot) ?: return null
        val m = Member(index, slot, form, s.displayName(form), TeamEngine.effectiveAbility(s, slot), emptyList(), TeamEngine.finalStats(s, slot))
        val abilities: List<String?> = if (form.isMega) form.abilities else form.abilities.ifEmpty { listOf(null) }
        return m to abilities
    }

    fun report(s: Snapshot, te: TypeEngine, mine: List<Member>, opponents: List<Pair<Member, List<String?>>>, trickRoom: Boolean = false): Report {
        val results = opponents.map { (o, ab) -> analyseOpponent(te, mine, o, ab, trickRoom) }
        val threats = results.filter { it.threat != Threat.LOW }.sortedBy { it.threat.ordinal }
        val unhittable = opponents.map { it.first }.filter { o ->
            results.first { it.opponent === o }.pairings.none { (it.myBest ?: 0.0) > 1.0 }
        }
        val speedLines = mutableListOf<String>()
        results.forEach { r ->
            val faster = r.pairings.count { it.faster == true }
            speedLines += "${r.opponent.name}（速度 ${r.opponent.stats[Stat.SPE]}）：我方 $faster/${mine.size} 隻${if (trickRoom) "在戲法空間下" else ""}較快"
        }
        val oppMembers = opponents.map { it.first }
        val oppDef = TeamAnalysisEngine.defense(s, te, oppMembers)
        val myOff = TeamAnalysisEngine.offense(s, te, mine)
        val shared = oppDef.filter { it.isShared }.map { row ->
            val users = myOff.first { it.type == row.type }.superEffective.distinctBy { it.member.index }
            "${TeamAnalysisEngine.zhType(row.type)}：對手 ${row.weak} 隻弱" +
                if (users.isEmpty()) "，但我方沒有效果絕佳的招式" else "；我方可用 " + users.joinToString("、") { "${it.member.name}・${it.move.nameZh ?: it.move.name}" }
        }
        val strategy = mutableListOf<String>()
        val oppFasterThanMost = results.count { r -> r.pairings.count { it.faster == false } > mine.size / 2 }
        val synergy = TeamAnalysisEngine.synergy(mine)
        val control = synergy.first { it.kind == TeamAnalysisEngine.SynergyKind.SPEED_CONTROL }.providers
        if (oppFasterThanMost >= (opponents.size + 1) / 2) {
            strategy += if (control.isNotEmpty()) "對手大部分比我方快：考慮先用 " + control.joinToString("、") { (m, w) -> "${m.name} 的 $w" }
            else "對手大部分比我方快，而我方沒有速度控制：考慮加入 Tailwind／Trick Room 或先制招式"
        }
        threats.filter { it.threat == Threat.HIGH }.forEach { t ->
            strategy += "${t.opponent.name} 是高威脅：" + (t.bestDefensive?.let { "可用 ${it.mine.name} 接招（對手最佳攻擊 ×${TeamAnalysisEngine.fmt(it.theirBest)}）" } ?: "我方沒有可靠的接招成員")
        }
        if (unhittable.isNotEmpty()) strategy += "我方沒有招式可效果絕佳打擊：" + unhittable.joinToString("、") { it.name }
        // Lead pair: the two members with the best total score across the opponents (doubles).
        val lead = mine.sortedByDescending { m -> results.sumOf { r -> r.pairings.first { it.mine.index == m.index }.score } }.take(2)
        return Report(results, threats, unhittable, speedLines, shared, strategy, lead)
    }
}
