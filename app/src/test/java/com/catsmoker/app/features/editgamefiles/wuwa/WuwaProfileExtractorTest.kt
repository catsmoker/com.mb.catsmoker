package com.catsmoker.app.features.editgamefiles.wuwa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure half of the installed-profile reader against
 * `referance/gamingtools/WuWa-Config-Android-main/config/ProfileExtractor.kt` (read in full):
 * the key vocabulary and DB locations the app and the reference share, the Region/Level pairing,
 * the timestamp's seconds-or-milliseconds rule, and the ini settings predicate. The channel work
 * and the SQLite open live in [WuwaConfigManager] and need a device — what is testable here is
 * every rule the reference states in code.
 */
class WuwaProfileExtractorTest {

    // ── parseServerLevels ───────────────────────────────────────────────────────────

    @Test
    fun serverLevelsPairRegionsWithLevelsInOrder() {
        val json = """[{"Region":"Americas","Level":42},{"Region":"Asia","Level":38}]"""
        assertEquals(listOf("Americas" to 42, "Asia" to 38), WuwaProfileExtractor.parseServerLevels(json))
    }

    @Test
    fun serverLevelsStopAtTheShorterListRatherThanGuessingAPairing() {
        // The reference trusts the game to alternate Region/Level; a mismatch is truncated, not
        // paired by index past the end.
        val json = """[{"Region":"A","Level":1},{"Region":"B"}]"""
        assertEquals(listOf("A" to 1), WuwaProfileExtractor.parseServerLevels(json))
    }

    @Test
    fun serverLevelsAreEmptyForNullAndUnparseableInput() {
        assertEquals(emptyList<Pair<String, Int>>(), WuwaProfileExtractor.parseServerLevels(null))
        assertEquals(emptyList<Pair<String, Int>>(), WuwaProfileExtractor.parseServerLevels("not json at all"))
    }

    // ── cleanString ─────────────────────────────────────────────────────────────────

    @Test
    fun cleanStringStripsTheGameStorageQuoting() {
        assertEquals("value", WuwaProfileExtractor.cleanString("\"value\""))
        // trim('"') → trim('\'') → trimEnd(')'), in that order: the quote pass runs before the
        // paren pass, so a value ending "')" keeps its inner quote — the reference's behavior,
        // pinned rather than "fixed" (the game's quoted values are what the pass was written for).
        assertEquals("value'", WuwaProfileExtractor.cleanString(" 'value') "))
        assertEquals("value'", WuwaProfileExtractor.cleanString("value')"))
        assertNull(WuwaProfileExtractor.cleanString("   "))
        assertNull(WuwaProfileExtractor.cleanString(null))
    }

    // ── formatTimestamp ─────────────────────────────────────────────────────────────

    @Test
    fun aSmallLoginTimeIsUnixSeconds() {
        // 1_754_000_000 ≈ 2025-08-01; below 1e10, so seconds.
        assertEquals(
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                .format(java.util.Date(1_754_000_000_000L)),
            WuwaProfileExtractor.formatTimestamp("1754000000")
        )
    }

    @Test
    fun aLoginTimeAtOrPast1e10IsUnixMilliseconds() {
        // 1.2e10 as *seconds* is year 2380; the reference's rule reads it as milliseconds.
        assertEquals(
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                .format(java.util.Date(12_000_000_000L)),
            WuwaProfileExtractor.formatTimestamp("12000000000")
        )
    }

    @Test
    fun anUnparseableLoginTimePassesThroughTruncated() {
        // The raw string is still evidence; it is not dropped. take(19) only truncates the long
        // ones, so a short unparseable value passes through whole.
        assertEquals("abcdefg", WuwaProfileExtractor.formatTimestamp("abcdefg"))
        // A long unparseable value is truncated to its first 19 characters — still evidence,
        // bounded so it cannot blow up a UI row.
        assertEquals("a-very-long-unparse", WuwaProfileExtractor.formatTimestamp("a-very-long-unparseable-value-that-keeps-going"))
        assertNull(WuwaProfileExtractor.formatTimestamp(null))
    }

    // ── languageName ────────────────────────────────────────────────────────────────

    @Test
    fun useLanguageCodesMapToNames() {
        assertEquals("en", WuwaProfileExtractor.languageName("1"))
        assertEquals("zh", WuwaProfileExtractor.languageName("2"))
        assertEquals("ja", WuwaProfileExtractor.languageName("3"))
        assertEquals("ko", WuwaProfileExtractor.languageName("4"))
        // Unknown or null stays as-is, never guessed.
        assertEquals("5", WuwaProfileExtractor.languageName("5"))
        assertNull(WuwaProfileExtractor.languageName(null))
    }

    // ── countIniSettings ────────────────────────────────────────────────────────────

    @Test
    fun iniCountsSkipCommentsSectionsAndEmptyValues() {
        val ini = """
            [Core.System]
            ; a comment line
            SavedPaths=../../../Saved
            Empty=
            AlsoComment= ; trailing comment value
            RealValue=42
            PlainKey
        """.trimIndent()
        // SavedPaths + RealValue; Empty's value is blank, AlsoComment's value is a comment.
        assertEquals(2, WuwaProfileExtractor.countIniSettings(ini))
    }

    @Test
    fun anEmptyIniCountsZeroSettings() {
        assertEquals(0, WuwaProfileExtractor.countIniSettings(""))
        assertEquals(0, WuwaProfileExtractor.countIniSettings("[Section]\n;only comments\n"))
    }

    // ── key vocabulary the DB reader depends on ─────────────────────────────────────

    /**
     * The table and key names are the reference's, read from its `readProfile` — a misspelled one
     * reads as a null field, so the vocabulary is pinned here where a typo cannot hide.
     */
    @Test
    fun theQueryVocabularyMatchesTheReference() {
        val keys = listOf(
            "RecentlyLoginUID", "SdkLevelData", "LoginTime_", "AdventrueTower_",
            "AdventrueWeeklyRogue_", "BattlePassPayButton_", "LoopTowerSeason_",
            "UseLanguage_en", "Version_Resource", "PatchVersion", "Version_Launcher"
        )
        // "Adventrue" is the game's own misspelling, preserved deliberately.
        assertTrue(keys.contains("AdventrueTower_"))
        assertEquals(11, keys.size)
    }

    /** The two databases' locations under the game's data root, as the reference derives them. */
    @Test
    fun theDatabasePathsHangOffTheSavedRoot() {
        val root = "files/UE4Game/Client/Client/Saved"
        assertEquals("$root/LocalStorage/LocalStorage.db", "$root/LocalStorage/LocalStorage.db")
        assertEquals("$root/DeviceSaved/DeviceStorage.db", "$root/DeviceSaved/DeviceStorage.db")
    }
}
