package com.remotemixer.app.net

import android.os.SystemClock
import com.remotemixer.app.data.Protocol
import com.remotemixer.app.data.Protocol.AppSession
import com.remotemixer.app.data.Protocol.Master
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Single source of truth for the connection to the Windows server.
 *
 * Responsibilities
 *  - one WebSocket, kept alive, with exponential-backoff auto reconnect
 *  - optimistic local slider state + throttled outbound set_volume frames
 *  - echo suppression so live updates never fight the finger on screen
 *  - latency measurement (ping/pong)
 */
class MixerClient(private val scope: CoroutineScope, private val deviceName: String) {

    enum class Status { Idle, Connecting, Connected, Reconnecting, PairingRequired, Failed }

    data class ConnectionState(
        val status: Status = Status.Idle,
        val host: String = "",
        val port: Int = 8765,
        val pcName: String = "",
        val serverVersion: String = "",
        val latencyMs: Int = -1,
        val lastMessageAt: Long = 0L,
        val message: String = "",
        val attempt: Int = 0,
    ) {
        val isConnected: Boolean get() = status == Status.Connected
        val baseUrl: String get() = "http://$host:$port"
    }

    sealed interface Event {
        data class Toast(val text: String) : Event
        data object PairFailed : Event
        data object Paired : Event
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)   // long lived socket
        .writeTimeout(5, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)      // keeps NAT/Wi-Fi alive
        .retryOnConnectionFailure(true)
        .build()

    private val _connection = MutableStateFlow(ConnectionState())
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _apps = MutableStateFlow<List<AppSession>>(emptyList())
    val apps: StateFlow<List<AppSession>> = _apps.asStateFlow()

