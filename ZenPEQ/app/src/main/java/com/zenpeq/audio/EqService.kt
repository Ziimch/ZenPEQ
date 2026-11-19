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
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

class EqService : Service() {
    private data class Route(val device: OutputDevice, val sampleRate: Int)

    private data class Snapshot(
        val settings: EqSettings,
        val profiles: List<EqProfile>,
        val route: Route,
        val sessions: Set<Int>,
        val live: EqProfile?,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    // All DynamicsProcessing work (EQ maths + binder calls + ramps) stays on this one thread.
    private val dspDispatcher = Executors.newSingleThreadExecutor { Thread(it, "zenpeq-dsp") }.asCoroutineDispatcher()
    private val engine = EqEngine()
    private lateinit var audioManager: AudioManager
    private val route = MutableStateFlow(Route(OutputDevices.DEFAULT, 48_000))
    private val repo: ZenRepository get() = (application as ZenPeqApp).repo
    private val sessionReceiver = AudioSessionReceiver()
    private var lastNotification: Pair<String, Boolean>? = null

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

        route.value = currentRoute()
        audioManager.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
        ContextCompat.registerReceiver(
            this, sessionReceiver,
            IntentFilter().apply {
                addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
                addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
            },
            ContextCompat.RECEIVER_EXPORTED, // players are other apps
        )

        scope.launch { applyDeviceRules(route.value.device, initial = true) }
        scope.launch {
            combine(repo.settings, repo.profiles, route, SessionRegistry.sessions, LiveEq.profile) { s, p, r, ses, live ->
                Snapshot(s, p, r, ses.keys, live)
            }.collectLatest { snap ->
                // collectLatest cancels an in-flight ramp when a newer edit arrives; the next ramp
                // starts from whatever gains are currently applied, so rapid dragging stays smooth.
                withContext(dspDispatcher) { render(snap) }
            }
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
        runBlocking(dspDispatcher) { engine.release() }
        dspDispatcher.close()
        EngineStatus.info.value = EngineInfo()
        super.onDestroy()
    }

    /** Output sample rate straight from the audio HAL (48 kHz when unknown). */
    private fun outputSampleRate(): Int =
        audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
            ?.takeIf { it in 8_000..384_000 } ?: 48_000

    private fun currentRoute() = Route(OutputDevices.resolve(audioManager), outputSampleRate())

    private fun refreshOutput() {
        val now = currentRoute()
        val was = route.value
        if (now.device.key == was.device.key && now.sampleRate == was.sampleRate) return
        route.value = now
        if (now.device.key != was.device.key) scope.launch { applyDeviceRules(now.device, initial = false) }
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

    private suspend fun render(snap: Snapshot) {
        val s = snap.settings
        val stored = snap.profiles.firstOrNull { it.id == s.activeProfileId } ?: snap.profiles.firstOrNull()
        // The live copy wins while it is the profile being edited.
        val profile = snap.live?.takeIf { it.id == stored?.id } ?: stored
        val r = engine.apply(
            profile, s.eqEnabled, s.sessionMode, snap.sessions, s.limiterEnabled,
            snap.route.sampleRate, s.balanceDb,
        )
        val dev = snap.route.device
        EngineStatus.info.value = EngineInfo(
            serviceRunning = true,
            route = r.route,
            bands = r.bands,
            appSessions = r.appSessions,
            preampDb = r.preampDb,
            peakDb = r.peakDb,
            limiterActive = r.limiterActive,
            deviceKey = dev.key,
            deviceLabel = dev.label,
            error = r.error,
        )
        val pre = "preamp ${"%.1f".format(r.preampDb)} dB"
        val text = when {
            r.route == EngineRoute.GLOBAL -> "${profile?.name} · ${dev.label} · $pre"
            r.route == EngineRoute.PER_APP && r.appSessions > 0 ->
                "${profile?.name} · ${r.appSessions} player(s) · $pre"
            r.route == EngineRoute.PER_APP -> "Waiting for a player to open an audio session"
            r.error != null -> "Effect unavailable: ${r.error}"
            else -> "Bypassed · ${dev.label}"
        }
        // Live edits re-render dozens of times a second; only touch the notification when it changes.
        val shown = text to s.eqEnabled
        if (shown != lastNotification) {
            lastNotification = shown
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text, s.eqEnabled))
        }
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
