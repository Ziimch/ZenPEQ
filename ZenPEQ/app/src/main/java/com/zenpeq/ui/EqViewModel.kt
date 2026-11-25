package com.zenpeq.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zenpeq.ZenPeqApp
import com.zenpeq.audio.EngineStatus
import com.zenpeq.audio.FilterMath
import com.zenpeq.audio.LiveEq
import com.zenpeq.data.AutoEqEntry
import com.zenpeq.data.AutoEqRepository
import com.zenpeq.data.AutoEqSuggestion
import com.zenpeq.data.AutoEqSuggestions
import com.zenpeq.data.ImportPreview
import com.zenpeq.data.ProfileIo
import com.zenpeq.data.importAutoEqPreset
import com.zenpeq.model.CompressorSettings
import com.zenpeq.model.EqFilter
import com.zenpeq.model.EqProfile
import com.zenpeq.model.EqSettings
import com.zenpeq.model.FilterType
import com.zenpeq.model.GraphicConfig
import com.zenpeq.model.GraphicPoint
import com.zenpeq.model.MAX_FILTERS
import com.zenpeq.model.MAX_FREQ_HZ
import com.zenpeq.model.MAX_GAIN_DB
import com.zenpeq.model.MAX_PREAMP_DB
import com.zenpeq.model.MAX_Q
import com.zenpeq.model.MIN_FREQ_HZ
import com.zenpeq.model.MIN_PREAMP_DB
import com.zenpeq.model.MIN_Q
import com.zenpeq.model.Mood
import com.zenpeq.model.SessionMode
import com.zenpeq.model.ToneState
import com.zenpeq.model.UiMode
import com.zenpeq.model.isSimpleCompatible
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.roundToInt

class EqViewModel(app: Application) : AndroidViewModel(app) {
    private val zen = app as ZenPeqApp
    private val repo = zen.repo
    val autoEq: AutoEqRepository = zen.autoEq

