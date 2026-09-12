package com.catsmoker.app.features.gamingtools.engine

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
import com.catsmoker.app.system.MainActivity
import com.catsmoker.app.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class GamingModeService : Service() {

    @Inject
    lateinit var gamingEngine: GamingEngine

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        _isRunning.value = true
        createNotificationChannel()

        val notification = buildNotification()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Reverts the system settings before releasing the service, exactly as the in-app switch
            // (deactivateGamingMode) and onTaskRemoved do. This path used to call stopSelf() alone,
            // which ended the service while leaving animation scales, DND, and the rest of the
            // snapshot applied — a stop that did not undo anything it was stopping.
            serviceScope.launch {
                gamingEngine.toggleGamingMode(false)
                stopSelf()
            }
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        _isRunning.value = false
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Ensure system settings are restored if the app is swiped from recents
        serviceScope.launch {
            gamingEngine.toggleGamingMode(false)
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.gt_svc_gm_channel), NotificationManager.IMPORTANCE_LOW)
        channel.description = getString(R.string.gt_svc_gm_channel_desc)
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(this, 0, tapIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // The same ACTION_STOP the service already handled, now reachable from the shade. It restores
        // the snapshot before stopping, so this button is equivalent to turning the switch off in-app.
        // getForegroundService, not getService: from API 26 a service started from a notification
        // action must call startForeground, which onCreate does on entry.
        val stop = PendingIntent.getForegroundService(
            this,
            REQUEST_STOP,
            Intent(this, GamingModeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.gt_svc_gm_title))
            .setContentText(getString(R.string.gt_svc_gm_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Use appropriate icon
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pi)
            .addAction(0, getString(R.string.notification_stop), stop)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "gaming_mode_channel"
        const val NOTIFICATION_ID = 102
        const val ACTION_STOP = "com.catsmoker.app.ACTION_STOP_GAMING_MODE"
        private const val REQUEST_STOP = 0

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
    }
}
