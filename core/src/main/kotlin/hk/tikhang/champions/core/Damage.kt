package hk.tikhang.champions.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Damage Calculation Engine (spec §12). Gen 9 damage pipeline, which Pokémon Champions keeps
 * (Showdown "champions" mod), with Champions data (stats at Lv50 from Stat Points, changed moves).
 * Rounding follows the Smogon damage calculator step by step; the unit tests check this engine
 * against the calculator's published Gen 9 results.
 */
object DamageEngine {

    enum class Status(val zh: String) { NONE("無"), BURN("灼傷"), PARALYSIS("麻痺"), POISON("中毒"), TOXIC("劇毒"), SLEEP("睡眠"), FREEZE("冰凍") }
    enum class GameType(val zh: String) { SINGLES("單打"), DOUBLES("雙打") }
    enum class Terrain(val zh: String) { NONE("無"), ELECTRIC("電氣場地"), GRASSY("青草場地"), PSYCHIC("精神場地"), MISTY("薄霧場地") }

    /** One side of the calculation. [stats] are final Lv-[level] stats before stat stages. */
    data class Side(
        val pokemon: Pokemon,
        val stats: Map<Stat, Int>,
        val ability: String? = null,
        val item: String? = null,
        val status: Status = Status.NONE,
        val boosts: Map<Stat, Int> = emptyMap(),
        val curHpPercent: Int = 100,
        val level: Int = 50,
        /** Flash Fire / Protosynthesis / Quark Drive / Unburden etc. currently active. */
        val abilityActive: Boolean = false,
        val types: List<String> = pokemon.types,
        /** Exact current HP (Battle Simulator); otherwise derived from [curHpPercent]. */
        val hpExact: Int? = null,
    ) {
        val maxHp: Int get() = stats[Stat.HP] ?: 1
        val curHp: Int get() = hpExact ?: max(1, (maxHp * curHpPercent + 99) / 100).coerceAtMost(maxHp)
        val atFullHp: Boolean get() = curHp >= maxHp
        fun ab(): String? = ability?.let(::toId)
        fun itemId(): String? = item?.let(::toId)
        fun boost(s: Stat) = (boosts[s] ?: 0).coerceIn(-6, 6)
    }

    data class Field(
        val gameType: GameType = GameType.DOUBLES,
        val weather: SpeedEngine.Weather = SpeedEngine.Weather.NONE,
        val terrain: Terrain = Terrain.NONE,
        val reflect: Boolean = false,
        val lightScreen: Boolean = false,
        val auroraVeil: Boolean = false,
        val helpingHand: Boolean = false,
        val friendGuard: Boolean = false,
        /** Spread move hitting more than one target (doubles). */
        val spreadHit: Boolean = true,
        val critical: Boolean = false,
        /** Number of hits for 2–5 hit moves; null = default (3, or 5 with Skill Link, 4 with Loaded Dice). */
        val hits: Int? = null,
    )

    data class Result(
        /** Damage of one use for each of the 16 random rolls (all hits added together). */
        val rolls: List<Int>,
        val perHitRolls: List<List<Int>>,
        val defenderHp: Int,
        val defenderMaxHp: Int,
        val basePower: Int,
        val moveType: String,
        val effectiveness: Double,
        val critical: Boolean,
        val notes: List<String>,
        val verification: String,
    ) {
        val min: Int get() = rolls.minOrNull() ?: 0
        val max: Int get() = rolls.maxOrNull() ?: 0
        val minPercent: Double get() = min * 100.0 / defenderMaxHp
        val maxPercent: Double get() = max * 100.0 / defenderMaxHp
        val ko: KO by lazy { koChance(perHitRolls, defenderHp) }
    }

    /** n uses needed and the chance to KO in exactly that many (0..1). n = 0 → no damage. */
    data class KO(val hits: Int, val chance: Double) {
        fun text(): String = when {
            hits == 0 -> "沒有傷害"
            hits > 9 -> "需要 10 次以上"
            chance >= 0.9999 -> "確定 ${n(hits)}"
            chance <= 0.0001 -> "有機會 ${n(hits)}"
            else -> "%.1f%% 機率 %s".format(chance * 100, n(hits))
        }
        private fun n(h: Int) = if (h == 1) "一擊擊倒（OHKO）" else "${h} 擊擊倒（${h}HKO）"
    }

