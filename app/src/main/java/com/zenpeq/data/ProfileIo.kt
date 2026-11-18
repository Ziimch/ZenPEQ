package com.zenpeq.data

import com.zenpeq.audio.BandGrid
import com.zenpeq.audio.FilterMath
import com.zenpeq.model.AutoEqParser
import com.zenpeq.model.EqProfile
import com.zenpeq.model.FilterType
import com.zenpeq.model.MAX_FILTERS
import com.zenpeq.model.ParseResult
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

sealed interface ImportPreview {
    data object Empty : ImportPreview
    data class Apo(val result: ParseResult) : ImportPreview
    data class JsonProfiles(val profiles: List<EqProfile>) : ImportPreview
    data class Invalid(val reason: String) : ImportPreview
}

/** Export/import of profiles as JSON or Equalizer APO / AutoEq text. */
object ProfileIo {
    private const val MAX_IMPORT_PROFILES = 50
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    fun toJson(p: EqProfile): String = json.encodeToString(p)

    /** Preamp actually applied: the stored value, or minus the curve peak when Auto-Preamp is on. */
    fun effectivePreamp(p: EqProfile): Float {
        if (!p.autoPreamp) return p.preampDb
        val peak = max(0.0, FilterMath.curve(p.filters, BandGrid(48).centers).max())
        return (-peak * 10).roundToInt() / 10f
    }

    /** Equalizer APO syntax. Shelves use LSC/HSC (shelf with Q); the importer also accepts LSQ/HSQ. */
    fun toApoText(p: EqProfile): String = buildString {
        appendLine("Preamp: ${"%.1f".format(Locale.US, effectivePreamp(p))} dB")
        p.filters.take(MAX_FILTERS).forEachIndexed { i, f ->
            val type = when (f.type) { FilterType.PK -> "PK"; FilterType.LSC -> "LSC"; FilterType.HSC -> "HSC" }
            val fc = if (f.freq % 1f == 0f) "%.0f".format(Locale.US, f.freq) else "%.1f".format(Locale.US, f.freq)
            appendLine(
                "Filter ${i + 1}: ${if (f.enabled) "ON" else "OFF"} $type Fc $fc Hz " +
                    "Gain ${"%.1f".format(Locale.US, f.gainDb)} dB Q ${"%.2f".format(Locale.US, f.q)}",
            )
        }
    }

    fun fileName(p: EqProfile, ext: String): String =
        p.name.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').ifEmpty { "profile" } + "." + ext

    fun preview(text: String): ImportPreview {
        val t = text.trim()
        if (t.isEmpty()) return ImportPreview.Empty
        if (t.startsWith("{") || t.startsWith("[")) {
            return try {
                val el = json.parseToJsonElement(t)
                val list = if (el is JsonArray) json.decodeFromJsonElement<List<EqProfile>>(el)
                else listOf(json.decodeFromJsonElement<EqProfile>(el))
                val clean = list.take(MAX_IMPORT_PROFILES).map(::sanitize)
                if (clean.isEmpty()) ImportPreview.Invalid("No profiles in file")
                else ImportPreview.JsonProfiles(clean)
            } catch (e: SerializationException) {
                ImportPreview.Invalid("Not a valid ZenPEQ profile JSON")
            } catch (e: IllegalArgumentException) {
                ImportPreview.Invalid("Not a valid ZenPEQ profile JSON")
            }
        }
        val r = AutoEqParser.parse(t)
        return if (r.filters.isEmpty() && r.preampDb == null) ImportPreview.Invalid("No Preamp or Filter lines found")
        else ImportPreview.Apo(r)
    }

    /** Fresh id (no collisions with existing profiles) and clamped values. */
    private fun sanitize(p: EqProfile) = p.copy(
        id = UUID.randomUUID().toString(),
        name = p.name.ifBlank { "Imported EQ" },
        preampDb = p.preampDb.coerceIn(-30f, 30f),
        filters = p.filters.take(MAX_FILTERS).map {
            it.copy(freq = it.freq.coerceIn(20f, 20_000f), gainDb = it.gainDb.coerceIn(-30f, 30f), q = it.q.coerceIn(0.1f, 30f))
        },
    )
}
