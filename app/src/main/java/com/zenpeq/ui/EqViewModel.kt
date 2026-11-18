package com.zenpeq.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zenpeq.ZenPeqApp
import com.zenpeq.data.ImportPreview
import com.zenpeq.data.ProfileIo
import com.zenpeq.model.EqFilter
import com.zenpeq.model.EqProfile
import com.zenpeq.model.EqSettings
import com.zenpeq.model.FilterType
import com.zenpeq.model.MAX_FILTERS
import com.zenpeq.model.SessionMode
import kotlinx.coroutines.Job
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
        val freq = if (hz < 1000f) hz.roundToInt().toFloat() else (hz / 10).roundToInt() * 10f
        val gain = ((db * 10).roundToInt() / 10f).coerceIn(-15f, 15f)
        p.copy(filters = p.filters.toMutableList().also { it[i] = f.copy(freq = freq, gainDb = gain) })
    }

    fun nudgeGain(i: Int, delta: Float) = edit { p ->
        val f = p.filters.getOrNull(i) ?: return@edit p
        val gain = (((f.gainDb + delta) * 10).roundToInt() / 10f).coerceIn(-15f, 15f)
        p.copy(filters = p.filters.toMutableList().also { it[i] = f.copy(gainDb = gain) })
    }

    fun addFilter() = edit { p ->
        if (p.filters.size >= MAX_FILTERS) p
        else p.copy(filters = p.filters + EqFilter(FilterType.PK, 1000f, 0f, 1f))
    }

    fun removeFilter(i: Int) = edit { p ->
        p.copy(filters = p.filters.filterIndexed { idx, _ -> idx != i })
    }

    fun setPreamp(db: Float) = edit { it.copy(preampDb = db) }
    fun setAutoPreamp(on: Boolean) = edit { it.copy(autoPreamp = on) }

    fun select(id: String) {
        viewModelScope.launch {
            flush() // don't lose a pending edit
            repo.setActive(id)
        }
    }

    fun saveAs(name: String) {
        val d = _draft.value ?: return
        viewModelScope.launch {
            val copy = d.copy(id = UUID.randomUUID().toString(), name = name.ifBlank { "Custom EQ" })
            repo.upsertProfile(copy)
            repo.setActive(copy.id)
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
    fun setAutoHeadset(on: Boolean) { viewModelScope.launch { repo.setAutoHeadset(on) } }
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
                    )
                    repo.upsertProfile(p)
                    repo.setActive(p.id)
                }
                is ImportPreview.JsonProfiles -> {
                    preview.profiles.forEach { repo.upsertProfile(it) }
                    preview.profiles.firstOrNull()?.let { repo.setActive(it.id) }
                }
                else -> Unit
            }
        }
    }

    private companion object {
        const val SAVE_DELAY_MS = 150L
    }
}
