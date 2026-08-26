package com.remotemixer.app

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.remotemixer.app.data.Prefs
import com.remotemixer.app.data.Protocol
import com.remotemixer.app.data.Protocol.AppSession
import com.remotemixer.app.net.Discovery
import com.remotemixer.app.net.MixerClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class Screen { Connection, Mixer, Diagnostics }

class MixerViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)
    val client = MixerClient(viewModelScope, deviceName = "${Build.MANUFACTURER} ${Build.MODEL}")

    // ---------------------------------------------------------------- screens
    private val _screen = MutableStateFlow(Screen.Connection)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    // -------------------------------------------------------- form / discovery
    private val _hostField = MutableStateFlow(prefs.lastHost.ifBlank { "192.168.1." })
    val hostField: StateFlow<String> = _hostField.asStateFlow()

    private val _portField = MutableStateFlow(prefs.lastPort.toString())
    val portField: StateFlow<String> = _portField.asStateFlow()

    private val _discovered = MutableStateFlow<List<Discovery.Found>>(emptyList())
    val discovered: StateFlow<List<Discovery.Found>> = _discovered.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    // ---------------------------------------------------------- list controls
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _sort = MutableStateFlow(prefs.sortMode)
    val sort: StateFlow<String> = _sort.asStateFlow()

    private val _favorites = MutableStateFlow(prefs.favorites)
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    /** Final list shown by the mixer: favourites first, then the chosen order. */
    val visibleApps: StateFlow<List<AppSession>> =
        combine(client.apps, _query, _sort, _favorites) { apps, q, sort, favs ->
            val filtered = if (q.isBlank()) apps else apps.filter {
                it.displayName.contains(q, true) || it.processName.contains(q, true)
            }
            val comparator = when (sort) {
                Prefs.SORT_NAME -> compareBy<AppSession> { it.displayName.lowercase() }
                Prefs.SORT_VOLUME -> compareByDescending<AppSession> { it.volume }
                else -> compareByDescending<AppSession> { it.lastActive }
            }
            filtered.sortedWith(
                compareByDescending<AppSession> { isFavorite(it, favs) }.then(comparator)
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // React to connection status: persist server, jump between screens.
        viewModelScope.launch {
            client.connection.collect { st ->
                if (st.status == MixerClient.Status.Connected) {
                    prefs.lastHost = st.host
                    prefs.lastPort = st.port
                    prefs.lastPcName = st.pcName
                    client.currentToken?.let { prefs.saveToken(st.host, it) }
                    if (_screen.value == Screen.Connection) _screen.value = Screen.Mixer
                }
            }
        }
        viewModelScope.launch {
            client.events.collect { ev ->
                when (ev) {
                    is MixerClient.Event.Toast -> _toast.value = ev.text
                    MixerClient.Event.PairFailed -> _toast.value = "Wrong pairing code"
                    MixerClient.Event.Paired -> _toast.value = "Paired with this PC"
                }
            }
        }
        // Auto reconnect to the last PC, plus a discovery sweep in parallel.
        if (prefs.autoConnect && prefs.lastHost.isNotBlank()) {
            connect(prefs.lastHost, prefs.lastPort, prefs.lastPcName)
        }
        scan()
    }

    // ------------------------------------------------------------------ form
    fun onHostChange(v: String) { _hostField.value = v.filter { it.isDigit() || it == '.' } }
    fun onPortChange(v: String) { _portField.value = v.filter { it.isDigit() }.take(5) }

    fun connectFromForm() {
        val port = _portField.value.toIntOrNull() ?: 8765
        connect(_hostField.value.trim(), port, prefs.lastPcName)
    }

    fun connect(host: String, port: Int, pcName: String = "") {
        _hostField.value = host
        _portField.value = port.toString()
        client.connect(host, port, prefs.token(host), pcName)
    }

    fun disconnect() {
        client.disconnect()
        _screen.value = Screen.Connection
        scan()
    }

    fun submitPairingCode(code: String) = client.submitPairingCode(code)

    fun scan() {
        if (_scanning.value) return
        viewModelScope.launch {
            _scanning.value = true
            _discovered.value = Discovery.scan()
            _scanning.value = false
        }
    }

    // ----------------------------------------------------------- interactions
    fun onVolumeDrag(id: String, volume: Int) = client.setVolume(id, volume, finalValue = false)
    fun onVolumeCommit(id: String, volume: Int) = client.setVolume(id, volume, finalValue = true)
    fun toggleMute(id: String, currentlyMuted: Boolean) = client.setMute(id, !currentlyMuted)
    fun refresh() = client.requestRefresh()

    fun onQueryChange(v: String) { _query.value = v }

    fun setSort(mode: String) {
        _sort.value = mode
        prefs.sortMode = mode
    }

    fun toggleFavorite(app: AppSession) {
        _favorites.value = prefs.toggleFavorite(app.processName)
    }

    fun isFavorite(app: AppSession, favs: Set<String> = _favorites.value): Boolean =
        favs.contains(app.processName.lowercase())

    fun show(screen: Screen) { _screen.value = screen }

    fun consumeToast() { _toast.value = null }

    /** Absolute URL of a session icon served by the PC, or null. */
    fun iconUrl(app: AppSession): String? {
        val path = app.icon ?: return null
        val st = client.connection.value
        if (st.host.isBlank()) return null
        return if (path.startsWith("http")) path else st.baseUrl + path
    }

    val masterId: String get() = Protocol.MASTER_ID
}
