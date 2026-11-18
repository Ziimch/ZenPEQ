package com.zenpeq.audio

import kotlinx.coroutines.flow.MutableStateFlow

enum class EngineRoute { NONE, GLOBAL, PER_APP }

data class EngineInfo(
    val serviceRunning: Boolean = false,
    val route: EngineRoute = EngineRoute.NONE,
    val headsetConnected: Boolean = false,
    val bands: Int = 0,
    val appSessions: Int = 0,
    val preampDb: Float = 0f,
    val peakDb: Float = 0f,
    val error: String? = null,
) {
    val effectActive: Boolean get() = route != EngineRoute.NONE
}

/** Process-wide status the UI and tiles can observe. */
object EngineStatus {
    val info = MutableStateFlow(EngineInfo())
}
