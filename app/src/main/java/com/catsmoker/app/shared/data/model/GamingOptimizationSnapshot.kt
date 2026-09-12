package com.catsmoker.app.shared.data.model

import com.google.gson.Gson

/**
 * The user's real system state, read once *before* Gaming Mode writes anything.
 *
 * Every field is either the value the device actually held or null for "we could not read it, so do
 * not touch it on the way back". Nothing here is a default this app assumed — restoring an assumed
 * value would silently overwrite a preference the user had set deliberately.
 */
data class GamingOptimizationSnapshot(
    val activeGamePackage: String?,
    val activeGameUid: Int?,
    val timestamp: Long,
    val minRefreshRate: SettingValue?,
    val peakRefreshRate: SettingValue?,
    val touchResponseSpeed: SettingValue?,
    val userPreferredDisplayModeId: SettingValue?,
    val affectedPackages: Set<String>,
    val uidWhitelistedBefore: Boolean = false,
    val vivoGameCubeApps: String? = null,
    val vivoSpeedModeApps: String? = null,
    /**
     * The two vivo high-refresh-rate whitelist CSVs (`vivo_high_refresh_rate_apps`,
     * `vivo_screen_refresh_rate_apps_list`) as the device held them, same contract as
     * [vivoGameCubeApps] above: null when there was no game to target or the device is not
     * vivo/iQOO, blank when the key was simply not set. The skin keeps its panel at peak only
     * for apps on these lists, so Gaming Mode appends the game to them on activation and the
     * whole recorded value goes back on the way out.
     */
    val vivoHighRefreshRateApps: String? = null,
    val vivoScreenRefreshRateAppsList: String? = null,
    val originalRingtoneVolume: Int? = null,
    val originalBrightnessMode: Int? = null,
    val originalRotation: Int? = null,
    /**
     * `global always_finish_activities` as it was — the "Don't keep activities" developer option.
     * Absent (`existed = false`) is the normal case and means "delete it again on the way out".
     */
    val alwaysFinishActivities: SettingValue? = null,
    /**
     * The whole `global activity_manager_constants` CSV as it was, not just the one key inside it
     * that Gaming Mode edits. Restoring the string wholesale is the only way to leave any other
     * constant the user or ROM had set in that CSV untouched.
     */
    val activityManagerConstants: SettingValue? = null,
    /**
     * True when the metered-background restriction was *already* engaged before activation.
     *
     * Gaming Mode then leaves it alone on the way out, because it belongs to the user's own
     * Background Data Restriction switch rather than to this session.
     */
    val backgroundDataRestrictedBefore: Boolean = false,
    /**
     * `NotificationManager.getCurrentInterruptionFilter()` as it was.
     *
     * Deactivation used to hard-code `INTERRUPTION_FILTER_ALL`, which silently cancelled a
     * priority-only or alarms-only Do Not Disturb the user had set for their own reasons. null means
     * it could not be read, and the filter is then left untouched.
     */
    val originalInterruptionFilter: Int? = null,
    /**
     * The `device_config game_overlay` entry the target game had before Gaming Mode wrote one.
     *
     * `existed = false` is the normal case — most games carry no intervention — and means "delete
     * ours on the way out", which is the reference script `genshun.sh`'s own revert step. A value
     * goes back verbatim, because overwriting another tool's intervention with an assumed "unset"
     * would be the same mistake as restoring an assumed setting over a deliberate one. null means
     * there was no game to target, the device is too old for game interventions, or the read
     * failed — in all three cases the flag is left completely alone.
     */
    val gameOverlay: SettingValue? = null,
    /**
     * Qualcomm's `vendor.gpu.mode` as the vendor booted with it — what Gaming Mode's GPU
     * performance switch puts back on deactivation.
     *
     * `existed = false` is the normal case on every SoC that is not Qualcomm (or whose vendor
     * does not expose the knob): the activation-side gate then skips the switch entirely, so
     * there is nothing to restore. null means the property could not be read at all, and the
     * revert leaves it alone.
     */
    val vendorGpuMode: SettingValue? = null,
    /**
     * `vendor.gfx.low_quality` as it was — the second half of the same Qualcomm switch, restored
     * by the same rule as [vendorGpuMode].
     */
    val vendorGfxLowQuality: SettingValue? = null,
    /**
     * Qualcomm's `debug.vendor.qti.game.fps` as it was before Gaming Mode hinted the panel's
     * measured peak at the vendor's perf framework.
     *
     * `existed = false` is the normal case — nothing defines the property until something sets
     * it — and unlike every other field here, the revert cannot fully honour it: `setprop` can
     * neither create nor delete a property, so a hint that did not exist before is left in
     * place until the next reboot clears the debug property area. The leftover is bounded and
     * stated (see `GamingModeReport.qtiGameFps`) rather than hidden. null means the read failed
     * and the property is left completely alone.
     */
    val debugVendorQtiGameFps: SettingValue? = null
) {
    fun toJson(): String = Gson().toJson(this)

    companion object {
        fun fromJson(json: String): GamingOptimizationSnapshot? {
            return try { Gson().fromJson(json, GamingOptimizationSnapshot::class.java) } catch (_: Exception) { null }
        }
    }
}
