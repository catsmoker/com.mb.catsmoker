package com.catsmoker.app.features.gamingtools.tools.firewall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins which UIDs a revert keeps retrying.
 *
 * The disable path must retain exactly the UIDs the read-back still shows as denied: retaining
 * less forgets blocked apps, retaining more re-removes policies the user set themselves.
 */
class BackgroundDataRestrictorTest {

    @Test
    fun emptyWhenNothingWasRequested() {
        assertTrue(
            BackgroundDataRestrictor.partitionStillBlocked(
                emptySet(),
                setOf(10123, 10124)
            ).isEmpty()
        )
    }

    @Test
    fun emptyWhenEverythingLifted() {
        assertTrue(
            BackgroundDataRestrictor.partitionStillBlocked(
                setOf(10123, 10124),
                emptySet()
            ).isEmpty()
        )
    }

    @Test
    fun keepsOnlyTheStillDeniedUids() {
        assertEquals(
            setOf(10124),
            BackgroundDataRestrictor.partitionStillBlocked(
                setOf(10123, 10124),
                setOf(10124, 10555)
            )
        )
    }

    @Test
    fun emptyWhenListingIsUnreadable() {
        // Unreadable is the state in which enable() never blocks anything per-app, so there is
        // nothing provable to retain — not a reason to keep the whole request forever.
        assertTrue(
            BackgroundDataRestrictor.partitionStillBlocked(
                setOf(10123, 10124),
                null
            ).isEmpty()
        )
    }
}
