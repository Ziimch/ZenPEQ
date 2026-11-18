package com.zenpeq.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ZenColors = darkColorScheme(
    primary = Color(0xFF2DD4BF),
    onPrimary = Color(0xFF00201C),
    secondary = Color(0xFF7AA2F7),
    background = Color(0xFF0E1114),
    onBackground = Color(0xFFE6EAEE),
    surface = Color(0xFF14181D),
    onSurface = Color(0xFFE6EAEE),
    surfaceVariant = Color(0xFF1C2229),
    onSurfaceVariant = Color(0xFFB4BDC6),
    error = Color(0xFFFF6B6B),
)

@Composable
fun ZenTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ZenColors, content = content)
}
