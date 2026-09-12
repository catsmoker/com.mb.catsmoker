package com.catsmoker.app.features.gamingtools.tools.booster

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.catsmoker.app.R
import com.catsmoker.app.features.gamingtools.engine.BoosterOutcome
import com.catsmoker.app.features.gamingtools.engine.BoosterState
import com.catsmoker.app.features.gamingtools.engine.GamingEngine
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The recurring ART dexopt sweep: the same engine run the App Booster button starts
 * ([GamingEngine.runArtOptimization]), driven on a schedule instead of a tap.
 *
 * What comes from the reference project (`referance/gamingtools/art`, its
 * `presentation/worker/OptimizationWorker.kt`, read in full): the worker shape itself — a
 * `@HiltWorker` that calls `setForeground` before doing anything, a progress job that re-calls
 * `setForeground` as the engine's state advances, and a stop path that cancels the optimization
 * rather than only the coroutine. What does **not** come from the reference: any kind of
 * scheduling. It has no periodic work anywhere — its scheduler only enqueues one-time work for
 * the visible screen. The recurrence is this app's own addition, and it is honest about the two
 * things a background schedule cannot promise: the interval is WorkManager's, which defers work
 * in Doze to batch it with other jobs (so "every 24 hours" means "roughly"), and each run needs
 * a privileged shell to be alive at that moment or the run is *skipped*, not failed — an
 * [BoosterOutcome.Unavailable] run never began, so the engine deliberately records no history
 * entry for it, and the skipped-run notification below is the only surface it gets.
 *
 * On Android 12+ the system may refuse the `setForeground` call outright when the app is in the
 * background. That is caught rather than crashed on: the sweep then runs as ordinary background
 * work, which the OS may kill mid-sweep — and if it does, the engine's own teardown marks the
 * run stopped, so the history says what actually happened instead of the schedule quietly
 * claiming every run succeeded.
 */
@HiltWorker
class DexoptSweepWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val gamingEngine: GamingEngine,
    private val scheduleStore: DexoptScheduleStore
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // The user switched the schedule off while this run was already queued. Running anyway
        // would be the opposite of listening, and it is not a failure of anything.
        if (!scheduleStore.isEnabled()) return Result.success()

        // A manual sweep (or an earlier scheduled one) already holds the engine. Starting would
        // only log "already running" and leave the other sweep's state untouched — the engine's
        // own guard, checked here so the notification never claims a second sweep.
        if (gamingEngine.boosterState.value.isRunning) return Result.success()

        ensureChannel()
        val foreground = try {
            setForeground(foregroundInfo(gamingEngine.boosterState.value))
            true
        } catch (_: Exception) {
            // ForegroundServiceStartNotAllowedException on Android 12+ — see the class KDoc.
            false
        }

        // The reference's own pattern: the notification follows the engine's real counts, which
        // only advance when a command actually ran and answered.
        val progressJob = CoroutineScope(coroutineContext).launch {
            gamingEngine.boosterState.collect { state ->
                if (foreground) runCatching { setForeground(foregroundInfo(state)) }
            }
        }

        try {
            // Never forced: the schedule exists to catch apps the phone has not compiled yet,
            // which `cmd package compile` skips on its own when they are already done.
            gamingEngine.runArtOptimization(mode = DexoptScheduleStore.SCHEDULED_MODE, force = false)

            val outcome = gamingEngine.boosterState.value.outcome
            if (outcome is BoosterOutcome.Unavailable) {
                // Unavailable means the sweep never began (no privilege at wake time, no
                // eligible apps) and the engine records no history entry for it by design — so
                // without this notification a skipped run would leave no trace at all.
                postSkippedNotification(outcome.reason)
            }
            // Completed / Cancelled / Failed are all recorded by the engine in its own history,
            // which the App Booster card shows; the worker has nothing to add on top.
            return Result.success()
        } finally {
            progressJob.cancel()
        }
    }

    /**
     * No onStopped override, unlike [AppBoosterService.onDestroy]: CoroutineWorker makes it final
     * here. A WorkManager-initiated stop cancels this coroutine instead, and the engine's own
     * CancellationException handling already covers that — it marks the run cancelled and its
     * finally destroys the in-flight compile process. The user-facing Stop action never goes
     * through WorkManager at all: it routes through [DexoptSweepStopReceiver] to
     * [GamingEngine.cancelArtOptimization], so the recurring schedule survives the stop.
     */

    private fun foregroundInfo(state: BoosterState): ForegroundInfo {
        val notification = buildNotification(state)
        return if (Build.VERSION.SDK_INT >= 34) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    /** Mirrors [AppBoosterService]'s notification, with the scheduled sweep's own title. */
    private fun buildNotification(state: BoosterState): Notification {
        val stop = PendingIntent.getBroadcast(
            applicationContext,
            REQUEST_STOP,
            Intent(applicationContext, DexoptSweepStopReceiver::class.java).apply {
                action = DexoptSweepStopReceiver.ACTION_STOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(applicationContext, AppBoosterService.CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.booster_scheduled_title))
            .setContentText(contentText(state))
            .setSmallIcon(R.drawable.ic_stat_name)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(R.drawable.ic_action_name, applicationContext.getString(R.string.booster_notification_stop), stop)

        val progress = state.progress
        if (progress == null) {
            // The package list has not been queried yet, so there is no percentage to show.
            builder.setProgress(0, 0, true)
        } else {
            builder.setProgress(100, (progress * 100).roundToInt().coerceIn(0, 100), false)
        }
        return builder.build()
    }

    private fun contentText(state: BoosterState): String = when {
        state.currentPackage != null -> applicationContext.getString(
            R.string.booster_notification_compiling,
            state.processedCount + 1,
            state.totalCount,
            state.currentPackage
        )
        state.totalCount > 0 -> applicationContext.getString(
            R.string.booster_notification_progress, state.processedCount, state.totalCount
        )
        else -> applicationContext.getString(R.string.booster_notification_preparing)
    }

    /**
     * The one surface a skipped run gets. Auto-cancel, no Stop action — there is nothing running
     * to stop, and the text is the engine's own reason for standing aside.
     */
    private fun postSkippedNotification(reason: String) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java) ?: return
        val text = applicationContext.getString(R.string.booster_scheduled_skipped, reason)
        val notification = NotificationCompat.Builder(applicationContext, AppBoosterService.CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.booster_scheduled_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_stat_name)
            .setAutoCancel(true)
            .build()
        manager.notify(SKIPPED_NOTIFICATION_ID, notification)
    }

    private fun ensureChannel() {
        // The service creates this channel too; createNotificationChannel is idempotent, so both
        // paths are safe whichever runs first.
        val manager = applicationContext.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(AppBoosterService.CHANNEL_ID, applicationContext.getString(R.string.gt_svc_booster_channel), NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        /** Addresses the schedule as one unit — cancelling by tag would leave it half-alive. */
        const val UNIQUE_WORK_NAME = "dexopt_scheduled_sweep"

        // Distinct from the manual sweep's 101, so both notifications can be up at once. The
        // skipped notice must also avoid the overlay's 104: IDs are per-app, and a collision
        // would let a skipped-sweep notice replace the overlay's ongoing notification.
        private const val NOTIFICATION_ID = 103
        private const val SKIPPED_NOTIFICATION_ID = 105
        private const val REQUEST_STOP = 1
    }
}
