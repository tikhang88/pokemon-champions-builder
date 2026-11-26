package hk.tikhang.champions.core

import kotlin.math.max

/**
 * Team Analysis Engine (spec §8). Every result is derived from the team's actual forms (Mega
 * toggle included), abilities, movesets and stats, and the type chart in the data snapshot.
 * Nothing here is an opinion: each conclusion carries the numbers it came from.
 */
object TeamAnalysisEngine {

    // ---- Type effectiveness with abilities and special moves -------------------------------

    /** Defensive ability effects on the damage multiplier of an attacking type. */
    private val DEFENSIVE_ABILITY: Map<String, Map<String, Double>> = mapOf(
        "levitate" to mapOf("Ground" to 0.0), "eartheater" to mapOf("Ground" to 0.0),
        "flashfire" to mapOf("Fire" to 0.0), "wellbakedbody" to mapOf("Fire" to 0.0),
        "waterabsorb" to mapOf("Water" to 0.0), "stormdrain" to mapOf("Water" to 0.0),
        "dryskin" to mapOf("Water" to 0.0, "Fire" to 1.25),
        "voltabsorb" to mapOf("Electric" to 0.0), "lightningrod" to mapOf("Electric" to 0.0), "motordrive" to mapOf("Electric" to 0.0),
        "sapsipper" to mapOf("Grass" to 0.0),
        "thickfat" to mapOf("Fire" to 0.5, "Ice" to 0.5), "heatproof" to mapOf("Fire" to 0.5),
        "waterbubble" to mapOf("Fire" to 0.5), "purifyingsalt" to mapOf("Ghost" to 0.5),
        "fluffy" to mapOf("Fire" to 2.0),
    )

    /** Abilities that cut super-effective damage to 0.75×. */
    private val FILTER = setOf("filter", "solidrock", "prismarmor")

    /** Abilities that turn Normal-type moves into another type. */
    private val ATE = mapOf(
        "dragonize" to "Dragon", "pixilate" to "Fairy", "aerilate" to "Flying",
        "refrigerate" to "Ice", "galvanize" to "Electric",
    )

    fun moveType(move: Move, ability: String?): String {
        val a = ability?.let(::toId)
        if (a == "normalize") return "Normal"
        if (move.type == "Normal" && a != null) ATE[a]?.let { return it }
        return move.type
    }

    fun abilityLabel(abilityId: String): String? = when {
        abilityId in DEFENSIVE_ABILITY -> DEFENSIVE_ABILITY.getValue(abilityId).entries.joinToString("、") { (t, m) -> "$t ×${fmt(m)}" }
        abilityId in FILTER -> "效果絕佳傷害 ×0.75"
        else -> null
    }

    /** Applies a defender's ability (immunities, Thick Fat, Filter…) to a type multiplier. */
    fun applyDefensiveAbility(m: Double, attackType: String, defAbility: String?): Double {
        var r = m
        val a = defAbility?.let(::toId)
        DEFENSIVE_ABILITY[a]?.get(attackType)?.let { r *= it }
        if (a in FILTER && r > 1.0) r *= 0.75
        return r
    }

    /** Multiplier of an attacking type against a defender, including the defender's ability. */
    fun defensiveMultiplier(te: TypeEngine, attackType: String, defTypes: List<String>, defAbility: String?): Double {
        var m = te.multiplier(attackType, defTypes)
        val a = defAbility?.let(::toId)
        DEFENSIVE_ABILITY[a]?.get(attackType)?.let { m *= it }
        if (a in FILTER && m > 1.0) m *= 0.75
        return m
    }

    /** Multiplier of a specific move (after -ate abilities and special type rules) against a defender. */
    fun moveMultiplier(te: TypeEngine, move: Move, attackerAbility: String?, defTypes: List<String>): Double {
        val type = moveType(move, attackerAbility)
        val a = attackerAbility?.let(::toId)
        var m = when (move.id) {
            // Freeze-Dry is super effective on Water.
            "freezedry" -> defTypes.fold(1.0) { acc, d -> acc * if (d == "Water") 2.0 else te.multiplier(type, listOf(d)) }
            // Flying Press adds Flying effectiveness.
            "flyingpress" -> te.multiplier("Fighting", defTypes) * te.multiplier("Flying", defTypes)
            // Thousand Arrows hits Flying types (neutral for the Flying part).
            "thousandarrows" -> defTypes.fold(1.0) { acc, d -> acc * if (d == "Flying") 1.0 else te.multiplier(type, listOf(d)) }
            else -> te.multiplier(type, defTypes)
        }
        // Scrappy / Mind's Eye: Normal and Fighting moves hit Ghost.
        if ((a == "scrappy" || a == "mindseye") && (type == "Normal" || type == "Fighting") && "Ghost" in defTypes) {
            m = defTypes.filter { it != "Ghost" }.fold(1.0) { acc, d -> acc * te.multiplier(type, listOf(d)) }
        }
        return m
    }

