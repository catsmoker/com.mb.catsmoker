package com.catsmoker.app.features.gamingtools.engine

/**
 * What `pm suspend` / `pm unsuspend` actually reported, read from the exit code and the shell's
 * own words rather than from the fact that a command was issued.
 *
 * `pm` prints the verdict on one line — `Package <pkg> new suspended state: true/false` — and
 * some builds still exit 0 after refusing, so both signals are read, the same way
 * `classifyCompileOutput` reads both for `cmd package compile`. A silent exit 0 is taken at
 * face value; inventing a failure there would be as wrong as inventing a success.
 *
 * Pure so the rule is pinnable by a JVM unit test: the revert path depends on it to decide
 * which packages are actually awake again, and an unverified unsuspend is how apps end up
 * frozen after Gaming Mode is off.
 */
object SuspendVerdict {

    /**
     * Whether `pm suspend` actually froze the package: exit 0 without the shell reporting the
     * package is still unsuspended (`state: false`).
     */
    fun isSuspendConfirmed(exitCode: Int, stdout: String): Boolean =
        exitCode == 0 && !stdout.contains("state: false", ignoreCase = true)

    /**
     * Whether `pm unsuspend` actually woke the package back up: exit 0 without the shell
     * reporting the package is still suspended (`state: true`).
     */
    fun isUnsuspendConfirmed(exitCode: Int, stdout: String): Boolean =
        exitCode == 0 && !stdout.contains("state: true", ignoreCase = true)
}
