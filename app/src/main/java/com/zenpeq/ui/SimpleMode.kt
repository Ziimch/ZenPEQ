package com.zenpeq.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zenpeq.model.EqProfile
import com.zenpeq.model.Macro
import com.zenpeq.model.SimpleMacros
import com.zenpeq.model.SimplePreset
import com.zenpeq.model.isSimpleCompatible
import kotlin.math.roundToInt

private fun macroLabel(m: Macro) = when (m) {
    Macro.BASS -> "Bass"
    Macro.WARMTH -> "Warmth"
    Macro.VOCALS -> "Vocals"
    Macro.TREBLE -> "Treble"
}

private fun macroValue(m: SimpleMacros, k: Macro) = when (k) {
    Macro.BASS -> m.bass
    Macro.WARMTH -> m.warmth
    Macro.VOCALS -> m.vocals
    Macro.TREBLE -> m.treble
}

/** One-tap presets. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SimplePresets(profile: EqProfile, onPreset: (SimplePreset) -> Unit) {
    val compatible = profile.isSimpleCompatible()
    val current = if (compatible) profile.macros ?: SimpleMacros() else null
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SimplePreset.entries.forEach { p ->
            FilterChip(selected = current == p.macros, onClick = { onPreset(p) }, label = { Text(p.label) })
        }
    }
}

/** Bass / Warmth / Vocals / Treble sliders, no Hz or Q shown. */
@Composable
internal fun MacroSliders(profile: EqProfile, onChange: (Macro, Float) -> Unit) {
    val compatible = profile.isSimpleCompatible()
    val m = if (compatible) profile.macros ?: SimpleMacros() else SimpleMacros()
    Macro.entries.forEach { k ->
        val v = macroValue(m, k)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(macroLabel(k), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Text(
                "%+.1f dB".format(v),
                style = MaterialTheme.typography.bodyMedium,
                color = if (v == 0f) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = v,
            onValueChange = { onChange(k, (it * 2).roundToInt() / 2f) },
            valueRange = -6f..6f,
            steps = 23, // 0.5 dB steps
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (!compatible) {
        Text(
            "Custom curve from Pro. Move a slider to start over.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
