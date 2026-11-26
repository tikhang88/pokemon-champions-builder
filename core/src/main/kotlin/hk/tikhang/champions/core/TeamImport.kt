package hk.tikhang.champions.core

/**
 * Imports team text in Pokémon Showdown / PokePaste format, as published by team-sharing
 * sites next to a Replica Team ID. English and Traditional Chinese names are accepted.
 *
 *   Garchomp @ Choice Scarf
 *   Ability: Rough Skin
 *   Level: 50
 *   EVs: 2 HP / 32 Atk / 32 Spe      (Champions: Stat Points; real EVs >32 are converted)
 *   Jolly Nature
 *   - Earthquake
 */
object TeamTextImporter {
    data class Result(val team: Team, val warnings: List<String>, val imported: Int)

    private val STAT_KEYS = mapOf(
        "hp" to Stat.HP, "atk" to Stat.ATK, "def" to Stat.DEF, "spa" to Stat.SPA, "spd" to Stat.SPD, "spe" to Stat.SPE,
        "attack" to Stat.ATK, "defense" to Stat.DEF, "spatk" to Stat.SPA, "spdef" to Stat.SPD, "speed" to Stat.SPE,
    )

    /** Champions/HOME rule: the first SP costs 4 EVs, each further SP costs 8. */
    fun evToSp(ev: Int): Int = if (ev < 4) 0 else minOf(StatsEngine.MAX_SP_PER_STAT, 1 + (ev - 4) / 8)

    fun parse(s: Snapshot, text: String, name: String, regulationId: String, teamCode: String = "", opponent: Boolean = false): Result {
        val warnings = mutableListOf<String>()
        val blocks = text.replace("\r", "").split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("===") }
        val pokemonByName = buildMap {
            for (p in s.pokemon) {
                put(toId(p.name), p); p.nameZh?.let { put(it, p) }; put(s.displayName(p), p)
            }
        }
        val moveByName = buildMap { for (m in s.moves) { put(m.id, m); m.nameZh?.let { put(it, m) } } }
        val itemByName = buildMap { for (i in s.items) { put(i.id, i); i.nameZh?.let { put(it, i) } } }
        val abilityByName = buildMap { for (a in s.abilities) { put(a.id, a); a.nameZh?.let { put(it, a) } } }
        val natureByName = buildMap {
            for (n in s.natures) { put(n.id, n) }
            NATURE_ZH.forEach { (id, zh) -> s.natures.firstOrNull { it.id == id }?.let { put(zh, it) } }
        }
        fun find(map: Map<String, *>, raw: String) = map[raw.trim()] ?: map[toId(raw)]

