package com.remotevolumemixer.ui.components

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Etichetta di sezione, sottile e discreta. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
        )
    }
}

/**
 * Messaggio chiaro quando il cavo non c'e': nessun errore tecnico,
 * nessuno stack trace, solo cosa fare.
 */
@Composable
fun DisconnectedBanner(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = colors.surfaceVariant,
        border = BorderStroke(1.dp, colors.outline)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(colors.onSurface.copy(alpha = 0.06f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Usb,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(21.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Waiting for the USB cable",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Plug the phone into the PC and make sure RemoteVolumeMixer.exe is running. Controls stay locked until the link is back.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
        }
    }
}

/** Avviso testuale (es. protocollo incompatibile), chiudibile. */
@Composable
fun NoticeBanner(
    text: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = colors.error.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, colors.error.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = colors.error,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Dismiss",
                    tint = colors.error,
                    modifier = Modifier.size(17.dp)
                )
            }
        }
    }
}

/** Nessuna sessione audio: succede, e va comunicato bene. */
@Composable
fun EmptyState(connected: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 56.dp, bottom = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(colors.onSurface.copy(alpha = 0.05f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.GraphicEq,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(28.dp)
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        Text(
            text = if (connected) "No audio applications yet" else "Nothing to show",
            style = MaterialTheme.typography.titleMedium,
            color = colors.onSurface
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = if (connected) {
                "Start Spotify, Chrome, Discord or any app that plays sound: it will appear here on its own."
            } else {
                "Connect the USB cable to see the applications running on your PC."
            },
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
    }
}
