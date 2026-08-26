package com.remotevolumemixer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.remotevolumemixer.data.AudioApp
import com.remotevolumemixer.protocol.Protocol
import com.remotevolumemixer.ui.components.AppCard
import com.remotevolumemixer.ui.components.ConnectionHeader
import com.remotevolumemixer.ui.components.DisconnectedBanner
import com.remotevolumemixer.ui.components.EmptyState
import com.remotevolumemixer.ui.components.NoticeBanner
import com.remotevolumemixer.ui.components.SectionHeader
import com.remotevolumemixer.ui.components.SettingsSheet

/**
 * Schermata unica del mixer: header di stato + lista (o griglia su tablet)
 * delle applicazioni audio reali del PC.
 */
@Composable
fun MixerScreen(viewModel: MixerViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { insets ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
        ) {
            // Su tablet (o telefono in orizzontale) due colonne, senza comprimere il testo.
            val columns = if (maxWidth >= 700.dp) 2 else 1
            val controlsEnabled = state.isConnected

            Column(modifier = Modifier.fillMaxSize()) {
                ConnectionHeader(
                    connection = state.connection,
                    pcName = state.pcName,
                    appCount = state.totalCount,
                    onSettingsClick = { showSettings = true },
                    modifier = Modifier.padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 16.dp)
                )

                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    state.notice?.let { notice ->
                        item(key = "notice", span = { GridItemSpan(maxLineSpan) }) {
                            NoticeBanner(text = notice, onDismiss = viewModel::onDismissNotice)
                        }
                    }

                    if (!state.isConnected) {
                        item(key = "disconnected", span = { GridItemSpan(maxLineSpan) }) {
                            DisconnectedBanner()
                        }
                    }

                    if (state.totalCount == 0) {
                        item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                            EmptyState(connected = state.isConnected)
                        }
                    }

                    items(
                        items = state.playing,
                        key = { app -> app.sessionId },
                        span = { app -> GridItemSpan(if (app.isMaster) maxLineSpan else 1) }
                    ) { app ->
                        AppCardItem(
                            app = app,
                            state = state,
                            enabled = controlsEnabled,
                            viewModel = viewModel,
                            modifier = Modifier.animateItem()
                        )
                    }

                    if (state.idle.isNotEmpty()) {
                        item(key = "idle_header", span = { GridItemSpan(maxLineSpan) }) {
                            SectionHeader(text = "Not playing")
                        }

                        items(
                            items = state.idle,
                            key = { app -> app.sessionId }
                        ) { app ->
                            AppCardItem(
                                app = app,
                                state = state,
                                enabled = controlsEnabled,
                                viewModel = viewModel,
                                modifier = Modifier.animateItem()
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSettings) {
        SettingsSheet(
            settings = state.settings,
            pcName = state.pcName,
            protocolVersion = Protocol.VERSION,
            onDismiss = { showSettings = false },
            onThemeChange = viewModel::setTheme,
            onSortChange = viewModel::setSort,
            onShowInactiveChange = viewModel::setShowInactive,
            onShowOutputCardChange = viewModel::setShowOutputCard,
            onKeepScreenOnChange = viewModel::setKeepScreenOn
        )
    }
}

@Composable
private fun AppCardItem(
    app: AudioApp,
    state: MixerUiState,
    enabled: Boolean,
    viewModel: MixerViewModel,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(app.iconKey) {
        if (app.iconKey.isNotBlank()) {
            viewModel.ensureIcon(app.iconKey)
        }
    }

    AppCard(
        app = app,
        icon = state.icons[app.iconKey],
        enabled = enabled,
        onVolumeChange = { volume -> viewModel.onVolumeChange(app.sessionId, volume) },
        onVolumeChangeFinished = { viewModel.onVolumeChangeFinished(app.sessionId) },
        onToggleMute = { viewModel.onToggleMute(app) },
        modifier = modifier
    )
}
