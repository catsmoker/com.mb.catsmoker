package com.catsmoker.app.features.gamingtools.tools.dns

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.os.Build
import android.provider.Settings
import com.catsmoker.app.R
import com.catsmoker.app.system.shell.ShellRunner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Changes which DNS resolver the device uses, through Android's Private DNS setting.
 *
 * ## Why this was rewritten
 *
 * The first version of this feature did nothing at all. It wrote the system properties `net.dns1`,
 * `net.dns2`, `net.eth0.dns1` and `net.eth0.dns2`. Those properties were how DNS was configured in
 * Android 4.x; since Android 5 resolution goes through `netd`, which takes its servers from the
 * network's own `LinkProperties` and from the Private DNS setting, and never reads those properties.
 * Nothing on a modern device consumes them, so every button press succeeded and changed nothing. It
 * also called `com.topjohnwu.superuser.Shell` directly instead of [ShellRunner], so on a Shizuku-only
 * device the commands were not even attempted.
 *
 * ## What it does now
 *
 * Writes `Settings.Global.private_dns_mode` and `private_dns_specifier` — the two keys behind
 * Settings → Network & internet → Private DNS, and the only user-settable DNS control the platform
 * offers. Each write is confirmed by reading the key back, because `settings put` exits 0 even when the
 * value is silently dropped.
 *
 * [Status.activeServers] reads the resolvers the active network is *actually* using, out of its
 * `LinkProperties`. That is the honest test of whether any of this had an effect: if the list does not
 * change after applying a provider, the change did not take.
 *
 * ## What it is worth, honestly
 *
 * DNS is used to turn a hostname into an address when a connection is opened. It is not in the path of
 * an established game connection, so **this does not lower in-game ping**. What a faster resolver
 * improves is the delay before a connection starts — launching a game, loading its assets, matchmaking
 * calls, web content — and Private DNS specifically encrypts those lookups, which stops a network from
 * seeing or redirecting them. Mode `hostname` adds a TLS handshake to the first lookup on a network,
 * which is a small one-off cost in exchange for that.
 *
 * ## Limitations
 *
 * - **Android 9 (API 28) and up.** Private DNS does not exist before that, and there is no other
 *   supported way to set a system-wide resolver; [Status.unsupportedReason] says so on older devices.
 * - **Needs root, Shizuku, or `WRITE_SECURE_SETTINGS` granted over adb.** These are secure settings.
 * - **A VPN takes precedence.** While a VPN is up, it supplies the resolvers and Private DNS is
 *   bypassed, which [Status.vpnActive] reports.
 */
