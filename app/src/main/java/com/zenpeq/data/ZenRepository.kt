package com.zenpeq.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zenpeq.model.EqProfile
import com.zenpeq.model.EqSettings
import com.zenpeq.model.Presets
import com.zenpeq.model.SessionMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "zenpeq")

class ZenRepository(context: Context) {
    private val store = context.applicationContext.dataStore
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private object Keys {
        val PROFILES = stringPreferencesKey("profiles_json")
        val ACTIVE = stringPreferencesKey("active_profile")
        val IEM = stringPreferencesKey("iem_profile")
        val ENABLED = booleanPreferencesKey("eq_enabled")
        val AUTO_HEADSET = booleanPreferencesKey("auto_headset")
        val SESSION_MODE = stringPreferencesKey("session_mode")
        val BATTERY_DISMISSED = booleanPreferencesKey("battery_hint_dismissed")
    }

    private fun decode(raw: String?): List<EqProfile> =
        raw?.let { runCatching { json.decodeFromString<List<EqProfile>>(it) }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }
            ?: Presets.defaults

    val profiles: Flow<List<EqProfile>> =
        store.data.map { decode(it[Keys.PROFILES]) }.distinctUntilChanged()

    val settings: Flow<EqSettings> = store.data.map { p ->
        EqSettings(
            eqEnabled = p[Keys.ENABLED] ?: true,
            autoHeadset = p[Keys.AUTO_HEADSET] ?: true,
            activeProfileId = p[Keys.ACTIVE] ?: Presets.DEFAULT_IEM_ID,
            iemProfileId = p[Keys.IEM] ?: Presets.DEFAULT_IEM_ID,
            sessionMode = runCatching { SessionMode.valueOf(p[Keys.SESSION_MODE] ?: "AUTO") }
                .getOrDefault(SessionMode.AUTO),
            batteryHintDismissed = p[Keys.BATTERY_DISMISSED] ?: false,
        )
    }.distinctUntilChanged()

    suspend fun upsertProfile(profile: EqProfile) {
        store.edit { prefs ->
            val list = decode(prefs[Keys.PROFILES]).toMutableList()
            val i = list.indexOfFirst { it.id == profile.id }
            if (i >= 0) list[i] = profile else list += profile
            prefs[Keys.PROFILES] = json.encodeToString(list.toList())
        }
    }

    suspend fun deleteProfile(id: String) {
        store.edit { prefs ->
            val list = decode(prefs[Keys.PROFILES]).filterNot { it.id == id }
            if (list.isEmpty()) return@edit // always keep at least one profile
            prefs[Keys.PROFILES] = json.encodeToString(list)
            val fallback = list.first().id
            if ((prefs[Keys.ACTIVE] ?: Presets.DEFAULT_IEM_ID) == id) prefs[Keys.ACTIVE] = fallback
            if ((prefs[Keys.IEM] ?: Presets.DEFAULT_IEM_ID) == id) prefs[Keys.IEM] = fallback
        }
    }

    suspend fun setActive(id: String) { store.edit { it[Keys.ACTIVE] = id } }
    suspend fun setIem(id: String) { store.edit { it[Keys.IEM] = id } }
    suspend fun setEnabled(on: Boolean) { store.edit { it[Keys.ENABLED] = on } }
    suspend fun setAutoHeadset(on: Boolean) { store.edit { it[Keys.AUTO_HEADSET] = on } }
    suspend fun setSessionMode(m: SessionMode) { store.edit { it[Keys.SESSION_MODE] = m.name } }
    suspend fun dismissBatteryHint() { store.edit { it[Keys.BATTERY_DISMISSED] = true } }

    /** Wired headset plugged in: switch to the saved IEM profile and turn the EQ on. */
    suspend fun activateOnHeadset() {
        store.edit { prefs ->
            prefs[Keys.ACTIVE] = prefs[Keys.IEM] ?: Presets.DEFAULT_IEM_ID
            prefs[Keys.ENABLED] = true
        }
    }
}
