package com.catsmoker.app.features.gamingtools.tools.booster

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The recurring dexopt sweep's persisted settings: whether it is on, and how far apart runs are.
 *
 * Its own store, not one of the eight existing ones — none of them has anything to do with
 * scheduling, and the house rule is a per-feature file. The worker re-reads these on every run
 * rather than receiving them in the work request's input data, so an interval change made after
 * a run was queued applies to that run without re-enqueueing it.
 */
@Singleton
class DexoptScheduleStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("dexopt_schedule_prefs", Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    /** Hours between scheduled runs. Clamped to [INTERVAL_CHOICES] on write, so a read is always one of them. */
    fun getIntervalHours(): Int = prefs.getInt(KEY_INTERVAL_HOURS, DEFAULT_INTERVAL_HOURS)

    fun setEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_ENABLED, enabled) }
    }

    fun setIntervalHours(hours: Int) {
        prefs.edit { putInt(KEY_INTERVAL_HOURS, INTERVAL_CHOICES.firstOrNull { it >= hours } ?: INTERVAL_CHOICES.last()) }
    }

    companion object {
        /**
         * The sweep the schedule runs: `speed`, the same one fixed setting the manual sweep uses,
         * and never with `-f` — the whole point of recurring is to catch apps the phone has not
         * compiled yet (new installs, updates), which the platform itself skips when already done.
         * A forced schedule would redo every app every night for nothing.
         */
        const val SCHEDULED_MODE = "speed"

        const val DEFAULT_INTERVAL_HOURS = 24

        /**
         * What the UI offers. The shortest is 12 hours, not shorter, because a sweep warms the
         * phone up and takes minutes — anything more frequent is a second daily dose of the cost
         * the card already states, with nothing new compiled the second time.
         */
        val INTERVAL_CHOICES = listOf(12, 24, 72, 168)

        private const val KEY_ENABLED = "enabled"
        private const val KEY_INTERVAL_HOURS = "interval_hours"
    }
}
