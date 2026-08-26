package com.remotevolumemixer.transport

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
import com.remotevolumemixer.protocol.PcMessage
import com.remotevolumemixer.protocol.PhoneMessage
import com.remotevolumemixer.protocol.Protocol
import com.remotevolumemixer.protocol.ProtocolCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InterruptedIOException
import kotlin.coroutines.coroutineContext

enum class LinkState {
    /** Socket non ancora in ascolto. */
    Idle,

    /** In ascolto: il cavo/PC non ha ancora aperto il canale. */
    Listening,

    /** Il client Windows e' collegato via USB. */
    Connected
}

/**
 * Il lato telefono del cavo.
 *
 * Il telefono pubblica un socket unix nel namespace astratto di Linux; il client
 * Windows ci si collega attraverso `adb forward`, cioe' attraverso il cavo USB.
 * Non esiste alcun socket di rete, alcun IP del telefono, alcun server HTTP:
 * se il cavo non c'e', non c'e' canale.
 */
class UsbBridge(private val scope: CoroutineScope) {

    private companion object {
        const val TAG = "RVM/Bridge"
        const val READ_TIMEOUT_MS = 12_000
        const val RETRY_DELAY_MS = 1_000L
    }

    private val _incoming = MutableSharedFlow<PcMessage>(
        replay = 0,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val incoming: SharedFlow<PcMessage> = _incoming.asSharedFlow()

    private val _linkState = MutableStateFlow(LinkState.Idle)
    val linkState: StateFlow<LinkState> = _linkState.asStateFlow()

    @Volatile
    private var serverSocket: LocalServerSocket? = null

    @Volatile
    private var clientSocket: LocalSocket? = null

    @Volatile
    private var outbox: Channel<String>? = null

    private var serverJob: Job? = null

    @Synchronized
    fun start() {
        if (serverJob != null) return
        serverJob = scope.launch(Dispatchers.IO) { serverLoop() }
        Log.i(TAG, "USB bridge starting on localabstract:${Protocol.SOCKET_NAME}")
    }

    @Synchronized
    fun stop() {
        val job = serverJob ?: return
        serverJob = null
        closeClient()
        closeServer()
        job.cancel()
        _linkState.value = LinkState.Idle
        Log.i(TAG, "USB bridge stopped")
    }

    /** Accoda un messaggio: se il cavo non e' collegato viene semplicemente ignorato. */
    fun send(message: PhoneMessage) {
        val channel = outbox ?: return
        val encoded = runCatching { ProtocolCodec.encode(message) }.getOrNull() ?: return
        channel.trySend(encoded)
    }

    /** Chiude la sessione corrente: il PC si ricollega da solo. */
    fun dropConnection() {
        Log.i(TAG, "Dropping the current USB session")
        closeClient()
    }

    private suspend fun serverLoop() {
        while (isRunning()) {
            try {
                val server = LocalServerSocket(Protocol.SOCKET_NAME)
                serverSocket = server
                _linkState.value = LinkState.Listening
                Log.i(TAG, "Listening for the Windows client")

                while (isRunning()) {
                    val socket = server.accept()
                    handleClient(socket)
                    if (isRunning()) {
                        _linkState.value = LinkState.Listening
                    }
                }
            } catch (io: IOException) {
                if (!isRunning()) return
                Log.w(TAG, "Socket loop error: ${io.message}")
                _linkState.value = LinkState.Listening
                delay(RETRY_DELAY_MS)
            } catch (t: Throwable) {
                if (!isRunning()) return
                Log.e(TAG, "Unexpected bridge failure", t)
                delay(RETRY_DELAY_MS)
            } finally {
                closeServer()
            }
        }
    }

    private suspend fun handleClient(socket: LocalSocket) = coroutineScope {
        clientSocket = socket
        val channel = Channel<String>(capacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        outbox = channel
        _linkState.value = LinkState.Connected
        Log.i(TAG, "Windows client connected over USB")

        val writerJob = launch(Dispatchers.IO) {
            runCatching {
                val writer = socket.outputStream.bufferedWriter()
                for (line in channel) {
                    writer.write(line)
                    writer.write("\n")
                    writer.flush()
                }
            }.onFailure { Log.d(TAG, "Writer stopped: ${it.message}") }
        }

        try {
            withContext(Dispatchers.IO) {
                runCatching { socket.soTimeout = READ_TIMEOUT_MS }
                val reader = socket.inputStream.bufferedReader()
                while (true) {
                    val line = reader.readLine() ?: break
                    val message = ProtocolCodec.decode(line) ?: continue
                    _incoming.tryEmit(message)
                }
            }
        } catch (timeout: InterruptedIOException) {
            Log.w(TAG, "No data from the PC for ${READ_TIMEOUT_MS / 1000}s, closing the session")
        } catch (io: IOException) {
            Log.d(TAG, "Session closed: ${io.message}")
        } finally {
            channel.close()
            runCatching { writerJob.cancelAndJoin() }
            outbox = null
            clientSocket = null
            closeSocket(socket)
            _linkState.value = LinkState.Listening
            Log.i(TAG, "USB session ended")
        }
    }

    /** true finche' il bridge non e' stato fermato e la coroutine e' viva. */
    private suspend fun isRunning(): Boolean = coroutineContext.isActive && serverJob != null

    private fun closeServer() {
        val server = serverSocket ?: return
        serverSocket = null
        runCatching { server.close() }
    }

    private fun closeClient() {
        val socket = clientSocket ?: return
        closeSocket(socket)
    }

    private fun closeSocket(socket: LocalSocket) {
        runCatching { socket.shutdownInput() }
        runCatching { socket.shutdownOutput() }
        runCatching { socket.close() }
    }
}