    // ---- Rounding helpers (Smogon calc util.ts) ------------------------------------------

    fun pokeRound(n: Double): Int = if (n % 1 > 0.5) ceil(n).toInt() else floor(n).toInt()
    fun chain(mods: List<Int>): Int {
        var m = 4096L
        for (x in mods) if (x != 4096) m = (m * x + 2048) shr 12
        return m.toInt()
    }
    private fun applyChain(value: Int, mods: List<Int>, lo: Int, hi: Int): Int =
        max(1, pokeRound(value.toDouble() * chain(mods).coerceIn(lo, hi) / 4096))

    fun stageStat(stat: Int, stage: Int): Int {
        val s = stage.coerceIn(-6, 6)
        val (num, den) = if (s >= 0) (2 + s) to 2 else 2 to (2 - s)
        return stat * num / den
    }

    // ---- Move data helpers ----------------------------------------------------------------

    private val ALWAYS_CRIT = setOf("surgingstrikes", "wickedblow", "flowertrick", "frostbreath", "stormthrow", "zippyzap")
    private val TYPE_ITEMS = mapOf(
        "charcoal" to "Fire", "mysticwater" to "Water", "miracleseed" to "Grass", "magnet" to "Electric", "nevermeltice" to "Ice",
        "blackbelt" to "Fighting", "poisonbarb" to "Poison", "softsand" to "Ground", "sharpbeak" to "Flying", "twistedspoon" to "Psychic",
        "silverpowder" to "Bug", "hardstone" to "Rock", "spelltag" to "Ghost", "dragonfang" to "Dragon", "blackglasses" to "Dark",
        "metalcoat" to "Steel", "silkscarf" to "Normal", "fairyfeather" to "Fairy",
        "flameplate" to "Fire", "splashplate" to "Water", "meadowplate" to "Grass", "zapplate" to "Electric", "icicleplate" to "Ice",
        "fistplate" to "Fighting", "toxicplate" to "Poison", "earthplate" to "Ground", "skyplate" to "Flying", "mindplate" to "Psychic",
        "insectplate" to "Bug", "stoneplate" to "Rock", "spookyplate" to "Ghost", "dracoplate" to "Dragon", "dreadplate" to "Dark",
        "ironplate" to "Steel", "pixieplate" to "Fairy",
    )
    private val RESIST_BERRIES = mapOf(
        "occaberry" to "Fire", "passhoberry" to "Water", "rindoberry" to "Grass", "wacanberry" to "Electric", "yacheberry" to "Ice",
        "chopleberry" to "Fighting", "kebiaberry" to "Poison", "shucaberry" to "Ground", "cobaberry" to "Flying", "payapaberry" to "Psychic",
        "tangaberry" to "Bug", "chartiberry" to "Rock", "kasibberry" to "Ghost", "habanberry" to "Dragon", "colburberry" to "Dark",
        "babiriberry" to "Steel", "roseliberry" to "Fairy", "chilanberry" to "Normal",
    )
    private val ATE = mapOf("dragonize" to "Dragon", "pixilate" to "Fairy", "aerilate" to "Flying", "refrigerate" to "Ice", "galvanize" to "Electric")
    private val SPREAD_TARGETS = setOf("allAdjacentFoes", "allAdjacent")
    /** Moves with a special power or damage rule that this engine handles. */
    private val HANDLED_SPECIAL = setOf("lowkick", "grassknot", "heavyslam", "heatcrash", "gyroball", "electroball", "seismictoss",
        "nightshade", "superfang", "ruination", "naturesmadness", "eruption", "waterspout", "dragonenergy", "weatherball",
        "facade", "hex", "venoshock", "brine", "acrobatics", "knockoff", "foulplay", "bodypress", "psyshock", "psystrike",
        "secretsword", "tripleaxel", "triplekick", "terrainpulse", "risingvoltage", "expandingforce", "poltergeist")

