package com.remotevolumemixer.data

import android.content.Context
import android.os.Build
import android.util.Log
import com.remotevolumemixer.protocol.AppPayload
import com.remotevolumemixer.protocol.ClientHello
import com.remotevolumemixer.protocol.PcAck
import com.remotevolumemixer.protocol.PcApplicationAdded
import com.remotevolumemixer.protocol.PcApplicationRemoved
import com.remotevolumemixer.protocol.PcApplicationUpdated
import com.remotevolumemixer.protocol.PcError
import com.remotevolumemixer.protocol.PcHello
import com.remotevolumemixer.protocol.PcIcon
import com.remotevolumemixer.protocol.PcPong
import com.remotevolumemixer.protocol.PcSnapshot
import com.remotevolumemixer.protocol.PcVolumeChanged
import com.remotevolumemixer.protocol.Ping
import com.remotevolumemixer.protocol.Protocol
import com.remotevolumemixer.protocol.RequestIcon
import com.remotevolumemixer.protocol.RequestSnapshot
import com.remotevolumemixer.protocol.SetMute
import com.remotevolumemixer.protocol.SetVolume
import com.remotevolumemixer.transport.LinkState
import com.remotevolumemixer.transport.UsbBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Stato del mixer e regole di comunicazione.
 *
 * - aggiornamento ottimistico: lo slider risponde al dito, non alla latenza
 * - throttling per sessione: massimo un pacchetto ogni 40 ms, valore finale sempre inviato
 * - eco-suppression: le conferme del PC piu' vecchie dell'ultimo gesto non fanno saltare lo slider
 */
