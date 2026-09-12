package com.catsmoker.app.features.gamingtools.tools.firewall

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import dagger.hilt.android.qualifiers.ApplicationContext
import com.catsmoker.app.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A local VPN that drops other apps' traffic — the same mechanism NetGuard uses.
 *
 * **This is a local VPN, not a kernel firewall, and the wording in the UI says so.** Nothing here
 * talks to netfilter or iptables. Android hands an app a `tun` interface, lets it name which packages
 * are routed into it, and sends everything else straight past it. This service claims the tun for the
 * apps you want blocked, routes all IPv4 and IPv6 into it, and then never reads it: their packets
 * enter the interface and stop there. Apps that are *not* on the list never touch the VPN at all and
 * keep their normal connection.
 *
 * Two consequences follow from that and are stated in the UI rather than hidden:
 * - A blocked app does not get a clean "no internet" error. Its connections sit there and time out,
 *   because the packets are accepted by the interface and simply go nowhere.
 * - Android allows exactly one active VPN. Turning this on disconnects any other VPN app, and any
 *   other VPN app turning on disconnects this. There is no way around that from an ordinary app.
 *
 * It needs no root and no Shizuku. It does need the user to accept Android's own VPN consent dialog
 * once, which only an Activity can show — see [consent].
 *
 * The set of blocked apps is deliberately the same set [BackgroundDataRestrictor] denies metered data
 * to: user-installed apps that hold `INTERNET`, minus this app and minus the game library. That keeps
 * the two switches describing one idea — "stop everything except my games from using the network" —
 * so the UI stays two plain on/off switches instead of a per-app matrix.
 */
@Singleton
class VpnFirewall @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * @param running whether a tun interface is established right now. Set from
     *   `Builder.establish()` returning a real descriptor — never from the fact that the service was
     *   asked to start.
     * @param blockedCount how many packages Android actually accepted into the route list.
     * @param lastError why the last start attempt failed, or null. Kept after a failure so the card
     *   can say what went wrong instead of quietly showing the switch back off.
     */
    data class State(
        val running: Boolean = false,
        val blockedCount: Int = 0,
        val lastError: String? = null
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Set by [start] so the service can read it after being restarted by the system. */
    @Volatile
    private var pendingBlockList: List<String> = emptyList()

    /**
     * What Android says about our permission to establish a VPN.
     *
     * The three cases must stay distinct. An earlier version collapsed [Unknown] into [Granted] by
     * returning null out of a `catch`, so a check that failed read as a consent we held: [start] went on
     * to launch the service, whose `establish()` could then only fail, and the switch reported an
     * attempt that was already doomed.
     */
    sealed interface Consent {
        /** Consent is already in place; the interface may be established. */
        data object Granted : Consent

        /**
         * The user has not agreed yet. [intent] is Android's own dialog and must be launched from an
         * Activity — the dialog belongs to the system, and an app cannot grant itself VPN rights.
         */
        data class Required(val intent: Intent) : Consent

        /** The check itself failed, so we do not know either way. [reason] is the exception's name. */
        data class Unknown(val reason: String) : Consent
    }

    /** Asks Android whether we may establish a VPN. Never throws; see [Consent.Unknown]. */
    fun consent(): Consent = try {
        val intent = VpnService.prepare(context)
        if (intent == null) Consent.Granted else Consent.Required(intent)
    } catch (t: Throwable) {
        Consent.Unknown(t.javaClass.simpleName)
    }

    /**
     * Packages this switch will block, computed fresh from the installed set.
     *
     * @param gamePackages the user's library, which is exempted so games keep working.
     */
    fun blockTargets(gamePackages: List<String>): List<String> {
        val games = gamePackages.toSet()
        val pm = context.packageManager
        val installed = runCatching { pm.getInstalledApplications(0) }.getOrNull().orEmpty()
        return installed.asSequence()
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .filter { it.packageName != context.packageName && it.packageName !in games }
            .filter {
                runCatching {
                    pm.checkPermission(android.Manifest.permission.INTERNET, it.packageName) ==
                        PackageManager.PERMISSION_GRANTED
                }.getOrDefault(false)
            }
            .map { it.packageName }
            .distinct()
            .toList()
    }

    /**
     * Starts the local VPN for every app in [gamePackages]'s complement.
     *
     * @return null when the service was asked to start, or a reason it could not be. A reason here is
     *   a refusal *before* anything was attempted; a failure inside the service lands in
     *   [State.lastError] instead.
     */
    fun start(gamePackages: List<String>): String? {
        when (val consent = consent()) {
            is Consent.Required ->
                return context.getString(R.string.gt_vpn_consent)
            is Consent.Unknown ->
                return context.getString(R.string.gt_vpn_unknown, consent.reason)
            Consent.Granted -> Unit
        }
        val targets = blockTargets(gamePackages)
        if (targets.isEmpty()) {
            // Establishing with an empty allow-list routes *every* app into the tun, which would cut
            // the whole device off the network. Refusing is the only correct answer.
            return context.getString(R.string.gt_vpn_nothing)
        }
        pendingBlockList = targets
        _state.update { it.copy(lastError = null) }
        return try {
            context.startService(Intent(context, VpnFirewallService::class.java))
            null
        } catch (e: Exception) {
            context.getString(R.string.gt_vpn_start_fail, e.javaClass.simpleName)
        }
    }

    fun stop() {
        context.startService(
            Intent(context, VpnFirewallService::class.java).setAction(VpnFirewallService.ACTION_STOP)
        )
    }

    /** Read by the service, including after the system restarts it with no Intent extras. */
    internal fun consumeBlockList(): List<String> = pendingBlockList

    internal fun onEstablished(blockedCount: Int) {
        _state.value = State(running = true, blockedCount = blockedCount, lastError = null)
    }

    internal fun onFailed(reason: String) {
        _state.value = State(running = false, blockedCount = 0, lastError = reason)
    }

    internal fun onStopped() {
        _state.update { it.copy(running = false, blockedCount = 0) }
    }

    /**
     * Whether Android reports a VPN transport on the active network.
     *
     * A second, independent check on [State.running]: that flag comes from our own `establish()` call,
     * while this comes from the connectivity service. They disagree if the platform tore our interface
     * down without telling us, which is exactly the case a switch must not keep showing as on.
     */
    fun systemReportsVpn(): Boolean? = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching null
        val network = cm.activeNetwork ?: return@runCatching false
        cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }.getOrNull()
}