    private fun hasSecondary(m: Move) = m.secondary != null && m.secondary !is kotlinx.serialization.json.JsonNull
    private fun hasRecoil(m: Move) = m.recoil != null && m.recoil !is kotlinx.serialization.json.JsonNull

    fun isGrounded(side: Side): Boolean =
        side.itemId() == "ironball" || ("Flying" !in side.types && side.ab() != "levitate" && side.itemId() != "airballoon")

    /** Number of hits for one use, and whether it is a "2–5" move. */
    fun hitCount(move: Move, attacker: Side, field: Field): Int {
        val mh = move.multihit ?: return 1
        (mh as? JsonPrimitive)?.intOrNull?.let { return it }
        val arr = mh as? JsonArray ?: return 1
        val lo = (arr.getOrNull(0) as? JsonPrimitive)?.intOrNull ?: 2
        val hi = (arr.getOrNull(1) as? JsonPrimitive)?.intOrNull ?: 5
        field.hits?.let { return it.coerceIn(lo, hi) }
        return when {
            attacker.ab() == "skilllink" -> hi
            attacker.itemId() == "loadeddice" && hi >= 5 -> 4
            else -> min(hi, max(lo, 3))
        }
    }

    // ---- Main calculation -----------------------------------------------------------------

    fun calculate(s: Snapshot, attacker: Side, defender: Side, move: Move, field: Field): Result {
        val te = TypeEngine(s.typeChart, s.types)
        val notes = mutableListOf<String>()
        var verification = Verification.VERIFIED
        val aAb = attacker.ab(); val dAb = defender.ab(); val aIt = attacker.itemId(); val dIt = defender.itemId()

        if (move.category == "Status") return empty(defender, move, "變化招式沒有傷害", notes)
        if (move.basePower == 0 && move.id !in HANDLED_SPECIAL) {
            notes += "此招式的威力由特殊規則決定，本計算器未支援"
            return empty(defender, move, null, notes, Verification.PENDING)
        }

        // Weather (Mega Sol: the user's moves act as in harsh sunlight).
        val weather = if (aAb == "megasol") SpeedEngine.Weather.SUN else field.weather
        val umbrella = dIt == "utilityumbrella"

        // Move type
        var type = move.type
        var bp = move.basePower
        val bpMods = mutableListOf<Int>()
        if (move.id == "weatherball" && weather != SpeedEngine.Weather.NONE) {
            type = when (weather) { SpeedEngine.Weather.SUN -> "Fire"; SpeedEngine.Weather.RAIN -> "Water"; SpeedEngine.Weather.SAND -> "Rock"; else -> "Ice" }
            bp = 100
        }
        if (move.id == "terrainpulse" && field.terrain != Terrain.NONE && isGrounded(attacker)) {
            type = when (field.terrain) { Terrain.ELECTRIC -> "Electric"; Terrain.GRASSY -> "Grass"; Terrain.PSYCHIC -> "Psychic"; else -> "Fairy" }
            bp = 100
        }
        var ateBoost = false
        if (aAb == "normalize") { if (type != "Normal") ateBoost = true; type = "Normal" }
        else if (type == "Normal" && aAb in ATE) { type = ATE.getValue(aAb!!); ateBoost = true }

        // Type effectiveness
        var eff = TeamAnalysisEngine.moveMultiplier(te, move.copy(type = type), null, defender.types)
        // Gen 5+: a Flying type grounded by Iron Ball takes neutral damage from Ground moves (Smogon calc).
        if (type == "Ground" && "Flying" in defender.types && dIt == "ironball") eff = 1.0
        if ((aAb == "scrappy" || aAb == "mindseye") && (type == "Normal" || type == "Fighting") && "Ghost" in defender.types)
            eff = te.multiplier(type, defender.types.filter { it != "Ghost" })
        val groundImmune = type == "Ground" && !isGrounded(defender) && move.id != "thousandarrows"
        if (groundImmune && eff > 0) { eff = 0.0; notes += "對手浮空（Levitate／氣球），地面招式無效" }
        val abilityImmune = when (dAb) {
            "flashfire", "wellbakedbody" -> type == "Fire"
            "waterabsorb", "stormdrain", "dryskin" -> type == "Water"
            "voltabsorb", "lightningrod", "motordrive" -> type == "Electric"
            "sapsipper" -> type == "Grass"
            "eartheater" -> type == "Ground"
            "wonderguard" -> eff <= 1.0
            "soundproof" -> "sound" in move.flags
            "bulletproof" -> "bullet" in move.flags
            else -> false
        } && aAb !in setOf("moldbreaker", "teravolt", "turboblaze")
        if (abilityImmune) { eff = 0.0; notes += "對手特性 ${defender.ability} 令此招式無效" }
        if (eff == 0.0) return empty(defender, move, null, notes)

        // Fixed-damage moves
        when (move.id) {
            "seismictoss", "nightshade" -> return fixed(defender, move, type, eff, attacker.level, notes)
            "superfang", "ruination", "naturesmadness" -> return fixed(defender, move, type, eff, max(1, defender.curHp / 2), notes)
        }

        // Base power changes
        val dWeight = defender.pokemon.weightKg ?: 0.0
        val aWeight = attacker.pokemon.weightKg ?: 0.0
        when (move.id) {
            "lowkick", "grassknot" -> bp = when { dWeight >= 200 -> 120; dWeight >= 100 -> 100; dWeight >= 50 -> 80; dWeight >= 25 -> 60; dWeight >= 10 -> 40; else -> 20 }
            "heavyslam", "heatcrash" -> { val r = if (dWeight > 0) aWeight / dWeight else 5.0; bp = when { r >= 5 -> 120; r >= 4 -> 100; r >= 3 -> 80; r >= 2 -> 60; else -> 40 } }
            "eruption", "waterspout", "dragonenergy" -> bp = max(1, 150 * attacker.curHp / attacker.maxHp)
            "facade" -> if (attacker.status in setOf(Status.BURN, Status.PARALYSIS, Status.POISON, Status.TOXIC)) bp *= 2
            "hex" -> if (defender.status != Status.NONE) bp *= 2
            "venoshock" -> if (defender.status == Status.POISON || defender.status == Status.TOXIC) bp *= 2
            "brine" -> if (defender.curHp * 2 <= defender.maxHp) bp *= 2
            "acrobatics" -> if (attacker.item == null) bp *= 2
            "gyroball" -> {
                val a = effSpeed(attacker, field); val d = effSpeed(defender, field)
                bp = min(150, (25 * d / max(1, a)) + 1)
            }
            "electroball" -> {
                val r = effSpeed(attacker, field) / max(1, effSpeed(defender, field))
                bp = when { r >= 4 -> 150; r >= 3 -> 120; r >= 2 -> 80; r >= 1 -> 60; else -> 40 }
            }
            "risingvoltage" -> if (field.terrain == Terrain.ELECTRIC && isGrounded(defender)) bp *= 2
            "expandingforce" -> if (field.terrain == Terrain.PSYCHIC && isGrounded(attacker)) bp = bp * 3 / 2
            "poltergeist" -> if (defender.item == null) { notes += "對手沒有道具：Poltergeist 會失敗"; return empty(defender, move, null, notes) }
        }

        // Base power modifiers
        val contact = "contact" in move.flags && aAb != "longreach" && aIt != "punchingglove"
        if (field.helpingHand) bpMods += 6144
        when (aAb) {
            "technician" -> if (bp <= 60) bpMods += 6144
            "toughclaws" -> if (contact) bpMods += 5325
            "ironfist" -> if ("punch" in move.flags) bpMods += 4915
            "strongjaw" -> if ("bite" in move.flags) bpMods += 6144
            "megalauncher" -> if ("pulse" in move.flags) bpMods += 6144
            "sharpness" -> if ("slicing" in move.flags) bpMods += 6144
            "sheerforce" -> if (hasSecondary(move)) bpMods += 5325
            "reckless" -> if (hasRecoil(move)) bpMods += 4915
            "punkrock" -> if ("sound" in move.flags) bpMods += 5325
            "sandforce" -> if (weather == SpeedEngine.Weather.SAND && type in setOf("Rock", "Ground", "Steel")) bpMods += 5325
        }
        if (ateBoost) bpMods += 4915
        if (dAb == "dryskin" && type == "Fire") bpMods += 5120
        if (aIt != null && TYPE_ITEMS[aIt] == type) bpMods += 4915
        if (aIt == "muscleband" && move.category == "Physical") bpMods += 4505
        if (aIt == "wiseglasses" && move.category == "Special") bpMods += 4505
        if (aIt == "punchingglove" && "punch" in move.flags) bpMods += 4506
        if (move.id == "knockoff" && dIt != null && s.itemById[dIt]?.isMegaStone != true) bpMods += 6144
        if (isGrounded(attacker)) when (field.terrain) {
            Terrain.ELECTRIC -> if (type == "Electric") bpMods += 5325
            Terrain.GRASSY -> if (type == "Grass") bpMods += 5325
            Terrain.PSYCHIC -> if (type == "Psychic") bpMods += 5325
            else -> {}
        }
        if (isGrounded(defender)) {
            if (field.terrain == Terrain.MISTY && type == "Dragon") bpMods += 2048
            if (field.terrain == Terrain.GRASSY && move.id in setOf("earthquake", "bulldoze")) bpMods += 2048
        }
        if (field.terrain == Terrain.PSYCHIC && isGrounded(defender) && move.priority > 0) {
            notes += "精神場地：先制招式對著地的對手無效"; return empty(defender, move, null, notes)
        }

        // Attack and defense stats
        val physical = move.category == "Physical"
        val crit = field.critical || move.id in ALWAYS_CRIT || move.willCrit == true
        val atkSource = if (move.id == "foulplay") defender else attacker
        val atkStat = when { move.id == "bodypress" -> Stat.DEF; physical -> Stat.ATK; else -> Stat.SPA }
        val defStat = if (physical || move.id in setOf("psyshock", "psystrike", "secretsword")) Stat.DEF else Stat.SPD
        val hitsPhysical = defStat == Stat.DEF

        var atk = atkSource.stats[atkStat] ?: 1
        val aBoost = atkSource.boost(atkStat)
        if (aBoost != 0 && !(crit && aBoost < 0) && dAb != "unaware") atk = stageStat(atk, aBoost)
        if (aAb == "hustle" && physical) atk = pokeRound(atk * 3 / 2.0)
        val atMods = mutableListOf<Int>()
        when (aAb) {
            "hugepower", "purepower" -> if (physical) atMods += 8192
            "guts" -> if (physical && attacker.status != Status.NONE) atMods += 6144
            "gorillatactics" -> if (physical) atMods += 6144
            "solarpower" -> if (!physical && weather == SpeedEngine.Weather.SUN) atMods += 6144
            "waterbubble" -> if (type == "Water") atMods += 8192
            "steelworker", "steelyspirit" -> if (type == "Steel") atMods += 6144
            "transistor" -> if (type == "Electric") atMods += 5325
            "dragonsmaw" -> if (type == "Dragon") atMods += 6144
            "rockypayload" -> if (type == "Rock") atMods += 6144
            "blaze" -> if (type == "Fire" && attacker.curHp * 3 <= attacker.maxHp) atMods += 6144
            "torrent" -> if (type == "Water" && attacker.curHp * 3 <= attacker.maxHp) atMods += 6144
            "overgrow" -> if (type == "Grass" && attacker.curHp * 3 <= attacker.maxHp) atMods += 6144
            "swarm" -> if (type == "Bug" && attacker.curHp * 3 <= attacker.maxHp) atMods += 6144
            "flashfire" -> if (type == "Fire" && attacker.abilityActive) atMods += 6144
            "defeatist" -> if (attacker.curHp * 2 <= attacker.maxHp) atMods += 2048
            "protosynthesis", "quarkdrive" -> if (attacker.abilityActive && highestStat(attacker) == atkStat) atMods += 5325
        }
        when (dAb) {
            "thickfat" -> if (type == "Fire" || type == "Ice") atMods += 2048
            "heatproof" -> if (type == "Fire") atMods += 2048
            "waterbubble" -> if (type == "Fire") atMods += 2048
            "purifyingsalt" -> if (type == "Ghost") atMods += 2048
        }
        if (aIt == "choiceband" && physical) atMods += 6144
        if (aIt == "choicespecs" && !physical) atMods += 6144
        atk = applyChain(atk, atMods, 410, 131072)

        var def = defender.stats[defStat] ?: 1
        val dBoost = defender.boost(defStat)
        if (dBoost != 0 && !(crit && dBoost > 0) && aAb != "unaware") def = stageStat(def, dBoost)
        if (weather == SpeedEngine.Weather.SAND && "Rock" in defender.types && !hitsPhysical && !umbrella) def = pokeRound(def * 3 / 2.0)
        if (weather == SpeedEngine.Weather.SNOW && "Ice" in defender.types && hitsPhysical) def = pokeRound(def * 3 / 2.0)
        val dfMods = mutableListOf<Int>()
        if (dIt == "assaultvest" && !hitsPhysical) dfMods += 6144
        if (dIt == "eviolite") { dfMods += 6144; notes += "Eviolite 只對未完全進化的 Pokémon 有效（已計算）" }
        if (dAb == "furcoat" && hitsPhysical) dfMods += 8192
        if (dAb == "marvelscale" && hitsPhysical && defender.status != Status.NONE) dfMods += 6144
        if (dAb == "grasspelt" && hitsPhysical && field.terrain == Terrain.GRASSY) dfMods += 6144
        if ((dAb == "protosynthesis" || dAb == "quarkdrive") && defender.abilityActive && highestStat(defender) == defStat) dfMods += 5325
        def = applyChain(def, dfMods, 410, 131072)

        val power = applyChain(bp, bpMods, 41, 2097152)

        // Final modifiers
        val finalMods = mutableListOf<Int>()
        val doubles = field.gameType == GameType.DOUBLES
        val screenMod = if (doubles) 2732 else 2048
        if (!crit && aAb != "infiltrator") {
            if (field.auroraVeil) finalMods += screenMod
            else if (field.reflect && hitsPhysical) finalMods += screenMod
            else if (field.lightScreen && !hitsPhysical) finalMods += screenMod
        }
        if (aAb == "neuroforce" && eff > 1) finalMods += 5120
        if (aAb == "sniper" && crit) finalMods += 6144
        if (aAb == "tintedlens" && eff < 1) finalMods += 8192
        if ((dAb == "multiscale" || dAb == "shadowshield") && defender.atFullHp) finalMods += 2048
        if (dAb == "fluffy" && contact && type != "Fire") finalMods += 2048
        if (dAb == "fluffy" && type == "Fire" && !contact) finalMods += 8192
        if (dAb == "punkrock" && "sound" in move.flags) finalMods += 2048
        if (dAb == "icescales" && !physical) finalMods += 2048
        if (field.friendGuard) finalMods += 3072
        if ((dAb == "filter" || dAb == "solidrock" || dAb == "prismarmor") && eff > 1) finalMods += 3072
        if (move.id in setOf("collisioncourse", "electrodrift") && eff > 1) finalMods += 5461
        if (aIt == "expertbelt" && eff > 1) finalMods += 4915
        if (aIt == "lifeorb") finalMods += 5324
        if (dIt != null && RESIST_BERRIES[dIt] == type && (eff > 1 || type == "Normal")) { finalMods += 2048; notes += "對手的抗性樹果令傷害減半（只限一次）" }
        val finalMod = chain(finalMods).coerceIn(41, 131072)

        // STAB
        val stabTypes = attacker.types
        var stab = 4096
        if (type in stabTypes || aAb in setOf("protean", "libero")) stab = if (aAb == "adaptability") 8192 else 6144

        // Burn
        val burned = attacker.status == Status.BURN && physical && aAb != "guts" && move.id != "facade"

        // Weather modifier on base damage
        val weatherMod = when {
            umbrella -> 4096
            weather == SpeedEngine.Weather.SUN && type == "Fire" -> 6144
            weather == SpeedEngine.Weather.SUN && type == "Water" -> 2048
            weather == SpeedEngine.Weather.RAIN && type == "Water" -> 6144
            weather == SpeedEngine.Weather.RAIN && type == "Fire" -> 2048
            else -> 4096
        }
        val spread = doubles && field.spreadHit && move.target in SPREAD_TARGETS

        // Hits
        val hits = hitCount(move, attacker, field)
        val parentalBond = aAb == "parentalbond" && hits == 1
        val perHit = mutableListOf<List<Int>>()
        for (h in 0 until (if (parentalBond) 2 else hits)) {
            val hitBp = when (move.id) {
                "tripleaxel" -> applyChain(20 * (h + 1), bpMods, 41, 2097152)
                "triplekick" -> applyChain(10 * (h + 1), bpMods, 41, 2097152)
                else -> power
            }
            var base = floor(floor(floor(2.0 * attacker.level / 5 + 2) * hitBp * atk / def) / 50 + 2).toInt()
            if (spread) base = pokeRound(base * 3072 / 4096.0)
            if (parentalBond && h == 1) base = pokeRound(base * 1024 / 4096.0)
            if (weatherMod != 4096) base = pokeRound(base * weatherMod / 4096.0)
            if (crit) base = floor(base * 1.5).toInt()
            perHit += (0 until 16).map { i -> finalDamage(base, i, eff, burned, stab, finalMod) }
        }
        val rolls = (0 until 16).map { i -> perHit.sumOf { it[i] } }

        if (hits > 1) notes += "${hits} 次攻擊（每次獨立隨機數；總傷害範圍為各次最小／最大之和）"
        if (parentalBond) notes += "親子愛：第二擊傷害為 25%"
        if (crit && (move.id in ALWAYS_CRIT || move.willCrit == true)) notes += "此招式必定擊中要害"
        if (weather != field.weather) notes += "Mega Sol：此 Pokémon 的招式視為在大晴天下使用"
        if (move.basePower == 0 || move.id in HANDLED_SPECIAL) notes += "威力按特殊規則計算：$bp"
        if (aAb == "protosynthesis" || aAb == "quarkdrive" || dAb == "protosynthesis" || dAb == "quarkdrive") verification = Verification.COMMUNITY
        if (move.championsChanged) notes += "此招式在 Champions 有調整（已使用 Champions 數值）"
        return Result(rolls, perHit, defender.curHp, defender.maxHp, bp, type, eff, crit, notes, verification)
    }