        val slots = mutableListOf<TeamSlot>()
        for (block in blocks) {
            val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue
            if (slots.size == TEAM_SIZE) { warnings += "多於 $TEAM_SIZE 隻，只匯入首 $TEAM_SIZE 隻"; break }
            // First line: "Nickname (Species) (F) @ Item" or "Species @ Item"
            val head = lines.first()
            val itemPart = head.substringAfter(" @ ", "").trim().ifEmpty { null }
            var namePart = head.substringBefore(" @ ").trim().replace(Regex("\\s*\\((M|F)\\)\\s*$"), "")
            Regex("\\(([^()]+)\\)\\s*$").find(namePart)?.let { namePart = it.groupValues[1] }
            val found = find(pokemonByName, namePart) as Pokemon?
            if (found == null) { warnings += "找不到 Pokémon「$namePart」，已略過"; continue }
            // A Mega written as the species → use the base form with the Mega toggle on.
            val species = if (found.isMega) s.pokemonById[found.megaOf] ?: found else found
            var slot = TeamEngine.newSlot(s, species.id)
            if (found.isMega) slot = TeamEngine.toggleMega(s, slot, found.id)

            itemPart?.let { raw ->
                val item = find(itemByName, raw) as Item?
                if (item == null) warnings += "${species.name}：找不到道具「$raw」"
                else {
                    slot = slot.copy(item = item.id)
                    s.megaOptions(species).firstOrNull { it.megaStone?.let(::toId) == item.id }?.let { m -> slot = slot.copy(megaFormId = m.id) }
                }
            }
            val moves = mutableListOf<String>()
            var evs: MutableMap<Stat, Int>? = null
            for (line in lines.drop(1)) {
                when {
                    line.startsWith("Ability:", true) || line.startsWith("特性") -> {
                        val raw = line.substringAfter(":").substringAfter("：").trim()
                        val a = find(abilityByName, raw) as Ability?
                        if (a == null) warnings += "${species.name}：找不到特性「$raw」" else slot = slot.copy(ability = a.name)
                    }
                    line.startsWith("EVs:", true) || line.startsWith("SPs:", true) || line.startsWith("SP:", true) -> {
                        evs = mutableMapOf()
                        line.substringAfter(":").split("/").forEach { part ->
                            val bits = part.trim().split(Regex("\\s+"))
                            val v = bits.firstOrNull()?.toIntOrNull()
                            val st = bits.getOrNull(1)?.let { STAT_KEYS[toId(it)] }
                            if (v != null && st != null) evs!![st] = v
                        }
                    }
                    line.endsWith(" Nature", true) || line.endsWith("性格") -> {
                        val raw = line.removeSuffix(" Nature").removeSuffix(" nature").removeSuffix("性格").trim()
                        val n = find(natureByName, raw) as Nature?
                        if (n == null) warnings += "${species.name}：找不到性格「$raw」" else slot = slot.copy(nature = n.id)
                    }
                    line.startsWith("- ") || line.startsWith("-") -> {
                        val raw = line.removePrefix("-").trim().substringBefore(" [")
                        val m = find(moveByName, raw) as Move?
                        if (m == null) warnings += "${species.name}：找不到招式「$raw」" else moves += m.id
                    }
                    // Level, IVs, Tera Type, Shiny, Happiness: not used in Champions.
                }
            }
            evs?.let { e ->
                val isEvs = e.values.any { it > StatsEngine.MAX_SP_PER_STAT }
                val sp = if (isEvs) e.mapValues { evToSp(it.value) } else e
                if (isEvs) warnings += "${species.name}：EV 已按 HOME 規則轉換為 Stat Points"
                slot = slot.copy(sp = sp.filterValues { it > 0 })
            }
            slot = slot.copy(moves = moves.distinct().take(MAX_MOVES))
            if (moves.size > MAX_MOVES) warnings += "${species.name}：多於 $MAX_MOVES 個招式，只保留首 $MAX_MOVES 個"
            slots += slot
        }
        val team = Team(
            name = name, regulationId = regulationId, format = BattleFormat.DOUBLES,
            slots = slots + List(TEAM_SIZE - slots.size) { null }, teamCode = teamCode.trim().uppercase(), opponent = opponent,
        )
        return Result(team, warnings, slots.size)
    }

    /** A Replica Team ID is 10 letters/digits. */
    fun isValidTeamCode(code: String): Boolean = code.trim().matches(Regex("[A-Za-z0-9]{10}"))

    val NATURE_ZH: Map<String, String> = mapOf(
        "hardy" to "勤奮", "lonely" to "怕寂寞", "brave" to "勇敢", "adamant" to "固執", "naughty" to "頑皮",
        "bold" to "大膽", "docile" to "坦率", "relaxed" to "悠閒", "impish" to "淘氣", "lax" to "樂天",
        "timid" to "膽小", "hasty" to "急躁", "serious" to "認真", "jolly" to "爽朗", "naive" to "天真",
        "modest" to "內斂", "mild" to "慢吞吞", "quiet" to "冷靜", "bashful" to "害羞", "rash" to "馬虎",
        "calm" to "溫和", "gentle" to "溫順", "sassy" to "自大", "careful" to "慎重", "quirky" to "浮躁",
    )
}
