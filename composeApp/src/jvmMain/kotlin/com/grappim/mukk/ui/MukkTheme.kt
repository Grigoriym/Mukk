package com.grappim.mukk.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val MukkDarkColorScheme = darkColorScheme(
    primary = Color(0xFFBB86FC),
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF3700B3),
    secondary = Color(0xFF03DAC6),
    onSecondary = Color(0xFF000000),
    background = Color(0xFF1A1A2E),
    onBackground = Color(0xFFE0E0E0),
    surface = Color(0xFF16213E),
    onSurface = Color(0xFFE0E0E0),
    surfaceVariant = Color(0xFF0F3460),
    onSurfaceVariant = Color(0xFFCACACA),
    error = Color(0xFFCF6679),
    onError = Color(0xFF000000)
)

@Composable
fun MukkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MukkDarkColorScheme,
        content = content
    )
}
