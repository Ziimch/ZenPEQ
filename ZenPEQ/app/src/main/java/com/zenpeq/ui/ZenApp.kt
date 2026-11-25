package com.zenpeq.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zenpeq.R
import com.zenpeq.audio.EngineInfo
import com.zenpeq.audio.EngineRoute
import com.zenpeq.audio.EngineStatus
import com.zenpeq.data.AutoEqSuggestions
import com.zenpeq.model.MAX_FILTERS
import com.zenpeq.model.ToneState
import com.zenpeq.model.UiMode
import com.zenpeq.model.toneFilters
import com.zenpeq.model.isSimpleCompatible
import kotlin.math.max

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZenApp(vm: EqViewModel = viewModel()) {
    val ctx = LocalContext.current
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val info by EngineStatus.info.collectAsStateWithLifecycle()
    val dirty by vm.dirty.collectAsStateWithLifecycle()
    val suggestion by AutoEqSuggestions.current.collectAsStateWithLifecycle()
    var showImport by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var showSaveAs by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showAutoEq by remember { mutableStateOf(false) }
    var numeric by remember { mutableStateOf<Pair<Int, NumericField>?>(null) } // (band index, field)
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

    Box(Modifier.fillMaxSize().zenBackground()) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopBar(
                info = info,
                eqOn = settings.eqEnabled,
                onEq = vm::setEnabled,
                onSettings = { showSettings = true },
            )
        },
    ) { pad ->
        val d = draft
        if (d == null) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            val sel = selected.coerceIn(0, max(0, d.filters.size - 1))
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!settings.batteryHintDismissed && !ignoringBattery) {
                    item {
                        ZenCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Keep EQ running in background", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { requestIgnoreBattery(ctx) }) { Text("Allow") }
                                IconButton(onClick = vm::dismissBatteryHint) { Icon(Icons.Default.Close, "Dismiss") }
                            }
                        }
                    }
                }
                suggestion?.let { sug ->
                    item {
                        ZenCard {
                            Text("AutoEq preset for ${sug.deviceLabel}", style = MaterialTheme.typography.titleSmall)
                            Text(
                                sug.entry.name + " · " + sug.entry.source,
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { vm.dismissSuggestion(sug) }) { Text("Dismiss") }
                                ZButton(onClick = { vm.applySuggestion(sug) }) { Text("Apply") }
                            }
                        }
                    }
                }
                if (d.locked) {
                    item {
                        ZenCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Preset locked", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                TextButton(onClick = vm::toggleLock) { Text("Unlock") }
                            }
                        }
                    }
                }
                if (settings.eqEnabled && info.route == EngineRoute.NONE && info.error != null) {
                    item {
                        ZenCard {
                            Text(
                                "EQ unavailable on this device. Try Per-app in Settings.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                item {
                    ProfileBar(
                        name = d.name,
                        isIem = d.id == settings.iemProfileId,
                        locked = d.locked,
                        dirty = dirty,
                        canDelete = profiles.size > 1,
                        onPick = { showPicker = true },
                        onSaveAs = { showSaveAs = true },
                        onImport = { showImport = true },
                        onExport = { showExport = true },
                        onAutoEq = { showAutoEq = true },
                        onLock = vm::toggleLock,
                        onSetIem = { vm.setIem(d.id) },
                        onDelete = { showDelete = true },
                        onSave = vm::saveNow,
                        onRevert = vm::revert,
                    )
                }
                item {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        UiMode.entries.forEachIndexed { i, m ->
                            SegmentedButton(
                                selected = settings.uiMode == m,
                                onClick = { vm.setUiMode(m) },
                                shape = SegmentedButtonDefaults.itemShape(i, UiMode.entries.size, ZenCorner),
                            ) { Text(m.name.lowercase()) }
                        }
                    }
                }

                when (settings.uiMode) {
                    UiMode.SIMPLE -> {
                        val compatible = d.isSimpleCompatible()
                        val tone = if (compatible) d.tone ?: ToneState() else ToneState()
                        item {
                            ZenCard {
                                TonePad(tone, onChange = vm::setTone)
                                Text(
                                    if (compatible) toneSummary(tone) else "Custom curve · drag to start over",
                                    Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Center,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        item { MoodChips(d, vm::applyMood) }
                    }

                    UiMode.GRAPHIC -> {
                        item {
                            ZenCard {
                                FrequencyGraph(
                                    filters = d.filters, graphic = d.graphic, extra = d.toneFilters(),
                                    interactive = false, height = 130.dp,
                                )
                            }
                        }
                        item {
                            ZenCard {
                                GraphicBandsEditor(
                                    profile = d,
                                    onGain = vm::setGraphicGain,
                                    onConfig = vm::setGraphicConfig,
                                    onReset = vm::resetGraphic,
                                    onEditHz = { start -> numeric = -1 to if (start) NumericField.GSTART else NumericField.GEND },
                                )
                            }
                        }
                        item { ToneCard(d, info, vm) { numeric = -1 to it } }
                    }

                    UiMode.PRO -> {
                        item {
                            ZenCard {
                                FrequencyGraph(
                                    filters = d.filters, graphic = d.graphic, extra = d.toneFilters(), selected = sel,
                                    onSelect = { selected = it }, onMove = vm::moveFilter,
                                )
                                if (d.graphic.isNotEmpty()) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            "Graphic layer · ${d.graphic.size} pts",
                                            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                                        )
                                        TextButton(onClick = vm::clearGraphic) { Text("Clear") }
                                    }
                                }
                            }
                        }
                        item {
                            BandChips(
                                filters = d.filters, selected = sel, canAdd = d.filters.size < MAX_FILTERS,
                                onSelect = { selected = it },
                                onAdd = { selected = d.filters.size; vm.addFilter() },
                            )
                        }
                        val f = d.filters.getOrNull(sel)
                        if (f == null) {
                            item {
                                Text(
                                    "No bands yet. Tap + Add.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            item {
                                BandEditor(
                                    index = sel, f = f,
                                    onChange = { vm.updateFilter(sel, it) },
                                    onStepGain = { vm.nudgeGain(sel, it) },
                                    onStepQ = { vm.stepQ(sel, it) },
                                    onEdit = { numeric = sel to it },
                                    onDelete = { vm.removeFilter(sel) },
                                )
                            }
                        }
                        item { ToneCard(d, info, vm) { numeric = -1 to it } }
                    }
                }
            }
        }
    }
    }

    val current = draft
    if (showSettings && current != null) {
        SettingsSheet(
            profile = current, profiles = profiles, settings = settings, info = info,
            ignoringBattery = ignoringBattery, vm = vm,
            onEditPreamp = { numeric = -1 to NumericField.PREAMP },
            onAllowBattery = { requestIgnoreBattery(ctx) },
            onDismiss = { showSettings = false },
        )
    }
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
    if (showAutoEq) AutoEqDialog(vm = vm, onDismiss = { showAutoEq = false })
    if (showDelete && current != null) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Delete “${current.name}”?") },
            confirmButton = { TextButton(onClick = { vm.deleteCurrent(); showDelete = false }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Cancel") } },
        )
    }
    numeric?.let { (idx, field) ->
        val f = current?.filters?.getOrNull(idx)
        val initial = when (field) {
            NumericField.PREAMP -> current?.preampDb
            NumericField.BASS -> current?.bassDb
            NumericField.TREBLE -> current?.trebleDb
            NumericField.GSTART -> current?.gcfg?.startHz
            NumericField.GEND -> current?.gcfg?.endHz
            NumericField.FREQ -> f?.freq
            NumericField.GAIN -> f?.gainDb
            NumericField.Q -> f?.q
        }
        if (initial != null) {
            NumericInputDialog(field, initial, onDismiss = { numeric = null }) { v ->
                when (field) {
                    NumericField.PREAMP -> vm.setPreamp(v)
                    NumericField.BASS -> vm.setBass(v)
                    NumericField.TREBLE -> vm.setTreble(v)
                    NumericField.GSTART -> current?.gcfg?.let { vm.setGraphicConfig(it.copy(startHz = v)) }
                    NumericField.GEND -> current?.gcfg?.let { vm.setGraphicConfig(it.copy(endHz = v)) }
                    NumericField.FREQ -> f?.let { vm.updateFilter(idx, it.copy(freq = v)) }
                    NumericField.GAIN -> f?.let { vm.updateFilter(idx, it.copy(gainDb = v)) }
                    NumericField.Q -> f?.let { vm.updateFilter(idx, it.copy(q = v)) }
                }
                numeric = null
            }
        } else numeric = null
    }
}

