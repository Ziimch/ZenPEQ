package com.zenpeq.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Rotary control: drag up/down to change, long-press to type an exact value, double-tap to reset.
 * The arc starts at 0 when 0 is inside the range, so cuts and boosts read at a glance.
 */
@Composable
internal fun Knob(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onLongPress: () -> Unit = {},
    onReset: () -> Unit = {},
) {
    val latestValue by rememberUpdatedState(value)
    val latestChange by rememberUpdatedState(onChange)
    val latestLong by rememberUpdatedState(onLongPress)
    val latestReset by rememberUpdatedState(onReset)
    val jade = if (enabled) ZenPalette.Green else MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            Modifier
                .size(68.dp)
                .pointerInput(enabled) {
                    if (enabled) detectTapGestures(onLongPress = { latestLong() }, onDoubleTap = { latestReset() })
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var v = latestValue
                        val span = range.endInclusive - range.start
                        drag(down.id) { change ->
                            v = (v - change.positionChange().y / 220.dp.toPx() * span)
                                .coerceIn(range.start, range.endInclusive)
                            latestChange(v)
                            change.consume()
                        }
                    }
                },
        ) {
            val stroke = 6.dp.toPx()
            val pad = stroke / 2f + 2.dp.toPx()
            val topLeft = Offset(pad, pad)
            val arcSize = Size(size.width - 2 * pad, size.height - 2 * pad)
            drawArc(
                color = ZenPalette.Border, startAngle = 135f, sweepAngle = 270f, useCenter = false,
                topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round),
            )
            val frac = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
            val zero = if (range.start < 0f && range.endInclusive > 0f) -range.start / (range.endInclusive - range.start) else 0f
            val a0 = zero * 270f
            val a1 = frac * 270f
            if (abs(a1 - a0) > 0.5f) {
                drawArc(
                    color = jade, startAngle = 135f + min(a0, a1), sweepAngle = abs(a1 - a0), useCenter = false,
                    topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
            val rad = Math.toRadians((135f + a1).toDouble())
            val r = arcSize.width / 2f
            val c = Offset(size.width / 2f, size.height / 2f)
            drawCircle(
                color = if (enabled) ZenPalette.Orange else jade, radius = 4.dp.toPx(),
                center = Offset(c.x + r * cos(rad).toFloat(), c.y + r * sin(rad).toFloat()),
            )
        }
        Text(valueText, style = MaterialTheme.typography.labelMedium, color = if (enabled) jade else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
