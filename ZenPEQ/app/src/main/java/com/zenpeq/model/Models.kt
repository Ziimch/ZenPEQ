package com.zenpeq.model

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

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
    /** Set when the profile was built by Simple mode's Tone Pad; filters are derived from it. */
    val tone: ToneState? = null,
)

/**
 * Zen Tone Pad position. x: -1 warm … +1 bright (low/high shelf balance).
 * y: -1 bass … +1 vocal clarity. [tamer] adds the 4.5 kHz / 7.7 kHz sibilance cuts.
 */
@Serializable
data class ToneState(val x: Float = 0f, val y: Float = 0f, val tamer: Boolean = false) {
    fun toFilters(): List<EqFilter> {
        fun g(v: Float) = (v * 10).roundToInt() / 10f
        val bass = g(if (y < 0f) -y * 6f else -y * 1.5f)
        val vocal = g(if (y > 0f) y * 4f else y * 1f)
        val low = g(-x * 3.5f)
        val high = g(x * 4f)
        return buildList {
            if (bass != 0f) add(EqFilter(FilterType.LSC, 80f, bass, 0.7f))
            if (low != 0f) add(EqFilter(FilterType.LSC, 220f, low, 0.6f))
            if (vocal != 0f) add(EqFilter(FilterType.PK, 2500f, vocal, 0.9f))
            if (high != 0f) add(EqFilter(FilterType.HSC, 6000f, high, 0.6f))
            if (tamer) {
                add(EqFilter(FilterType.PK, 4500f, -3.5f, 2.5f))
                add(EqFilter(FilterType.PK, 7700f, -4.5f, 3.0f))
            }
        }
    }
}

/** One-tap moods for the Tone Pad. */
enum class Mood(val label: String, val tone: ToneState) {
    PURE_FLAT("Pure Flat", ToneState()),
    VELVET_BASS("Velvet Bass", ToneState(-0.4f, -0.7f)),
    CRYSTAL_VOCAL("Crystal Vocal", ToneState(0.35f, 0.75f)),
    LATE_NIGHT("Late Night", ToneState(-0.3f, -0.2f, tamer = true)),
}

/** False once Pro-mode edits (or an import) made the filters differ from what the pad produces. */
fun EqProfile.isSimpleCompatible(): Boolean =
    graphic.isEmpty() && (tone?.toFilters() == filters || (tone == null && filters.isEmpty()))

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
    /** -3..+3 dB. Positive = right louder (left is attenuated); negative = left louder. */
    val balanceDb: Float = 0f,
    /** Output device key -> profile id (last profile used on that output). */
    val deviceProfiles: Map<String, String> = emptyMap(),
)

object Presets {
    const val FLAT_ID = "flat"
    const val DEFAULT_IEM_ID = "default_iem"

    val flat = EqProfile(id = FLAT_ID, name = "Flat", autoPreamp = true, tone = ToneState())

    /** Starting point for V-shaped budget IEMs: tames the 4.5 kHz and 7.7 kHz sibilance peaks. */
    val vShapeTamer = EqProfile(
        id = DEFAULT_IEM_ID,
        name = "V-Shape IEM – Sibilance Tamer",
        autoPreamp = true,
        tone = ToneState(tamer = true),
        filters = ToneState(tamer = true).toFilters(),
    )

    val defaults: List<EqProfile> = listOf(vShapeTamer, flat)
}
