package com.catsmoker.app.shared.data.model

/**
 * One editable thing about a game, as the Edit Game Files screen offers it.
 *
 * A profile pairs the label the user picks with the bundled asset that carries it. PUBG has two
 * (max-FPS and iPad-view saves); Genshin Impact has one (the tuned `hardware_model_config.json`).
 */
data class GameProfile(
    val label: String,
    val assetPath: String
)

data class GameConfig(
    val packageName: String,
    val saveDir: String,
    val saveFile: String,
    val profiles: List<GameProfile> = emptyList(),
    /**
     * True when the bundled asset is a text template that names the device it is being pushed to.
     *
     * Genshin Impact's `hardware_model_config.json` looks its entries up by the device's real
     * model, so the shipped template carries a placeholder that every delivery channel must
     * substitute with `Build.MODEL` before the file lands. Binary blobs (PUBG's `Active.sav`)
     * never need this and stay false.
     */
    val requiresDeviceModel: Boolean = false,
    /**
     * Absolute path of the file whose deletion reverts every push this screen made, or null when
     * the game has no resettable file.
     *
     * PUBG regenerates `Active.sav` from its own defaults on the next launch, so deleting it is
     * the clean revert for both profiles — the mechanism `referance/gamingtools/
     * BattleGrounds_GFX-main` exposes as "Reset Active.sav". Genshin Impact's config always
     * exists on a working install and the game does not rebuild it from defaults, so there is
     * no reset channel for it and the field stays null.
     */
    val resetFilePath: String? = null,
    /**
     * The file name the UI should show when talking about this game's reset, defaulting to
     * [saveFile]. Exists because the reset button and its confirmation dialog once hardcoded
     * "ACTIVE.SAV" and showed it for every game — including Genshin, whose file is
     * `hardware_model_config.json` and which has no reset at all (null [resetFilePath] hides
     * the button entirely).
     */
    val resetFileLabel: String = saveFile,
    /**
     * Whether the screen's Custom File Upload section applies to this game.
     *
     * Custom upload pushes an arbitrary user-picked file over the game's config — that only
     * makes sense where the file format is the game's own opaque save blob the user may have
     * obtained elsewhere: PUBG's `Active.sav`. Genshin's `hardware_model_config.json` is a
     * model-keyed template this app generates (and the game's lookup is exact), so a random
     * dropped-in file would just break the lookup — the bundled profile is the whole surface,
     * and the section is hidden for it.
     */
    val allowCustomUpload: Boolean = false,
    /**
     * Whether the screen shows the inline save editor for this game — read-modify-write of the
     * fields the save already carries, rather than pushing a bundled file over it.
     *
     * Only PUBG's `Active.sav` qualifies today: its GVAS layout is verified byte-level (see
     * [com.catsmoker.app.features.editgamefiles.pubg.PubgSavePatcher]), and the closed-source
     * reference tools improve exactly these ints inside the user's own save. Whole-file
     * templates (Genshin) stay false — there is nothing to patch, only a file to replace.
     */
    val supportsSavePatching: Boolean = false
)
