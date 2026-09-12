package com.catsmoker.app.features.gamingtools.tools.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Brings the performance overlay back after a reboot or an app update — but only if the user
 * had it on.
 *
 * Cross-checked against the reference project's `BootReceiver`
 * (`referance/gamingtools/booster`, `com.framex.app.overlay.BootReceiver`), which solves the
 * same problem the same way: the service's `isRunning` flag is in-process state and resets on
 * a fresh boot, so the decision has to come from a persisted preference the service writes
 * when it starts and clears when it is torn down. Not exported — only the system addresses
 * this receiver, through the filters declared in the manifest.
 *
 * `MY_PACKAGE_REPLACED` matters as much as `BOOT_COMPLETED`: an app update kills the process
 * without running onDestroy, so the persisted flag is what survives, and restoring the overlay
 * after an update is exactly what the user set it up for.
 */
class OverlayBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val prefs = context.getSharedPreferences(PerformanceOverlayService.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(PerformanceOverlayService.KEY_WAS_RUNNING, false)) return
        ContextCompat.startForegroundService(
            context,
            Intent(context, PerformanceOverlayService::class.java)
        )
    }
}
