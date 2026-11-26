package hk.tikhang.champions.core

import kotlinx.serialization.Serializable

/** Contents of data/version.json published by the "Update data" workflow (spec §14.3). */
@Serializable
data class RemoteVersion(
    val schemaVersion: Int,
    val dataVersion: String,
    val generatedAt: String,
    val currentRegulation: String,
    val nextRegulation: String? = null,
    val sha256: String,
    val bytes: Long,
    val file: String = "champions_data.json",
    val regulations: List<RemoteRegulation> = emptyList(),
) {
    companion object {
        fun parse(text: String): RemoteVersion = SnapshotJson.decodeFromString(serializer(), text)
    }
}

@Serializable
data class RemoteRegulation(val id: String, val provisional: Boolean = false, val verification: String = "", val teamSelectable: Int? = null)

enum class UpdateStatus(val zh: String) { UP_TO_DATE("已是最新"), AVAILABLE("有可用更新"), APP_TOO_OLD("需要更新 App 才能使用新資料") }

object UpdatePolicy {
    const val MAX_BYTES = 50L * 1024 * 1024

    /** A newer snapshot is one with a different version that was generated later. */
    fun status(local: Meta, remote: RemoteVersion): UpdateStatus = when {
        remote.schemaVersion > SUPPORTED_SCHEMA_VERSION -> UpdateStatus.APP_TOO_OLD
        remote.dataVersion == local.dataVersion -> UpdateStatus.UP_TO_DATE
        remote.generatedAt <= local.generatedAt -> UpdateStatus.UP_TO_DATE
        else -> UpdateStatus.AVAILABLE
    }

    /** Integrity check before import (spec §14.5). Returns an error message, or null when OK. */
    fun verify(bytes: ByteArray, remote: RemoteVersion): String? {
        if (bytes.isEmpty()) return "下載的檔案是空的"
        if (bytes.size.toLong() > MAX_BYTES) return "檔案太大"
        if (bytes.size.toLong() != remote.bytes) return "檔案大小不符（${bytes.size} / ${remote.bytes}），下載可能不完整"
        val hash = SnapshotImporter.sha256(bytes)
        if (!hash.equals(remote.sha256, ignoreCase = true)) return "SHA-256 不符，檔案可能已損壞"
        return null
    }

    /** Short description of what the remote update contains, before downloading it. */
    fun describe(local: Snapshot, remote: RemoteVersion): List<String> = buildList {
        add("${local.meta.dataVersion} → ${remote.dataVersion}")
        if (remote.currentRegulation != local.meta.currentRegulation) add("目前 Regulation：${local.meta.currentRegulation} → ${remote.currentRegulation}")
        for (r in remote.regulations) {
            val old = local.regulationById[r.id]
            when {
                old == null -> add("新 Regulation：${r.id}")
                old.provisional && !r.provisional -> add("${r.id}：取得正式名單（${r.teamSelectable ?: "?"} 個）")
                r.teamSelectable != null && old.allowedPokemon != null && r.teamSelectable != old.allowedPokemon.size ->
                    add("${r.id}：可選 Pokémon ${old.allowedPokemon.size} → ${r.teamSelectable}")
            }
        }
        add("大小 ${"%.0f".format(remote.bytes / 1024.0)} KB")
    }
}

/** How a data change affects saved teams (spec §4.5: keep old teams, show if they are affected). */
object TeamImpact {
    data class Entry(val teamName: String, val regulationId: String, val before: Legality, val after: Legality, val newProblems: List<String>) {
        fun text(): String = "$teamName（$regulationId）：${before.zh} → ${after.zh}" +
            if (newProblems.isNotEmpty()) "：" + newProblems.take(3).joinToString("；") + if (newProblems.size > 3) "…" else "" else ""
    }

    fun compute(old: Snapshot, new: Snapshot, teams: List<Team>): List<Entry> = teams.mapNotNull { t ->
        val a = LegalityEngine.check(old, t)
        val b = LegalityEngine.check(new, t)
        val oldMsgs = a.issues.map { it.message }.toSet()
        val added = b.issues.filter { it.level == LegalityIssue.Level.ERROR && it.message !in oldMsgs }.map { it.message }
        if (a.result == b.result && added.isEmpty()) null else Entry(t.name, t.regulationId, a.result, b.result, added)
    }

    /** Teams still set to a Regulation that has ended while a newer one has a list. */
    fun outdatedRegulation(s: Snapshot, team: Team, now: java.time.Instant): Regulation? {
        val reg = s.regulationById[team.regulationId] ?: return null
        if (RegulationEngine.status(reg, now) != RegulationStatus.PAST) return null
        val current = RegulationEngine.defaultRegulation(s, now)
        return current.takeIf { it.id != reg.id }
    }
}
