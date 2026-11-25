package com.zenpeq.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zenpeq.audio.FilterMath
import com.zenpeq.model.EqFilter
import com.zenpeq.model.GraphicPoint
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow

private const val RANGE_DB = 15f
private const val POINTS = 160

private fun xOf(hz: Float, w: Float) = (ln(hz / 20f) / ln(1000f)).coerceIn(0f, 1f) * w
private fun hzOf(x: Float, w: Float) = 20f * 1000f.pow((x / w).coerceIn(0f, 1f))
private fun yOf(db: Float, h: Float) = h * (0.5f - db.coerceIn(-RANGE_DB, RANGE_DB) / (2 * RANGE_DB))
private fun dbOf(y: Float, h: Float) = (0.5f - (y / h).coerceIn(0f, 1f)) * 2 * RANGE_DB

internal fun formatHz(hz: Float) = if (hz >= 1000f) "%.2f kHz".format(hz / 1000f) else "%.0f Hz".format(hz)

private fun DrawScope.curvePath(curve: DoubleArray): Path {
    val path = Path()
    curve.forEachIndexed { i, db ->
        val x = size.width * i / (curve.size - 1)
        if (i == 0) path.moveTo(x, yOf(db.toFloat(), size.height)) else path.lineTo(x, yOf(db.toFloat(), size.height))
    }
    return path
}

/** Same curve closed down to the bottom edge, for the gradient fill. */
private fun DrawScope.fillPath(curve: DoubleArray): Path {
    val path = curvePath(curve)
    path.lineTo(size.width, size.height)
    path.lineTo(0f, size.height)
    path.close()
    return path
}

/**
 * Log-frequency response graph: jade summed curve over a soft gradient, thin per-band curves
 * (the selected band in amber), dashed GraphicEQ layer. Each band has a draggable point
 * (horizontal = frequency, vertical = gain); touches that miss every point are left to the list.
 */
@Composable
fun FrequencyGraph(
    filters: List<EqFilter>,
    graphic: List<GraphicPoint>,
    extra: List<EqFilter> = emptyList(),
    selected: Int = 0,
    onSelect: (Int) -> Unit = {},
    onMove: (index: Int, hz: Float, db: Float) -> Unit = { _, _, _ -> },
    interactive: Boolean = true,
    showLabels: Boolean = true,
    height: Dp = 220.dp,
) {
    val freqs = remember { DoubleArray(POINTS) { 20.0 * 1000.0.pow(it / (POINTS - 1.0)) } }
    val summed = remember(filters, extra, graphic) { FilterMath.curve(filters + extra, freqs, graphic) }
    val perBand = remember(filters) { filters.map { if (it.enabled) FilterMath.filterCurve(it, freqs) else null } }
    val graphicCurve = remember(graphic) { if (graphic.isEmpty()) null else FilterMath.graphicCurve(graphic, freqs) }
    val latestFilters by rememberUpdatedState(filters)
    val latestSelect by rememberUpdatedState(onSelect)
    val latestMove by rememberUpdatedState(onMove)
    val hitRadius = with(LocalDensity.current) { 32.dp.toPx() }

    val jade = ZenPalette.Green
    val amber = ZenPalette.Orange
    val grid = ZenPalette.Border
    val muted = MaterialTheme.colorScheme.onSurface
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(interactive) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!interactive) return@awaitEachGesture // leave the touch to the scrolling list
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    var best = -1
                    var bestDist = hitRadius
                    latestFilters.forEachIndexed { i, f ->
                        val dist = hypot(xOf(f.freq, w) - down.position.x, yOf(f.gainDb, h) - down.position.y)
                        if (dist < bestDist) { bestDist = dist; best = i }
                    }
                    if (best < 0) return@awaitEachGesture
                    latestSelect(best)
                    var px = xOf(latestFilters[best].freq, w)
                    var py = yOf(latestFilters[best].gainDb, h)
                    drag(down.id) { change ->
                        val d = change.positionChange()
                        px = (px + d.x).coerceIn(0f, w)
                        py = (py + d.y).coerceIn(0f, h)
                        latestMove(best, hzOf(px, w), dbOf(py, h))
                        change.consume()
                    }
                }
            },
    ) {
        val w = size.width
        val h = size.height
        for (db in listOf(-10f, 0f, 10f)) {
            drawLine(grid, Offset(0f, yOf(db, h)), Offset(w, yOf(db, h)), 1.dp.toPx())
            if (showLabels) drawText(textMeasurer, "%+.0f".format(db), Offset(2.dp.toPx(), yOf(db, h) - 12.dp.toPx()), labelStyle)
        }
        for ((hz, name) in listOf(100f to "100", 1000f to "1k", 10000f to "10k")) {
            drawLine(grid, Offset(xOf(hz, w), 0f), Offset(xOf(hz, w), h), 1.dp.toPx())
            if (showLabels) drawText(textMeasurer, name, Offset(xOf(hz, w) + 3.dp.toPx(), h - 14.dp.toPx()), labelStyle)
        }

        // soft gradient beneath the summed curve
        drawPath(
            fillPath(summed),
            Brush.verticalGradient(listOf(jade.copy(alpha = 0.28f), jade.copy(alpha = 0f))),
        )

        // thin individual band curves; the selected band in amber
        if (interactive) perBand.forEachIndexed { i, c ->
            if (c != null) {
                drawPath(
                    curvePath(c),
                    if (i == selected) amber.copy(alpha = 0.85f) else muted.copy(alpha = 0.22f),
                    style = Stroke(width = if (i == selected) 1.75.dp.toPx() else 1.dp.toPx()),
                )
            }
        }
        graphicCurve?.let {
            drawPath(
                curvePath(it), muted.copy(alpha = 0.5f),
                style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
            )
        }

        // luminous summed curve: wide faint stroke under a crisp one
        val sumPath = curvePath(summed)
        drawPath(sumPath, jade.copy(alpha = 0.18f), style = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round))
        drawPath(sumPath, jade, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))

        if (interactive) filters.forEachIndexed { i, f ->
            val c = Offset(xOf(f.freq, w), yOf(f.gainDb, h))
            if (i == selected) {
                drawCircle(amber.copy(alpha = 0.22f), radius = 15.dp.toPx(), center = c)
                drawCircle(amber, radius = 7.dp.toPx(), center = c)
                drawCircle(ZenPalette.Ink0, radius = 2.5.dp.toPx(), center = c)
            } else {
                val ring: Color = if (f.enabled) muted.copy(alpha = 0.7f) else muted.copy(alpha = 0.3f)
                drawCircle(ring, radius = 5.dp.toPx(), center = c, style = Stroke(1.5.dp.toPx()))
            }
        }
    }
}
