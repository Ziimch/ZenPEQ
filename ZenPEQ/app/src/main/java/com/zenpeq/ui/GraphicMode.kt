package com.zenpeq.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.zenpeq.audio.EngineInfo
import com.zenpeq.audio.EngineRoute
import com.zenpeq.model.EqProfile
import com.zenpeq.model.GRAPHIC_COUNTS
import com.zenpeq.model.GraphicConfig
import com.zenpeq.model.MAX_GAIN_DB
import kotlin.math.roundToInt

private fun shortFreq(f: Float): String =
    if (f >= 1000f) "%.1fk".format(f / 1000f).replace(".0k", "k") else "%.0f".format(f)

/** 5–32 draggable band sliders over a configurable frequency span (±15 dB each). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GraphicBandsEditor(
    profile: EqProfile,
    onGain: (Int, Float) -> Unit,
    onConfig: (GraphicConfig) -> Unit,
    onReset: () -> Unit,
    onEditHz: (start: Boolean) -> Unit,
) {
    val cfg = profile.gcfg
    if (cfg == null || profile.graphic.size != cfg.count) {
        Text(
            "Graphic bands are off.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ZButton(onClick = { onConfig(GraphicConfig()) }) { Text("Enable 10 bands") }
        return
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GRAPHIC_COUNTS.forEach { n ->
            FilterChip(selected = cfg.count == n, onClick = { onConfig(cfg.copy(count = n)) }, label = { Text("$n") })
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        ZOutlinedButton(onClick = { onEditHz(true) }) { Text("From ${shortFreq(cfg.startHz)} Hz") }
        ZOutlinedButton(onClick = { onEditHz(false) }) { Text("To ${shortFreq(cfg.endHz)} Hz") }
        TextButton(onClick = onReset) { Text("Reset") }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val bandWidth = (maxWidth / cfg.count).coerceAtLeast(36.dp)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            profile.graphic.forEachIndexed { i, p ->
                VerticalBand(p.freq, p.gainDb, onGain = { onGain(i, it) }, modifier = Modifier.width(bandWidth))
            }
        }
    }
}

/** Vertical fader. A tap jumps to that level; a vertical drag moves it; horizontal drags scroll the strip. */
@Composable
private fun VerticalBand(freq: Float, gain: Float, onGain: (Float) -> Unit, modifier: Modifier) {
    val latest by rememberUpdatedState(onGain)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (gain == 0f) "0" else "%+.1f".format(gain),
            style = MaterialTheme.typography.labelSmall,
            color = if (gain == 0f) MaterialTheme.colorScheme.onSurfaceVariant else ZenPalette.Green,
        )
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(180.dp)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val h = size.height.toFloat()
                        val inset = 12.dp.toPx()
                        fun gainAt(y: Float): Float {
                            val ny = ((y - inset) / (h - 2 * inset)).coerceIn(0f, 1f)
                            return (0.5f - ny) * 2f * MAX_GAIN_DB
                        }
                        val slop = awaitVerticalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                        if (slop != null) {
                            latest(gainAt(slop.position.y))
                            verticalDrag(slop.id) { change ->
                                latest(gainAt(change.position.y))
                                change.consume()
                            }
                        } else if (currentEvent.changes.none { it.isConsumed }) {
                            latest(gainAt(down.position.y)) // plain tap
                        }
                    }
                },
        ) {
            val inset = 12.dp.toPx()
            val cx = size.width / 2f
            val cy = size.height / 2f
            val y = inset + (0.5f - gain / (2f * MAX_GAIN_DB)) * (size.height - 2 * inset)
            drawLine(ZenPalette.Border, Offset(cx, inset), Offset(cx, size.height - inset), 3.dp.toPx(), StrokeCap.Round)
            drawLine(ZenPalette.Border, Offset(cx - 9.dp.toPx(), cy), Offset(cx + 9.dp.toPx(), cy), 1.dp.toPx())
            if (gain != 0f) {
                drawLine(ZenPalette.Green.copy(alpha = 0.55f), Offset(cx, cy), Offset(cx, y), 3.dp.toPx(), StrokeCap.Round)
            }
            drawCircle(if (gain == 0f) ZenPalette.Border else ZenPalette.Green, 8.dp.toPx(), Offset(cx, y))
            drawCircle(ZenPalette.Ink0, 3.dp.toPx(), Offset(cx, y))
        }
        Text(shortFreq(freq), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Preamp / Bass / Treble knobs. With Auto on, the preamp knob just shows the level the engine chose. */
@Composable
internal fun ToneKnobs(
    profile: EqProfile,
    info: EngineInfo,
    onAuto: (Boolean) -> Unit,
    onPreamp: (Float) -> Unit,
    onBass: (Float) -> Unit,
    onTreble: (Float) -> Unit,
    onEdit: (NumericField) -> Unit,
) {
    fun r1(v: Float) = (v * 10).roundToInt() / 10f
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Top) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val shown = if (profile.autoPreamp) (if (info.route != EngineRoute.NONE) info.preampDb else 0f) else profile.preampDb
            Knob(
                label = "preamp", valueText = "%.1f dB".format(shown), value = shown, range = -24f..6f,
                enabled = !profile.autoPreamp,
                onChange = { onPreamp(r1(it)) },
                onLongPress = { onEdit(NumericField.PREAMP) },
                onReset = { onPreamp(0f) },
            )
            FilterChip(selected = profile.autoPreamp, onClick = { onAuto(!profile.autoPreamp) }, label = { Text("Auto") })
        }
        Knob(
            label = "bass", valueText = "%+.1f dB".format(profile.bassDb), value = profile.bassDb, range = -12f..12f,
            onChange = { onBass(r1(it)) },
            onLongPress = { onEdit(NumericField.BASS) },
            onReset = { onBass(0f) },
        )
        Knob(
            label = "treble", valueText = "%+.1f dB".format(profile.trebleDb), value = profile.trebleDb, range = -12f..12f,
            onChange = { onTreble(r1(it)) },
            onLongPress = { onEdit(NumericField.TREBLE) },
            onReset = { onTreble(0f) },
        )
    }
}
