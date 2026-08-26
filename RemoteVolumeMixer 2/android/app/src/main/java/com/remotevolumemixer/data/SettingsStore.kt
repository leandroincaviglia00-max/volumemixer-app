package com.remotevolumemixer.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "rvm_settings")

enum class ThemeMode { System, Dark, Light }

enum class SortMode { Activity, Name, Volume }

data class UiSettings(
    val theme: ThemeMode = ThemeMode.Dark,
    val sort: SortMode = SortMode.Activity,
    val showInactive: Boolean = true,
    val showOutputCard: Boolean = true,
    val keepScreenOn: Boolean = true
)

/**
 * Preferenze locali (DataStore). Solo impostazioni di visualizzazione:
 * nessun account, nessuna credenziale, nessun cloud.
 */
class SettingsStore(private val context: Context) {

    private object Keys {
        val theme = stringPreferencesKey("theme")
        val sort = stringPreferencesKey("sort")
        val showInactive = booleanPreferencesKey("show_inactive")
        val showOutputCard = booleanPreferencesKey("show_output_card")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
    }

    val settings: Flow<UiSettings> = context.settingsDataStore.data.map { prefs ->
        UiSettings(
            theme = prefs[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.Dark,
            sort = prefs[Keys.sort]?.let { runCatching { SortMode.valueOf(it) }.getOrNull() } ?: SortMode.Activity,
            showInactive = prefs[Keys.showInactive] ?: true,
            showOutputCard = prefs[Keys.showOutputCard] ?: true,
            keepScreenOn = prefs[Keys.keepScreenOn] ?: true
        )
    }

    suspend fun setTheme(mode: ThemeMode) {
        context.settingsDataStore.edit { it[Keys.theme] = mode.name }
    }

    suspend fun setSort(mode: SortMode) {
        context.settingsDataStore.edit { it[Keys.sort] = mode.name }
    }

    suspend fun setShowInactive(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.showInactive] = value }
    }

    suspend fun setShowOutputCard(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.showOutputCard] = value }
    }

    suspend fun setKeepScreenOn(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.keepScreenOn] = value }
    }
}
