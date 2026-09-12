package com.catsmoker.app.features.gamingtools.tools.booster

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.catsmoker.app.R
import com.catsmoker.app.features.gamingtools.engine.BoosterState
import com.catsmoker.app.features.gamingtools.engine.GamingEngine
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Runs the ART dexopt sweep in the foreground, so closing the screen does not abandon a
 * half-finished compile of every installed app.
 *
 * The stop path mirrors the reference project's: one code path serves both the in-app button and
 * the notification action, and it cancels the work before releasing the service — the reference's
 * `StopOptimizationUseCase` orders it the same way, and its notification receiver exists so the
 * notification "receives the same 'Canceled' state as when stopping via the in-app HeroCard".
 */
@AndroidEntryPoint
class AppBoosterService : Service() {

    @Inject
    lateinit var gamingEngine: GamingEngine

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var workJob: Job? = null
    private var notificationJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Foreground first, on both paths: the notification's Stop action starts this service the
        // same way the app does, and a service started that way is killed if it does not post its
        // notification straight away.
        postForegroundNotification(gamingEngine.boosterState.value)

        if (intent?.action == ACTION_STOP) {
            stopWorkAndSelf()
            return START_NOT_STICKY
        }

        if (workJob?.isActive == true) {
            // A second Start would leave two compile loops walking the same package list. The one
            // already running keeps going.
            return START_NOT_STICKY
        }

        val mode = intent?.getStringExtra(EXTRA_MODE) ?: DEFAULT_MODE
        val force = intent?.getBooleanExtra(EXTRA_FORCE, false) ?: false
        startWork(mode, force)
        startNotificationUpdates()
        return START_NOT_STICKY
    }

    private fun startWork(mode: String, force: Boolean) {
        workJob = serviceScope.launch {
            gamingEngine.runArtOptimization(mode, force)
            // The sweep is over either way, so the foreground slot and its notification go with it.
            stopSelf()
        }
    }

    /**
     * Stops the sweep, then the service.
     *
     * [GamingEngine.cancelArtOptimization] is what actually unblocks the work: cancelling [workJob]
     * cannot, because `Process.waitFor` ignores coroutine cancellation, so the engine destroys the
     * compiler process instead. The join is bounded — a compile that ignores the destroy must not
     * hold the service open indefinitely.
     */
    private fun stopWorkAndSelf() {
        serviceScope.launch {
            gamingEngine.cancelArtOptimization()
            withTimeoutOrNull(STOP_GRACE_MS) { workJob?.join() }
            workJob?.cancel()
            stopSelf()
        }
    }

    /** Keeps the notification on the real counts for as long as the sweep runs. */
    private fun startNotificationUpdates() {
        notificationJob?.cancel()
        notificationJob = serviceScope.launch {
            gamingEngine.boosterState.collect { state ->
                notificationManager()?.notify(NOTIFICATION_ID, buildNotification(state))
            }
        }
    }

    private fun postForegroundNotification(state: BoosterState) {
        val notification = buildNotification(state)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * The ongoing notification: what the sweep is on now, and a Stop action.
     *
     * The action is the same stop path the in-app button uses, so stopping from either place leaves
     * the UI in the one cancelled state.
     */
    private fun buildNotification(state: BoosterState): Notification {
        val stop = PendingIntent.getForegroundService(
            this,
            REQUEST_STOP,
            stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.booster_running_title))
            .setContentText(contentText(state))
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(0, getString(R.string.booster_notification_stop), stop)

        val progress = state.progress
        if (progress == null) {
            // The package list has not been queried yet, so there is no percentage to show.
            builder.setProgress(0, 0, true)
        } else {
            builder.setProgress(100, (progress * 100).roundToInt().coerceIn(0, 100), false)
        }
        return builder.build()
    }

    private fun contentText(state: BoosterState): String {
        val pkg = state.currentPackage
        return when {
            pkg != null -> getString(
                R.string.booster_notification_compiling,
                state.processedCount + 1,
                state.totalCount,
                pkg
            )
            state.totalCount > 0 -> getString(
                R.string.booster_notification_progress,
                state.processedCount,
                state.totalCount
            )
            else -> getString(R.string.booster_notification_preparing)
        }
    }

    override fun onDestroy() {
        // Cancelling serviceScope cannot stop a blocking waitFor, so the compile is stopped here
        // and now: a teardown the system initiated must not leave a dexopt sweep running with
        // nothing left to report it or stop it. No-op when the sweep already finished.
        gamingEngine.cancelArtOptimizationNow()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notificationManager(): NotificationManager? =
        getSystemService(NotificationManager::class.java)

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.gt_svc_booster_channel), NotificationManager.IMPORTANCE_LOW)
        notificationManager()?.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_STOP = "com.catsmoker.app.ACTION_STOP_BOOSTER"
        const val EXTRA_MODE = "mode"
        const val EXTRA_FORCE = "force"
        const val DEFAULT_MODE = "speed-profile"

        /**
         * Shared with the scheduled sweep's worker: both surfaces post about the same engine run,
         * so they belong on one channel rather than two notifications that behave differently.
         */
        const val CHANNEL_ID = "app_booster_channel"
        private const val NOTIFICATION_ID = 101
        private const val REQUEST_STOP = 1
        private const val STOP_GRACE_MS = 3000L

        fun startIntent(context: Context, mode: String, force: Boolean): Intent {
            return Intent(context, AppBoosterService::class.java).apply {
                putExtra(EXTRA_MODE, mode)
                putExtra(EXTRA_FORCE, force)
            }
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, AppBoosterService::class.java).apply {
                action = ACTION_STOP
            }
        }
    }
}