    private fun finalDamage(base: Int, i: Int, eff: Double, burned: Boolean, stab: Int, finalMod: Int): Int {
        var d = floor(base * (85.0 + i) / 100).toDouble()
        if (stab != 4096) d = d * stab / 4096
        d = floor(pokeRound(d) * eff)
        if (burned) d = floor(d / 2)
        return pokeRound(max(1.0, d * finalMod / 4096)) and 0xFFFF
    }

    private fun highestStat(side: Side): Stat =
        listOf(Stat.ATK, Stat.DEF, Stat.SPA, Stat.SPD, Stat.SPE).maxBy { side.stats[it] ?: 0 }

    private fun effSpeed(side: Side, field: Field): Int =
        SpeedEngine.effective(side.stats[Stat.SPE] ?: 1, side.ability, SpeedEngine.Conditions(stage = side.boost(Stat.SPE), paralysis = side.status == Status.PARALYSIS,
            choiceScarf = side.itemId() == "choicescarf", ironBall = side.itemId() == "ironball", weather = field.weather))

    private fun empty(defender: Side, move: Move, why: String?, notes: MutableList<String>, v: String = Verification.VERIFIED): Result {
        why?.let { notes += it }
        return Result(List(16) { 0 }, listOf(List(16) { 0 }), defender.curHp, defender.maxHp, move.basePower, move.type, 0.0, false, notes, v)
    }

