package com.catsmoker.app.features.gamingtools.engine

/**
 * Classifies packages for the ART dexopt sweep's scope decision — which apps are worth a compile
 * command and which are only going to burn a slot.
 *
 * Ported from `referance/gamingtools/art/.../data/util/PackageClassifier.kt` (read in full before
 * this file was written). What the sweep used to get wrong without it: the eligibility gate kept
 * every non-system package, and both classes below slipped through —
 *
 * - **Overlay/RRO packages** carry no meaningful dex; the platform refuses or no-ops a compile of
 *   them, so each one burned a slot and sometimes landed in the failure count on its own refusal.
 * - **Never-opened apps** sit at status `verify` — no runtime profile yet — and a `speed-profile`
 *   compile of them produces nothing the platform's own first-use dexopt would not redo. They used
 *   to be "compiled", counted as improved, and had nothing to show for it.
 *
 * Both are skip reasons, and the house rule applies to each: "nothing to do" is kept distinct from
 * "already done" and from "failed", per app, in the sweep log.
 *
 * Deliberately not ported from the reference:
 * - its per-package fallback chain (`dumpsys package <pkg>`, `cmd package compile --check`, OAT
 *   file scans). The reference starts from bare package names and must shell out per suspect; this
 *   engine already holds [android.content.pm.ApplicationInfo] — the APK's own path is
 *   [android.content.pm.ApplicationInfo.sourceDir] — and reads statuses from one global
 *   `dumpsys package dexopt` call, so the cheap channels here answer without a single extra fork.
 * - the reference's session cache of just-optimized packages (same reason: no per-app queries).
 * - its `SkipReason.SystemApp` — declared there but unused by it; this app's FLAG_SYSTEM gate in
 *   `eligibleBoosterPackages` already scopes system apps out, except games the user added
 *   themselves. Persistent apps are all system apps, so that question is answered by the same
 *   gate: the reference applies no persistent-specific rule and neither does this app.
 */
object BoosterPackageClassifier {

    /**
     * The overlay install directories the reference looks for, in its order. Its dump checks are
     * the same list prefixed with `codepath=` / `resourcepath=`; here the list also serves the
     * app-side channel, where [android.content.pm.ApplicationInfo.sourceDir] is the code path
     * itself and can be tested as a prefix directly.
     */
    private val OVERLAY_PATH_PREFIXES = listOf(
        "/product/overlay/",
        "/system/overlay/",
        "/vendor/overlay/",
        "/odm/overlay/",
        "/overlay/"
    )

    /**
     * Whether the package is very likely an overlay / RRO style artifact.
     *
     * Three signals, the reference's own, ORed together (the name signal stays in play even when a
     * dump is available — its choice, kept verbatim):
     * - **Name**: contains `auto_generated_rro`, `.rro`, `rro_`, `overlay` or plain `rro`
     *   (case-insensitive). The bare `rro` substring makes this a heuristic — a user app named
     *   e.g. "rromusic" would be classified overlay-like on name alone. The reference accepts
     *   that trade for a name-based fallback and so does this port; the APK-path and dump
     *   signals below are the definitive ones.
     * - **APK path** (this app's cheap channel): [sourceDir] under one of the overlay install
     *   directories — the same paths the reference greps for in a dump, read straight off the
     *   ApplicationInfo with no shell call.
     * - **Dump**: `codepath=`/`resourcepath=` under those directories, or the definitive
     *   `overlaytarget=` marker, from `dumpsys package <pkg>` output when a caller has it.
     *
     * @param packageName the package's name.
     * @param sourceDir the package's base APK path, or null when unknown.
     * @param dumpsysPackageOutput raw `dumpsys package <packageName>` output, or null when the
     *   caller has none — classification then rests on the name and path signals alone.
     */
    fun isOverlayLike(
        packageName: String,
        sourceDir: String?,
        dumpsysPackageOutput: String? = null
    ): Boolean {
        val lowerPkg = packageName.lowercase()

        // Name-based signal (fallback). The individual markers are the reference's, verbatim.
        val nameSuggestsOverlay = lowerPkg.contains("auto_generated_rro") ||
            lowerPkg.contains(".rro") ||
            lowerPkg.contains("rro_") ||
            lowerPkg.contains("overlay") ||
            lowerPkg.contains("rro")

        val lowerSource = sourceDir?.lowercase()
        val sourcePathIsOverlay = lowerSource != null &&
            OVERLAY_PATH_PREFIXES.any { lowerSource.startsWith(it) }

        val dump = dumpsysPackageOutput?.lowercase().orEmpty()
        if (dump.isBlank()) return sourcePathIsOverlay || nameSuggestsOverlay

        // Path-based signals, matched the way the dump spells them.
        val hasOverlayPath = OVERLAY_PATH_PREFIXES.any { prefix ->
            dump.contains("codepath=$prefix") || dump.contains("resourcepath=$prefix")
        }

        // Overlay target markers are definitive.
        val hasOverlayTarget = dump.contains("overlaytarget=")

        return hasOverlayTarget || hasOverlayPath || sourcePathIsOverlay || nameSuggestsOverlay
    }
}
