package com.remotevolumemixer.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.remotevolumemixer.data.SortMode
import com.remotevolumemixer.data.ThemeMode
import com.remotevolumemixer.data.UiSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: UiSettings,
    pcName: String?,
    protocolVersion: Int,
    onDismiss: () -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    onSortChange: (SortMode) -> Unit,
    onShowInactiveChange: (Boolean) -> Unit,
    onShowOutputCardChange: (Boolean) -> Unit,
    onKeepScreenOnChange: (Boolean) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val colors = MaterialTheme.colorScheme

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface
    ) {
        Column(modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 32.dp)) {
            Text(
                text = "DISPLAY",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(14.dp))

            SettingLabel("Theme")
            ChipRow(
                options = ThemeMode.entries.map { it to it.name },
                selected = settings.theme,
                onSelected = onThemeChange
            )

            Spacer(modifier = Modifier.height(18.dp))

            SettingLabel("Sort applications by")
            ChipRow(
                options = SortMode.entries.map { it to it.name },
                selected = settings.sort,
                onSelected = onSortChange
            )

            Spacer(modifier = Modifier.height(10.dp))

            ToggleRow(
                title = "Show apps that are not playing",
                subtitle = "Idle sessions stay in a secondary section",
                checked = settings.showInactive,
                onCheckedChange = onShowInactiveChange
            )

            ToggleRow(
                title = "Show Windows output card",
                subtitle = "Master volume of the current output device",
                checked = settings.showOutputCard,
                onCheckedChange = onShowOutputCardChange
            )

            ToggleRow(
                title = "Keep the screen on",
                subtitle = "Useful while the phone works as a control panel",
                checked = settings.keepScreenOn,
                onCheckedChange = onKeepScreenOnChange
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = buildString {
                    append("USB only. No Wi-Fi, no LAN, no account.")
                    append("\nProtocol v")
                    append(protocolVersion)
                    if (!pcName.isNullOrBlank()) {
                        append(" · linked to ")
                        append(pcName)
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun <T> ChipRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelected: (T) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            ChoiceChip(
                label = label,
                selected = value == selected,
                onClick = { onSelected(value) }
            )
        }
    }
}

@Composable
private fun ChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val background by animateColorAsState(
        targetValue = if (selected) colors.primary.copy(alpha = 0.16f) else colors.onSurface.copy(alpha = 0.05f),
        animationSpec = tween(160),
        label = "chipBackground"
    )
    val content by animateColorAsState(
        targetValue = if (selected) colors.primary else colors.onSurfaceVariant,
        animationSpec = tween(160),
        label = "chipContent"
    )

    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(background)
            .border(
                BorderStroke(1.dp, if (selected) colors.primary.copy(alpha = 0.45f) else colors.outline),
                CircleShape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.padding(horizontal = 6.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