    private fun isDamaging(m: Move) = m.category != "Status"

    // ---- Per-member view ------------------------------------------------------------------

    data class Member(
        val index: Int,
        val slot: TeamSlot,
        val form: Pokemon,
        val name: String,
        val ability: String?,
        val moves: List<Move>,
        val stats: Map<Stat, Int>,
    ) {
        val damaging: List<Move> get() = moves.filter { it.category != "Status" }
    }

    /** [useMega] = false analyses every member in its original form (spec §6.5 comparison). */
    fun members(s: Snapshot, team: Team, useMega: Boolean = true): List<Member> =
        team.slots.withIndex().mapNotNull { (i, sl) ->
            val slot = (if (useMega) sl else sl?.copy(megaFormId = null)) ?: return@mapNotNull null
            val form = TeamEngine.effectiveForm(s, slot) ?: return@mapNotNull null
            Member(i, slot, form, s.displayName(form), TeamEngine.effectiveAbility(s, slot),
                slot.moves.mapNotNull { s.moveById[it] }, TeamEngine.finalStats(s, slot))
        }

    // ---- Defensive coverage (spec §8.1, §8.2, §9.2) ---------------------------------------

    enum class Effect(val zh: String, val symbol: String) {
        IMMUNE("無效", "0"), RESIST_4("¼", "¼"), RESIST("½", "½"), NEUTRAL("一般", "·"), WEAK("2倍", "2"), WEAK_4("4倍", "4");

        companion object {
            fun of(m: Double): Effect = when {
                m == 0.0 -> IMMUNE; m <= 0.25 -> RESIST_4; m < 1.0 -> RESIST
                m == 1.0 -> NEUTRAL; m < 4.0 -> WEAK; else -> WEAK_4
            }
        }
    }

    data class DefenseRow(val type: String, val perMember: List<Double>) {
        val weak: Int get() = perMember.count { it > 1.0 }
        val resist: Int get() = perMember.count { it > 0.0 && it < 1.0 }
        val immune: Int get() = perMember.count { it == 0.0 }
        val neutral: Int get() = perMember.count { it == 1.0 }
        /** Positive = more members weak than covered. */
        val balance: Int get() = weak - resist - immune
        val isShared: Boolean get() = weak >= 3 || (weak >= 2 && balance >= 2)
        val isGap: Boolean get() = resist + immune == 0
    }

    fun defense(s: Snapshot, te: TypeEngine, members: List<Member>): List<DefenseRow> =
        s.types.map { t -> DefenseRow(t, members.map { defensiveMultiplier(te, t, it.form.types, it.ability) }) }

    // ---- Offensive coverage (spec §8.3, §9.3) ---------------------------------------------

    data class Hit(val member: Member, val move: Move, val multiplier: Double, val type: String)

    data class OffenseRow(val type: String, val hits: List<Hit>) {
        val best: Double get() = hits.maxOfOrNull { it.multiplier } ?: 0.0
        val superEffective: List<Hit> get() = hits.filter { it.multiplier > 1.0 }
        val superEffectiveMembers: Int get() = superEffective.map { it.member.index }.distinct().size
        val isGap: Boolean get() = best <= 1.0
        val hasNoNeutral: Boolean get() = hits.isEmpty() || best < 1.0
    }

    fun offense(s: Snapshot, te: TypeEngine, members: List<Member>): List<OffenseRow> =
        s.types.map { t ->
            OffenseRow(t, members.flatMap { m ->
                m.damaging.map { mv -> Hit(m, mv, moveMultiplier(te, mv, m.ability, listOf(t)), moveType(mv, m.ability)) }
            })
        }

