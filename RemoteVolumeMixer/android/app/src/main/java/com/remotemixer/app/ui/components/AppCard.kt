package com.remotemixer.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.remotemixer.app.data.Protocol.AppSession
import com.remotemixer.app.ui.theme.Amber
import com.remotemixer.app.ui.theme.TextLow
import com.remotemixer.app.ui.theme.TextMid
import com.remotemixer.app.ui.theme.accentFor

@Composable
fun AppCard(
    app: AppSession,
    iconUrl: String?,
    isFavorite: Boolean,
    onDrag: (Int) -> Unit,
    onCommit: (Int) -> Unit,
    onToggleMute: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val baseAccent = accentFor(app.processName)
    val accent by animateColorAsState(
        if (app.muted) TextLow else baseAccent,
        label = "accent",
    )

    GlassCard(glow = if (app.muted) null else baseAccent) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {

            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(iconUrl = iconUrl, processName = app.processName, accent = accent)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        app.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${app.processName}  ·  PID ${app.pid}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextLow,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    imageVector = if (isFavorite) Icons.Filled.Star else Icons.Rounded.StarBorder,
                    contentDescription = "Favourite",
                    tint = if (isFavorite) Amber else TextLow,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onToggleFavorite() }
                        .padding(8.dp),
                )
            }

            Spacer(Modifier.size(6.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (app.muted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
                    contentDescription = if (app.muted) "Unmute" else "Mute",
                    tint = if (app.muted) MaterialTheme.colorScheme.error else accent,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (app.muted) MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                            else accent.copy(alpha = 0.10f)
                        )
                        .clickable { onToggleMute() }
                        .padding(9.dp),
                )
                Spacer(Modifier.width(10.dp))
                VolumeSlider(
                    value = app.volume,
                    accent = accent,
                    modifier = Modifier.weight(1f),
                    onDrag = onDrag,
                    onCommit = onCommit,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "${app.volume}%",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (app.muted) TextLow else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.width(46.dp),
                )
            }

            if (app.muted) {
                Text(
                    "Muted · volume kept at ${app.volume}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMid,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
fun MasterCard(
    volume: Int,
    muted: Boolean,
    onDrag: (Int) -> Unit,
    onCommit: (Int) -> Unit,
    onToggleMute: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    GlassCard(glow = accent, radius = 28) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionLabel("Master volume")
                Text(
                    if (muted) "MUTED" else "$volume%",
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (muted) MaterialTheme.colorScheme.error else accent,
                )
            }
            Spacer(Modifier.size(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (muted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
                    contentDescription = "Mute master",
                    tint = if (muted) MaterialTheme.colorScheme.error else accent,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (muted) MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                            else accent.copy(alpha = 0.12f)
                        )
                        .clickable { onToggleMute() }
                        .padding(10.dp),
                )
                Spacer(Modifier.width(10.dp))
                VolumeSlider(
                    value = volume,
                    accent = if (muted) TextLow else accent,
                    modifier = Modifier.weight(1f),
                    onDrag = onDrag,
                    onCommit = onCommit,
                )
            }
        }
    }
}
