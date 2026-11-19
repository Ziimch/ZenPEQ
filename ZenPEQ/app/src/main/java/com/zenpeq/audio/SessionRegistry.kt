package com.zenpeq.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Player audio sessions announced via ACTION_OPEN/CLOSE_AUDIO_EFFECT_CONTROL_SESSION (session id -> package). */
object SessionRegistry {
    val sessions = MutableStateFlow<Map<Int, String?>>(emptyMap())
    fun open(id: Int, pkg: String?) = sessions.update { it + (id to pkg) }
    fun close(id: Int) = sessions.update { it - id }
}

/** Registered in the manifest (explicit broadcasts) and dynamically by [EqService] (implicit ones). Idempotent. */
class AudioSessionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
        if (id <= 0) return // 0 = global mix, <0 = missing
        val pkg = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME)
        if (pkg == context.packageName) return
        when (intent.action) {
            AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> SessionRegistry.open(id, pkg)
            AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> SessionRegistry.close(id)
        }
    }
}
