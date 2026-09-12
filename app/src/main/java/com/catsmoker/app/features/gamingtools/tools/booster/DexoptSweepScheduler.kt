package com.catsmoker.app.features.gamingtools.tools.booster

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the recurring sweep's WorkManager enrollment — the thin wrapper role the reference
 * project gives its own `WorkManagerOptimizationWorkScheduler`, except that the reference has
 * nothing periodic to wrap (its scheduler only enqueues one-time work for the visible screen),
 * so this class is where "recurring" itself lives, as this app's own addition.
 *
 * [ExistingPeriodicWorkPolicy.UPDATE] rather than REPLACE: re-applying the schedule (say, after
 * an interval change) must not restart the countdown from zero every time the screen is opened,
 * or "every 24 hours" quietly becomes "24 hours after your last visit". UPDATE adjusts the spec
 * in place and keeps the original enqueue time.
 */
@Singleton
class DexoptSweepScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /** Applies the store's current enabled/interval to WorkManager — enable, disable, or retune. */
    fun apply(store: DexoptScheduleStore) {
        val workManager = WorkManager.getInstance(context)
        if (!store.isEnabled()) {
            workManager.cancelUniqueWork(DexoptSweepWorker.UNIQUE_WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<DexoptSweepWorker>(
            store.getIntervalHours().toLong(), TimeUnit.HOURS
        )
            // A sweep warms the phone up for minutes; doing that on the last percent of battery
            // is a trade the user did not make.
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        workManager.enqueueUniquePeriodicWork(
            DexoptSweepWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    /**
     * When WorkManager currently expects the next run, or null when nothing is scheduled.
     *
     * This is WorkManager's own estimate, not a promise — it shifts when Doze defers the job, and
     * it is re-read rather than cached so the UI quotes what the scheduler believes now.
     */
    suspend fun nextScheduledRunAt(): Long? = withContext(Dispatchers.IO) {
        try {
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(DexoptSweepWorker.UNIQUE_WORK_NAME)
                .get()
                .asSequence()
                .filter { !it.state.isFinished }
                .map { it.nextScheduleTimeMillis }
                .minOrNull()
        } catch (_: Exception) {
            // WorkManager not reachable (or not yet initialized) — no estimate exists, and null
            // renders as "unknown" rather than a made-up time.
            null
        }
    }
}
