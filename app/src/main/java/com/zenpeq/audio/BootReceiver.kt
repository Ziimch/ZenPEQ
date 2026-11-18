package com.zenpeq.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zenpeq.ZenPeqApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Restarts the engine after reboot if the EQ was left enabled. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                if ((app as ZenPeqApp).repo.settings.first().eqEnabled) {
                    runCatching { EqService.start(app) }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
