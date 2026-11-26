package hk.tikhang.champions.data

import hk.tikhang.champions.core.RemoteVersion
import hk.tikhang.champions.core.UpdatePolicy
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Online data update (spec §4.4, §14.3). The "Update data" workflow publishes the snapshot and
 * version.json in the public repository; the app reads them over HTTPS. Nothing is written
 * here: the caller verifies and imports through [SnapshotStore], so a failed download never
 * touches the data in use.
 */
object OnlineUpdater {
    const val BASE_URL = "https://raw.githubusercontent.com/tikhang88/pokemon-champions-builder/main/data/"

    fun fetchVersion(): RemoteVersion = RemoteVersion.parse(get(BASE_URL + "version.json", 64 * 1024).toString(Charsets.UTF_8))

    /** Downloads the snapshot named in [v] and checks its size and SHA-256. */
    fun fetchSnapshot(v: RemoteVersion): ByteArray {
        val bytes = get(BASE_URL + v.file, UpdatePolicy.MAX_BYTES)
        UpdatePolicy.verify(bytes, v)?.let { throw IllegalStateException(it) }
        return bytes
    }

    private fun get(url: String, limit: Long): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("Cache-Control", "no-cache")
        c.setRequestProperty("User-Agent", "PokemonChampionBuilder-Android")
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("伺服器回應 ${c.responseCode}")
            c.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > limit) throw IllegalStateException("檔案太大")
                    out.write(buf, 0, n)
                }
                return out.toByteArray()
            }
        } finally {
            c.disconnect()
        }
    }
}
