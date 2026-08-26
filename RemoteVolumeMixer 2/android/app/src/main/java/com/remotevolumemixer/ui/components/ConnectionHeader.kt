package com.remotevolumemixer.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.remotevolumemixer.data.ConnectionState

/**
 * Header: titolo del prodotto e stato del cavo, rappresentato visivamente
 * con un pallino animato. Connesso = pieno con alone pulsante,
 * disconnesso = anello vuoto.
 */
@Composable
fun ConnectionHeader(
    connection: ConnectionState,
    pcName: String?,
    appCount: Int,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val statusColor = when (connection) {
        ConnectionState.Connected -> colors.secondary
        ConnectionState.Incompatible -> colors.error
        ConnectionState.Disconnected -> colors.onSurfaceVariant
    }

    val label = when (connection) {
        ConnectionState.Connected -> "USB Connected"
        ConnectionState.Incompatible -> "Version mismatch"
        ConnectionState.Disconnected -> "USB Disconnected"
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "REMOTE VOLUME MIXER",
                style = MaterialTheme.typography.titleLarge,
                color = colors.onBackground
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusPill(
                    text = label,
                    color = statusColor,
                    connected = connection == ConnectionState.Connected
                )

                if (connection == ConnectionState.Connected) {
                    InfoPill(text = if (appCount == 1) "1 app" else "$appCount apps")
                    if (!pcName.isNullOrBlank()) {
                        InfoPill(text = pcName)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(colors.onSurface.copy(alpha = 0.06f))
                .clickable(onClick = onSettingsClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Tune,
                contentDescription = "Settings",
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(21.dp)
            )
        }
    }
}

@Composable
private fun StatusPill(
    text: String,
    color: Color,
    connected: Boolean
) {
    Surface(
        shape = CircleShape,
        color = color.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.28f))
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusDot(color = color, connected = connected)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = color
            )
        }
    }
}

@Composable
private fun StatusDot(color: Color, connected: Boolean) {
    val transition = rememberInfiniteTransition(label = "statusPulse")
    val pulse by transition.animateFloat(
        initialValue = if (connected) 0.55f else 0.25f,
        targetValue = if (connected) 0.14f else 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (connected) 1400 else 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    val coreSize by animateFloatAsState(
        targetValue = if (connected) 9f else 8f,
        animationSpec = tween(220),
        label = "dotSize"
    )

    Box(modifier = Modifier.size(16.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .alpha(pulse)
                .clip(CircleShape)
                .background(color)
        )

        if (connected) {
            Box(
                modifier = Modifier
                    .size(coreSize.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(coreSize.dp)
                    .clip(CircleShape)
                    .border(1.5.dp, color, CircleShape)
            )
        }
    }
}

@Composable
private fun InfoPill(text: String) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = CircleShape,
        color = colors.onSurface.copy(alpha = 0.05f),
        border = BorderStroke(1.dp, colors.outline.copy(alpha = 0.5f))
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
        )
    }
}
