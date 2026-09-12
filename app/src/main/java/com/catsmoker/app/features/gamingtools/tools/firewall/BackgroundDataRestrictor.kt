package com.catsmoker.app.features.gamingtools.tools.firewall

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import com.catsmoker.app.R
import com.catsmoker.app.system.shell.ShellRunner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Denies background mobile data to installed apps, leaving the game library untouched.
 *
 * ## What this actually is
 *
 * Two of Android's own network policies, driven through `cmd netpolicy` — the shell interface to
 * `NetworkPolicyManagerService`, the same service the Settings app talks to. Nothing is intercepted,
 * proxied or inspected by this app.
 *
 * 1. **Per-app metered-background denial** (`add restrict-background-blacklist <uid>`, the platform's
 *    `POLICY_REJECT_METERED_BACKGROUND`). This is exactly the "Background data" switch under
 *    Settings → Apps → *app* → Mobile data & Wi-Fi. It is the part that actually blocks traffic, and
 *    it applies whether or not Data Saver is on.
 * 2. **Data Saver** (`set restrict-background true`) plus an exemption for each game
 *    (`add restrict-background-whitelist <uid>`). This is Settings → Network → Data Saver and that
 *    screen's "Unrestricted data access" list. It adds a global backstop that also covers system apps
 *    the per-app pass deliberately leaves alone.
 *
 * The first release of this feature had only step 2. Data Saver on its own leaves a large hole: an app
 * the user has granted unrestricted data, an app holding a foreground service, and every app the
 * platform whitelists by default all keep their background data — which is why apps still reached the
 * network with the switch on. Step 1 closes that hole per app, and every write here is confirmed by
 * reading the policy back.
 *
 * ## Why this is not a VPN, and why the original implementation was wrong
 *
 * This replaces a `VpnService` that claimed to be a firewall and was not one. That service built a tun
 * interface with `addRoute("0.0.0.0", 0)` and then never read a single packet from it, so every packet
 * routed in was silently dropped: with a game selected it blackholed *that game's* traffic, and with
 * none selected it blackholed the whole device's. It also never called `VpnService.prepare()`, so on a
 * device that had not already granted VPN consent `establish()` returned null while the service still
 * published a running state and posted a "Network Firewall Active" notification — a success message
 * for something that had not happened.
 *
 * A local VPN is also the wrong tool here on its own terms: routing every packet through a userspace
 * process adds a copy in and out of the tun on the way to the network, which costs exactly the latency
 * a gaming app exists to reduce. These policies cost nothing at runtime because the kernel enforces
 * them.
 *
 * ## Limitations, honestly
 *
 * - **Metered networks only.** Both policies govern mobile data and Wi-Fi the user has marked metered.
 *   On ordinary unmetered Wi-Fi neither does anything — [Status.meteredNow] reports whether the network
 *   in use right now is one this can affect. Blocking an app on *every* network needs a local VPN or
 *   root-level packet filtering, neither of which this feature uses.
 * - **Background only.** An app the user is looking at keeps its network, and an app stays "foreground"
 *   for a short grace period after being left.
 * - **User-installed apps only.** The per-app pass skips system apps: denying background data to
 *   Google Play services or the messaging stack breaks push for every app on the device. Data Saver
 *   still covers system apps that are not on the platform's own exemption list.
 * - **Needs root or Shizuku.** `cmd netpolicy` is a privileged shell command; there is no public API
 *   for an app to change another app's network policy.
 */
