package com.remotevolumemixer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.remotevolumemixer.ui.MixerScreen
import com.remotevolumemixer.ui.MixerViewModel
import com.remotevolumemixer.ui.theme.RemoteVolumeMixerTheme
import com.remotevolumemixer.ui.theme.shouldUseDarkTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MixerViewModel by viewModels {
        MixerViewModel.factory(application as RvmApplication)
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* il canale USB funziona anche senza notifica visibile */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val app = application as RvmApplication
        app.container.repository.ensureStarted()
        app.startBridgeService()
        askNotificationPermissionIfNeeded()

        setContent {
            val themeMode by viewModel.theme.collectAsStateWithLifecycle()
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            val darkTheme = shouldUseDarkTheme(themeMode)

            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }

            val keepScreenOn = state.settings.keepScreenOn && state.isConnected
            DisposableEffect(keepScreenOn) {
                if (keepScreenOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                onDispose {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            RemoteVolumeMixerTheme(darkTheme = darkTheme) {
                MixerScreen(viewModel = viewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Se il servizio fosse stato terminato dal sistema, il canale riparte qui.
        (application as RvmApplication).container.repository.ensureStarted()
    }

    private fun askNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

        if (!granted) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
