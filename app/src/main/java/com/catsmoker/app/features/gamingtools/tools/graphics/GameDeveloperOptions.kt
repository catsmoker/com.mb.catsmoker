package com.catsmoker.app.features.gamingtools.tools.graphics

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import com.catsmoker.app.R
import com.catsmoker.app.features.gamingtools.engine.DisplayRefreshRateProvider
import com.catsmoker.app.system.shell.ShellRunner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * The three gaming switches Android's own Developer Options screen exposes.
 *
 * Each one goes through the mechanism the platform screen itself uses — no app-local imitation, and
 * no key invented to make a switch look real. Every write is confirmed by reading the value back
 * from the system, and a control the device does not support reports *why* rather than moving and
 * doing nothing.
 *
 * - **Show refresh rate** — SurfaceFlinger's debug transaction `1034` on the
 *   `android.ui.ISurfaceComposer` interface, exactly as `ShowRefreshRatePreferenceController` does:
 *   interface token first, then one int — `0`/`1` to set, anything else to ask. `service call` writes
 *   that token itself, so the parcel this app sends is the same shape the platform controller sends.
 *
 *   What is *not* the same is the caller. From Android 14 SurfaceFlinger gates its whole backdoor
 *   range on `ACCESS_SURFACE_FLINGER`, which `shell` does not hold and cannot be granted — it is a
 *   signature permission — so a Shizuku-only device is refused. Measured on Android 16, uid 2000:
 *   `Result: Parcel(Error: 0xffffffffffffffff "Operation not permitted")`, **exit code 0**, with
 *   `Permission Denial: can't access SurfaceFlinger` going to logcat where the caller never sees it.
 *   Root is a different caller and is still accepted, and Android's own Developer options screen runs
 *   as `system` and always is.
 *
 *   Getting that refusal wrong is what made this switch the one control here that lied: the reply
 *   carries no `Failure`, so [transactionRefusal] passed it as applied, the app recorded an overlay it
 *   had never drawn, and [recordedOverlayState] then handed the switch back its own invention. It
 *   read "On" over a screen with nothing on it. Availability is now decided by asking SurfaceFlinger,
 *   not by assuming the send worked, and a refusal points at the screen that does work. The state
 *   still falls back to what this app recorded on Android 11 and earlier, where transaction `1034` has
 *   no branch that answers a query at all — but only there, and the row says so. The panel's live rate
 *   is shown either way, from the display's active mode.
 * - **Force peak refresh rate** — writes `min_refresh_rate` as a float, set to the panel's peak
 *   rate, as `ForcePeakRefreshRatePreferenceController` does. Raising the *minimum* is what forces
 *   the panel up: `DisplayModeDirector` votes the ceiling as `max(min, peak)`, so the minimum wins.
 * - **Disable default frame rate for games** — Android holds this in the system property
 *   `debug.graphics.game_default_frame_rate.disabled`, and the cap it lifts comes from the
 *   read-only `ro.surface_flinger.game_default_frame_rate_override`.
 */
