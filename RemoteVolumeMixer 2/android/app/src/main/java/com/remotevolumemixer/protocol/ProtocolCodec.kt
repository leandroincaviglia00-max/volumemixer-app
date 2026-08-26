package com.remotevolumemixer.protocol

import android.util.Log
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** Codifica/decodifica tollerante: un pacchetto invalido viene loggato, non fa crashare nulla. */
object ProtocolCodec {

    private const val TAG = "RVM/Protocol"

    fun encode(message: PhoneMessage): String =
        Protocol.json.encodeToString<PhoneMessage>(message)

    fun decode(line: String): PcMessage? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        return try {
            Protocol.json.decodeFromString<PcMessage>(trimmed)
        } catch (t: Throwable) {
            Log.w(TAG, "Invalid packet ignored (${trimmed.length} chars): ${t.message}")
            null
        }
    }
}
