package hk.tikhang.champions.core

import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val snapshotText: String by lazy { File(System.getProperty("snapshot.path")).readText() }
private val snap: Snapshot by lazy { Snapshot.parse(snapshotText) }

class StatsEngineTest {
    @Test fun hpFormula() {
        // Garchomp base HP 108: 108 + 32 + 75 = 215, same as 252 HP EVs at Lv50 in the main series.
        assertEquals(215, StatsEngine.calc(Stat.HP, 108, 32))
        assertEquals(183, StatsEngine.calc(Stat.HP, 108, 0))
        assertEquals(1, StatsEngine.calc(Stat.HP, 1, 32)) // Shedinja
    }

    @Test fun otherStatsAndNature() {
        // Garchomp base Spe 102: (102 + 32 + 20) × 1.1 = 169.4 → 169; neutral 154.
        assertEquals(169, StatsEngine.calc(Stat.SPE, 102, 32, StatsEngine.NatureEffect.PLUS))
        assertEquals(154, StatsEngine.calc(Stat.SPE, 102, 32))
        // (130 + 0 + 20) × 0.9 = 135
        assertEquals(135, StatsEngine.calc(Stat.ATK, 130, 0, StatsEngine.NatureEffect.MINUS))
    }

    @Test fun spreadValidation() {
        assertTrue(StatsEngine.validateSpread(mapOf(Stat.HP to 32, Stat.ATK to 32, Stat.SPE to 2)).isEmpty())
        assertEquals(1, StatsEngine.validateSpread(mapOf(Stat.HP to 32, Stat.ATK to 32, Stat.SPE to 3)).size)
        assertEquals(1, StatsEngine.validateSpread(mapOf(Stat.HP to 33)).size)
    }
}

class TypeEngineTest {
    private val engine by lazy { TypeEngine(snap.typeChart, snap.types) }

    @Test fun basicChart() {
        assertEquals(0.0, engine.multiplier("Ground", listOf("Flying")))
        assertEquals(4.0, engine.multiplier("Ice", listOf("Dragon", "Ground")))
        assertEquals(0.25, engine.multiplier("Fire", listOf("Water", "Dragon")))
        assertEquals(0.0, engine.multiplier("Normal", listOf("Ghost")))
        assertEquals(2.0, engine.multiplier("Fairy", listOf("Dragon")))
    }

    @Test fun megaChangesTypes() {
        // Mega Garchomp Z drops Ground: Ice goes from 4× to 2×, Electric from 0× to 0.5×.
        val base = snap.pokemonById.getValue("garchomp")
        val megaZ = snap.pokemonById.getValue("garchompmegaz")
        assertEquals(4.0, engine.multiplier("Ice", base.types))
        assertEquals(2.0, engine.multiplier("Ice", megaZ.types))
        assertEquals(0.0, engine.multiplier("Electric", base.types))
        assertEquals(0.5, engine.multiplier("Electric", megaZ.types))
    }
}

class SnapshotTest {
    @Test fun bundledSnapshotIsValid() {
        val errors = SnapshotValidator.validate(snap).filter { it.severity == Issue.Severity.ERROR }
        assertTrue(errors.isEmpty(), errors.joinToString("\n"))
        assertEquals(SUPPORTED_SCHEMA_VERSION, snap.meta.schemaVersion)
    }

    @Test fun regulationMcCounts() {
        val mc = snap.regulationById.getValue("M-C")
        assertEquals(345, mc.allowedPokemon!!.size)
        assertEquals(82, mc.counts!!.megaForms)
        assertTrue("absolmegaz" in mc.allowedPokemon!!)
    }

    @Test fun megaData() {
        val megas = snap.megasOf("charizard").map { it.id }.toSet()
        assertEquals(setOf("charizardmegax", "charizardmegay"), megas)
        val absolZ = snap.pokemonById.getValue("absolmegaz")
        assertEquals(listOf("Dark", "Ghost"), absolZ.types)
        assertEquals(151, absolZ.baseStats.spe)
        assertEquals("Absolite Z", absolZ.megaStone)
    }

    @Test fun championsMoveChanges() {
        val protect = snap.moveById.getValue("protect")
        assertEquals(5, protect.pp)
        assertEquals(8, protect.maxPp)
        assertTrue(protect.championsChanged)
        assertEquals(95, snap.moveById.getValue("makeitrain").accuracyPercent)
        assertNull(snap.moveById.getValue("aerialace").accuracyPercent) // never misses
    }