@Singleton
class BackgroundDataRestrictor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shellRunner: ShellRunner
) {
    private val prefs = context.getSharedPreferences("background_data_restrictor", Context.MODE_PRIVATE)

    /** Everything about the current state that could actually be read. */
    data class Status(
        /** Data Saver's real state, or null when neither the shell nor the framework would say. */
        val dataSaverOn: Boolean?,
        /** UIDs currently exempt from Data Saver, as `cmd netpolicy` lists them. */
        val exemptedUids: Set<Int> = emptySet(),
        /** Package names for [exemptedUids] that resolve on this device. */
        val exemptedPackages: List<String> = emptyList(),
        /** UIDs denied metered background data, as `cmd netpolicy` lists them. */
        val restrictedUids: Set<Int> = emptySet(),
        /** Package names for [restrictedUids] that resolve on this device. */
        val restrictedPackages: List<String> = emptyList(),
        /**
         * Whether this build's `cmd netpolicy` answers `list restrict-background-blacklist`.
         * False means the per-app denial cannot be verified on this device, and is reported as such
         * rather than assumed to have worked.
         */
        val perAppSupported: Boolean = false,
        /** Whether the network in use right now is one these policies can restrict. Null if unreadable. */
        val meteredNow: Boolean?,
        /** Whether a privileged channel exists to change any of this. */
        val privileged: Boolean
    )

    /** Outcome of a change, carrying the reason when it did not work. */
    data class Outcome(val success: Boolean, val message: String)

    suspend fun status(): Status = withContext(Dispatchers.IO) {
        val privileged = shellRunner.hasPrivilege()
        val exempted = if (privileged) readExemptedUids() else emptySet()
        val restricted = if (privileged) readRestrictedUids() else null
        Status(
            dataSaverOn = readDataSaver(privileged),
            exemptedUids = exempted,
            exemptedPackages = exempted.mapNotNull { labelForUid(it) },
            restrictedUids = restricted.orEmpty(),
            restrictedPackages = restricted.orEmpty().mapNotNull { labelForUid(it) },
            perAppSupported = restricted != null,
            meteredNow = readMetered(),
            privileged = privileged
        )
    }

    /**
     * Denies background metered data to every user-installed app that is not in [gamePackages], and
     * turns Data Saver on with the games exempted from it.
     *
     * Every write is verified by reading the policy back, and what the device looked like beforehand is
     * recorded so [disable] can put it back exactly — including leaving Data Saver on, and leaving an
     * app blocked, if that is how the user already had it.
     */
    suspend fun enable(gamePackages: List<String>): Outcome = withContext(Dispatchers.IO) {
        if (!shellRunner.hasPrivilege()) {
            return@withContext Outcome(false, context.getString(R.string.gt_needs_root_shizuku_change))
        }

        val wasOn = readDataSaver(privileged = true)
        val alreadyExempt = readExemptedUids()
        val alreadyRestricted = readRestrictedUids()

        // --- 1. Per-app denial: the half that actually blocks traffic. ---------------------------
        var perAppNote: String
        val blacklisted = mutableListOf<Int>()
        if (alreadyRestricted == null) {
            perAppNote = context.getString(R.string.gt_bdr_no_verify)
        } else {
            val targets = restrictableUids(gamePackages) - alreadyRestricted
            if (targets.isEmpty()) {
                perAppNote = context.getString(R.string.gt_bdr_no_targets)
            } else {
                runNetpolicyBatch("add", "restrict-background-blacklist", targets)
                val nowRestricted = readRestrictedUids().orEmpty()
                blacklisted += targets.filter { it in nowRestricted }
                val refused = targets.size - blacklisted.size
                perAppNote = if (refused == 0) {
                    context.getString(R.string.gt_bdr_blocked, blacklisted.size)
                } else {
                    context.getString(R.string.gt_bdr_blocked_some, blacklisted.size, targets.size, refused)
                }
            }
        }

        // --- 2. Data Saver + game exemptions: the global backstop. ------------------------------
        shellRunner.execSafeResult("cmd", "netpolicy", "set", "restrict-background", "true")
        val dataSaverOn = readDataSaver(privileged = true) == true

        val exempted = mutableListOf<Int>()
        val failedExempt = mutableListOf<String>()
        if (dataSaverOn) {
            for (pkg in gamePackages) {
                val uid = uidFor(pkg) ?: continue
                if (uid in alreadyExempt) continue
                shellRunner.execSafeResult("cmd", "netpolicy", "add", "restrict-background-whitelist", uid.toString())
                if (uid in readExemptedUids()) exempted += uid else failedExempt += pkg
            }
        }

        prefs.edit()
            // Only worth restoring to "off" if it was off; null means the reading failed, and
            // guessing either way would be inventing the previous state.
            .putString("data_saver_was", wasOn?.toString() ?: "unknown")
            .putStringSet("uids_we_added", exempted.map { it.toString() }.toSet())
            // Union, not overwrite: a previous revert may have retained verified-still-blocked
            // UIDs for retry, and dropping them here would forget apps that never got unblocked.
            .putStringSet(
                "uids_we_blocked",
                blacklisted.map { it.toString() }.toSet() +
                    prefs.getStringSet("uids_we_blocked", emptySet()).orEmpty()
            )
            .putBoolean("engaged", true)
            .apply()

        val saverNote = when {
            !dataSaverOn -> context.getString(R.string.gt_bdr_saver_off)
            failedExempt.isEmpty() -> context.getString(R.string.gt_bdr_saver_ok)
            else -> context.getString(R.string.gt_bdr_saver_exempt, failedExempt.joinToString())
        }
        // The blocking half is what the switch promises, so success follows it.
        val success = blacklisted.isNotEmpty() || dataSaverOn
        Outcome(success, context.getString(R.string.gt_bdr_result, perAppNote, saverNote))
    }

    /** Puts back what [enable] changed, and nothing else. */
    suspend fun disable(): Outcome = withContext(Dispatchers.IO) {
        if (!shellRunner.hasPrivilege()) {
            return@withContext Outcome(false, context.getString(R.string.gt_needs_root_shizuku_change))
        }

        // Only the entries this app added: a policy the user set for some other app in Settings is
        // not ours to remove.
        val blocked = prefs.getStringSet("uids_we_blocked", emptySet()).orEmpty()
            .mapNotNull { it.toIntOrNull() }.toSet()
        if (blocked.isNotEmpty()) {
            runNetpolicyBatch("remove", "restrict-background-blacklist", blocked)
        }
        // Verified, not assumed: whatever refused to lift stays on our record instead of being
        // deleted from it, so the next disable retries exactly the leftovers. Wiping the whole
        // set up front is what left apps blocked ("stopped") with no trace after a revert.
        val stillBlocked = partitionStillBlocked(blocked, readRestrictedUids())

        val ours = prefs.getStringSet("uids_we_added", emptySet()).orEmpty().mapNotNull { it.toIntOrNull() }
        for (uid in ours) {
            shellRunner.execSafeResult("cmd", "netpolicy", "remove", "restrict-background-whitelist", uid.toString())
        }

        val wasOn = prefs.getString("data_saver_was", "unknown")
        var saverNote = context.getString(R.string.gt_bdr_saver_left)
        if (wasOn == "false") {
            shellRunner.execSafeResult("cmd", "netpolicy", "set", "restrict-background", "false")
            saverNote = if (readDataSaver(privileged = true) == false) {
                context.getString(R.string.gt_bdr_saver_off_again)
            } else {
                context.getString(R.string.gt_bdr_saver_stuck)
            }
        } else if (wasOn == "true") {
            saverNote = context.getString(R.string.gt_bdr_saver_was_on)
        }

        prefs.edit()
            // A leftover block is still ours, so the record — and the engaged flag the Gaming Mode
            // snapshot reads — stays until a disable actually lifts it. Only a verified-clean
            // revert clears the evidence.
            .putBoolean("engaged", stillBlocked.isNotEmpty())
            .remove("uids_we_added")
            .apply()
        if (stillBlocked.isEmpty()) {
            prefs.edit().remove("uids_we_blocked").apply()
        } else {
            prefs.edit()
                .putStringSet("uids_we_blocked", stillBlocked.map { it.toString() }.toSet())
                .apply()
        }

        val unblockNote = when {
            blocked.isEmpty() -> context.getString(R.string.gt_bdr_unblock_none)
            stillBlocked.isEmpty() -> context.getString(R.string.gt_bdr_unblock_all, blocked.size)
            else -> context.getString(
                R.string.gt_bdr_unblock_some,
                blocked.size - stillBlocked.size,
                blocked.size,
                stillBlocked.size
            )
        }
        Outcome(stillBlocked.isEmpty(), context.getString(R.string.gt_bdr_result, unblockNote, saverNote))
    }

    /** Whether a previous revert left verified-still-blocked UIDs behind for the next disable to retry. */
    fun hasRetainedBlocks(): Boolean =
        prefs.getStringSet("uids_we_blocked", emptySet()).orEmpty().isNotEmpty()

    /** Whether this app is the one holding the restriction on, per its own record. */
    fun isEngaged(): Boolean = prefs.getBoolean("engaged", false)

    /**
     * UIDs eligible for per-app denial: user-installed apps that can reach the network, minus the game
     * library and this app.
     *
     * System apps are skipped on purpose — see the class limitations. Apps without `INTERNET` are
     * skipped because denying data to them would be a no-op that still shows up in the count.
     */
    private fun restrictableUids(gamePackages: List<String>): Set<Int> {
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
            .map { it.uid }
            .filter { it >= MIN_APP_UID }
            .toSet()
    }

    /**
     * Runs one `cmd netpolicy <action> <list> <uid>` per UID, batched into a few shell invocations.
     *
     * `netpolicy` takes a single UID per call, and a device with a hundred apps would otherwise mean a
     * hundred process spawns. Chaining with `;` keeps that to a handful; the caller decides what
     * actually took effect by reading the list back afterwards.
     */
    private suspend fun runNetpolicyBatch(action: String, list: String, uids: Set<Int>) {
        uids.chunked(UID_BATCH_SIZE).forEach { batch ->
            val command = batch.joinToString("; ") { "cmd netpolicy $action $list $it" }
            shellRunner.execResult(command)
        }
    }

    /**
     * Reads Data Saver's state.
     *
     * The shell is asked first because it reports the global switch. The framework fallback reports
     * this app's *own* restriction status, which tracks the global switch except in one case: if the
     * user has separately blocked this app's background data, it reads as restricted regardless. That
     * is why it is only the fallback.
     *
     * @return true/false, or null when neither source answered.
     */
    private suspend fun readDataSaver(privileged: Boolean): Boolean? {
        if (privileged) {
            val out = shellRunner.execSafeResult("cmd", "netpolicy", "get", "restrict-background")
            if (out.isSuccess) {
                val text = out.text.lowercase()
                if (text.contains("enabled")) return true
                if (text.contains("disabled")) return false
            }
        }
        return runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            cm?.restrictBackgroundStatus?.let {
                it != ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED
            }
        }.getOrNull()
    }

    /**
     * Parses `cmd netpolicy list restrict-background-whitelist`.
     *
     * The command prints one line — `Restrict background whitelisted UIDs: 10123 10456` — so the UIDs
     * are space-separated tokens on that line, not lines of their own.
     */
    private suspend fun readExemptedUids(): Set<Int> {
        val out = shellRunner.execSafeResult("cmd", "netpolicy", "list", "restrict-background-whitelist")
        if (!out.isSuccess) return emptySet()
        return parseUidList(out.text)
    }

    /**
     * Parses `cmd netpolicy list restrict-background-blacklist`.
     *
     * @return the denied UIDs, or **null** when this build does not answer that query — which is a
     *   different fact from "nothing is denied" and has to stay distinguishable, because it is the
     *   only way to know the per-app write cannot be verified here.
     */
    private suspend fun readRestrictedUids(): Set<Int>? {
        val out = shellRunner.execSafeResult("cmd", "netpolicy", "list", "restrict-background-blacklist")
        if (!out.isSuccess) return null
        if (!looksLikeUidListing(out.text)) return null
        return parseUidList(out.text)
    }

    private fun uidFor(packageName: String): Int? = runCatching {
        context.packageManager.getApplicationInfo(packageName, 0).uid
    }.getOrNull()

    /** A package name for [uid], or null when nothing on the device claims it. */
    private fun labelForUid(uid: Int): String? = runCatching {
        context.packageManager.getPackagesForUid(uid)?.firstOrNull()
    }.getOrNull()

    private fun readMetered(): Boolean? = runCatching {
        context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered
    }.getOrNull()

    companion object {
        /** Batched per shell invocation; keeps the command line well inside any argv limit. */
        private const val UID_BATCH_SIZE = 25

        /** Below this are platform UIDs, which never belong to an installed app. */
        private const val MIN_APP_UID = 10000

        /**
         * Which of [requested] UIDs are still denied after the removal attempt, from the
         * read-back listing.
         *
         * A null [after] means the listing itself is unreadable on this device — the same state
         * in which [enable] never blocks anything per-app, so there is nothing provable to
         * retain and the answer is empty rather than a guess in either direction.
         */
        fun partitionStillBlocked(requested: Set<Int>, after: Set<Int>?): Set<Int> =
            if (after == null) emptySet() else requested intersect after

        /**
         * Pulls the UIDs out of a `cmd netpolicy list …` line.
         *
         * Splits on non-digits and keeps plausible app UIDs, so it survives both the
         * `UIDs: 10123 10456` form and any per-line variant an OEM ROM prints. `none` yields nothing.
         */
        fun parseUidList(output: String): Set<Int> =
            Regex("\\d+").findAll(output.substringAfter(':', output))
                .mapNotNull { it.value.toIntOrNull() }
                // Below this are system UIDs that never appear in this list; above it is nothing.
                .filter { it >= 1000 }
                .toSet()

        /**
         * Whether output is a UID listing rather than the usage text `netpolicy` prints for a
         * subcommand it does not know.
         *
         * AOSP prints `Restrict background blacklisted UIDs: 10123 …` when there are entries and the
         * bare word `None` when there are none, so an empty listing is not evidence of an unsupported
         * command. An unknown list type prints the whole command reference instead — that is what this
         * rules out.
         */
        fun looksLikeUidListing(output: String): Boolean {
            val text = output.lowercase()
            return !text.contains("netpolicy commands:") &&
                !text.contains("usage:") &&
                !text.contains("unknown command")
        }
    }
}
