package com.zenpeq.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** "Boutique Japanese audio gear": obsidian, slate, hairlines, jade signal, amber selection. */
object ZenPalette {
    val Obsidian = Color(0xFF0B0E11)
    val Slate = Color(0xFF141920)
    val SlateHigh = Color(0xFF1A212A)
    val Hairline = Color(0xFF232B36)
    val Jade = Color(0xFF2EE6B8)
    val Amber = Color(0xFFFFB74D)
    val OnAmber = Color(0xFF2A1B00)
}

private val ZenColors = darkColorScheme(
    primary = ZenPalette.Jade,
    onPrimary = Color(0xFF00382B),
    primaryContainer = Color(0xFF0F3D33),
    onPrimaryContainer = ZenPalette.Jade,
    secondary = ZenPalette.Amber,
    onSecondary = ZenPalette.OnAmber,
    secondaryContainer = Color(0xFF3A2A10), // selected chips / segments read as amber
    onSecondaryContainer = ZenPalette.Amber,
    tertiary = ZenPalette.Amber,
    background = ZenPalette.Obsidian,
    onBackground = Color(0xFFE6EAEE),
    surface = ZenPalette.Slate,
    onSurface = Color(0xFFE6EAEE),
    surfaceVariant = ZenPalette.SlateHigh,
    onSurfaceVariant = Color(0xFF9AA6B2),
    surfaceContainerLowest = ZenPalette.Obsidian,
    surfaceContainerLow = ZenPalette.Slate,
    surfaceContainer = ZenPalette.Slate,
    surfaceContainerHigh = ZenPalette.SlateHigh,
    surfaceContainerHighest = ZenPalette.SlateHigh,
    outline = ZenPalette.Hairline,
    outlineVariant = ZenPalette.Hairline,
    error = Color(0xFFFF6B6B),
)

@Composable
fun ZenTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ZenColors, content = content)
}
