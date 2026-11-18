package com.zenpeq.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.zenpeq.R
import com.zenpeq.ZenPeqApp
import com.zenpeq.data.ZenRepository
import com.zenpeq.model.EqProfile
import com.zenpeq.model.EqSettings
import com.zenpeq.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class EqService : Service() {
    private data class Snapshot(
        val settings: EqSettings,
        val profiles: List<EqProfile>,
        val output: OutputDevice,
        val sessions: Set<Int>,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val engine = EqEngine()
    private lateinit var audioManager: AudioManager
    private val output = MutableStateFlow(OutputDevices.DEFAULT)
    private val repo: ZenRepository get() = (application as ZenPeqApp).repo
    private val sessionReceiver = AudioSessionReceiver()

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refreshOutput()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refreshOutput()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        createChannel()
        ServiceCompat.startForeground(
            this, NOTIF_ID, notification(getString(R.string.notif_starting), true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )

        output.value = OutputDevices.resolve(audioManager)
        audioManager.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
        ContextCompat.registerReceiver(
            this, sessionReceiver,
            IntentFilter().apply {
                addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
                addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
            },
            ContextCompat.RECEIVER_EXPORTED, // players are other apps
        )

        scope.launch { applyDeviceRules(output.value, initial = true) }
        scope.launch {
            combine(repo.settings, repo.profiles, output, SessionRegistry.sessions) { s, p, o, ses ->
                Snapshot(s, p, o, ses.keys)
            }.collect { render(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE -> scope.launch { repo.setEnabled(!repo.settings.first().eqEnabled) } // A/B bypass
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        runCatching { unregisterReceiver(sessionReceiver) }
        engine.release()
        EngineStatus.info.value = EngineInfo()
        super.onDestroy()
    }

    private fun refreshOutput() {
        val now = OutputDevices.resolve(audioManager)
        if (now.key == output.value.key) return
        output.value = now
        scope.launch { applyDeviceRules(now, initial = false) }
    }

    /**
     * Output changed:
     *  1. a profile was last used on this output -> load it and enable the EQ
     *  2. (not on startup) unassigned 3.5 mm / USB -> the ★ IEM profile
     *  3. (not on startup) unassigned phone speaker -> bypass
     *  Anything else (e.g. unassigned Bluetooth) leaves the current state alone.
     */
    private suspend fun applyDeviceRules(dev: OutputDevice, initial: Boolean) {
        val s = repo.settings.first()
        if (!s.autoDevice) return
        val ids = repo.profiles.first().map { it.id }.toSet()
        val assigned = s.deviceProfiles[dev.key]?.takeIf { it in ids }
        when {
            assigned != null -> repo.activateProfile(assigned)
            initial -> Unit
            (dev.kind == OutputKind.WIRED || dev.kind == OutputKind.USB) && s.iemProfileId in ids ->
                repo.activateProfile(s.iemProfileId)
            dev.kind == OutputKind.SPEAKER -> repo.setEnabled(false)
        }
    }

    private fun render(snap: Snapshot) {
        val s = snap.settings
        val profile = snap.profiles.firstOrNull { it.id == s.activeProfileId } ?: snap.profiles.firstOrNull()
        val r = engine.apply(profile, s.eqEnabled, s.sessionMode, snap.sessions, s.limiterEnabled)
        EngineStatus.info.value = EngineInfo(
            serviceRunning = true,
            route = r.route,
            bands = r.bands,
            appSessions = r.appSessions,
            preampDb = r.preampDb,
            peakDb = r.peakDb,
            limiterActive = r.limiterActive,
            deviceKey = snap.output.key,
            deviceLabel = snap.output.label,
            error = r.error,
        )
        val pre = "preamp ${"%.1f".format(r.preampDb)} dB"
        val text = when {
            r.route == EngineRoute.GLOBAL -> "${profile?.name} · ${snap.output.label} · $pre"
            r.route == EngineRoute.PER_APP && r.appSessions > 0 ->
                "${profile?.name} · ${r.appSessions} player(s) · $pre"
            r.route == EngineRoute.PER_APP -> "Waiting for a player to open an audio session"
            r.error != null -> "Effect unavailable: ${r.error}"
            else -> "Bypassed · ${snap.output.label}"
        }
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text, s.eqEnabled))
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun notification(text: String, eqOn: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val toggle = PendingIntent.getService(
            this, 2, Intent(this, EqService::class.java).setAction(ACTION_TOGGLE), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, EqService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("ZenPEQ")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, if (eqOn) "Bypass (A)" else "Enable EQ (B)", toggle)
            .addAction(0, getString(R.string.stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.zenpeq.action.STOP"
        const val ACTION_TOGGLE = "com.zenpeq.action.TOGGLE"
        private const val CHANNEL = "engine"
        private const val NOTIF_ID = 1

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, EqService::class.java))
        }
    }
}
