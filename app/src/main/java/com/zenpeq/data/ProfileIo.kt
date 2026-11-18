package com.zenpeq.data

import com.zenpeq.audio.BandGrid
import com.zenpeq.audio.FilterMath
import com.zenpeq.model.AutoEqParser
import com.zenpeq.model.EqProfile
import com.zenpeq.model.FilterType
import com.zenpeq.model.MAX_FILTERS
import com.zenpeq.model.MAX_FREQ_HZ
import com.zenpeq.model.MAX_GAIN_DB
import com.zenpeq.model.MAX_GRAPHIC_POINTS
import com.zenpeq.model.MAX_PREAMP_DB
import com.zenpeq.model.MAX_Q
import com.zenpeq.model.MIN_FREQ_HZ
import com.zenpeq.model.MIN_PREAMP_DB
import com.zenpeq.model.MIN_Q
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

/** Export/import of profiles as JSON or Equalizer APO / AutoEq text (incl. single-line GraphicEQ). */
object ProfileIo {
    private const val MAX_IMPORT_PROFILES = 50
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    private fun f1(v: Float) = "%.1f".format(Locale.US, v)

    fun toJson(p: EqProfile): String = json.encodeToString(p)

    /** Preamp actually applied: the stored value, or minus the curve peak when Auto-Preamp is on. */
    fun effectivePreamp(p: EqProfile): Float {
        if (!p.autoPreamp) return p.preampDb
        val peak = max(0.0, FilterMath.curve(p.filters, BandGrid(64).centers, p.graphic).max())
        return (-peak * 10).roundToInt() / 10f
    }

    /**
     * Equalizer APO syntax. Shelves use LSC/HSC (shelf with Q) unless [lsqShelves] asks for LSQ/HSQ;
     * the importer accepts both. A GraphicEQ layer is written as one `GraphicEQ:` line.
     */
    fun toApoText(p: EqProfile, lsqShelves: Boolean = false): String = buildString {
        appendLine("Preamp: ${f1(effectivePreamp(p))} dB")
        p.filters.take(MAX_FILTERS).forEachIndexed { i, f ->
            val type = when (f.type) {
                FilterType.PK -> "PK"
                FilterType.LSC -> if (lsqShelves) "LSQ" else "LSC"
                FilterType.HSC -> if (lsqShelves) "HSQ" else "HSC"
            }
            val fc = if (f.freq % 1f == 0f) "%.0f".format(Locale.US, f.freq) else f1(f.freq)
            appendLine(
                "Filter ${i + 1}: ${if (f.enabled) "ON" else "OFF"} $type Fc $fc Hz " +
                    "Gain ${f1(f.gainDb)} dB Q ${"%.2f".format(Locale.US, f.q)}",
            )
        }
        if (p.graphic.isNotEmpty()) {
            append("GraphicEQ: ")
            appendLine(
                p.graphic.sortedBy { it.freq }.joinToString("; ") {
                    val fr = if (it.freq % 1f == 0f) "%.0f".format(Locale.US, it.freq) else f1(it.freq)
                    "$fr ${f1(it.gainDb)}"
                },
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
        return if (r.filters.isEmpty() && r.preampDb == null && r.graphic.isEmpty()) {
            ImportPreview.Invalid("No Preamp, Filter or GraphicEQ lines found")
        } else ImportPreview.Apo(r)
    }

    /** Fresh id (no collisions with existing profiles) and clamped values. */
    private fun sanitize(p: EqProfile) = p.copy(
        id = UUID.randomUUID().toString(),
        name = p.name.ifBlank { "Imported EQ" },
        preampDb = p.preampDb.coerceIn(MIN_PREAMP_DB, MAX_PREAMP_DB),
        filters = p.filters.take(MAX_FILTERS).map {
            it.copy(
                freq = it.freq.coerceIn(MIN_FREQ_HZ, MAX_FREQ_HZ),
                gainDb = it.gainDb.coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB),
                q = it.q.coerceIn(MIN_Q, MAX_Q),
            )
        },
        graphic = p.graphic.take(MAX_GRAPHIC_POINTS).map {
            it.copy(freq = it.freq.coerceIn(MIN_FREQ_HZ, MAX_FREQ_HZ), gainDb = it.gainDb.coerceIn(-24f, 24f))
        },
    )
}
