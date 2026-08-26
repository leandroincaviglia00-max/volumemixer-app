package com.remotevolumemixer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.remotevolumemixer.data.ThemeMode

private val OnAccentDark = Color(0xFF0A0B0F)
private val White = Color(0xFFFFFFFF)

private val DarkScheme = darkColorScheme(
    primary = AccentDark,
    onPrimary = OnAccentDark,
    primaryContainer = AccentDark,
    onPrimaryContainer = OnAccentDark,
    secondary = MintDark,
    onSecondary = OnAccentDark,
    tertiary = MintDark,
    onTertiary = OnAccentDark,
    background = InkBackground,
    onBackground = InkTextPrimary,
    surface = InkSurface,
    onSurface = InkTextPrimary,
    surfaceVariant = InkSurfaceElevated,
    onSurfaceVariant = InkTextSecondary,
    surfaceContainer = InkSurface,
    surfaceContainerHigh = InkSurfaceElevated,
    surfaceContainerLow = InkSurface,
    outline = InkBorder,
    outlineVariant = InkBorder,
    error = DangerDark,
    onError = OnAccentDark
)

private val LightScheme = lightColorScheme(
    primary = AccentLight,
    onPrimary = White,
    primaryContainer = AccentLight,
    onPrimaryContainer = White,
    secondary = MintLight,
    onSecondary = White,
    tertiary = MintLight,
    onTertiary = White,
    background = PaperBackground,
    onBackground = PaperTextPrimary,
    surface = PaperSurface,
    onSurface = PaperTextPrimary,
    surfaceVariant = PaperSurfaceElevated,
    onSurfaceVariant = PaperTextSecondary,
    surfaceContainer = PaperSurface,
    surfaceContainerHigh = PaperSurfaceElevated,
    surfaceContainerLow = PaperSurface,
    outline = PaperBorder,
    outlineVariant = PaperBorder,
    error = DangerLight,
    onError = White
)

@Composable
fun shouldUseDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.System -> isSystemInDarkTheme()
    ThemeMode.Dark -> true
    ThemeMode.Light -> false
}

@Composable
fun RemoteVolumeMixerTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = RvmTypography,
        content = content
    )
}