class MixerRepository(
    context: Context,
    private val scope: CoroutineScope
) {
    private companion object {
        const val TAG = "RVM/Repo"
        const val VOLUME_THROTTLE_MS = 40L
        const val ECHO_GUARD_MS = 450L
        const val PING_INTERVAL_MS = 3_000L
    }

    private val bridge = UsbBridge(scope)
    val iconStore = IconStore(context.applicationContext, scope)

    private val _state = MutableStateFlow(MixerState())
    val state: StateFlow<MixerState> = _state.asStateFlow()

    private val volumeChannels = ConcurrentHashMap<String, Channel<Int>>()
    private val lastLocalEdit = ConcurrentHashMap<String, Long>()
    private val requestIds = AtomicLong(0)

    private var started = false
    private var pingJob: Job? = null

    private val clientLabel: String = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})"

    @Synchronized
    fun ensureStarted() {
        if (started) return
        started = true
        observeIncoming()
        observeLink()
        bridge.start()
    }

    @Synchronized
    fun shutdown() {
        if (!started) return
        started = false
        pingJob?.cancel()
        pingJob = null
        volumeChannels.values.forEach { it.close() }
        volumeChannels.clear()
        bridge.stop()
        _state.value = _state.value.copy(connection = ConnectionState.Disconnected)
    }

    // ------------------------------------------------------------------ comandi

    fun setVolume(sessionId: String, volume: Int) {
        val clamped = volume.coerceIn(0, 100)
        val current = _state.value.apps.firstOrNull { it.sessionId == sessionId } ?: return
        if (!_state.value.isConnected) return

        // 1) UI immediata
        if (current.volume != clamped) {
            updateApp(sessionId) { it.copy(volume = clamped) }
        }

        // 2) invio throttlato verso il PC
        lastLocalEdit[sessionId] = now()
        channelFor(sessionId).trySend(clamped)
    }

    fun setMuted(sessionId: String, muted: Boolean) {
        if (!_state.value.isConnected) return
        updateApp(sessionId) { it.copy(muted = muted) }
        lastLocalEdit[sessionId] = now()
        bridge.send(SetMute(sessionId = sessionId, muted = muted, requestId = requestIds.incrementAndGet()))
    }

    /**
     * Invio immediato del valore finale quando il dito lascia lo slider:
     * garantisce che l'ultimo valore arrivi al PC anche se cade nel throttling.
     */
    fun finalizeVolume(sessionId: String) {
        if (!_state.value.isConnected) return
        val app = _state.value.apps.firstOrNull { it.sessionId == sessionId } ?: return
        lastLocalEdit[sessionId] = now()
        bridge.send(
            SetVolume(
                sessionId = sessionId,
                volume = app.volume,
                requestId = requestIds.incrementAndGet()
            )
        )
    }

    fun requestRefresh() {
        if (!_state.value.isConnected) return
        bridge.send(RequestSnapshot())
    }

    fun dismissNotice() {
        _state.value = _state.value.copy(notice = null)
    }

    // ------------------------------------------------------------------ interni

    private fun channelFor(sessionId: String): Channel<Int> =
        volumeChannels.getOrPut(sessionId) {
            Channel<Int>(Channel.CONFLATED).also { channel ->
                scope.launch {
                    for (value in channel) {
                        bridge.send(
                            SetVolume(
                                sessionId = sessionId,
                                volume = value,
                                requestId = requestIds.incrementAndGet()
                            )
                        )
                        delay(VOLUME_THROTTLE_MS)
                    }
                }
            }
        }

    private fun observeIncoming() {
        scope.launch {
            bridge.incoming.collect { message ->
                when (message) {
                    is PcHello -> onHello(message)
                    is PcSnapshot -> onSnapshot(message.applications)
                    is PcApplicationAdded -> onUpsert(message.application)
                    is PcApplicationUpdated -> onUpsert(message.application)
                    is PcApplicationRemoved -> onRemoved(message.sessionId)
                    is PcVolumeChanged -> onVolumeChanged(message)
                    is PcIcon -> iconStore.onIconReceived(message.iconKey, message.png)
                    is PcAck -> if (!message.ok) {
                        Log.w(TAG, "Request ${message.requestId} rejected: ${message.error}")
                    }
                    is PcPong -> Unit
                    is PcError -> onError(message)
                }
            }
        }
    }

    private fun observeLink() {
        scope.launch {
            bridge.linkState.collect { link ->
                when (link) {
                    LinkState.Connected -> Unit // si attende l'handshake
                    LinkState.Listening, LinkState.Idle -> {
                        pingJob?.cancel()
                        pingJob = null
                        if (_state.value.connection != ConnectionState.Disconnected) {
                            Log.i(TAG, "USB link lost")
                        }
                        _state.value = _state.value.copy(
                            connection = ConnectionState.Disconnected,
                            pcName = null
                        )
                    }
                }
            }
        }
    }

    private fun onHello(hello: PcHello) {
        val version = hello.protocolVersion
        if (version < Protocol.MIN_SUPPORTED_VERSION || version > Protocol.VERSION) {
            Log.e(TAG, "Protocol mismatch: PC speaks v$version, app speaks v${Protocol.VERSION}")
            _state.value = _state.value.copy(
                connection = ConnectionState.Incompatible,
                notice = "The Windows client uses a different protocol version. Update both sides to the same release."
            )
            return
        }

        bridge.send(ClientHello(client = clientLabel))
        iconStore.resetPendingRequests()
        _state.value = _state.value.copy(
            connection = ConnectionState.Connected,
            pcName = hello.host.ifBlank { "Windows PC" },
            notice = null
        )
        Log.i(TAG, "Handshake with ${hello.host} (client v${hello.appVersion}, protocol v$version)")
        startPing()
    }

    private fun startPing() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive) {
                delay(PING_INTERVAL_MS)
                bridge.send(Ping(nonce = now()))
            }
        }
    }

    private fun onSnapshot(applications: List<AppPayload>) {
        val apps = applications.map { it.toAudioApp() }
        _state.value = _state.value.copy(apps = apps)
        Log.i(TAG, "Snapshot received: ${apps.size} application(s)")
    }

    private fun onUpsert(payload: AppPayload) {
        val incoming = payload.toAudioApp()
        val apps = _state.value.apps
        val index = apps.indexOfFirst { it.sessionId == incoming.sessionId }
        _state.value = _state.value.copy(
            apps = if (index >= 0) {
                apps.toMutableList().also { list ->
                    // Il volume locale in corso di trascinamento non viene sovrascritto.
                    val keepLocal = isEchoGuarded(incoming.sessionId)
                    val previous = list[index]
                    list[index] = if (keepLocal) {
                        incoming.copy(volume = previous.volume, muted = previous.muted)
                    } else {
                        incoming
                    }
                }
            } else {
                apps + incoming
            }
        )
    }

    private fun onRemoved(sessionId: String) {
        volumeChannels.remove(sessionId)?.close()
        lastLocalEdit.remove(sessionId)
        _state.value = _state.value.copy(apps = _state.value.apps.filterNot { it.sessionId == sessionId })
    }

    private fun onVolumeChanged(message: PcVolumeChanged) {
        if (isEchoGuarded(message.sessionId)) {
            // Conferma di un valore piu' vecchio dell'ultimo gesto: ignorata.
            return
        }

        updateApp(message.sessionId) {
            it.copy(volume = message.volume.coerceIn(0, 100), muted = message.muted)
        }
    }

    private fun onError(error: PcError) {
        Log.w(TAG, "PC reported an error: ${error.code} ${error.message}")
        _state.value = _state.value.copy(
            connection = if (error.code == "protocol_version") {
                ConnectionState.Incompatible
            } else {
                _state.value.connection
            },
            notice = error.message.ifBlank { "The Windows client reported a problem." }
        )
    }

    private fun isEchoGuarded(sessionId: String): Boolean {
        val last = lastLocalEdit[sessionId] ?: return false
        return now() - last < ECHO_GUARD_MS
    }

    private inline fun updateApp(sessionId: String, transform: (AudioApp) -> AudioApp) {
        val apps = _state.value.apps
        val index = apps.indexOfFirst { it.sessionId == sessionId }
        if (index < 0) return
        _state.value = _state.value.copy(
            apps = apps.toMutableList().also { it[index] = transform(it[index]) }
        )
    }

    fun requestIcon(iconKey: String) {
        bridge.send(RequestIcon(iconKey = iconKey))
    }

    private fun now(): Long = System.currentTimeMillis()
}
