package com.remotemixer.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Everything the app remembers between launches:
 * last server (auto reconnect), pairing token, favourites, sort order.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("remote_mixer", Context.MODE_PRIVATE)

    // ---------------------------------------------------------- last server
    var lastHost: String
        get() = sp.getString(KEY_HOST, "") ?: ""
        set(v) = sp.edit().putString(KEY_HOST, v).apply()

    var lastPort: Int
        get() = sp.getInt(KEY_PORT, 8765)
        set(v) = sp.edit().putInt(KEY_PORT, v).apply()

    var lastPcName: String
        get() = sp.getString(KEY_PC_NAME, "") ?: ""
        set(v) = sp.edit().putString(KEY_PC_NAME, v).apply()

    var autoConnect: Boolean
        get() = sp.getBoolean(KEY_AUTO, true)
        set(v) = sp.edit().putBoolean(KEY_AUTO, v).apply()

    // ------------------------------------------------------------- pairing
    fun token(host: String): String? = sp.getString(KEY_TOKEN_PREFIX + host, null)

    fun saveToken(host: String, token: String) =
        sp.edit().putString(KEY_TOKEN_PREFIX + host, token).apply()

    fun clearToken(host: String) = sp.edit().remove(KEY_TOKEN_PREFIX + host).apply()

    // ----------------------------------------------------------- favourites
    /** Keyed by process name so a favourite survives app restarts / new PIDs. */
    var favorites: Set<String>
        get() = sp.getStringSet(KEY_FAVS, emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet(KEY_FAVS, v).apply()

    fun toggleFavorite(processName: String): Set<String> {
        val key = processName.lowercase()
        val next = favorites.toMutableSet()
        if (!next.remove(key)) next.add(key)
        favorites = next
        return next
    }

    // ------------------------------------------------------------ sorting
    var sortMode: String
        get() = sp.getString(KEY_SORT, SORT_RECENT) ?: SORT_RECENT
        set(v) = sp.edit().putString(KEY_SORT, v).apply()

    companion object {
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_PC_NAME = "pc_name"
        private const val KEY_AUTO = "auto_connect"
        private const val KEY_TOKEN_PREFIX = "token_"
        private const val KEY_FAVS = "favorites"
        private const val KEY_SORT = "sort"

        const val SORT_RECENT = "recent"
        const val SORT_NAME = "name"
        const val SORT_VOLUME = "volume"
    }
}
