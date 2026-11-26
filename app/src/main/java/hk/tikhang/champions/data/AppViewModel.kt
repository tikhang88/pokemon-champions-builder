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
import kotlinx.coroutines.flow.MutableStateFlow
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
    data class Done(val message: String, val success: Boolean) : ImportUi()
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SnapshotStore(app)
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _data = MutableStateFlow<DataState?>(null)
    val data: StateFlow<DataState?> = _data.asStateFlow()

    private val _online = MutableStateFlow(false)
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private val _import = MutableStateFlow<ImportUi>(ImportUi.Idle)
    val import: StateFlow<ImportUi> = _import.asStateFlow()

    private val _startupWarning = MutableStateFlow<String?>(null)
    val startupWarning: StateFlow<String?> = _startupWarning.asStateFlow()

    val hasPrevious: Boolean get() = store.hasPrevious
    val hasImported: Boolean get() = store.hasImported
    fun history() = store.history()

    init {
        reload()
        watchNetwork()
    }

    private fun reload() {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { store.load() }
            loaded.warning?.let { _startupWarning.value = it }
            _data.value = buildState(loaded)
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
        viewModelScope.launch {
            val err = withContext(Dispatchers.IO) { store.commit(bytes, ok, ready.fileName) }
            if (err == null) {
                _data.value = buildState(SnapshotStore.Loaded(ok.snapshot, SnapshotStore.Origin.IMPORTED, ok.sha256))
                _import.value = ImportUi.Done("已匯入資料版本 ${ok.snapshot.meta.dataVersion}", true)
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
    }
}
