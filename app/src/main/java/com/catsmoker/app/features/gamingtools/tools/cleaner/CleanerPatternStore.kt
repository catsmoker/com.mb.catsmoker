package com.catsmoker.app.features.gamingtools.tools.cleaner

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The cleaner's user-editable keep and clean rules, persisted so they survive process death.
 *
 * The persistence shape follows the reference cleaner's `PreferenceRepository` (read in full):
 * two string-sets, one per list. The reference also keeps an "on" variant of each (`whitelistOn` /
 * `blacklistOn`) because its UI distinguishes a default list from an enabled one; here there is
 * no default list to mirror — the built-in patterns live in [CleaningFeature] as code — so the
 * user's set is the whole story and one key per list is enough.
 *
 * Keys are deliberately namespaced with `cleaner_` because SharedPreferences stores are shared
 * sloppily across this app's features and a bare `whitelist` would be a collision waiting to
 * happen.
 *
 * Matching semantics live in [CleanerPatternMatcher]; this class is storage only.
 */
@Singleton
class CleanerPatternStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Paths or bare names the scan must never claim, whatever category matched. Keep rules are
     * judged before every other rule, so a user entry here overrides even the built-in
     * blacklist.
     */
    fun getKeepEntries(): Set<String> =
        prefs.getStringSet(KEY_KEEP, emptySet()) ?: emptySet()

    /**
     * Regular-expression patterns matched against the whole path; anything matching is claimed
     * as junk even when no built-in rule would. Written case-sensitively because that is how
     * they are matched.
     */
    fun getCleanPatterns(): Set<String> =
        prefs.getStringSet(KEY_CLEAN, emptySet()) ?: emptySet()

    /** @return the keep list after the change, so the caller does not have to re-read it. */
    fun addKeepEntry(entry: String): Set<String> {
        val next = getKeepEntries() + entry.trim()
        prefs.edit { putStringSet(KEY_KEEP, next) }
        return next
    }

    /** @return the keep list after the change. */
    fun removeKeepEntry(entry: String): Set<String> {
        val next = getKeepEntries() - entry
        prefs.edit { putStringSet(KEY_KEEP, next) }
        return next
    }

    /**
     * @return the pattern list after the change, or null when [pattern] is not valid regular
     *   expression text — rejected here so a pattern that would make every future scan throw
     *   never reaches storage.
     */
    fun addCleanPattern(pattern: String): Set<String>? {
        val trimmed = pattern.trim()
        if (!CleanerPatternMatcher.isValidBlacklistPattern(trimmed)) return null
        val next = getCleanPatterns() + trimmed
        prefs.edit { putStringSet(KEY_CLEAN, next) }
        return next
    }

    /** @return the pattern list after the change. */
    fun removeCleanPattern(pattern: String): Set<String> {
        val next = getCleanPatterns() - pattern
        prefs.edit { putStringSet(KEY_CLEAN, next) }
        return next
    }

    private companion object {
        const val PREFS_NAME = "cleaner_patterns"
        const val KEY_KEEP = "cleaner_keep_entries"
        const val KEY_CLEAN = "cleaner_clean_patterns"
    }
}
