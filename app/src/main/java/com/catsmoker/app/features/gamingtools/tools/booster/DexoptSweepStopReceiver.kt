package com.catsmoker.app.features.gamingtools.tools.booster

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.catsmoker.app.features.gamingtools.engine.GamingEngine
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The Stop action on the scheduled dexopt sweep's notification.
 *
 * It cancels the *sweep the engine is running*, not the unique work name — cancelling
 * [DexoptSweepWorker.UNIQUE_WORK_NAME] would take the recurring schedule down with the run, and
 * a Stop button that silently un-schedules tomorrow's run is not a Stop button. This is the same
 * stop path the App Booster card and the manual sweep's own notification use, so stopping from
 * any of the three surfaces leaves the one cancelled state.
 *
 * The goAsync window exists because [GamingEngine.cancelArtOptimization] is a suspend call that
 * has to complete before the broadcast finishes; without it the process could be re-prioritized
 * as an idle receiver before the kill lands.
 */
@AndroidEntryPoint
class DexoptSweepStopReceiver : BroadcastReceiver() {

    @Inject
    lateinit var gamingEngine: GamingEngine

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STOP) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                gamingEngine.cancelArtOptimization()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_STOP = "com.catsmoker.app.ACTION_STOP_SCHEDULED_DEXOPT"
    }
}
