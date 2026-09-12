package com.catsmoker.app.features.gamingtools.tools.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import com.catsmoker.app.R
import com.catsmoker.app.features.main.engine.MetricsEngine
import com.catsmoker.app.shared.data.model.FpsSource
import com.catsmoker.app.shared.data.model.MetricReadStatus
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class PerformanceOverlayService : Service() {

    @Inject
    lateinit var metricsEngine: MetricsEngine

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())

        // The notification's Stop action. Handled before the rest so it can never re-show the
        // overlay: this method re-inflates and re-adds the view on every delivery, which is right
        // for a start and wrong for a stop.
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // The notification's quick-toggle: hide the window but keep the service (and the
        // metrics engine) alive, or bring it back. Deliberately not a stop — a gamer hiding the
        // readout mid-match wants it one tap away, not a cold start away. The notification is
        // rebuilt so the action's label follows the new state.
        if (intent?.action == ACTION_TOGGLE) {
            if (overlayView != null) hideOverlay() else showOverlay()
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification())
            return START_NOT_STICKY
        }

        showOverlay()
        metricsEngine.start()
        startUpdating()

        isRunning = true
        // Remember that the overlay was deliberately on, so the boot receiver can bring it back
        // after a reboot or an app update. Cleared in onDestroy — the one teardown every stop
        // path funnels through.
        prefs.edit { putBoolean(KEY_WAS_RUNNING, true) }
        return START_NOT_STICKY
    }

    private fun showOverlay() {
        @android.annotation.SuppressLint("InflateParams")
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_performance, null)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 100
        }
        windowManager?.addView(overlayView, params)
    }

    /** The quick-toggle's other half: window gone, service and metrics still running. */
    private fun hideOverlay() {
        overlayView?.let { view ->
            runCatching { windowManager?.removeView(view) }
        }
        overlayView = null
    }

    private fun startUpdating() {
        serviceScope.launch {
            // Resolved on every emission, not captured once: the quick-toggle replaces the
            // window, and a collector holding the old view's TextViews would keep updating a
            // detached view while the fresh one sat blank.
            metricsEngine.state.collect { state ->
                val fpsView = overlayView?.findViewById<TextView>(R.id.fpsNumber)
                val cpuView = overlayView?.findViewById<TextView>(R.id.cpuNumber)
                val powerView = overlayView?.findViewById<TextView>(R.id.powerNumber)
                val ramView = overlayView?.findViewById<TextView>(R.id.ramNumber)
                val tempView = overlayView?.findViewById<TextView>(R.id.tempNumber)
                // Frames per second, labelled by where the number came from: the vsync fallback
                // measures this app's frames, not the game's, and must not be passed off as FPS.
                val fpsLabel = if (state.fpsSource == FpsSource.Choreographer) "UI FPS" else "FPS"
                fpsView?.text = row(fpsLabel, state.fps?.toString(), state.fpsReadStatus)
                cpuView?.text = row("CPU", state.cpuPercentage?.let { "$it%" }, state.cpuReadStatus)
                powerView?.text = row(
                    "Power",
                    state.powerW?.let { String.format(Locale.US, "%.2f W", it) },
                    state.powerReadStatus
                )
                ramView?.text = row(
                    "RAM",
                    state.ramUsedGb?.let { used ->
                        val total = state.ramTotalGb
                        if (total != null) {
                            String.format(Locale.US, "%.1f / %.1f GB", used, total)
                        } else {
                            String.format(Locale.US, "%.1f GB", used)
                        }
                    },
                    state.ramReadStatus
                )
                tempView?.text = row(
                    if (state.displayTempIsSoc) "SoC" else "Batt",
                    state.displayTempC?.let { String.format(Locale.US, "%.1f°C", it) },
                    state.displayTempReadStatus
                )
            }
        }
    }

    /**
     * One overlay line.
     *
     * With no [value] the metric's own reason is printed — "needs root/Shizuku", "unsupported" —
     * because a 0 or a dash would claim a reading the device never gave.
     */
    private fun row(label: String, value: String?, status: MetricReadStatus): String =
        "$label: ${value ?: getString(status.labelRes)}"

    /**
     * The ongoing notification, with a quick-toggle and a Stop action so the overlay can be
     * driven entirely from the shade.
     *
     * The toggle's label follows the window's state, so the notification is re-posted by the
     * toggle branch of onStartCommand — a stale "Hide" on a hidden overlay would make the
     * button look broken. onDestroy removes the window, stops the metrics engine, and clears
     * [isRunning] — the same teardown the in-app switch's stopService gets.
     * getForegroundService rather than getService, because from API 26 a service started from a
     * notification action must call startForeground, which onStartCommand does on entry.
     */
    private fun buildNotification(): android.app.Notification {
        val toggle = android.app.PendingIntent.getForegroundService(
            this,
            REQUEST_TOGGLE,
            Intent(this, PerformanceOverlayService::class.java).setAction(ACTION_TOGGLE),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val stop = android.app.PendingIntent.getForegroundService(
            this,
            REQUEST_STOP,
            Intent(this, PerformanceOverlayService::class.java).setAction(ACTION_STOP),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.gt_svc_perf_title))
            .setSmallIcon(R.drawable.ic_stat_name)
            .setOngoing(true)
            .addAction(R.drawable.ic_action_name, if (overlayView != null) getString(R.string.gt_svc_perf_hide) else getString(R.string.gt_svc_perf_show), toggle)
            .addAction(R.drawable.ic_action_name, getString(R.string.notification_stop), stop)
            .build()
    }

    override fun onDestroy() {
        if (overlayView != null) {
            windowManager?.removeView(overlayView)
        }
        metricsEngine.stop()
        serviceScope.cancel()
        isRunning = false
        // The overlay is gone by every path that ends here, so the boot receiver must not
        // resurrect it. A hard process kill skips this — the flag then stays true and the
        // overlay comes back on the next boot, which is the same tradeoff the reference makes:
        // erring towards restoring what the user last had on.
        prefs.edit { putBoolean(KEY_WAS_RUNNING, false) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.gt_svc_perf_channel), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    companion object {
        var isRunning = false
        /** Ends the service from the notification's Stop action; onDestroy does the real teardown. */
        const val ACTION_STOP = "com.catsmoker.app.STOP_PERFORMANCE_OVERLAY"
        /** The notification's quick-toggle: hide or re-show the window without stopping. */
        const val ACTION_TOGGLE = "com.catsmoker.app.TOGGLE_PERFORMANCE_OVERLAY"
        const val PREFS_NAME = "overlay_prefs"
        const val KEY_WAS_RUNNING = "perf_overlay_was_running"
        private const val CHANNEL_ID = "perf_overlay_channel"
        private const val NOTIFICATION_ID = 104
        private const val REQUEST_STOP = 0
        private const val REQUEST_TOGGLE = 1
    }
}
