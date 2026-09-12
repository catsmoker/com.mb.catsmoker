package com.catsmoker.app.features.gamingtools.engine

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.edit
import com.catsmoker.app.R
import com.catsmoker.app.shared.data.model.GamingOptimizationSnapshot
import com.catsmoker.app.shared.data.model.SettingValue
import com.catsmoker.app.features.gamingtools.engine.parsers.DexoptStatusParser
import com.catsmoker.app.features.gamingtools.tools.firewall.BackgroundDataRestrictor
import com.catsmoker.app.features.gamingtools.tools.interventions.GameInterventions
import com.catsmoker.app.system.shell.ShellRunner
import com.catsmoker.app.shared.util.isVivoOrIqoo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

sealed class GamingModeState {
    object Idle : GamingModeState()
    data class Enabling(val progress: Float = 0f, val statusText: String = "Preparing…") : GamingModeState()
    object Active : GamingModeState()
    object Disabling : GamingModeState()
    data class Error(val message: String) : GamingModeState()
}

/**
 * What activation actually managed to apply, verified by reading each value back.
 *
 * The card used to show four fixed strings whether or not anything landed. Every field here is
 * either a measured fact or absent, and [unavailable] carries the human-readable reason for each
 * optimization the device refused — that is what gets shown instead of a slogan.
 */
data class GamingModeReport(
    val fixedPerformance: Boolean = false,
    /**
     * Whether Qualcomm's vendor GPU mode was confirmed at `performance` (with
     * `vendor.gfx.low_quality` at `1`) by reading both properties back.
     *
     * The reference project ships a native root daemon for exactly this payload
     * (`referance/spoofdevice/GameUnlocker-main/cpp/controller.cpp`, read in full: a daemon on an
     * abstract UNIX socket that sets the two properties while a whitelisted game process is
     * connected and restores them when the last one exits). The daemon itself is not portable
     * here — opening the connection from inside the game needs Zygisk injection — but its payload
     * is, and Gaming Mode is this app's equivalent lifecycle: applied on activation, restored from
     * the snapshot on deactivation. The daemon logs its `setprop` and never reads the property
     * back; this reports the device's answer instead. Its `isQualcomm()` `ro.hardware` prefix
     * table is deliberately not ported — the property's own existence is the gate, and a curated
     * chipset-name list goes stale the same way a curated module-id list does.
     *
     * null when the device carries neither property (every non-Qualcomm SoC) — not applicable
     * rather than failed, so the report row is omitted.
     */
    val gpuPerformanceMode: Boolean? = null,
    /**
     * Whether Qualcomm's `debug.vendor.qti.game.fps` hint was confirmed at the panel's measured
     * peak by reading the property back.
     *
     * The reference sets both this and its `persist.` twin to a hard-coded 120 at boot in
     * `post-fs-data.sh` (`referance/spoofdevice/GameUnlocker-main/common/post-fs-data.sh`, read
     * in full — those two `setprop` lines are that file's whole payload). Three deliberate
     * divergences:
     *  - **Session, not boot.** Gaming Mode is this app's lifecycle — the hint is applied on
     *    activation and the prior value restored from the snapshot on deactivation, the same
     *    contract as every other switch here.
     *  - **The panel's measured peak, not a hard-coded 120.** The same `maxHz` the refresh-rate
     *    lock verified is what gets hinted; 120 on a 90 Hz panel would be a number the hardware
     *    cannot honor.
     *  - **The `persist.` twin is deliberately not set.** `setprop` cannot delete a property, so
     *    a `persist.vendor.qti.game.fps` that did not exist would become a permanent,
     *    reboot-surviving, device-wide change that deactivation could overwrite but never
     *    remove — exactly the class of change the Magisk channel's narrowing rejected. The
     *    `debug.` prop is reversible in the only way Android offers: a reboot clears the debug
     *    property area. Until then, a hint that did not exist before stays hinting the panel's
     *    own peak — bounded, and stated here rather than hidden.
     *
     * null when the device's own property dump carries no `ro.vendor.qti.*` property (every
     * non-Qualcomm SoC) — not applicable, so the report row is omitted. The gate is the dump
     * itself rather than the reference's `isQualcomm()` `ro.hardware` prefix table, for the same
     * reason [gpuPerformanceMode] gives: a curated chipset list goes stale.
     */
    val qtiGameFps: Boolean? = null,
    /** Refresh rate the panel was actually pinned to, or null when the ROM ignored the keys. */
    val lockedRefreshHz: Int? = null,
    val touchResponseBoost: Boolean = false,
    val suspendedPackages: Int = 0,
    val suspendFailures: Int = 0,
    val dndEngaged: Boolean = false,
    /** null when no game was targeted, so the UI can say "not applicable" instead of "failed". */
    val networkWhitelisted: Boolean? = null,
    /** `always_finish_activities` confirmed at 1 by a read-back. */
    val discardActivities: Boolean = false,
    /** `max_cached_processes=1` confirmed present in `activity_manager_constants`. */
    val processLimit: Boolean = false,
    /**
     * Metered-background data blocked for the apps that are not the game.
     *
     * null means "left alone deliberately" — the user's own Background Data Restriction switch was
     * already on before activation, so this run neither engaged nor owns it.
     */
    val backgroundDataRestricted: Boolean? = null,
    /**
     * Whether the `device_config game_overlay` intervention for the target game was confirmed by a
     * read-back.
     *
     * null when no game was targeted or the device is older than Android 12, where game
     * interventions do not exist — both are "not applicable", not "failed", and the report row is
     * omitted rather than shown as refused.
     */
    val gameInterventionApplied: Boolean? = null,
    /**
     * Whether the second-layer notification suppression is armed.
     *
     * null means the user has not granted notification access, so
     * [GamingNotificationListener] is never bound — "not applicable", not "failed". true means
     * the listener is connected and cancelling; each individual cancellation is best-effort,
     * so the field reports the layer being armed rather than promising every notification was
     * swallowed.
     */
    val notificationSuppression: Boolean? = null,
    val unavailable: List<String> = emptyList()
)

/**
 * Whether the device accepted a fixed-performance-mode request, and what it said if it did not.
 *
 * The toggle used to set its own state from the request, so it read "on" on builds where the command
 * does not exist. [accepted] is now the framework's answer and [message] is the device's own text.
 */
data class FixedPerformanceOutcome(
    val accepted: Boolean,
    val message: String
)

/**
 * The three animation scales Android's Developer Options exposes, with the keys it writes.
 *
 * Kept as the real `Settings.Global` constants: these are the same settings the system screen edits,
 * so a change here is the same change made there — not an app-local imitation of one.
 */
enum class AnimationScaleKind(val key: String, val label: String) {
    WINDOW(Settings.Global.WINDOW_ANIMATION_SCALE, "Window animation scale"),
    TRANSITION(Settings.Global.TRANSITION_ANIMATION_SCALE, "Transition animation scale"),
    ANIMATOR(Settings.Global.ANIMATOR_DURATION_SCALE, "Animator duration scale")
}

/**
 * How the ART dexopt sweep ended, or that it never began.
 *
 * Mirrors the reference project's `OptimizationResult`, including the distinction it draws between
 * a cancelled run and a completed one — its repository carries an explicit "never overwrite a
 * cancellation with 'Completed'" guard, and that only works if the two are separate states.
 */
sealed class BoosterOutcome {
    /** Nothing has been run in this session. */
    object Idle : BoosterOutcome()
    object Running : BoosterOutcome()
    object Completed : BoosterOutcome()
    object Cancelled : BoosterOutcome()

    /** The sweep could not start at all; [reason] is what the device or the shell refused. */
    data class Unavailable(val reason: String) : BoosterOutcome()

    /** The sweep started and then broke; [reason] is the real error text. */
    data class Failed(val reason: String) : BoosterOutcome()
}

/**
 * What the ART dexopt sweep is doing right now.
 *
 * Modelled on the reference project's `OptimizationProgress`: the running flag, the counts and the
 * outcome travel together, so the UI can tell "preparing", "compiling app 12 of 90", "cancelled
 * after 12" and "finished" apart. A bare progress float could not — it reads 0 both before a run
 * starts and after one is cancelled, which is why the old card showed nothing in either case.
 *
 * Every count is incremented from a command that actually ran and actually answered.
 */
data class BoosterState(
    val isRunning: Boolean = false,
    /** Package being compiled at this moment, or null between packages. */
    val currentPackage: String? = null,
    /** Apps the sweep will visit. 0 until the package list has been queried. */
    val totalCount: Int = 0,
    /** Apps `cmd package compile` accepted. */
    val optimizedCount: Int = 0,
    /** Apps already in the requested compile filter, so no command was run for them. */
    val skippedCount: Int = 0,
    /** Apps the platform refused to compile. */
    val failedCount: Int = 0,
    val outcome: BoosterOutcome = BoosterOutcome.Idle
) {
    /** Apps visited, whether compiled, skipped or refused. */
    val processedCount: Int get() = optimizedCount + skippedCount + failedCount

    /**
     * Fraction done, or null while the total is still unknown. The UI shows an indeterminate bar
     * then, rather than a 0% that would read as a run that has stalled.
     */
    val progress: Float?
        get() = if (totalCount > 0) processedCount.toFloat() / totalCount.toFloat() else null
}