    val profiles: StateFlow<List<EqProfile>> =
        repo.profiles.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val settings: StateFlow<EqSettings> =
        repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, EqSettings())

    /** The profile being edited. Edits go live at once through [LiveEq]; saving follows the Autosave setting. */
    private val _draft = MutableStateFlow<EqProfile?>(null)
    val draft: StateFlow<EqProfile?> = _draft.asStateFlow()
    private var stored: List<EqProfile> = emptyList()

    /** Autosave off and the draft differs from the stored copy: show Save / Revert. */
    val dirty: StateFlow<Boolean> = combine(_draft, profiles, settings) { d, p, s ->
        !s.autosave && d != null && p.firstOrNull { it.id == d.id } != d
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Debounced save: one cancellable Job so delete can kill it instantly.
    private var saveJob: Job? = null
    private var lastEdited: EqProfile? = null
    private val deleted = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            combine(repo.profiles, repo.settings) { p, s -> p to s }.collect { (p, s) ->
                stored = p
                val active = p.firstOrNull { it.id == s.activeProfileId } ?: p.first()
                if (_draft.value?.id != active.id) {
                    if (!s.autosave) {
                        saveJob?.cancel()
                        lastEdited = null
                        LiveEq.profile.value = null // unsaved edits of the old profile are dropped
                    }
                    _draft.value = active
                }
            }
        }
    }

    private suspend fun persist(d: EqProfile) {
        if (d.id in deleted) return
        if (stored.firstOrNull { it.id == d.id } != d) repo.upsertProfile(d)
    }

    private suspend fun flush() {
        saveJob?.cancelAndJoin()
        saveJob = null
        lastEdited?.let { persist(it) }
        lastEdited = null
    }

    /** A locked preset ignores edits unless [force]d (only the lock toggle does that). */
    private fun edit(force: Boolean = false, block: (EqProfile) -> EqProfile) {
        val cur = _draft.value ?: return
        if (cur.locked && !force) return
        val next = block(cur)
        if (next == cur) return
        _draft.value = next
        LiveEq.profile.value = next // audible immediately
        lastEdited = next
        saveJob?.cancel()
        if (settings.value.autosave) {
            saveJob = viewModelScope.launch {
                delay(SAVE_DELAY_MS)
                lastEdited?.let { persist(it) }
            }
        }
    }

    // ---- PEQ bands -------------------------------------------------------------------------------

    fun updateFilter(i: Int, f: EqFilter) = edit { p ->
        p.copy(filters = p.filters.toMutableList().also { if (i in it.indices) it[i] = f })
    }

    /** Graph drag: horizontal = frequency, vertical = gain. */
    fun moveFilter(i: Int, hz: Float, db: Float) = edit { p ->
        val f = p.filters.getOrNull(i) ?: return@edit p
        val freq = (if (hz < 1000f) hz.roundToInt().toFloat() else (hz / 10).roundToInt() * 10f)
            .coerceIn(MIN_FREQ_HZ, MAX_FREQ_HZ)
        val gain = ((db * 10).roundToInt() / 10f).coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB)
        p.copy(filters = p.filters.toMutableList().also { it[i] = f.copy(freq = freq, gainDb = gain) })
    }

    fun nudgeGain(i: Int, delta: Float) = edit { p ->
        val f = p.filters.getOrNull(i) ?: return@edit p
        val gain = (((f.gainDb + delta) * 10).roundToInt() / 10f).coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB)
        p.copy(filters = p.filters.toMutableList().also { it[i] = f.copy(gainDb = gain) })
    }

    fun stepQ(i: Int, delta: Float) = edit { p ->
        val f = p.filters.getOrNull(i) ?: return@edit p
        val q = (((f.q + delta) * 100).roundToInt() / 100f).coerceIn(MIN_Q, MAX_Q)
        p.copy(filters = p.filters.toMutableList().also { it[i] = f.copy(q = q) })
    }

    fun addFilter() = edit { p ->
        if (p.filters.size >= MAX_FILTERS) p
        else p.copy(filters = p.filters + EqFilter(FilterType.PK, 1000f, 0f, 1f))
    }

    fun removeFilter(i: Int) = edit { p ->
        p.copy(filters = p.filters.filterIndexed { idx, _ -> idx != i })
    }

    // ---- preamp / tone ----------------------------------------------------------------------------

    fun setPreamp(db: Float) = edit { it.copy(preampDb = db.coerceIn(MIN_PREAMP_DB, MAX_PREAMP_DB)) }

    fun nudgePreamp(delta: Float) = edit {
        it.copy(preampDb = (((it.preampDb + delta) * 10).roundToInt() / 10f).coerceIn(MIN_PREAMP_DB, MAX_PREAMP_DB))
    }

    fun setAutoPreamp(on: Boolean) = edit { it.copy(autoPreamp = on) }

    fun setBass(db: Float) = edit { it.copy(bassDb = ((db * 10).roundToInt() / 10f).coerceIn(-12f, 12f)) }
    fun setTreble(db: Float) = edit { it.copy(trebleDb = ((db * 10).roundToInt() / 10f).coerceIn(-12f, 12f)) }

    // ---- Simple mode ------------------------------------------------------------------------------

    fun clearGraphic() = edit { it.copy(graphic = emptyList(), gcfg = null) }

    /** Tone Pad drag (a customised curve starts over from flat). */
    fun setTone(x: Float, y: Float) = edit { p ->
        val prev = if (p.isSimpleCompatible()) p.tone ?: ToneState() else ToneState()
        val t = prev.copy(
            x = ((x * 100).roundToInt() / 100f).coerceIn(-1f, 1f),
            y = ((y * 100).roundToInt() / 100f).coerceIn(-1f, 1f),
        )
        p.copy(tone = t, filters = t.toFilters(), graphic = emptyList(), gcfg = null)
    }

    fun applyMood(m: Mood) = edit {
        it.copy(
            tone = m.tone, filters = m.tone.toFilters(), graphic = emptyList(), gcfg = null,
            bassDb = 0f, trebleDb = 0f,
        )
    }

    // ---- Graphic EQ -------------------------------------------------------------------------------

    /** Change the slider layout; the current graphic curve is resampled onto the new centres. */
    fun setGraphicConfig(cfg: GraphicConfig) = edit { p ->
        val centers = cfg.copy(
            startHz = cfg.startHz.coerceIn(MIN_FREQ_HZ, MAX_FREQ_HZ / 2),
            endHz = cfg.endHz.coerceIn(cfg.startHz * 2f, MAX_FREQ_HZ),
        )
        val freqs = centers.centers()
        val gains = FilterMath.graphicCurve(p.graphic, DoubleArray(freqs.size) { freqs[it].toDouble() })
        p.copy(
            gcfg = centers,
            graphic = freqs.mapIndexed { i, f ->
                GraphicPoint(f, ((gains[i] * 10).roundToInt() / 10f).coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB))
            },
        )
    }

    fun setGraphicGain(i: Int, db: Float) = edit { p ->
        if (p.gcfg == null || i !in p.graphic.indices) p
        else p.copy(
            graphic = p.graphic.toMutableList().also {
                it[i] = it[i].copy(gainDb = ((db * 10).roundToInt() / 10f).coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB))
            },
        )
    }

    fun resetGraphic() = edit { p ->
        if (p.gcfg == null) p else p.copy(graphic = p.graphic.map { it.copy(gainDb = 0f) })
    }

    // ---- presets: select / save / lock ------------------------------------------------------------

    fun select(id: String) {
        viewModelScope.launch {
            if (settings.value.autosave) {
                flush() // don't lose a pending edit
            } else {
                saveJob?.cancel()
                lastEdited = null
                LiveEq.profile.value = null
            }
            repo.setActive(id, deviceKey())
        }
    }

    fun saveAs(name: String) {
        val d = _draft.value ?: return
        viewModelScope.launch {
            val copy = d.copy(id = UUID.randomUUID().toString(), name = name.ifBlank { "Custom EQ" }, locked = false)
            repo.upsertProfile(copy)
            repo.setActive(copy.id, deviceKey())
        }
    }

    fun deleteCurrent() {
        val d = _draft.value ?: return
        if (stored.size <= 1) return
        // Kill any pending save first, and block late persists of this id, so it can't be resurrected.
        deleted += d.id
        saveJob?.cancel()
        saveJob = null
        if (lastEdited?.id == d.id) lastEdited = null
        viewModelScope.launch { repo.deleteProfile(d.id) }
    }

    fun toggleLock() {
        val d = _draft.value ?: return
        val next = d.copy(locked = !d.locked)
        _draft.value = next
        viewModelScope.launch { repo.upsertProfile(next) }
    }

    /** Autosave off: write the draft now. */
    fun saveNow() {
        val d = _draft.value ?: return
        saveJob?.cancel()
        lastEdited = null
        viewModelScope.launch { repo.upsertProfile(d) }
    }

    /** Autosave off: drop unsaved edits. */
    fun revert() {
        val d = _draft.value ?: return
        val s = stored.firstOrNull { it.id == d.id } ?: return
        saveJob?.cancel()
        lastEdited = null
        _draft.value = s
        LiveEq.profile.value = null
    }

    // ---- settings ---------------------------------------------------------------------------------

    fun setIem(id: String) { viewModelScope.launch { repo.setIem(id) } }
    fun setEnabled(on: Boolean) { viewModelScope.launch { repo.setEnabled(on) } }
    fun setAutoDevice(on: Boolean) { viewModelScope.launch { repo.setAutoDevice(on) } }
    fun setLimiter(on: Boolean) { viewModelScope.launch { repo.setLimiter(on) } }
    fun setUiMode(m: UiMode) { viewModelScope.launch { repo.setUiMode(m) } }
    fun setAutosave(on: Boolean) { viewModelScope.launch { repo.setAutosave(on) } }
    fun setCompressor(c: CompressorSettings) { viewModelScope.launch { repo.setCompressor(c) } }
    fun assignDevice(key: String, profileId: String) { viewModelScope.launch { repo.assignDevice(key, profileId) } }
    fun clearAssignment(key: String) { viewModelScope.launch { repo.clearDeviceAssignment(key) } }
    fun setSessionMode(m: SessionMode) { viewModelScope.launch { repo.setSessionMode(m) } }
    fun dismissBatteryHint() { viewModelScope.launch { repo.dismissBatteryHint() } }

    fun setBalance(db: Float) {
        val v = ((db * 10).roundToInt() / 10f).coerceIn(-3f, 3f)
        viewModelScope.launch { repo.setBalance(v) }
    }
    fun nudgeBalance(delta: Float) = setBalance(settings.value.balanceDb + delta)

    private fun deviceKey(): String? = EngineStatus.info.value.deviceKey.takeIf { it.isNotEmpty() }

    // ---- import / AutoEq --------------------------------------------------------------------------

    fun preview(text: String): ImportPreview = ProfileIo.preview(text)

    fun importPreview(name: String, preview: ImportPreview) {
        viewModelScope.launch {
            when (preview) {
                is ImportPreview.Apo -> {
                    val r = preview.result
                    val p = EqProfile(
                        id = UUID.randomUUID().toString(),
                        name = name.ifBlank { "Imported EQ" },
                        preampDb = r.preampDb ?: 0f,
                        // Honour the file's own Preamp line if present; otherwise let Auto-Preamp decide.
                        autoPreamp = r.preampDb == null,
                        filters = r.filters,
                        graphic = r.graphic,
                    )
                    repo.upsertProfile(p)
                    repo.setActive(p.id, deviceKey())
                }
                is ImportPreview.JsonProfiles -> {
                    preview.profiles.forEach { repo.upsertProfile(it) }
                    preview.profiles.firstOrNull()?.let { repo.setActive(it.id, deviceKey()) }
                }
                else -> Unit
            }
        }
    }

    /** Picked from the AutoEq browser: download, create the profile, switch to it. */
    fun importAutoEq(entry: AutoEqEntry, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = runCatching {
                val id = importAutoEqPreset(repo, autoEq, entry, null)
                repo.activateProfile(id)
                if (settings.value.autoDevice) deviceKey()?.let { repo.assignDevice(it, id) }
            }.isSuccess
            onDone(ok)
        }
    }

    fun applySuggestion(s: AutoEqSuggestion) {
        viewModelScope.launch {
            runCatching {
                val id = importAutoEqPreset(repo, autoEq, s.entry, s.deviceKey)
                repo.activateProfile(id)
            }
            repo.markAutoEqSeen(s.deviceKey)
            AutoEqSuggestions.current.value = null
        }
    }

    fun dismissSuggestion(s: AutoEqSuggestion) {
        viewModelScope.launch {
            repo.markAutoEqSeen(s.deviceKey)
            AutoEqSuggestions.current.value = null
        }
    }

    private companion object {
        const val SAVE_DELAY_MS = 150L
    }
}
