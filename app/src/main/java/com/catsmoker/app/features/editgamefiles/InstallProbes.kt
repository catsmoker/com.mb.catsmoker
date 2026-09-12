package com.catsmoker.app.features.editgamefiles

import com.catsmoker.app.shared.data.model.GameType

/**
 * The five PUBG store variants, in picker order — one game, five packages. Lived inline in
 * [EditGameFilesScreen] until the group-dot rule needed a test; a rule worth pinning is worth
 * its own file.
 */
internal val PUBG_VARIANTS = listOf(
    GameType.PUBG_GLOBAL, GameType.PUBG_KRJP, GameType.PUBG_VN, GameType.BGMI, GameType.PUBG_REKOO
)

/**
 * The PUBG group row's dot in the game picker. Three states on purpose, per the house rule
 * that "absent" and "unknown" stay distinct: green when *any* variant is installed, red only
 * when all five were probed and none answered, unknown (null) when at least one probe is
 * missing — a package-visibility failure must never paint a false "not installed".
 */
internal fun pubgGroupDot(installedGames: Map<GameType, Boolean>): Boolean? = when {
    PUBG_VARIANTS.any { installedGames[it] == true } -> true
    PUBG_VARIANTS.all { installedGames.containsKey(it) } -> false
    else -> null
}
