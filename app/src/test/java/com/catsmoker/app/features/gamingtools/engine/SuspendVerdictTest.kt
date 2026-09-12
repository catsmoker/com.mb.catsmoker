package com.catsmoker.app.features.gamingtools.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the `pm suspend` / `pm unsuspend` verdict rules.
 *
 * The revert path decides from these whether a package is actually awake again, so a refusal
 * the shell reports as text must read as a failure even when the exit code is 0 — otherwise
 * the package is dropped from the record while still frozen.
 */
class SuspendVerdictTest {

    // --------------------------------------------------------------- suspend

    @Test
    fun suspendConfirmedWhenSilentExitZero() {
        assertTrue(SuspendVerdict.isSuspendConfirmed(0, ""))
    }

    @Test
    fun suspendConfirmedWhenShellReportsSuspended() {
        assertTrue(
            SuspendVerdict.isSuspendConfirmed(
                0,
                "Package com.example.app new suspended state: true"
            )
        )
    }

    @Test
    fun suspendRefusedWhenShellReportsStillUnsuspended() {
        // The exact refusal shape the activation path already relied on.
        assertFalse(
            SuspendVerdict.isSuspendConfirmed(
                0,
                "Package com.example.app new suspended state: false"
            )
        )
    }

    @Test
    fun suspendRefusedWhenExitCodeIsNonZero() {
        assertFalse(SuspendVerdict.isSuspendConfirmed(1, ""))
        assertFalse(
            SuspendVerdict.isSuspendConfirmed(
                1,
                "Package com.example.app new suspended state: true"
            )
        )
    }

    // --------------------------------------------------------------- unsuspend

    @Test
    fun unsuspendConfirmedWhenSilentExitZero() {
        assertTrue(SuspendVerdict.isUnsuspendConfirmed(0, ""))
    }

    @Test
    fun unsuspendConfirmedWhenShellReportsAwake() {
        assertTrue(
            SuspendVerdict.isUnsuspendConfirmed(
                0,
                "Package com.example.app new suspended state: false"
            )
        )
    }

    @Test
    fun unsuspendRefusedWhenShellReportsStillSuspended() {
        assertFalse(
            SuspendVerdict.isUnsuspendConfirmed(
                0,
                "Package com.example.app new suspended state: true"
            )
        )
    }

    @Test
    fun unsuspendRefusedWhenExitCodeIsNonZero() {
        assertFalse(SuspendVerdict.isUnsuspendConfirmed(1, ""))
        assertFalse(
            SuspendVerdict.isUnsuspendConfirmed(
                1,
                "Package com.example.app new suspended state: false"
            )
        )
    }

    @Test
    fun verdictMatchingIsCaseInsensitive() {
        assertFalse(SuspendVerdict.isSuspendConfirmed(0, "New Suspended State: False"))
        assertFalse(SuspendVerdict.isUnsuspendConfirmed(0, "New Suspended State: True"))
    }
}
