package com.zenpeq.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zenpeq.audio.BandGrid
import com.zenpeq.audio.EngineInfo
import com.zenpeq.audio.EngineRoute
import com.zenpeq.audio.EngineStatus
import com.zenpeq.audio.FilterMath
import com.zenpeq.model.EqFilter
import com.zenpeq.model.FilterType
import com.zenpeq.model.MAX_FILTERS
import com.zenpeq.model.SessionMode
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ZenApp(vm: EqViewModel = viewModel()) {
    val ctx = LocalContext.current
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val info by EngineStatus.info.collectAsStateWithLifecycle()
    var showImport by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var showSaveAs by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var ignoringBattery by remember { mutableStateOf(true) }

    // Re-check when returning from the system battery dialog.
    LifecycleResumeEffect(Unit) {
        ignoringBattery = ctx.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(ctx.packageName) ?: true
        onPauseOrDispose { }
    }

    if (Build.VERSION.SDK_INT >= 33) {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        LaunchedEffect(Unit) {
            val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { pad ->
        val d = draft
        if (d == null) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            val peak = remember(d.filters) {
                max(0.0, FilterMath.curve(d.filters, BandGrid(48).centers).max())
            }
            val sel = selected.coerceIn(0, max(0, d.filters.size - 1))
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("ZenPEQ", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                        Text(if (settings.eqEnabled) "ON" else "OFF", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.width(8.dp))
                        Switch(checked = settings.eqEnabled, onCheckedChange = vm::setEnabled)
                    }
                }
                if (!settings.batteryHintDismissed && !ignoringBattery) {
                    item {
                        BatteryHintCard(
                            onAllow = { requestIgnoreBattery(ctx) },
                            onDismiss = vm::dismissBatteryHint,
                        )
                    }
                }
                item { StatusCard(info) }
                item {
                    ZenCard {
                        Text("Profile", style = MaterialTheme.typography.labelLarge)
                        OutlinedButton(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                d.name + if (d.id == settings.iemProfileId) "  ★ IEM" else "",
                                modifier = Modifier.weight(1f),
                            )
                            Icon(Icons.Default.ArrowDropDown, null)
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { showSaveAs = true }) { Text("Save as…") }
                            TextButton(onClick = { showImport = true }) { Text("Import") }
                            TextButton(onClick = { showExport = true }) { Text("Export") }
                            TextButton(onClick = { vm.setIem(d.id) }, enabled = d.id != settings.iemProfileId) {
                                Icon(Icons.Default.Star, null)
                                Text(" Set as IEM")
                            }
                            TextButton(onClick = vm::deleteCurrent, enabled = profiles.size > 1) { Text("Delete") }
                        }
                    }
                }
                item {
                    ZenCard {
                        RowSwitch(
                            "Auto-switch on wired headset",
                            "Plug in 3.5 mm: load the ★ IEM profile and enable EQ. Unplug: bypass.",
                            settings.autoHeadset, vm::setAutoHeadset,
                        )
                    }
                }
                item {
                    ZenCard {
                        Text("Audio session", style = MaterialTheme.typography.titleSmall)
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
                            sessionHint(settings.sessionMode),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item {
                    ZenCard {
                        RowSwitch(
                            "Auto-Preamp",
                            "Lowers input gain by the highest positive boost to avoid clipping.",
                            d.autoPreamp, vm::setAutoPreamp,
                        )
                        if (d.autoPreamp) {
                            Text("Preamp: ${"%.1f".format(-peak)} dB", style = MaterialTheme.typography.bodyMedium)
                        } else {
                            LabeledSlider("Preamp", "%.1f dB".format(d.preampDb), d.preampDb, -24f..6f) {
                                vm.setPreamp((it * 10).roundToInt() / 10f)
                            }
                        }
                    }
                }
                item {
                    ZenCard {
                        FrequencyGraph(
                            filters = d.filters,
                            selected = sel,
                            onSelect = { selected = it },
                            onMove = vm::moveFilter,
                        )
                        GainFineTune(sel, d.filters.getOrNull(sel)) { vm.nudgeGain(sel, it) }
                        Text(
                            "Drag a point: ↔ frequency, ↕ gain · 20 Hz – 20 kHz, ±15 dB",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Bands ${d.filters.size}/$MAX_FILTERS", Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Button(onClick = vm::addFilter, enabled = d.filters.size < MAX_FILTERS) {
                            Icon(Icons.Default.Add, null)
                            Text("Add")
                        }
                    }
                }
                itemsIndexed(d.filters) { i, f ->
                    FilterCard(
                        index = i, f = f, highlighted = i == sel,
                        onSelect = { selected = i },
                        onChange = { vm.updateFilter(i, it) },
                        onDelete = { vm.removeFilter(i) },
                    )
                }
            }
        }
    }

    val current = draft
    if (showPicker) {
        ProfilePickerDialog(
            profiles = profiles,
            activeId = current?.id.orEmpty(),
            iemId = settings.iemProfileId,
            onSelect = vm::select,
            onDismiss = { showPicker = false },
        )
    }
    if (showImport) {
        ImportDialog(
            preview = vm::preview,
            onDismiss = { showImport = false },
            onConfirm = { name, p -> vm.importPreview(name, p); showImport = false },
        )
    }
    if (showExport && current != null) {
        ExportDialog(profile = current, onDismiss = { showExport = false })
    }
    if (showSaveAs) {
        NameDialog("Save profile as", "Custom EQ", onDismiss = { showSaveAs = false }) {
            vm.saveAs(it); showSaveAs = false
        }
    }
}

