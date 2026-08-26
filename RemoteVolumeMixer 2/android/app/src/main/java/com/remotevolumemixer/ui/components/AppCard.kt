package com.remotevolumemixer.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remotevolumemixer.data.AudioApp

/**
 * Card di una applicazione: icona reale, nome, percentuale sempre visibile,
 * mute individuale e slider grande.
 */
@Composable
fun AppCard(
    app: AudioApp,
    icon: ImageBitmap?,
    enabled: Boolean,
    onVolumeChange: (Int) -> Unit,
    onVolumeChangeFinished: () -> Unit,
    onToggleMute: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val percentColor by animateColorAsState(
        targetValue = when {
            !enabled -> colors.onSurface.copy(alpha = 0.35f)
            app.muted -> colors.onSurfaceVariant
            else -> colors.primary
        },
        animationSpec = tween(180),
        label = "percentColor"
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outline.copy(alpha = if (app.isMaster) 0.9f else 0.6f))
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 14.dp, top = 14.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIconTile(app = app, icon = icon)

                Spacer(modifier = Modifier.width(13.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = app.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.55f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = subtitleFor(app),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = "${app.volume}%",
                    color = percentColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 19.sp,
                    textAlign = TextAlign.End,
                    modifier = Modifier.widthIn(min = 56.dp)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MuteButton(muted = app.muted, enabled = enabled, onClick = onToggleMute)

                VolumeSlider(
                    volume = app.volume,
                    enabled = enabled,
                    muted = app.muted,
                    onVolumeChange = onVolumeChange,
                    onVolumeChangeFinished = onVolumeChangeFinished,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

private fun subtitleFor(app: AudioApp): String = when {
    app.isMaster -> app.processName.ifBlank { "Windows output device" }
    app.muted -> "Muted"
    app.isActive -> "Playing"
    else -> "Idle"
}

@Composable
private fun MuteButton(
    muted: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        targetValue = when {
            !enabled -> colors.onSurface.copy(alpha = 0.04f)
            muted -> colors.error.copy(alpha = 0.16f)
            else -> colors.onSurface.copy(alpha = 0.07f)
        },
        animationSpec = tween(180),
        label = "muteContainer"
    )
    val content by animateColorAsState(
        targetValue = when {
            !enabled -> colors.onSurface.copy(alpha = 0.3f)
            muted -> colors.error
            else -> colors.onSurfaceVariant
        },
        animationSpec = tween(180),
        label = "muteContent"
    )
    val scale by animateFloatAsState(
        targetValue = if (muted) 1.05f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium),
        label = "muteScale"
    )

    Box(
        modifier = Modifier
            .size(44.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(container)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = muted,
            transitionSpec = {
                (fadeIn(tween(140)) + scaleIn(initialScale = 0.7f)) togetherWith
                    (fadeOut(tween(90)) + scaleOut(targetScale = 0.7f))
            },
            label = "muteIcon"
        ) { isMuted ->
            Icon(
                imageVector = if (isMuted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
                contentDescription = if (isMuted) "Unmute" else "Mute",
                tint = content,
                modifier = Modifier.size(21.dp)
            )
        }
    }
}
