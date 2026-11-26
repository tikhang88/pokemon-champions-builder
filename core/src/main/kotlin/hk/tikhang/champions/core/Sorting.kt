package hk.tikhang.champions.core

/** Picker ordering for items and moves: grouped so related choices sit together. */
object ItemGroups {
    enum class Group(val zh: String) {
        OWN_MEGA("此 Pokémon 的 Mega Stone"),
        COMMON("常用對戰道具"),
        TYPE_BOOST("屬性強化道具"),
        RESIST_BERRY("抗性樹果（減半效果絕佳傷害）"),
        BERRY("其他樹果"),
        FIELD("天氣／場地"),
        OTHER("其他道具"),
        MEGA("其他 Mega Stone"),
    }

    /** Competitive staples, in this display order. Grouping is an analysis aid, not usage data. */
    private val COMMON = listOf(
        "choicescarf", "focussash", "lifeorb", "sitrusberry", "leftovers", "lumberry", "mentalherb", "rockyhelmet",
        "expertbelt", "whiteherb", "lightclay", "scopelens", "quickclaw", "brightpowder", "kingsrock", "shellbell",
        "ejectbutton", "redcard", "airballoon", "focusband", "muscleband", "wiseglasses", "widelens", "zoomlens",
    )
    val TYPE_BOOST: Map<String, String> = mapOf(
        "silkscarf" to "Normal", "normalgem" to "Normal", "charcoal" to "Fire", "mysticwater" to "Water", "magnet" to "Electric",
        "miracleseed" to "Grass", "nevermeltice" to "Ice", "blackbelt" to "Fighting", "poisonbarb" to "Poison", "softsand" to "Ground",
        "sharpbeak" to "Flying", "twistedspoon" to "Psychic", "silverpowder" to "Bug", "hardstone" to "Rock", "spelltag" to "Ghost",
        "dragonfang" to "Dragon", "blackglasses" to "Dark", "metalcoat" to "Steel", "fairyfeather" to "Fairy",
    )
    val RESIST_BERRY: Map<String, String> = mapOf(
        "chilanberry" to "Normal", "occaberry" to "Fire", "passhoberry" to "Water", "wacanberry" to "Electric", "rindoberry" to "Grass",
        "yacheberry" to "Ice", "chopleberry" to "Fighting", "kebiaberry" to "Poison", "shucaberry" to "Ground", "cobaberry" to "Flying",
        "payapaberry" to "Psychic", "tangaberry" to "Bug", "chartiberry" to "Rock", "kasibberry" to "Ghost", "habanberry" to "Dragon",
        "colburberry" to "Dark", "babiriberry" to "Steel", "roseliberry" to "Fairy",
    )
    private val FIELD = listOf("damprock", "heatrock", "smoothrock", "icyrock", "terrainextender", "electricseed", "grassyseed", "mistyseed", "psychicseed")

    fun groupOf(item: Item, speciesBaseId: String?): Group = when {
        item.isMegaStone && speciesBaseId != null && item.megaStone.orEmpty().keys.any { toId(it) == speciesBaseId } -> Group.OWN_MEGA
        item.isMegaStone -> Group.MEGA
        item.id in COMMON -> Group.COMMON
        item.id in TYPE_BOOST -> Group.TYPE_BOOST
        item.id in RESIST_BERRY -> Group.RESIST_BERRY
        item.id in FIELD -> Group.FIELD
        item.id.endsWith("berry") -> Group.BERRY
        else -> Group.OTHER
    }

    /** Items grouped and ordered for the picker. [speciesBaseId] puts that species' Mega Stones first. */
    fun grouped(s: Snapshot, items: List<Item>, speciesBaseId: String?): List<Pair<Group, List<Item>>> {
        val typeIndex = s.types.withIndex().associate { it.value to it.index }
        fun within(g: Group, i: Item): Int = when (g) {
            Group.COMMON -> COMMON.indexOf(i.id)
            Group.TYPE_BOOST -> typeIndex[TYPE_BOOST[i.id]] ?: 99
            Group.RESIST_BERRY -> typeIndex[RESIST_BERRY[i.id]] ?: 99
            Group.FIELD -> FIELD.indexOf(i.id)
            else -> 0
        }
        return items.groupBy { groupOf(it, speciesBaseId) }.toSortedMap(compareBy { it.ordinal }).map { (g, list) ->
            g to list.sortedWith(compareBy<Item>({ within(g, it) }, { it.name }))
        }
    }
}

object MoveGroups {
    private val CATEGORY_ORDER = mapOf("Physical" to 0, "Special" to 1, "Status" to 2)

    /**
     * Moves grouped by type: the Pokémon's own types first, then the standard type order.
     * Within a type: physical, special, then status; higher power first.
     */
    fun grouped(s: Snapshot, moves: List<Move>, ownTypes: List<String>): List<Pair<String, List<Move>>> {
        val order = ownTypes.filter { it in s.types } + s.types.filter { it !in ownTypes }
        val rank = order.withIndex().associate { it.value to it.index }
        return moves.groupBy { it.type }.toList().sortedBy { rank[it.first] ?: 99 }.map { (t, list) ->
            t to list.sortedWith(compareBy<Move>({ CATEGORY_ORDER[it.category] ?: 3 }, { -it.basePower }, { it.name }))
        }
    }
}
