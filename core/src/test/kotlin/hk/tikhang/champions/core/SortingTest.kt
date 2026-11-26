package hk.tikhang.champions.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SortingTest {
    private val s: Snapshot by lazy { Snapshot.parse(File(System.getProperty("snapshot.path")).readText()) }

    @Test fun itemGroups() {
        val g = ItemGroups.grouped(s, s.items, "charizard")
        assertEquals(ItemGroups.Group.OWN_MEGA, g.first().first)
        assertTrue(g.first().second.all { it.isMegaStone } && g.first().second.any { it.id == "charizarditex" })
        assertEquals("choicescarf", g.first { it.first == ItemGroups.Group.COMMON }.second.first().id)
        // Type boosts follow the type order: Normal items first, Fire (Charcoal) next.
        val boosts = g.first { it.first == ItemGroups.Group.TYPE_BOOST }.second.map { it.id }
        assertTrue(boosts.indexOf("charcoal") < boosts.indexOf("mysticwater"))
        assertEquals(s.items.size, g.sumOf { it.second.size })
        // Without a species, every Mega Stone is in the last group.
        assertEquals(ItemGroups.Group.MEGA, ItemGroups.grouped(s, s.items, null).last().first)
    }

    @Test fun movesGroupedByTypeOwnTypesFirst() {
        val p = s.pokemonById.getValue("gengar")
        val moves = s.learnsets.getValue("gengar").mapNotNull { s.moveById[it] }
        val g = MoveGroups.grouped(s, moves, p.types)
        assertEquals(listOf("Ghost", "Poison"), g.take(2).map { it.first })
        assertEquals(moves.size, g.sumOf { it.second.size })
        assertEquals(g.size, g.map { it.first }.distinct().size)          // each type appears once
        g.forEach { (_, list) ->
            val cats = list.map { it.category }
            assertEquals(cats.sortedBy { listOf("Physical", "Special", "Status").indexOf(it) }, cats)
        }
    }
}
