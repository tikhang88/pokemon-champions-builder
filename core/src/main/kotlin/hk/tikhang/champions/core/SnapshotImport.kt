package hk.tikhang.champions.core

import java.security.MessageDigest

/** One problem found while validating a data snapshot. Errors block the import; warnings don't. */
data class Issue(val severity: Severity, val message: String) {
    enum class Severity { ERROR, WARNING }
}

object SnapshotValidator {
    private const val MAX_LISTED = 5

    fun validate(s: Snapshot): List<Issue> {
        val out = mutableListOf<Issue>()
        fun err(m: String) { out += Issue(Issue.Severity.ERROR, m) }
        fun warn(m: String) { out += Issue(Issue.Severity.WARNING, m) }
        fun sample(c: Collection<String>) = c.take(MAX_LISTED).joinToString() + if (c.size > MAX_LISTED) " …（共 ${c.size} 個）" else ""

        if (s.meta.schemaVersion > SUPPORTED_SCHEMA_VERSION) {
            err("資料格式版本 ${s.meta.schemaVersion} 比本 App 支援的版本（$SUPPORTED_SCHEMA_VERSION）新，請先更新 App")
        }
        if (s.pokemon.isEmpty()) err("沒有 Pokémon 資料")
        if (s.moves.isEmpty()) err("沒有招式資料")
        if (s.types.size != 18) err("屬性數目應為 18（現為 ${s.types.size}）")
        for (a in s.types) {
            val row = s.typeChart[a]
            if (row == null || s.types.any { it !in row }) err("屬性相性表缺少 $a 的資料")
        }
        if (s.natures.size != 25) warn("性格數目應為 25（現為 ${s.natures.size}）")

        dupes(s.pokemon.map { it.id }).takeIf { it.isNotEmpty() }?.let { err("重複的 Pokémon ID：${sample(it)}") }
        dupes(s.moves.map { it.id }).takeIf { it.isNotEmpty() }?.let { err("重複的招式 ID：${sample(it)}") }
        dupes(s.regulations.map { it.id }).takeIf { it.isNotEmpty() }?.let { err("重複的 Regulation：${sample(it)}") }

        if (s.meta.currentRegulation !in s.regulationById) err("目前 Regulation「${s.meta.currentRegulation}」不在 Regulation 清單內")
        else if (!s.regulationById.getValue(s.meta.currentRegulation).hasPokemonList) err("目前 Regulation「${s.meta.currentRegulation}」沒有 Pokémon 名單")

        for (r in s.regulations) {
            val missing = r.allowedPokemon.orEmpty().filter { it !in s.pokemonById }
            if (missing.isNotEmpty()) err("${r.id} 名單內有未知 Pokémon：${sample(missing)}")
            if (r.effectiveDate != null && RegulationEngine.parseInstant(r.effectiveDate) == null) err("${r.id} 的生效日期格式錯誤")
        }

        val badLearn = s.learnsets.filterKeys { it !in s.pokemonById }.keys
        if (badLearn.isNotEmpty()) err("招式表屬於未知 Pokémon：${sample(badLearn)}")
        val badMoves = s.learnsets.values.flatten().filter { it !in s.moveById }.toSet()
        if (badMoves.isNotEmpty()) err("招式表內有未知招式：${sample(badMoves)}")
        val noLearnset = s.pokemon.filter { it.teamSelectable && it.id !in s.learnsets }.map { it.id }
        if (noLearnset.isNotEmpty()) warn("以下 Pokémon 沒有招式表：${sample(noLearnset)}")

        val badMega = s.pokemon.filter { it.isMega && (it.megaOf == null || it.megaStone == null) }.map { it.id }
        if (badMega.isNotEmpty()) err("Mega 資料不完整（缺原形態或 Mega Stone）：${sample(badMega)}")
        val unknownAbility = s.pokemon.flatMap { it.abilities }.filter { s.ability(it) == null }.toSet()
        if (unknownAbility.isNotEmpty()) warn("未知特性：${sample(unknownAbility)}")

        val pending = s.regulations.filter { it.verification == Verification.PENDING }.map { it.id }
        if (pending.isNotEmpty()) warn("以下 Regulation 仍是待驗證（沒有完整名單）：${pending.joinToString()}")
        return out
    }

    private fun dupes(ids: List<String>) = ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
}

