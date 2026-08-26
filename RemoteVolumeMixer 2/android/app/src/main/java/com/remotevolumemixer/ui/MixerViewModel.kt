package com.remotevolumemixer.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.remotevolumemixer.RvmApplication
import com.remotevolumemixer.data.AudioApp
import com.remotevolumemixer.data.ConnectionState
import com.remotevolumemixer.data.MixerRepository
import com.remotevolumemixer.data.MixerState
import com.remotevolumemixer.data.SettingsStore
import com.remotevolumemixer.data.SortMode
import com.remotevolumemixer.data.ThemeMode
import com.remotevolumemixer.data.UiSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MixerUiState(
    val connection: ConnectionState = ConnectionState.Disconnected,
    val pcName: String? = null,
    val notice: String? = null,
    val playing: List<AudioApp> = emptyList(),
    val idle: List<AudioApp> = emptyList(),
    val icons: Map<String, ImageBitmap> = emptyMap(),
    val settings: UiSettings = UiSettings()
) {
    val isConnected: Boolean get() = connection == ConnectionState.Connected
    val totalCount: Int get() = playing.size + idle.size
}

class MixerViewModel(
    private val repository: MixerRepository,
    private val settingsStore: SettingsStore
) : ViewModel() {

    val uiState: StateFlow<MixerUiState> =
        combine(
            repository.state,
            settingsStore.settings,
            repository.iconStore.icons
        ) { mixer, settings, icons ->
            build(mixer, settings, icons)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = MixerUiState()
        )

    /** Tema separato: serve anche fuori dallo stato principale, senza flicker. */
    val theme: StateFlow<ThemeMode> = settingsStore.settings
        .map { it.theme }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.Dark)

    fun onVolumeChange(sessionId: String, volume: Int) = repository.setVolume(sessionId, volume)

    fun onVolumeChangeFinished(sessionId: String) = repository.finalizeVolume(sessionId)

    fun onToggleMute(app: AudioApp) = repository.setMuted(app.sessionId, !app.muted)

    fun onRefresh() = repository.requestRefresh()

    fun onDismissNotice() = repository.dismissNotice()

    fun ensureIcon(iconKey: String) =
        repository.iconStore.ensure(iconKey) { key -> repository.requestIcon(key) }

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch { settingsStore.setTheme(mode) }
    }

    fun setSort(mode: SortMode) {
        viewModelScope.launch { settingsStore.setSort(mode) }
    }

    fun setShowInactive(value: Boolean) {
        viewModelScope.launch { settingsStore.setShowInactive(value) }
    }

    fun setShowOutputCard(value: Boolean) {
        viewModelScope.launch { settingsStore.setShowOutputCard(value) }
    }

    fun setKeepScreenOn(value: Boolean) {
        viewModelScope.launch { settingsStore.setKeepScreenOn(value) }
    }

    private fun build(
        mixer: MixerState,
        settings: UiSettings,
        icons: Map<String, ImageBitmap>
    ): MixerUiState {
        val visible = mixer.apps.filter { settings.showOutputCard || !it.isMaster }

        val comparator = when (settings.sort) {
            SortMode.Name -> compareBy<AudioApp>({ !it.isMaster }, { it.name.lowercase() })
            SortMode.Volume -> compareBy<AudioApp>({ !it.isMaster }, { -it.volume }, { it.name.lowercase() })
            SortMode.Activity -> compareBy<AudioApp>(
                { !it.isMaster },
                { !it.isActive },
                { it.isSystemSounds },
                { it.name.lowercase() }
            )
        }

        val sorted = visible.sortedWith(comparator)
        val playing = sorted.filter { it.isMaster || it.isActive }
        val idle = if (settings.showInactive) {
            sorted.filter { !it.isMaster && !it.isActive }
        } else {
            emptyList()
        }

        return MixerUiState(
            connection = mixer.connection,
            pcName = mixer.pcName,
            notice = mixer.notice,
            playing = playing,
            idle = idle,
            icons = icons,
            settings = settings
        )
    }

    companion object {
        fun factory(application: RvmApplication): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                MixerViewModel(
                    repository = application.container.repository,
                    settingsStore = application.container.settings
                )
            }
        }
    }
}
