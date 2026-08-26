package com.remotemixer.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val MixerColors = darkColorScheme(
    primary = Iris,
    onPrimary = Color(0xFF0A1024),
    primaryContainer = IrisDeep,
    secondary = Mint,
    background = Ink,
    onBackground = TextHi,
    surface = Slate,
    onSurface = TextHi,
    surfaceVariant = SlateHi,
    onSurfaceVariant = TextMid,
    error = Rose,
    outline = HairlineStrong,
)

/**
 * Dark only, on purpose: this is a low-light "second screen" remote and a light
 * theme would be actively unpleasant next to a gaming setup.
 */
@Composable
fun RemoteVolumeMixerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            (view.context as? Activity)?.window?.let { window ->
                WindowCompat.setDecorFitsSystemWindows(window, false)
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            }
        }
    }
    MaterialTheme(
        colorScheme = MixerColors,
        typography = MixerTypography,
        content = content,
    )
}
