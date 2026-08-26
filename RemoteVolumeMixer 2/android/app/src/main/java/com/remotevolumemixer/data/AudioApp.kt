package com.remotevolumemixer.data

import com.remotevolumemixer.protocol.AppPayload

/** Modello UI di una applicazione audio di Windows. */
data class AudioApp(
    val sessionId: String,
    val name: String,
    val processName: String,
    val volume: Int,
    val muted: Boolean,
    val isActive: Boolean,
    val isMaster: Boolean,
    val isSystemSounds: Boolean,
    val iconKey: String
)

fun AppPayload.toAudioApp(): AudioApp = AudioApp(
    sessionId = sessionId,
    name = name.ifBlank { processName.ifBlank { "Unknown app" } },
    processName = processName,
    volume = volume.coerceIn(0, 100),
    muted = muted,
    isActive = state == "active",
    isMaster = isMaster,
    isSystemSounds = isSystemSounds,
    iconKey = iconKey
)

enum class ConnectionState {
    Disconnected,
    Connected,
    Incompatible
}

data class MixerState(
    val connection: ConnectionState = ConnectionState.Disconnected,
    val apps: List<AudioApp> = emptyList(),
    val pcName: String? = null,
    val notice: String? = null
) {
    val isConnected: Boolean get() = connection == ConnectionState.Connected
}
