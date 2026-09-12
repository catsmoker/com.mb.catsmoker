package com.catsmoker.app.features.gamingtools.engine

/**
 * The OEM-specific surface of Gaming Mode, kept in one pure object so the vendor vocabulary is
 * testable without a device: which preinstalled packages may be frozen for a session, which
 * `settings global` keys name the games the skin should fast-path, and the CSV append rule for
 * those keys.
 *
 * Ported from `referance/gamingtools/booster/.../gaming/OemPackageResolver.kt` and its
 * `EsportsOptimizationEngine.kt` (both read in full before this file was written); the suspend
 * list and the four whitelist keys below are theirs verbatim.
 *
 * Why a package list is needed at all: the suspend sweep's user-app half filters on
 * `FLAG_SYSTEM`, and OEM bloat is exactly that — preinstalled system packages a user-app sweep
 * can never reach. The reference's resolver exists for the same reason, and on this app the gap
 * was real: `GamingEngine.getSuspendTargets` had no path to a system package at all.
 *
 * Deliberately not ported from the reference:
 * - the `vivoOptEnabled` user toggle gating the suspend list. Gaming Mode's own activation is
 *   the opt-in here, and every other vivo write this engine makes (the whitelist appends, the
 *   `cmd thermalservice reset`) runs on the same device check without a second switch.
 * - the reference's legacy-cleanup deletes (`com.vivo.vtouch.persist`,
 *   `game_screen_resolution_switch`, `gamecube_competition_mode_state`). Those remove what
 *   *older FrameX builds* wrote; this app never wrote those keys, so there is nothing here to
 *   clean.
 */
object OemPackageResolver {

    /**
     * vivo/iQOO preinstalled packages the reference judged safe to `pm suspend` for a gaming
     * session — stores, updaters, widgets, cloud sync, its cleaner/security suite and its
     * analytics agent. The judgment is the reference's, and it is consistent with this app's own
     * `systemCritical` and `gamingDaemons` lists (the genuinely load-bearing vivo processes,
     * kept out of this set on purpose): none of these is a launcher, SystemUI, a phone/IMS
     * process or a game, so the list joins the sweep without the per-app guards the dynamic
     * package lists need.
     */
    val VIVO_SAFE_TO_SUSPEND = listOf(
        // App Stores & Updaters
        "com.vivo.appstore",
        "com.bbk.updater",
        "com.vivo.website",
        "com.vivo.cardstore",

        // UI Bloat & Background Polling
        "com.vivo.assistant",
        "com.vivo.hiboard",            // Jovi / Minus-one screen
        "com.vivo.globalsearch",
        "com.vivo.magazine",           // Lockscreen magazine
        "com.bbk.theme",               // Theme store background sync
        "com.vivo.theme.effect",
        "com.vivo.video.floating",

        // Widgets & Syncers
        "com.vivo.weather",
        "com.vivo.weather.provider",
        "com.vivo.healthwidget",
        "com.vivo.stepcount",
        "com.vivo.exhealth",
        "com.bbk.cloud",               // Vivo Cloud sync

        // Secondary Vivo Services
        "com.vivo.imanager",           // Vivo cleaner
        "com.vivo.safecenter",         // Vivo security
        "com.vivo.xspace",
        "com.vivo.doubleinstance",     // App clone daemon
        "com.vivo.musicwidgetmix",
        "com.vivo.smartshot",
        "com.vivo.nps"                 // Net Promoter Score / Analytics
    )

    const val KEY_GAME_CUBE_APPS = "game_cube_apps"
    const val KEY_SPEED_MODE_APPS = "speed_mode_apps"
    const val KEY_VIVO_HIGH_REFRESH_RATE_APPS = "vivo_high_refresh_rate_apps"
    const val KEY_VIVO_SCREEN_REFRESH_RATE_APPS_LIST = "vivo_screen_refresh_rate_apps_list"

    /**
     * The four `settings global` CSV keys the vivo skin reads to decide which apps get its
     * gaming fast-path, in the reference's order (`EsportsOptimizationEngine.vivoWhitelistKeys`):
     * game-mode membership, speed-mode membership, and the two high-refresh-rate lists that keep
     * the panel at its peak for the game. The reference appends the target game to all four;
     * this app used to write only the first two, which left the skin's refresh lists treating
     * the game as an ordinary app while Game Cube knew better.
     */
    val VIVO_GAME_WHITELIST_KEYS = listOf(
        KEY_GAME_CUBE_APPS,
        KEY_SPEED_MODE_APPS,
        KEY_VIVO_HIGH_REFRESH_RATE_APPS,
        KEY_VIVO_SCREEN_REFRESH_RATE_APPS_LIST
    )

    /**
     * vivo's own display refresh-rate switch, written beside the standard
     * `min_refresh_rate`/`peak_refresh_rate` keys during the display lock — the reference's vivo
     * branch writes this key with [VIVO_REFRESH_RATE_MODE_VALUE] there, because the skin honours
     * its own mode switch over the standard keys on some builds. What the value means on any
     * given OriginOS build is the vendor's business and is not verified on hardware here; the
     * pre-activation snapshot records whatever the device held, and deactivation puts it back.
     */
    const val KEY_VIVO_SCREEN_REFRESH_RATE_MODE = "vivo_screen_refresh_rate_mode"

    /** The value the reference writes to [KEY_VIVO_SCREEN_REFRESH_RATE_MODE] during its lock. */
    const val VIVO_REFRESH_RATE_MODE_VALUE = "1"

    /**
     * The OEM packages to suspend: the vivo list when the device is vivo/iQOO, filtered to what
     * is actually installed. The reference does this filter at its call site; here the predicate
     * comes in as a parameter so the whole object stays pure.
     *
     * A non-vivo device gets an empty list — "not applicable", not a failure.
     */
    fun packagesToSuspend(isVivoOrIqoo: Boolean, isInstalled: (String) -> Boolean): List<String> {
        if (!isVivoOrIqoo) return emptyList()
        return VIVO_SAFE_TO_SUSPEND.filter { isInstalled(it) }
    }

    /**
     * Appends [pkg] to one of the whitelist CSVs without duplicating it — moved verbatim from
     * GamingEngine's `appendToCsv`, which served the first two keys. A package already present
     * returns the list untouched; otherwise whitespace items are trimmed and empties dropped.
     *
     * The literal string `"null"` is normalized to empty first: `settings get` prints exactly
     * that for an unset key (the reference does the same normalization at its read site), and
     * appending a package to the sentinel would write `null,com.game.x` — a whitelist whose
     * first entry is not a package.
     */
    fun appendPackage(list: String, pkg: String): String {
        val items = list.split(",").map { it.trim() }.filter { it.isNotEmpty() && it != "null" }
        return if (pkg in items) list else (items + pkg).joinToString(",")
    }
}
