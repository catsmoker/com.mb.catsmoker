package com.catsmoker.app.features.editgamefiles.hsr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

/**
 * Pins the HSR QoL-preferences codec against
 * `referance/gamingtools/hsrgraphicdroid-main/data/GamePreferences.kt`: the language code
 * tables and their XML string codes, the defaults, and the URL-encoded JSON-array blacklist
 * format. These are the keys the game itself reads — a renamed one would silently do nothing.
 */
class HsrGamePreferencesTest {

    @Test
    fun languageTablesMatchTheReference() {
        assertEquals(13, HsrGamePreferences.TEXT_LANGUAGES.size)
        assertEquals("English", HsrGamePreferences.TEXT_LANGUAGES[2])
        assertEquals("日本語", HsrGamePreferences.TEXT_LANGUAGES[3])
        assertEquals("Tiếng Việt", HsrGamePreferences.TEXT_LANGUAGES[12])
        assertEquals(4, HsrGamePreferences.AUDIO_LANGUAGES.size)
        assertEquals("中文", HsrGamePreferences.AUDIO_LANGUAGES[0])
        assertEquals("한국어", HsrGamePreferences.AUDIO_LANGUAGES[3])

        // Integer code -> the string stored in the XML entry, and back.
        assertEquals("en", HsrGamePreferences.textLanguageCode(2))
        assertEquals("cht", HsrGamePreferences.textLanguageCode(1))
        assertEquals("jp", HsrGamePreferences.audioLanguageCode(2))
        assertEquals(2, HsrGamePreferences.textLanguageFromCode("en"))
        assertEquals(1, HsrGamePreferences.textLanguageFromCode("cht"))
        assertEquals(3, HsrGamePreferences.audioLanguageFromCode("kr"))
        // Unknown codes fall back to the defaults (English text, Japanese audio).
        assertEquals(2, HsrGamePreferences.textLanguageFromCode("zz"))
        assertEquals(2, HsrGamePreferences.audioLanguageFromCode("zz"))
    }

    @Test
    fun blacklistCodecRoundTripsTheReferenceFormat() {
        val list = listOf("cs_010105.usm", "cs_020301.usm")
        val encoded = HsrGamePreferences.encodeBlacklist(list)

        // URL-encoded JSON array, same shape the game stores: %5B%22...%22%2C...%5D.
        assertEquals("[\"cs_010105.usm\",\"cs_020301.usm\"]", URLDecoder.decode(encoded, "UTF-8"))
        assertEquals(list, HsrGamePreferences.parseBlacklist(encoded))

        // Empty list encodes as the empty array, not as a missing entry.
        assertEquals("%5B%5D", HsrGamePreferences.encodeBlacklist(emptyList()))
        assertEquals(emptyList<String>(), HsrGamePreferences.parseBlacklist("%5B%5D"))
        assertEquals(emptyList<String>(), HsrGamePreferences.parseBlacklist(null))
        assertEquals(emptyList<String>(), HsrGamePreferences.parseBlacklist(""))
    }

    @Test
    fun xmlKeysArePinnedVerbatim() {
        // The literal entry names inside the game's playerprefs.xml, cross-checked against the
        // reference's readGamePreferences/writeLanguageSettings/writeOtherPreferences.
        assertEquals("LanguageSettings_LocalTextLanguage", HsrGamePreferences.KEY_TEXT_LANGUAGE)
        assertEquals("LanguageSettings_LocalAudioLanguage", HsrGamePreferences.KEY_AUDIO_LANGUAGE)
        assertEquals("App_VideoBlacklist", HsrGamePreferences.KEY_VIDEO_BLACKLIST)
        assertEquals("App_AudioBlacklist", HsrGamePreferences.KEY_AUDIO_BLACKLIST)
        assertEquals("App_LastUserID", HsrGamePreferences.KEY_LAST_USER_ID)
        assertEquals("OtherSettings_AutoBattleOpen", HsrGamePreferences.KEY_AUTO_BATTLE)
        // The per-user key is assembled from a prefix and suffix around the uid.
        assertEquals(
            "User_100173_SpeedUpOpen",
            "${HsrGamePreferences.KEY_SPEED_UP_OPEN_PREFIX}100173${HsrGamePreferences.KEY_SPEED_UP_OPEN_SUFFIX}"
        )
    }

    @Test
    fun defaultsAreEnglishTextAndJapaneseAudio() {
        val prefs = HsrGamePreferences()
        assertEquals(2, prefs.textLanguage)
        assertEquals(2, prefs.audioLanguage)
        assertEquals("English", prefs.textLanguageName())
        assertEquals("日本語", prefs.audioLanguageName())
        assertTrue(prefs.videoBlacklist.isEmpty())
        assertTrue(prefs.audioBlacklist.isEmpty())
    }
}