class GamingEngine(
    private val context: Context,
    private val shellRunner: ShellRunner,
    val refreshRates: DisplayRefreshRateProvider,
    private val backgroundDataRestrictor: BackgroundDataRestrictor,
    private val gameInterventions: GameInterventions
) {
    private val _state = MutableStateFlow<GamingModeState>(GamingModeState.Idle)
    val state: StateFlow<GamingModeState> = _state.asStateFlow()

    private val _report = MutableStateFlow(GamingModeReport())
    val report: StateFlow<GamingModeReport> = _report.asStateFlow()

    /**
     * Whether [GamingNotificationListener] should be cancelling notifications right now.
     *
     * Read by the listener (which the system binds in this same process whenever the user has
     * granted notification access), set only by activation, deactivation and the boot-time
     * restore of a persisted active mode — never by the listener itself.
     */
    private val _notificationSuppressionActive = MutableStateFlow(false)
    val notificationSuppressionActive: StateFlow<Boolean> = _notificationSuppressionActive.asStateFlow()

    private val _isFixedPerformanceMode = MutableStateFlow(value = false)
    val isFixedPerformanceMode: StateFlow<Boolean> = _isFixedPerformanceMode.asStateFlow()

    private val _alwaysFinishActivities = MutableStateFlow(false)
    val alwaysFinishActivities: StateFlow<Boolean> = _alwaysFinishActivities.asStateFlow()

    private val _backgroundProcessLimit = MutableStateFlow(false)
    val backgroundProcessLimit: StateFlow<Boolean> = _backgroundProcessLimit.asStateFlow()

    private val _boosterLog = MutableStateFlow<List<String>>(emptyList())
    val boosterLog: StateFlow<List<String>> = _boosterLog.asStateFlow()

    private val _boosterState = MutableStateFlow(BoosterState())
    val boosterState: StateFlow<BoosterState> = _boosterState.asStateFlow()

    /**
     * Finished sweeps, newest last, persisted across process death by [BoosterHistoryStore].
     *
     * The in-memory booster log dies with the process; this is the "what did the last boost
     * actually do" that survives. Loaded off the main thread in [init], since reading the file
     * is real disk I/O however small.
     */
    private val _boosterHistory = MutableStateFlow<List<BoosterRun>>(emptyList())
    val boosterHistory: StateFlow<List<BoosterRun>> = _boosterHistory.asStateFlow()

    private val _animationScales = MutableStateFlow(Triple(1f, 1f, 1f))
    val animationScales: StateFlow<Triple<Float, Float, Float>> = _animationScales.asStateFlow()

    private val boosterCancelRequested = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Held for the whole of one sweep and released in its `finally`.
     *
     * The published [BoosterState] cannot be the guard: cancellation clears `isRunning` immediately
     * so the UI updates without waiting for the current compile, which would leave a window where a
     * second Start could begin walking the package list alongside the first.
     */
    private val boosterActive = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Serializes Gaming Mode toggles.
     *
     * The switch can be driven from three places at once — the in-app card, the foreground
     * service's notification Stop button, and `onTaskRemoved` when the app is swiped away — and
     * enable/disable both read-modify-write the same `affected_pkgs` record. Interleaved, an
     * enable's suspends land after a disable's clear and no later revert knows about them, which
     * leaves apps frozen with an empty record: exactly the "still suspended after turning it
     * off" leak.
     */
    private val toggleMutex = Mutex()

    private val boosterHistoryStore = BoosterHistoryStore(context)

    /** Mode and start time of the sweep in flight, for its own history entry when it ends. */
    @Volatile private var activeBoosterMode: String? = null
    @Volatile private var activeBoosterStartedAt: Long = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var currentCompileProcess: Process? = null

    private val prefs = context.getSharedPreferences("gaming_engine_prefs", Context.MODE_PRIVATE)

    init {
        val isActive = prefs.getBoolean("is_active", false)
        val isFixedPerf = prefs.getBoolean("fixed_perf_manual", false)
        
        if (isActive) {
            _state.value = GamingModeState.Active
            _isFixedPerformanceMode.value = true
            // A mode that survived process death keeps its suppression layer: the flag is what
            // the listener reads, and it has no persisted state of its own to restore from.
            _notificationSuppressionActive.value = isNotificationListenerEnabled()
            scope.launch { recoverPersistedState() }
        } else if (isFixedPerf) {
            _isFixedPerformanceMode.value = true
            scope.launch { reapplyFixedPerformanceMode() }
        }

        _alwaysFinishActivities.value = getGlobalInt(android.provider.Settings.Global.ALWAYS_FINISH_ACTIVITIES) == 1
        _backgroundProcessLimit.value = getGlobalString("activity_manager_constants")?.contains("max_cached_processes=1") == true

        refreshAnimationScales()

        scope.launch { _boosterHistory.value = boosterHistoryStore.load() }
    }

    private fun getGlobalInt(key: String): Int {
        return try { android.provider.Settings.Global.getInt(context.contentResolver, key, 0) } catch (_: Exception) { 0 }
    }

    /**
     * Whether the user has granted this app notification access, which is what decides the
     * system will ever bind [GamingNotificationListener]. Reported as the difference between
     * "armed" and "not applicable" in [GamingModeReport.notificationSuppression].
     */
    private fun isNotificationListenerEnabled(): Boolean =
        androidx.core.app.NotificationManagerCompat
            .getEnabledListenerPackages(context)
            .contains(context.packageName)

    private fun getGlobalString(key: String): String? {
        return android.provider.Settings.Global.getString(context.contentResolver, key)
    }

    private fun getSystemString(key: String): String? {
        return android.provider.Settings.System.getString(context.contentResolver, key)
    }

    val googleSafeToSuspend = listOf(
        "com.google.android.youtube",
        "com.google.android.apps.photos",
        "com.google.android.apps.maps",
        "com.google.android.gm",
        "com.google.android.apps.messaging",
        "com.google.android.calendar",
        "com.google.android.googlequicksearchbox",
        "com.google.android.apps.bard",
        "com.google.android.apps.nbu.files",
        "com.google.android.apps.wellbeing",
        "com.google.android.projection.gearhead",
        "com.google.android.apps.authenticator2",
        "com.google.android.apps.restore",
        "com.android.chrome"
    )

    val systemCritical = listOf(
        "com.vivo.pem",                // Power Event Manager
        "com.vivo.abe",                // App Behavior Engine
        "com.vivo.daemonService",      // Hardware daemon
        "com.vivo.sps",                // System Power Service
        "com.vivo.pie",                // Framework extension
        "com.vivo.fingerprintui",
        "com.vivo.fingerprint",
        "com.vivo.fingerprintvit",
        "com.vivo.faceui",
        "com.vivo.faceunlock",
        "com.vivo.systemuiplugin",
        "com.vivo.networkstate",
        "com.vivo.connbase",
        "com.android.systemui",
        "com.android.phone",
        "com.mediatek.ims"              // VoLTE
    )

    val gamingDaemons = listOf(
        "com.vivo.gamecube",
        "com.vivo.gamewatch",
        "com.vivo.game",
        "com.iqoo.powersaving",        // Prevents thermal throttling
        "com.microsoft.deviceintegrationservice"  // ThermalInfoService bridge
    )

    // The vivo whitelist keys moved to [OemPackageResolver] (with two more the reference writes
    // and this engine used to skip); the constants there are the same strings these keys were.
    private val vivoGameCubeApps = OemPackageResolver.KEY_GAME_CUBE_APPS
    private val vivoSpeedModeApps = OemPackageResolver.KEY_SPEED_MODE_APPS
    private val vivoHighRefreshRateApps = OemPackageResolver.KEY_VIVO_HIGH_REFRESH_RATE_APPS
    private val vivoScreenRefreshRateAppsList = OemPackageResolver.KEY_VIVO_SCREEN_REFRESH_RATE_APPS_LIST

    private val hardWhitelist = setOf(
        "moe.shizuku.privileged.api",
        context.packageName,
        "com.topjohnwu.magisk"
    )

    suspend fun toggleGamingMode(active: Boolean, packageName: String? = null) {
        toggleMutex.withLock {
            withContext(Dispatchers.IO) {
                if (active) {
                    enableGamingMode(packageName)
                } else {
                    disableGamingMode()
                }
            }
        }
    }

    private suspend fun enableGamingMode(packageName: String?) {
        _state.value = GamingModeState.Enabling(0f, context.getString(R.string.gt_eng_init))
        shellRunner.refreshShizukuPermission()
        val isRoot = shellRunner.isRootAvailable()
        val hasShizuku = shellRunner.shizukuHasPermission.value
        if (!isRoot && !hasShizuku) {
            _state.value = GamingModeState.Error(context.getString(R.string.gt_eng_no_priv))
            return
        }
        try {
            _state.value = GamingModeState.Enabling(0.05f, context.getString(R.string.gt_eng_snapshot))
            // Read the user's real values first — before the cache trim, the suspends and every
            // `settings put` below — so deactivation restores their configuration and not ours.
            val restrictedBefore = runCatching { backgroundDataRestrictor.isEngaged() }
                .getOrDefault(false)
            if (!captureAndSaveSnapshot(packageName)) {
                _state.value = GamingModeState.Error(context.getString(R.string.gt_eng_snapshot_fail))
                return
            }
            val unavailable = mutableListOf<String>()

            _state.value = GamingModeState.Enabling(0.15f, context.getString(R.string.gt_eng_trim))
            execute("pm trim-caches 4G")
            execute("am compact background")
            runCatching { execute("cmd pinner repin /system/framework/framework.jar") }

            _state.value = GamingModeState.Enabling(0.35f, context.getString(R.string.gt_eng_suspend))
            val targets = getSuspendTargets(packageName)
            val currentlyAffected = prefs.getStringSet("affected_pkgs", emptySet())?.toMutableSet() ?: mutableSetOf()
            var suspendedNow = 0
            var suspendFailures = 0
            for (pkg in targets) {
                if (pkg in currentlyAffected) continue
                // Record only what actually got suspended. disableGamingMode unsuspends exactly
                // this set, so a package listed here that was never frozen makes the revert lie —
                // and a package frozen but not listed would be left frozen for good.
                val result = shellRunner.execSafeResult("pm", "suspend", "--user", "0", pkg)
                if (SuspendVerdict.isSuspendConfirmed(result.exitCode, result.stdout)) {
                    currentlyAffected.add(pkg)
                    suspendedNow++
                } else {
                    suspendFailures++
                }
            }
            prefs.edit { putStringSet("affected_pkgs", currentlyAffected) }
            if (suspendFailures > 0) {
                unavailable += context.getString(R.string.gt_eng_un_suspend, suspendFailures)
            }

            _state.value = GamingModeState.Enabling(0.6f, context.getString(R.string.gt_eng_focus))
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            var dndEngaged = false
            if (nm.isNotificationPolicyAccessGranted) {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
                // The filter change travels through ZenModeHelper and a config write, so reading it
                // back on the next line often still returns the old value — that race is why DND
                // "sometimes failed" while actually being applied. Poll briefly instead.
                dndEngaged = awaitInterruptionFilter(nm, NotificationManager.INTERRUPTION_FILTER_NONE)
                if (!dndEngaged) unavailable += context.getString(R.string.gt_eng_un_dnd_off)
            } else {
                unavailable += context.getString(R.string.gt_eng_un_dnd_perm)
            }

            // Second layer beyond DND, from the reference's GamingNotificationListener: some
            // OEM skins post their own alerts around the interruption filter, and cancelling
            // the notification directly does not depend on the filter being honoured. Arming is
            // all activation does — the listener (bound by the system only once the user has
            // granted notification access) does the cancelling, including a purge of what is
            // already on screen.
            val notificationSuppression = isNotificationListenerEnabled()
            _notificationSuppressionActive.value = notificationSuppression

            _state.value = GamingModeState.Enabling(0.9f, context.getString(R.string.gt_eng_hw))
            val maxHz = refreshRates.getMaxHardwareRefreshRate().toInt()
            val peakOk = putSettingVerified("system", "peak_refresh_rate", maxHz.toString())
            val minOk = putSettingVerified("system", "min_refresh_rate", maxHz.toString())
            val lockedHz = if (peakOk || minOk) maxHz else null
            if (lockedHz == null) {
                unavailable += context.getString(R.string.gt_eng_un_refresh)
            }
            // Touch sampling boost. Only OEMs that ship the key honour it; captureAndSaveSnapshot
            // already recorded the old value, so disableGamingMode puts it back.
            val touchOk = putSettingVerified("system", "touch_response_speed", "2")
            if (!touchOk) unavailable += context.getString(R.string.gt_eng_un_touch)

            val fixedPerfOk = shellRunner
                .execSafeResult("cmd", "power", "set-fixed-performance-mode-enabled", "true")
                .isSuccess
            if (!fixedPerfOk) unavailable += context.getString(R.string.gt_eng_un_fixed)

            // Qualcomm's vendor GPU mode. The payload is the reference daemon's whole job (see
            // [GamingModeReport.gpuPerformanceMode] for the lineage); gated on the property
            // existing at all, so non-Qualcomm silicon is "not applicable" rather than a refused
            // switch it never had.
            val gpuPerfOk = applyVendorGpuPerformance()
            if (gpuPerfOk == false) {
                unavailable += context.getString(R.string.gt_eng_un_gpu)
            }

            // Qualcomm's game FPS hint, from the same reference's boot script (see
            // [GamingModeReport.qtiGameFps] for the lineage and the persist-twin decision).
            val qtiFpsOk = applyQtiGameFpsHint(maxHz)
            if (qtiFpsOk == false) {
                unavailable += context.getString(R.string.gt_eng_un_qti)
            }

            // The two developer options, applied through the same verified helpers the Developer
            // Options card uses — so the switches there and the state here can never disagree.
            _state.value = GamingModeState.Enabling(0.92f, context.getString(R.string.gt_eng_dev))
            val discardOk = toggleAlwaysFinishActivities(true)
            if (!discardOk) {
                unavailable += context.getString(R.string.gt_eng_un_discard)
            }
            val processLimitOk = toggleBackgroundProcessLimit(true)
            if (!processLimitOk) {
                unavailable += context.getString(R.string.gt_eng_un_limit)
            }

            // Metered-background data. Left entirely alone when the user's own switch was already on:
            // engaging it again would make deactivation turn off a restriction we did not own.
            _state.value = GamingModeState.Enabling(0.95f, context.getString(R.string.gt_eng_data))
            var backgroundDataRestricted: Boolean? = null
            if (restrictedBefore) {
                unavailable += context.getString(R.string.gt_eng_un_data_on)
            } else {
                val outcome = runCatching {
                    backgroundDataRestrictor.enable(listOfNotNull(packageName))
                }.getOrNull()
                backgroundDataRestricted = outcome?.success == true
                if (outcome == null) {
                    unavailable += context.getString(R.string.gt_eng_un_data_silent)
                } else if (!outcome.success) {
                    unavailable += context.getString(R.string.gt_eng_un_data_fail, outcome.message)
                }
            }

            var networkWhitelisted: Boolean? = null
            var gameInterventionApplied: Boolean? = null
            if (packageName != null) {
                execute("pm unsuspend --user 0 $packageName")
                execute("cmd activity set-bg-restriction-level --user 0 $packageName unrestricted")
                execute("am set-standby-bucket --user 0 $packageName active")
                execute("cmd deviceidle whitelist +$packageName")
                networkWhitelisted = applyPerGameOptimizations(packageName, maxHz)

                // The game's own frame-cap table, raised to the panel's peak. Skipped whole on
                // Android 11 and older, where game interventions do not exist — that is "not
                // applicable", reported as null, not a refusal.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val outcome = gameInterventions.apply(packageName, maxHz)
                    gameInterventionApplied = outcome.applied
                    if (!outcome.applied) {
                        unavailable += context.getString(
                            R.string.gt_eng_un_framecap,
                            outcome.refusal?.let { context.getString(R.string.gt_eng_un_framecap_no, it) }
                                ?: context.getString(R.string.gt_eng_un_framecap_kept)
                        )
                    }
                }
            }
            execute("cmd deviceidle force-idle")
            execute("am kill-all")

            prefs.edit {
                putBoolean("is_active", true)
                // Fixed performance mode and `cmd game set` can leave the thermal service in an
                // overridden state after the mode is off (some vendor HALs keep the override
                // until it is explicitly cleared). Only those two paths set this flag, so the
                // deactivation-side reset runs exactly when there is something to undo.
                if (fixedPerfOk || networkWhitelisted == true) {
                    putBoolean("thermal_recovery_needed", true)
                }
            }
            _isFixedPerformanceMode.value = fixedPerfOk
            _report.value = GamingModeReport(
                fixedPerformance = fixedPerfOk,
                gpuPerformanceMode = gpuPerfOk,
                qtiGameFps = qtiFpsOk,
                lockedRefreshHz = lockedHz,
                touchResponseBoost = touchOk,
                suspendedPackages = suspendedNow,
                suspendFailures = suspendFailures,
                dndEngaged = dndEngaged,
                networkWhitelisted = networkWhitelisted,
                discardActivities = discardOk,
                processLimit = processLimitOk,
                backgroundDataRestricted = backgroundDataRestricted,
                gameInterventionApplied = gameInterventionApplied,
                // false means "not granted", which the report renders as absent rather than
                // refused — the user has simply not switched this layer on.
                notificationSuppression = if (notificationSuppression) true else null,
                unavailable = unavailable
            )
            _state.value = GamingModeState.Active
        } catch (e: Exception) {
            _state.value = GamingModeState.Error(e.message ?: context.getString(R.string.gt_eng_activation_fail))
        }
    }

    /**
     * Waits, briefly, for the notification interruption filter to actually reach [wanted].
     *
     * `setInterruptionFilter` returns as soon as NotificationManagerService has the request; the zen
     * state it changes is published a moment later. Reading `currentInterruptionFilter` on the very
     * next line therefore reports the *previous* filter often enough to look intermittent, which is
     * exactly how "DND sometimes fails" presented. Six 50 ms checks cover that gap without turning a
     * genuinely refused filter into a long stall.
     *
     * @return whether the filter is [wanted] now. A false here is a real failure, not a race.
     */
    private suspend fun awaitInterruptionFilter(
        nm: NotificationManager,
        wanted: Int,
        attempts: Int = 6
    ): Boolean {
        repeat(attempts) {
            if (runCatching { nm.currentInterruptionFilter }.getOrNull() == wanted) return true
            delay(50.milliseconds)
        }
        return runCatching { nm.currentInterruptionFilter }.getOrNull() == wanted
    }

    private suspend fun disableGamingMode() {
        _state.value = GamingModeState.Disabling
        // First, before any lever is reverted: the moment suppression stops, notifications
        // arrive normally again, and a device busy reporting its own restoration would have
        // its reports eaten by the layer this mode switched on.
        _notificationSuppressionActive.value = false
        // Anything gathered here survives into the report even when a later step throws, so a
        // half-finished revert still says what it did not get to.
        val revertProblems = mutableListOf<String>()
        try {
            // Every unsuspend is verified by what the shell answered — the same standard
            // activation holds suspends to. A package that refused to wake stays in the record
            // instead of being deleted from it, so the next deactivation retries exactly the
            // leftovers rather than forgetting frozen apps. Dropping the whole set up front is
            // what made one refused unsuspend a permanently suspended app with no trace.
            //
            // The sweep covers the whole suspend-target universe, not just the record: sessions
            // reverted by older builds deleted their record before verifying, so their leftovers
            // have no entry in `affected_pkgs`. Waking an already-awake package is a confirmed
            // no-op, which is what makes this blind sweep safe — it can only wake our own past
            // leaks. `pm` takes a single package per call, so each chunk is N commands chained
            // with `;` in one fork (the same batching BackgroundDataRestrictor uses for
            // netpolicy); a refused chunk falls back to per-package isolation rather than
            // retaining the whole chunk as still suspended.
            val affected = prefs.getStringSet("affected_pkgs", emptySet()) ?: emptySet()
            val stillSuspended = mutableSetOf<String>()
            for (chunk in (affected + getSuspendTargets(null)).distinct().chunked(UNSUSPEND_BATCH_SIZE)) {
                if (chunk.size == 1) {
                    if (!unsuspendOne(chunk[0])) stillSuspended.add(chunk[0])
                    continue
                }
                val chained = chunk.joinToString("; ") { "pm unsuspend --user 0 $it" }
                val batchResult = runCatching { shellRunner.execResult(chained) }.getOrNull()
                if (batchResult != null &&
                    batchResult.isSuccess &&
                    SuspendVerdict.isUnsuspendConfirmed(batchResult.exitCode, batchResult.stdout)
                ) continue
                for (pkg in chunk) {
                    if (!unsuspendOne(pkg)) stillSuspended.add(pkg)
                }
            }
            if (stillSuspended.isEmpty()) {
                prefs.edit { remove("affected_pkgs") }
            } else {
                prefs.edit { putStringSet("affected_pkgs", stillSuspended) }
                revertProblems +=
                    context.getString(R.string.gt_eng_still_suspended, stillSuspended.size)
            }
            execute("cmd deviceidle unforce")
            execute("cmd power set-fixed-performance-mode-enabled false")
            // Do Not Disturb is restored inside revertFromSnapshot, from the filter that was recorded
            // before activation — not to INTERRUPTION_FILTER_ALL, which would cancel a DND the user set.
            revertProblems += revertFromSnapshot()
            val thermalRefusal = if (!recoverThermalOverrideIfNeeded()) {
                context.getString(R.string.gt_eng_thermal)
            } else {
                null
            }
            // Deactivation normally clears the report, but a refused step is the device's own
            // refusal text and belongs in front of the user rather than in a silent state change.
            _report.value = GamingModeReport(unavailable = revertProblems + listOfNotNull(thermalRefusal))
        } catch (e: Exception) {
            revertProblems += context.getString(R.string.gt_eng_revert_fail, e.message ?: e.javaClass.simpleName)
            _report.value = GamingModeReport(unavailable = revertProblems)
        } finally {
            prefs.edit { putBoolean("is_active", false); putBoolean("fixed_perf_manual", false) }
            _isFixedPerformanceMode.value = false
            _state.value = GamingModeState.Idle
        }
    }

    /**
     * Clears any thermal override the session may have left behind.
     *
     * Fixed performance mode and the GameManager game mode (`cmd game set`) can leave the
     * thermal service holding an override after every other lever is reverted — on some vendor
     * HALs the override stays engaged until it is explicitly cleared, which presents as the
     * phone running hot long after Gaming Mode is off. Activation records that one of those
     * paths was accepted ([thermal_recovery_needed]); this runs once, on deactivation, and
     * clears the flag only when `cmd thermalservice reset` actually answered with success.
     *
     * Modelled on the reference project's `EsportsOptimizationEngine.recoverThermalOverrideIfNeeded`
     * (`referance/gamingtools/booster`): guarded by a needs-recovery flag rather than run
     * unconditionally, judged by the exit code, and idempotent — a reset that never became
     * needed is a success, not a skipped failure.
     *
     * @return false when a reset was needed and the device refused it.
     */
    private suspend fun recoverThermalOverrideIfNeeded(): Boolean {
        if (!prefs.getBoolean("thermal_recovery_needed", false)) return true
        val result = runCatching {
            shellRunner.execSafeResult("cmd", "thermalservice", "reset")
        }.getOrNull() ?: return false
        if (!result.isSuccess) return false
        prefs.edit { remove("thermal_recovery_needed") }
        return true
    }

    /**
     * Result of a manual RAM boost.
     *
     * @param freedMb megabytes of available memory gained, measured before and after. null when the
     *   device would not let us read the memory figures at all — the UI must say "unknown" rather
     *   than print a zero that looks like a measurement.
     * @param stoppedCount packages `am force-stop` actually accepted.
     * @param attemptedCount packages it was asked to stop.
     */
    data class RamBoostResult(
        val freedMb: Long?,
        val stoppedCount: Int,
        val attemptedCount: Int
    )

    /**
     * Frees background memory and measures the result.
     *
     * The "before" reading is taken *first*, before any cache trimming, so the number covers the
     * whole operation. `MemAvailable` from /proc/meminfo is the kernel's own estimate of what can be
     * handed out without swapping — the same source the reference RamMonitor parses — with
     * [android.app.ActivityManager.MemoryInfo] as the fallback when the file cannot be read.
     */
    suspend fun manualBoostRam(): RamBoostResult {
        val availBefore = readAvailableMemoryBytes()
        var stoppedCount = 0
        var attempted = 0
        try {
            execute("pm trim-caches 4G")
            execute("am compact background")
            val targets = getSuspendTargets(null)
            attempted = targets.size
            for (pkg in targets) {
                // execute() never throws for a command that merely failed, so counting attempts
                // would report apps that were never stopped. Only a zero exit counts.
                if (shellRunner.execSafeResult("am", "force-stop", pkg).isSuccess) stoppedCount++
            }
            execute("am kill-all")
        } catch (_: Exception) {}
        // The kills are asynchronous; give the kernel a moment to reclaim before measuring again.
        delay(500.milliseconds)
        val availAfter = readAvailableMemoryBytes()
        val freedMb = if (availBefore != null && availAfter != null) {
            ((availAfter - availBefore) / (1024L * 1024L)).coerceAtLeast(0L)
        } else {
            null
        }
        return RamBoostResult(freedMb = freedMb, stoppedCount = stoppedCount, attemptedCount = attempted)
    }

    /** @return available bytes, or null when neither /proc/meminfo nor ActivityManager could answer. */
    private fun readAvailableMemoryBytes(): Long? {
        parseMemAvailableBytes()?.let { return it }
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val info = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            info.availMem
        } catch (_: Exception) {
            null
        }
    }

    private fun parseMemAvailableBytes(): Long? = try {
        java.io.File("/proc/meminfo").useLines { lines ->
            lines.firstOrNull { it.startsWith("MemAvailable:") }
                ?.split(WHITESPACE)
                ?.getOrNull(1)
                ?.toLongOrNull()
                ?.times(1024L)
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Turns Android's fixed-performance mode on or off, and says whether the device accepted it.
     *
     * `cmd power set-fixed-performance-mode-enabled` reaches PowerManagerService, which calls
     * `PowerHAL.setMode(MODE_FIXED_PERFORMANCE)`. That mode asks the SoC vendor's power HAL to hold
     * clocks at a fixed operating point instead of letting the governor scale them with load — the
     * point of it is *consistency*, not peak speed, and on many HALs the fixed point is deliberately
     * below the boost ceiling to keep the device thermally sustainable.
     *
     * Two real limits, both reported rather than hidden:
     * - The command was added in Android 11. On older builds `cmd power` does not know it.
     * - `MODE_FIXED_PERFORMANCE` is optional for vendors. A HAL that does not implement it accepts the
     *   call and does nothing, so a success here means "the framework accepted the request", which is
     *   as much as any caller — including Android's own CTS — can determine.
     */
    suspend fun toggleFixedPerformanceMode(enabled: Boolean): FixedPerformanceOutcome {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return FixedPerformanceOutcome(
                accepted = false,
                message = context.getString(R.string.gt_eng_fp_old, Build.VERSION.RELEASE)
            )
        }
        if (!shellRunner.hasPrivilege()) {
            return FixedPerformanceOutcome(
                accepted = false,
                message = context.getString(R.string.gt_needs_root_shizuku_change)
            )
        }

        val result = shellRunner.execSafeResult(
            "cmd", "power", "set-fixed-performance-mode-enabled", if (enabled) "true" else "false"
        )
        // `cmd power` prints its complaint and returns non-zero when it does not recognise the
        // sub-command, so the exit code is a real answer here rather than the always-0 that
        // `settings put` gives.
        val accepted = result.isSuccess &&
            !result.stderr.contains("Unknown", ignoreCase = true) &&
            !result.stdout.contains("Unknown", ignoreCase = true)

        if (accepted && enabled) {
            // Housekeeping that pairs with the mode rather than being part of it: drop the memory
            // background apps are sitting on, and pin the framework so it is not paged back in
            // mid-frame. Neither is required for the mode to work.
            execute("am compact background")
            execute("cmd pinner repin /system/framework/framework.jar")
        } else if (accepted) {
            execute("cmd deviceidle unforce")
        }

        val state = accepted && enabled
        prefs.edit { putBoolean("fixed_perf_manual", state) }
        _isFixedPerformanceMode.value = state

        return FixedPerformanceOutcome(
            accepted = accepted,
            message = if (accepted) {
                if (enabled) {
                    context.getString(R.string.gt_eng_fp_ok)
                } else {
                    context.getString(R.string.gt_eng_fp_off)
                }
            } else {
                context.getString(
                    R.string.gt_eng_fp_no,
                    result.text.trim().ifBlank { context.getString(R.string.gt_eng_fp_no_reason) }
                )
            }
        )
    }

    /** @return true when the setting actually holds the requested value afterwards. */
    suspend fun toggleAlwaysFinishActivities(enabled: Boolean): Boolean {
        val target = if (enabled) 1 else 0
        execute("settings put global always_finish_activities $target")
        // `settings put` is silent on success, so confirm by reading the value back.
        val applied = getGlobalInt(Settings.Global.ALWAYS_FINISH_ACTIVITIES) == target
        _alwaysFinishActivities.value = applied && enabled
        return applied
    }

    /**
     * Merges (or removes) `max_cached_processes` inside `activity_manager_constants` instead of
     * replacing the whole CSV, so any other constants the ROM set survive.
     * @return true when the change is visible in settings afterwards.
     */
    suspend fun toggleBackgroundProcessLimit(enabled: Boolean): Boolean {
        val current = getGlobalString("activity_manager_constants").orEmpty()
        val merged = if (enabled) {
            upsertCsvKey(current, "max_cached_processes", "1")
        } else {
            removeCsvKey(current, "max_cached_processes")
        }
        if (merged.isBlank()) {
            execute("settings delete global activity_manager_constants")
        } else {
            execute("settings put global activity_manager_constants $merged")
        }
        val readBack = getGlobalString("activity_manager_constants").orEmpty()
        val applied = readBack.contains("max_cached_processes=1") == enabled
        _backgroundProcessLimit.value = applied && enabled
        return applied
    }

    // The standalone refresh-rate control lives in GameDeveloperOptions, which implements Android's
    // own "Force peak refresh rate" switch (min_refresh_rate as a float, verified by read-back).
    // Gaming mode still writes both min and peak itself, and still restores both from its snapshot.

    /**
     * Compiles every user app with `cmd package compile` — the same command Android's own `pm
     * compile` front-end runs, so this is real ART dexopt rather than an imitation of it.
     *
     * Follows the reference project's optimisation run: a privilege check before anything is
     * claimed, its own package left out so a forced recompile cannot kill the process doing the
     * compiling, a cancellation flag checked between packages, and a completion that can never
     * overwrite a cancellation. Each package is counted from what the shell actually answered, so
     * the closing summary is a tally of real results and not of attempts made.
     */
    suspend fun runArtOptimization(mode: String = "speed-profile", force: Boolean = false) {
        if (!boosterActive.compareAndSet(false, true)) {
            addBoosterLog(context.getString(R.string.gt_eng_booster_running))
            return
        }
        try {
            _boosterLog.value = emptyList()
            boosterCancelRequested.set(false)
            activeBoosterMode = mode
            activeBoosterStartedAt = System.currentTimeMillis()
            _boosterState.value = BoosterState(isRunning = true, outcome = BoosterOutcome.Running)
            addBoosterLog(
                if (force) context.getString(R.string.gt_eng_booster_start_force)
                else context.getString(R.string.gt_eng_booster_start)
            )

            // `cmd package compile` is refused for other packages without a privileged shell.
            // Checking first means the log says why nothing happened, instead of listing apps that
            // were never touched and then reporting a success that never took place.
            if (!shellRunner.hasPrivilege()) {
                val reason = context.getString(R.string.gt_eng_booster_no_priv)
                fail(BoosterOutcome.Unavailable(reason), context.getString(R.string.gt_eng_booster_unavail, reason))
                return
            }

            val apps = eligibleBoosterPackages(mode, force)
            if (apps.isEmpty()) {
                val reason = context.getString(R.string.gt_eng_booster_no_apps)
                fail(BoosterOutcome.Unavailable(reason), context.getString(R.string.gt_eng_booster_unavail, reason))
                return
            }
            addBoosterLog(context.getString(R.string.gt_eng_booster_found, apps.size))
            _boosterState.update { it.copy(totalCount = apps.size) }

            val useRoot = shellRunner.isRootAvailable()
            addBoosterLog(
                if (useRoot) context.getString(R.string.gt_eng_booster_root)
                else context.getString(R.string.gt_eng_booster_shizuku)
            )

            // Without -f the platform skips an app that is already in the requested filter, so ask
            // it up front what each app is compiled with and say which ones are being skipped.
            val currentStatuses = if (force) emptyMap() else queryDexoptStatuses()

            for (pkg in apps) {
                if (boosterCancelRequested.get()) return

                if (!force && currentStatuses[pkg] == mode) {
                    addBoosterLog(context.getString(R.string.gt_eng_booster_skip_done, pkg))
                    _boosterState.update { it.copy(currentPackage = null, skippedCount = it.skippedCount + 1) }
                    continue
                }

                // A `verify` status means the app has never been opened, so no runtime profile
                // exists yet and a speed-profile compile has nothing to work with — the platform's
                // own first-use dexopt will do the same job. "Nothing to do yet", not "already
                // done" and not a failure; forced runs compile them anyway.
                if (!force && mode == "speed-profile" && currentStatuses[pkg] == "verify") {
                    addBoosterLog(context.getString(R.string.gt_eng_booster_skip_verify, pkg))
                    _boosterState.update { it.copy(currentPackage = null, skippedCount = it.skippedCount + 1) }
                    continue
                }

                _boosterState.update { it.copy(currentPackage = pkg) }
                addBoosterLog(context.getString(R.string.gt_eng_booster_working, pkg))
                val outcome = runCompileCommand(mode, force, pkg, useRoot)

                // A cancel that landed mid-compile: the partial result is not a failure of this app.
                if (boosterCancelRequested.get()) return

                if (outcome.succeeded) {
                    addBoosterLog(
                        context.getString(
                            R.string.gt_eng_booster_ok,
                            pkg,
                            outcome.detail.takeIf { it.isNotBlank() }
                                ?.let { context.getString(R.string.gt_eng_booster_ok_detail, it) }
                                .orEmpty()
                        )
                    )
                    _boosterState.update { it.copy(currentPackage = null, optimizedCount = it.optimizedCount + 1) }
                } else {
                    addBoosterLog(context.getString(R.string.gt_eng_booster_fail, pkg, outcome.detail))
                    _boosterState.update { it.copy(currentPackage = null, failedCount = it.failedCount + 1) }
                }
            }

            // The reference's own guard: a cancellation that arrived during the last compile must
            // not be reported as a completed sweep.
            if (boosterCancelRequested.get()) return

            val finished = _boosterState.value
            addBoosterLog(
                context.getString(
                    R.string.gt_eng_booster_done,
                    finished.optimizedCount,
                    finished.skippedCount,
                    finished.failedCount
                )
            )
            _boosterState.value = finished.copy(
                isRunning = false,
                currentPackage = null,
                outcome = BoosterOutcome.Completed
            )
            recordBoosterRun("completed")
        } catch (e: CancellationException) {
            markBoosterCancelled()
            throw e
        } catch (e: Exception) {
            val reason = e.message ?: e.javaClass.simpleName
            fail(
                BoosterOutcome.Failed(reason),
                context.getString(R.string.gt_eng_booster_failed, reason)
            )
        } finally {
            // Whatever happened, nothing is left compiling and a new run can start.
            if (boosterCancelRequested.get()) markBoosterCancelled()
            currentCompileProcess?.destroy()
            currentCompileProcess = null
            boosterActive.set(false)
            activeBoosterMode = null
        }
    }

    /**
     * Appends the finished run to the persisted history and the live flow, from the counts the
     * state holds right now. Not a read-back of anything — the counts were accumulated from
     * what the shell answered, which is the same standard a report row has to meet.
     */
    private fun recordBoosterRun(outcome: String) {
        val mode = activeBoosterMode ?: return
        val startedAt = activeBoosterStartedAt
        if (startedAt <= 0L) return
        val s = _boosterState.value
        val run = BoosterRun(
            startedAt = startedAt,
            durationMs = System.currentTimeMillis() - startedAt,
            mode = mode,
            optimized = s.optimizedCount,
            skipped = s.skippedCount,
            failed = s.failedCount,
            outcome = outcome
        )
        boosterHistoryStore.record(run)
        _boosterHistory.update { it + run }
    }

    /** Ends the run with a stated reason instead of a silent stop. */
    private fun fail(outcome: BoosterOutcome, logLine: String) {
        addBoosterLog(logLine)
        _boosterState.update { it.copy(isRunning = false, currentPackage = null, outcome = outcome) }
        // Only a run that actually started something is worth a history entry: Unavailable means
        // the sweep never began, and recording it would bury the runs that did.
        if (outcome is BoosterOutcome.Failed) recordBoosterRun("failed")
    }

    /**
     * The apps worth compiling: everything the user installed, plus any system app they added to
     * their own game library — with the two classes that can only waste a compile slot screened
     * out by [BoosterPackageClassifier]: overlay/RRO packages (no meaningful dex; the platform
     * refuses or no-ops them) and, for a `speed-profile` run, never-opened apps at status `verify`
     * (no runtime profile yet, so a profile-guided compile has nothing to work with). Both are
     * counted as [BoosterState.skippedCount] with their own log line — "nothing to do" is not
     * "already done" and not a failure — and neither is filtered in a forced run, where the user
     * explicitly asked for every package.
     */
    private fun eligibleBoosterPackages(mode: String = "speed-profile", force: Boolean = false): List<String> {
        val userAdded = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            .getStringSet("user_games", emptySet()) ?: emptySet()
        return context.packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            .asSequence()
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || it.packageName in userAdded }
            .map { it.packageName to it }
            // Recompiling ourselves restarts this process and takes the sweep down with it. The
            // reference excludes its own package for the same reason.
            .filter { (pkg, _) -> pkg != context.packageName }
            .filter { (pkg, info) ->
                // The classifier reads the APK's own path — no shell call, no per-app fork. In a
                // forced run every package the user asked for goes through, overlays included.
                force || !BoosterPackageClassifier.isOverlayLike(pkg, info.sourceDir)
            }
            .map { (pkg, _) -> pkg }
            .distinct()
            .toList()
            // The verify check needs the statuses, which come from one `dumpsys package dexopt`
            // call the caller already makes; keep the filter there (see runArtOptimization).
    }

    /**
     * Records a cancellation without discarding the counts: the run stopped where it stopped, and
     * the UI reports how far it got rather than resetting as if it had never started.
     */
    private fun markBoosterCancelled() {
        val current = _boosterState.value
        // Only a run still in progress can be cancelled. Once it has reported how it ended —
        // completed, unavailable, failed, or already cancelled — that stands: a Stop that arrives
        // in the moment after the last package must not rewrite a finished sweep as a cancelled one.
        if (current.outcome !is BoosterOutcome.Running) return
        addBoosterLog(context.getString(R.string.gt_eng_booster_stopped, current.processedCount))
        _boosterState.value = current.copy(
            isRunning = false,
            currentPackage = null,
            outcome = BoosterOutcome.Cancelled
        )
        // "Cancelled after 12" is a different fact about the sweep than "completed", and the
        // counts of how far it got are exactly what the history row is for.
        recordBoosterRun("cancelled")
    }

    private suspend fun queryDexoptStatuses(): Map<String, String> {
        return withContext(Dispatchers.IO) {
            DexoptStatusParser.parse(execute("dumpsys package dexopt"))
        }
    }

    /** What `cmd package compile` actually reported for one package. */
    private data class CompileOutcome(val succeeded: Boolean, val detail: String)

    /**
     * Compiles one package and reports what the platform said about it.
     *
     * The root path spawns `su` directly instead of going through [ShellRunner] because cancelling
     * has to be able to `destroy()` this exact process: both libsu and the Shizuku binder call
     * block until the compile returns, and a dexopt of a large app is not quick.
     */
    private suspend fun runCompileCommand(
        mode: String,
        force: Boolean,
        pkg: String,
        useRoot: Boolean
    ): CompileOutcome = withContext(Dispatchers.IO) {
        val args = buildList {
            add("cmd"); add("package"); add("compile"); add("-m"); add(mode)
            if (force) add("-f")
            add(pkg)
        }
        if (!useRoot) {
            val result = shellRunner.execSafeResult(*args.toTypedArray())
            return@withContext classifyCompileOutput(result.exitCode, result.text)
        }
        try {
            val process = ProcessBuilder("su", "-c", args.joinToString(" "))
                .redirectErrorStream(true)
                .start()
            currentCompileProcess = process
            // Drained on this thread: an unread pipe fills up and stalls the compiler, and the
            // output is also the only place the platform says whether it accepted the request.
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exit = process.waitFor()
            currentCompileProcess = null
            classifyCompileOutput(exit, output)
        } catch (e: Exception) {
            currentCompileProcess = null
            CompileOutcome(false, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Decides whether one compile was accepted, from the exit code and the shell's own words.
     *
     * `cmd package compile` prints its verdict — `Success`, or a `Failure`/`Error:` line — and some
     * builds still exit 0 after refusing, so both signals are read. A silent exit 0 is taken at
     * face value; inventing a failure there would be as wrong as inventing a success.
     */
    private fun classifyCompileOutput(exitCode: Int, output: String): CompileOutcome {
        val text = output.trim()
        val lower = text.lowercase(java.util.Locale.US)
        return when {
            exitCode != 0 -> CompileOutcome(false, text.ifBlank { context.getString(R.string.gt_eng_booster_exit, exitCode) })
            lower.startsWith("failure") || lower.contains("error:") -> CompileOutcome(false, text)
            else -> CompileOutcome(true, text.takeUnless { it.equals("Success", ignoreCase = true) }.orEmpty())
        }
    }

    /**
     * User-initiated stop. Says plainly when there was nothing to stop, matching the reference's
     * own "No optimization is currently running." response.
     */
    suspend fun cancelArtOptimization() {
        if (!requestBoosterCancel()) {
            addBoosterLog(context.getString(R.string.gt_eng_booster_idle))
            return
        }
        // The platform forks dex2oat on our behalf, and it outlives the shell that asked for it.
        shellRunner.killCurrentProcess()
    }

    /**
     * Stop from a component that is being torn down.
     *
     * The flag and the `destroy()` run on the calling thread, so the sweep is unblocked before this
     * returns even though the caller's scope is about to die; the `pkill` needs a coroutine and
     * goes to the engine's own scope, which outlives the service.
     *
     * @return true when a run was actually in progress.
     */
    fun cancelArtOptimizationNow(): Boolean {
        if (!requestBoosterCancel()) return false
        scope.launch { shellRunner.killCurrentProcess() }
        return true
    }

    /**
     * Raises the cancellation flag and unblocks the compile in flight.
     *
     * @return false when no sweep was running, so callers can say so rather than report a stop
     *   that stopped nothing.
     */
    private fun requestBoosterCancel(): Boolean {
        if (!boosterActive.get()) return false
        boosterCancelRequested.set(true)
        // Ends the blocking read and waitFor in runCompileCommand, so the loop reaches its next
        // cancellation check instead of waiting out the current dexopt.
        currentCompileProcess?.destroy()
        currentCompileProcess = null
        markBoosterCancelled()
        return true
    }

    /**
     * Reads the three animation scales out of `Settings.Global`.
     *
     * The `1f` fallbacks are the platform's own defaults for these keys — an unset key genuinely
     * means "normal speed" — so this is the real effective value, not a placeholder. Same getters
     * the reference project uses in `SettingsManager.getWindowAnimationScale` and friends.
     */
    fun refreshAnimationScales() {
        val cr = context.contentResolver
        val w = Settings.Global.getFloat(cr, Settings.Global.WINDOW_ANIMATION_SCALE, 1f)
        val t = Settings.Global.getFloat(cr, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f)
        val a = Settings.Global.getFloat(cr, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        _animationScales.value = Triple(w, t, a)
    }

    /**
     * True when this app has a channel that can write `Settings.Global`.
     *
     * Root and Shizuku both run `settings put`; a plain install cannot, unless `WRITE_SECURE_SETTINGS`
     * was granted over adb, which is the reference project's own fallback path. Anything else and the
     * write will be refused, so the UI is told up front rather than after a silent failure.
     */
    fun canWriteAnimationScales(): Boolean =
        shellRunner.hasPrivilege() || hasWriteSecureSettings()

    private fun hasWriteSecureSettings(): Boolean =
        context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Writes one animation scale and confirms it by reading the value back.
     *
     * Both routes are the reference project's (`SettingsManager.setGlobalFloat`): the privileged
     * shell first, then a direct `Settings.Global.putFloat` for a build where `WRITE_SECURE_SETTINGS`
     * has been granted. `settings put` exits 0 without checking that the key was accepted, so the
     * read-back — not the exit code — decides what this reports.
     *
     * @return true only when the setting now actually holds [value].
     */
    suspend fun setAnimationScale(scale: AnimationScaleKind, value: Float): Boolean =
        withContext(Dispatchers.IO) {
            // Two decimals, matching the reference, so 0.5 is written as "0.50" rather than in
            // whatever form Float.toString() happens to pick.
            val formatted = String.format(java.util.Locale.US, "%.2f", value)

            if (shellRunner.hasPrivilege()) {
                shellRunner.execSafeResult("settings", "put", "global", scale.key, formatted)
            }
            if (!readsBack(scale, value) && hasWriteSecureSettings()) {
                runCatching { Settings.Global.putFloat(context.contentResolver, scale.key, value) }
            }

            val applied = readsBack(scale, value)
            refreshAnimationScales()
            applied
        }

    /** Compares numerically: a ROM may store "0.5" for a written "0.50", and both mean the same. */
    private fun readsBack(scale: AnimationScaleKind, value: Float): Boolean {
        val current = runCatching {
            Settings.Global.getFloat(context.contentResolver, scale.key, Float.NaN)
        }.getOrDefault(Float.NaN)
        return !current.isNaN() && kotlin.math.abs(current - value) < 0.005f
    }

    /**
     * Appends one line to the booster log.
     *
     * [MutableStateFlow.update] rather than a read-then-write, because the sweep logs from the IO
     * dispatcher while a stop can come in from the main thread, and a lost line would be a step
     * that happened with nothing to show it.
     */
    private fun addBoosterLog(msg: String) {
        _boosterLog.update { it + msg }
    }

    suspend fun execute(command: String): String = shellRunner.exec(command)

    suspend fun executeSafe(vararg args: String): String = shellRunner.execSafe(*args)

    private fun getSuspendTargets(activeGamePkg: String?): List<String> {
        val pm = context.packageManager
        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val appPrefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val userGames = appPrefs.getStringSet("user_games", emptySet()) ?: emptySet()
        val removedGames = appPrefs.getStringSet("removed_games", emptySet()) ?: emptySet()
        val launcherPkgs = getLauncherPackages()
        val userApps = installedApps.filter { ai ->
            val pkg = ai.packageName
            val isLibraryGame = (ai.category == ApplicationInfo.CATEGORY_GAME || pkg in userGames) && pkg !in removedGames
            val isWhitelisted = pkg == activeGamePkg || isLibraryGame || pkg in hardWhitelist ||
                pkg in systemCritical || pkg in gamingDaemons || pkg in launcherPkgs
            (ai.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && !isWhitelisted
        }.map { it.packageName }
        val googleApps = googleSafeToSuspend.filter { pkg ->
            val isInLibrary = pkg in userGames || try {
                pm.getApplicationInfo(pkg, 0).category == ApplicationInfo.CATEGORY_GAME && pkg !in removedGames
            } catch (_: Exception) { false }
            pkg != activeGamePkg && pkg !in launcherPkgs && !isInLibrary &&
                installedApps.any { it.packageName == pkg }
        }
        // OEM bloat is FLAG_SYSTEM, so the user-app sweep above can never reach it — this list is
        // the only path a preinstalled package has into the sweep. Curated per vendor in
        // [OemPackageResolver] (the reference's OemPackageResolver.VIVO_SAFE_TO_SUSPEND, verbatim),
        // and already disjoint from [systemCritical]/[gamingDaemons], whose load-bearing vivo
        // processes must stay out.
        val oemApps = OemPackageResolver.packagesToSuspend(
            isVivoOrIqoo = isVivoOrIqoo(),
            isInstalled = { pkg -> installedApps.any { it.packageName == pkg } }
        ).filter { pkg ->
            pkg != activeGamePkg && pkg !in launcherPkgs &&
                pkg !in userGames &&
                try {
                    pm.getApplicationInfo(pkg, 0).category != ApplicationInfo.CATEGORY_GAME
                } catch (_: Exception) { true }
        }
        return (userApps + googleApps + oemApps).distinct()
    }

    private fun getLauncherPackages(): Set<String> {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_HOME)
        return context.packageManager.queryIntentActivities(intent, 0)
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()
    }

    /**
     * Reads the user's real state and stores it, before activation writes anything at all.
     *
     * Called as the very first step of [enableGamingMode] — ahead of the cache trim, the suspends and
     * every `settings put` — so what lands here is the user's own configuration rather than a value
     * this app had already changed. A second activation over an active one would otherwise capture
     * the optimized values as if they were the originals, and deactivation would "restore" those.
     */
    private suspend fun captureAndSaveSnapshot(packageName: String?): Boolean {
        val minRefresh = readSettingOrNull("system", "min_refresh_rate") ?: return false
        val peakRefresh = readSettingOrNull("system", "peak_refresh_rate") ?: return false
        val touchSpeed = readSettingOrNull("system", "touch_response_speed") ?: return false
        val displayMode = readSettingOrNull("secure", "user_preferred_display_mode_id") ?: return false
        // The two developer options Gaming Mode now also sets. Both usually come back
        // `existed = false`, which is a real answer and tells the revert to delete rather than write.
        val alwaysFinish = readSettingOrNull("global", Settings.Global.ALWAYS_FINISH_ACTIVITIES)
        val amConstants = readSettingOrNull("global", "activity_manager_constants")
        // Whether the user's own Background Data Restriction switch was already on. Recorded before
        // activation touches netpolicy, so deactivation can tell "we engaged it" from "they did".
        val restrictedBefore = runCatching { backgroundDataRestrictor.isEngaged() }.getOrDefault(false)

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_RING)
        // The Do Not Disturb filter the user had. Read before activation forces
        // INTERRUPTION_FILTER_NONE, so a priority-only DND they set themselves comes back intact.
        val originalFilter = runCatching {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .currentInterruptionFilter
        }.getOrNull()
        
        val cr = context.contentResolver
        val origBrightnessMode = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
        val origRotation = Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 1)

        var uid: Int? = null
        var uidWhitelistedBefore = false
        if (packageName != null) {
            uid = runCatching { context.packageManager.getApplicationInfo(packageName, 0).uid }.getOrNull()
            uidWhitelistedBefore = (uid != null) && isUidWhitelisted(uid)
        }
        val isVivo = (packageName != null) && isVivoOrIqoo()
        val vivoCube = if (isVivo) getGlobalString(vivoGameCubeApps) else null
        val vivoSpeed = if (isVivo) getGlobalString(vivoSpeedModeApps) else null
        // The two high-refresh-rate whitelist CSVs the reference also writes. Same null contract
        // as the pair above: null when there is no game or the device is not vivo/iQOO, and a
        // blank string when the key simply was not set (meaning "delete it again on the way out").
        val vivoHighRefresh = if (isVivo) getGlobalString(vivoHighRefreshRateApps) else null
        val vivoScreenRefresh = if (isVivo) getGlobalString(vivoScreenRefreshRateAppsList) else null
        // The game's own intervention-table entry, read *before* activation writes one, so the
        // revert can put back what was there or delete ours if nothing was. Game interventions
        // exist from Android 12 only; an unreadable answer is stored as null, which the revert
        // reads as "leave the flag alone" rather than guessing at its prior shape.
        val gameOverlay = if (packageName != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when (val overlay = gameInterventions.readOverlay(packageName)) {
                is GameInterventions.OverlayValue.Set -> SettingValue(overlay.value, existed = true)
                is GameInterventions.OverlayValue.Unset -> SettingValue("", existed = false)
                is GameInterventions.OverlayValue.Unreadable -> null
            }
        } else null

        // Qualcomm's vendor GPU properties, as the vendor booted with them. A blank property (the
        // normal case on non-Qualcomm silicon) records existed = false, which both the
        // activation-side gate and the revert read as "never touch this property".
        val vendorGpuMode = propSettingValue(VENDOR_GPU_MODE)
        val vendorGfxLowQuality = propSettingValue(VENDOR_GFX_LOW_QUALITY)
        // The game FPS hint too. On a Qualcomm device that never set it this records
        // existed = false — and the revert then leaves our hint in place until a reboot clears
        // the debug property area, the one deletion Android offers. See the snapshot field's
        // KDoc; recorded either way so a prior value is never lost.
        val debugVendorQtiGameFps = propSettingValue(DEBUG_VENDOR_QTI_GAME_FPS)

        val snapshot = GamingOptimizationSnapshot(
            activeGamePackage = packageName,
            activeGameUid = uid,
            timestamp = System.currentTimeMillis(),
            minRefreshRate = minRefresh,
            peakRefreshRate = peakRefresh,
            touchResponseSpeed = touchSpeed,
            userPreferredDisplayModeId = displayMode,
            affectedPackages = emptySet(),
            uidWhitelistedBefore = uidWhitelistedBefore,
            vivoGameCubeApps = vivoCube,
            vivoSpeedModeApps = vivoSpeed,
            vivoHighRefreshRateApps = vivoHighRefresh,
            vivoScreenRefreshRateAppsList = vivoScreenRefresh,
            originalRingtoneVolume = currentVol,
            originalBrightnessMode = origBrightnessMode,
            originalRotation = origRotation,
            alwaysFinishActivities = alwaysFinish,
            activityManagerConstants = amConstants,
            backgroundDataRestrictedBefore = restrictedBefore,
            originalInterruptionFilter = originalFilter,
            gameOverlay = gameOverlay,
            vendorGpuMode = vendorGpuMode,
            vendorGfxLowQuality = vendorGfxLowQuality,
            debugVendorQtiGameFps = debugVendorQtiGameFps
        )
        prefs.edit { putString("last_snapshot", snapshot.toJson()) }
        return true
    }

    /**
     * Reads one setting for the pre-activation snapshot.
     *
     * The provider is asked first: reading `Settings.System`/`Secure`/`Global` needs no permission
     * at all, so it cannot fail for the reasons a shell can (no root, dead Shizuku binder) and it
     * costs no process fork. A key that is simply not set comes back as
     * `SettingValue("", existed = false)` — a legitimate answer, and the reason activation no
     * longer aborts on devices that never shipped `touch_response_speed`.
     *
     * @return null only when neither the provider nor a privileged shell could answer at all,
     *   which is the same "un-restorable state, do not touch anything" signal the reference uses.
     */
    private suspend fun readSettingOrNull(namespace: String, key: String): SettingValue? {
        readSettingViaProvider(namespace, key)?.let { return it }
        val result = shellRunner.execSafeResult("settings", "get", namespace, key)
        if (!result.isSuccess) return null
        return SettingValue.fromCommandOutput(result.stdout)
    }

    private fun readSettingViaProvider(namespace: String, key: String): SettingValue? {
        val cr = context.contentResolver
        return try {
            val raw = when (namespace) {
                "system" -> Settings.System.getString(cr, key)
                "secure" -> Settings.Secure.getString(cr, key)
                "global" -> Settings.Global.getString(cr, key)
                else -> return null
            }
            SettingValue.fromCommandOutput(raw.orEmpty())
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Writes a setting and confirms it stuck by reading it back.
     *
     * `settings put` exits 0 with empty stdout even on ROMs that accept the command and silently
     * drop an unknown key, so the read-back is the only honest success signal. Numbers are compared
     * numerically because some providers normalise `120` to `120.0`.
     */
    private suspend fun putSettingVerified(namespace: String, key: String, value: String): Boolean {
        shellRunner.execSafeResult("settings", "put", namespace, key, value)
        val readBack = readSettingOrNull(namespace, key)?.takeIf { it.existed }?.value ?: return false
        val actual = readBack.toFloatOrNull()
        val wanted = value.toFloatOrNull()
        return if (actual != null && wanted != null) actual == wanted else readBack == value
    }

    /** @return true when the game's uid was actually added to the background-data whitelist. */
    private suspend fun applyPerGameOptimizations(packageName: String, maxHz: Int): Boolean {
        try {
            val snapshot = prefs.getString("last_snapshot", null)
                ?.let { GamingOptimizationSnapshot.fromJson(it) }
            val uid = snapshot?.activeGameUid
                ?: runCatching { context.packageManager.getApplicationInfo(packageName, 0).uid }.getOrNull()
                ?: return false

            // Apply volume override (mute ringtone)
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            try { audioManager.setStreamVolume(AudioManager.STREAM_RING, 0, 0) } catch (_: Exception) {}

            // Apply Settings overrides if possible
            if (Settings.System.canWrite(context)) {
                val cr = context.contentResolver
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0)
            }

            execute("cmd netpolicy add restrict-background-whitelist $uid")
            execute("cmd game set --mode performance --fps $maxHz $packageName")
            if (isVivoOrIqoo()) {
                // All four whitelist CSVs the reference writes, not just the first two: the skin's
                // high-refresh-rate lists decide whether the panel holds its peak for the game, so
                // writing only the Game Cube pair left the game fast-pathed in one surface and an
                // ordinary app in the other two. Snapshot already recorded each key's prior value.
                for (key in listOf(vivoGameCubeApps, vivoSpeedModeApps, vivoHighRefreshRateApps, vivoScreenRefreshRateAppsList)) {
                    val current = getGlobalString(key) ?: ""
                    execute("settings put global $key ${appendToCsv(current, packageName)}")
                }
            }
            // netpolicy has no read-back on its own, but the whitelist can be listed.
            return isUidWhitelisted(uid)
        } catch (_: Exception) {
            return false
        }
    }

    /**
     * Restores everything the snapshot recorded, reporting what refused to go back.
     *
     * @return human-readable problems for the deactivation report; empty when everything landed.
     *   A missing or unparsable snapshot answers empty rather than accusing the device — there
     *   is no record to restore from, so there is nothing to verify against.
     */
    private suspend fun revertFromSnapshot(): List<String> {
        val json = prefs.getString("last_snapshot", null) ?: return emptyList()
        val snapshot = GamingOptimizationSnapshot.fromJson(json) ?: return emptyList()
        val problems = mutableListOf<String>()
        
        // Restore volume
        snapshot.originalRingtoneVolume?.let { vol ->
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            try { audioManager.setStreamVolume(AudioManager.STREAM_RING, vol, 0) } catch (_: Exception) {}
        }

        // Restore the Do Not Disturb filter the user actually had, which may itself have been a DND.
        snapshot.originalInterruptionFilter?.let { filter ->
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.isNotificationPolicyAccessGranted) {
                runCatching { nm.setInterruptionFilter(filter) }
            }
        }

        // Restore Settings
        if (Settings.System.canWrite(context)) {
            val cr = context.contentResolver
            snapshot.originalBrightnessMode?.let { Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, it) }
            snapshot.originalRotation?.let { Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, it) }
        }

        restoreSetting("system", "min_refresh_rate", snapshot.minRefreshRate)
        restoreSetting("system", "peak_refresh_rate", snapshot.peakRefreshRate)
        restoreSetting("system", "touch_response_speed", snapshot.touchResponseSpeed)
        restoreSetting("secure", "user_preferred_display_mode_id", snapshot.userPreferredDisplayModeId)

        // The two developer options, back to whatever they were — including "was not set at all",
        // which restoreSetting turns into a delete rather than a write of some assumed default.
        restoreSetting("global", Settings.Global.ALWAYS_FINISH_ACTIVITIES, snapshot.alwaysFinishActivities)
        restoreSetting("global", "activity_manager_constants", snapshot.activityManagerConstants)
        _alwaysFinishActivities.value =
            getGlobalInt(Settings.Global.ALWAYS_FINISH_ACTIVITIES) == 1
        _backgroundProcessLimit.value =
            getGlobalString("activity_manager_constants").orEmpty().contains("max_cached_processes=1")

        // Lift the metered-background block only if this session put it there. When the user's own
        // switch was already on before activation, turning it off here would be undoing their
        // setting — unless a previous revert retained verified-still-blocked UIDs, which are
        // ours to retry. The outcome is reported rather than dropped: a refused unblock that
        // stays silent is another "still stopped after reverting".
        if (!snapshot.backgroundDataRestrictedBefore || backgroundDataRestrictor.hasRetainedBlocks()) {
            val dataOutcome = runCatching { backgroundDataRestrictor.disable() }.getOrNull()
            if (dataOutcome == null) {
                problems += context.getString(R.string.gt_eng_un_data_revert)
            } else if (!dataOutcome.success) {
                problems += dataOutcome.message
            }
        }

        if (snapshot.activeGamePackage != null) {
            val pkg = snapshot.activeGamePackage
            execute("cmd activity set-bg-restriction-level --user 0 $pkg adaptive_bucket")
            execute("am set-standby-bucket --user 0 $pkg working_set")
            execute("cmd deviceidle whitelist -$pkg")
            execute("cmd game reset --user 0 $pkg")
            // The intervention table goes back to whatever it held before activation — a value put
            // back verbatim, or ours deleted when there was none. Only attempted when the snapshot
            // actually recorded the prior state (null means "do not touch it at all"). A refused
            // restore is left as-is rather than retried as a blind delete: deleting anyway would
            // destroy a prior entry the device told us existed but would not accept back.
            snapshot.gameOverlay?.let { previous ->
                gameInterventions.restore(pkg, previous)
            }
        }
        if (snapshot.activeGameUid != null && !snapshot.uidWhitelistedBefore) {
            execute("cmd netpolicy remove restrict-background-whitelist ${snapshot.activeGameUid}")
        }
        if (isVivoOrIqoo() && (snapshot.activeGamePackage != null)) {
            restoreGlobalSetting(vivoGameCubeApps, snapshot.vivoGameCubeApps)
            restoreGlobalSetting(vivoSpeedModeApps, snapshot.vivoSpeedModeApps)
            restoreGlobalSetting(vivoHighRefreshRateApps, snapshot.vivoHighRefreshRateApps)
            restoreGlobalSetting(vivoScreenRefreshRateAppsList, snapshot.vivoScreenRefreshRateAppsList)
        }
        // Qualcomm GPU mode, back to what the vendor booted with. Only when the snapshot recorded
        // the property as existing — a blank one was never set, and setprop can neither create nor
        // delete a property, so there is nothing an absent one could be restored *to*.
        restoreProp(VENDOR_GPU_MODE, snapshot.vendorGpuMode)
        restoreProp(VENDOR_GFX_LOW_QUALITY, snapshot.vendorGfxLowQuality)
        // The game FPS hint. Same rule — but a hint that did not exist before cannot be deleted,
        // so this returns it only when the vendor had one, and the created hint otherwise stays
        // until the next reboot. That bounded leftover is stated in the snapshot field's KDoc and
        // in GamingModeReport.qtiGameFps's, not hidden.
        restoreProp(DEBUG_VENDOR_QTI_GAME_FPS, snapshot.debugVendorQtiGameFps)
        prefs.edit { remove("last_snapshot") }
        return problems
    }

    private suspend fun restoreGlobalSetting(key: String, original: String?) {
        if (original == null) return
        if (original.isBlank()) execute("settings delete global $key")
        else execute("settings put global $key $original")
    }

    /**
     * Whether [uid] is exempt from Data Saver, per `cmd netpolicy`.
     *
     * The command answers on a single line — `Restrict background whitelisted UIDs: 10123 10456` — so
     * the UIDs are tokens within that line. Treating each line as one UID (as this did) never matched
     * anything, which made a successful whitelist report as a failure in the activation result.
     */
    private suspend fun isUidWhitelisted(uid: Int): Boolean {
        val output = execute("cmd netpolicy list restrict-background-whitelist")
        return uid in BackgroundDataRestrictor.parseUidList(output)
    }

    /** CSV append now lives in [OemPackageResolver.appendPackage] (pure, and pinned by tests). */
    private fun appendToCsv(list: String, pkg: String): String = OemPackageResolver.appendPackage(list, pkg)

    private fun upsertCsvKey(csv: String, key: String, value: String): String {
        val kept = csv.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("$key=") }
        return (kept + "$key=$value").joinToString(",")
    }

    private fun removeCsvKey(csv: String, key: String): String {
        return csv.split(",")
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("$key=") }
            .joinToString(",")
    }

    /** One verified wake-up; false means still suspended, or the shell never answered. */
    private suspend fun unsuspendOne(pkg: String): Boolean {
        val result = runCatching {
            shellRunner.execSafeResult("pm", "unsuspend", "--user", "0", pkg)
        }.getOrNull() ?: return false
        return SuspendVerdict.isUnsuspendConfirmed(result.exitCode, result.stdout)
    }

    private suspend fun restoreSetting(namespace: String, key: String, sv: SettingValue?) {        if (sv == null) return
        val cmd = if (sv.existed && sv.value.isNotBlank()) {
            "settings put $namespace $key ${sv.value}"
        } else {
            "settings delete $namespace $key"
        }
        execute(cmd)
    }

    /**
     * Sets Qualcomm's vendor GPU properties for the duration of Gaming Mode and confirms them by
     * read-back — the payload `GameUnlocker-main/cpp/controller.cpp` ships its daemon for, minus
     * the daemon (see [GamingModeReport.gpuPerformanceMode]).
     *
     * @return null when the device carries neither property — not applicable, every non-Qualcomm
     *   SoC — otherwise whether every property it does carry landed at its target value.
     */
    private suspend fun applyVendorGpuPerformance(): Boolean? {
        val modePresent = readProp(VENDOR_GPU_MODE).isNotBlank()
        val lowQualityPresent = readProp(VENDOR_GFX_LOW_QUALITY).isNotBlank()
        if (!modePresent && !lowQualityPresent) return null
        var confirmed = 0
        var attempted = 0
        if (modePresent) {
            attempted++
            if (setPropVerified(VENDOR_GPU_MODE, "performance")) confirmed++
        }
        if (lowQualityPresent) {
            attempted++
            if (setPropVerified(VENDOR_GFX_LOW_QUALITY, "1")) confirmed++
        }
        return confirmed == attempted
    }

    /**
     * Hints Qualcomm's perf framework at [peakHz] through `debug.vendor.qti.game.fps`, the whole
     * payload of `GameUnlocker-main/common/post-fs-data.sh` (see [GamingModeReport.qtiGameFps]
     * for why the `persist.` twin is deliberately not set and why the value is measured rather
     * than the reference's hard-coded 120).
     *
     * @return null when the device is not Qualcomm — not applicable — otherwise whether the
     *   property read back at [peakHz].
     */
    private suspend fun applyQtiGameFpsHint(peakHz: Int): Boolean? {
        if (!isQualcommDevice()) return null
        return setPropVerified(DEBUG_VENDOR_QTI_GAME_FPS, peakHz.toString())
    }

    /**
     * Whether the device's own property dump names a Qualcomm vendor property. The reference's
     * `isQualcomm()` matched `ro.hardware` against a prefix table (qcom, kalama, taro, …); the
     * dump itself is the measured fact — every Qualcomm device carries `ro.vendor.qti.*`
     * properties because its whole vendor stack defines them — and it cannot go stale the way a
     * chipset-name list does.
     */
    private suspend fun isQualcommDevice(): Boolean {
        val dump = shellRunner.execSafeResult("getprop")
        return dump.isSuccess && dump.stdout.contains("[ro.vendor.qti.")
    }

    /** @return the property's value, or "" when it is not set or could not be read. */
    private suspend fun readProp(key: String): String {
        val result = shellRunner.execSafeResult("getprop", key)
        return if (result.isSuccess) result.stdout.trim() else ""
    }

    /**
     * `setprop` prints nothing on success and its refusal goes to stderr, so — like
     * `settings put` — the read-back is the only honest signal. Root only: a shell-uid Shizuku
     * session cannot write vendor properties, and that surfaces here as a refusal rather than
     * as silence.
     */
    private suspend fun setPropVerified(key: String, value: String): Boolean {
        shellRunner.execSafeResult("setprop", key, value)
        return readProp(key) == value
    }

    /** A system property as a [SettingValue]: blank is `existed = false`, a failed read is null. */
    private suspend fun propSettingValue(key: String): SettingValue? {
        val result = shellRunner.execSafeResult("getprop", key)
        if (!result.isSuccess) return null
        val value = result.stdout.trim()
        return if (value.isEmpty()) SettingValue("", existed = false) else SettingValue(value, existed = true)
    }

    /** Puts a property back, only when the snapshot actually recorded it as existing. */
    private suspend fun restoreProp(key: String, sv: SettingValue?) {
        if (sv == null || !sv.existed || sv.value.isBlank()) return
        shellRunner.execSafeResult("setprop", key, sv.value)
    }

    /**
     * Re-applies the gaming-mode writes after a process restart and rebuilds [report] from what
     * actually landed.
     *
     * The flags come from the verified results rather than from the persisted "it was on before"
     * bit: a reboot, a ROM update or a settings reset between runs can silently undo any of them,
     * and showing the old state would be reporting a change that is no longer in effect.
     */
    private suspend fun recoverPersistedState() {
        try {
            val unavailable = mutableListOf<String>()
            val maxHz = refreshRates.getMaxHardwareRefreshRate().toInt()
            val peakOk = putSettingVerified("system", "peak_refresh_rate", maxHz.toString())
            val minOk = putSettingVerified("system", "min_refresh_rate", maxHz.toString())
            val lockedHz = if (peakOk || minOk) maxHz else null
            if (lockedHz == null) {
                unavailable += context.getString(R.string.gt_eng_un_refresh)
            }

            val touchOk = putSettingVerified("system", "touch_response_speed", "2")
            if (!touchOk) unavailable += context.getString(R.string.gt_eng_un_touch)

            val fixedPerfOk = shellRunner
                .execSafeResult("cmd", "power", "set-fixed-performance-mode-enabled", "true")
                .isSuccess
            if (!fixedPerfOk) unavailable += context.getString(R.string.gt_eng_un_fixed)
            // The Qualcomm GPU switch too, under the same re-assert-everything rule as the rest
            // of this recovery — the snapshot still holds the vendor's originals, so the eventual
            // deactivation restores them either way.
            val gpuPerfOk = applyVendorGpuPerformance()
            if (gpuPerfOk == false) {
                unavailable += context.getString(R.string.gt_eng_un_gpu)
            }
            // The game FPS hint with the same rule; maxHz above is this run's measured peak.
            val qtiFpsOk = applyQtiGameFpsHint(maxHz)
            if (qtiFpsOk == false) {
                unavailable += context.getString(R.string.gt_eng_un_qti)
            }
            execute("cmd deviceidle force-idle")

            // OEM specific recovery
            if (isVivoOrIqoo()) {
                execute("cmd thermalservice reset")
            }

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val dndEngaged = nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE

            // Re-assert the two developer options and read them back. The snapshot still holds the
            // user's originals from the previous process, so deactivation restores those either way.
            val discardOk = toggleAlwaysFinishActivities(true)
            if (!discardOk) {
                unavailable += context.getString(R.string.gt_eng_un_discard)
            }
            val processLimitOk = toggleBackgroundProcessLimit(true)
            if (!processLimitOk) {
                unavailable += context.getString(R.string.gt_eng_un_limit)
            }

            val snapshot = prefs.getString("last_snapshot", null)
                ?.let { GamingOptimizationSnapshot.fromJson(it) }

            // Report the real netpolicy state rather than re-issuing the block: whatever this app set
            // before the restart is still in the kernel's uid rules, since netpolicy survives a
            // process death. Re-engaging would also overwrite the "was it already on" bookkeeping.
            val backgroundDataRestricted: Boolean? = when {
                snapshot?.backgroundDataRestrictedBefore == true -> null
                else -> runCatching { backgroundDataRestrictor.status().restrictedUids.isNotEmpty() }
                    .getOrDefault(false)
            }

            val pkg = snapshot?.activeGamePackage
            var networkWhitelisted: Boolean? = null
            if (pkg != null) {
                execute("pm unsuspend --user 0 $pkg")
                execute("cmd activity set-bg-restriction-level --user 0 $pkg unrestricted")
                execute("am set-standby-bucket --user 0 $pkg active")
                execute("cmd deviceidle whitelist +$pkg")
                networkWhitelisted = snapshot.activeGameUid?.let { isUidWhitelisted(it) } == true
            }

            _isFixedPerformanceMode.value = fixedPerfOk
            _report.value = GamingModeReport(
                fixedPerformance = fixedPerfOk,
                gpuPerformanceMode = gpuPerfOk,
                qtiGameFps = qtiFpsOk,
                lockedRefreshHz = lockedHz,
                touchResponseBoost = touchOk,
                // The suspends happened in the previous process; this set is the record of them and
                // is exactly what disableGamingMode will unsuspend.
                suspendedPackages = prefs.getStringSet("affected_pkgs", emptySet())?.size ?: 0,
                dndEngaged = dndEngaged,
                networkWhitelisted = networkWhitelisted,
                discardActivities = discardOk,
                processLimit = processLimitOk,
                backgroundDataRestricted = backgroundDataRestricted,
                unavailable = unavailable
            )
        } catch (_: Exception) {}
    }

    private suspend fun reapplyFixedPerformanceMode() {
        val applied = runCatching {
            shellRunner.execSafeResult("cmd", "power", "set-fixed-performance-mode-enabled", "true").isSuccess
        }.getOrDefault(false)
        _isFixedPerformanceMode.value = applied
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")

        /** Packages per `pm unsuspend` fork in the deactivation sweep; mirrors UID_BATCH_SIZE. */
        private const val UNSUSPEND_BATCH_SIZE = 25

        /** Qualcomm's vendor GPU mode knob, and its texture-quality companion. */
        const val VENDOR_GPU_MODE = "vendor.gpu.mode"
        const val VENDOR_GFX_LOW_QUALITY = "vendor.gfx.low_quality"

        /**
         * Qualcomm's game FPS hint, set by Gaming Mode to the panel's measured peak. The
         * `persist.vendor.qti.game.fps` twin from the reference's `post-fs-data.sh` is
         * deliberately absent — see [GamingModeReport.qtiGameFps].
         */
        const val DEBUG_VENDOR_QTI_GAME_FPS = "debug.vendor.qti.game.fps"
    }
}
