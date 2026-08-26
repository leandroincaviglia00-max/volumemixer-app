package com.remotemixer.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage

/**
 * Real Windows executable icon when the server could extract one, otherwise a
 * generic glyph guessed from the process name.
 */
@Composable
fun AppIcon(
    iconUrl: String?,
    processName: String,
    accent: Color,
    size: Int = 42,
) {
    val shape = RoundedCornerShape((size * 0.3f).dp)
    Box(
        Modifier
            .size(size.dp)
            .clip(shape)
            .background(accent.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        if (iconUrl != null) {
            SubcomposeAsyncImage(
                model = iconUrl,
                contentDescription = processName,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size((size * 0.68f).dp),
                loading = { Fallback(processName, accent, size) },
                error = { Fallback(processName, accent, size) },
            )
        } else {
            Fallback(processName, accent, size)
        }
    }
}

@Composable
private fun Fallback(processName: String, accent: Color, size: Int) {
    Icon(
        imageVector = glyphFor(processName),
        contentDescription = null,
        tint = accent,
        modifier = Modifier.size((size * 0.52f).dp).padding(0.dp),
    )
}

private fun glyphFor(processName: String): ImageVector {
    val p = processName.lowercase()
    return when {
        listOf("chrome", "msedge", "firefox", "opera", "brave", "browser").any { it in p } ->
            Icons.Rounded.Language
        listOf("spotify", "music", "itunes", "tidal", "deezer", "foobar", "aimp").any { it in p } ->
            Icons.Rounded.MusicNote
        listOf("discord", "teams", "zoom", "skype", "slack", "telegram", "whatsapp").any { it in p } ->
            Icons.Rounded.Chat
        listOf("steam", "epic", "game", "riot", "valorant", "league", "battle", "gta", "minecraft")
            .any { it in p } -> Icons.Rounded.SportsEsports
        listOf("vlc", "mpc", "potplayer", "movies", "netflix", "video", "mpv").any { it in p } ->
            Icons.Rounded.PlayCircle
        listOf("system", "audiodg").any { it in p } -> Icons.Rounded.VolumeUp
        listOf("cmd", "powershell", "terminal", "python", "code").any { it in p } ->
            Icons.Rounded.Terminal
        listOf("explorer", "settings", "host").any { it in p } -> Icons.Rounded.Settings
        else -> Icons.Rounded.Apps
    }
}
