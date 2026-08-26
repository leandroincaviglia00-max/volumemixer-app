package com.remotemixer.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.remotemixer.app.ui.ConnectionScreen
import com.remotemixer.app.ui.DiagnosticsScreen
import com.remotemixer.app.ui.MixerScreen
import com.remotemixer.app.ui.theme.Ink
import com.remotemixer.app.ui.theme.InkDeep
import com.remotemixer.app.ui.theme.RemoteVolumeMixerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RemoteVolumeMixerTheme {
                MixerRoot()
            }
        }
    }
}

@Composable
private fun MixerRoot(vm: MixerViewModel = viewModel()) {
    val screen by vm.screen.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(toast) {
        toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.consumeToast()
        }
    }

    BackHandler(enabled = screen != Screen.Connection) {
        vm.show(if (screen == Screen.Diagnostics && vm.client.connection.value.isConnected)
            Screen.Mixer else Screen.Connection)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Ink, InkDeep),
                    center = Offset(0.15f, 0f),
                    radius = 1600f,
                )
            )
    ) {
        Crossfade(targetState = screen, animationSpec = tween(280), label = "screen") { s ->
            when (s) {
                Screen.Connection -> ConnectionScreen(vm)
                Screen.Mixer -> MixerScreen(vm)
                Screen.Diagnostics -> DiagnosticsScreen(vm)
            }
        }
    }
}
