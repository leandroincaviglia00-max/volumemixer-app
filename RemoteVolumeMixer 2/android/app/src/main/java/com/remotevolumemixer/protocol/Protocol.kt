package com.remotevolumemixer.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Protocollo USB, versione 1.
 *
 * Trasporto: una riga di testo = un messaggio JSON (NDJSON) su socket unix
 * astratto, esposto al PC da `adb forward ... localabstract:remotevolumemixer`.
 * Semplice da leggere a mano, robusto (una riga corrotta non uccide la sessione),
 * bidirezionale e versionato tramite il campo "v".
 */
object Protocol {
    const val VERSION = 1
    const val MIN_SUPPORTED_VERSION = 1

    /** Nome del socket nel namespace astratto: deve combaciare con il client Windows. */
    const val SOCKET_NAME = "remotevolumemixer"

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
        classDiscriminator = "type"
        isLenient = true
    }
}

/** Payload di una singola applicazione audio di Windows. */
@Serializable
data class AppPayload(
    val sessionId: String = "",
    val name: String = "",
    val processName: String = "",
    val pid: Int = 0,
    val volume: Int = 0,
    val muted: Boolean = false,
    val state: String = "inactive",
    val isSystemSounds: Boolean = false,
    val isMaster: Boolean = false,
    val iconKey: String = ""
)

// --------------------------------------------------------------- PC -> telefono

@Serializable
sealed interface PcMessage

@Serializable
@SerialName("hello")
data class PcHello(
    @SerialName("v") val protocolVersion: Int = 0,
    val host: String = "",
    val appVersion: String = "",
    val minProtocolVersion: Int = 1
) : PcMessage

@Serializable
@SerialName("snapshot")
data class PcSnapshot(
    val applications: List<AppPayload> = emptyList()
) : PcMessage

@Serializable
@SerialName("app_added")
data class PcApplicationAdded(val application: AppPayload) : PcMessage

@Serializable
@SerialName("app_updated")
data class PcApplicationUpdated(val application: AppPayload) : PcMessage

@Serializable
@SerialName("app_removed")
data class PcApplicationRemoved(val sessionId: String) : PcMessage

@Serializable
@SerialName("volume_changed")
data class PcVolumeChanged(
    val sessionId: String,
    val volume: Int,
    val muted: Boolean = false
) : PcMessage

@Serializable
@SerialName("icon")
data class PcIcon(
    val iconKey: String,
    val png: String? = null
) : PcMessage

@Serializable
@SerialName("ack")
data class PcAck(
    val requestId: Long = 0,
    val ok: Boolean = true,
    val error: String? = null
) : PcMessage

@Serializable
@SerialName("pong")
data class PcPong(val nonce: Long = 0) : PcMessage

@Serializable
@SerialName("error")
data class PcError(
    val code: String = "",
    val message: String = ""
) : PcMessage

// --------------------------------------------------------------- telefono -> PC

@Serializable
sealed interface PhoneMessage

@Serializable
@SerialName("client_hello")
data class ClientHello(
    val client: String = "",
    @SerialName("v") val protocolVersion: Int = Protocol.VERSION
) : PhoneMessage

@Serializable
@SerialName("set_volume")
data class SetVolume(
    val sessionId: String,
    val volume: Int,
    val requestId: Long,
    @SerialName("v") val protocolVersion: Int = Protocol.VERSION
) : PhoneMessage

@Serializable
@SerialName("set_mute")
data class SetMute(
    val sessionId: String,
    val muted: Boolean,
    val requestId: Long,
    @SerialName("v") val protocolVersion: Int = Protocol.VERSION
) : PhoneMessage

@Serializable
@SerialName("request_snapshot")
data class RequestSnapshot(
    @SerialName("v") val protocolVersion: Int = Protocol.VERSION
) : PhoneMessage

@Serializable
@SerialName("request_icon")
data class RequestIcon(
    val iconKey: String,
    @SerialName("v") val protocolVersion: Int = Protocol.VERSION
) : PhoneMessage

@Serializable
@SerialName("ping")
data class Ping(
    val nonce: Long,
    @SerialName("v") val protocolVersion: Int = Protocol.VERSION
) : PhoneMessage