    // ---- Distributions (spec §8.5) --------------------------------------------------------

    enum class Role(val zh: String) { PHYSICAL("物理攻擊"), SPECIAL("特殊攻擊"), MIXED("雙刀"), SUPPORT("輔助"), UNKNOWN("未設定招式") }

    data class MemberRole(val member: Member, val physical: Int, val special: Int, val status: Int, val role: Role)

    fun roles(members: List<Member>): List<MemberRole> = members.map { m ->
        val p = m.moves.count { it.category == "Physical" }
        val sp = m.moves.count { it.category == "Special" }
        val st = m.moves.count { it.category == "Status" }
        val role = when {
            m.moves.isEmpty() -> Role.UNKNOWN
            st >= 2 || p + sp == 0 -> Role.SUPPORT
            p > 0 && sp > 0 && kotlin.math.abs(p - sp) <= 1 -> Role.MIXED
            p > sp -> Role.PHYSICAL
            else -> Role.SPECIAL
        }
        MemberRole(m, p, sp, st, role)
    }

    // ---- Synergy (spec §8.4) --------------------------------------------------------------

    enum class SynergyKind(val zh: String) {
        SPEED_CONTROL("速度控制"), PRIORITY("先制招式"), SUPPORT("輔助"), SETUP("強化"), PIVOT("交換（Pivot）"),
        STATUS("異常狀態"), PROTECT("守住類"), WEATHER("天氣"), TERRAIN("場地"),
    }

    data class SynergyEntry(val kind: SynergyKind, val providers: List<Pair<Member, String>>)

    val SPEED_CONTROL = setOf("tailwind", "trickroom", "icywind", "electroweb", "thunderwave", "scaryface", "bulldoze",
        "rocktomb", "glare", "nuzzle", "stringshot", "cottonspore", "lowsweep", "mudshot", "bleakwindstorm")
    private val SUPPORT_MOVES = setOf("followme", "ragepowder", "helpinghand", "wideguard", "quickguard", "reflect", "lightscreen",
        "auroraveil", "fakeout", "allyswitch", "coaching", "afteryou", "instruct", "pollenpuff", "lifedew", "healpulse", "decorate",
        "encore", "taunt", "haze", "clearsmog", "safeguard", "spore", "partingshot", "snarl", "faketears", "screech", "psychup")
    private val SUPPORT_ABILITIES = setOf("intimidate", "prankster", "friendguard", "telepathy", "flowerveil", "healer", "hospitality", "commander")
    val SETUP = setOf("swordsdance", "nastyplot", "dragondance", "calmmind", "bulkup", "quiverdance", "shellsmash", "bellydrum",
        "coil", "shiftgear", "irondefense", "agility", "rockpolish", "curse", "growth", "workup", "tailglow", "victorydance",
        "filletaway", "clangoroussoul", "noretreat", "tidyup", "geomancy", "cosmicpower", "amnesia", "acidarmor", "honeclaws", "howl", "autotomize")
    private val PIVOT = setOf("uturn", "voltswitch", "flipturn", "partingshot", "teleport", "chillyreception", "batonpass", "shedtail")
    private val STATUS = setOf("willowisp", "thunderwave", "toxic", "spore", "sleeppowder", "yawn", "hypnosis", "glare", "nuzzle",
        "stunspore", "poisonpowder", "darkvoid", "lovelykiss", "sing", "confuseray", "swagger", "toxicthread")
    private val PROTECT = setOf("protect", "detect", "spikyshield", "kingsshield", "banefulbunker", "silktrap", "burningbulwark", "obstruct")
    private val WEATHER_SET = mapOf("drought" to "晴天", "drizzle" to "下雨", "sandstream" to "沙暴", "snowwarning" to "下雪",
        "sunnyday" to "晴天", "raindance" to "下雨", "sandstorm" to "沙暴", "snowscape" to "下雪")
    private val WEATHER_USE = mapOf("chlorophyll" to "晴天", "solarpower" to "晴天", "protosynthesis" to "晴天", "megasol" to "晴天（自身）",
        "swiftswim" to "下雨", "raindish" to "下雨", "hydration" to "下雨", "sandrush" to "沙暴", "sandforce" to "沙暴",
        "slushrush" to "下雪", "icebody" to "下雪", "weatherball" to "任何天氣", "solarbeam" to "晴天", "thunder" to "下雨", "hurricane" to "下雨", "blizzard" to "下雪")
    private val TERRAIN_SET = mapOf("electricsurge" to "電氣場地", "grassysurge" to "青草場地", "psychicsurge" to "精神場地",
        "mistysurge" to "薄霧場地", "seedsower" to "青草場地", "hadronengine" to "電氣場地",
        "electricterrain" to "電氣場地", "grassyterrain" to "青草場地", "psychicterrain" to "精神場地", "mistyterrain" to "薄霧場地")
    private val TERRAIN_USE = mapOf("surgesurfer" to "電氣場地", "quarkdrive" to "電氣場地", "grassyglide" to "青草場地",
        "expandingforce" to "精神場地", "risingvoltage" to "電氣場地", "mistyexplosion" to "薄霧場地", "terrainpulse" to "任何場地")

