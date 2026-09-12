package com.catsmoker.app.features.editgamefiles.wuwa

/**
 * The player/device identity half of an installed WuWa setup: what the game's own databases and
 * config files say, merged with the log-derived device facts.
 *
 * Ported from `referance/gamingtools/WuWa-Config-Android-main/config/ProfileExtractor.kt`'s
 * `readProfile` (read in full before this file was written). The pieces kept verbatim:
 *
 * - the `LocalStorage` table query and every key it reads — `RecentlyLoginUID`, `SdkLevelData`,
 *   `LoginTime_<uid>`, `AdventrueTower_<uid>`, `AdventrueWeeklyRogue_<uid>`,
 *   `BattlePassPayButton_<uid>`, `LoopTowerSeason_<uid>` from LocalStorage.db (the `Adventrue`
 *   spelling is the game's own), `UseLanguage_en`, `Version_Resource`, `PatchVersion`,
 *   `Version_Launcher` from DeviceStorage.db;
 * - the DB locations — `Saved/LocalStorage/LocalStorage.db` and `Saved/DeviceSaved/DeviceStorage.db`,
 *   both derived from the log directory's parent exactly the way the reference derives them;
 * - [parseServerLevels]' paired Region/Level regex scan, [cleanString]'s quote/paren stripping,
 *   [formatTimestamp]'s seconds-or-milliseconds heuristic (a value past ~year 2286 *in seconds*
 *   is read as milliseconds), and [languageName]'s 1–4 mapping;
 * - [countIniSettings]' predicate for what counts as one setting in a deployed ini — a line with
 *   an `=`, a non-comment key, and a value that is present and not itself a comment.
 *
 * Deliberately not ported: `readBattleStats`/`parseBattleStatsLines` (the same reading this app's
 * log parser already declined, for the same reason) and the reference's pull mechanism — it reads
 * the databases as `base64 <path>` through shell stdout, where GNU base64 line-wraps at 76
 * characters and a decode has to hope the device's base64 does not. This app already owns
 * byte-exact file channels ([WuwaConfigManager]'s shell/ADB `readBytes` and the SAF provider
 * read), so the databases are pulled through those instead.
 *
 * Everything here is pure and JVM-testable; the channel work and the SQLite open live in
 * [WuwaConfigManager.readInstalledProfile], which assembles the [InstalledProfile].
 */
object WuwaProfileExtractor {

    /**
     * One merged reading of an installed game. Every nullable field is null because it could not
     * be read — never a placeholder — and [log] is the only half that can be entirely absent
     * (the log exists only after a play session, while the databases exist from first launch).
     */
    data class InstalledProfile(
        val uid: String?,
        val server: String?,
        val playerLevel: Int?,
        /** Every (region, level) the game recorded, server first — the reference's own shape. */
        val serverLevels: List<Pair<String, Int>>,
        val lastLoginTime: String?,
        val towerFloor: Int?,
        val weeklyRogueScore: Int?,
        val battlePassPurchased: Boolean?,
        val loopTowerSeason: Int?,
        val gameVersion: String?,
        val patchVersion: String?,
        val launcherVersion: String?,
        val language: String?,
        /** Per-ini count of settings the files on the device actually carry, by file name. */
        val iniSettingCounts: Map<String, Int>,
        /** The log-derived device facts, or null when Client.log could not be read. */
        val log: WuwaLogParser.LogInfo?,
        /** Which channel read the databases; null = neither database could be read. */
        val dbChannel: String?,
        /** Which channel read the log; null = no log read. */
        val logChannel: String?
    )

    /**
     * The regions and levels recorded in `SdkLevelData`, paired in order — the reference's own
     * regex scan, which trusts the game to emit Region and Level alternating and stops at the
     * shorter of the two lists rather than guessing a pairing.
     */
    fun parseServerLevels(json: String?): List<Pair<String, Int>> {
        if (json == null) return emptyList()
        val results = mutableListOf<Pair<String, Int>>()
        try {
            // The reference writes these as raw strings; the pattern's own quotes inside a
            // """...""" literal depend on the lexer's tie-breaking at a 4-quote run, so they are
            // spelled as escaped plain strings here — same patterns, no ambiguity to resolve.
            val regions = Regex("\"Region\"\\s*:\\s*\"([^\"]+)\"").findAll(json).toList()
            val levels = Regex("\"Level\"\\s*:\\s*(\\d+)").findAll(json).toList()
            for (i in 0 until minOf(regions.size, levels.size)) {
                val region = regions[i].groupValues[1]
                val level = levels[i].groupValues[1].toIntOrNull() ?: continue
                results.add(region to level)
            }
        } catch (_: Exception) {
        }
        return results
    }

    /**
     * Strips the quoting the game's storage layer leaves on values — surrounding quotes, then a
     * stray trailing paren — and returns null for blank, exactly the reference's `cleanString`.
     */
    fun cleanString(raw: String?): String? =
        raw?.trim()?.trim('"')?.trim('\'')?.trimEnd(')')?.takeIf { it.isNotBlank() }

    /**
     * `LoginTime_<uid>` as a readable timestamp. The value is unix seconds or unix milliseconds
     * with no marker; the reference's rule (kept exactly) is that a value at or past 1e10 cannot
     * be seconds without being year 2286, so it is read as milliseconds. Anything unparseable is
     * passed through truncated rather than dropped — the raw string is still evidence.
     */
    fun formatTimestamp(ts: String?): String? {
        if (ts == null) return null
        val cleaned = ts.takeWhile { it.isDigit() || it == '.' }
        val seconds = cleaned.toDoubleOrNull()
        if (seconds != null && seconds > 0) {
            val millis = if (seconds >= 1e10) seconds else seconds * 1000
            return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                .format(java.util.Date(millis.toLong().coerceAtLeast(0L)))
        }
        return ts.take(19)
    }

    /** `UseLanguage_en` code to a language name; unknown or null stays as-is, not guessed. */
    fun languageName(code: String?): String? = when (code) {
        "1" -> "en"
        "2" -> "zh"
        "3" -> "ja"
        "4" -> "ko"
        else -> code
    }

    /**
     * How many settings one deployed ini carries. Comments (`;`), section headers, and keys with
     * empty or comment values are not settings — the reference's own predicate, and the number a
     * user compares against "what the generator promised to write".
     */
    fun countIniSettings(text: String): Int =
        text.lines().count { line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith(";") || trimmed.startsWith("[")) return@count false
            val eq = trimmed.indexOf('=')
            if (eq < 0) return@count false
            val afterEq = trimmed.substring(eq + 1).trim()
            afterEq.isNotEmpty() && !afterEq.startsWith(";")
        }
}
