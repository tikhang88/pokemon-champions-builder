package hk.tikhang.champions.data

import android.content.Context
import android.net.Uri
import hk.tikhang.champions.core.ImportCheck
import hk.tikhang.champions.core.Issue
import hk.tikhang.champions.core.Snapshot
import hk.tikhang.champions.core.SnapshotImporter
import hk.tikhang.champions.core.SnapshotValidator
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Offline data storage (spec §14). The bundled snapshot ships in the APK; imported
 * snapshots live in app storage:
 *
 *   files/data/active.json   – snapshot in use (absent → bundled one is used)
 *   files/data/previous.json – the one it replaced, for "還原上一版本"
 *   files/data/history.json  – import log
 *
 * An import is written to a temp file and moved into place only after it parsed and
 * validated, so a failed or interrupted import never touches the data in use.
 */
class SnapshotStore(private val context: Context) {
    private val dir = File(context.filesDir, "data").apply { mkdirs() }
    private val active = File(dir, "active.json")
    private val previous = File(dir, "previous.json")
    private val history = File(dir, "history.json")

    enum class Origin { BUNDLED, IMPORTED }
    data class Loaded(val snapshot: Snapshot, val origin: Origin, val sha256: String, val warning: String? = null)

    fun load(): Loaded {
        if (active.exists()) {
            val bytes = active.readBytes()
            val parsed = runCatching { Snapshot.parse(bytes.toString(Charsets.UTF_8)) }.getOrNull()
            if (parsed != null && SnapshotValidator.validate(parsed).none { it.severity == Issue.Severity.ERROR }) {
                return Loaded(parsed, Origin.IMPORTED, SnapshotImporter.sha256(bytes))
            }
            // Corrupt imported file: fall back to bundled data but keep the file for inspection.
            active.renameTo(File(dir, "active.corrupt.json"))
            return loadBundled().copy(warning = "匯入的資料無法讀取，已改用內置資料")
        }
        return loadBundled()
    }

    private fun loadBundled(): Loaded {
        val bytes = context.assets.open(BUNDLED_ASSET).use { it.readBytes() }
        return Loaded(Snapshot.parse(bytes.toString(Charsets.UTF_8)), Origin.BUNDLED, SnapshotImporter.sha256(bytes))
    }

    /** Read a user-picked file (.json, or a .zip such as a GitHub Actions artifact) and check it. */
    fun check(uri: Uri, current: Snapshot?): Pair<ImportCheck, ByteArray?> {
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { readJsonBytes(it) }
                ?: return ImportCheck.Unreadable("無法開啟檔案") to null
        } catch (e: Exception) {
            return ImportCheck.Unreadable("讀取檔案失敗：${e.message}") to null
        }
        return SnapshotImporter.check(bytes, current) to bytes
    }

    /** Make [bytes] (already checked) the active snapshot. Returns an error message or null. */
    fun commit(bytes: ByteArray, result: ImportCheck.Ok, fileName: String?): String? {
        if (!result.canImport) return "資料有錯誤，不能匯入"
        val tmp = File(dir, "incoming.tmp")
        return try {
            tmp.writeBytes(bytes)
            if (active.exists()) active.copyTo(previous, overwrite = true)
            if (!tmp.renameTo(active)) {
                tmp.copyTo(active, overwrite = true); tmp.delete()
            }
            appendHistory("匯入", result.snapshot.meta.dataVersion, fileName, result.changes?.summary())
            null
        } catch (e: Exception) {
            tmp.delete()
            "寫入失敗，原有資料保持不變：${e.message}"
        }
    }

    val hasPrevious: Boolean get() = previous.exists()
    val hasImported: Boolean get() = active.exists()

    /** Swap back to the snapshot used before the last import. */
    fun revertToPrevious(): String? {
        if (!previous.exists()) return "沒有上一版本"
        val parsed = runCatching { Snapshot.parse(previous.readText()) }.getOrNull() ?: return "上一版本的檔案已損壞"
        val tmp = File(dir, "swap.tmp")
        if (active.exists()) active.copyTo(tmp, overwrite = true)
        previous.copyTo(active, overwrite = true)
        if (tmp.exists()) tmp.renameTo(previous) else previous.delete()
        appendHistory("還原", parsed.meta.dataVersion, null, null)
        return null
    }

    /** Stop using imported data and go back to the data built into the APK. */
    fun resetToBundled() {
        if (active.exists()) active.copyTo(previous, overwrite = true)
        active.delete()
        appendHistory("重設為內置資料", null, null, null)
    }

    data class HistoryEntry(val time: Long, val action: String, val dataVersion: String?, val file: String?, val summary: String?)

    fun history(): List<HistoryEntry> = runCatching {
        val arr = JSONArray(history.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            HistoryEntry(o.getLong("time"), o.getString("action"), o.optString("dataVersion").ifEmpty { null },
                o.optString("file").ifEmpty { null }, o.optString("summary").ifEmpty { null })
        }.reversed()
    }.getOrDefault(emptyList())

    private fun appendHistory(action: String, version: String?, file: String?, summary: String?) {
        val arr = runCatching { JSONArray(history.readText()) }.getOrDefault(JSONArray())
        arr.put(JSONObject().apply {
            put("time", System.currentTimeMillis()); put("action", action)
            version?.let { put("dataVersion", it) }; file?.let { put("file", it) }; summary?.let { put("summary", it) }
        })
        while (arr.length() > 50) arr.remove(0)
        history.writeText(arr.toString())
    }

    companion object {
        const val BUNDLED_ASSET = "champions_data.json"
        private const val MAX_BYTES = 50L * 1024 * 1024

        /** Returns the JSON bytes; if the stream is a zip, the first .json entry inside it. */
        fun readJsonBytes(input: InputStream): ByteArray {
            val buffered = input.buffered()
            buffered.mark(4)
            val magic = ByteArray(2).also { buffered.read(it) }
            buffered.reset()
            if (magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte()) {
                ZipInputStream(buffered).use { zip ->
                    while (true) {
                        val e = zip.nextEntry ?: break
                        if (!e.isDirectory && e.name.endsWith(".json", ignoreCase = true)) return readLimited(zip)
                    }
                }
                throw IllegalArgumentException("壓縮檔內找不到 .json 資料檔")
            }
            return readLimited(buffered)
        }

        private fun readLimited(input: InputStream): ByteArray {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_BYTES) throw IllegalArgumentException("檔案太大")
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }
}
