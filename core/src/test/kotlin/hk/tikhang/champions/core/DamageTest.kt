package hk.tikhang.champions.core

import hk.tikhang.champions.core.DamageEngine.Field
import hk.tikhang.champions.core.DamageEngine.GameType
import hk.tikhang.champions.core.DamageEngine.Side
import hk.tikhang.champions.core.DamageEngine.Status
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verification (spec §12.7). Cases A–I reproduce the Smogon damage calculator's own Gen 9 test
 * results (calc/src/test/calc.test.ts): Lv100, 31 IVs, 0 EVs, neutral nature, singles.
 * Cases from J use Champions Lv50 stats (Stat Points) and were cross-checked against an
 * independent re-implementation of the calculator's rounding steps.
 */
class DamageTest {
    private val s: Snapshot by lazy { Snapshot.parse(File(System.getProperty("snapshot.path")).readText()) }
    private val singles = Field(gameType = GameType.SINGLES)

    private fun mon(name: String, types: List<String>, base: Stats, abilities: List<String> = listOf("No Ability")) =
        Pokemon(id = toId(name), name = name, num = 1, types = types, baseStats = base, abilities = abilities, baseSpecies = name)

    /** Lv100, 31 IV, 0 EV, neutral nature stats (main-series formula), as in the Smogon tests. */
    private fun lv100(p: Pokemon) = mapOf(
        Stat.HP to 2 * p.baseStats.hp + 141, Stat.ATK to 2 * p.baseStats.atk + 36, Stat.DEF to 2 * p.baseStats.def + 36,
        Stat.SPA to 2 * p.baseStats.spa + 36, Stat.SPD to 2 * p.baseStats.spd + 36, Stat.SPE to 2 * p.baseStats.spe + 36,
    )

    private fun side(p: Pokemon, ability: String? = null, item: String? = null, status: Status = Status.NONE,
                     boosts: Map<Stat, Int> = emptyMap(), hp: Int = 100) =
        Side(p, lv100(p), ability, item, status, boosts, hp, level = 100)

    private fun move(id: String, type: String, cat: String, bp: Int, target: String = "normal", flags: List<String> = emptyList(), priority: Int = 0) =
        Move(id = id, name = id, type = type, category = cat, basePower = bp, target = target, flags = flags, priority = priority)

    private fun range(r: DamageEngine.Result) = listOf(r.min, r.max)

    private val mew = mon("Mew", listOf("Psychic"), Stats(100, 100, 100, 100, 100, 100))
    private val vulpix = mon("Vulpix", listOf("Fire"), Stats(38, 41, 40, 50, 65, 65))
    private val abomasnow = mon("Abomasnow", listOf("Grass", "Ice"), Stats(90, 92, 75, 92, 85, 60))
    private val dragonite = mon("Dragonite", listOf("Dragon", "Flying"), Stats(91, 134, 95, 100, 100, 80))
    private val explosion = move("explosion", "Normal", "Physical", 250, "allAdjacent")

    @Test fun a_critIgnoresReflectAndBurnHalves() {
        val r = DamageEngine.calculate(s, side(mew, status = Status.BURN), side(vulpix), explosion, singles.copy(reflect = true, critical = true))
        assertEquals(listOf(273, 321), range(r))
    }

    @Test fun b_boostsReflectBurn() {
        val r = DamageEngine.calculate(s, side(mew, status = Status.BURN, boosts = mapOf(Stat.ATK to 2)),
            side(vulpix, boosts = mapOf(Stat.DEF to 2)), explosion, singles.copy(reflect = true))
        assertEquals(listOf(91, 107), range(r))
    }

    @Test fun c_flyingPress() {
        val hawlucha = mon("Hawlucha", listOf("Fighting", "Flying"), Stats(78, 92, 75, 74, 63, 118))
        val cacturne = mon("Cacturne", listOf("Grass", "Dark"), Stats(70, 115, 60, 115, 60, 55))
        val r = DamageEngine.calculate(s, side(hawlucha), side(cacturne), move("flyingpress", "Fighting", "Physical", 100, flags = listOf("contact")), singles)
        assertEquals(listOf(612, 720), range(r))
        assertEquals(4.0, r.effectiveness)
    }

