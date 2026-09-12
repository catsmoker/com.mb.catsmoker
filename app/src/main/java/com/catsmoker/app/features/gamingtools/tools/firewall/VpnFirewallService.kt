package com.catsmoker.app.features.gamingtools.tools.firewall

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.catsmoker.app.R
import com.catsmoker.app.system.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The `tun` interface that holds blocked apps' traffic and never forwards it.
 *
 * `Builder.addAllowedApplication` is the whole mechanism: a package named there is routed into this
 * interface, and a package not named there bypasses the VPN entirely. Since nothing in this service
 * ever reads the descriptor, a routed packet is accepted by the interface and goes no further — which
 * is how NetGuard and every other no-root blocker on Android works.
 *
 * The descriptor is kept open for exactly as long as the block is meant to last. Closing it is what
 * lifts the block, so [onDestroy] closes it rather than relying on the process dying.
 */
@AndroidEntryPoint
class VpnFirewallService : VpnService() {

    @Inject
    lateinit var firewall: VpnFirewall

    private var tunnel: ParcelFileDescriptor? = null

    companion object {
        const val ACTION_STOP = "com.catsmoker.app.action.STOP_VPN_FIREWALL"
        private const val CHANNEL_ID = "vpn_firewall_channel"
        private const val NOTIF_ID = 4301
        private const val REQUEST_STOP = 0

        /**
         * A private address range for the interface itself. Nothing is ever sent to it — a tun needs
         * an address to exist at all, and these two are in ranges reserved for exactly this use.
         */
        private const val TUN_IPV4 = "10.111.222.1"
        private const val TUN_IPV6 = "fd00:1:cat5:1::1"
        private const val MTU = 1500
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            teardown()
            stopSelf()
            return START_NOT_STICKY
        }

        val targets = firewall.consumeBlockList()
        if (targets.isEmpty()) {
            // Establishing with nothing allowed would route the entire device into a dead interface.
            firewall.onFailed(getString(R.string.gt_vpn_fail_empty))
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIF_ID, buildNotification(getString(R.string.gt_svc_starting)))

        val builder = Builder()
            .setSession(getString(R.string.gt_svc_vpn_session))
            .setMtu(MTU)
            .addAddress(TUN_IPV4, 32)
            .addRoute("0.0.0.0", 0)

        // Some devices ship without IPv6 on the tun driver and throw here. IPv4 blocking still works,
        // so this is attempted and not required — but a blocked app could then still reach an
        // IPv6-only host, and that is reported rather than hidden.
        val ipv6 = runCatching {
            builder.addAddress(TUN_IPV6, 128).addRoute("::", 0)
        }.isSuccess

        // Counted from the calls Android accepted: a package uninstalled since the list was built
        // throws NameNotFoundException, and must not be counted as blocked.
        var accepted = 0
        targets.forEach { pkg ->
            runCatching { builder.addAllowedApplication(pkg) }.onSuccess { accepted++ }
        }
        if (accepted == 0) {
            firewall.onFailed(getString(R.string.gt_vpn_fail_accept))
            stopSelf()
            return START_NOT_STICKY
        }

        // Sending our own configuration Intent here is what makes Android's VPN notification lead
        // back into this app rather than to nothing.
        runCatching { builder.setConfigureIntent(mainActivityIntent()) }

        val fd = try {
            builder.establish()
        } catch (e: Exception) {
            // The usual cause is consent having been revoked between the check and this call.
            firewall.onFailed(getString(R.string.gt_vpn_fail_refused, e.javaClass.simpleName))
            stopSelf()
            return START_NOT_STICKY
        }

        if (fd == null) {
            firewall.onFailed(getString(R.string.gt_vpn_fail_interface))
            stopSelf()
            return START_NOT_STICKY
        }

        tunnel = fd
        firewall.onEstablished(accepted)
        val ipv6Failed = !ipv6
        val note = getString(R.string.gt_svc_vpn_blocking, accepted) +
            if (ipv6Failed) getString(R.string.gt_svc_vpn_ipv4) else ""
        updateNotification(note)
        return START_STICKY
    }

    private fun teardown() {
        runCatching { tunnel?.close() }
        tunnel = null
        firewall.onStopped()
    }

    /**
     * Called by Android when the user revokes VPN permission or another VPN app takes over.
     *
     * The interface is already gone at this point, so the only correct thing to do is report it —
     * leaving the switch on would be claiming a block that no longer exists.
     */
    override fun onRevoke() {
        firewall.onFailed(getString(R.string.gt_vpn_fail_revoke))
        tunnel = null
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun mainActivityIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
    )

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.gt_svc_vpn_channel), NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): android.app.Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.gt_svc_vpn_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_stat_name)
            .setOngoing(true)
            .setContentIntent(mainActivityIntent())
            // The same stop path the in-app switch uses ([VpnFirewall.stop] sends this action), so
            // either exit tears the tun down through teardown() and reports onStopped().
            // getForegroundService, not getService: from API 26 a service started from a
            // notification action must call startForeground, which onStartCommand does on entry.
            .addAction(
                R.drawable.ic_action_name,
                getString(R.string.notification_stop),
                PendingIntent.getForegroundService(
                    this,
                    REQUEST_STOP,
                    Intent(this, VpnFirewallService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
}
