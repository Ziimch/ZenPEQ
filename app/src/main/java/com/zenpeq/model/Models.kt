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
)

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
    /** Output device key -> profile id (last profile used on that output). */
    val deviceProfiles: Map<String, String> = emptyMap(),
)

object Presets {
    const val FLAT_ID = "flat"
    const val DEFAULT_IEM_ID = "default_iem"

    val flat = EqProfile(id = FLAT_ID, name = "Flat", autoPreamp = false)

    /** Starting point for V-shaped budget IEMs: tames the 4.5 kHz and 7.7 kHz sibilance peaks. */
    val vShapeTamer = EqProfile(
        id = DEFAULT_IEM_ID,
        name = "V-Shape IEM – Sibilance Tamer",
        autoPreamp = true,
        filters = listOf(
            EqFilter(FilterType.PK, 4500f, -3.5f, 2.5f),
            EqFilter(FilterType.PK, 7700f, -4.5f, 3.0f),
        ),
    )

    val defaults: List<EqProfile> = listOf(vShapeTamer, flat)
}