    @Test fun d_thousandArrowsHitsFlying() {
        val zygarde = mon("Zygarde", listOf("Dragon", "Ground"), Stats(108, 100, 121, 81, 95, 95))
        val swellow = mon("Swellow", listOf("Normal", "Flying"), Stats(60, 85, 60, 75, 50, 125))
        val r = DamageEngine.calculate(s, side(zygarde), side(swellow), move("thousandarrows", "Ground", "Physical", 90, "allAdjacentFoes"), singles)
        assertEquals(listOf(147, 174), range(r))
    }

    @Test fun e_multiscaleOnlyAtFullHp() {
        val iceShard = move("iceshard", "Ice", "Physical", 40, priority = 1)
        val notFull = DamageEngine.calculate(s, side(abomasnow), side(dragonite, ability = "Multiscale", hp = 21), iceShard, singles)
        assertEquals(listOf(168, 204), range(notFull))
        val full = DamageEngine.calculate(s, side(abomasnow), side(dragonite, ability = "Multiscale"), iceShard, singles)
        assertEquals(listOf(84, 102), range(full))
        assertEquals(DamageEngine.KO(4, 1.0), full.ko.let { it.copy(chance = Math.round(it.chance * 1000) / 1000.0) })
    }

    @Test fun f_shadowShieldBlizzard() {
        val r = DamageEngine.calculate(s, side(abomasnow), side(dragonite, ability = "Shadow Shield"),
            move("blizzard", "Ice", "Special", 110, "allAdjacentFoes"), singles)
        assertEquals(listOf(222, 264), range(r))
    }

    @Test fun g_ironBallGroundsFlying_koChance() {
        val vibrava = mon("Vibrava", listOf("Ground", "Dragon"), Stats(50, 70, 50, 50, 50, 70))
        val zapdos = mon("Zapdos", listOf("Electric", "Flying"), Stats(90, 90, 85, 125, 90, 100))
        val r = DamageEngine.calculate(s, side(vibrava), side(zapdos, item = "Iron Ball"), move("earthquake", "Ground", "Physical", 100, "allAdjacent"), singles)
        assertEquals(listOf(93, 109), range(r))
        assertEquals(3, r.ko.hits)
        assertTrue(abs(r.ko.chance - 0.012) < 0.0005, "chance ${r.ko.chance}")   // "1.2% chance to 3HKO"
    }

    @Test fun h_ironBallGroundsLevitate() {
        val poliwrath = mon("Poliwrath", listOf("Water", "Fighting"), Stats(90, 95, 95, 70, 90, 70))
        val mismagius = mon("Mismagius", listOf("Ghost"), Stats(60, 60, 60, 105, 105, 105))
        val r = DamageEngine.calculate(s, side(poliwrath), side(mismagius, ability = "Levitate", item = "Iron Ball"), move("mudshot", "Ground", "Special", 55), singles)
        assertEquals(listOf(29, 35), range(r))
        assertEquals(8, r.ko.hits)                                                  // "possible 8HKO"
        val noBall = DamageEngine.calculate(s, side(poliwrath), side(mismagius, ability = "Levitate"), move("mudshot", "Ground", "Special", 55), singles)
        assertEquals(0, noBall.max)
    }

    @Test fun i_koChanceMatchesCalc() {
        // Rolls of the calc's Mew Sludge Bomb vs Ring Target Skarmory (271 HP): "94.6% chance to 3HKO".
        val rolls = listOf(87, 88, 89, 90, 91, 92, 93, 94, 95, 96, 97, 98, 99, 100, 101, 103)
        val ko = DamageEngine.koChance(listOf(rolls), 271)
        assertEquals(3, ko.hits)
        assertTrue(abs(ko.chance - 0.946) < 0.0005, "chance ${ko.chance}")
    }