@Singleton
class DnsFeature @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shellRunner: ShellRunner
) {
    /** The three states `private_dns_mode` can hold, named as Android names them. */
    enum class Mode(val settingValue: String, val label: String) {
        /** Plain DNS to whatever the network hands out. */
        OFF("off", "Off"),

        /** Encrypted to the network's own resolver when it supports it, plain otherwise. */
        AUTOMATIC("opportunistic", "Automatic"),

        /** Encrypted to a named provider, and no fallback to plain DNS if it fails. */
        PROVIDER("hostname", "Private DNS provider");

        companion object {
            fun from(value: String?): Mode? = entries.firstOrNull { it.settingValue == value }
        }
    }

    /**
     * A DNS-over-TLS provider.
     *
     * The hostname is what the platform needs — Private DNS is verified by certificate, so it takes a
     * name, never an IP. The IPs are shown only so the reading in [Status.activeServers] can be
     * recognised as belonging to the provider that was picked.
     */
    data class Provider(
        val id: String,
        val label: String,
        val hostname: String,
        val addresses: List<String>,
        val note: String
    )

    /** Everything about the current DNS configuration that could be read. */
    data class Status(
        /** The mode the device reports, or null when the key could not be read. */
        val mode: Mode?,
        /** The raw value of `private_dns_mode`, kept for a value this app does not know. */
        val rawMode: String?,
        /** The configured provider hostname, or null when none is set. */
        val hostname: String?,
        /** The resolvers the active network is using right now — the proof any of this took effect. */
        val activeServers: List<String>,
        /** The provider name the platform reports as validated on the active network, if any. */
        val validatedPrivateDns: String?,
        /** Whether a VPN is supplying the resolvers instead, which overrides all of this. */
        val vpnActive: Boolean,
        /** Whether this Android version has Private DNS at all. */
        val supported: Boolean,
        /** Why not, when [supported] is false. */
        val unsupportedReason: String?,
        /** Whether a channel exists to write the setting. */
        val canWrite: Boolean
    )

    /** Outcome of a change, carrying the device's reason when it did not take. */
    data class Outcome(val success: Boolean, val message: String)

    suspend fun status(): Status = withContext(Dispatchers.IO) {
        val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        val raw = if (supported) readGlobal(KEY_MODE) else null
        val link = activeLinkProperties()
        Status(
            mode = Mode.from(raw),
            rawMode = raw,
            hostname = if (supported) readGlobal(KEY_SPECIFIER)?.takeIf { it.isNotBlank() } else null,
            activeServers = link?.dnsServers?.mapNotNull { it.hostAddress }.orEmpty(),
            validatedPrivateDns = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                link?.privateDnsServerName
            } else {
                null
            },
            vpnActive = isVpnActive(),
            supported = supported,
            unsupportedReason = if (supported) {
                null
            } else {
                context.getString(R.string.gt_dns_old_phone, Build.VERSION.RELEASE)
            },
            canWrite = canWriteGlobal()
        )
    }

    /**
     * Points Private DNS at [provider].
     *
     * The specifier is written before the mode: switching to `hostname` with a stale or empty specifier
     * would leave the device with no resolver it is willing to use, and `netd` fails closed there — no
     * name resolves at all until a valid one arrives.
     */
    suspend fun apply(provider: Provider): Outcome = withContext(Dispatchers.IO) {
        val guard = writeGuard() ?: return@withContext guardFailure()
        val specifierOk = putGlobal(KEY_SPECIFIER, provider.hostname)
        if (!specifierOk) {
            return@withContext Outcome(
                false,
                context.getString(R.string.gt_dns_refused_provider, provider.label, guard)
            )
        }
        val modeOk = putGlobal(KEY_MODE, Mode.PROVIDER.settingValue)
        if (!modeOk) {
            return@withContext Outcome(
                false,
                context.getString(R.string.gt_dns_set_not_switched, provider.label)
            )
        }
        Outcome(true, context.getString(R.string.gt_dns_now_using_toast, provider.label))
    }

    /** Switches to `opportunistic` — encrypted where the network supports it, and clears the hostname. */
    suspend fun setAutomatic(): Outcome = withContext(Dispatchers.IO) {
        val guard = writeGuard() ?: return@withContext guardFailure()
        if (!putGlobal(KEY_MODE, Mode.AUTOMATIC.settingValue)) {
            return@withContext Outcome(
                false,
                context.getString(R.string.gt_dns_no_auto, guard)
            )
        }
        Outcome(true, context.getString(R.string.gt_dns_auto_done))
    }

    /** Turns Private DNS off, leaving the network's own resolvers in use unencrypted. */
    suspend fun disable(): Outcome = withContext(Dispatchers.IO) {
        val guard = writeGuard() ?: return@withContext guardFailure()
        if (!putGlobal(KEY_MODE, Mode.OFF.settingValue)) {
            return@withContext Outcome(
                false,
                context.getString(R.string.gt_dns_no_off, guard)
            )
        }
        Outcome(true, context.getString(R.string.gt_dns_off_done))
    }

    // ------------------------------------------------------------------------ plumbing

    /**
     * The channel a write will actually use, or null when there is none.
     *
     * Named in plain words because it is quoted in the messages the user reads when a write is
     * refused: knowing *how* it was asked is the difference between "try Shizuku" and "your ROM
     * refuses it even with root".
     */
    private fun writeGuard(): String? = when {
        shellRunner.isRootAvailable() -> "root"
        shellRunner.hasPrivilege() -> "Shizuku"
        canWriteGlobalDirectly() -> context.getString(R.string.gt_dns_guard_adb)
        else -> null
    }

    private fun guardFailure() = Outcome(
        false,
        context.getString(R.string.gt_dns_need)
    )

    private fun canWriteGlobal(): Boolean = writeGuard() != null

    private fun canWriteGlobalDirectly(): Boolean = context.checkSelfPermission(
        android.Manifest.permission.WRITE_SECURE_SETTINGS
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun readGlobal(key: String): String? = runCatching {
        Settings.Global.getString(context.contentResolver, key)
    }.getOrNull()

    /**
     * Writes a global setting and proves it landed.
     *
     * `settings put` returns 0 whether or not the value was kept — a namespace that rejects the key, or
     * a ROM that overwrites it immediately, both look like success — so the read-back is the only
     * evidence. The direct provider write is attempted as well when the permission is held, because
     * that path works without any shell at all.
     */
    private suspend fun putGlobal(key: String, value: String): Boolean {
        if (shellRunner.hasPrivilege()) {
            shellRunner.execSafeResult("settings", "put", "global", key, value)
            if (readGlobal(key) == value) return true
        }
        if (canWriteGlobalDirectly()) {
            runCatching { Settings.Global.putString(context.contentResolver, key, value) }
        }
        return readGlobal(key) == value
    }

    private fun activeLinkProperties(): LinkProperties? = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = cm.activeNetwork ?: return null
        cm.getLinkProperties(network)
    }.getOrNull()

    private fun isVpnActive(): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        cm.getNetworkCapabilities(network)
            ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
    }.getOrDefault(false)

    companion object {
        /** `Settings.Global.PRIVATE_DNS_MODE`, which is `@hide` and so referenced by its literal name. */
        private const val KEY_MODE = "private_dns_mode"

        /** `Settings.Global.PRIVATE_DNS_SPECIFIER`, likewise hidden. */
        private const val KEY_SPECIFIER = "private_dns_specifier"

        /**
         * The providers offered.
         *
         * Each hostname is the operator's published DNS-over-TLS name — Private DNS validates the
         * server's certificate against it, so a made-up or mistyped name means no resolution at all
         * rather than a silent fallback.
         */
        val PROVIDERS = listOf(
            Provider(
                id = "cloudflare",
                label = "Cloudflare",
                hostname = "one.one.one.one",
                addresses = listOf("1.1.1.1", "1.0.0.1"),
                note = "Usually the fastest. Blocks nothing."
            ),
            Provider(
                id = "google",
                label = "Google",
                hostname = "dns.google",
                addresses = listOf("8.8.8.8", "8.8.4.4"),
                note = "Works well almost everywhere. Blocks nothing."
            ),
            Provider(
                id = "quad9",
                label = "Quad9",
                hostname = "dns.quad9.net",
                addresses = listOf("9.9.9.9", "149.112.112.112"),
                note = "Blocks websites known to be dangerous."
            ),
            Provider(
                id = "adguard",
                label = "AdGuard",
                hostname = "dns.adguard-dns.com",
                addresses = listOf("94.140.14.14", "94.140.15.15"),
                note = "Blocks ads and tracking. A few apps may stop working properly."
            )
        )
    }
}
