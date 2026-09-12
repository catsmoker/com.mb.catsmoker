package com.catsmoker.app.system.config

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Appearance preferences: theme mode and app language.
 *
 * A plain singleton rather than a Hilt binding because both consumers live outside the
 * object graph's reach: [com.catsmoker.app.system.CatsmokerApp.attachBaseContext] and
 * [com.catsmoker.app.system.MainActivity.attachBaseContext] run before injection exists,
 * and the theme is read inside `setContent` before any ViewModel is created. Own prefs
 * file (`appearance_prefs`) — new features get their own file rather than `app_prefs`.
 */
object AppearanceStore {

    enum class ThemeMode { SYSTEM, DARK, LIGHT }

    /** Empty means "follow the system". Otherwise a BCP-47 tag resolvable by Resources. */
    const val LANGUAGE_SYSTEM = ""
    const val LANGUAGE_ENGLISH = "en"
    const val LANGUAGE_ARABIC = "ar"
    const val LANGUAGE_SPANISH = "es"
    const val LANGUAGE_CHINESE = "zh-CN"

    private const val PREFS = "appearance_prefs"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_CHOSEN = "appearance_chosen"

    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    @Volatile
    private var initialized = false

    /** Idempotent: safe to call from every entry point that needs the flow primed. */
    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            _themeMode.value = themeModeSync(context)
            initialized = true
        }
    }

    fun themeModeSync(context: Context): ThemeMode {
        val raw = prefs(context).getString(KEY_THEME, ThemeMode.SYSTEM.name)
        return runCatching { ThemeMode.valueOf(raw ?: ThemeMode.SYSTEM.name) }
            .getOrDefault(ThemeMode.SYSTEM)
    }

    fun setThemeMode(context: Context, mode: ThemeMode) {
        prefs(context).edit().putString(KEY_THEME, mode.name).apply()
        _themeMode.value = mode
    }

    fun languageTag(context: Context): String =
        prefs(context).getString(KEY_LANGUAGE, LANGUAGE_SYSTEM).orEmpty()

    /** Whether the first-run appearance gate was already answered. */
    fun isChosen(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CHOSEN, false)

    /**
     * Marks the gate answered. Synchronous commit, not apply: the language path recreates
     * the activity immediately after, and the recreated instance must see the flag even
     * if the process dies mid-restart.
     */
    fun setChosen(context: Context) {
        prefs(context).edit().putBoolean(KEY_CHOSEN, true).commit()
    }

    fun setLanguage(context: Context, tag: String) {
        prefs(context).edit().putString(KEY_LANGUAGE, tag).apply()
    }

    // NOTE: uses the passed context directly, never context.applicationContext — during
    // Application.attachBaseContext the application object is not attached yet and
    // getApplicationContext() returns null, which crashed startup (the locale wrap reads
    // prefs before super.attachBaseContext runs). The prefs file is app-scoped by name
    // on any context, so this is identical everywhere else too.
    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
