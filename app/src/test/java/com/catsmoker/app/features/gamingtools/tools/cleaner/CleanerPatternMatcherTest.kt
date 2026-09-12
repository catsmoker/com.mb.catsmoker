package com.catsmoker.app.features.gamingtools.tools.cleaner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the cleaner's user-rule matching against the reference cleaner's `FileScanner.kt` (read in
 * full): whitelist = whole-path or case-insensitive bare-name equality, blacklist = whole-path
 * case-sensitive regex, and the installer-extension filter from `Constants.filter_apkFiles`.
 */
class CleanerPatternMatcherTest {

    // --------------------------------------------------------------- keep rules (isWhiteListed)

    @Test
    fun anExactPathEntryKeepsTheFile() {
        // The reference compares the entry to file.absolutePath first.
        val keep = setOf("/storage/emulated/0/Download/my_installer.apk")
        assertTrue(
            CleanerPatternMatcher.isWhitelisted(
                "/storage/emulated/0/Download/my_installer.apk",
                "my_installer.apk",
                keep
            )
        )
    }

    @Test
    fun aBareNameEntryKeepsTheFileWhateverItsCase() {
        // The reference's second test: path.equals(name, ignoreCase = true). "Backup" keeps
        // "backup", "BACKUP" and "BaCkUp" alike.
        val keep = setOf("Backup")
        assertTrue(CleanerPatternMatcher.isWhitelisted("/storage/emulated/0/Backup", "Backup", keep))
        assertTrue(CleanerPatternMatcher.isWhitelisted("/anywhere/backup", "backup", keep))
        assertTrue(CleanerPatternMatcher.isWhitelisted("/anywhere/BACKUP", "BACKUP", keep))
    }

    @Test
    fun aKeepEntryIsNotASubstringMatch() {
        // The reference uses equality, not contains: "backup" does not keep "MyBackup_2024".
        // (The auto-whitelist fragments are the substring rules; user keep entries are not.)
        val keep = setOf("backup")
        assertFalse(CleanerPatternMatcher.isWhitelisted("/x/MyBackup_2024", "MyBackup_2024", keep))
    }

    @Test
    fun anEmptyKeepListKeepsNothing() {
        assertFalse(
            CleanerPatternMatcher.isWhitelisted(
                "/storage/emulated/0/Download/app.apk",
                "app.apk",
                emptySet()
            )
        )
    }

    // --------------------------------------------------------------- clean rules (isBlackListed)

    @Test
    fun aCleanPatternMatchesTheWholePathCaseSensitively() {
        // The reference compiles stored patterns with a plain toRegex() and matches the whole
        // absolute path — no anchor is added, no case folding.
        val patterns = CleanerPatternMatcher.compileBlacklist(setOf("/storage/emulated/0/Download/oldgame"))
        assertTrue(CleanerPatternMatcher.matchesBlacklist("/storage/emulated/0/Download/oldgame", patterns))
        assertFalse(CleanerPatternMatcher.matchesBlacklist("/storage/emulated/0/Download/oldgame2", patterns))
    }

    @Test
    fun aCleanPatternIsCaseSensitive() {
        val patterns = CleanerPatternMatcher.compileBlacklist(setOf("oldgame"))
        // Same letters, different case: the reference does not fold case here, so neither does
        // this port — the user writes `(?i)` in the pattern when they want it.
        assertFalse(CleanerPatternMatcher.matchesBlacklist("/x/OldGame", patterns))
    }

    @Test
    fun aCleanPatternCanMatchAnywhereBySayingSo() {
        val patterns = CleanerPatternMatcher.compileBlacklist(setOf(".*/junk/.*"))
        assertTrue(CleanerPatternMatcher.matchesBlacklist("/a/b/junk/c", patterns))
        assertFalse(CleanerPatternMatcher.matchesBlacklist("/a/b/junkette/c", patterns))
    }

    @Test
    fun anInvalidCleanPatternIsSkippedNotFatal() {
        // One bad pattern can never take the whole scan down with it.
        val patterns = CleanerPatternMatcher.compileBlacklist(setOf("[unclosed", ".*crash\\.txt$"))
        assertEquals(1, patterns.size)
        assertTrue(CleanerPatternMatcher.matchesBlacklist("/x/crash.txt", patterns))
    }

    @Test
    fun patternValidationAcceptsAndRejects() {
        assertTrue(CleanerPatternMatcher.isValidBlacklistPattern(".*\\.log$"))
        assertFalse(CleanerPatternMatcher.isValidBlacklistPattern("[unclosed"))
    }

    // --------------------------------------------------------------- installer filter (filter_apkFiles)

    @Test
    fun everyReferenceInstallerExtensionIsMatched() {
        // Verbatim from Constants.filter_apkFiles: .aab, .apk, .apks, .apkm.
        assertTrue(CleanerPatternMatcher.isInstallerFile("game.apk"))
        assertTrue(CleanerPatternMatcher.isInstallerFile("game.apks"))
        assertTrue(CleanerPatternMatcher.isInstallerFile("game.apkm"))
        assertTrue(CleanerPatternMatcher.isInstallerFile("game.aab"))
    }

    @Test
    fun installerExtensionsAreMatchedCaseInsensitively() {
        // The reference lowercases the whole path before matching its filter regexes.
        assertTrue(CleanerPatternMatcher.isInstallerFile("GAME.APK"))
    }

    @Test
    fun apksDoesNotTripTheApkTest() {
        // The extension is tested as a whole suffix: ".apks" ends with ".apks", not with ".apk".
        assertTrue(CleanerPatternMatcher.isInstallerFile("bundle.apks"))
    }

    @Test
    fun nonInstallerFilesAreNotMatched() {
        assertFalse(CleanerPatternMatcher.isInstallerFile("photo.jpg"))
        assertFalse(CleanerPatternMatcher.isInstallerFile("apk"))          // no dot at all
        assertFalse(CleanerPatternMatcher.isInstallerFile("game.apk.txt")) // suffix is .txt
    }
}
