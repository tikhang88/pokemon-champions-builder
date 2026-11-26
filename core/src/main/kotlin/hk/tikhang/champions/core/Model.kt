package hk.tikhang.champions.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** Snapshot schema versions this build of the app can read. */
const val SUPPORTED_SCHEMA_VERSION = 1

val SnapshotJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

@Serializable
data class Snapshot(
    val meta: Meta,
    val regulations: List<Regulation>,
    val types: List<String>,
    val typeChart: Map<String, Map<String, Double>>,
    val natures: List<Nature>,
    val pokemon: List<Pokemon>,
    val moves: List<Move>,
    val abilities: List<Ability>,
    val items: List<Item>,
    val learnsets: Map<String, List<String>>,
    val mechanics: JsonElement? = null,
    val balancePatches: List<BalancePatch> = emptyList(),
) {
    // Lookup tables, built once per snapshot.
    @kotlinx.serialization.Transient val pokemonById: Map<String, Pokemon> = pokemon.associateBy { it.id }
    @kotlinx.serialization.Transient val moveById: Map<String, Move> = moves.associateBy { it.id }
    @kotlinx.serialization.Transient val abilityById: Map<String, Ability> = abilities.associateBy { it.id }
    @kotlinx.serialization.Transient val itemById: Map<String, Item> = items.associateBy { it.id }
    @kotlinx.serialization.Transient val regulationById: Map<String, Regulation> = regulations.associateBy { it.id }

    /** Mega forms of a base species id, e.g. "charizard" → [Charizard-Mega-X, Charizard-Mega-Y]. */
    fun megasOf(baseId: String): List<Pokemon> = pokemon.filter { it.isMega && it.megaOf == baseId }

    /**
     * Mega forms available to one out-of-battle form. Handles species whose formes have their own
     * Megas: Tatsugiri (Curly/Droopy/Stretchy) and Meowstic (♂/♀).
     */
    fun megaOptions(p: Pokemon): List<Pokemon> {
        val candidates = megasOf(p.baseId)
        val siblingFormes = pokemon.filter { it.baseId == p.baseId && !it.isMega && it.forme != null }.mapNotNull { it.forme }.toSet()
        // e.g. Mega Floette belongs to Eternal Floette because plain Floette isn't in the data.
        val plainBaseMissing = pokemonById[p.baseId]?.teamSelectable != true
        return candidates.filter { m ->
            val prefix = (m.forme ?: "").substringBefore("Mega").trimEnd('-')
            if (p.forme != null) prefix == p.forme || (prefix.isEmpty() && plainBaseMissing)
            else prefix.isEmpty() || prefix !in siblingFormes
        }
    }

    fun ability(name: String): Ability? = abilityById[toId(name)]

    /** Traditional Chinese display name; spells out the forme when the source gives none. */
    fun displayName(p: Pokemon): String = displayNames[p.id] ?: p.name

    @kotlinx.serialization.Transient
    private val displayNames: Map<String, String> = pokemon.associate { p -> p.id to computeDisplayName(p) }

    private fun computeDisplayName(p: Pokemon): String {
        val zh = p.nameZh ?: return p.name
        val f = p.forme ?: return zh
        if (p.isMega) {
            if (zh.contains("超級")) return zh
            val suffix = f.substringAfter("Mega", "").trim('-')
            val gender = when (f.substringBefore("-Mega", "")) { "F" -> "♀"; "M" -> "♂"; else -> "" }
            return "超級$zh$gender" + if (suffix.isNotEmpty()) " $suffix" else ""
        }
        val baseZh = pokemonById[p.baseId]?.nameZh
        return if (baseZh != null && zh != baseZh) zh else "$zh（${FORME_ZH[f] ?: f}）"
    }

    companion object {
        fun parse(text: String): Snapshot = SnapshotJson.decodeFromString(serializer(), text)
    }
}

@Serializable
data class Meta(
    val app: String = "",
    val schemaVersion: Int,
    val dataVersion: String,
    val generatedAt: String = "",
    val currentRegulation: String,
    val nextRegulation: String? = null,
    val regulationInEffect: String? = null,
    val source: Source,
)

