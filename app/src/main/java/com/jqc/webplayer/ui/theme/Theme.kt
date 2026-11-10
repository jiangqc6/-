package com.jqc.webplayer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AppColors = darkColorScheme(
    primary = Color(0xFF7BD88F),
    onPrimary = Color(0xFF0A2010),
    secondary = Color(0xFF8AB4F8),
    background = Color(0xFF101214),
    onBackground = Color(0xFFE6E8EA),
    surface = Color(0xFF171A1D),
    onSurface = Color(0xFFE6E8EA),
    surfaceVariant = Color(0xFF24292E),
    onSurfaceVariant = Color(0xFFB7BEC6),
    error = Color(0xFFFF8A80),
)

@Composable
fun WebPlayerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AppColors, content = content)
}
