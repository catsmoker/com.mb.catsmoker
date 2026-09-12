package com.catsmoker.app.features.editgamefiles.wuwa

import com.google.gson.Gson
import java.io.File

/**
 * The auto-tune benchmark loop: deploy a preset, wait for the user to play a session, measure
 * the FPS the game's own log recorded, then step along the preset ladder until the target is
 * met or the round budget is spent.
 *
 * Ported from
 * `referance/gamingtools/WuWa-Config-Android-main/app/src/main/java/com/wuwaconfig/app/config/BenchmarkTuner.kt`,
 * read in full before this file was written: the five-stage machine, the round/result state
 * persisted to JSON, the ladder order and both step rules (down one tier under 85% of target,
 * up one tier when the average clears the target by more than 15%), the avg/min/stability
 * formulas — `count(fps >= avg * 0.8) / size * 100` — and the tiered option escalation are
 * line-for-line. [WuwaBenchmarkTunerTest] pins them against the reference's own numbers.
 *
 * Deliberate divergences from the reference, each because the subsystem it hangs off was not
 * ported:
 *  - **Sample source.** The reference scraped `logcat -d -v brief -t 500` from the game's
 *    process with a broad "any line that smells like FPS" regex. This app already owns a
 *    truer channel for the same fact — the game's own XOR-obfuscated `Client.log`, read
 *    byte-exactly and decrypted by [WuwaConfigManager.readClientLog] +
 *    [WuwaLogDecryptor]. [parseFpsSamples] therefore windows the last [LINE_WINDOW] lines of
 *    that decrypted log (the same recency bound `-t 500` gave the reference) and collects its
 *    `AverageFPS` readings through [WuwaLogParser.averageFpsSamples] — the game's own
 *    reported series, not a logcat pattern match.
 *  - **Ladder direction.** [PRESET_ORDER] is the reference's own, heaviest preset first, so
 *    "step down" means index+1 exactly as there. It is the generator's
 *    [WuWaConfigGenerator.PRESET_ORDER] reversed **except for one pair**: the reference's
 *    ladder ranks `endurance` above `performance`, while the generator's own `detail` ranks
 *    (endurance 1, performance 2) rank performance the heavier preset. That disagreement is
 *    inside the reference itself, between its tuner and its generator; it is kept verbatim
 *    and stated here rather than reconciled, because "fixing" either side would invent an
 *    agreement nobody established.
 *  - **Option escalation reaches live switches.** The reference's `adjustOptionsForFps`
 *    carried a comment that its `shadowOverride`/`texOverride` were dead fields; every toggle
 *    the tiers touch here (`disableSSR`/`disableBloom`/`disableRadialBlur`/
 *    `disableAutoExposure`) is consumed by [WuWaConfigGenerator]'s builders, so each tier
 *    actually changes the next round's deployed files instead of re-measuring an identical
 *    config.
 *  - **Persistence takes a File.** The reference reached for an app singleton for its
 *    state file; here the caller supplies the store file, the same shape as
 *    [WuwaDeployHistoryStore].
 */
object WuwaBenchmarkTuner {

    /**
     * The loop's stages. `DEPLOYING` and `CAPTURING` are transient (a coroutine is running);
     * a persisted state found in either on startup is demoted to `WAITING_FOR_PLAY`, because
     * a deploy or measurement interrupted by process death must not resume mid-write — the
     * user re-confirms they played instead.
     */
    enum class TunerStage { IDLE, DEPLOYING, WAITING_FOR_PLAY, CAPTURING, COMPLETE }

    /** One measured round: what was deployed and what the game's log said about it. */
    data class RoundResult(
        val round: Int,
        val preset: String,
        val avgFps: Float,
        val minFps: Float,
        val stabilityPct: Float
    )

    /** A round's measurement — the reference's `BenchmarkResult`, plus the sample count. */
    data class BenchmarkResult(
        val avgFps: Float,
        val minFps: Float,
        val stabilityPct: Float,
        /**
         * How many `AverageFPS` readings the numbers came from. A one-sample round reads as
         * min = avg and stability 100% without having measured anything, so the count is
         * carried (and shown) rather than hidden inside the averages.
         */
        val sampleCount: Int
    )

    data class TunerState(
        val stage: TunerStage = TunerStage.IDLE,
        val round: Int = 1,
        val preset: String = "balanced",
        val options: WuWaConfigGenerator.Options = WuWaConfigGenerator.Options(),
        val targetFps: Int = 60,
        val results: List<RoundResult> = emptyList(),
        val finalPreset: String? = null,
        val error: String? = null
    )

