package com.remotemixer.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Wire protocol v1 of the Remote Volume Mixer.
 * Mirrors windows-server/README.md section 8 exactly.
 */
object Protocol {

    const val VERSION = 1
    const val MASTER_ID = "master"

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    // ------------------------------------------------------------------ models
    @Serializable
    data class AppSession(
        val id: String,
        val pid: Int = 0,
        @SerialName("process_name") val processName: String = "",
        @SerialName("display_name") val displayName: String = "",
        val volume: Int = 0,
        val muted: Boolean = false,
        /** relative path such as `/api/icon/1234-abcd`, or null */
        val icon: String? = null,
        @SerialName("last_active") val lastActive: Long = 0L,
    )

    @Serializable
    data class Master(
        val volume: Int = 0,
        val muted: Boolean = false,
    )

    // ------------------------------------------------------- server -> client
    sealed interface Incoming {
        data class Hello(
            val hostname: String,
            val version: String,
            val requiresPairing: Boolean,
        ) : Incoming

        data object AuthOk : Incoming
        data object AuthRequired : Incoming
        data class Paired(val token: String) : Incoming
        data class PairFailed(val message: String) : Incoming

        data class Apps(
            val apps: List<AppSession>,
            val master: Master?,
            val isUpdate: Boolean,
        ) : Incoming

        data class VolumeUpdate(val id: String, val volume: Int, val muted: Boolean) : Incoming
        data class VolumeChanged(val id: String, val volume: Int) : Incoming
        data class MuteChanged(val id: String, val muted: Boolean) : Incoming
        data class Pong(val clientTime: Long) : Incoming
        data class Icon(val id: String, val pngBase64: String?) : Incoming
        data class Error(val code: String, val id: String?, val message: String) : Incoming
        data class Unknown(val type: String) : Incoming
    }

    fun parse(raw: String): Incoming? = runCatching {
        val obj = json.parseToJsonElement(raw).jsonObject
        when (val type = obj.str("type")) {
            "hello" -> Incoming.Hello(
                hostname = obj.str("hostname") ?: "PC",
                version = obj.str("version") ?: "?",
                requiresPairing = obj.bool("requires_pairing") ?: false,
            )
            "auth_ok" -> Incoming.AuthOk
            "auth_required" -> Incoming.AuthRequired
            "paired" -> Incoming.Paired(obj.str("token") ?: "")
            "pair_failed" -> Incoming.PairFailed(obj.str("message") ?: "Wrong pairing code")
            "apps", "apps_updated" -> Incoming.Apps(
                apps = obj["apps"]?.let {
                    json.decodeFromJsonElement(
                        kotlinx.serialization.builtins.ListSerializer(AppSession.serializer()), it
                    )
                } ?: emptyList(),
                master = obj["master"]?.let {
                    json.decodeFromJsonElement(Master.serializer(), it)
                },
                isUpdate = type == "apps_updated",
            )
            "volume_update" -> Incoming.VolumeUpdate(
                id = obj.str("id") ?: return@runCatching null,
                volume = obj.int("volume") ?: 0,
                muted = obj.bool("muted") ?: false,
            )
            "volume_changed" -> Incoming.VolumeChanged(
                id = obj.str("id") ?: return@runCatching null,
                volume = obj.int("volume") ?: 0,
            )
            "mute_changed" -> Incoming.MuteChanged(
                id = obj.str("id") ?: return@runCatching null,
                muted = obj.bool("muted") ?: false,
            )
            "pong" -> Incoming.Pong(obj.long("t") ?: 0L)
            "icon" -> Incoming.Icon(obj.str("id") ?: "", obj.str("png_base64"))
            "error" -> Incoming.Error(
                code = obj.str("code") ?: "error",
                id = obj.str("id"),
                message = obj.str("message") ?: "unknown error",
            )
            else -> Incoming.Unknown(type ?: "")
        }
    }.getOrNull()

    // ------------------------------------------------------- client -> server
    fun auth(token: String?, deviceName: String): String = buildJsonObject {
        put("type", "auth")
        put("name", deviceName)
        if (token != null) put("token", token)
    }.toString()

    fun pair(code: String, deviceName: String): String = buildJsonObject {
        put("type", "pair")
        put("code", code)
        put("name", deviceName)
    }.toString()

    fun getApps(): String = """{"type":"get_apps"}"""

    fun refresh(): String = """{"type":"refresh"}"""

    fun ping(t: Long): String = buildJsonObject {
        put("type", "ping")
        put("t", t)
    }.toString()

    fun setVolume(id: String, volume: Int): String = buildJsonObject {
        put("type", "set_volume")
        put("id", id)
        put("volume", volume.coerceIn(0, 100))
    }.toString()

    fun setMute(id: String, muted: Boolean): String = buildJsonObject {
        put("type", "set_mute")
        put("id", id)
        put("muted", muted)
    }.toString()

    // ------------------------------------------------------------- json utils
    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.int(key: String): Int? =
        this[key]?.jsonPrimitive?.intOrNull

    private fun JsonObject.long(key: String): Long? =
        this[key]?.jsonPrimitive?.longOrNull

    private fun JsonObject.bool(key: String): Boolean? =
        this[key]?.jsonPrimitive?.booleanOrNull
}
