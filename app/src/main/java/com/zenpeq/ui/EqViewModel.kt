package com.zenpeq.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zenpeq.ZenPeqApp
import com.zenpeq.audio.EngineStatus
import com.zenpeq.data.ImportPreview
import com.zenpeq.data.ProfileIo
import com.zenpeq.model.EqFilter
import com.zenpeq.model.EqProfile
import com.zenpeq.model.EqSettings
import com.zenpeq.model.FilterType
import com.zenpeq.model.Macro
import com.zenpeq.model.SimpleMacros
import com.zenpeq.model.SimplePreset
import com.zenpeq.model.isSimpleCompatible
import com.zenpeq.model.with
import com.zenpeq.model.MAX_FILTERS
import com.zenpeq.model.MAX_FREQ_HZ
import com.zenpeq.model.MAX_GAIN_DB
import com.zenpeq.model.MAX_PREAMP_DB
import com.zenpeq.model.MIN_FREQ_HZ
import com.zenpeq.model.MIN_PREAMP_DB
import com.zenpeq.model.MAX_Q
import com.zenpeq.model.MIN_Q
import com.zenpeq.model.SessionMode
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
    private val repo = (app as ZenPeqApp).repo

    val profiles: StateFlow<List<EqProfile>> =
        repo.profiles.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val settings: StateFlow<EqSettings> =
        repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, EqSettings())

    /** The profile being edited. Edits go live (debounced) because the service observes the store. */
    private val _draft = MutableStateFlow<EqProfile?>(null)
    val draft: StateFlow<EqProfile?> = _draft.asStateFlow()
    private var stored: List<EqProfile> = emptyList()

    // Debounced save: one cancellable Job instead of a flow operator, so delete can kill it instantly.
    private var saveJob: Job? = null
    private var lastEdited: EqProfile? = null
    private val deleted = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            combine(repo.profiles, repo.settings) { p, s -> p to s }.collect { (p, s) ->
                stored = p
                val active = p.firstOrNull { it.id == s.activeProfileId } ?: p.first()
                if (_draft.value?.id != active.id) _draft.value = active
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

    private fun edit(block: (EqProfile) -> EqProfile) {
        val next = _draft.value?.let(block) ?: return
        _draft.value = next
        lastEdited = next
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(SAVE_DELAY_MS)
            lastEdited?.let { persist(it) }
        }
    }

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

    fun addFilter() = edit { p ->
        if (p.filters.size >= MAX_FILTERS) p
        else p.copy(filters = p.filters + EqFilter(FilterType.PK, 1000f, 0f, 1f))
    }

    fun removeFilter(i: Int) = edit { p ->
        p.copy(filters = p.filters.filterIndexed { idx, _ -> idx != i })
    }

    fun setPreamp(db: Float) = edit { it.copy(preampDb = db.coerceIn(MIN_PREAMP_DB, MAX_PREAMP_DB)) }

    fun stepQ(i: Int, delta: Float) = edit { p ->
        val f = p.filters.getOrNull(i) ?: return@edit p
        val q = (((f.q + delta) * 100).roundToInt() / 100f).coerceIn(MIN_Q, MAX_Q)
        p.copy(filters = p.filters.toMutableList().also { it[i] = f.copy(q = q) })
    }

    fun nudgePreamp(delta: Float) = edit {
        it.copy(preampDb = (((it.preampDb + delta) * 10).roundToInt() / 10f).coerceIn(MIN_PREAMP_DB, MAX_PREAMP_DB))
    }

    fun clearGraphic() = edit { it.copy(graphic = emptyList()) }

    /** Simple mode: a macro slider rebuilds the filters (a customised curve starts over from flat). */
    fun setMacro(kind: Macro, value: Float) = edit { p ->
        val base = if (p.isSimpleCompatible()) p.macros ?: SimpleMacros() else SimpleMacros()
        val m = base.with(kind, value)
        p.copy(macros = m, filters = m.toFilters(), graphic = emptyList())
    }

    fun applyPreset(preset: SimplePreset) = edit {
        it.copy(macros = preset.macros, filters = preset.macros.toFilters(), graphic = emptyList())
    }
    fun setAutoPreamp(on: Boolean) = edit { it.copy(autoPreamp = on) }

    fun select(id: String) {
        viewModelScope.launch {
            flush() // don't lose a pending edit
            repo.setActive(id, deviceKey())
        }
    }

    fun saveAs(name: String) {
        val d = _draft.value ?: return
        viewModelScope.launch {
            val copy = d.copy(id = UUID.randomUUID().toString(), name = name.ifBlank { "Custom EQ" })
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

    fun setIem(id: String) { viewModelScope.launch { repo.setIem(id) } }
    fun setEnabled(on: Boolean) { viewModelScope.launch { repo.setEnabled(on) } }
    fun setAutoDevice(on: Boolean) { viewModelScope.launch { repo.setAutoDevice(on) } }
    fun setLimiter(on: Boolean) { viewModelScope.launch { repo.setLimiter(on) } }
    fun setProMode(on: Boolean) { viewModelScope.launch { repo.setProMode(on) } }
    fun assignDevice(key: String, profileId: String) { viewModelScope.launch { repo.assignDevice(key, profileId) } }
    fun clearAssignment(key: String) { viewModelScope.launch { repo.clearDeviceAssignment(key) } }

    private fun deviceKey(): String? = EngineStatus.info.value.deviceKey.takeIf { it.isNotEmpty() }
    fun setSessionMode(m: SessionMode) { viewModelScope.launch { repo.setSessionMode(m) } }
    fun dismissBatteryHint() { viewModelScope.launch { repo.dismissBatteryHint() } }

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

    private companion object {
        const val SAVE_DELAY_MS = 150L
    }
}