    fun synergy(members: List<Member>): List<SynergyEntry> {
        val out = mutableMapOf<SynergyKind, MutableList<Pair<Member, String>>>()
        fun add(k: SynergyKind, m: Member, what: String) { out.getOrPut(k) { mutableListOf() } += m to what }
        for (m in members) {
            val ab = m.ability?.let(::toId)
            for (mv in m.moves) {
                val n = mv.nameZh ?: mv.name
                if (mv.id in SPEED_CONTROL) add(SynergyKind.SPEED_CONTROL, m, n)
                if (mv.priority > 0 && mv.category != "Status") add(SynergyKind.PRIORITY, m, "$n（+${mv.priority}）")
                if (mv.id in SUPPORT_MOVES) add(SynergyKind.SUPPORT, m, n)
                if (mv.id in SETUP) add(SynergyKind.SETUP, m, n)
                if (mv.id in PIVOT) add(SynergyKind.PIVOT, m, n)
                if (mv.id in STATUS) add(SynergyKind.STATUS, m, n)
                if (mv.id in PROTECT) add(SynergyKind.PROTECT, m, n)
                WEATHER_SET[mv.id]?.let { add(SynergyKind.WEATHER, m, "$n：設置$it") }
                WEATHER_USE[mv.id]?.let { add(SynergyKind.WEATHER, m, "$n：受惠於$it") }
                TERRAIN_SET[mv.id]?.let { add(SynergyKind.TERRAIN, m, "$n：設置$it") }
                TERRAIN_USE[mv.id]?.let { add(SynergyKind.TERRAIN, m, "$n：受惠於$it") }
            }
            if (ab != null) {
                if (ab in SUPPORT_ABILITIES) add(SynergyKind.SUPPORT, m, "特性 ${m.ability}")
                if (ab in setOf("regenerator", "emergencyexit", "wimpout")) add(SynergyKind.PIVOT, m, "特性 ${m.ability}")
                if (ab == "prankster") add(SynergyKind.PRIORITY, m, "特性 Prankster（變化招式 +1）")
                WEATHER_SET[ab]?.let { add(SynergyKind.WEATHER, m, "特性 ${m.ability}：設置$it") }
                WEATHER_USE[ab]?.let { add(SynergyKind.WEATHER, m, "特性 ${m.ability}：受惠於$it") }
                TERRAIN_SET[ab]?.let { add(SynergyKind.TERRAIN, m, "特性 ${m.ability}：設置$it") }
                TERRAIN_USE[ab]?.let { add(SynergyKind.TERRAIN, m, "特性 ${m.ability}：受惠於$it") }
            }
        }
        return SynergyKind.entries.map { SynergyEntry(it, out[it].orEmpty()) }
    }

    // ---- Threats from the Regulation's Pokémon (type-based, explainable) -------------------

    data class Threat(val pokemon: Pokemon, val count: Int, val detail: String)

    /** Pokémon the team has no neutral-or-better move against (by type; opponent abilities not counted). */
    fun walls(s: Snapshot, te: TypeEngine, members: List<Member>, pool: List<Pokemon>): List<Threat> {
        val all = members.flatMap { m -> m.damaging.map { m to it } }
        if (all.isEmpty()) return emptyList()
        return pool.mapNotNull { p ->
            val best = all.maxOf { (m, mv) -> moveMultiplier(te, mv, m.ability, p.types) }
            if (best < 1.0) Threat(p, 0, "隊伍招式對牠最多只有 ×${fmt(best)}") else null
        }.sortedByDescending { it.pokemon.baseStats.total }
    }