@Composable
private fun ToneCard(d: com.zenpeq.model.EqProfile, info: com.zenpeq.audio.EngineInfo, vm: EqViewModel, onEdit: (NumericField) -> Unit) {
    ZenCard {
        ToneKnobs(
            profile = d, info = info,
            onAuto = vm::setAutoPreamp, onPreamp = vm::setPreamp,
            onBass = vm::setBass, onTreble = vm::setTreble,
            onEdit = onEdit,
        )
    }
}

/** Terminal-style wordmark with a blinking cursor. */
@Composable
private fun Wordmark() {
    val blink by rememberInfiniteTransition(label = "cursor").animateFloat(
        initialValue = 1f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "cursorAlpha",
    )
    Row {
        Text("zenpeq", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "_", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            color = ZenPalette.Green.copy(alpha = blink),
        )
    }
}

/** Wordmark left, live output pill centre, A/B + settings right. */
@Composable
private fun TopBar(info: EngineInfo, eqOn: Boolean, onEq: (Boolean) -> Unit, onSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Wordmark()
        Box(Modifier.weight(1f).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
            OutputPill(info = info, eqOn = eqOn, onClick = onSettings)
        }
        AbToggle(eqOn = eqOn, onSelect = onEq)
        IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") }
    }
}

private fun deviceIcon(key: String): Int = when {
    key.startsWith("bt:") -> R.drawable.ic_dev_bt
    key.startsWith("usb:") -> R.drawable.ic_dev_usb
    key == "wired" -> R.drawable.ic_dev_wired
    else -> R.drawable.ic_dev_speaker
}

