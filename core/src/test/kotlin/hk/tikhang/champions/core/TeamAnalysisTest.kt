package hk.tikhang.champions.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TeamAnalysisTest {
    private val s: Snapshot by lazy { Snapshot.parse(File(System.getProperty("snapshot.path")).readText()) }
    private val te by lazy { TypeEngine(s.typeChart, s.types) }
    private val E = TeamAnalysisEngine

    private fun slot(id: String, moves: List<String>, ability: String? = null) =
        TeamEngine.newSlot(s, id).copy(moves = moves.filter { it in s.learnsets.getValue(id) }, ability = ability ?: s.pokemonById.getValue(id).abilities.first())

    private fun team(vararg slots: TeamSlot) = Team("t", "M-C", BattleFormat.DOUBLES, slots.toList() + List(TEAM_SIZE - slots.size) { null })

    @Test fun levitateMakesGroundImmune() {
        val a = E.analyse(s, te, team(slot("rotomwash", listOf("hydropump"), "Levitate")))
        val ground = a.defense.first { it.type == "Ground" }
        assertEquals(0.0, ground.perMember[0])
        assertEquals(1, ground.immune)
    }

    @Test fun pixilateChangesHyperVoiceToFairy() {
        val sylveon = s.moveById.getValue("hypervoice")
        assertEquals("Fairy", E.moveType(sylveon, "Pixilate"))
        assertEquals(2.0, E.moveMultiplier(te, sylveon, "Pixilate", listOf("Dragon")))
        assertEquals(1.0, E.moveMultiplier(te, sylveon, null, listOf("Dragon")))
    }

    @Test fun freezeDryHitsWater() {
        val fd = s.moveById.getValue("freezedry")
        assertEquals(2.0, E.moveMultiplier(te, fd, null, listOf("Water")))
        assertEquals(4.0, E.moveMultiplier(te, fd, null, listOf("Water", "Ground")))
    }

    @Test fun sharedWeaknessAndGap() {
        // Garchomp, Dragonite and Glalie are all weak to Ice... except Glalie; use three Ice-weak members.
        val t = team(
            slot("garchomp", listOf("earthquake", "dragonclaw")),
            slot("dragonite", listOf("extremespeed", "dragonclaw")),
            slot("rillaboom", listOf("woodhammer", "grassyglide")),
        )
        val a = E.analyse(s, te, t)
        val ice = a.defense.first { it.type == "Ice" }
        assertEquals(3, ice.weak)
        assertTrue(ice.isShared)
        assertTrue(a.report.weaknesses.any { "冰" in it })
        assertTrue(a.report.weaknesses.any { "4 倍" in it }) // Garchomp and Dragonite take 4× from Ice
        // Suggestions explain problem, reason, benefit, cost and new weaknesses.
        val sug = a.report.suggestions.first()
        assertTrue(sug.problem.isNotBlank() && sug.reason.isNotBlank() && sug.benefit.isNotBlank() && sug.cost.isNotBlank() && sug.newWeaknesses.isNotBlank())
        assertTrue(a.report.suggestions.any { it.title.startsWith("不更換成員") })
    }

    @Test fun megaToggleChangesDefence() {
        val chomp = TeamEngine.toggleMega(s, slot("garchomp", listOf("earthquake")), "garchompmegaz")
        val withMega = E.analyse(s, te, team(chomp), useMega = true)
        val noMega = E.analyse(s, te, team(chomp), useMega = false)
        // Mega Garchomp Z is pure Dragon with Levitate: Electric 0.5×, Ground immune (ability), Ice 2×.
        assertEquals(0.5, withMega.defense.first { it.type == "Electric" }.perMember[0])
        assertEquals(0.0, withMega.defense.first { it.type == "Ground" }.perMember[0])
        assertEquals(2.0, withMega.defense.first { it.type == "Ice" }.perMember[0])
        assertEquals(0.0, noMega.defense.first { it.type == "Electric" }.perMember[0])
        assertEquals(4.0, noMega.defense.first { it.type == "Ice" }.perMember[0])
    }

    @Test fun offensiveCoverageUsesMoveset() {
        val a = E.analyse(s, te, team(slot("garchomp", listOf("earthquake", "dragonclaw"))))
        assertTrue(a.offense.first { it.type == "Fire" }.superEffective.isNotEmpty())   // Earthquake
        assertTrue(a.offense.first { it.type == "Flying" }.isGap)                       // Ground can't hit Flying
        assertTrue(a.walls.isNotEmpty())                                                // e.g. Steel/Flying types
    }

    @Test fun synergyDetection() {
        val a = E.analyse(s, te, team(
            slot("incineroar", listOf("fakeout", "partingshot", "flareblitz", "knockoff"), "Intimidate"),
            slot("rillaboom", listOf("grassyglide", "woodhammer", "uturn"), "Grassy Surge"),
        ))
        val kinds = a.synergy.filter { it.providers.isNotEmpty() }.map { it.kind }.toSet()
        assertTrue(SynergyKindsExpected.all { it in kinds }, kinds.toString())
    }

    private val SynergyKindsExpected = setOf(
        TeamAnalysisEngine.SynergyKind.SUPPORT, TeamAnalysisEngine.SynergyKind.PIVOT,
        TeamAnalysisEngine.SynergyKind.PRIORITY, TeamAnalysisEngine.SynergyKind.TERRAIN,
    )

    @Test fun emptyTeam() {
        val a = E.analyse(s, te, team())
        assertTrue(a.members.isEmpty())
        assertFalse(a.defense.any { it.weak > 0 })
    }
}