    private fun fixed(defender: Side, move: Move, type: String, eff: Double, dmg: Int, notes: MutableList<String>): Result {
        notes += "固定傷害招式"
        return Result(List(16) { dmg }, listOf(List(16) { dmg }), defender.curHp, defender.maxHp, 0, type, eff, false, notes, Verification.VERIFIED)
    }

    // ---- KO probability (spec §12.3) -------------------------------------------------------

    /** Probability distribution of one use's total damage (each hit has 16 equally likely rolls). */
    private fun useDistribution(perHit: List<List<Int>>): Map<Int, Double> {
        var dist = mapOf(0 to 1.0)
        for (hit in perHit) {
            val next = HashMap<Int, Double>()
            for ((sum, p) in dist) for (r in hit) next.merge(sum + r, p / hit.size, Double::plus)
            dist = next
        }
        return dist
    }

    fun koChance(perHit: List<List<Int>>, hp: Int, maxUses: Int = 9): KO {
        if (perHit.all { h -> h.all { it == 0 } }) return KO(0, 0.0)
        val one = useDistribution(perHit)
        var dist = mapOf(0 to 1.0)
        for (n in 1..maxUses) {
            val next = HashMap<Int, Double>()
            for ((sum, p) in dist) for ((d, q) in one) next.merge(min(sum + d, hp), p * q, Double::plus)
            dist = next
            val chance = dist.filterKeys { it >= hp }.values.sum()
            if (chance > 0) return KO(n, chance)
        }
        return KO(maxUses + 1, 0.0)
    }