    /** Pokémon whose own types (STAB) hit at least half the team super effectively. */
    fun stabThreats(s: Snapshot, te: TypeEngine, members: List<Member>, pool: List<Pokemon>): List<Threat> {
        if (members.isEmpty()) return emptyList()
        val need = max(2, (members.size + 1) / 2)
        return pool.mapNotNull { p ->
            val hit = members.filter { m -> p.types.any { t -> defensiveMultiplier(te, t, m.form.types, m.ability) > 1.0 } }
            if (hit.size >= need) Threat(p, hit.size, "本身屬性可效果絕佳打擊 ${hit.size} 隻：${hit.joinToString("、") { it.name }}") else null
        }.sortedWith(compareByDescending<Threat> { it.count }.thenByDescending { it.pokemon.baseStats.total })
    }

    // ---- Improvement suggestions (spec §8.6): always with problem / reason / gain / cost ----

    data class Suggestion(
        val title: String,
        val problem: String,
        val reason: String,
        val benefit: String,
        val cost: String,
        val newWeaknesses: String,
        val label: String = "分析建議（估算，非必然最佳）",
    )

    private fun shareCount(rows: List<DefenseRow>) = rows.filter { it.isShared }.sumOf { it.weak }

    fun suggestions(s: Snapshot, te: TypeEngine, team: Team, members: List<Member>, pool: List<Pokemon>): List<Suggestion> {
        if (members.isEmpty()) return emptyList()
        val out = mutableListOf<Suggestion>()
        val def = defense(s, te, members)
        val off = offense(s, te, members)

        // 1) Biggest shared weakness → candidates that resist it, replacing the most exposed member.
        def.filter { it.isShared }.maxByOrNull { it.balance * 10 + it.weak }?.let { row ->
            val t = row.type
            val exposed = members.filter { defensiveMultiplier(te, t, it.form.types, it.ability) > 1.0 }
            val replace = exposed.maxWith(compareBy<Member> { m -> def.count { r -> r.perMember[members.indexOf(m)] > 1.0 } }.thenBy { -it.form.baseStats.total })
            val teamNums = members.map { it.form.num }.toSet()
            val candidates = pool.filter { !it.isMega && it.num !in teamNums && it.teamSelectable }
                .mapNotNull { c ->
                    val ab = c.abilities.minByOrNull { a -> defensiveMultiplier(te, t, c.types, a) } ?: return@mapNotNull null
                    if (defensiveMultiplier(te, t, c.types, ab) >= 1.0) return@mapNotNull null
                    val newMembers = members.map { m ->
                        if (m.index == replace.index) m.copy(form = c, name = s.displayName(c), ability = ab, moves = emptyList()) else m
                    }
                    val newDef = defense(s, te, newMembers)
                    val newShared = newDef.filter { it.isShared && def.first { d -> d.type == it.type }.let { d -> !d.isShared } }.map { it.type }
                    val gain = shareCount(def) - shareCount(newDef)
                    Triple(c, ab, Triple(gain, newShared, newDef))
                }
                .sortedWith(compareByDescending<Triple<Pokemon, String, Triple<Int, List<String>, List<DefenseRow>>>> { it.third.first - 2 * it.third.second.size }
                    .thenByDescending { it.first.baseStats.total })
                .take(3)
            // Offensive coverage only the replaced member provides.
            val lost = off.filter { r -> r.superEffective.isNotEmpty() && r.superEffective.all { it.member.index == replace.index } }.map { it.type }
            for ((c, ab, res) in candidates) {
                val (gain, newShared, newDef) = res
                val after = newDef.first { it.type == t }
                out += Suggestion(
                    title = "以 ${s.displayName(c)} 代替 ${replace.name}",
                    problem = "${zhType(t)} 是共同弱點：${row.weak} 隻怕 ${zhType(t)}，只有 ${row.resist + row.immune} 隻可以抵抗或無效。",
                    reason = "${s.displayName(c)}（${c.types.joinToString("/") { zhType(it) }}）受 ${zhType(t)} 屬性 ×${fmt(defensiveMultiplier(te, t, c.types, ab))}" +
                        (if (abilityLabel(toId(ab)) != null) "（需選特性 $ab）" else "") + "；${replace.name} 是隊中弱點最多的 ${zhType(t)} 弱點成員。",
                    benefit = "${zhType(t)} 弱點：${row.weak} → ${after.weak} 隻；共同弱點總數減少 $gain。",
                    cost = if (lost.isEmpty()) "失去 ${replace.name} 的招式及角色；新成員的招式需要重新設定。"
                    else "失去 ${replace.name} 對 ${lost.joinToString("、") { zhType(it) }} 的效果絕佳攻擊（隊中沒有其他成員提供）。",
                    newWeaknesses = if (newShared.isEmpty()) "沒有新增共同弱點（只按屬性計算）。" else "可能新增共同弱點：${newShared.joinToString("、") { zhType(it) }}。",
                )
            }
            out += Suggestion(
                title = "不更換成員的做法（${zhType(t)}）",
                problem = "${zhType(t)} 是共同弱點。",
                reason = "替換 Pokémon 不是唯一方法。",
                benefit = "可考慮：抵抗 ${zhType(t)} 的樹果、Wide Guard／Protect、速度控制令我方先手擊倒對方、或調整特性。",
                cost = "這些方法需要佔用道具或招式欄位，亦不能完全消除弱點。",
                newWeaknesses = "不會改變隊伍屬性組合。",
            )
        }

        // 2) Offensive gaps → moves the current members can already learn.
        off.filter { it.isGap && members.any { m -> m.damaging.isNotEmpty() } }.take(3).forEach { row ->
            val options = members.flatMap { m ->
                val learn = (s.learnsets[m.slot.speciesId] ?: s.learnsets[m.form.baseId]).orEmpty()
                learn.mapNotNull { s.moveById[it] }
                    .filter { isDamaging(it) && it.basePower >= 60 && it.id !in m.slot.moves && moveMultiplier(te, it, m.ability, listOf(row.type)) > 1.0 }
                    .sortedByDescending { it.basePower }.take(1).map { m to it }
            }.take(3)
            if (options.isNotEmpty()) {
                out += Suggestion(
                    title = "補上 ${zhType(row.type)} 打擊面",
                    problem = "隊伍沒有招式可以效果絕佳打擊 ${zhType(row.type)} 屬性（目前最多 ×${fmt(row.best)}）。",
                    reason = "現有成員已可學會：" + options.joinToString("；") { (m, mv) -> "${m.name} → ${mv.nameZh ?: mv.name}（${zhType(moveType(mv, m.ability))}，威力 ${mv.basePower}）" },
                    benefit = "不用更換成員就可以覆蓋 ${zhType(row.type)}。",
                    cost = "需要替換該成員現有的一個招式" + if (options.any { it.first.slot.moves.size < MAX_MOVES }) "（有成員仍有空招式欄）。" else "。",
                    newWeaknesses = "不影響防守屬性。",
                )
            }
        }
        return out
    }

