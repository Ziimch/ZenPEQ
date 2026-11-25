package com.zenpeq.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zenpeq.model.EqFilter
import com.zenpeq.model.FilterType
import com.zenpeq.model.MAX_GAIN_DB
import com.zenpeq.model.MAX_Q
import com.zenpeq.model.MIN_Q
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/** Numbered band pills 1, 2, 3 … and + Add, scrolling sideways. Cream marks the active band. */
@Composable
internal fun BandChips(
    filters: List<EqFilter>,
    selected: Int,
    canAdd: Boolean,
    onSelect: (Int) -> Unit,
    onAdd: () -> Unit,
) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        filters.forEachIndexed { i, f -> BandPill("${i + 1}", i == selected, f.enabled) { onSelect(i) } }
        Surface(
            onClick = onAdd,
            enabled = canAdd,
            shape = ZenShape,
            color = Color.Transparent,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Text(
                "+ Add",
                Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                style = MaterialTheme.typography.labelLarge,
                color = if (canAdd) ZenPalette.Green else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
            )
        }
    }
}

@Composable
private fun BandPill(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ZenShape,
        color = if (selected) ZenPalette.Cream else ZenPalette.Panel,
        border = BorderStroke(1.dp, if (selected) ZenPalette.Cream else ZenPalette.Border),
        modifier = Modifier.size(38.dp),
    ) {
        androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = when {
                    selected -> ZenPalette.Ink
                    enabled -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                },
            )
        }
    }
}

/** Type / Frequency / Gain / Q for the one selected band. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BandEditor(
    index: Int,
    f: EqFilter,
    onChange: (EqFilter) -> Unit,
    onStepGain: (Float) -> Unit,
    onStepQ: (Float) -> Unit,
    onEdit: (NumericField) -> Unit,
    onDelete: () -> Unit,
) {
    ZenCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("band ${index + 1}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Switch(checked = f.enabled, onCheckedChange = { onChange(f.copy(enabled = it)) })
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Remove band") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterType.entries.forEach { t ->
                FilterChip(selected = f.type == t, onClick = { onChange(f.copy(type = t)) }, label = { Text(typeLabel(t)) })
            }
        }
        LabeledSlider(
            "Frequency", formatHz(f.freq), ln(f.freq), ln(20f)..ln(20_000f),
            onChange = {
                onChange(f.copy(freq = exp(it).let { hz -> if (hz < 1000) hz.roundToInt().toFloat() else (hz / 10).roundToInt() * 10f }))
            },
            onValueClick = { onEdit(NumericField.FREQ) },
        )
        LabeledSlider(
            "Gain", "%.1f dB".format(f.gainDb), f.gainDb, -MAX_GAIN_DB..MAX_GAIN_DB,
            onChange = { onChange(f.copy(gainDb = (it * 10).roundToInt() / 10f)) },
            onValueClick = { onEdit(NumericField.GAIN) },
            step = 0.1f, onStep = onStepGain,
        )
        LabeledSlider(
            "Q", "%.2f".format(f.q), f.q, MIN_Q..MAX_Q,
            onChange = { onChange(f.copy(q = (it * 100).roundToInt() / 100f)) },
            onValueClick = { onEdit(NumericField.Q) },
            step = 0.1f, onStep = onStepQ,
        )
    }
}
