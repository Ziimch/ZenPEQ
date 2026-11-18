package com.zenpeq.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zenpeq.audio.FilterMath
import com.zenpeq.model.EqFilter
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow

private const val RANGE_DB = 15f

private fun xOf(hz: Float, w: Float) = (ln(hz / 20f) / ln(1000f)).coerceIn(0f, 1f) * w
private fun hzOf(x: Float, w: Float) = 20f * 1000f.pow((x / w).coerceIn(0f, 1f))
private fun yOf(db: Float, h: Float) = h * (0.5f - db.coerceIn(-RANGE_DB, RANGE_DB) / (2 * RANGE_DB))
private fun dbOf(y: Float, h: Float) = (0.5f - (y / h).coerceIn(0f, 1f)) * 2 * RANGE_DB

internal fun formatHz(hz: Float) = if (hz >= 1000f) "%.2f kHz".format(hz / 1000f) else "%.0f Hz".format(hz)

/**
 * Response curve with one draggable point per band: horizontal = log frequency, vertical = gain.
 * A touch that doesn't land on a point is left alone so the surrounding list can still scroll.
 */
@Composable
fun FrequencyGraph(
    filters: List<EqFilter>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onMove: (index: Int, hz: Float, db: Float) -> Unit,
) {
    val freqs = remember { DoubleArray(200) { 20.0 * 1000.0.pow(it / 199.0) } }
    val curve = remember(filters) { FilterMath.curve(filters, freqs) }
    val latestFilters by rememberUpdatedState(filters)
    val latestSelect by rememberUpdatedState(onSelect)
    val latestMove by rememberUpdatedState(onMove)
    val hitRadius = with(LocalDensity.current) { 32.dp.toPx() }

    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
    val pointColor = MaterialTheme.colorScheme.secondary
    val offColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(200.dp)
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
        }
        for (hz in listOf(100f, 1000f, 10000f)) {
            drawLine(grid, Offset(xOf(hz, w), 0f), Offset(xOf(hz, w), h), 1.dp.toPx())
        }
        val path = Path()
        curve.forEachIndexed { i, db ->
            val x = w * i / (curve.size - 1)
            if (i == 0) path.moveTo(x, yOf(db.toFloat(), h)) else path.lineTo(x, yOf(db.toFloat(), h))
        }
        drawPath(path, line, style = Stroke(width = 3.dp.toPx()))

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

/** ±0.1 dB steppers for the selected band. */
@Composable
fun GainFineTune(index: Int, filter: EqFilter?, onNudge: (Float) -> Unit) {
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
        Text("%.1f dB".format(filter.gainDb), Modifier.width(76.dp), textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { onNudge(0.1f) }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("+0.1") }
    }
}
