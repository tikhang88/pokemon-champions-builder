package hk.tikhang.champions.data

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import hk.tikhang.champions.core.ImportCheck
import hk.tikhang.champions.core.Regulation
import hk.tikhang.champions.core.RegulationEngine
import hk.tikhang.champions.core.Snapshot
import hk.tikhang.champions.core.TypeEngine
import kotlinx.coroutines.Dispatchers
import hk.tikhang.champions.core.BattleFormat
import hk.tikhang.champions.core.Team
import hk.tikhang.champions.core.TeamTextImporter
import hk.tikhang.champions.core.RemoteVersion
import hk.tikhang.champions.core.SnapshotImporter
import hk.tikhang.champions.core.TeamImpact
import hk.tikhang.champions.core.UpdatePolicy
import hk.tikhang.champions.core.UpdateStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

data class DataState(
    val loaded: SnapshotStore.Loaded,
    val typeEngine: TypeEngine,
    val regulation: Regulation,
    val regulationIsManual: Boolean,
    val needsUpdateFor: Regulation?,
) {
    val snapshot: Snapshot get() = loaded.snapshot
}

sealed class ImportUi {
    data object Idle : ImportUi()
    data object Checking : ImportUi()
    data class Ready(val check: ImportCheck, val bytes: ByteArray?, val fileName: String?) : ImportUi()
    data class Done(val message: String, val success: Boolean, val details: List<String> = emptyList()) : ImportUi()
}

/** Online update check state (spec §14.4 Offline Status: Online / Offline / Data Available / Update Available). */
sealed class UpdateUi {
    data object Idle : UpdateUi()
    data object Checking : UpdateUi()
    data class UpToDate(val time: Long) : UpdateUi()
    data class Available(val remote: RemoteVersion, val summary: List<String>) : UpdateUi()
    data class TooOld(val remote: RemoteVersion) : UpdateUi()
    data class Failed(val message: String) : UpdateUi()
}

