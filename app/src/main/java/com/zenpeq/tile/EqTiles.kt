package com.zenpeq.tile

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.zenpeq.ZenPeqApp
import com.zenpeq.audio.EngineStatus
import com.zenpeq.audio.EqService
import com.zenpeq.data.ZenRepository
import com.zenpeq.model.EqProfile
import com.zenpeq.model.EqSettings
import com.zenpeq.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

abstract class BaseTile : TileService() {
    protected var scope: CoroutineScope? = null
    protected val repo: ZenRepository get() = (application as ZenPeqApp).repo

    abstract fun render(tile: Tile, settings: EqSettings, profiles: List<EqProfile>)

    override fun onStartListening() {
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        s.launch {
            combine(repo.settings, repo.profiles) { a, b -> a to b }.collect { (settings, profiles) ->
                qsTile?.let { render(it, settings, profiles); it.updateTile() }
            }
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
    }

    /** Make sure the engine service is alive; if the OS refuses a background start, open the app instead. */
    protected fun ensureService() {
        if (EngineStatus.info.value.serviceRunning) return
        try {
            EqService.start(this)
        } catch (e: Exception) {
            val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE),
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
    }

    protected fun Tile.subtitleCompat(text: String) {
        if (Build.VERSION.SDK_INT >= 29) subtitle = text
    }
}

/** Tap: EQ on/off. */
class EqToggleTile : BaseTile() {
    override fun render(tile: Tile, settings: EqSettings, profiles: List<EqProfile>) {
        tile.label = "ZenPEQ"
        tile.state = if (settings.eqEnabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        val name = profiles.firstOrNull { it.id == settings.activeProfileId }?.name ?: ""
        tile.subtitleCompat(if (settings.eqEnabled) name.ifEmpty { "On" } else "Off")
    }

    override fun onClick() {
        scope?.launch {
            val on = repo.settings.first().eqEnabled
            repo.setEnabled(!on)
            if (!on) ensureService()
        }
    }
}

/** Tap: cycle to the next saved profile. */
class ProfileCycleTile : BaseTile() {
    override fun render(tile: Tile, settings: EqSettings, profiles: List<EqProfile>) {
        tile.label = profiles.firstOrNull { it.id == settings.activeProfileId }?.name ?: "Profile"
        tile.state = if (settings.eqEnabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitleCompat("Tap to switch profile")
    }

    override fun onClick() {
        scope?.launch {
            val profiles = repo.profiles.first()
            if (profiles.isEmpty()) return@launch
            val active = repo.settings.first().activeProfileId
            val idx = profiles.indexOfFirst { it.id == active }
            repo.setActive(profiles[(idx + 1) % profiles.size].id)
            ensureService()
        }
    }
}
