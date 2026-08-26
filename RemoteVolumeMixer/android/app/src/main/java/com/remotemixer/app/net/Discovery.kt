package com.remotemixer.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * UDP LAN discovery. Broadcasts "RVMX_DISCOVER" on port 8766 and collects the
 * JSON answers from every Remote Volume Mixer server on the network.
 *
 * Manual IP entry always remains available; this is pure convenience.
 */
object Discovery {

    private const val PROBE = "RVMX_DISCOVER"
    private const val SERVICE = "remote-volume-mixer"
    const val DEFAULT_PORT = 8766

    data class Found(
        val name: String,
        val ip: String,
        val port: Int,
        val requiresPairing: Boolean,
        val version: String,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun scan(
        discoveryPort: Int = DEFAULT_PORT,
        timeoutMs: Int = 2500,
    ): List<Found> = withContext(Dispatchers.IO) {
        val results = LinkedHashMap<String, Found>()
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket().apply {
                broadcast = true
                soTimeout = 400
            }
            val payload = PROBE.toByteArray()
            for (addr in broadcastAddresses()) {
                runCatching {
                    socket.send(
                        DatagramPacket(payload, payload.size, InetSocketAddress(addr, discoveryPort))
                    )
                }
            }
            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(2048)
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                }
                val body = String(packet.data, 0, packet.length)
                if (PROBE in body) continue          // our own broadcast echo
                parse(body, packet.address.hostAddress)?.let { results[it.ip] = it }
            }
        } catch (_: Exception) {
            // discovery is best effort, never surface an error for it
        } finally {
            runCatching { socket?.close() }
        }
        results.values.toList()
    }

    private fun parse(body: String, fallbackIp: String?): Found? = runCatching {
        val obj = json.parseToJsonElement(body).jsonObject
        if (obj["service"]?.jsonPrimitive?.contentOrNull != SERVICE) return@runCatching null
        Found(
            name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "PC",
            ip = obj["ip"]?.jsonPrimitive?.contentOrNull ?: fallbackIp ?: return@runCatching null,
            port = obj["port"]?.jsonPrimitive?.intOrNull ?: 8765,
            requiresPairing = obj["requires_pairing"]?.jsonPrimitive?.contentOrNull == "true",
            version = obj["version"]?.jsonPrimitive?.contentOrNull ?: "?",
        )
    }.getOrNull()

    private fun broadcastAddresses(): List<InetAddress> {
        val out = mutableListOf<InetAddress>()
        runCatching { out.add(InetAddress.getByName("255.255.255.255")) }
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses }
                .mapNotNull { it.broadcast }
                .forEach { out.add(it) }
        }
        return out.distinctBy { it.hostAddress }
    }
}
