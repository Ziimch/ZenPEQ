package com.zenpeq.audio

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
