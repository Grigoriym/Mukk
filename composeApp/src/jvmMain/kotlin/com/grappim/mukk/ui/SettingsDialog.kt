package com.grappim.mukk.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.grappim.mukk.data.RepeatMode
import com.grappim.mukk.data.ResumeMode
import com.grappim.mukk.data.SettingsState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    settingsState: SettingsState,
    isScanning: Boolean,
    onRepeatModeChange: (RepeatMode) -> Unit,
    onShuffleToggle: () -> Unit,
    onResumeModeChange: (ResumeMode) -> Unit,
    onAudioDeviceChange: (String) -> Unit,
    onRescanAll: () -> Unit,
    onClearLibrary: () -> Unit,
    onResetPreferences: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                AudioOutputSection(
                    settingsState = settingsState,
                    onAudioDeviceChange = onAudioDeviceChange
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                PlaybackSection(
                    settingsState = settingsState,
                    onRepeatModeChange = onRepeatModeChange,
                    onShuffleToggle = onShuffleToggle,
                    onResumeModeChange = onResumeModeChange
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                LibrarySection(
                    settingsState = settingsState,
                    isScanning = isScanning,
                    onRescanAll = onRescanAll,
                    onClearLibrary = onClearLibrary,
                    onResetPreferences = onResetPreferences
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioOutputSection(
    settingsState: SettingsState,
    onAudioDeviceChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedDevice = settingsState.availableAudioDevices
        .firstOrNull { it.name == settingsState.selectedAudioDevice }

    Text(
        text = "Audio Output",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary
    )
    Spacer(modifier = Modifier.height(8.dp))

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = selectedDevice?.displayName ?: "Automatic (default)",
            onValueChange = {},
            readOnly = true,
            label = { Text("Device") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            settingsState.availableAudioDevices.forEach { device ->
                DropdownMenuItem(
                    text = { Text(device.displayName) },
                    onClick = {
                        onAudioDeviceChange(device.name)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun PlaybackSection(
    settingsState: SettingsState,
    onRepeatModeChange: (RepeatMode) -> Unit,
    onShuffleToggle: () -> Unit,
    onResumeModeChange: (ResumeMode) -> Unit
) {
    Text(
        text = "Playback",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary
    )
    Spacer(modifier = Modifier.height(8.dp))

    Text(
        text = "Repeat",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(4.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RepeatMode.entries.forEach { mode ->
            FilterChip(
                selected = settingsState.repeatMode == mode,
                onClick = { onRepeatModeChange(mode) },
                label = {
                    Text(
                        when (mode) {
                            RepeatMode.OFF -> "Off"
                            RepeatMode.ONE -> "One"
                            RepeatMode.ALL -> "All"
                        }
                    )
                }
            )
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Shuffle",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = settingsState.shuffleEnabled,
            onCheckedChange = { onShuffleToggle() }
        )
    }

    Spacer(modifier = Modifier.height(12.dp))

    Text(
        text = "Resume on startup",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(4.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ResumeMode.entries.forEach { mode ->
            FilterChip(
                selected = settingsState.resumeMode == mode,
                onClick = { onResumeModeChange(mode) },
                label = {
                    Text(
                        when (mode) {
                            ResumeMode.PAUSED -> "Paused"
                            ResumeMode.PLAYING -> "Playing"
                        }
                    )
                }
            )
        }
    }
}

@Composable
private fun LibrarySection(
    settingsState: SettingsState,
    isScanning: Boolean,
    onRescanAll: () -> Unit,
    onClearLibrary: () -> Unit,
    onResetPreferences: () -> Unit
) {
    Text(
        text = "Library",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary
    )
    Spacer(modifier = Modifier.height(8.dp))

    if (settingsState.libraryPath != null) {
        Text(
            text = settingsState.libraryPath,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "${settingsState.trackCount} tracks",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onRescanAll, enabled = !isScanning) {
            Text("Rescan All")
        }
        OutlinedButton(
            onClick = onClearLibrary,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            )
        ) {
            Text("Clear DB")
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    TextButton(
        onClick = onResetPreferences,
        colors = ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.colorScheme.error
        )
    ) {
        Text("Reset all preferences")
    }
}