    @Test fun displayNames() {
        assertEquals("雷丘（阿羅拉的樣子）", snap.displayName(snap.pokemonById.getValue("raichualola")))
        assertEquals("清洗洛托姆", snap.displayName(snap.pokemonById.getValue("rotomwash")))
        assertTrue(snap.displayName(snap.pokemonById.getValue("absolmegaz")).startsWith("超級"))
    }
}

class RegulationEngineTest {
    @Test fun currentAndUpcoming() {
        val nov = Instant.parse("2026-11-26T01:00:00Z")
        assertEquals("M-C", RegulationEngine.defaultRegulation(snap, nov).id)
        assertEquals(RegulationStatus.UPCOMING, RegulationEngine.status(snap.regulationById.getValue("M-D"), nov))
        assertNull(RegulationEngine.needsDataUpdate(snap, nov))
    }

    @Test fun afterMdStartsWithProvisionalList() {
        val dec = Instant.parse("2026-12-03T00:00:00Z")
        // M-D has only a provisional list (M-C + officially named Pokémon): use it, but ask for an update.
        assertEquals("M-D", RegulationEngine.defaultRegulation(snap, dec).id)
        assertEquals("M-D", RegulationEngine.needsDataUpdate(snap, dec)?.id)
    }

    @Test fun provisionalMd() {
        val md = snap.regulationById.getValue("M-D")
        assertTrue(md.provisional)
        assertEquals(Verification.PENDING, md.verification)
        assertTrue(snap.regulationById.getValue("M-C").allowedPokemon!!.all { it in md.allowedPokemon!! })
        assertTrue("heatranmega" in md.allowedPokemon!!)
        assertEquals(listOf("Water Compaction"), snap.pokemonById.getValue("heatranmega").abilities)
        assertTrue(snap.pokemonById.getValue("heatran").hasPlaceholderLearnset)
        assertEquals(Verification.PENDING, snap.pokemonById.getValue("dondozo").verification)
    }

    @Test fun balancePatchIsUpcomingUntilDec2() {
        val nov = Instant.parse("2026-11-26T01:00:00Z")
        assertEquals("命中 90% → 95%，畏縮機會 30% → 20%", RegulationEngine.upcomingMoveChanges(snap, "rockslide", nov).single().second.text)
        assertTrue(RegulationEngine.upcomingNewMoves(snap, "umbreon", nov).map { it.second }.containsAll(listOf("wonderroom", "playrough")))
        assertTrue(RegulationEngine.upcomingPatches(snap, Instant.parse("2026-12-02T03:00:00Z")).isEmpty())
    }
}

class ImportTest {
    @Test fun identicalSnapshotHasNoChanges() {
        val r = SnapshotImporter.check(snapshotText.toByteArray(), snap)
        r as ImportCheck.Ok
        assertTrue(r.canImport)
        assertTrue(r.changes!!.isEmpty, r.changes!!.summary())
        assertEquals(64, r.sha256.length)
    }

    @Test fun detectsNewRegulationList() {
        // Simulate an M-D snapshot: M-D gains a list with one extra Pokémon (Heatran stand-in = an existing id).
        val md = snap.regulationById.getValue("M-D").copy(
            allowedPokemon = snap.regulationById.getValue("M-C").allowedPokemon,
            verification = Verification.COMMUNITY, version = 1, provisional = false,
        )
        val newSnap = snap.copy(
            meta = snap.meta.copy(dataVersion = "test-md"),
            regulations = snap.regulations.map { if (it.id == "M-D") md else it },
        )
        val log = ChangeLog.between(snap, newSnap)
        assertFalse(log.isEmpty)
        assertTrue(log.regulationChanges.any { it.startsWith("M-D：已取得 Pokémon 名單") }, log.regulationChanges.toString())
    }

    @Test fun rejectsBrokenFiles() {
        assertTrue(SnapshotImporter.check("not json".toByteArray(), snap) is ImportCheck.Unreadable)
        val broken = snap.copy(learnsets = snap.learnsets + ("missingno" to listOf("tackle")))
        val issues = SnapshotValidator.validate(broken)
        assertTrue(issues.any { it.severity == Issue.Severity.ERROR })
    }

    @Test fun rejectsNewerSchema() {
        val future = snap.copy(meta = snap.meta.copy(schemaVersion = SUPPORTED_SCHEMA_VERSION + 1))
        assertNotNull(SnapshotValidator.validate(future).firstOrNull { it.severity == Issue.Severity.ERROR })
    }
}
