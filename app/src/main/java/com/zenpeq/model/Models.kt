package com.zenpeq.model

import kotlinx.serialization.Serializable

const val MAX_FILTERS = 10

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

@Serializable
data class EqProfile(
    val id: String,
    val name: String,
    val preampDb: Float = 0f,
    val autoPreamp: Boolean = true,
    val filters: List<EqFilter> = emptyList(),
)

enum class SessionMode { AUTO, GLOBAL, PER_APP }

data class EqSettings(
    val eqEnabled: Boolean = true,
    val autoHeadset: Boolean = true,
    val activeProfileId: String = Presets.DEFAULT_IEM_ID,
    val iemProfileId: String = Presets.DEFAULT_IEM_ID,
    val sessionMode: SessionMode = SessionMode.AUTO,
    val batteryHintDismissed: Boolean = false,
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