    // ---- Report (spec §8.6) --------------------------------------------------------------

    data class Report(
        val strengths: List<String>,
        val weaknesses: List<String>,
        val coverageGaps: List<String>,
        val synergy: List<String>,
        val suggestions: List<Suggestion>,
    )

    data class Analysis(
        val members: List<Member>,
        val defense: List<DefenseRow>,
        val offense: List<OffenseRow>,
        val roles: List<MemberRole>,
        val synergy: List<SynergyEntry>,
        val walls: List<Threat>,
        val stabThreats: List<Threat>,
        val report: Report,
        val megaCount: Int,
    ) {
        val speedOrder: List<Pair<Member, Int>> get() = members.map { it to (it.stats[Stat.SPE] ?: 0) }.sortedByDescending { it.second }
    }

    fun analyse(s: Snapshot, te: TypeEngine, team: Team, useMega: Boolean = true): Analysis {
        val members = members(s, team, useMega)
        val allowed = s.regulationById[team.regulationId]?.allowedPokemon?.toSet()
        val pool = s.pokemon.filter { it.teamSelectable && (allowed == null || it.id in allowed) }
        val def = defense(s, te, members)
        val off = offense(s, te, members)
        val syn = synergy(members)
        val walls = walls(s, te, members, pool)
        val threats = stabThreats(s, te, members, pool)
        val roles = roles(members)

        val strengths = mutableListOf<String>()
        val weaknesses = mutableListOf<String>()
        val gaps = mutableListOf<String>()
        val synergyText = mutableListOf<String>()
        if (members.isNotEmpty()) {
            val wellCovered = def.filter { it.resist + it.immune >= 3 }.map { zhType(it.type) }
            if (wellCovered.isNotEmpty()) strengths += "防守穩固：${wellCovered.joinToString("、")}（3 隻或以上抵抗／無效）"
            val immunities = def.filter { it.immune > 0 }.map { "${zhType(it.type)}（${it.immune}）" }
            if (immunities.isNotEmpty()) strengths += "有無效屬性：${immunities.joinToString("、")}"
            val seTypes = off.count { it.superEffective.isNotEmpty() }
            if (off.any { it.hits.isNotEmpty() }) strengths += "招式可效果絕佳打擊 $seTypes / ${s.types.size} 種屬性"
            def.filter { it.isShared }.forEach { weaknesses += "共同弱點 ${zhType(it.type)}：${it.weak} 隻弱、${it.resist + it.immune} 隻抵抗／無效" }
            def.filter { it.isGap && it.weak > 0 && !it.isShared }.forEach { weaknesses += "${zhType(it.type)}：沒有成員可以抵抗（${it.weak} 隻弱）" }
            def.filter { r -> r.perMember.any { it >= 4.0 } }.forEach { r ->
                weaknesses += "${zhType(r.type)} 4 倍弱點：" + members.filterIndexed { i, _ -> r.perMember[i] >= 4.0 }.joinToString("、") { it.name }
            }
            if (members.none { it.damaging.isNotEmpty() }) gaps += "未設定攻擊招式，未能分析攻擊覆蓋"
            else {
                off.filter { it.isGap }.forEach { gaps += "${zhType(it.type)}：沒有效果絕佳的招式（最多 ×${fmt(it.best)}）" }
                off.filter { it.superEffectiveMembers >= 3 }.forEach { gaps += "重複覆蓋 ${zhType(it.type)}：${it.superEffectiveMembers} 隻都可效果絕佳打擊" }
                if (walls.isNotEmpty()) gaps += "有 ${walls.size} 隻可用 Pokémon 抵抗隊伍全部攻擊招式，例如 ${walls.take(5).joinToString("、") { s.displayName(it.pokemon) }}"
            }
            syn.forEach { e ->
                synergyText += if (e.providers.isEmpty()) "${e.kind.zh}：沒有" else "${e.kind.zh}：" + e.providers.joinToString("、") { (m, w) -> "${m.name}（$w）" }
            }
            val megaCount = members.count { it.slot.megaFormId != null }
            if (megaCount > 1) weaknesses += "有 $megaCount 隻 Mega，但每場只可 Mega 一次"
            if (members.size < TEAM_SIZE) weaknesses += "隊伍只有 ${members.size} 隻，分析只計已加入的成員"
        }
        return Analysis(
            members, def, off, roles, syn, walls, threats,
            Report(strengths, weaknesses, gaps, synergyText, suggestions(s, te, team, members, pool)),
            members.count { it.slot.megaFormId != null },
        )
    }

    private val TYPE_ZH = mapOf(
        "Normal" to "一般", "Fire" to "火", "Water" to "水", "Electric" to "電", "Grass" to "草", "Ice" to "冰",
        "Fighting" to "格鬥", "Poison" to "毒", "Ground" to "地面", "Flying" to "飛行", "Psychic" to "超能力",
        "Bug" to "蟲", "Rock" to "岩石", "Ghost" to "幽靈", "Dragon" to "龍", "Dark" to "惡", "Steel" to "鋼", "Fairy" to "妖精",
    )

    fun zhType(t: String): String = TYPE_ZH[t] ?: t

    fun fmt(m: Double): String = when (m) {
        0.0 -> "0"; 0.25 -> "¼"; 0.5 -> "½"; 0.375 -> "0.375"
        else -> if (m == Math.floor(m)) m.toInt().toString() else "%.2f".format(m).trimEnd('0').trimEnd('.')
    }
}