    // ---- Champions Lv50 (Stat Points) --------------------------------------------------------

    @Test fun j_championsDoublesSpread() {
        val chomp = DamageEngine.sideFromSlot(s, TeamSlot("garchomp", nature = "jolly", sp = mapOf(Stat.ATK to 32, Stat.SPE to 32)))!!
        val inc = DamageEngine.sideFromSlot(s, TeamSlot("incineroar", ability = "Intimidate", sp = mapOf(Stat.HP to 32)))!!
        val eq = s.moveById.getValue("earthquake")
        val spread = DamageEngine.calculate(s, chomp, inc, eq, Field(gameType = GameType.DOUBLES, spreadHit = true))
        assertEquals(listOf(138, 140, 140, 144, 144, 146, 150, 150, 152, 152, 156, 156, 158, 158, 162, 164), spread.rolls)
        assertEquals(DamageEngine.KO(2, 1.0), spread.ko.let { it.copy(chance = Math.round(it.chance * 1000) / 1000.0) })
        val single = DamageEngine.calculate(s, chomp, inc, eq, Field(gameType = GameType.DOUBLES, spreadHit = false))
        assertEquals(listOf(186, 222), range(single))
    }

    @Test fun k_megaToggleChangesResult() {
        val base = TeamSlot("charizard", ability = "Blaze", nature = "modest", sp = mapOf(Stat.SPA to 32))
        val mega = TeamEngine.toggleMega(s, base, "charizardmegay")
        val target = DamageEngine.sideFromSlot(s, TeamSlot("garchomp", sp = mapOf(Stat.HP to 32)))!!
        val flamethrower = s.moveById.getValue("flamethrower")
        val r1 = DamageEngine.calculate(s, DamageEngine.sideFromSlot(s, base)!!, target, flamethrower, Field())
        val r2 = DamageEngine.calculate(s, DamageEngine.sideFromSlot(s, mega)!!, target, flamethrower, Field())
        assertTrue(r2.min > r1.max, "Mega Charizard Y (Drought, higher SpA) must hit harder: ${range(r1)} vs ${range(r2)}")
    }

    @Test fun l_immunitiesAndStatus() {
        val chomp = DamageEngine.sideFromSlot(s, TeamSlot("garchomp"))!!
        val rotom = DamageEngine.sideFromSlot(s, TeamSlot("rotomwash", ability = "Levitate"))!!
        assertEquals(0, DamageEngine.calculate(s, chomp, rotom, s.moveById.getValue("earthquake"), Field()).max)
        assertEquals(DamageEngine.KO(0, 0.0), DamageEngine.calculate(s, chomp, rotom, s.moveById.getValue("earthquake"), Field()).ko)
        val burned = chomp.copy(status = Status.BURN)
        val normal = DamageEngine.calculate(s, chomp, rotom, s.moveById.getValue("dragonclaw"), Field())
        val halved = DamageEngine.calculate(s, burned, rotom, s.moveById.getValue("dragonclaw"), Field())
        assertTrue(halved.max <= normal.max / 2 + 1)
    }

    @Test fun m_multiHitAndSurvival() {
        val attacker = DamageEngine.sideFromSlot(s, TeamSlot("garchomp", nature = "adamant", sp = mapOf(Stat.ATK to 32)))!!
        val eq = s.moveById.getValue("earthquake")
        val defSlot = TeamSlot("incineroar", ability = "Intimidate")
        val sv = DamageEngine.survival(s, attacker, defSlot, eq, Field(spreadHit = false))!!
        // Whatever it needs, the reported spread must really survive the highest roll.
        if (sv.possible) assertTrue(sv.maxDamage < sv.hp)
        assertTrue(sv.hpSp in 0..32 && sv.defSp in 0..32)
    }
}
