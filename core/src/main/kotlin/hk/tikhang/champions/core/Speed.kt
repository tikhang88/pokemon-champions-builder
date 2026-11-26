package hk.tikhang.champions.core

/**
 * Speed Tier Engine (spec §10). Lv50 Champions stats; modifiers follow the main-series
 * order: stat stage, then Speed modifiers, then paralysis. Combined 4096-scale rounding is
 * approximated with floor at each step, so results with several modifiers are marked Estimated.
 */
object SpeedEngine {

    enum class Weather(val zh: String) { NONE("無"), SUN("晴天"), RAIN("下雨"), SAND("沙暴"), SNOW("下雪") }

    data class Conditions(
        val stage: Int = 0,
        val tailwind: Boolean = false,
        val paralysis: Boolean = false,
        val choiceScarf: Boolean = false,
        val ironBall: Boolean = false,
        val weather: Weather = Weather.NONE,
        /** Unburden after the item is used, Quick Feet with a status, etc. are handled via [abilityActive]. */
        val abilityActive: Boolean = false,
        val trickRoom: Boolean = false,
    ) {
        val isDefault: Boolean get() = this == Conditions()
        fun describe(): String = buildList {
            if (stage != 0) add("速度 ${if (stage > 0) "+" else ""}$stage")
            if (tailwind) add("順風")
            if (paralysis) add("麻痺")
            if (choiceScarf) add("講究圍巾")
            if (ironBall) add("黑色鐵球")
            if (weather != Weather.NONE) add(weather.zh)
            if (abilityActive) add("特性發動")
            if (trickRoom) add("戲法空間")
        }.joinToString("・").ifEmpty { "無" }
    }

    private val WEATHER_ABILITY = mapOf(
        "swiftswim" to Weather.RAIN, "chlorophyll" to Weather.SUN, "sandrush" to Weather.SAND, "slushrush" to Weather.SNOW,
    )

    /** Abilities whose Speed boost needs a trigger the user toggles with abilityActive. */
    private val TRIGGERED_ABILITY = mapOf("unburden" to 2.0, "quickfeet" to 1.5, "surgesurfer" to 2.0)

    fun stageMultiplier(stage: Int): Double {
        val s = stage.coerceIn(-6, 6)
        return if (s >= 0) (2 + s) / 2.0 else 2.0 / (2 - s)
    }

    /** Effective Speed for ordering. Trick Room is not applied here (it reverses the order instead). */
    fun effective(baseStat: Int, ability: String?, c: Conditions): Int {
        var v = Math.floor(baseStat * stageMultiplier(c.stage)).toInt()
        var mod = 1.0
        if (c.tailwind) mod *= 2.0
        if (c.choiceScarf) mod *= 1.5
        if (c.ironBall) mod *= 0.5
        val a = ability?.let(::toId)
        if (a != null && WEATHER_ABILITY[a] == c.weather && c.weather != Weather.NONE) mod *= 2.0
        if (a != null && c.abilityActive) TRIGGERED_ABILITY[a]?.let { mod *= it }
        v = Math.floor(v * mod).toInt()
        // Quick Feet ignores the paralysis Speed drop.
        if (c.paralysis && a != "quickfeet") v = Math.floor(v * 0.5).toInt()
        return v
    }

    fun isEstimated(c: Conditions): Boolean =
        listOf(c.tailwind, c.choiceScarf, c.ironBall, c.weather != Weather.NONE, c.abilityActive).count { it } >= 2

    // ---- Benchmarks for every Pokémon in a Regulation (spec §10.6, §10.7) -----------------

    enum class Spread(val zh: String) {
        MAX_PLUS("最速（32 SP＋性格）"), MAX("準速（32 SP）"), NEUTRAL("無投資（0 SP）"), MIN("最慢（0 SP－性格）")
    }

    fun benchmark(base: Int, spread: Spread): Int = when (spread) {
        Spread.MAX_PLUS -> StatsEngine.calc(Stat.SPE, base, 32, StatsEngine.NatureEffect.PLUS)
        Spread.MAX -> StatsEngine.calc(Stat.SPE, base, 32)
        Spread.NEUTRAL -> StatsEngine.calc(Stat.SPE, base, 0)
        Spread.MIN -> StatsEngine.calc(Stat.SPE, base, 0, StatsEngine.NatureEffect.MINUS)
    }

