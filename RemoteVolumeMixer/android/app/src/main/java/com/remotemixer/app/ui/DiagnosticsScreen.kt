package com.remotemixer.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.remotemixer.app.MixerViewModel
import com.remotemixer.app.Screen
import com.remotemixer.app.net.MixerClient
import com.remotemixer.app.ui.components.GlassCard
import com.remotemixer.app.ui.components.SectionLabel
import com.remotemixer.app.ui.theme.Mint
import com.remotemixer.app.ui.theme.Rose
import com.remotemixer.app.ui.theme.TextLow
import com.remotemixer.app.ui.theme.TextMid
import kotlinx.coroutines.delay

@Composable
fun DiagnosticsScreen(vm: MixerViewModel) {
    val conn by vm.client.connection.collectAsStateWithLifecycle()
    val apps by vm.client.apps.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()

    // ticks once a second so "last update" stays honest
    val now by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000)
        }
    }
    val ago = if (conn.lastMessageAt == 0L) "—"
    else "${((now - conn.lastMessageAt) / 1000).coerceAtLeast(0)} sec ago"

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(56.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.ArrowBack, "Back", tint = TextMid,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .clickable {
                        vm.show(if (conn.isConnected) Screen.Mixer else Screen.Connection)
                    }
                    .padding(9.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Diagnostics",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.Rounded.Refresh, "Refresh", tint = TextMid,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .clickable { vm.refresh() }
                    .padding(9.dp),
            )
        }
        Spacer(Modifier.height(20.dp))

        GlassCard {
            Column(Modifier.padding(18.dp)) {
                SectionLabel("Connection")
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (conn.isConnected) "Connected" else conn.status.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (conn.isConnected) Mint else Rose,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Line("Server", if (conn.host.isBlank()) "—" else "${conn.host}:${conn.port}")
                Line("PC name", conn.pcName.ifBlank { "—" })
                Line("WebSocket", if (conn.isConnected) "Connected" else "Closed")
                Line("Server version", conn.serverVersion.ifBlank { "—" })
                Line("Latency", if (conn.latencyMs >= 0) "${conn.latencyMs} ms" else "—")
                Line("Applications", apps.size.toString())
                Line("Last update", ago)
                Line("Reconnect attempts", conn.attempt.toString())
                Line("Favourites", favorites.size.toString())
            }
        }

        Spacer(Modifier.height(14.dp))

        GlassCard {
            Column(Modifier.padding(18.dp)) {
                SectionLabel("Audio sessions (raw)")
                Spacer(Modifier.height(10.dp))
                if (apps.isEmpty()) {
                    Text("none", style = MaterialTheme.typography.bodySmall, color = TextLow)
                } else {
                    apps.forEach { a ->
                        Text(
                            "${a.displayName}  ·  ${a.processName}  ·  pid ${a.pid}  ·  " +
                                "${a.volume}%${if (a.muted) " · muted" else ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMid,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                        Text(
                            "id ${a.id}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextLow,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        GlassCard {
            Column(Modifier.padding(18.dp)) {
                SectionLabel("Server endpoints")
                Spacer(Modifier.height(10.dp))
                listOf("/", "/api/status", "/api/apps", "/docs").forEach {
                    Text(
                        "${conn.baseUrl}$it",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextLow,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = TextLow,
            modifier = Modifier.width(140.dp))
        Text(value, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface)
    }
}
