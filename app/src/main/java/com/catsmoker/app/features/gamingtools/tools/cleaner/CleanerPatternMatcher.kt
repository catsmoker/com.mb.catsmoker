package com.catsmoker.app.features.gamingtools.tools.cleaner

/**
 * The cleaner's user-defined rule matching, kept in one pure object so the semantics can be pinned
 * by unit tests without a device.
 *
 * Both rule shapes follow the reference cleaner's `FileScanner` (read in full before this file was
 * written):
 * - **Keep rules** (`isWhiteListed`): an entry wins when it equals the file's absolute path, or
 *   equals the file's bare name ignoring case. No substring, no glob — the user names what to keep.
 * - **Clean rules** (`isBlackListed`): an entry is regular-expression text matched against the
 *   WHOLE path (`Regex.matches`), case-sensitively — the reference compiles its stored patterns
 *   with a plain `String.toRegex()`. A pattern meant to match anywhere inside the path has to say
 *   so itself, e.g. `.*oldgame.*`.
 *
 * Deliberately not ported, and not claimed either:
 * - The reference's editing UI (`BlacklistFragment` / `WhitelistFragment`). Those classes were
 *   trimmed out of the reference tree, so only the match rules above and the persisted string-set
 *   shape (`PreferenceRepository`'s `whitelist` / `blacklist` keys) were available to agree with —
 *   nothing here asserts anything about how the fragments presented or edited the lists.
 * - The reference's habit of *writing* auto-whitelisted folders into the persisted whitelist
 *   mid-scan (`autoWhiteList`). This app's built-in fragments are computed per scan and the
 *   user's own list is never mutated behind their back.
 */
object CleanerPatternMatcher {

    /**
     * Installer extensions, verbatim from the reference cleaner's `Constants.filter_apkFiles`
     * (including `.aab`, which the reference itself flags with "should this be considered?" —
     * kept anyway, because an Android App Bundle found loose in shared storage is exactly as
     * disposable as the other three).
     */
    private val INSTALLER_EXTENSIONS = listOf(".aab", ".apk", ".apks", ".apkm")

    /**
     * @return true when one of [entries] keeps [absolutePath] — by full-path equality or by
     *   case-insensitive bare-name equality, the reference's two tests in its order.
     */
    fun isWhitelisted(absolutePath: String, name: String, entries: Set<String>): Boolean =
        entries.any { it == absolutePath || it.equals(name, ignoreCase = true) }

    /**
     * Compiles keep-out patterns once per scan. Invalid regular expressions are skipped rather
     * than thrown: [CleanerPatternStore.addBlacklist] rejects them at write time, so one here can
     * only mean an entry that arrived some other way — and a bad pattern must never take the
     * whole scan down with it.
     */
    fun compileBlacklist(entries: Set<String>): List<Regex> =
        entries.mapNotNull { entry ->
            try {
                entry.toRegex()
            } catch (_: Exception) {
                null
            }
        }

    /**
     * @return true when [absolutePath] matches any compiled clean rule. Whole-path match and
     *   case-sensitive, as the reference matches its blacklist.
     */
    fun matchesBlacklist(absolutePath: String, patterns: List<Regex>): Boolean =
        patterns.any { it.matches(absolutePath) }

    /** @return true when [pattern] is regular-expression text that can actually be compiled. */
    fun isValidBlacklistPattern(pattern: String): Boolean = try {
        pattern.toRegex()
        true
    } catch (_: Exception) {
        false
    }

    /**
     * @return true when [name] ends with an installer extension, the reference's
     *   `filter_apkFiles` applied the way its `getRegexForFile` does: lowercased, anchored at the
     *   end of the path. A `.apks` file does not trip the `.apk` test — the extension is checked
     *   as a whole suffix, not a prefix.
     */
    fun isInstallerFile(name: String): Boolean {
        val lower = name.lowercase()
        return INSTALLER_EXTENSIONS.any { lower.endsWith(it) }
    }
}
