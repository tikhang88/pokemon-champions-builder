package hk.tikhang.champions.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpeedLineTest {
    private val s: Snapshot by lazy { Snapshot.parse(File(System.getProperty("snapshot.path")).readText()) }
    private fun line(id: String, l: SpeedLineEngine.Line) = SpeedLineEngine.linesOf(s.pokemonById.getValue(id)).firstOrNull { it.line == l }?.speed

    @Test fun garchompLines() {
        // Base 102 at Lv50: 32 SP + nature → 169; 32 SP → 154; 0 SP → 122; 0 SP − nature → 109.
        assertEquals(169, line("garchomp", SpeedLineEngine.Line.MAX_PLUS))
        assertEquals(154, line("garchomp", SpeedLineEngine.Line.MAX))
        assertEquals(122, line("garchomp", SpeedLineEngine.Line.NEUTRAL))
        assertEquals(109, line("garchomp", SpeedLineEngine.Line.MIN))
        assertEquals(253, line("garchomp", SpeedLineEngine.Line.SCARF))      // floor(169 × 1.5)
        assertEquals(253, line("garchomp", SpeedLineEngine.Line.PLUS1))
        assertEquals(338, line("garchomp", SpeedLineEngine.Line.PLUS2))
        assertEquals(338, line("garchomp", SpeedLineEngine.Line.TAILWIND))
        assertEquals(112, line("garchomp", SpeedLineEngine.Line.MINUS1))     // floor(169 × 2/3)
        assertNull(line("garchomp", SpeedLineEngine.Line.ABILITY))            // no Speed ability
    }

    @Test fun abilityLineOnlyForSpeedAbilities() {
        val p = s.pokemon.first { mon -> mon.abilities.any { toId(it) == "swiftswim" } }
        val e = SpeedLineEngine.linesOf(p).single { it.line == SpeedLineEngine.Line.ABILITY }
        assertEquals(2 * SpeedEngine.benchmark(p.baseStats.spe, SpeedEngine.Spread.MAX_PLUS), e.speed)
        assertNotNull(e.note); assertTrue("下雨" in e.note!!)
    }

    @Test fun orderingAndComparison() {
        val pool = s.regulationById.getValue("M-C").allowedPokemon!!.map { s.pokemonById.getValue(it) }
        val all = SpeedLineEngine.lines(pool, SpeedLineEngine.DEFAULT_LINES)
        assertTrue(all.zipWithNext().all { (a, b) -> a.speed >= b.speed })
        val tr = SpeedLineEngine.lines(pool, setOf(SpeedLineEngine.Line.MIN), trickRoom = true)
        assertTrue(tr.zipWithNext().all { (a, b) -> a.speed <= b.speed })
        val c = SpeedLineEngine.compare(169, all)
        assertEquals(all.size, c.total)
        assertTrue(c.ties >= 1)   // Garchomp's own max-speed line
    }
}
