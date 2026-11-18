package com.zenpeq.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
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

/**
 * Log-frequency response graph. Individual band curves are drawn subtly, the GraphicEQ layer dashed,
 * and the summed curve bright. Each band has a draggable point (horizontal = frequency, vertical = gain);
 * touches that miss every point are left alone so the surrounding list still scrolls.
 */
@Composable
fun FrequencyGraph(
    filters: List<EqFilter>,
    graphic: List<GraphicPoint>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onMove: (index: Int, hz: Float, db: Float) -> Unit,
) {
    val freqs = remember { DoubleArray(POINTS) { 20.0 * 1000.0.pow(it / (POINTS - 1.0)) } }
    val summed = remember(filters, graphic) { FilterMath.curve(filters, freqs, graphic) }
    val perBand = remember(filters) { filters.map { if (it.enabled) FilterMath.filterCurve(it, freqs) else null } }
    val graphicCurve = remember(graphic) { if (graphic.isEmpty()) null else FilterMath.graphicCurve(graphic, freqs) }
    val latestFilters by rememberUpdatedState(filters)
    val latestSelect by rememberUpdatedState(onSelect)
    val latestMove by rememberUpdatedState(onMove)
    val hitRadius = with(LocalDensity.current) { 32.dp.toPx() }

    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
    val pointColor = MaterialTheme.colorScheme.secondary
    val offColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(220.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
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
            drawText(textMeasurer, "%+.0f".format(db), Offset(2.dp.toPx(), yOf(db, h) - 12.dp.toPx()), labelStyle)
        }
        for ((hz, name) in listOf(100f to "100", 1000f to "1k", 10000f to "10k")) {
            drawLine(grid, Offset(xOf(hz, w), 0f), Offset(xOf(hz, w), h), 1.dp.toPx())
            drawText(textMeasurer, name, Offset(xOf(hz, w) + 3.dp.toPx(), h - 14.dp.toPx()), labelStyle)
        }

        // Individual band curves, subtle (selected one slightly stronger).
        perBand.forEachIndexed { i, c ->
            if (c != null) {
                drawPath(
                    curvePath(c),
                    pointColor.copy(alpha = if (i == selected) 0.75f else 0.28f),
                    style = Stroke(width = if (i == selected) 2.dp.toPx() else 1.dp.toPx()),
                )
            }
        }
        graphicCurve?.let {
            drawPath(
                curvePath(it), pointColor.copy(alpha = 0.7f),
                style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
            )
        }
        // Summed response, bright.
        drawPath(curvePath(summed), line, style = Stroke(width = 3.dp.toPx()))

        filters.forEachIndexed { i, f ->
            val c = Offset(xOf(f.freq, w), yOf(f.gainDb, h))
            val color: Color = if (f.enabled) pointColor else offColor
            if (i == selected) {
                drawCircle(Color.White, radius = 12.dp.toPx(), center = c, style = Stroke(2.dp.toPx()))
                drawCircle(line, radius = 8.dp.toPx(), center = c)
            } else {
                drawCircle(color, radius = 6.dp.toPx(), center = c)
            }
        }
    }
}

/** ±0.1 dB steppers for the selected band; tap the value to type an exact gain. */
@Composable
fun GainFineTune(index: Int, filter: EqFilter?, onNudge: (Float) -> Unit, onEdit: () -> Unit) {
    if (filter == null) {
        Text("Add a band to edit it on the graph", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Band ${index + 1} · ${formatHz(filter.freq)}",
            modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = { onNudge(-0.1f) }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("−0.1") }
        Text(
            "%.1f dB".format(filter.gainDb),
            Modifier.width(76.dp).clickable(onClick = onEdit),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = { onNudge(0.1f) }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("+0.1") }
    }
}
