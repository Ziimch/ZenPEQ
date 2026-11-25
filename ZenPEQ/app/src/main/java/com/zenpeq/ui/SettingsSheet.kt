package com.zenpeq.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zenpeq.audio.EngineInfo
import com.zenpeq.audio.EngineRoute
import com.zenpeq.model.EqProfile
import com.zenpeq.model.CompressorSettings
import com.zenpeq.model.EqSettings
import com.zenpeq.model.MAX_PREAMP_DB
import com.zenpeq.model.MIN_PREAMP_DB
import com.zenpeq.model.SessionMode
import kotlin.math.roundToInt

private fun engineLine(i: EngineInfo): String = when {
    !i.serviceRunning -> "Starting…"
    i.route == EngineRoute.GLOBAL -> "Global · ${i.bands} bands" + extras(i)
    i.route == EngineRoute.PER_APP && i.appSessions > 0 ->
        "Per-app · ${i.appSessions} player(s)" + extras(i)
    i.route == EngineRoute.PER_APP -> "Waiting for a player"
    i.error != null -> "Unavailable: ${i.error}"
    else -> "Bypassed"
}

private fun extras(i: EngineInfo) =
    (if (i.limiterActive) " · limiter" else "") + (if (i.compressorActive) " · compressor" else "")

private fun balanceText(db: Float): String = when {
    db > 0.04f -> "R +${"%.1f".format(db)} dB"
    db < -0.04f -> "L +${"%.1f".format(-db)} dB"
    else -> "Center"
}

private fun sessionLabel(m: SessionMode) = when (m) {
    SessionMode.AUTO -> "Auto"
    SessionMode.GLOBAL -> "Global"
    SessionMode.PER_APP -> "Per-app"
}

@Composable
private fun CompressorControls(c: CompressorSettings, onChange: (CompressorSettings) -> Unit) {
    fun r1(v: Float) = (v * 10).roundToInt() / 10f
    LabeledSlider("Threshold", "%.0f dB".format(c.thresholdDb), c.thresholdDb, -60f..0f,
        onChange = { onChange(c.copy(thresholdDb = it.roundToInt().toFloat())) })
    LabeledSlider("Ratio", "%.1f : 1".format(c.ratio), c.ratio, 1f..10f,
        onChange = { onChange(c.copy(ratio = r1(it))) })
    LabeledSlider("Attack", "%.0f ms".format(c.attackMs), c.attackMs, 1f..100f,
        onChange = { onChange(c.copy(attackMs = it.roundToInt().toFloat())) })
    LabeledSlider("Release", "%.0f ms".format(c.releaseMs), c.releaseMs, 10f..1000f,
        onChange = { onChange(c.copy(releaseMs = (it / 10).roundToInt() * 10f)) })
    LabeledSlider("Makeup gain", "%.1f dB".format(c.makeupDb), c.makeupDb, 0f..12f,
        onChange = { onChange(c.copy(makeupDb = r1(it))) }, step = 0.1f,
        onStep = { onChange(c.copy(makeupDb = r1((c.makeupDb + it).coerceIn(0f, 12f)))) })
}

@Composable
private fun SectionTitle(text: String) =
    Text(text.lowercase(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

/** All engine-level options live here so the main screen stays simple. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsSheet(
    profile: EqProfile,
    profiles: List<EqProfile>,
    settings: EqSettings,
    info: EngineInfo,
    ignoringBattery: Boolean,
    vm: EqViewModel,
    onEditPreamp: () -> Unit,
    onAllowBattery: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Settings", style = MaterialTheme.typography.headlineSmall)

            SectionTitle("Sound")
            RowSwitch("Auto-preamp", profile.autoPreamp, vm::setAutoPreamp, "Avoids clipping")
            if (!profile.autoPreamp) {
                LabeledSlider(
                    "Preamp", "%.1f dB".format(profile.preampDb), profile.preampDb, MIN_PREAMP_DB..MAX_PREAMP_DB,
                    onChange = { vm.setPreamp((it * 10).roundToInt() / 10f) },
                    onValueClick = onEditPreamp,
                    step = 0.1f, onStep = vm::nudgePreamp,
                )
            } else if (info.route != EngineRoute.NONE) {
                Text(
                    "Preamp ${"%.1f".format(info.preampDb)} dB",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RowSwitch("Limiter", settings.limiterEnabled, vm::setLimiter, "Peak protection")
            LabeledSlider(
                "Balance (L ◀ ▶ R)", balanceText(settings.balanceDb), settings.balanceDb, -3f..3f,
                onChange = vm::setBalance,
                step = 0.1f, onStep = vm::nudgeBalance,
            )

            HorizontalDivider()
            SectionTitle("Compressor")
            val c = settings.compressor
            RowSwitch("Compressor", c.enabled, { vm.setCompressor(c.copy(enabled = it)) }, "Evens out loud and quiet parts")
            if (c.enabled) CompressorControls(c, vm::setCompressor)

            HorizontalDivider()
            SectionTitle("Presets")
            RowSwitch("Autosave", settings.autosave, vm::setAutosave, "Off: use Save and Revert")

            HorizontalDivider()
            SectionTitle("Output")
            RowSwitch("Auto-switch by output", settings.autoDevice, vm::setAutoDevice, "A profile per device")
            if (info.deviceKey.isNotEmpty()) {
                val assigned = profiles.firstOrNull { it.id == settings.deviceProfiles[info.deviceKey] }
                Text(info.deviceLabel, style = MaterialTheme.typography.bodyMedium)
                Text(
                    assigned?.let { "Profile: ${it.name}" } ?: "No profile assigned",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row {
                    TextButton(onClick = { vm.assignDevice(info.deviceKey, profile.id) }) { Text("Use current profile") }
                    TextButton(onClick = { vm.clearAssignment(info.deviceKey) }, enabled = assigned != null) { Text("Clear") }
                }
            }

            HorizontalDivider()
            SectionTitle("Engine")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SessionMode.entries.forEach { m ->
                    FilterChip(
                        selected = settings.sessionMode == m,
                        onClick = { vm.setSessionMode(m) },
                        label = { Text(sessionLabel(m)) },
                    )
                }
            }
            Text(
                engineLine(info),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()
            SectionTitle("Background")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Keep running", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                if (ignoringBattery) {
                    Text("Allowed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    ZButton(onClick = onAllowBattery) { Text("Allow") }
                }
            }
        }
    }
}