enum class AutoUpdate(val zh: String) { OFF("關閉"), CHECK("自動檢查並提示"), APPLY("自動下載並套用") }

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SnapshotStore(app)
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _data = MutableStateFlow<DataState?>(null)
    val data: StateFlow<DataState?> = _data.asStateFlow()

    private val _online = MutableStateFlow(false)
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private val _import = MutableStateFlow<ImportUi>(ImportUi.Idle)
    val import: StateFlow<ImportUi> = _import.asStateFlow()

    private val _update = MutableStateFlow<UpdateUi>(UpdateUi.Idle)
    val update: StateFlow<UpdateUi> = _update.asStateFlow()

    var autoUpdate: AutoUpdate
        get() = runCatching { AutoUpdate.valueOf(prefs.getString(KEY_AUTO, AutoUpdate.CHECK.name)!!) }.getOrDefault(AutoUpdate.CHECK)
        set(v) { prefs.edit().putString(KEY_AUTO, v.name).apply() }
    val lastCheck: Long get() = prefs.getLong(KEY_LAST_CHECK, 0L)

    private val _startupWarning = MutableStateFlow<String?>(null)
    val startupWarning: StateFlow<String?> = _startupWarning.asStateFlow()

    // ---- Teams (Phase 2) ---------------------------------------------------------------
    private val teamDao = AppDatabase.get(app).teams()
    val teams: StateFlow<List<TeamEntity>> = teamDao.all().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun createTeam(opponent: Boolean = false, onCreated: (Long) -> Unit) {
        val reg = _data.value?.regulation?.id ?: return
        viewModelScope.launch {
            val n = teams.value.count { it.team()?.opponent == opponent } + 1
            val team = Team(name = (if (opponent) "對手隊伍 " else "隊伍 ") + n, regulationId = reg, format = BattleFormat.DOUBLES, opponent = opponent)
            val now = System.currentTimeMillis()
            val id = withContext(Dispatchers.IO) { teamDao.insert(TeamEntity(name = team.name, json = team.toJson(), createdAt = now, updatedAt = now)) }
            onCreated(id)
        }
    }

    /** Imports Showdown/PokePaste text as a new team. Returns the import result for display. */
    fun importTeam(text: String, name: String, code: String, opponent: Boolean, onDone: (TeamTextImporter.Result, Long?) -> Unit) {
        val st = _data.value ?: return
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                TeamTextImporter.parse(st.snapshot, text, name.ifBlank { "匯入的隊伍" }, st.regulation.id, code, opponent)
            }
            var id: Long? = null
            if (result.imported > 0) {
                val now = System.currentTimeMillis()
                id = withContext(Dispatchers.IO) { teamDao.insert(TeamEntity(name = result.team.name, json = result.team.toJson(), createdAt = now, updatedAt = now)) }
            }
            onDone(result, id)
        }
    }

    fun saveTeam(id: Long, team: Team) {
        viewModelScope.launch(Dispatchers.IO) {
            val old = teamDao.get(id) ?: return@launch
            teamDao.update(old.copy(name = team.name, json = team.toJson(), updatedAt = System.currentTimeMillis()))
        }
    }

    fun duplicateTeam(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val old = teamDao.get(id) ?: return@launch
            val team = old.team()?.let { it.copy(name = it.name + "（複製）") } ?: return@launch
            val now = System.currentTimeMillis()
            teamDao.insert(TeamEntity(name = team.name, json = team.toJson(), createdAt = now, updatedAt = now))
        }
    }

    fun deleteTeam(id: Long) {
        viewModelScope.launch(Dispatchers.IO) { teamDao.delete(id) }
    }

    val hasPrevious: Boolean get() = store.hasPrevious
    val hasImported: Boolean get() = store.hasImported
    fun history() = store.history()

    init {
        reload(autoCheck = true)
        watchNetwork()
    }

    private fun reload(autoCheck: Boolean = false) {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { store.load() }
            loaded.warning?.let { _startupWarning.value = it }
            _data.value = buildState(loaded)
            // Automatic update on start (spec §4.4): only when online; offline use is unaffected.
            if (autoCheck && autoUpdate != AutoUpdate.OFF && _online.value) checkOnline(manual = false)
        }
    }

    /** Check GitHub for newer data. Manual checks report every result; automatic ones stay quiet unless there is news. */
    fun checkOnline(manual: Boolean = true) {
        val current = _data.value?.snapshot ?: return
        if (!_online.value) { if (manual) _update.value = UpdateUi.Failed("目前離線，未能檢查更新。所有功能仍可離線使用。"); return }
        _update.value = UpdateUi.Checking
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { OnlineUpdater.fetchVersion() } }
            prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
            val remote = result.getOrElse { e ->
                _update.value = if (manual) UpdateUi.Failed("檢查失敗：${e.message ?: e.javaClass.simpleName}") else UpdateUi.Idle
                return@launch
            }
            when (UpdatePolicy.status(current.meta, remote)) {
                UpdateStatus.UP_TO_DATE -> _update.value = UpdateUi.UpToDate(System.currentTimeMillis())
                UpdateStatus.APP_TOO_OLD -> _update.value = UpdateUi.TooOld(remote)
                UpdateStatus.AVAILABLE -> {
                    _update.value = UpdateUi.Available(remote, UpdatePolicy.describe(current, remote))
                    if (!manual && autoUpdate == AutoUpdate.APPLY) downloadUpdate(autoApply = true)
                }
            }
        }
    }

    /** Download the available update, verify it, then preview it (or apply it directly when [autoApply]). */
    fun downloadUpdate(autoApply: Boolean = false) {
        val avail = _update.value as? UpdateUi.Available ?: return
        if (!autoApply) _import.value = ImportUi.Checking
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) { runCatching { OnlineUpdater.fetchSnapshot(avail.remote) } }.getOrElse { e ->
                _import.value = ImportUi.Done("下載失敗，原有資料保持不變：${e.message}", false); return@launch
            }
            val check = withContext(Dispatchers.Default) { SnapshotImporter.check(bytes, _data.value?.snapshot) }
            _import.value = ImportUi.Ready(check, bytes, "線上更新 ${avail.remote.dataVersion}")
            if (autoApply) {
                if ((check as? ImportCheck.Ok)?.canImport == true) confirmImport()
                else _import.value = ImportUi.Done("自動更新未套用：資料檢查不通過，原有資料保持不變", false)
            }
        }
    }

    private fun buildState(loaded: SnapshotStore.Loaded): DataState {
        val s = loaded.snapshot
        val now = Instant.now()
        val default = RegulationEngine.defaultRegulation(s, now)
        val manualId = prefs.getString(KEY_REGULATION, null)
        val manual = manualId?.let { s.regulationById[it] }?.takeIf { it.hasPokemonList }
        return DataState(
            loaded = loaded,
            typeEngine = TypeEngine(s.typeChart, s.types),
            regulation = manual ?: default,
            regulationIsManual = manual != null,
            needsUpdateFor = RegulationEngine.needsDataUpdate(s, now),
        )
    }

    /** Apply a Regulation to the whole app; [id] = null follows the current Regulation automatically. */
    fun selectRegulation(id: String?) {
        prefs.edit().apply { if (id == null) remove(KEY_REGULATION) else putString(KEY_REGULATION, id) }.apply()
        _data.value?.let { _data.value = buildState(it.loaded) }
    }

    fun checkImport(uri: Uri) {
        _import.value = ImportUi.Checking
        viewModelScope.launch {
            val name = withContext(Dispatchers.IO) { displayName(uri) }
            val (check, bytes) = withContext(Dispatchers.Default) { store.check(uri, _data.value?.snapshot) }
            _import.value = ImportUi.Ready(check, bytes, name)
        }
    }

    fun confirmImport() {
        val ready = _import.value as? ImportUi.Ready ?: return
        val ok = ready.check as? ImportCheck.Ok ?: return
        val bytes = ready.bytes ?: return
        val old = _data.value?.snapshot
        viewModelScope.launch {
            val err = withContext(Dispatchers.IO) { store.commit(bytes, ok, ready.fileName) }
            if (err == null) {
                _data.value = buildState(SnapshotStore.Loaded(ok.snapshot, SnapshotStore.Origin.IMPORTED, ok.sha256))
                if (_update.value is UpdateUi.Available) _update.value = UpdateUi.UpToDate(System.currentTimeMillis())
                // Spec §4.5: keep old teams, show which are affected by the new data.
                val details = mutableListOf<String>()
                ok.changes?.let { details += it.summary().lines() }
                if (old != null) {
                    if (old.meta.currentRegulation != ok.snapshot.meta.currentRegulation)
                        details += "Regulation 已更新：${old.meta.currentRegulation} → ${ok.snapshot.meta.currentRegulation}。舊隊伍保留原有 Regulation，可在隊伍頁切換。"
                    val impact = withContext(Dispatchers.Default) { TeamImpact.compute(old, ok.snapshot, teams.value.mapNotNull { it.team() }) }
                    details += if (impact.isEmpty()) "已儲存的隊伍沒有受影響" else listOf("受影響的隊伍：") + impact.map { "・" + it.text() }
                }
                _import.value = ImportUi.Done("已匯入資料版本 ${ok.snapshot.meta.dataVersion}", true, details)
            } else {
                _import.value = ImportUi.Done(err, false)
            }
        }
    }

    fun dismissImport() { _import.value = ImportUi.Idle }

    fun revertToPrevious() {
        viewModelScope.launch {
            val err = withContext(Dispatchers.IO) { store.revertToPrevious() }
            _import.value = ImportUi.Done(err ?: "已還原上一版本", err == null)
            reload()
        }
    }

    fun resetToBundled() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.resetToBundled() }
            _import.value = ImportUi.Done("已改用 App 內置資料", true)
            reload()
        }
    }

    fun clearStartupWarning() { _startupWarning.value = null }

    private fun displayName(uri: Uri): String? = runCatching {
        getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun watchNetwork() {
        val cm = getApplication<Application>().getSystemService(ConnectivityManager::class.java) ?: return
        fun refresh() {
            val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            _online.value = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }
        refresh()
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = refresh()
                override fun onLost(network: Network) { _online.update { false } }
                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = refresh()
            })
        }
    }

    companion object {
        private const val KEY_REGULATION = "regulation"
        private const val KEY_AUTO = "auto_update"
        private const val KEY_LAST_CHECK = "last_update_check"
    }
}