@Singleton
class GameDeveloperOptions @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shellRunner: ShellRunner,
    private val refreshRates: DisplayRefreshRateProvider
) {
    /**
     * One switch's real condition.
     *
     * @param enabled what the system reports, or null when it would not answer at all. Null must be
     *   shown as unknown — never as "off", which would be a reading the device did not give.
     * @param available whether the control can do anything on this device.
     * @param unavailableReason the concrete reason when [available] is false.
     * @param detail a device fact worth showing next to the switch (the peak rate, the frame cap).
     * @param openDeveloperOptions true when the switch cannot work here but the user can set the same
     *   thing themselves in Android's own Developer options screen. The row then offers a button that
     *   goes there instead of only saying no.
     */
    data class ToggleState(
        val enabled: Boolean? = null,
        val available: Boolean = false,
        val unavailableReason: String? = null,
        val detail: String? = null,
        val openDeveloperOptions: Boolean = false
    )

    /** All three, as last read. */
    data class State(
        val showRefreshRate: ToggleState = ToggleState(),
        val forcePeakRefreshRate: ToggleState = ToggleState(),
        val gameDefaultFrameRateDisabled: ToggleState = ToggleState()
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val prefs: SharedPreferences =
        context.getSharedPreferences("game_developer_options", Context.MODE_PRIVATE)

    suspend fun refresh() {
        _state.value = State(
            showRefreshRate = readShowRefreshRate(),
            forcePeakRefreshRate = readForcePeakRefreshRate(),
            gameDefaultFrameRateDisabled = readGameDefaultFrameRate()
        )
    }

    // ---------------------------------------------------------------- Show refresh rate

    /**
     * What asking SurfaceFlinger for the overlay state produced.
     *
     * Three outcomes, kept apart because they justify three different rows. An answer is the truth and
     * ends the matter. A refusal means the switch cannot work here at all — the query and the write go
     * through the same permission gate, so a refused query proves a refused write. "No answer" means
     * this build has no query branch, which is not a failure and must not be rendered as one. The
     * previous version collapsed the last two into a single null, which is how a device that had
     * refused the transaction outright became indistinguishable from one that merely could not be
     * asked — and the refused one then got its state from [recordedOverlayState].
     */
    private sealed interface OverlayProbe {
        data class Answered(val enabled: Boolean) : OverlayProbe
        data class Refused(val reason: String) : OverlayProbe
        data object Unanswerable : OverlayProbe
    }

    /**
     * Reads the overlay switch.
     *
     * Availability means *will SurfaceFlinger accept the transaction* — established by sending it one,
     * not by observing that this app has a privileged shell. Those are different facts, and treating
     * the second as the first is what let a device that refuses every backdoor call present a working
     * switch. Only when SurfaceFlinger cannot be asked at all (Android 11 and earlier) does the state
     * fall back to what this app recorded, and the row names which of the two it is showing.
     *
     * Without root or Shizuku the switch stays off and immovable — but Android's own Developer options
     * screen has the very same toggle and needs no privilege from us, so the row offers a button that
     * opens it. That is a real route to the same result rather than a dead end, and it is the only
     * honest thing to offer: nothing this app can call from an ordinary process moves this overlay.
     */
    private suspend fun readShowRefreshRate(): ToggleState = withContext(Dispatchers.IO) {
        // The rate the panel is on right now, which needs no privilege and is worth showing whether or
        // not SurfaceFlinger's own overlay can be turned on.
        val liveNote = refreshRates.getCurrentRefreshRate()
            ?.let { context.getString(R.string.gt_gdo_live_at, formatHz(it)) }
            ?: context.getString(R.string.gt_gdo_live_unknown)

        if (!shellRunner.hasPrivilege()) {
            return@withContext ToggleState(
                enabled = false,
                available = false,
                unavailableReason = context.getString(R.string.gt_gdo_no_priv),
                detail = liveNote,
                openDeveloperOptions = true
            )
        }

        when (val probe = probeSurfaceFlingerOverlay()) {
            // SurfaceFlinger answered, so there is nothing left to infer.
            is OverlayProbe.Answered -> ToggleState(
                enabled = probe.enabled,
                available = true,
                detail = context.getString(R.string.gt_gdo_confirmed, liveNote)
            )

            // Turned down. The state is genuinely unknown — not "off", which would be a reading the
            // device never gave — and the switch must not move, because moving it would change
            // nothing. Android's own screen is a caller SurfaceFlinger does accept, so the row goes
            // there instead of stopping at no.
            is OverlayProbe.Refused -> ToggleState(
                enabled = null,
                available = false,
                unavailableReason = context.getString(
                    R.string.gt_gdo_refused_detail, probe.reason, refusalAdvice()
                ),
                detail = liveNote,
                openDeveloperOptions = true
            )

            // No query branch on this build. Our own record is the best evidence there is here, and
            // the row says that is what it is rather than passing it off as the device's answer.
            OverlayProbe.Unanswerable -> {
                val recorded = recordedOverlayState()
                val buildDefault = if (recorded == null) buildDefaultOverlay() else null
                val refusal = staleProofRefusal()
                val source = when {
                    refusal != null -> context.getString(R.string.gt_gdo_src_refused)
                    recorded != null ->
                        context.getString(R.string.gt_gdo_src_recorded, Build.VERSION.RELEASE)
                    buildDefault != null ->
                        context.getString(R.string.gt_gdo_src_boot, Build.VERSION.RELEASE)
                    else ->
                        context.getString(R.string.gt_gdo_src_fresh, Build.VERSION.RELEASE)
                }

                ToggleState(
                    enabled = if (refusal != null) null else recorded ?: buildDefault ?: false,
                    available = refusal == null,
                    unavailableReason = refusal?.let {
                        context.getString(R.string.gt_gdo_refused_detail, it, refusalAdvice())
                    },
                    detail = context.getString(R.string.gt_gdo_detail_join, liveNote, source),
                    openDeveloperOptions = refusal != null
                )
            }
        }
    }

    /**
     * What is left to try once SurfaceFlinger has turned the transaction down.
     *
     * Shizuku runs commands as `shell`, and from Android 14 the backdoor range is gated on
     * `ACCESS_SURFACE_FLINGER` — a signature permission `shell` does not hold and cannot be granted.
     * Root is a different caller and may still be accepted. Telling a rooted user to get root, or a
     * Shizuku user that root and Shizuku are interchangeable here, would both be wrong, so the two are
     * worded apart.
     */
    private fun refusalAdvice(): String =
        if (shellRunner.isRootAvailable()) {
            context.getString(R.string.gt_gdo_advice_root)
        } else {
            context.getString(R.string.gt_gdo_advice_shell)
        }

    /**
     * Turns SurfaceFlinger's refresh-rate overlay on or off.
     *
     * The outcome is decided by reading the call's output, not its exit code: `service` returns 0 even
     * when the transaction was rejected — it prints the rejection inside the reply parcel and exits
     * successfully — so an exit code of 0 is not evidence that anything happened. On a refusal the
     * stored record is *cleared* rather than updated, because a record written next to a refusal is a
     * claim about an overlay that was never drawn, and [readShowRefreshRate] would go on to serve it
     * back as the switch's state.
     *
     * @return the state read back afterwards, which on Android 12 and up is SurfaceFlinger's own.
     */
    suspend fun setShowRefreshRate(enabled: Boolean): ToggleState {
        val call = withContext(Dispatchers.IO) {
            shellRunner.execSafeResult(
                "service", "call", "SurfaceFlinger", SF_REFRESH_RATE_OVERLAY.toString(),
                "i32", if (enabled) "1" else "0"
            )
        }
        val refusal = transactionRefusal(call)
        prefs.edit().apply {
            if (refusal == null) {
                // Recorded against the boot it applies to: the overlay lives only in SurfaceFlinger's
                // memory, so after a reboot this record would claim an overlay that is no longer there.
                putString(PREF_OVERLAY_ON, enabled.toString())
                putLong(PREF_OVERLAY_BOOT, bootStamp())
                remove(PREF_OVERLAY_REFUSAL)
                remove(PREF_OVERLAY_REFUSAL_ROOT)
            } else {
                putString(PREF_OVERLAY_REFUSAL, refusal)
                // Kept beside the refusal so gaining root later re-opens the switch instead of
                // latching it shut on a verdict that was only ever true for the weaker caller.
                putBoolean(PREF_OVERLAY_REFUSAL_ROOT, shellRunner.isRootAvailable())
                remove(PREF_OVERLAY_ON)
                remove(PREF_OVERLAY_BOOT)
            }
        }.apply()

        val result = readShowRefreshRate()
        _state.value = _state.value.copy(showRefreshRate = result)
        return result
    }

    /**
     * Asks SurfaceFlinger whether the overlay is on.
     *
     * Transaction 1034 reads one int: 0 and 1 set the overlay, and from Android 12 anything else makes
     * it reply with the current state instead. **That reply branch does not exist before Android 12.**
     * On Android 11 and earlier the value is only tested for truth, so the conventional query value of
     * 2 counts as "on" and switches the overlay on while returning an empty parcel — the query would
     * change the very thing it was asking about, and then report nothing. So it is not sent there.
     */
    private suspend fun probeSurfaceFlingerOverlay(): OverlayProbe {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return OverlayProbe.Unanswerable
        val result = shellRunner.execSafeResult(
            "service", "call", "SurfaceFlinger", SF_REFRESH_RATE_OVERLAY.toString(),
            "i32", SF_QUERY.toString()
        )
        transactionRefusal(result)?.let { return OverlayProbe.Refused(it) }
        return parseParcelInt(combinedOutput(result))
            ?.let { OverlayProbe.Answered(it != 0) }
            ?: OverlayProbe.Unanswerable
    }

    /**
     * The recorded refusal, unless the privilege picture has changed since it was written.
     *
     * A refusal earned as `shell` says nothing about the same call made as root. Without this the
     * stored reason latched the switch off permanently — and because the switch was then disabled,
     * nothing could ever produce the successful write that was the only thing that cleared it.
     *
     * @return the reason to keep showing, or null when there is none or it no longer applies.
     */
    private fun staleProofRefusal(): String? {
        val refusal = prefs.getString(PREF_OVERLAY_REFUSAL, null) ?: return null
        val refusedAsRoot = prefs.getBoolean(PREF_OVERLAY_REFUSAL_ROOT, false)
        return refusal.takeIf { refusedAsRoot == shellRunner.isRootAvailable() }
    }

    /**
     * What this app last set the overlay to, but only if it is still the same boot.
     *
     * @return the recorded state, or null when there is none or it belongs to a previous boot.
     */
    private fun recordedOverlayState(): Boolean? {
        val storedBoot = prefs.getLong(PREF_OVERLAY_BOOT, NO_BOOT_STAMP)
        if (storedBoot == NO_BOOT_STAMP) return null
        // One unit of slack: the stamp is derived from the wall clock, which an NTP correction moves.
        if (abs(storedBoot - bootStamp()) > 1L) return null
        return prefs.getString(PREF_OVERLAY_ON, null)?.toBooleanStrictOrNull()
    }

    /** Whether this build boots with the overlay already on, per SurfaceFlinger's own config property. */
    private suspend fun buildDefaultOverlay(): Boolean? =
        getProp(PROP_SHOW_REFRESH_RATE_OVERLAY)
            ?.takeIf { it.isNotBlank() }
            ?.let { it in TRUTHY_PROPERTY_VALUES }

    // ---------------------------------------------------------- Force peak refresh rate

    private fun readForcePeakRefreshRate(): ToggleState {
        val peak = refreshRates.getMaxHardwareRefreshRate()
        if (peak <= DEFAULT_REFRESH_RATE) {
            return ToggleState(
                available = false,
                unavailableReason = context.getString(R.string.gt_gdo_single_speed),
                detail = context.getString(R.string.gt_gdo_runs_at, formatHz(peak))
            )
        }
        // An unset key means "no minimum": Settings.System.getFloat(cr, key, 0f) — which is how the
        // platform controller reads it — returns 0 both when the key is absent and when it holds
        // something unparseable, so falling back to 0 here is the device's own answer, not a guess.
        val current = readSystemFloat(KEY_MIN_REFRESH_RATE) ?: NO_CONFIG
        return ToggleState(
            // AOSP treats "min is at the peak" as on. Compared with a tolerance because a provider
            // may hold 120 for a written 120.0, and both mean the same rate.
            enabled = current >= peak - HZ_TOLERANCE,
            available = canWriteSystemSettings(),
            unavailableReason = if (canWriteSystemSettings()) {
                null
            } else {
                context.getString(R.string.gt_gdo_need_sys)
            },
            detail = if (current > 0f && current < peak - HZ_TOLERANCE) {
                context.getString(R.string.gt_gdo_peak_full, formatHz(peak), formatHz(current))
            } else {
                context.getString(R.string.gt_gdo_peak_upto, formatHz(peak))
            }
        )
    }

    /**
     * Raises or clears the minimum refresh rate.
     *
     * Turning it on stashes whatever `min_refresh_rate` held first, so turning it off puts the
     * user's own value back rather than blanket-writing the platform's 0 over it.
     *
     * @return the state read back afterwards.
     */
    suspend fun setForcePeakRefreshRate(enabled: Boolean): ToggleState {
        val peak = refreshRates.getMaxHardwareRefreshRate()
        if (enabled) {
            // Skip the capture when it is already forced, or this would record our own value.
            if (readForcePeakRefreshRate().enabled != true) {
                val previous = readSystemFloat(KEY_MIN_REFRESH_RATE)
                prefs.edit().putString(PREF_PREVIOUS_MIN_HZ, previous?.toString() ?: "").apply()
            }
            writeSystemFloat(KEY_MIN_REFRESH_RATE, peak)
        } else {
            val stashed = prefs.getString(PREF_PREVIOUS_MIN_HZ, null)
            val previous = stashed?.toFloatOrNull()
            when {
                // A recorded value goes back verbatim.
                previous != null -> writeSystemFloat(KEY_MIN_REFRESH_RATE, previous)
                // Recorded as unset: remove the key, which is what "unset" means.
                stashed != null -> deleteSystemSetting(KEY_MIN_REFRESH_RATE)
                // No record at all — the force predates this build. 0 is the platform's own
                // "no minimum" value and is what Developer Options writes when switched off.
                else -> writeSystemFloat(KEY_MIN_REFRESH_RATE, NO_CONFIG)
            }
            prefs.edit().remove(PREF_PREVIOUS_MIN_HZ).apply()
        }
        val result = readForcePeakRefreshRate()
        _state.value = _state.value.copy(forcePeakRefreshRate = result)
        return result
    }

    // ------------------------------------------- Disable default frame rate for games

    /**
     * Reads the game default frame rate switch.
     *
     * The property is the state Android itself keeps; there is no `Settings` key for this. The cap
     * being lifted is a read-only build property, so it is shown as a fact rather than a control.
     */
    private suspend fun readGameDefaultFrameRate(): ToggleState = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            return@withContext ToggleState(
                available = false,
                unavailableReason = context.getString(R.string.gt_gdo_old15, Build.VERSION.RELEASE)
            )
        }
        val cap = getProp(PROP_GAME_FRAME_RATE_OVERRIDE)?.toIntOrNull()
        val raw = getProp(PROP_GAME_FRAME_RATE_DISABLED)
        ToggleState(
            // SystemProperties.getBoolean, which the Developer Options controller uses to read this,
            // counts exactly these tokens as true and everything else — including a property that is
            // not set — as false. Null here means the read itself failed, which is not a reading.
            enabled = raw?.let { it in TRUTHY_PROPERTY_VALUES },
            available = shellRunner.hasPrivilege(),
            unavailableReason = if (shellRunner.hasPrivilege()) {
                null
            } else {
                context.getString(R.string.gt_needs_root_shizuku_change)
            },
            detail = if (cap != null) {
                context.getString(R.string.gt_gdo_cap, cap)
            } else {
                context.getString(R.string.gt_gdo_cap_unknown)
            }
        )
    }

    /**
     * Sets the game default frame rate property.
     *
     * Developer Options routes this through `IGameManagerService.toggleGameDefaultFrameRate`, which
     * writes this same property and then notifies SurfaceFlinger in the same step. That binder
     * interface is not reachable from an app, so this writes the property directly and verifies it;
     * the notification part is what the caller is told about in the UI.
     *
     * @return the state read back afterwards.
     */
    suspend fun setGameDefaultFrameRateDisabled(disabled: Boolean): ToggleState {
        withContext(Dispatchers.IO) {
            shellRunner.execSafeResult(
                "setprop", PROP_GAME_FRAME_RATE_DISABLED, if (disabled) "true" else "false"
            )
        }
        val result = readGameDefaultFrameRate()
        _state.value = _state.value.copy(gameDefaultFrameRateDisabled = result)
        return result
    }

    // ------------------------------------------------------------------------ plumbing

    /**
     * @return the property's value, "" when it is not set, or null when it could not be read at all.
     *
     * An unset property makes `getprop` print an empty line and still succeed, so blank is a real
     * answer — "not set" — and must not be confused with a read that failed.
     */
    private suspend fun getProp(name: String): String? {
        val result = shellRunner.execSafeResult("getprop", name)
        return if (result.isSuccess) result.stdout.trim() else null
    }

    private fun canWriteSystemSettings(): Boolean =
        shellRunner.hasPrivilege() || Settings.System.canWrite(context)

    private fun readSystemFloat(key: String): Float? = try {
        // Read through the provider rather than the shell: it needs no privilege and cannot fail
        // for the reasons a shell can. An unset key returns null here, not 0.
        Settings.System.getString(context.contentResolver, key)?.toFloatOrNull()
    } catch (_: Exception) {
        null
    }

    /** Writes [value] through the shell, then the framework, and leaves the read-back to the caller. */
    private suspend fun writeSystemFloat(key: String, value: Float) = withContext(Dispatchers.IO) {
        val formatted = String.format(Locale.US, "%.2f", value)
        if (shellRunner.hasPrivilege()) {
            shellRunner.execSafeResult("settings", "put", "system", key, formatted)
        }
        val landed = readSystemFloat(key)?.let { abs(it - value) < HZ_TOLERANCE } == true
        if (!landed && Settings.System.canWrite(context)) {
            runCatching { Settings.System.putFloat(context.contentResolver, key, value) }
        }
    }

    private suspend fun deleteSystemSetting(key: String) = withContext(Dispatchers.IO) {
        shellRunner.execSafeResult("settings", "delete", "system", key)
        Unit
    }

    /**
     * A stamp identifying the current boot: wall clock minus uptime is the moment the device started.
     *
     * Divided down to a coarse unit so an NTP correction of a few seconds does not read as a reboot.
     */
    private fun bootStamp(): Long =
        (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / BOOT_STAMP_UNIT_MS

    companion object {
        /** SurfaceFlinger's refresh-rate-overlay backdoor transaction. */
        private const val SF_REFRESH_RATE_OVERLAY = 1034

        /**
         * Any value other than 0 or 1 makes transaction 1034 report instead of set — from Android 12
         * onwards only. See [querySurfaceFlingerOverlay] for why it must not be sent before that.
         */
        private const val SF_QUERY = 2

        private const val KEY_MIN_REFRESH_RATE = "min_refresh_rate"

        private const val PROP_GAME_FRAME_RATE_DISABLED =
            "debug.graphics.game_default_frame_rate.disabled"
        private const val PROP_GAME_FRAME_RATE_OVERRIDE =
            "ro.surface_flinger.game_default_frame_rate_override"

        /** SurfaceFlinger's own config property for booting with the overlay already drawn. */
        private const val PROP_SHOW_REFRESH_RATE_OVERLAY = "ro.surface_flinger.show_refresh_rate_overlay"

        /** Below this there is nothing to force; the platform uses the same threshold. */
        private const val DEFAULT_REFRESH_RATE = 60f

        /** The platform's "no minimum" value for `min_refresh_rate`. */
        private const val NO_CONFIG = 0f

        /** Rates differing by less than this are the same rate, stored differently. */
        private const val HZ_TOLERANCE = 0.5f

        private const val PREF_PREVIOUS_MIN_HZ = "previous_min_refresh_rate"

        /** What this app last set the overlay to, and the boot that record belongs to. */
        private const val PREF_OVERLAY_ON = "show_refresh_rate_overlay_on"
        private const val PREF_OVERLAY_BOOT = "show_refresh_rate_overlay_boot"

        /** The reason SurfaceFlinger last refused the call, kept so the row can explain itself. */
        private const val PREF_OVERLAY_REFUSAL = "show_refresh_rate_overlay_refusal"

        /**
         * Whether root was available when that refusal was recorded.
         *
         * A refusal earned as `shell` is not evidence about the same call made as root, so the verdict
         * is only reused while the caller is the same one it was reached about.
         */
        private const val PREF_OVERLAY_REFUSAL_ROOT = "show_refresh_rate_overlay_refusal_root"

        /** Coarse enough that a clock correction is not mistaken for a reboot. */
        private const val BOOT_STAMP_UNIT_MS = 10_000L

        /** Sentinel for "no boot recorded"; a real stamp is a positive count of 10-second units. */
        private const val NO_BOOT_STAMP = Long.MIN_VALUE

        /** The values `SystemProperties.getBoolean` reads as true. */
        private val TRUTHY_PROPERTY_VALUES = setOf("1", "y", "yes", "on", "true")

        private fun formatHz(hz: Float): String =
            if (hz % 1f == 0f) "${hz.toInt()} Hz" else String.format(Locale.US, "%.1f Hz", hz)

        /** Both streams, because `service` prints its failures to stderr and its replies to stdout. */
        fun combinedOutput(result: ShellRunner.ExecResult): String =
            listOf(result.stdout, result.stderr).filter { it.isNotBlank() }.joinToString("\n")

        /**
         * Whether a `service call` was rejected, and in the device's own words.
         *
         * This has to be read out of the output because `service` exits 0 regardless: it prints
         * `Failure: …` on a rejected transaction and still returns success, so treating exit 0 as proof
         * of a landed call is how a refused write ends up reported as applied.
         *
         * A binder-level rejection does not print `Failure` either — see [parcelError] — and missing
         * that was the specific hole here: SurfaceFlinger's refusal reached the app as a reply parcel
         * carrying a status, sailed through all four remaining branches as "no refusal", and the
         * overlay switch reported an overlay that had never been drawn.
         *
         * The wording is plain because it is shown on the card, but the device's own line is still
         * carried through: a refusal nobody can read is not much better than no refusal at all, and a
         * refusal this app paraphrased away cannot be diagnosed.
         *
         * @return the reason, or null when the call went through.
         */
        fun transactionRefusal(result: ShellRunner.ExecResult): String? {
            val text = combinedOutput(result)
            val parcelError = parcelError(text)
            return when {
                text.contains("Permission Denial", ignoreCase = true) ->
                    "Your phone refused. This part of Android only listens to a few trusted callers, " +
                        "and the way this app is asking is not one of them."
                parcelError != null ->
                    "Your phone turned the request down — it said \"$parcelError\"."
                text.contains("does not exist", ignoreCase = true) ->
                    "Your phone does not have the part of Android this needs."
                text.contains("Failure", ignoreCase = true) ->
                    "Your phone said no: " +
                        text.lines().first { it.contains("Failure", ignoreCase = true) }.trim()
                !result.isSuccess ->
                    "The request could not be sent: ${text.trim().ifBlank { "your phone said nothing" }}"
                else -> null
            }
        }

        /**
         * The status a `service call` reply carries in place of data.
         *
         * A rejected transaction prints neither `Failure` nor a non-zero exit code. `service` prints
         * whatever status `transact` handed back inside the parcel itself and still exits 0:
         *
         * ```
         * $ service call SurfaceFlinger 1034 i32 1
         * Result: Parcel(Error: 0xffffffffffffffff "Operation not permitted")
         * $ echo $?
         * 0
         * ```
         *
         * That is what Android 14 and up return for SurfaceFlinger's backdoor transactions when the
         * caller is only `shell` — as Shizuku's is. `PERMISSION_DENIED` is `-EPERM`, hence the `-1` and
         * `strerror`'s "Operation not permitted"; SurfaceFlinger's own `Permission Denial: can't access
         * SurfaceFlinger pid=… uid=2000` goes to logcat, which the caller cannot see, so this reply is
         * the only evidence the app ever gets.
         *
         * @return the message the reply carried, or null when the reply was data rather than a status.
         */
        fun parcelError(output: String): String? {
            val body = output.substringAfter("Parcel(", missingDelimiterValue = "")
            if (!body.trimStart().startsWith("Error", ignoreCase = true)) return null
            // Prefer the quoted half: it is strerror()'s own words, where the hex status alone would
            // tell the user nothing. Fall back to the whole reply if this build words it differently.
            val quoted = body.substringAfter('"', missingDelimiterValue = "").substringBefore('"').trim()
            return quoted.takeIf { it.isNotBlank() }
                ?: body.substringBefore(')').trim().takeIf { it.isNotBlank() }
        }

        /**
         * Pulls the first int out of `service call`'s reply.
         *
         * Two forms have to be handled. A short reply prints inline — `Result: Parcel(00000001 '....')`
         * — while a longer one prints an offset-prefixed hexdump, `Parcel(\n  0x00000000: 00000001
         * '....')`, whose offset column would otherwise be read as the value. `Parcel(NULL)` is a reply
         * that carried no data at all, and an `Error:` reply carried a status instead of data — a hex
         * status is not a reading, and letting one through here would make a refusal look like an
         * answer.
         *
         * @return the int, or null when there was none to read.
         */
        fun parseParcelInt(output: String): Int? {
            val body = output.substringAfter("Parcel(", missingDelimiterValue = "")
            if (body.isBlank() || body.trimStart().startsWith("NULL")) return null
            if (parcelError(output) != null) return null
            // Drop the `0x00000000:` offsets so the first remaining hex word is a data word.
            val words = body.replace(Regex("0x[0-9a-fA-F]+:"), " ")
            return Regex("\\b([0-9a-fA-F]{8})\\b")
                .find(words)
                ?.groupValues
                ?.get(1)
                ?.toLongOrNull(16)
                ?.toInt()
        }
    }
}
