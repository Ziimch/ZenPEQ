package com.zenpeq.model

import kotlinx.serialization.Serializable

const val MAX_FILTERS = 16
const val MIN_FREQ_HZ = 20f
const val MAX_FREQ_HZ = 20_000f
const val MAX_GAIN_DB = 15f
const val MIN_Q = 0.4f
const val MAX_Q = 12f
const val MIN_PREAMP_DB = -24f
const val MAX_PREAMP_DB = 6f
const val MAX_GRAPHIC_POINTS = 1024

@Serializable
enum class FilterType { PK, LSC, HSC }

@Serializable
data class EqFilter(
    val type: FilterType = FilterType.PK,
    val freq: Float = 1000f,
    val gainDb: Float = 0f,
    val q: Float = 1f,
    val enabled: Boolean = true,
)

/** One point of an APO `GraphicEQ:` curve (interpolated linearly over log-frequency). */
@Serializable
data class GraphicPoint(val freq: Float, val gainDb: Float)

@Serializable
data class EqProfile(
    val id: String,
    val name: String,
    val preampDb: Float = 0f,
    val autoPreamp: Boolean = true,
    val filters: List<EqFilter> = emptyList(),
    val graphic: List<GraphicPoint> = emptyList(),
    /** Set when the profile was built by Simple mode; filters are derived from it. */
    val macros: SimpleMacros? = null,
)

/** Beginner controls (dB each) that expand into shelf/peaking filters. */
@Serializable
data class SimpleMacros(
    val bass: Float = 0f,
    val warmth: Float = 0f,
    val vocals: Float = 0f,
    val treble: Float = 0f,
    val tamer: Boolean = false,
) {
    fun toFilters(): List<EqFilter> = buildList {
        if (bass != 0f) add(EqFilter(FilterType.LSC, 100f, bass, 0.7f))
        if (warmth != 0f) add(EqFilter(FilterType.PK, 250f, warmth, 0.8f))
        if (vocals != 0f) add(EqFilter(FilterType.PK, 2200f, vocals, 0.9f))
        if (treble != 0f) add(EqFilter(FilterType.HSC, 8000f, treble, 0.7f))
        if (tamer) {
            add(EqFilter(FilterType.PK, 4500f, -3.5f, 2.5f))
            add(EqFilter(FilterType.PK, 7700f, -4.5f, 3.0f))
        }
    }
}

enum class Macro { BASS, WARMTH, VOCALS, TREBLE }

fun SimpleMacros.with(kind: Macro, v: Float): SimpleMacros = when (kind) {
    Macro.BASS -> copy(bass = v)
    Macro.WARMTH -> copy(warmth = v)
    Macro.VOCALS -> copy(vocals = v)
    Macro.TREBLE -> copy(treble = v)
}

enum class SimplePreset(val label: String, val macros: SimpleMacros) {
    FLAT("Flat", SimpleMacros()),
    BASS_BOOST("Bass Boost", SimpleMacros(bass = 5f, warmth = 1f)),
    VOCAL_CLARITY("Vocal Clarity", SimpleMacros(warmth = -1f, vocals = 3f, treble = 1f)),
    SIBILANCE_TAMER("Sibilance Tamer", SimpleMacros(tamer = true)),
}

/** False once Pro-mode edits (or an import) made the filters differ from what the macros produce. */
fun EqProfile.isSimpleCompatible(): Boolean =
    graphic.isEmpty() && (macros?.toFilters() == filters || (macros == null && filters.isEmpty()))

enum class SessionMode { AUTO, GLOBAL, PER_APP }

data class EqSettings(
    val eqEnabled: Boolean = true,
    /** Per-output-device profile switching (stored under the legacy key "auto_headset"). */
    val autoDevice: Boolean = true,
    val activeProfileId: String = Presets.DEFAULT_IEM_ID,
    val iemProfileId: String = Presets.DEFAULT_IEM_ID,
    val sessionMode: SessionMode = SessionMode.AUTO,
    val batteryHintDismissed: Boolean = false,
    val limiterEnabled: Boolean = true,
    val proMode: Boolean = false,
    /** Output device key -> profile id (last profile used on that output). */
    val deviceProfiles: Map<String, String> = emptyMap(),
)

object Presets {
    const val FLAT_ID = "flat"
    const val DEFAULT_IEM_ID = "default_iem"

    val flat = EqProfile(id = FLAT_ID, name = "Flat", autoPreamp = true, macros = SimpleMacros())

    /** Starting point for V-shaped budget IEMs: tames the 4.5 kHz and 7.7 kHz sibilance peaks. */
    val vShapeTamer = EqProfile(
        id = DEFAULT_IEM_ID,
        name = "V-Shape IEM – Sibilance Tamer",
        autoPreamp = true,
        macros = SimpleMacros(tamer = true),
        filters = SimpleMacros(tamer = true).toFilters(),
    )

    val defaults: List<EqProfile> = listOf(vShapeTamer, flat)
}
