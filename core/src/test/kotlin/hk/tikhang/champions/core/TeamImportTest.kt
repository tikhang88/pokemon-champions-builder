package hk.tikhang.champions.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TeamImportTest {
    private val s: Snapshot by lazy { Snapshot.parse(File(System.getProperty("snapshot.path")).readText()) }

    private val paste = """
        === [gen9champions] Rain ===

        Chompy (Garchomp) (M) @ Garchompite Z
        Ability: Rough Skin
        Level: 50
        EVs: 2 HP / 32 Atk / 32 Spe
        Jolly Nature
        - Earthquake
        - Dragon Claw

        Incineroar @ Sitrus Berry
        Ability: Intimidate
        EVs: 252 HP / 4 Atk / 252 SpD
        Careful Nature
        IVs: 0 Spe
        - Fake Out
        - Flare Blitz
        - Parting Shot

        Missingno @ Leftovers
        - Tackle
    """.trimIndent()

    @Test fun importsShowdownText() {
        val r = TeamTextImporter.parse(s, paste, "Rain", "M-C", teamCode = "sqmpyrw6bp")
        assertEquals(2, r.imported)
        assertEquals("SQMPYRW6BP", r.team.teamCode)
        val chomp = r.team.slots[0]!!
        assertEquals("garchomp", chomp.speciesId)
        assertEquals("garchompmegaz", chomp.megaFormId) // Mega stone → Mega toggle on
        assertEquals("jolly", chomp.nature)
        assertEquals(32, chomp.spOf(Stat.SPE))
        assertEquals(listOf("earthquake", "dragonclaw"), chomp.moves)
        // Real EVs are converted: 252 → 32, 4 → 1.
        val inc = r.team.slots[1]!!
        assertEquals(32, inc.spOf(Stat.HP)); assertEquals(1, inc.spOf(Stat.ATK)); assertEquals(32, inc.spOf(Stat.SPD))
        assertEquals("Intimidate", inc.ability)
        assertTrue(r.warnings.any { "Missingno" in it })
        assertTrue(r.warnings.any { "EV 已按 HOME 規則轉換" in it })
    }

    @Test fun importsChineseNames() {
        val zhName = s.pokemonById.getValue("garchomp").nameZh!!
        val eq = s.moveById.getValue("earthquake").nameZh!!
        val r = TeamTextImporter.parse(s, "$zhName\n固執性格\n- $eq", "zh", "M-C")
        assertEquals(1, r.imported)
        assertEquals("adamant", r.team.slots[0]!!.nature)
        assertEquals(listOf("earthquake"), r.team.slots[0]!!.moves)
    }

    @Test fun exportThenImportRoundTrip() {
        val original = TeamTextImporter.parse(s, paste, "Rain", "M-C").team
        val again = TeamTextImporter.parse(s, TeamEngine.exportText(s, original), "Rain", "M-C").team
        assertEquals(original.members.map { it.copy() }, again.members)
    }

    @Test fun teamCodeFormat() {
        assertTrue(TeamTextImporter.isValidTeamCode("SQMPYRW6BP"))
        assertFalse(TeamTextImporter.isValidTeamCode("SQMPYRW6B"))
        assertFalse(TeamTextImporter.isValidTeamCode("SQMPYRW6B!"))
        assertEquals(32, TeamTextImporter.evToSp(252)); assertEquals(0, TeamTextImporter.evToSp(0)); assertEquals(2, TeamTextImporter.evToSp(12))
    }
}