private fun sessionLabel(m: SessionMode) = when (m) {
    SessionMode.AUTO -> "Auto"
    SessionMode.GLOBAL -> "Global"
    SessionMode.PER_APP -> "Per-app"
}

private fun sessionHint(m: SessionMode) = when (m) {
    SessionMode.AUTO -> "Global session first; falls back to per-app sessions if the device rejects it."
    SessionMode.GLOBAL -> "Global session only."
    SessionMode.PER_APP ->
        "Attach to each player's own session. Use this if Global is accepted but you hear no change."
}

private fun requestIgnoreBattery(ctx: Context) {
    try {
        ctx.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")),
        )
    } catch (e: Exception) {
        runCatching { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
}

@Composable
private fun ZenCard(highlighted: Boolean = false, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = if (highlighted) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

@Composable
private fun BatteryHintCard(onAllow: () -> Unit, onDismiss: () -> Unit) {
    ZenCard {
        Text("Keep ZenPEQ running", style = MaterialTheme.typography.titleSmall)
        Text(
            "Battery optimization can stop the EQ service in the background. Allow ZenPEQ to ignore it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text("Dismiss") }
            Button(onClick = onAllow) { Text("Allow") }
        }
    }
}

@Composable
private fun StatusCard(info: EngineInfo) {
    ZenCard {
        val line = when {
            !info.serviceRunning -> "Engine starting…"
            info.route == EngineRoute.GLOBAL -> "EQ active · global session · ${info.bands} render bands"
            info.route == EngineRoute.PER_APP && info.appSessions > 0 ->
                "EQ active · ${info.appSessions} player session(s)"
            info.route == EngineRoute.PER_APP -> "Waiting for a player to open an audio session"
            info.error != null -> "Effect unavailable"
            else -> "Bypassed"
        }
        Text(line, style = MaterialTheme.typography.bodyMedium)
        Text(
            if (info.headsetConnected) "Wired headset connected" else "No wired headset",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        info.error?.let {
            if (info.route == EngineRoute.NONE) {
                Text(
                    "$it — try Per-app mode if the device rejects the global session.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                )
            } else {
                Text(
                    "Global session unavailable ($it) — using per-app sessions.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RowSwitch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun FilterCard(
    index: Int,
    f: EqFilter,
    highlighted: Boolean,
    onSelect: () -> Unit,
    onChange: (EqFilter) -> Unit,
    onDelete: () -> Unit,
) {
    ZenCard(highlighted) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Band ${index + 1}",
                Modifier.weight(1f).clickable(onClick = onSelect),
                style = MaterialTheme.typography.titleSmall,
            )
            FilterType.entries.forEach { t ->
                FilterChip(selected = f.type == t, onClick = { onChange(f.copy(type = t)) }, label = { Text(t.name) })
                Spacer(Modifier.width(4.dp))
            }
            Switch(checked = f.enabled, onCheckedChange = { onChange(f.copy(enabled = it)) })
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Remove band") }
        }
        LabeledSlider("Freq", formatHz(f.freq), ln(f.freq), ln(20f)..ln(20_000f)) {
            onChange(f.copy(freq = exp(it).let { hz -> if (hz < 1000) hz.roundToInt().toFloat() else (hz / 10).roundToInt() * 10f }))
        }
        LabeledSlider("Gain", "%.1f dB".format(f.gainDb), f.gainDb, -15f..15f) {
            onChange(f.copy(gainDb = (it * 10).roundToInt() / 10f))
        }
        LabeledSlider("Q", "%.2f".format(f.q), f.q, 0.1f..10f) {
            onChange(f.copy(q = (it * 100).roundToInt() / 100f))
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String, valueText: String, value: Float,
    range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(48.dp), style = MaterialTheme.typography.bodySmall)
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f))
        Text(valueText, Modifier.width(76.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
        confirmButton = { TextButton(onClick = { onOk(name) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