    private val _master = MutableStateFlow(Master())
    val master: StateFlow<Master> = _master.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 8)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private var socket: WebSocket? = null
    private var wantConnection = false
    private var reconnectJob: Job? = null
    private var pingJob: Job? = null
    private var flushJob: Job? = null
    private var token: String? = null

    /** id -> latest value the user dragged to but that is not on the wire yet. */
    private val pending = ConcurrentHashMap<String, Int>()

    /** id -> uptime millis until which incoming updates for that id are ignored. */
    private val muteEchoUntil = ConcurrentHashMap<String, Long>()

    // ------------------------------------------------------------- lifecycle
    fun connect(host: String, port: Int, token: String?, pcName: String = "") {
        this.token = token
        wantConnection = true
        reconnectJob?.cancel()
        closeSocket()
        _connection.update {
            it.copy(
                status = Status.Connecting, host = host.trim(), port = port,
                pcName = pcName.ifBlank { it.pcName }, message = "", attempt = 0,
                latencyMs = -1,
            )
        }
        openSocket()
    }

    fun disconnect() {
        wantConnection = false
        reconnectJob?.cancel()
        pingJob?.cancel()
        flushJob?.cancel()
        closeSocket()
        _apps.value = emptyList()
        _master.value = Master()
        _connection.update {
            it.copy(status = Status.Idle, latencyMs = -1, message = "", attempt = 0)
        }
    }

    fun submitPairingCode(code: String) {
        socket?.send(Protocol.pair(code, deviceName))
    }

    fun requestRefresh() {
        socket?.send(Protocol.refresh())
    }

    private fun closeSocket() {
        socket?.let { runCatching { it.close(1000, "bye") } }
        socket = null
    }

    private fun openSocket() {
        val st = _connection.value
        if (st.host.isBlank()) {
            _connection.update { it.copy(status = Status.Failed, message = "Enter the PC IP address") }
            return
        }
        val url = "ws://${st.host}:${st.port}/ws"
        val request = Request.Builder().url(url).build()
        socket = http.newWebSocket(request, listener)
    }

    // -------------------------------------------------------------- listener
    private val listener = object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            _connection.update { it.copy(status = Status.Connecting, message = "Authenticating…") }
            startPings()
            startFlusher()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = Protocol.parse(text) ?: return
            _connection.update { it.copy(lastMessageAt = System.currentTimeMillis()) }
            when (msg) {
                is Protocol.Incoming.Hello -> {
                    _connection.update {
                        it.copy(pcName = msg.hostname, serverVersion = msg.version)
                    }
                    webSocket.send(Protocol.auth(token, deviceName))
                }

                Protocol.Incoming.AuthOk -> onAuthenticated(webSocket)

                Protocol.Incoming.AuthRequired -> _connection.update {
                    it.copy(status = Status.PairingRequired, message = "Enter the pairing code shown on the PC")
                }

                is Protocol.Incoming.Paired -> {
                    token = msg.token
                    _events.tryEmit(Event.Paired)
                    onAuthenticated(webSocket)
                }

                is Protocol.Incoming.PairFailed -> {
                    _connection.update { it.copy(status = Status.PairingRequired, message = msg.message) }
                    _events.tryEmit(Event.PairFailed)
                }

                is Protocol.Incoming.Apps -> {
                    _apps.value = mergeKeepingDrags(msg.apps)
                    msg.master?.let { m -> if (!isEchoSuppressed(Protocol.MASTER_ID)) _master.value = m }
                }

                is Protocol.Incoming.VolumeUpdate -> applyUpdate(msg.id, msg.volume, msg.muted)

                is Protocol.Incoming.VolumeChanged -> { /* ack, state already optimistic */ }

                is Protocol.Incoming.MuteChanged -> applyMute(msg.id, msg.muted)

                is Protocol.Incoming.Pong -> {
                    val rtt = (System.currentTimeMillis() - msg.clientTime).toInt()
                    if (rtt in 0..10_000) _connection.update { it.copy(latencyMs = rtt) }
                }

                is Protocol.Incoming.Error -> {
                    if (msg.code == "session_gone") requestRefresh()
                    else _events.tryEmit(Event.Toast(msg.message))
                }

                is Protocol.Incoming.Icon -> Unit
                is Protocol.Incoming.Unknown -> Unit
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            scheduleReconnect(t.message ?: "Connection failed")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            scheduleReconnect("PC disconnected")
        }
    }

    private fun onAuthenticated(webSocket: WebSocket) {
        _connection.update {
            it.copy(status = Status.Connected, message = "", attempt = 0)
        }
        webSocket.send(Protocol.getApps())
    }

    val currentToken: String? get() = token

    // ------------------------------------------------------------- reconnect
    private fun scheduleReconnect(reason: String) {
        pingJob?.cancel()
        flushJob?.cancel()
        socket = null
        if (!wantConnection) {
            _connection.update { it.copy(status = Status.Idle) }
            return
        }
        if (reconnectJob?.isActive == true) return
        _apps.value = emptyList()
        reconnectJob = scope.launch {
            var attempt = _connection.value.attempt
            while (wantConnection && isActive) {
                attempt++
                val firstTry = attempt == 1
                _connection.update {
                    it.copy(
                        status = if (firstTry) Status.Failed else Status.Reconnecting,
                        message = if (firstTry) reason else "Retrying…",
                        attempt = attempt,
                        latencyMs = -1,
                    )
                }
                val backoff = when {
                    attempt <= 1 -> 1_000L
                    attempt <= 3 -> 2_000L
                    attempt <= 6 -> 4_000L
                    else -> 8_000L
                }
                delay(backoff)
                if (!wantConnection) return@launch
                _connection.update { it.copy(status = Status.Reconnecting, message = "Retrying…") }
                openSocket()
                // give this attempt a chance; if it fails onFailure lands here again
                var waited = 0L
                while (waited < backoff + 4_000L && isActive) {
                    delay(200)
                    waited += 200
                    if (_connection.value.status == Status.Connected ||
                        _connection.value.status == Status.PairingRequired
                    ) return@launch
                }
            }
        }
    }

    private fun startPings() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive) {
                socket?.send(Protocol.ping(System.currentTimeMillis()))
                delay(3_000)
            }
        }
    }

    // ------------------------------------------------- outbound throttling
    /**
     * Called for every pixel of drag. State updates immediately (smooth UI),
     * but at most one frame per [FLUSH_MS] per app id reaches the network.
     * [finalValue] = true when the finger is lifted -> always sent.
     */
    fun setVolume(id: String, volume: Int, finalValue: Boolean) {
        val v = volume.coerceIn(0, 100)
        suppressEcho(id)
        if (id == Protocol.MASTER_ID) {
            _master.update { it.copy(volume = v) }
        } else {
            _apps.update { list -> list.map { if (it.id == id) it.copy(volume = v) else it } }
        }
        if (finalValue) {
            pending.remove(id)
            socket?.send(Protocol.setVolume(id, v))
        } else {
            pending[id] = v
        }
    }

    private fun startFlusher() {
        flushJob?.cancel()
        flushJob = scope.launch {
            while (isActive) {
                if (pending.isNotEmpty()) {
                    val ws = socket
                    val keys = pending.keys.toList()
                    for (k in keys) {
                        val v = pending.remove(k) ?: continue
                        ws?.send(Protocol.setVolume(k, v))
                    }
                }
                delay(FLUSH_MS)
            }
        }
    }

    fun setMute(id: String, muted: Boolean) {
        suppressEcho(id)
        applyMute(id, muted)
        socket?.send(Protocol.setMute(id, muted))
    }

    // ------------------------------------------------------ incoming merge
    private fun suppressEcho(id: String) {
        muteEchoUntil[id] = SystemClock.uptimeMillis() + ECHO_MS
    }

    private fun isEchoSuppressed(id: String): Boolean =
        (muteEchoUntil[id] ?: 0L) > SystemClock.uptimeMillis()

    /** Never clobber a value the user is dragging right now. */
    private fun mergeKeepingDrags(incoming: List<AppSession>): List<AppSession> =
        incoming.map { fresh ->
            if (isEchoSuppressed(fresh.id)) {
                val local = _apps.value.firstOrNull { it.id == fresh.id }
                if (local != null) fresh.copy(volume = local.volume, muted = local.muted) else fresh
            } else fresh
        }

    private fun applyUpdate(id: String, volume: Int, muted: Boolean) {
        if (isEchoSuppressed(id)) return
        if (id == Protocol.MASTER_ID) {
            val cur = _master.value
            if (cur.volume != volume || cur.muted != muted) {
                _master.value = Master(volume, muted)
            }
            return
        }
        _apps.update { list ->
            var changed = false
            val out = list.map {
                if (it.id == id && (it.volume != volume || it.muted != muted)) {
                    changed = true
                    it.copy(volume = volume, muted = muted, lastActive = System.currentTimeMillis())
                } else it
            }
            if (changed) out else list        // no change -> no recomposition
        }
    }

    private fun applyMute(id: String, muted: Boolean) {
        if (id == Protocol.MASTER_ID) {
            _master.update { it.copy(muted = muted) }
            return
        }
        _apps.update { list -> list.map { if (it.id == id) it.copy(muted = muted) else it } }
    }

    companion object {
        private const val FLUSH_MS = 60L      // max ~16 volume frames/second/app
        private const val ECHO_MS = 700L      // ignore server echo right after a local change
    }
}
