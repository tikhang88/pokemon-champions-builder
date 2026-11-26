package hk.tikhang.champions.core

import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase 7 (offline update) and Phase 8 (data, regulation, performance) tests. */
class Phase78Test {
    private val path = System.getProperty("snapshot.path")
    private val bytes: ByteArray by lazy { File(path).readBytes() }
    private val s: Snapshot by lazy { Snapshot.parse(bytes.toString(Charsets.UTF_8)) }
    private val versionFile = File(File(path).parentFile.parentFile.parentFile.parentFile.parentFile, "data/version.json")

    // ---- Phase 7: online update -------------------------------------------------------

    private fun remote(dv: String = "2026.12.03-sdabcdef0", gen: String = "2026-12-03T19:05:00Z", schema: Int = 1) =
        RemoteVersion(schema, dv, gen, "M-D", null, SnapshotImporter.sha256(bytes), bytes.size.toLong())

    @Test fun updateStatus() {
        assertEquals(UpdateStatus.AVAILABLE, UpdatePolicy.status(s.meta, remote()))
        assertEquals(UpdateStatus.UP_TO_DATE, UpdatePolicy.status(s.meta, remote(dv = s.meta.dataVersion)))
        assertEquals(UpdateStatus.UP_TO_DATE, UpdatePolicy.status(s.meta, remote(gen = "2020-01-01T00:00:00Z")))
        assertEquals(UpdateStatus.APP_TOO_OLD, UpdatePolicy.status(s.meta, remote(schema = SUPPORTED_SCHEMA_VERSION + 1)))
    }

    @Test fun integrityCheck() {
        assertNull(UpdatePolicy.verify(bytes, remote()))
        assertNotNull(UpdatePolicy.verify(bytes.copyOf(bytes.size - 10), remote()))     // truncated download
        val corrupted = bytes.copyOf().also { it[100] = (it[100] + 1).toByte() }
        assertNotNull(UpdatePolicy.verify(corrupted, remote()))                         // same size, wrong hash
        assertNotNull(UpdatePolicy.verify(ByteArray(0), remote()))
    }

    @Test fun publishedVersionFileMatchesBundledData() {
        assertTrue(versionFile.exists(), "data/version.json missing at $versionFile")
        val v = RemoteVersion.parse(versionFile.readText())
        // data/champions_data.json and the app asset are the same snapshot.
        val dataFile = File(versionFile.parentFile, "champions_data.json").readBytes()
        assertNull(UpdatePolicy.verify(dataFile, v))
        assertEquals(s.meta.dataVersion, v.dataVersion)
    }

    @Test fun teamImpactWhenRegulationListChanges() {
        val chomp = TeamEngine.newSlot(s, "garchomp").copy(moves = listOf("earthquake"))
        val team = Team("t", "M-C", BattleFormat.DOUBLES, listOf(chomp) + List(5) { null })
        // Simulate an update that removes Garchomp from M-C.
        val mc = s.regulationById.getValue("M-C")
        val newSnap = s.copy(regulations = s.regulations.map { if (it.id == "M-C") mc.copy(allowedPokemon = mc.allowedPokemon!! - "garchomp") else it })
        val impact = TeamImpact.compute(s, newSnap, listOf(team))
        assertEquals(1, impact.size)
        assertEquals(Legality.ILLEGAL, impact.single().after)
        assertTrue(impact.single().newProblems.any { "不能在此 Regulation 使用" in it })
        assertTrue(TeamImpact.compute(s, s, listOf(team)).isEmpty())
    }

    @Test fun outdatedRegulationHint() {
        val team = Team("t", "M-B", BattleFormat.DOUBLES)
        val now = Instant.parse("2026-11-26T03:00:00Z")
        assertEquals("M-C", TeamImpact.outdatedRegulation(s, team, now)?.id)
        assertNull(TeamImpact.outdatedRegulation(s, team.copy(regulationId = "M-C"), now))
    }

    // ---- Phase 8: data validation ------------------------------------------------------

