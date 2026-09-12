package com.catsmoker.app.features.gamingtools.engine

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * One finished dexopt sweep, as the device actually reported it.
 *
 * Every count comes from `BoosterState` at the moment the run ended — a tally of what the
 * shell answered, never of attempts made — and [outcome] keeps the BoosterOutcome distinction
 * the run itself makes: "cancelled after 12" and "completed" are different facts about the
 * same sweep.
 */
data class BoosterRun(
    /** Wall-clock start of the sweep. */
    val startedAt: Long,
    val durationMs: Long,
    /** The compile filter the sweep requested (`speed-profile` and friends). */
    val mode: String,
    val optimized: Int,
    val skipped: Int,
    val failed: Int,
    /** `completed` / `cancelled` / `failed` — the run's own outcome, spelled out. */
    val outcome: String
)

/**
 * Persistent history of dexopt sweeps, so "what did the last boost actually do" survives
 * process death — the in-memory booster log does not.
 *
 * From the reference project's `OptimizationLogger`
 * (`referance/gamingtools/art`, `com.tony.appbooster.data.util.OptimizationLogger`), which
 * caps its structured entries at 100 "to prevent memory bloat". The cap here is smaller and
 * on disk as well: a recurring sweep would otherwise grow this file forever, and with the
 * oldest aged out, the same way the reference chose to age its oldest entries out.
 *
 * Unavailable runs are deliberately not recorded — a sweep that never started (no privilege,
 * no apps) has nothing to look back on, and a history full of "could not run" would bury the
 * runs that did.
 */
class BoosterHistoryStore(context: Context) {

    private val file = File(context.filesDir, "booster_history.json")
    private val gson = Gson()

    /** The saved runs, oldest first — the caller decides which end is "newest" for the UI. */
    fun load(): List<BoosterRun> {
        val type = object : TypeToken<List<BoosterRun>>() {}.type
        return runCatching {
            if (!file.exists()) return emptyList()
            gson.fromJson<List<BoosterRun>>(file.readText(), type) ?: emptyList()
        }.getOrDefault(emptyList())
    }

    /**
     * Appends one run and caps the file at [MAX_RUNS], newest kept.
     *
     * A failed write is swallowed on purpose: history must never be able to fail the sweep it
     * is describing. The in-memory copy is the caller's to update.
     */
    fun record(run: BoosterRun) {
        runCatching {
            val updated = (load() + run).takeLast(MAX_RUNS)
            file.writeText(gson.toJson(updated))
        }
    }

    private companion object {
        private const val MAX_RUNS = 20
    }
}
