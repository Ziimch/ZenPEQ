package com.zenpeq.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zenpeq.model.CompressorSettings
import com.zenpeq.model.EqProfile
import com.zenpeq.model.EqSettings
import com.zenpeq.model.Presets
import com.zenpeq.model.SessionMode
import com.zenpeq.model.UiMode
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
        val AUTO_DEVICE = booleanPreferencesKey("auto_headset") // legacy key name kept for upgrades
        val SESSION_MODE = stringPreferencesKey("session_mode")
        val BATTERY_DISMISSED = booleanPreferencesKey("battery_hint_dismissed")
        val LIMITER = booleanPreferencesKey("limiter_enabled")
        val PRO_MODE = booleanPreferencesKey("pro_mode") // legacy, read for migration
        val UI_MODE = stringPreferencesKey("ui_mode")
        val AUTOSAVE = booleanPreferencesKey("autosave")
        val COMPRESSOR = stringPreferencesKey("compressor_json")
        val AUTOEQ_SEEN = stringSetPreferencesKey("autoeq_seen")
        val BALANCE = floatPreferencesKey("balance_db")
        val DEVICE_PROFILES = stringPreferencesKey("device_profiles_json")
    }

    private fun decode(raw: String?): List<EqProfile> =
        raw?.let { runCatching { json.decodeFromString<List<EqProfile>>(it) }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }
            ?: Presets.defaults

    private fun readMap(prefs: Preferences): Map<String, String> =
        prefs[Keys.DEVICE_PROFILES]
            ?.let { runCatching { json.decodeFromString<Map<String, String>>(it) }.getOrNull() }
            ?: emptyMap()

    private fun writeMap(prefs: MutablePreferences, map: Map<String, String>) {
        prefs[Keys.DEVICE_PROFILES] = json.encodeToString(map)
    }

    val profiles: Flow<List<EqProfile>> =
        store.data.map { decode(it[Keys.PROFILES]) }.distinctUntilChanged()

    val settings: Flow<EqSettings> = store.data.map { p ->
        EqSettings(
            eqEnabled = p[Keys.ENABLED] ?: true,
            autoDevice = p[Keys.AUTO_DEVICE] ?: true,
            activeProfileId = p[Keys.ACTIVE] ?: Presets.DEFAULT_IEM_ID,
            iemProfileId = p[Keys.IEM] ?: Presets.DEFAULT_IEM_ID,
            sessionMode = runCatching { SessionMode.valueOf(p[Keys.SESSION_MODE] ?: "AUTO") }
                .getOrDefault(SessionMode.AUTO),
            batteryHintDismissed = p[Keys.BATTERY_DISMISSED] ?: false,
            limiterEnabled = p[Keys.LIMITER] ?: true,
            uiMode = p[Keys.UI_MODE]?.let { runCatching { UiMode.valueOf(it) }.getOrNull() }
                ?: if (p[Keys.PRO_MODE] == true) UiMode.PRO else UiMode.SIMPLE,
            autosave = p[Keys.AUTOSAVE] ?: true,
            compressor = p[Keys.COMPRESSOR]
                ?.let { runCatching { json.decodeFromString<CompressorSettings>(it) }.getOrNull() }
                ?: CompressorSettings(),
            autoEqSeen = p[Keys.AUTOEQ_SEEN] ?: emptySet(),
            balanceDb = p[Keys.BALANCE] ?: 0f,
            deviceProfiles = readMap(p),
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
            writeMap(prefs, readMap(prefs).filterValues { it != id })
        }
    }

    /**
     * Makes [id] the active profile. If [deviceKey] is given and per-device switching is on, it is also
     * recorded as the last profile used on that output.
     */
    suspend fun setActive(id: String, deviceKey: String? = null) {
        store.edit { prefs ->
            prefs[Keys.ACTIVE] = id
            if (deviceKey != null && (prefs[Keys.AUTO_DEVICE] ?: true)) {
                writeMap(prefs, readMap(prefs) + (deviceKey to id))
            }
        }
    }

    suspend fun assignDevice(deviceKey: String, id: String) {
        store.edit { prefs -> writeMap(prefs, readMap(prefs) + (deviceKey to id)) }
    }

    suspend fun clearDeviceAssignment(deviceKey: String) {
        store.edit { prefs -> writeMap(prefs, readMap(prefs) - deviceKey) }
    }

    /** Device rule hit: switch to [id] and make sure the EQ is on. */
    suspend fun activateProfile(id: String) {
        store.edit { prefs ->
            prefs[Keys.ACTIVE] = id
            prefs[Keys.ENABLED] = true
        }
    }

    suspend fun setIem(id: String) { store.edit { it[Keys.IEM] = id } }
    suspend fun setEnabled(on: Boolean) { store.edit { it[Keys.ENABLED] = on } }
    suspend fun setAutoDevice(on: Boolean) { store.edit { it[Keys.AUTO_DEVICE] = on } }
    suspend fun setLimiter(on: Boolean) { store.edit { it[Keys.LIMITER] = on } }
    suspend fun setUiMode(m: UiMode) { store.edit { it[Keys.UI_MODE] = m.name } }
    suspend fun setAutosave(on: Boolean) { store.edit { it[Keys.AUTOSAVE] = on } }
    suspend fun setCompressor(c: CompressorSettings) { store.edit { it[Keys.COMPRESSOR] = json.encodeToString(c) } }
    suspend fun markAutoEqSeen(deviceKey: String) {
        store.edit { it[Keys.AUTOEQ_SEEN] = (it[Keys.AUTOEQ_SEEN] ?: emptySet()) + deviceKey }
    }
    suspend fun setBalance(db: Float) { store.edit { it[Keys.BALANCE] = db } }
    suspend fun setSessionMode(m: SessionMode) { store.edit { it[Keys.SESSION_MODE] = m.name } }
    suspend fun dismissBatteryHint() { store.edit { it[Keys.BATTERY_DISMISSED] = true } }
}