    /** Heaviest preset first, the reference's own order — "down the ladder" is index+1. */
    val PRESET_ORDER = listOf(
        "cinematic", "ultra", "high", "balanced", "competitive", "endurance", "performance", "potato"
    )

    /** The reference stops after five rounds (`round >= 5`, hard-coded there). */
    const val MAX_ROUNDS = 5

    /** The reference's `logcat -t 500` recency bound, applied to the decrypted log's lines. */
    const val LINE_WINDOW = 500

    private val gson = Gson()

    /** Best-effort like the reference: a corrupt or absent file means "never started". */
    fun loadState(storeFile: File): TunerState? = try {
        if (!storeFile.exists()) null else gson.fromJson(storeFile.readText(), TunerState::class.java)
    } catch (_: Exception) {
        null
    }

    /**
     * Best-effort like the reference: a failed save must not kill the round in flight — the
     * cost is only that a process death restarts from `WAITING_FOR_PLAY`. The write is
     * temp-sibling-then-rename so a crash mid-save never leaves a truncated state file.
     */
    fun saveState(state: TunerState, storeFile: File) {
        try {
            val tmp = File(storeFile.parentFile, "${storeFile.name}.tmp-${System.nanoTime()}")
            tmp.writeText(gson.toJson(state))
            if (!tmp.renameTo(storeFile)) {
                tmp.delete()
                storeFile.writeText(gson.toJson(state))
            }
        } catch (_: Exception) {
        }
    }

    fun clearState(storeFile: File) {
        try {
            storeFile.delete()
        } catch (_: Exception) {
        }
    }

    /**
     * Measures a decrypted `Client.log`. Only the last [LINE_WINDOW] lines are read — the
     * game appends to the file as it plays, so the tail is the session that was just played
     * and earlier sessions' samples must not widen min/stability. null means no
     * `AverageFPS` reading exists in the window (nothing was played, or the log could not be
     * read) — never a zero, which the device never reported.
     */
    fun parseFpsSamples(decryptedLogText: String): BenchmarkResult? {
        val windowed = decryptedLogText.lines().takeLast(LINE_WINDOW).joinToString("\n")
        val fpsValues = WuwaLogParser.averageFpsSamples(windowed)
        if (fpsValues.isEmpty()) return null
        val avg = fpsValues.average().toFloat()
        val min = fpsValues.min()
        val stable = fpsValues.count { it >= avg * 0.8f }.toFloat() / fpsValues.size * 100f
        return BenchmarkResult(avgFps = avg, minFps = min, stabilityPct = stable, sampleCount = fpsValues.size)
    }

    fun pickPresetForFps(
        currentPreset: String,
        avgFps: Float,
        targetFps: Int
    ): String {
        if (targetFps <= 0) return currentPreset
        val idx = PRESET_ORDER.indexOf(currentPreset)
        // A preset outside the ladder (the chip row is data, so one could appear) must not be
        // "stepped down" into cinematic — bail out and keep the current one.
        if (idx < 0) return currentPreset
        if (avgFps >= targetFps && idx > 0) {
            val stepUp = (avgFps - targetFps) / targetFps
            return if (stepUp > 0.15f && idx > 0) PRESET_ORDER[idx - 1] else currentPreset
        }
        if (avgFps < targetFps * 0.85f && idx < PRESET_ORDER.lastIndex) {
            return PRESET_ORDER[idx + 1]
        }
        return currentPreset
    }

    fun adjustOptionsForFps(
        current: WuWaConfigGenerator.Options,
        avgFps: Float,
        targetFps: Int
    ): WuWaConfigGenerator.Options {
        if (avgFps >= targetFps) return current
        val gap = targetFps - avgFps
        var adjusted = current
        if (gap > 15 && !adjusted.disableSSR) {
            adjusted = adjusted.copy(disableSSR = true)
        } else if (gap > 10 && !adjusted.disableBloom) {
            adjusted = adjusted.copy(disableBloom = true)
        } else if (gap > 8 && !adjusted.disableRadialBlur) {
            adjusted = adjusted.copy(disableRadialBlur = true)
        } else if (gap > 5 && !adjusted.disableAutoExposure) {
            adjusted = adjusted.copy(disableAutoExposure = true)
        }
        return adjusted
    }
}
