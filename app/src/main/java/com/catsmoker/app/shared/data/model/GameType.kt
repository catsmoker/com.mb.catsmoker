package com.catsmoker.app.shared.data.model

/**
 * Every game File Engineering offers, in one list — the screen's selector is the single place
 * games appear from. Most entries drive the screen's own profile-push flow; the last three are
 * the read-modify-write editors ([embeddedEditor] true), which that screen hosts inline below
 * the selector. Pushing a bundled template over these games' files is exactly what those
 * editors must not do, and routing them to screens of their own left the feature spread over
 * four screens for one job — so selecting them swaps the content, not the destination.
 *
 * [shortLabel] is unused by the picker since it became a dialog (kept for callers that want a
 * compact name); the five PUBG entries are one game to the picker, which shows a single row
 * and reveals [variantLabel] chips as a second selector once PUBG is the chosen game.
 */
enum class GameType(val displayName: String, val shortLabel: String, val variantLabel: String? = null, val embeddedEditor: Boolean = false) {
    NONE("Select a game", "Select"),
    PUBG_GLOBAL("PUBG Mobile (Global)", "PUBG Global", "Global"),
    PUBG_KRJP("PUBG Mobile Korea (KRJP)", "PUBG KR", "KRJP"),
    PUBG_VN("PUBG Mobile Vietnam", "PUBG VN", "Vietnam"),
    BGMI("BGMI (India)", "BGMI", "BGMI"),
    PUBG_REKOO("PUBG Mobile (Rekoo)", "PUBG Rekoo", "Rekoo"),
    GENSHIN_IMPACT("Genshin Impact", "Genshin"),
    HSR("Honkai: Star Rail", "HSR", embeddedEditor = true),
    WUWA("Wuthering Waves", "WuWa", embeddedEditor = true),
    GRID("GRID Autosport", "GRID", embeddedEditor = true)
}