    @Test fun dataIntegrity() {
        // Every legal Pokémon exists, has a learnset, valid types and abilities.
        for (r in s.regulations) for (id in r.allowedPokemon.orEmpty()) assertTrue(id in s.pokemonById, "${r.id}: $id")
        for (p in s.pokemon) {
            assertTrue(p.types.all { it in s.types }, p.id)
            assertTrue(p.abilities.isNotEmpty(), p.id)
            assertTrue(p.baseStats.total > 0, p.id)
            if (p.teamSelectable) assertTrue(s.learnsets[p.id].orEmpty().isNotEmpty(), "learnset ${p.id}")
        }
        // Every Mega has its stone in the item list and a base form.
        for (m in s.pokemon.filter { it.isMega }) {
            assertNotNull(s.itemById[toId(m.megaStone!!)], "stone for ${m.id}")
            assertTrue(m.megaOf in s.pokemonById || s.pokemon.any { it.baseId == m.megaOf }, "base of ${m.id}")
        }
        // Moves: Champions PP rule (base ≤ 20, max = 4 × (base/5 + 1)).
        for (mv in s.moves) {
            assertTrue(mv.pp <= 20, mv.id)
            if (mv.pp >= 5) assertTrue(mv.maxPp == (mv.pp / 5 + 1) * 4 || mv.maxPp == mv.pp, mv.id)   // noPPBoosts moves keep base PP
        }
        // Type chart: 18 × 18 with only the four legal multipliers.
        assertEquals(18, s.typeChart.size)
        assertTrue(s.typeChart.values.all { row -> row.size == 18 && row.values.all { it in setOf(0.0, 0.5, 1.0, 2.0) } })
        assertEquals(25, s.natures.size)
        // Every record carries a verification status.
        assertTrue((s.pokemon.map { it.verification } + s.moves.map { it.verification }).all { it.isNotBlank() })
    }

    @Test fun regulationValidation() {
        val regs = s.regulations.sortedBy { it.effectiveDate }
        // Dates are in order and do not overlap.
        regs.zipWithNext().forEach { (a, b) ->
            val end = RegulationEngine.parseInstant(a.endDate!!)!!; val start = RegulationEngine.parseInstant(b.effectiveDate!!)!!
            assertTrue(!end.isAfter(start), "${a.id} ends after ${b.id} starts")
        }
        // Each list contains the previous one (the roster only grows in Champions so far).
        val withList = regs.filter { it.hasPokemonList }
        withList.zipWithNext().forEach { (a, b) -> assertTrue(b.allowedPokemon!!.containsAll(a.allowedPokemon!!), "${b.id} ⊇ ${a.id}") }
        // No Mythical or Restricted Legendary is allowed (Flat Rules).
        assertTrue(s.regulationById.getValue("M-C").allowedPokemon!!.none { it in setOf("mewtwo", "mew", "kyogre", "groudon", "rayquaza", "zacian", "koraidon", "miraidon") })
    }

    // ---- Phase 8: performance (JVM in CI; the phone is slower, limits are generous) ----

    private inline fun <T> timed(label: String, limitMs: Long, block: () -> T): T {
        val t0 = System.nanoTime()
        val r = block()
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("PERF $label: ${ms} ms (limit $limitMs ms)")
        assertTrue(ms <= limitMs, "$label took $ms ms")
        return r
    }

    @Test fun performance() {
        timed("parse snapshot", 3000) { Snapshot.parse(bytes.toString(Charsets.UTF_8)) }
        timed("validate snapshot", 1000) { SnapshotValidator.validate(s) }
        val team = Team("perf", "M-C", BattleFormat.DOUBLES, listOf("garchomp", "incineroar", "rillaboom", "charizard", "dragonite", "sylveon").map { id ->
            TeamEngine.newSlot(s, id).copy(moves = s.learnsets.getValue(id).take(4))
        })
        val te = TypeEngine(s.typeChart, s.types)
        timed("team analysis", 1500) { TeamAnalysisEngine.analyse(s, te, team) }
        val pool = s.regulationById.getValue("M-C").allowedPokemon!!.map { s.pokemonById.getValue(it) }
        timed("speed tier list", 500) { SpeedEngine.tierList(pool, SpeedEngine.Spread.MAX_PLUS, TeamAnalysisEngine.members(s, team), SpeedEngine.Conditions()) }
        val atk = DamageEngine.sideFromSlot(s, team.slots[0]!!)!!; val def = DamageEngine.sideFromSlot(s, team.slots[1]!!)!!
        timed("1000 damage calculations", 2000) { repeat(1000) { DamageEngine.calculate(s, atk, def, s.moveById.getValue("earthquake"), DamageEngine.Field()) } }
        timed("survival analysis", 2000) { DamageEngine.survival(s, atk, team.slots[1]!!, s.moveById.getValue("earthquake"), DamageEngine.Field()) }
        val opp = team.copy(opponent = true)
        timed("matchup report", 1500) { MatchupEngine.report(s, te, TeamAnalysisEngine.members(s, team), MatchupEngine.opponentsFromTeam(s, opp)) }
        val setup = BattleEngine.Setup(BattleFormat.DOUBLES, listOf(team.members.take(4), opp.members.drop(2)), maxTurns = 30)
        timed("100 AI battles", 30000) { BattleEngine.simulateBattles(s, setup, 100) }
    }
}
