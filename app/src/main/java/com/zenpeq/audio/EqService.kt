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
        val headset: Boolean,
        val sessions: Set<Int>,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val engine = EqEngine()
    private lateinit var audioManager: AudioManager
    private val headset = MutableStateFlow(false)
    private val repo: ZenRepository get() = (application as ZenPeqApp).repo

    private val sessionReceiver = AudioSessionReceiver()

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refreshHeadset()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refreshHeadset()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        createChannel()
        ServiceCompat.startForeground(
            this, NOTIF_ID, notification(getString(R.string.notif_starting)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )

        headset.value = wiredConnected()
        audioManager.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))

        ContextCompat.registerReceiver(
            this, sessionReceiver,
            IntentFilter().apply {
                addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
                addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
            },
            ContextCompat.RECEIVER_EXPORTED, // players are other apps
        )

        scope.launch {
            combine(repo.settings, repo.profiles, headset, SessionRegistry.sessions) { s, p, h, ses ->
                Snapshot(s, p, h, ses.keys)
            }.collect { render(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
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

    private fun wiredConnected(): Boolean =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
        }

    private fun refreshHeadset() {
        val now = wiredConnected()
        if (now == headset.value) return
        headset.value = now
        scope.launch {
            if (!repo.settings.first().autoHeadset) return@launch
            if (now) repo.activateOnHeadset() else repo.setEnabled(false) // unplugged -> bypass
        }
    }

    private fun render(snap: Snapshot) {
        val s = snap.settings
        val profile = snap.profiles.firstOrNull { it.id == s.activeProfileId } ?: snap.profiles.firstOrNull()
        val r = engine.apply(profile, s.eqEnabled, s.sessionMode, snap.sessions)
        EngineStatus.info.value = EngineInfo(
            serviceRunning = true,
            route = r.route,
            headsetConnected = snap.headset,
            bands = r.bands,
            appSessions = r.appSessions,
            preampDb = r.preampDb,
            peakDb = r.peakDb,
            error = r.error,
        )
        val pre = "preamp ${"%.1f".format(r.preampDb)} dB"
        val text = when {
            r.route == EngineRoute.GLOBAL -> "${profile?.name} · global · $pre"
            r.route == EngineRoute.PER_APP && r.appSessions > 0 -> "${profile?.name} · ${r.appSessions} player(s) · $pre"
            r.route == EngineRoute.PER_APP -> "Waiting for a player to open an audio session"
            r.error != null -> "Effect unavailable: ${r.error}"
            else -> "Bypassed"
        }
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text))
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, EqService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("ZenPEQ")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, getString(R.string.stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.zenpeq.action.STOP"
        private const val CHANNEL = "engine"
        private const val NOTIF_ID = 1

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, EqService::class.java))
        }
    }
}
