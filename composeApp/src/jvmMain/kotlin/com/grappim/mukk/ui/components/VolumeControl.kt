package com.grappim.mukk.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.grappim.mukk.ui.MukkTheme

@Composable
fun VolumeControl(
    volume: Float,
    onVolumeChange: (Double) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
            contentDescription = "Volume",
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(end = 4.dp)
        )
        Slider(
            value = volume,
            onValueChange = { onVolumeChange(it.toDouble()) },
            modifier = Modifier.width(120.dp),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.secondary,
                activeTrackColor = MaterialTheme.colorScheme.secondary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )
    }
}

@Preview
@Composable
private fun VolumeControlPreview() {
    MukkTheme {
        VolumeControl(volume = 0.67f, onVolumeChange = {})
    }
}