/** Differences between the snapshot in use and one about to be imported (spec §15 Data Change Log). */
data class ChangeLog(
    val fromVersion: String,
    val toVersion: String,
    val newPokemon: List<String>,
    val removedPokemon: List<String>,
    val changedPokemon: List<String>,
    val newMegas: List<String>,
    val removedMegas: List<String>,
    val newMoves: List<String>,
    val removedMoves: List<String>,
    val changedMoves: List<String>,
    val regulationChanges: List<String>,
) {
    val isEmpty: Boolean
        get() = listOf(newPokemon, removedPokemon, changedPokemon, newMegas, removedMegas, newMoves, removedMoves, changedMoves, regulationChanges).all { it.isEmpty() }

    fun summary(): String = buildString {
        appendLine("$fromVersion → $toVersion")
        fun line(label: String, l: List<String>) { if (l.isNotEmpty()) appendLine("$label：${l.size}") }
        line("新增 Pokémon", newPokemon); line("移除 Pokémon", removedPokemon); line("資料改變的 Pokémon", changedPokemon)
        line("新增 Mega", newMegas); line("移除 Mega", removedMegas)
        line("新增招式", newMoves); line("移除招式", removedMoves); line("改變的招式", changedMoves)
        line("Regulation 變更", regulationChanges)
        if (isEmpty) appendLine("沒有資料改變")
    }.trim()

    companion object {
        fun between(old: Snapshot, new: Snapshot): ChangeLog {
            fun name(s: Snapshot, id: String) = s.pokemonById[id]?.let { s.displayName(it) } ?: id
            val oldP = old.pokemonById; val newP = new.pokemonById
            val added = (newP.keys - oldP.keys).sortedBy { newP.getValue(it).num }
            val removed = (oldP.keys - newP.keys).sortedBy { oldP.getValue(it).num }
            val changed = newP.keys.intersect(oldP.keys).filter { id ->
                val a = oldP.getValue(id); val b = newP.getValue(id)
                a.types != b.types || a.baseStats != b.baseStats || a.abilities != b.abilities ||
                    old.learnsets[id].orEmpty().toSet() != new.learnsets[id].orEmpty().toSet()
            }.sortedBy { newP.getValue(it).num }

            val oldM = old.moveById; val newM = new.moveById
            val movesChanged = newM.keys.intersect(oldM.keys).filter { id ->
                val a = oldM.getValue(id); val b = newM.getValue(id)
                a.basePower != b.basePower || a.accuracy != b.accuracy || a.pp != b.pp || a.type != b.type ||
                    a.category != b.category || a.priority != b.priority || a.shortDesc != b.shortDesc
            }.sorted()

            val regs = mutableListOf<String>()
            for (r in new.regulations) {
                val o = old.regulationById[r.id]
                when {
                    o == null -> regs += "新 Regulation：${r.id}"
                    !o.hasConfirmedList && r.hasConfirmedList -> regs += "${r.id}：已取得 Pokémon 名單（${r.allowedPokemon!!.size} 個）"
                    else -> {
                        val add = r.allowedPokemon.orEmpty() - o.allowedPokemon.orEmpty().toSet()
                        val rem = o.allowedPokemon.orEmpty() - r.allowedPokemon.orEmpty().toSet()
                        if (add.isNotEmpty()) regs += "${r.id}：新增 ${add.size} 個可用 Pokémon"
                        if (rem.isNotEmpty()) regs += "${r.id}：移除 ${rem.size} 個可用 Pokémon"
                        if (o.effectiveDate != r.effectiveDate || o.endDate != r.endDate) regs += "${r.id}：日期改變"
                        if (o.verification != r.verification) regs += "${r.id}：${Verification.label(o.verification)} → ${Verification.label(r.verification)}"
                    }
                }
            }
            if (old.meta.currentRegulation != new.meta.currentRegulation) regs += "目前 Regulation：${old.meta.currentRegulation} → ${new.meta.currentRegulation}"

            return ChangeLog(
                fromVersion = old.meta.dataVersion, toVersion = new.meta.dataVersion,
                newPokemon = added.map { name(new, it) }, removedPokemon = removed.map { name(old, it) },
                changedPokemon = changed.map { name(new, it) },
                newMegas = added.filter { newP.getValue(it).isMega }.map { name(new, it) },
                removedMegas = removed.filter { oldP.getValue(it).isMega }.map { name(old, it) },
                newMoves = (newM.keys - oldM.keys).sorted().map { newM.getValue(it).nameZh ?: newM.getValue(it).name },
                removedMoves = (oldM.keys - newM.keys).sorted().map { oldM.getValue(it).nameZh ?: oldM.getValue(it).name },
                changedMoves = movesChanged.map { newM.getValue(it).nameZh ?: newM.getValue(it).name },
                regulationChanges = regs,
            )
        }
    }
}

/** Result of reading a candidate snapshot file, before the user confirms the import. */
sealed class ImportCheck {
    data class Ok(val snapshot: Snapshot, val sha256: String, val issues: List<Issue>, val changes: ChangeLog?) : ImportCheck() {
        val canImport: Boolean get() = issues.none { it.severity == Issue.Severity.ERROR }
    }
    data class Unreadable(val reason: String) : ImportCheck()
}

object SnapshotImporter {
    /** Parse, validate and diff [bytes] against [current]. Never modifies anything. */
    fun check(bytes: ByteArray, current: Snapshot?): ImportCheck {
        val text = runCatching { bytes.toString(Charsets.UTF_8) }.getOrElse { return ImportCheck.Unreadable("檔案不是文字檔") }
        val snap = try {
            Snapshot.parse(text)
        } catch (e: Exception) {
            return ImportCheck.Unreadable("不是有效的 Champions 資料檔（${e.message?.take(160)}）")
        }
        val issues = SnapshotValidator.validate(snap)
        val changes = current?.let { ChangeLog.between(it, snap) }
        return ImportCheck.Ok(snap, sha256(bytes), issues, changes)
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
