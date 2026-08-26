package com.remotevolumemixer.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remotevolumemixer.data.AudioApp
import kotlin.math.absoluteValue

/**
 * Icona dell'applicazione: quella reale di Windows quando disponibile,
 * altrimenti un riquadro di fallback coerente (mai un'immagine rotta).
 * Dimensione e raggio degli angoli sono sempre identici.
 */
@Composable
fun AppIconTile(
    app: AudioApp,
    icon: ImageBitmap?,
    modifier: Modifier = Modifier,
    size: Dp = 46.dp
) {
    val shape = RoundedCornerShape(14.dp)
    val dark = MaterialTheme.colorScheme.background.luminanceIsDark()
    val (top, bottom) = fallbackTint(app.name.ifBlank { app.processName }, dark)

    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(top, bottom)))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f), shape),
        contentAlignment = Alignment.Center
    ) {
        Crossfade(targetState = icon, animationSpec = tween(220), label = "appIcon") { bitmap ->
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = app.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(size * 0.16f)
                )
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    when {
                        app.isMaster -> Icon(
                            imageVector = Icons.Rounded.Speaker,
                            contentDescription = app.name,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                            modifier = Modifier.size(size * 0.5f)
                        )

                        app.isSystemSounds -> Icon(
                            imageVector = Icons.Rounded.Notifications,
                            contentDescription = app.name,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                            modifier = Modifier.size(size * 0.46f)
                        )

                        else -> Text(
                            text = initial(app.name.ifBlank { app.processName }),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                            fontWeight = FontWeight.Bold,
                            fontSize = (size.value * 0.42f).sp
                        )
                    }
                }
            }
        }
    }
}

private fun initial(name: String): String {
    val letter = name.trim().firstOrNull { it.isLetterOrDigit() } ?: '?'
    return letter.uppercase()
}

private fun fallbackTint(seed: String, dark: Boolean): Pair<Color, Color> {
    val hue = ((seed.hashCode().toLong().absoluteValue) % 360L).toFloat()
    return if (dark) {
        Color.hsl(hue, 0.28f, 0.26f) to Color.hsl(hue, 0.26f, 0.17f)
    } else {
        Color.hsl(hue, 0.42f, 0.88f) to Color.hsl(hue, 0.36f, 0.80f)
    }
}

private fun Color.luminanceIsDark(): Boolean = (red * 0.299f + green * 0.587f + blue * 0.114f) < 0.5f
