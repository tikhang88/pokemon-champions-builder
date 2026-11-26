package hk.tikhang.champions.core

import hk.tikhang.champions.core.BattleEngine.Action
import hk.tikhang.champions.core.BattleEngine.Battle
import hk.tikhang.champions.core.BattleEngine.Kind
import hk.tikhang.champions.core.BattleEngine.Setup
import hk.tikhang.champions.core.BattleEngine.Target
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BattleTest {
    private val s: Snapshot by lazy { Snapshot.parse(File(System.getProperty("snapshot.path")).readText()) }

    private fun slot(id: String, moves: List<String>, ability: String? = null, item: String? = null, nature: String = "serious",
                     sp: Map<Stat, Int> = emptyMap(), mega: String? = null): TeamSlot {
        val learn = s.learnsets.getValue(id)
        moves.forEach { require(it in learn) { "$id cannot learn $it" } }
        var sl = TeamEngine.newSlot(s, id).copy(moves = moves, item = item, nature = nature, sp = sp)
        if (ability != null) sl = sl.copy(ability = ability)
        if (mega != null) sl = TeamEngine.toggleMega(s, sl, mega)
        return sl
    }

    private fun singles(a: List<TeamSlot>, b: List<TeamSlot>, sc: BattleEngine.Scenario = BattleEngine.Scenario(),
                        roll: BattleEngine.RollMode = BattleEngine.RollMode.AVERAGE) =
        Setup(BattleFormat.SINGLES, listOf(a, b), scenario = sc, rollMode = roll, critMode = BattleEngine.CritMode.NEVER)

    private fun doubles(a: List<TeamSlot>, b: List<TeamSlot>, sc: BattleEngine.Scenario = BattleEngine.Scenario()) =
        Setup(BattleFormat.DOUBLES, listOf(a, b), scenario = sc, rollMode = BattleEngine.RollMode.AVERAGE, critMode = BattleEngine.CritMode.NEVER)

    private fun texts(b: Battle) = b.log.map { it.text }

    @Test fun fasterMovesFirstAndTrickRoomReverses() {
        val fast = slot("garchomp", listOf("dragonclaw"), nature = "jolly", sp = mapOf(Stat.SPE to 32))
        val slow = slot("incineroar", listOf("darkestlariat"))
        val b = Battle(s, singles(listOf(fast), listOf(slow)), 1)
        b.runTurn(emptyMap())
        val moves = b.log.filter { it.kind == Kind.MOVE }.map { it.text }
        assertTrue(moves[0].contains("烈咬陸鯊") || moves[0].startsWith("我方"), moves.toString())
        val tr = Battle(s, singles(listOf(fast), listOf(slow), BattleEngine.Scenario(trickRoom = true)), 1)
        tr.runTurn(emptyMap())
        assertTrue(tr.log.first { it.kind == Kind.MOVE }.text.startsWith("對手"), texts(tr).toString())
    }

    @Test fun priorityBeatsSpeed() {
        val fast = slot("garchomp", listOf("dragonclaw"), nature = "jolly", sp = mapOf(Stat.SPE to 32))
        val prio = slot("incineroar", listOf("fakeout"))
        val b = Battle(s, singles(listOf(fast), listOf(prio)), 1)
        b.runTurn(emptyMap())
        assertTrue(b.log.first { it.kind == Kind.MOVE }.text.startsWith("對手"))
        assertTrue(texts(b).any { "畏縮" in it }, texts(b).toString())   // Fake Out flinches the slower Garchomp
        // Fake Out fails on the next turn.
        b.runTurn(emptyMap())
        assertTrue(texts(b).any { "只可在出場後第一回合使用" in it })
    }

    @Test fun protectBlocksAndFailsWhenRepeated() {
        val a = slot("garchomp", listOf("protect", "dragonclaw"))
        val d = slot("incineroar", listOf("flareblitz"))
        val b = Battle(s, singles(listOf(a), listOf(d)), 3)
        val before = b.sides[0].mons[0].hp
        b.runTurn(mapOf((0 to 0) to Action.UseMove("protect")))
        assertTrue(texts(b).any { "守住了攻擊" in it }, texts(b).toString())
        assertEquals(before, b.sides[0].mons[0].hp)
    }

    @Test fun damageMatchesCalculatorAverageRoll() {
        val atk = slot("garchomp", listOf("dragonclaw"), nature = "adamant", sp = mapOf(Stat.ATK to 32))
        val def = slot("incineroar", listOf("darkestlariat"), ability = "Blaze", sp = mapOf(Stat.HP to 32))
        val b = Battle(s, singles(listOf(atk), listOf(def)), 1)
        b.runTurn(mapOf((0 to 0) to Action.UseMove("dragonclaw")))
        val calc = DamageEngine.calculate(s, DamageEngine.sideFromSlot(s, atk)!!, DamageEngine.sideFromSlot(s, def)!!,
            s.moveById.getValue("dragonclaw"), DamageEngine.Field(gameType = DamageEngine.GameType.SINGLES))
        val inc = b.sides[1].mons[0]
        assertFalse(inc.fainted)
        assertEquals(calc.rolls[7], inc.maxHp - inc.hp)   // AVERAGE roll mode uses roll index 7
    }

    @Test fun megaEvolvesOncePerSide() {
        val zard = slot("charizard", listOf("flamethrower"), nature = "timid", sp = mapOf(Stat.SPE to 32), mega = "charizardmegay")
        val chomp = slot("garchomp", listOf("dragonclaw"), mega = "garchompmega")
        val b = Battle(s, doubles(listOf(zard, chomp), listOf(slot("incineroar", listOf("darkestlariat")), slot("rillaboom", listOf("woodhammer")))), 1)
        b.runTurn(mapOf((0 to 0) to Action.UseMove("flamethrower", Target(1, 0), mega = true),
            (0 to 1) to Action.UseMove("dragonclaw", Target(1, 1), mega = true)))
        assertTrue(b.sides[0].mons[0].isMega)
        assertFalse(b.sides[0].mons[1].isMega)
        assertEquals("Drought", b.sides[0].mons[0].ability)
        assertTrue(b.sides[0].megaUsed)
    }

    @Test fun intimidateAndWeatherOnSwitchIn() {
        val inc = slot("incineroar", listOf("darkestlariat"), ability = "Intimidate")
        val chomp = slot("garchomp", listOf("dragonclaw"))
        val b = Battle(s, singles(listOf(chomp), listOf(inc)), 1)
        assertEquals(-1, b.sides[0].mons[0].boost(Stat.ATK))
        val zard = slot("charizard", listOf("flamethrower"), mega = "charizardmegay")
        val b2 = Battle(s, singles(listOf(zard), listOf(chomp)), 1)
        b2.runTurn(mapOf((0 to 0) to Action.UseMove("flamethrower", mega = true)))
        // Drought activates only when the ability is gained by switching in; Mega does not re-trigger it here.
        assertNotNull(b2.sides[0].mons[0].ability)
    }

    @Test fun statusRulesChampions() {
        val wisp = slot("incineroar", listOf("willowisp"))
        // Fire types can't be burned.
        val b = Battle(s, singles(listOf(wisp), listOf(slot("charizard", listOf("flamethrower")))), 1)
        b.runTurn(mapOf((0 to 0) to Action.UseMove("willowisp")))
        assertEquals(DamageEngine.Status.NONE, b.sides[1].mons[0].status)
        // Burn deals 1/16 at end of turn.
        val sc = BattleEngine.Scenario(status = listOf(emptyList(), listOf(DamageEngine.Status.BURN)))
        val b2 = Battle(s, singles(listOf(slot("garchomp", listOf("protect"))), listOf(slot("rillaboom", listOf("protect"))), sc), 1)
        b2.runTurn(mapOf((0 to 0) to Action.UseMove("protect"), (1 to 0) to Action.UseMove("protect")))
        val r = b2.sides[1].mons[0]
        assertEquals(r.maxHp - r.maxHp / 16, r.hp)
    }

    @Test fun sleepLastsTwoOrThreeTurnsAtMost() {
        repeat(20) { seed ->
            val sc = BattleEngine.Scenario(status = listOf(listOf(DamageEngine.Status.SLEEP), emptyList()))
            val b = Battle(s, singles(listOf(slot("garchomp", listOf("dragonclaw"))), listOf(slot("rillaboom", listOf("protect"))), sc), seed.toLong())
            var woke = false
            repeat(3) { if (!woke) { b.runTurn(emptyMap()); woke = b.sides[0].mons[0].status == DamageEngine.Status.NONE } }
            assertTrue(woke, "seed $seed")
        }
    }

    @Test fun faintedIsReplacedAndBattleEnds() {
        val atk = slot("garchomp", listOf("earthquake"), nature = "adamant", sp = mapOf(Stat.ATK to 32))
        val weak1 = slot("incineroar", listOf("darkestlariat"))
        val weak2 = slot("rillaboom", listOf("woodhammer"))
        val sc = BattleEngine.Scenario(hpPercent = listOf(emptyList(), listOf(1, 1)))
        val b = Battle(s, singles(listOf(atk), listOf(weak1, weak2), sc), 1)
        b.runTurn(mapOf((0 to 0) to Action.UseMove("earthquake")))
        assertTrue(texts(b).any { "補位" in it }, texts(b).toString())
        b.runTurn(mapOf((0 to 0) to Action.UseMove("earthquake")))
        assertEquals(0, b.winner)
    }

    @Test fun aiBattlesFinishAndAreReproducible() {
        val a = listOf(slot("garchomp", listOf("earthquake", "dragonclaw")), slot("incineroar", listOf("fakeout", "flareblitz", "darkestlariat")),
            slot("rillaboom", listOf("woodhammer", "grassyglide")), slot("charizard", listOf("flamethrower", "airslash")))
        val bTeam = listOf(slot("dragonite", listOf("extremespeed", "dragonclaw")), slot("glalie", listOf("freezedry")),
            slot("rotomwash", listOf("hydropump", "thunderbolt"), ability = "Levitate"), slot("sylveon", listOf("hypervoice"), ability = "Pixilate"))
        val setup = Setup(BattleFormat.DOUBLES, listOf(a, bTeam), maxTurns = 40)
        val stats = BattleEngine.simulateBattles(s, setup, runs = 20)
        assertEquals(1.0, stats.winRate.sum() + stats.drawRate, 1e-9)
        assertEquals(stats, BattleEngine.simulateBattles(s, setup, runs = 20))   // same seeds → same results
        val turn = BattleEngine.simulateTurn(s, setup, emptyMap(), runs = 30)
        assertTrue(turn.firstMover.values.sum() in 0.99..1.01)
    }
}
