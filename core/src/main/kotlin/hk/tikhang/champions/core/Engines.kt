package hk.tikhang.champions.core

import java.time.Instant

/**
 * Pokémon Champions stat formula (all battles at Level 50, IVs fixed at maximum).
 * HP = base + SP + 75; other = floor((base + SP + 20) × nature).
 * Source: Showdown champions/scripts.ts and Smogon damage-calc (both agree). Verification: VERIFIED.
 */
object StatsEngine {
    const val MAX_SP_TOTAL = 66
    const val MAX_SP_PER_STAT = 32

    /** Nature effect as an integer percentage, matching the game's integer arithmetic. */
    enum class NatureEffect(val percent: Int, val zh: String) {
        PLUS(110, "↑"), NEUTRAL(100, ""), MINUS(90, "↓")
    }

    fun calc(stat: Stat, base: Int, sp: Int = 0, nature: NatureEffect = NatureEffect.NEUTRAL): Int {
        require(sp in 0..MAX_SP_PER_STAT) { "SP must be 0..$MAX_SP_PER_STAT" }
        if (stat == Stat.HP) return if (base == 1) 1 else base + sp + 75 // base 1 = Shedinja
        return (base + sp + 20) * nature.percent / 100
    }

    /** Stat range at Lv50: minimum (0 SP, hindering nature) … maximum (32 SP, boosting nature). */
    fun range(stat: Stat, base: Int): IntRange =
        if (stat == Stat.HP) calc(stat, base, 0)..calc(stat, base, MAX_SP_PER_STAT)
        else calc(stat, base, 0, NatureEffect.MINUS)..calc(stat, base, MAX_SP_PER_STAT, NatureEffect.PLUS)

    /** Returns problems with an SP spread, empty when valid. */
    fun validateSpread(sp: Map<Stat, Int>): List<String> {
        val out = mutableListOf<String>()
        sp.forEach { (s, v) -> if (v !in 0..MAX_SP_PER_STAT) out += "${s.zh} 的 SP 必須在 0–$MAX_SP_PER_STAT 之間（現為 $v）" }
        val total = sp.values.sum()
        if (total > MAX_SP_TOTAL) out += "SP 總數不可超過 $MAX_SP_TOTAL（現為 $total）"
        return out
    }
}

/** Type effectiveness from the snapshot's 18×18 chart. */
class TypeEngine(private val chart: Map<String, Map<String, Double>>, val types: List<String>) {
    fun multiplier(attack: String, defenders: List<String>): Double =
        defenders.fold(1.0) { acc, d -> acc * (chart[attack]?.get(d) ?: 1.0) }

    /** Defensive profile: attacking type → damage multiplier against [defenders]. */
    fun defensiveProfile(defenders: List<String>): Map<String, Double> = types.associateWith { multiplier(it, defenders) }

    /**
     * Abilities that grant a type immunity (only the common, clear-cut ones).
     * Shown as a note; the base type chart is not changed by them.
     */
    fun abilityImmunity(ability: String): String? = ABILITY_IMMUNITY[toId(ability)]

    companion object {
        private val ABILITY_IMMUNITY = mapOf(
            "levitate" to "Ground", "flashfire" to "Fire", "waterabsorb" to "Water", "stormdrain" to "Water",
            "dryskin" to "Water", "voltabsorb" to "Electric", "lightningrod" to "Electric", "motordrive" to "Electric",
            "sapsipper" to "Grass", "eartheater" to "Ground", "wellbakedbody" to "Fire",
        )
    }
}

/** Regulation status relative to a moment in time. */
enum class RegulationStatus(val zh: String) { CURRENT("目前"), UPCOMING("即將生效"), PAST("歷史"), UNKNOWN("日期未定") }

object RegulationEngine {
    fun status(r: Regulation, now: Instant): RegulationStatus {
        val start = r.effectiveDate?.let(::parseInstant) ?: return RegulationStatus.UNKNOWN
        val end = r.endDate?.let(::parseInstant)
        return when {
            now.isBefore(start) -> RegulationStatus.UPCOMING
            end != null && now.isAfter(end) -> RegulationStatus.PAST
            else -> RegulationStatus.CURRENT
        }
    }

    /** The Regulation in effect at [now] according to its dates (may have no Pokémon list yet). */
    fun inEffect(s: Snapshot, now: Instant): Regulation? = s.regulations.firstOrNull { status(it, now) == RegulationStatus.CURRENT }

    /**
     * The Regulation the app should use by default: the one in effect if it has a Pokémon list,
     * otherwise the newest Regulation that has one.
     */
    fun defaultRegulation(s: Snapshot, now: Instant): Regulation {
        inEffect(s, now)?.takeIf { it.hasPokemonList }?.let { return it }
        return s.regulations.filter { it.hasPokemonList }.maxByOrNull { it.effectiveDate ?: "" }
            ?: s.regulationById.getValue(s.meta.currentRegulation)
    }

    /** True when a Regulation has started but this snapshot has no Pokémon list for it. */
    fun needsDataUpdate(s: Snapshot, now: Instant): Regulation? = inEffect(s, now)?.takeIf { !it.hasPokemonList }

    fun parseInstant(s: String): Instant? = runCatching { Instant.parse(s) }.getOrNull()
}
