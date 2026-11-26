package hk.tikhang.champions.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpeedMatchupTest {
    private val s: Snapshot by lazy { Snapshot.parse(File(System.getProperty("snapshot.path")).readText()) }
    private val te by lazy { TypeEngine(s.typeChart, s.types) }

    private fun slot(id: String, moves: List<String>, sp: Map<Stat, Int> = emptyMap(), nature: String = "serious") =
        TeamEngine.newSlot(s, id).copy(moves = moves.filter { it in s.learnsets.getValue(id) }, sp = sp, nature = nature)

    private fun team(vararg slots: TeamSlot, opponent: Boolean = false) =
        Team("t", "M-C", BattleFormat.DOUBLES, slots.toList() + List(TEAM_SIZE - slots.size) { null }, opponent = opponent)

    @Test fun speedModifiers() {
        val c = SpeedEngine.Conditions()
        assertEquals(100, SpeedEngine.effective(100, null, c))
        assertEquals(150, SpeedEngine.effective(100, null, c.copy(stage = 1)))
        assertEquals(50, SpeedEngine.effective(100, null, c.copy(stage = -2)))
        assertEquals(200, SpeedEngine.effective(100, null, c.copy(tailwind = true)))
        assertEquals(150, SpeedEngine.effective(100, null, c.copy(choiceScarf = true)))
        assertEquals(50, SpeedEngine.effective(100, null, c.copy(paralysis = true)))
        assertEquals(200, SpeedEngine.effective(100, "Swift Swim", c.copy(weather = SpeedEngine.Weather.RAIN)))
        assertEquals(100, SpeedEngine.effective(100, "Swift Swim", c.copy(weather = SpeedEngine.Weather.SUN)))
        assertEquals(150, SpeedEngine.effective(100, "Quick Feet", c.copy(paralysis = true, abilityActive = true)))
    }

    @Test fun benchmarksUseChampionsFormula() {
        // Garchomp base 102: 32 SP + boosting nature = (102 + 32 + 20) × 1.1 = 169.
        assertEquals(169, SpeedEngine.benchmark(102, SpeedEngine.Spread.MAX_PLUS))
        assertEquals(154, SpeedEngine.benchmark(102, SpeedEngine.Spread.MAX))
        assertEquals(122, SpeedEngine.benchmark(102, SpeedEngine.Spread.NEUTRAL))
        assertEquals(109, SpeedEngine.benchmark(102, SpeedEngine.Spread.MIN))
    }

    @Test fun tierListAndTrickRoom() {
        val members = TeamAnalysisEngine.members(s, team(slot("garchomp", listOf("earthquake"), mapOf(Stat.SPE to 32), "jolly")))
        val pool = s.regulationById.getValue("M-C").allowedPokemon!!.map { s.pokemonById.getValue(it) }
        val list = SpeedEngine.tierList(pool, SpeedEngine.Spread.MAX_PLUS, members, SpeedEngine.Conditions())
        val mine = list.first { it.teamIndex == 0 }
        assertEquals(169, mine.speed)
        assertTrue(list.zipWithNext().all { (a, b) -> a.speed >= b.speed })
        val tr = SpeedEngine.tierList(pool, SpeedEngine.Spread.MAX_PLUS, members, SpeedEngine.Conditions(trickRoom = true))
        assertTrue(tr.zipWithNext().all { (a, b) -> a.speed <= b.speed })
        val pct = SpeedEngine.percentile(pool, SpeedEngine.Spread.MAX_PLUS, 169)
        assertTrue(pct in 1.0..99.0)
    }

    @Test fun matchupExplainsThreat() {
        val mine = TeamAnalysisEngine.members(s, team(
            slot("garchomp", listOf("earthquake", "dragonclaw"), mapOf(Stat.SPE to 32), "jolly"),
            slot("dragonite", listOf("extremespeed", "dragonclaw")),
            slot("rillaboom", listOf("woodhammer")),
        ))
        // An Ice attacker threatens all three (Garchomp/Dragonite 4×, Rillaboom 2×).
        val glalie = MatchupEngine.quickOpponent(s, "glalie", 0)!!
        val r = MatchupEngine.report(s, te, mine, listOf(glalie))
        val res = r.results.single()
        assertEquals(3, res.superEffectiveOnMe)
        assertTrue(res.explanation.contains("3/3"))
        assertTrue(res.threat != MatchupEngine.Threat.LOW)
        // Rotom-Wash's Levitate makes Earthquake useless: Garchomp's best is Dragon Claw (neutral).
        val rotom = MatchupEngine.quickOpponent(s, "rotomwash", 1)!!
        val p = MatchupEngine.analyseOpponent(te, mine, rotom.first, rotom.second).pairings.first { it.mine.form.id == "garchomp" }
        assertEquals(1.0, p.myBest)
        assertEquals("dragonclaw", p.myBestMove?.id)
    }

    @Test fun opponentTeamUsesConfiguredMoves() {
        val opp = team(slot("incineroar", listOf("flareblitz", "fakeout")), opponent = true)
        val mine = TeamAnalysisEngine.members(s, team(slot("rillaboom", listOf("woodhammer"))))
        val r = MatchupEngine.report(s, te, mine, MatchupEngine.opponentsFromTeam(s, opp))
        val res = r.results.single()
        assertTrue(res.movesKnown)
        assertEquals(2.0, res.pairings.single().theirBest)   // Flare Blitz vs Grass
        assertTrue(res.flags.any { "先制" in it })           // Fake Out
    }
}
