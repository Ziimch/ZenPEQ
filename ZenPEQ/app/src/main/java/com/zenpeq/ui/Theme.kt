package com.zenpeq.ui

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zenpeq.R

/**
 * Omarchy "Kanagawa": the same palette as the launcher widgets (sampled from them). Square windows,
 * 1 dp lavender-grey borders, cream fills for the active thing, JetBrains Mono everywhere.
 */
object ZenPalette {
    val Ink0 = Color(0xFF16161D)       // sumiInk0, deepest background
    val Ink = Color(0xFF1F1F28)        // sumiInk1, wallpaper base / text on cream
    val Panel = Color(0xFF2A2A37)      // window background
    val PanelHigh = Color(0xFF363646)  // raised: chips, menus
    val Border = Color(0xFF54546D)     // window border (launcher uses #54546C)
    val Cream = Color(0xFFDCD7BA)      // fujiWhite: text and active fills
    val Dim = Color(0xFFA6A69C)        // secondary text
    val Green = Color(0xFF98BB6C)      // springGreen: primary
    val Blue = Color(0xFF7E9CD8)       // crystalBlue
    val WaveBlue = Color(0xFF223249)   // bar tracks
    val Orange = Color(0xFFFF9E3B)     // roninYellow: selection highlight
    val Red = Color(0xFFE46876)        // waveRed: errors
}

private val ZenColors = darkColorScheme(
    primary = ZenPalette.Green,
    onPrimary = ZenPalette.Ink0,
    primaryContainer = ZenPalette.WaveBlue,
    onPrimaryContainer = ZenPalette.Cream,
    secondary = ZenPalette.Blue,
    onSecondary = ZenPalette.Ink0,
    secondaryContainer = ZenPalette.Cream, // selected chips / segments: filled cream like the launcher
    onSecondaryContainer = ZenPalette.Ink,
    tertiary = ZenPalette.Orange,
    background = ZenPalette.Ink0,
    onBackground = ZenPalette.Cream,
    surface = ZenPalette.Panel,
    onSurface = ZenPalette.Cream,
    surfaceVariant = ZenPalette.PanelHigh,
    onSurfaceVariant = ZenPalette.Dim,
    surfaceContainerLowest = ZenPalette.Ink0,
    surfaceContainerLow = ZenPalette.Panel,
    surfaceContainer = ZenPalette.Panel,
    surfaceContainerHigh = ZenPalette.PanelHigh,
    surfaceContainerHighest = ZenPalette.PanelHigh,
    outline = ZenPalette.Border,
    outlineVariant = ZenPalette.Border,
    error = ZenPalette.Red,
)

private val Mono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

private val ZenTypography = Typography().let { t ->
    Typography(
        displayLarge = t.displayLarge.copy(fontFamily = Mono),
        displayMedium = t.displayMedium.copy(fontFamily = Mono),
        displaySmall = t.displaySmall.copy(fontFamily = Mono),
        headlineLarge = t.headlineLarge.copy(fontFamily = Mono),
        headlineMedium = t.headlineMedium.copy(fontFamily = Mono),
        headlineSmall = t.headlineSmall.copy(fontFamily = Mono),
        titleLarge = t.titleLarge.copy(fontFamily = Mono),
        titleMedium = t.titleMedium.copy(fontFamily = Mono),
        titleSmall = t.titleSmall.copy(fontFamily = Mono),
        bodyLarge = t.bodyLarge.copy(fontFamily = Mono),
        bodyMedium = t.bodyMedium.copy(fontFamily = Mono),
        bodySmall = t.bodySmall.copy(fontFamily = Mono),
        labelLarge = t.labelLarge.copy(fontFamily = Mono),
        labelMedium = t.labelMedium.copy(fontFamily = Mono),
        labelSmall = t.labelSmall.copy(fontFamily = Mono),
    )
}

private val ZenShapes = Shapes(
    extraSmall = ZenCorner, small = ZenCorner, medium = ZenCorner, large = ZenCorner, extraLarge = ZenCorner,
)

/**
 * Ink background with a faint seigaiha (wave scale) pattern, a nod to the launcher's Great Wave wallpaper.
 * Each row of scales is filled so it overlaps the one above, as in the real pattern.
 */
fun Modifier.zenBackground(): Modifier = this
    .background(ZenPalette.Ink)
    .drawBehind {
        val r = 30.dp.toPx()
        val line = ZenPalette.Border.copy(alpha = 0.28f)
        val stroke = Stroke(1.dp.toPx())
        var row = 0
        var y = -r
        while (y < size.height + r) {
            var x = -r + if (row % 2 == 0) 0f else r
            while (x < size.width + r) {
                drawCircle(ZenPalette.Ink, radius = r, center = Offset(x, y))
                for (k in 1..3) drawCircle(line, radius = r * k / 3f, center = Offset(x, y), style = stroke)
                x += r * 2f
            }
            y += r * 0.5f
            row++
        }
    }

@Composable
fun ZenTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ZenColors, typography = ZenTypography, shapes = ZenShapes, content = content)
}