/** Active output with a slow jade breathing glow while the EQ is processing. */
@Composable
private fun OutputPill(info: EngineInfo, eqOn: Boolean, onClick: () -> Unit) {
    val active = eqOn && info.route != EngineRoute.NONE
    val breath by rememberInfiniteTransition(label = "breath").animateFloat(
        initialValue = 0.2f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathAlpha",
    )
    val a = if (active) breath else 0f
    val name = info.deviceLabel.substringAfter(" · ").ifEmpty { "Starting…" }
    val tint = if (active) ZenPalette.Green else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = onClick,
        shape = ZenShape,
        color = ZenPalette.Panel,
        border = BorderStroke(1.dp, if (active) ZenPalette.Green.copy(alpha = 0.3f + 0.4f * a) else ZenPalette.Border),
        modifier = Modifier
            .widthIn(max = 170.dp)
            .drawBehind {
                if (a > 0f) {
                    for (i in 1..4) {
                        val grow = i * 3.dp.toPx()
                        drawRoundRect(
                            ZenPalette.Green.copy(alpha = 0.10f * a / i),
                            topLeft = Offset(-grow, -grow),
                            size = Size(size.width + 2 * grow, size.height + 2 * grow),
                            cornerRadius = CornerRadius(grow),
                        )
                    }
                }
            },
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(deviceIcon(info.deviceKey)), null, Modifier.size(16.dp), tint = tint)
            Spacer(Modifier.size(6.dp))
            Text(
                name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium, color = tint,
            )
        }
    }
}

/** A = bypass, B = EQ. The active side lights up amber. */
@Composable
private fun AbToggle(eqOn: Boolean, onSelect: (Boolean) -> Unit) {
    val shape = ZenShape
    Row(Modifier.clip(shape).background(ZenPalette.Panel).border(1.dp, ZenPalette.Border, shape)) {
        AbCell("A", selected = !eqOn) { onSelect(false) }
        AbCell("B", selected = eqOn) { onSelect(true) }
    }
}

@Composable
private fun AbCell(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(ZenShape)
            .background(if (selected) ZenPalette.Cream else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = if (selected) ZenPalette.Ink else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Profile selector plus a single overflow menu for the rarely used actions. */
@Composable
private fun ProfileBar(
    name: String,
    isIem: Boolean,
    locked: Boolean,
    dirty: Boolean,
    canDelete: Boolean,
    onPick: () -> Unit,
    onSaveAs: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onAutoEq: () -> Unit,
    onLock: () -> Unit,
    onSetIem: () -> Unit,
    onDelete: () -> Unit,
    onSave: () -> Unit,
    onRevert: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ZOutlinedButton(onClick = onPick, modifier = Modifier.weight(1f)) {
                Text(
                    (if (locked) "🔒 " else "") + name + if (isIem) "  ★" else "",
                    Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Icon(Icons.Default.ArrowDropDown, null)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("AutoEq presets…") }, onClick = { menu = false; onAutoEq() })
                    DropdownMenuItem(text = { Text("Save as…") }, onClick = { menu = false; onSaveAs() })
                    DropdownMenuItem(text = { Text("Import") }, onClick = { menu = false; onImport() })
                    DropdownMenuItem(text = { Text("Export") }, onClick = { menu = false; onExport() })
                    DropdownMenuItem(
                        text = { Text(if (locked) "Unlock preset" else "Lock preset") },
                        onClick = { menu = false; onLock() },
                    )
                    DropdownMenuItem(
                        text = { Text(if (isIem) "★ IEM profile" else "Set as IEM") },
                        enabled = !isIem,
                        onClick = { menu = false; onSetIem() },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        enabled = canDelete,
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
        if (dirty) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Unsaved changes", Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall, color = ZenPalette.Orange,
                )
                TextButton(onClick = onRevert) { Text("Revert") }
                ZButton(onClick = onSave) { Text("Save") }
            }
        }
    }
}
