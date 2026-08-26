package com.remotemixer.app.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remotemixer.app.MixerViewModel
import com.remotemixer.app.Screen
import com.remotemixer.app.net.Discovery
import com.remotemixer.app.net.MixerClient
import com.remotemixer.app.ui.components.GlassCard
import com.remotemixer.app.ui.components.SectionLabel
import com.remotemixer.app.ui.components.StatusLine
import com.remotemixer.app.ui.theme.Amber
import com.remotemixer.app.ui.theme.Iris
import com.remotemixer.app.ui.theme.Mint
import com.remotemixer.app.ui.theme.Rose
import com.remotemixer.app.ui.theme.TextLow
import com.remotemixer.app.ui.theme.TextMid
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun ConnectionScreen(vm: MixerViewModel) {
    val conn by vm.client.connection.collectAsStateWithLifecycle()
    val host by vm.hostField.collectAsStateWithLifecycle()
    val port by vm.portField.collectAsStateWithLifecycle()
    val found by vm.discovered.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 22.dp),
    ) {
        Spacer(Modifier.height(72.dp))

        // ---------------------------------------------------------- branding
        Box(
            Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.linearGradient(listOf(Iris.copy(alpha = 0.30f), Color.Transparent))
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Tune, contentDescription = null, tint = Iris,
                modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "Remote\nVolume Mixer",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Control your PC audio remotely.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextMid,
        )

        Spacer(Modifier.height(34.dp))

        // ------------------------------------------------------- manual form
        GlassCard {
            Column(Modifier.padding(18.dp)) {
                SectionLabel("PC IP address")
                Spacer(Modifier.height(8.dp))
                MixerTextField(
                    value = host,
                    onValueChange = vm::onHostChange,
                    placeholder = "192.168.1.100",
                    keyboardType = KeyboardType.Number,
                )
                Spacer(Modifier.height(16.dp))
                SectionLabel("Port")
                Spacer(Modifier.height(8.dp))
                MixerTextField(
                    value = port,
                    onValueChange = vm::onPortChange,
                    placeholder = "8765",
                    keyboardType = KeyboardType.Number,
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = vm::connectFromForm,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Iris),
                ) {
                    val busy = conn.status == MixerClient.Status.Connecting ||
                        conn.status == MixerClient.Status.Reconnecting
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Color(0xFF0A1024),
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(
                        if (busy) "CONNECTING" else "CONNECT",
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                        color = Color(0xFF0A1024),
                    )
                }
                Spacer(Modifier.height(14.dp))
                ConnectionStatus(conn)
            }
        }

        // ------------------------------------------------------------ pairing
        AnimatedVisibility(conn.status == MixerClient.Status.PairingRequired) {
            Column {
                Spacer(Modifier.height(16.dp))
                PairingCard(onSubmit = vm::submitPairingCode, message = conn.message)
            }
        }

        // ---------------------------------------------------------- discovery
        Spacer(Modifier.height(26.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(if (found.isEmpty()) "Auto discovery" else "Found on your network")
            Spacer(Modifier.weight(1f))
            if (scanning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(15.dp), strokeWidth = 2.dp, color = TextLow
                )
            } else {
                Icon(
                    Icons.Rounded.Refresh, contentDescription = "Scan again", tint = TextMid,
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { vm.scan() }
                        .padding(5.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        if (found.isEmpty()) {
            Text(
                if (scanning) "Searching for a PC on this Wi-Fi…"
                else "No PC announced itself. Make sure the server is running, " +
                    "or just type the IP above.",
                style = MaterialTheme.typography.bodySmall,
                color = TextLow,
            )
        } else {
            found.forEach { pc -> FoundPcCard(pc) { vm.connect(pc.ip, pc.port, pc.name) } }
        }

        Spacer(Modifier.height(28.dp))
        Text(
            "Both devices must be on the same Wi-Fi.\nThe PC firewall must allow TCP ${port.ifBlank { "8765" }}.",
            style = MaterialTheme.typography.bodySmall,
            color = TextLow,
            textAlign = TextAlign.Start,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            "Diagnostics",
            style = MaterialTheme.typography.bodySmall,
            color = Iris,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { vm.show(Screen.Diagnostics) }
                .padding(vertical = 6.dp),
        )
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun ConnectionStatus(conn: MixerClient.ConnectionState) {
    when (conn.status) {
        MixerClient.Status.Idle ->
            StatusLine(TextLow, "Not connected")
        MixerClient.Status.Connecting ->
            StatusLine(Amber, "Searching for PC…", pulsing = true)
        MixerClient.Status.Reconnecting ->
            StatusLine(Amber, "Retrying… (attempt ${conn.attempt})", pulsing = true)
        MixerClient.Status.PairingRequired ->
            StatusLine(Amber, "Pairing required", pulsing = true)
        MixerClient.Status.Failed ->
            StatusLine(Rose, "Connection failed")
        MixerClient.Status.Connected -> Column {
            StatusLine(Mint, "Connected")
            Spacer(Modifier.height(10.dp))
            InfoRow("PC", conn.pcName.ifBlank { conn.host })
            InfoRow("Address", "${conn.host}:${conn.port}")
            InfoRow("Ping", if (conn.latencyMs >= 0) "${conn.latencyMs} ms" else "—")
        }
    }
    if (conn.status == MixerClient.Status.Failed && conn.message.isNotBlank()) {
        Spacer(Modifier.height(6.dp))
        Text(conn.message, style = MaterialTheme.typography.bodySmall, color = TextLow)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = TextLow,
            modifier = Modifier.width(78.dp))
        Text(value, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun FoundPcCard(pc: Discovery.Found, onConnect: () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp), radius = 20) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(Mint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Computer, null, tint = Mint, modifier = Modifier.size(21.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(pc.name, style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface)
                Text("${pc.ip}:${pc.port}", style = MaterialTheme.typography.bodySmall,
                    color = TextLow)
            }
            Button(
                onClick = onConnect,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Iris.copy(alpha = 0.18f)),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp, vertical = 8.dp
                ),
            ) {
                Text("CONNECT", color = Iris, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodySmall, letterSpacing = 1.sp)
            }
        }
    }
}

@Composable
private fun PairingCard(onSubmit: (String) -> Unit, message: String) {
    var code by remember { mutableStateOf("") }
    GlassCard(glow = Amber) {
        Column(Modifier.padding(18.dp)) {
            SectionLabel("Pairing code")
            Spacer(Modifier.height(6.dp))
            Text(
                message.ifBlank { "Type the 6 digit code shown in the server window." },
                style = MaterialTheme.typography.bodySmall, color = TextMid,
            )
            Spacer(Modifier.height(12.dp))
            MixerTextField(
                value = code,
                onValueChange = { code = it.filter { c -> c.isDigit() }.take(6) },
                placeholder = "482731",
                keyboardType = KeyboardType.NumberPassword,
            )
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = { onSubmit(code) },
                enabled = code.length == 6,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Amber),
            ) {
                Text("PAIR", fontWeight = FontWeight.Bold, color = Color(0xFF201400),
                    letterSpacing = 1.2.sp)
            }
        }
    }
}

@Composable
fun MixerTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, color = TextLow) },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = ImeAction.Done,
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Iris,
            unfocusedBorderColor = Color.White.copy(alpha = 0.12f),
            focusedContainerColor = Color.White.copy(alpha = 0.04f),
            unfocusedContainerColor = Color.White.copy(alpha = 0.04f),
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            cursorColor = Iris,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}
