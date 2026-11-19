package com.zenpeq.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zenpeq.model.EqProfile
import com.zenpeq.model.Mood
import com.zenpeq.model.ToneState
import com.zenpeq.model.isSimpleCompatible

/** Short, number-free description of where the orb sits. */
internal fun toneSummary(t: ToneState): String {
    val warm = when {
        t.x < -0.1f -> "Warm"
        t.x > 0.1f -> "Bright"
        else -> "Neutral"
    }
    val focus = when {
        t.y < -0.1f -> "Bass"
        t.y > 0.1f -> "Vocal"
        else -> "Balanced"
    }
    return "$warm · $focus"
}

/**
 * Zen Tone Pad: drag the jade orb. Horizontal = Warm ↔ Bright, vertical = Bass ↔ Vocal clarity.
 * The pad takes the whole touch so the list underneath doesn't scroll while you tune.
 */
@Composable
internal fun TonePad(tone: ToneState, onChange: (x: Float, y: Float) -> Unit) {
    val latestChange by rememberUpdatedState(onChange)
    val haptic = LocalHapticFeedback.current
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(
        fontSize = 11.sp, letterSpacing = 1.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val inset = with(LocalDensity.current) { 30.dp.toPx() }

    Canvas(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(20.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    fun apply(p: Offset) {
                        val nx = ((p.x - inset) / (w - 2 * inset)).coerceIn(0f, 1f) * 2f - 1f
                        val ny = 1f - ((p.y - inset) / (h - 2 * inset)).coerceIn(0f, 1f) * 2f
                        latestChange(nx, ny)
                    }
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    apply(down.position)
                    down.consume()
                    drag(down.id) { change ->
                        apply(change.position)
                        change.consume()
                    }
                }
            },
    ) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f
        val corner = CornerRadius(20.dp.toPx())
        val hairline = ZenPalette.Hairline
        val jade = ZenPalette.Jade

        drawRoundRect(Brush.verticalGradient(listOf(ZenPalette.Slate, ZenPalette.Obsidian)), cornerRadius = corner)
        drawRoundRect(hairline, cornerRadius = corner, style = Stroke(1.dp.toPx()))

        // ripples + crosshair
        val maxR = (w / 2f) - inset
        for (k in 1..3) {
            drawCircle(hairline.copy(alpha = 0.8f), radius = maxR * k / 3f, center = Offset(cx, cy), style = Stroke(1.dp.toPx()))
        }
        drawLine(hairline, Offset(inset, cy), Offset(w - inset, cy), 1.dp.toPx())
        drawLine(hairline, Offset(cx, inset), Offset(cx, h - inset), 1.dp.toPx())
        drawCircle(hairline, radius = 3.dp.toPx(), center = Offset(cx, cy))

        fun label(text: String, center: Offset) {
            val m = textMeasurer.measure(text, labelStyle)
            drawText(m, topLeft = Offset(center.x - m.size.width / 2f, center.y - m.size.height / 2f))
        }
        label("WARM", Offset(inset + 26.dp.toPx(), cy - 14.dp.toPx()))
        label("BRIGHT", Offset(w - inset - 28.dp.toPx(), cy - 14.dp.toPx()))
        label("VOCAL", Offset(cx, inset * 0.5f))
        label("BASS", Offset(cx, h - inset * 0.5f))

        // orb
        val pos = Offset(
            inset + (tone.x + 1f) / 2f * (w - 2 * inset),
            inset + (1f - tone.y) / 2f * (h - 2 * inset),
        )
        drawLine(jade.copy(alpha = 0.25f), Offset(cx, cy), pos, 1.5.dp.toPx())
        val glow = 56.dp.toPx()
        drawCircle(
            Brush.radialGradient(listOf(jade.copy(alpha = 0.55f), jade.copy(alpha = 0f)), center = pos, radius = glow),
            radius = glow, center = pos,
        )
        drawCircle(jade, radius = 11.dp.toPx(), center = pos)
        drawCircle(Color.White.copy(alpha = 0.9f), radius = 3.5.dp.toPx(), center = pos)
    }
}

/** Four one-tap moods. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MoodChips(profile: EqProfile, onMood: (Mood) -> Unit) {
    val current = if (profile.isSimpleCompatible()) profile.tone ?: ToneState() else null
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Mood.entries.forEach { m ->
            FilterChip(selected = current == m.tone, onClick = { onMood(m) }, label = { Text(m.label) })
        }
    }
}