    data class TierEntry(val pokemon: Pokemon, val speed: Int, val teamIndex: Int? = null, val label: String? = null)

    /**
     * Regulation Pokémon (Megas included) at [spread] — optionally holding a Choice Scarf — plus
     * the team's members at their configured speed with [c] applied. Trick Room reverses the order.
     */
    fun tierList(pool: List<Pokemon>, spread: Spread, members: List<TeamAnalysisEngine.Member>, c: Conditions, opponentScarf: Boolean = false): List<TierEntry> {
        val others = pool.map { TierEntry(it, effective(benchmark(it.baseStats.spe, spread), null, Conditions(choiceScarf = opponentScarf))) }
        val mine = members.map { m -> TierEntry(m.form, effective(m.stats[Stat.SPE] ?: 0, m.ability, c), m.index, m.name) }
        val all = others + mine
        return if (c.trickRoom) all.sortedBy { it.speed } else all.sortedByDescending { it.speed }
    }

    /** Share of [pool] at [spread] that [speed] strictly outspeeds (or under Trick Room, is slower than). */
    fun percentile(pool: List<Pokemon>, spread: Spread, speed: Int, trickRoom: Boolean = false): Double {
        if (pool.isEmpty()) return 0.0
        val beats = pool.count { p -> val o = benchmark(p.baseStats.spe, spread); if (trickRoom) speed < o else speed > o }
        return beats * 100.0 / pool.size
    }

    // ---- Summary (spec §10.3, §10.8) ------------------------------------------------------

    data class Summary(
        val fastest: Pair<TeamAnalysisEngine.Member, Int>?,
        val slowest: Pair<TeamAnalysisEngine.Member, Int>?,
        val fast: List<TeamAnalysisEngine.Member>,
        val slow: List<TeamAnalysisEngine.Member>,
        val speedControl: List<String>,
        val advantages: List<String>,
        val disadvantages: List<String>,
        val gaps: List<String>,
    )

    fun summary(s: Snapshot, pool: List<Pokemon>, members: List<TeamAnalysisEngine.Member>, synergy: List<TeamAnalysisEngine.SynergyEntry>): Summary {
        val speeds = members.map { it to (it.stats[Stat.SPE] ?: 0) }
        // "Fast" = outspeeds at least 75% of the pool at max investment; "slow" = outspeeds under 25%.
        val pct = speeds.associate { (m, v) -> m to percentile(pool, Spread.MAX_PLUS, v) }
        val fast = pct.filterValues { it >= 75.0 }.keys.toList()
        val slow = pct.filterValues { it < 25.0 }.keys.toList()
        val control = synergy.firstOrNull { it.kind == TeamAnalysisEngine.SynergyKind.SPEED_CONTROL }?.providers.orEmpty()
            .map { (m, w) -> "${m.name}：$w" } +
            synergy.firstOrNull { it.kind == TeamAnalysisEngine.SynergyKind.PRIORITY }?.providers.orEmpty().map { (m, w) -> "${m.name}：$w（先制）" }
        val adv = mutableListOf<String>()
        val dis = mutableListOf<String>()
        val gaps = mutableListOf<String>()
        speeds.forEach { (m, v) ->
            val p = pct.getValue(m)
            adv += "${m.name} 速度 $v，快過 ${"%.0f".format(p)}% 的可用 Pokémon（以對方最速配置計）"
        }
        if (fast.isEmpty() && members.isNotEmpty()) dis += "沒有成員能快過 75% 的可用 Pokémon（最速配置）"
        if (slow.size >= 3) {
            dis += "${slow.size} 隻成員偏慢（快過少於 25%）"
            if (control.none { "戲法空間" in it || "Trick Room" in it }) gaps += "多隻慢速成員，但沒有 Trick Room"
        }
        if (control.isEmpty()) gaps += "沒有速度控制或先制招式"
        val tailwindUsers = control.count { "順風" in it || "Tailwind" in it }
        if (tailwindUsers > 0) adv += "有 Tailwind：4 回合內己方速度 ×2"
        return Summary(speeds.maxByOrNull { it.second }, speeds.minByOrNull { it.second }, fast, slow, control, adv, dis, gaps)
    }
}
