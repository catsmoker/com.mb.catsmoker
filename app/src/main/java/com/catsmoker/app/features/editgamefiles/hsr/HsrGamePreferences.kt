package com.catsmoker.app.features.editgamefiles.hsr

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Honkai: Star Rail's quality-of-life preferences, as sibling entries in the same Unity
 * `playerprefs.xml` the graphics block lives in: language (two `<string>` entries), the
 * cutscene/voice blacklists (URL-encoded JSON-array `<string>` entries), and the per-user
 * QoL ints (speed-up, auto-battle).
 *
 * Cross-checked against `referance/gamingtools/hsrgraphicdroid-main/data/GamePreferences.kt`
 * (`com.ireddragonicy.hsrgraphicdroid.data.GamePreferences`): the language code tables and
 * their XML string codes, the defaults (English text, Japanese audio), and the blacklist
 * codec — a URL-encoded `["file.usm","..."]` JSON array, parsed with the same manual split
 * the reference uses (blacklist entries are `.usm` filenames; none contain commas).
 *
 * Forcing a text/audio language the region's build does not offer in its own menu is the
 * point of the feature — the value is written directly, bypassing the in-game selector.
 */
data class HsrGamePreferences(
    var textLanguage: Int = 2,
    var audioLanguage: Int = 2,
    var videoBlacklist: List<String> = emptyList(),
    var audioBlacklist: List<String> = emptyList(),
    /** Per-user (`User_<uid>_SpeedUpOpen`); null means no last-user id was in the file. */
    var speedUpOpen: Int? = null,
    var autoBattleOpen: Int? = null
) {

    companion object {
        internal const val KEY_TEXT_LANGUAGE = "LanguageSettings_LocalTextLanguage"
        internal const val KEY_AUDIO_LANGUAGE = "LanguageSettings_LocalAudioLanguage"
        internal const val KEY_VIDEO_BLACKLIST = "App_VideoBlacklist"
        internal const val KEY_AUDIO_BLACKLIST = "App_AudioBlacklist"
        internal const val KEY_LAST_USER_ID = "App_LastUserID"
        internal const val KEY_SPEED_UP_OPEN_PREFIX = "User_"
        internal const val KEY_SPEED_UP_OPEN_SUFFIX = "_SpeedUpOpen"
        internal const val KEY_AUTO_BATTLE = "OtherSettings_AutoBattleOpen"

        /** Integer code -> display name, the reference's tables verbatim. */
        val TEXT_LANGUAGES = linkedMapOf(
            0 to "简体中文",
            1 to "繁體中文",
            2 to "English",
            3 to "日本語",
            4 to "한국어",
            5 to "Deutsch",
            6 to "Español",
            7 to "Français",
            8 to "Indonesia",
            9 to "Русский",
            10 to "Português",
            11 to "ไทย",
            12 to "Tiếng Việt"
        )

        val AUDIO_LANGUAGES = linkedMapOf(
            0 to "中文",
            1 to "English",
            2 to "日本語",
            3 to "한국어"
        )

        /** Integer code -> the string actually stored in the XML entry. */
        private val TEXT_LANGUAGE_CODES = mapOf(
            0 to "cn", 1 to "cht", 2 to "en", 3 to "jp", 4 to "kr", 5 to "de",
            6 to "es", 7 to "fr", 8 to "id", 9 to "ru", 10 to "pt", 11 to "th", 12 to "vi"
        )

        private val AUDIO_LANGUAGE_CODES = mapOf(
            0 to "cn", 1 to "en", 2 to "jp", 3 to "kr"
        )

        fun textLanguageName(code: Int): String = TEXT_LANGUAGES[code] ?: "Unknown"
        fun audioLanguageName(code: Int): String = AUDIO_LANGUAGES[code] ?: "Unknown"

        internal fun textLanguageCode(code: Int): String = TEXT_LANGUAGE_CODES[code] ?: "en"
        internal fun audioLanguageCode(code: Int): String = AUDIO_LANGUAGE_CODES[code] ?: "jp"

        internal fun textLanguageFromCode(code: String): Int =
            TEXT_LANGUAGE_CODES.entries.find { it.value == code }?.key ?: 2

        internal fun audioLanguageFromCode(code: String): Int =
            AUDIO_LANGUAGE_CODES.entries.find { it.value == code }?.key ?: 2

        /** `%5B%22file1.usm%22%2C...%5D` -> `["file1.usm", ...]`, the reference's manual parse. */
        fun parseBlacklist(encoded: String?): List<String> {
            if (encoded.isNullOrBlank()) return emptyList()
            return runCatching {
                URLDecoder.decode(encoded, "UTF-8")
                    .trim('[', ']')
                    .split(",")
                    .map { it.trim().trim('"') }
                    .filter { it.isNotBlank() }
            }.getOrDefault(emptyList())
        }

        fun encodeBlacklist(list: List<String>): String {
            if (list.isEmpty()) return URLEncoder.encode("[]", "UTF-8")
            val jsonArray = list.joinToString(",", prefix = "[", postfix = "]") { "\"$it\"" }
            return URLEncoder.encode(jsonArray, "UTF-8")
        }
    }

    fun textLanguageName(): String = textLanguageName(textLanguage)
    fun audioLanguageName(): String = audioLanguageName(audioLanguage)
}
