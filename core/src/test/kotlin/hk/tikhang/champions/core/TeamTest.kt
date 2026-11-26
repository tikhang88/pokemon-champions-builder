package hk.tikhang.champions.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TeamTest {
    private val s: Snapshot by lazy { Snapshot.parse(File(System.getProperty("snapshot.path")).readText()) }

    private fun slot(id: String, moves: List<String>, item: String? = null, ability: String? = null, nature: String = "serious") =
        TeamEngine.newSlot(s, id).copy(moves = moves, item = item, ability = ability ?: s.pokemonById.getValue(id).abilities.first(), nature = nature)

    private fun legalMoves(id: String, n: Int = 4) = s.learnsets.getValue(id).take(n)

    private fun team(vararg slots: TeamSlot?, reg: String = "M-C") =
        Team("t", reg, BattleFormat.DOUBLES, slots.toList() + List(TEAM_SIZE - slots.size) { null })

    private fun fourLegal(): List<TeamSlot> = listOf("garchomp", "incineroar", "rillaboom", "sneasler")
        .filter { it in s.pokemonById }.take(4).map { slot(it, legalMoves(it)) }

    @Test fun legalTeam() {
        val members = fourLegal()
        assertEquals(4, members.size, "test species missing from data")
        val r = LegalityEngine.check(s, team(*members.toTypedArray()))
        assertEquals(Legality.LEGAL, r.result, r.issues.joinToString("\n"))
    }

    @Test fun tooFewIsIncomplete() {
        val r = LegalityEngine.check(s, team(slot("garchomp", legalMoves("garchomp"))))
        assertEquals(Legality.INCOMPLETE, r.result)
    }

    @Test fun speciesAndItemClause() {
        val a = slot("garchomp", legalMoves("garchomp"), item = "choicescarf")
        val r = LegalityEngine.check(s, team(*(fourLegal() + a).toTypedArray()))
        assertEquals(Legality.ILLEGAL, r.result)
        assertTrue(r.issues.any { it.message.startsWith("Species Clause") })
        val b = fourLegal().mapIndexed { i, sl -> if (i < 2) sl.copy(item = "choicescarf") else sl }
        assertTrue(LegalityEngine.check(s, team(*b.toTypedArray())).issues.any { it.message.startsWith("Item Clause") })
    }

    @Test fun illegalMoveAbilityAndSp() {
        val bad = slot("garchomp", listOf("earthquake", "spore"), ability = "Levitate")
            .copy(sp = mapOf(Stat.HP to 32, Stat.ATK to 32, Stat.SPE to 32))
        val msgs = LegalityEngine.check(s, team(*(fourLegal().drop(1) + bad).toTypedArray())).issues.map { it.message }
        assertTrue(msgs.any { "不能學會" in it }, msgs.toString())
        assertTrue(msgs.any { "不能擁有特性" in it }, msgs.toString())
        assertTrue(msgs.any { "SP 總數" in it }, msgs.toString())
    }

    @Test fun megaToggleSyncsStoneTypesAndStats() {
        var sl = slot("garchomp", legalMoves("garchomp"))
        sl = TeamEngine.toggleMega(s, sl, "garchompmegaz")
        assertEquals("garchompitez", sl.item)
        val form = TeamEngine.effectiveForm(s, sl)!!
        assertEquals(listOf("Dragon"), form.types)
        assertEquals("Levitate", TeamEngine.effectiveAbility(s, sl))
        // Spe base 151, 32 SP, neutral: 151 + 32 + 20 = 203
        assertEquals(203, TeamEngine.finalStats(s, sl.copy(sp = mapOf(Stat.SPE to 32)))[Stat.SPE])
        val r = LegalityEngine.check(s, team(*(fourLegal().drop(1) + sl).toTypedArray()))
        assertEquals(Legality.LEGAL, r.result, r.issues.joinToString("\n"))
        // Turning Mega off removes the stone.
        assertNull(TeamEngine.toggleMega(s, sl, null).item)
    }

    @Test fun megaWithoutStoneIsIllegal() {
        val sl = slot("garchomp", legalMoves("garchomp")).copy(megaFormId = "garchompmegaz", item = "choicescarf")
        val r = LegalityEngine.check(s, team(*(fourLegal().drop(1) + sl).toTypedArray()))
        assertTrue(r.issues.any { "需要持有" in it.message })
    }

    @Test fun twoMegasWarns() {
        val a = TeamEngine.toggleMega(s, slot("garchomp", legalMoves("garchomp")), "garchompmega")
        val b = TeamEngine.toggleMega(s, slot("charizard", legalMoves("charizard")), "charizardmegay")
        val r = LegalityEngine.check(s, team(*(fourLegal().drop(2).filter { it.speciesId != "garchomp" } + a + b).toTypedArray()))
        assertTrue(r.issues.any { it.level == LegalityIssue.Level.WARNING && "Mega Evolution 一次" in it.message })
    }

    @Test fun provisionalPokemonIsPendingInMd() {
        val heatran = slot("heatran", legalMoves("heatran"))
        val r = LegalityEngine.check(s, team(*(fourLegal().drop(1) + heatran).toTypedArray(), reg = "M-D"))
        assertEquals(Legality.PENDING, r.result, r.issues.joinToString("\n"))
        // Not allowed in M-C.
        assertEquals(Legality.ILLEGAL, LegalityEngine.check(s, team(*(fourLegal().drop(1) + heatran).toTypedArray())).result)
    }

    @Test fun megaOptionsPerForme() {
        assertEquals(setOf("charizardmegax", "charizardmegay"), s.megaOptions(s.pokemonById.getValue("charizard")).map { it.id }.toSet())
        assertEquals(listOf("tatsugiridroopymega"), s.megaOptions(s.pokemonById.getValue("tatsugiridroopy")).map { it.id })
        assertEquals(listOf("floettemega"), s.megaOptions(s.pokemonById.getValue("floetteeternal")).map { it.id })
        assertTrue(s.megaOptions(s.pokemonById.getValue("raichualola")).isEmpty())
    }

    @Test fun teamJsonRoundTrip() {
        val t = team(*fourLegal().toTypedArray()).copy(name = "測試隊伍")
        val json = SnapshotJson.encodeToString(Team.serializer(), t)
        assertEquals(t, SnapshotJson.decodeFromString(Team.serializer(), json))
    }

    @Test fun exportText() {
        val txt = TeamEngine.exportText(s, team(*fourLegal().toTypedArray()))
        assertTrue("Garchomp" in txt && "Level: 50" in txt, txt)
        assertNotNull(txt.lines().firstOrNull { it.startsWith("- ") })
    }
}