@Serializable
data class Source(
    val name: String = "",
    val url: String = "",
    val commit: String = "",
    val commitDate: String = "",
)

@Serializable
data class Regulation(
    val id: String,
    val name: String,
    val version: Int = 0,
    val effectiveDate: String? = null,
    val endDate: String? = null,
    val level: String? = null,
    val speciesClause: Boolean = true,
    val itemClause: Boolean = true,
    val bannedCategories: List<String> = emptyList(),
    val megaRules: String? = null,
    val statSystem: String? = null,
    val counts: RegulationCounts? = null,
    val allowedPokemon: List<String>? = null,
    val allowedPokemonNote: String? = null,
    val newVsPrevious: List<String>? = null,
    val announced: Announced? = null,
    val verification: String = Verification.PENDING,
    val source: String = "",
    val isCurrent: Boolean = false,
    /** True when the list is built from announced names only (Showdown has no data yet). */
    val provisional: Boolean = false,
) {
    val hasPokemonList: Boolean get() = !allowedPokemon.isNullOrEmpty()
    /** A list that is confirmed by the data source, not just announced. */
    val hasConfirmedList: Boolean get() = hasPokemonList && !provisional
}

@Serializable
data class RegulationCounts(
    val legalEntries: Int = 0,
    val teamSelectable: Int = 0,
    val megaForms: Int = 0,
    val nonMegaSelectable: Int = 0,
)

@Serializable
data class Announced(
    val carriesOverFrom: String? = null,
    val newPokemonCount: Int? = null,
    val newMegaCount: Int? = null,
    val namedSoFar: List<String> = emptyList(),
    val balanceAdjustments: String? = null,
    val unannounced: String? = null,
)

/** Officially announced battle balance changes that take effect at [effectiveDate]. */
@Serializable
data class BalancePatch(
    val id: String,
    val effectiveDate: String,
    val appliesTo: String? = null,
    val source: String = "",
    val verification: String = Verification.OFFICIAL,
    val moveChanges: List<PatchMoveChange> = emptyList(),
    val newMoves: List<PatchNewMove> = emptyList(),
    val abilityChanges: List<PatchAbilityChange> = emptyList(),
)

@Serializable
data class PatchMoveChange(val move: String, val text: String, val accuracy: Int? = null)

@Serializable
data class PatchNewMove(val move: String, val pokemon: List<String>)

@Serializable
data class PatchAbilityChange(val ability: String, val text: String)

@Serializable
data class Stats(val hp: Int, val atk: Int, val def: Int, val spa: Int, val spd: Int, val spe: Int) {
    operator fun get(stat: Stat): Int = when (stat) {
        Stat.HP -> hp; Stat.ATK -> atk; Stat.DEF -> def; Stat.SPA -> spa; Stat.SPD -> spd; Stat.SPE -> spe
    }
    val total: Int get() = hp + atk + def + spa + spd + spe
}

enum class Stat(val key: String, val zh: String) {
    HP("hp", "HP"), ATK("atk", "攻擊"), DEF("def", "防禦"), SPA("spa", "特攻"), SPD("spd", "特防"), SPE("spe", "速度")
}

@Serializable
data class Pokemon(
    val id: String,
    val name: String,
    val nameZh: String? = null,
    val num: Int,
    val types: List<String>,
    val baseStats: Stats,
    val bst: Int = 0,
    val abilities: List<String>,
    val forme: String? = null,
    val baseSpecies: String,
    val isMega: Boolean = false,
    val megaStone: String? = null,
    val megaOf: String? = null,
    val battleOnly: Boolean = false,
    val teamSelectable: Boolean = true,
    val heightM: Double? = null,
    val weightKg: Double? = null,
    val gender: String? = null,
    val otherFormes: List<String> = emptyList(),
    val legalIn: List<String> = emptyList(),
    val learnsetFrom: String? = null,
    /** "MAIN_SERIES_SV" when the move list is a placeholder from Scarlet/Violet. */
    val learnsetSource: String? = null,
    val abilityNote: String? = null,
    val verification: String = Verification.COMMUNITY,
) {
    val hasPlaceholderLearnset: Boolean get() = learnsetSource == "MAIN_SERIES_SV"
    val baseId: String get() = toId(baseSpecies)
}

