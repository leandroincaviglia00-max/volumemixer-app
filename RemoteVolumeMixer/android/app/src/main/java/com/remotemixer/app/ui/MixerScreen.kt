package com.remotemixer.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.remotemixer.app.MixerViewModel
import com.remotemixer.app.Screen
import com.remotemixer.app.data.Prefs
import com.remotemixer.app.net.MixerClient
import com.remotemixer.app.ui.components.AppCard
import com.remotemixer.app.ui.components.GlassCard
import com.remotemixer.app.ui.components.MasterCard
import com.remotemixer.app.ui.components.SectionLabel
import com.remotemixer.app.ui.components.StatusDot
import com.remotemixer.app.ui.theme.Amber
import com.remotemixer.app.ui.theme.Iris
import com.remotemixer.app.ui.theme.Mint
import com.remotemixer.app.ui.theme.Rose
import com.remotemixer.app.ui.theme.TextLow
import com.remotemixer.app.ui.theme.TextMid

@Composable
fun MixerScreen(vm: MixerViewModel) {
    val conn by vm.client.connection.collectAsStateWithLifecycle()
    val apps by vm.visibleApps.collectAsStateWithLifecycle()
    val master by vm.client.master.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()

    val offline = !conn.isConnected
    val dim by animateFloatAsState(if (offline) 0.45f else 1f, label = "dim")

    Column(Modifier.fillMaxSize().imePadding()) {

        // -------------------------------------------------------------- header
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 22.dp, end = 14.dp, top = 58.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Remote Volume Mixer",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (dotColor, label) = when (conn.status) {
                        MixerClient.Status.Connected -> Mint to "Connected"
                        MixerClient.Status.Reconnecting -> Amber to "Reconnecting…"
                        MixerClient.Status.Connecting -> Amber to "Connecting…"
                        else -> Rose to "Disconnected"
                    }
                    StatusDot(dotColor, pulsing = conn.status != MixerClient.Status.Connected)
                    Text(
                        "  $label  ·  ${conn.host}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMid,
                    )
                }
            }
            IconAction(Icons.Rounded.Speed, "Diagnostics") { vm.show(Screen.Diagnostics) }
            IconAction(Icons.Rounded.PowerSettingsNew, "Disconnect") { vm.disconnect() }
        }

        // ------------------------------------------------------ offline banner
        AnimatedVisibility(offline) {
            Box(Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
                GlassCard(glow = Rose, radius = 18) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusDot(Rose, pulsing = true)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                "PC DISCONNECTED",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                                color = Rose,
                            )
                            Text(
                                if (conn.status == MixerClient.Status.Reconnecting)
                                    "Retrying automatically… (attempt ${conn.attempt})"
                                else conn.message.ifBlank { "Waiting for the server" },
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMid,
                            )
                        }
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().alpha(dim),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 18.dp, end = 18.dp, top = 8.dp, bottom = 40.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("master") {
                MasterCard(
                    volume = master.volume,
                    muted = master.muted,
                    onDrag = { vm.onVolumeDrag(vm.masterId, it) },
                    onCommit = { vm.onVolumeCommit(vm.masterId, it) },
                    onToggleMute = { vm.toggleMute(vm.masterId, master.muted) },
                )
            }

            item("search") {
                Column {
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = query,
                        onValueChange = vm::onQueryChange,
                        placeholder = { Text("Search applications…", color = TextLow) },
                        leadingIcon = {
                            Icon(Icons.Rounded.Search, null, tint = TextLow,
                                modifier = Modifier.size(19.dp))
                        },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                Icon(
                                    Icons.Rounded.Close, "Clear", tint = TextLow,
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { vm.onQueryChange("") }
                                        .padding(6.dp),
                                )
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Iris,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.10f),
                            focusedContainerColor = Color.White.copy(alpha = 0.04f),
                            unfocusedContainerColor = Color.White.copy(alpha = 0.04f),
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            cursorColor = Iris,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            item("controls") {
                Row(
                    Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionLabel("Applications  (${apps.size})")
                    Spacer(Modifier.weight(1f))
                    SortChip("Recent", sort == Prefs.SORT_RECENT) { vm.setSort(Prefs.SORT_RECENT) }
                    Spacer(Modifier.width(6.dp))
                    SortChip("Name", sort == Prefs.SORT_NAME) { vm.setSort(Prefs.SORT_NAME) }
                    Spacer(Modifier.width(6.dp))
                    SortChip("Volume", sort == Prefs.SORT_VOLUME) { vm.setSort(Prefs.SORT_VOLUME) }
                }
            }

            if (apps.isEmpty()) {
                item("empty") { EmptyState(query.isNotBlank(), offline) }
            }

            items(apps, key = { it.id }) { app ->
                AppCard(
                    app = app,
                    iconUrl = vm.iconUrl(app),
                    isFavorite = vm.isFavorite(app, favorites),
                    onDrag = { vm.onVolumeDrag(app.id, it) },
                    onCommit = { vm.onVolumeCommit(app.id, it) },
                    onToggleMute = { vm.toggleMute(app.id, app.muted) },
                    onToggleFavorite = { vm.toggleFavorite(app) },
                )
            }
        }
    }
}

@Composable
private fun IconAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Icon(
        icon, label, tint = TextMid,
        modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(11.dp),
    )
}

@Composable
private fun SortChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(11.dp))
            .background(if (selected) Iris.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.05f))
            .clickable { onClick() }
            .padding(horizontal = 11.dp, vertical = 6.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (selected) Iris else TextLow,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun EmptyState(filtered: Boolean, offline: Boolean) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.GraphicEq, null, tint = TextLow, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(14.dp))
        Text(
            when {
                offline -> "Waiting for the PC"
                filtered -> "No application matches your search"
                else -> "No app is playing audio right now"
            },
            style = MaterialTheme.typography.titleMedium,
            color = TextMid,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (offline) "Reconnecting as soon as the server is back."
            else "Start Spotify, a video or a game and it shows up here instantly.",
            style = MaterialTheme.typography.bodySmall,
            color = TextLow,
        )
    }
}