    // ---- Survival analysis (spec §12.3) -----------------------------------------------------

    data class Survival(val hpSp: Int, val defSp: Int, val defStat: Stat, val possible: Boolean, val maxDamage: Int, val hp: Int)

    /**
     * Least extra Stat Points in HP and the hit defence stat (keeping the defender's other SP and
     * nature) so that it survives the attacker's highest roll from full HP. [rebuild] recomputes
     * the defender with a new HP/defence SP.
     */
    fun survival(s: Snapshot, attacker: Side, defenderSlot: TeamSlot, move: Move, field: Field): Survival? {
        if (move.category == "Status") return null
        val defStat = if (move.category == "Physical" || move.id in setOf("psyshock", "psystrike", "secretsword")) Stat.DEF else Stat.SPD
        val form = TeamEngine.effectiveForm(s, defenderSlot) ?: return null
        val others = defenderSlot.sp.filterKeys { it != Stat.HP && it != defStat }.values.sum()
        val budget = StatsEngine.MAX_SP_TOTAL - others
        var best: Survival? = null
        for (h in 0..StatsEngine.MAX_SP_PER_STAT) for (d in 0..StatsEngine.MAX_SP_PER_STAT) {
            if (h + d > budget) continue
            if (best != null && h + d >= best.hpSp + best.defSp) continue
            val slot = defenderSlot.copy(sp = defenderSlot.sp + mapOf(Stat.HP to h, defStat to d))
            val stats = TeamEngine.finalStats(s, slot)
            val side = Side(form, stats, TeamEngine.effectiveAbility(s, slot), slot.item)
            val r = calculate(s, attacker, side, move, field)
            if (r.max < (stats[Stat.HP] ?: 0)) best = Survival(h, d, defStat, true, r.max, stats[Stat.HP] ?: 0)
        }
        return best ?: run {
            val slot = defenderSlot.copy(sp = defenderSlot.sp + mapOf(Stat.HP to 32, defStat to min(32, max(0, budget - 32))))
            val stats = TeamEngine.finalStats(s, slot)
            val r = calculate(s, attacker, Side(form, stats, TeamEngine.effectiveAbility(s, slot), slot.item), move, field)
            Survival(32, min(32, max(0, budget - 32)), defStat, false, r.max, stats[Stat.HP] ?: 0)
        }
    }

    /** Build a [Side] from a team slot (Champions Lv50 stats, Mega toggle applied). */
    fun sideFromSlot(s: Snapshot, slot: TeamSlot, status: Status = Status.NONE, boosts: Map<Stat, Int> = emptyMap(),
                     curHpPercent: Int = 100, abilityActive: Boolean = false): Side? {
        val form = TeamEngine.effectiveForm(s, slot) ?: return null
        return Side(form, TeamEngine.finalStats(s, slot), TeamEngine.effectiveAbility(s, slot), slot.item?.let { s.itemById[it]?.name ?: it },
            status, boosts, curHpPercent, 50, abilityActive)
    }
}