@Serializable
data class Move(
    val id: String,
    val name: String,
    val nameZh: String? = null,
    val type: String,
    val category: String,
    val basePower: Int = 0,
    /** A number, or `true` for moves that never miss. */
    val accuracy: JsonElement = JsonPrimitive(true),
    val pp: Int = 0,
    val maxPp: Int = 0,
    val priority: Int = 0,
    val target: String? = null,
    val flags: List<String> = emptyList(),
    val critRatio: Int = 1,
    /** A number of hits, or [min, max]. */
    val multihit: JsonElement? = null,
    val secondary: JsonElement? = null,
    val recoil: JsonElement? = null,
    val drain: JsonElement? = null,
    // Effects for the Battle Simulator
    val self: JsonElement? = null,
    val boosts: Map<String, Int>? = null,
    val status: String? = null,
    val volatileStatus: String? = null,
    val sideCondition: String? = null,
    val weather: String? = null,
    val terrain: String? = null,
    val pseudoWeather: String? = null,
    val heal: List<Int>? = null,
    val selfSwitch: JsonElement? = null,
    val selfdestruct: JsonElement? = null,
    val secondaries: JsonElement? = null,
    val willCrit: Boolean? = null,
    val ohko: JsonElement? = null,
    val stallingMove: Boolean? = null,
    val shortDesc: String? = null,
    val shortDescZh: String? = null,
    val championsChanged: Boolean = false,
    val mainSeriesValues: JsonElement? = null,
    val verification: String = Verification.COMMUNITY,
) {
    /** Accuracy percentage, or null when the move never misses. */
    val accuracyPercent: Int?
        get() = (accuracy as? JsonPrimitive)?.let { p -> if (p.booleanOrNull == true) null else p.intOrNull }
}

@Serializable
data class Ability(
    val id: String,
    val name: String,
    val nameZh: String? = null,
    val shortDesc: String? = null,
    val shortDescZh: String? = null,
    val championsChanged: Boolean = false,
    val verification: String = Verification.COMMUNITY,
)

@Serializable
data class Item(
    val id: String,
    val name: String,
    val nameZh: String? = null,
    val megaStone: Map<String, String>? = null,
    val isMegaStone: Boolean = false,
    val shortDesc: String? = null,
    val shortDescZh: String? = null,
    val verification: String = Verification.COMMUNITY,
)

@Serializable
data class Nature(val id: String, val name: String, val plus: String? = null, val minus: String? = null)

object Verification {
    const val OFFICIAL = "OFFICIAL"
    const val VERIFIED = "VERIFIED"
    const val COMMUNITY = "COMMUNITY_SOURCE"
    const val PENDING = "PENDING_VERIFICATION"

    fun label(v: String): String = when (v) {
        OFFICIAL -> "官方確認"
        VERIFIED -> "已驗證"
        COMMUNITY -> "社群資料"
        PENDING -> "待驗證"
        else -> v
    }
}

/** Chinese labels for common formes; unknown formes fall back to the English forme name. */
val FORME_ZH: Map<String, String> = mapOf(
    "Alola" to "阿羅拉的樣子", "Galar" to "伽勒爾的樣子", "Hisui" to "洗翠的樣子", "Paldea-Combat" to "帕底亞・鬥戰種",
    "Paldea-Blaze" to "帕底亞・火熾種", "Paldea-Aqua" to "帕底亞・水瀾種", "F" to "♀", "M" to "♂",
    "Midnight" to "黑夜的樣子", "Dusk" to "黃昏的樣子", "Low-Key" to "低調的樣子", "Eternal" to "永恆之花",
    "Small" to "小尺寸", "Large" to "大尺寸", "Super" to "特大尺寸", "Blue" to "藍羽毛", "Yellow" to "黃羽毛", "White" to "白羽毛",
)

fun toId(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }
