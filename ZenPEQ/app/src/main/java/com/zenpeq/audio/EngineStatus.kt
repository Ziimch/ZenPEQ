package com.zenpeq.audio

import com.zenpeq.model.EqProfile
import kotlinx.coroutines.flow.MutableStateFlow

enum class EngineRoute { NONE, GLOBAL, PER_APP }

data class EngineInfo(
    val serviceRunning: Boolean = false,
    val route: EngineRoute = EngineRoute.NONE,
    val bands: Int = 0,
    val appSessions: Int = 0,
    val preampDb: Float = 0f,
    val peakDb: Float = 0f,
    val limiterActive: Boolean = false,
    val deviceKey: String = "",
    val deviceLabel: String = "",
    val error: String? = null,
) {
    val effectActive: Boolean get() = route != EngineRoute.NONE
}

/** Process-wide status the UI and tiles can observe. */
object EngineStatus {
    val info = MutableStateFlow(EngineInfo())
}

/**
 * The profile being edited right now, pushed by the UI on every drag/slider event. The engine prefers it
 * over the stored copy (saved ~150 ms after the last edit) so tuning is audible while you move.
 */
object LiveEq {
    val profile = MutableStateFlow<EqProfile?>(null)
}
