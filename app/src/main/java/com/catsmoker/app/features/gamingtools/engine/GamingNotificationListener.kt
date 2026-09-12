package com.catsmoker.app.features.gamingtools.engine

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Second-layer notification suppression while Gaming Mode is on.
 *
 * Cross-checked against the reference project's `GamingNotificationListener`
 * (`referance/gamingtools/booster`, `com.framex.app.gaming.GamingNotificationListener`): the
 * same shape and the same reason to exist. DND is the first layer, but some OEM skins post
 * their own "system warning" and battery alerts around the interruption filter — cancelling
 * the notification directly does not depend on the filter being honoured.
 *
 * This service only ever *cancels* what the tray shows: it posts nothing and changes no
 * setting, so deactivation needs no restore. When [GamingEngine.notificationSuppressionActive]
 * flips off, this listener simply stops cancelling, and anything posted afterwards reappears
 * normally. That asymmetry is deliberate — a suppression layer that had to be "undone" would
 * need to answer which notifications it ate, which the platform does not offer.
 *
 * The user must grant notification access (Settings → Special app access → Notification
 * access) before the system will bind this service at all. Until then the layer is reported
 * by [GamingModeReport.notificationSuppression] as null — "not applicable" — rather than as a
 * failure, the same distinction every other report field makes.
 */
@AndroidEntryPoint
class GamingNotificationListener : NotificationListenerService() {

    @Inject lateinit var gamingEngine: GamingEngine

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onListenerConnected() {
        super.onListenerConnected()
        // Covers the mid-game grant too: if the user enables notification access while Gaming
        // Mode is already on, the tray that filled up before this service existed is purged as
        // well, not just notifications posted from now on.
        if (gamingEngine.notificationSuppressionActive.value) {
            purgeExistingNotifications()
        }
        serviceScope.launch {
            gamingEngine.notificationSuppressionActive.collectLatest { active ->
                if (active) purgeExistingNotifications()
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        // Never our own: cancelling the Gaming Mode foreground notification would tear down
        // the mode's own stop control, and the overlays' notifications are live telemetry.
        if (sbn.packageName == packageName) return
        if (gamingEngine.notificationSuppressionActive.value) {
            try {
                cancelNotification(sbn.key)
            } catch (_: Exception) {
                // Failing to cancel one notification is non-fatal; the next one gets its own try.
            }
        }
    }

    /**
     * Walks the current tray and cancels everything that is not ours, the same walk the
     * reference performs on activation — DND silences what arrives next, this clears what is
     * already on screen.
     */
    private fun purgeExistingNotifications() {
        val current = try {
            activeNotifications
        } catch (_: Exception) {
            return
        } ?: return
        for (sbn in current) {
            if (sbn.packageName == packageName) continue
            try {
                cancelNotification(sbn.key)
            } catch (_: Exception) {
                // Non-fatal, same as above.
            }
        }
    }
}
