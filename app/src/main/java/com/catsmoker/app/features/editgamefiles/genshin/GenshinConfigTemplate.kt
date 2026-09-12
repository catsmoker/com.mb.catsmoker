package com.catsmoker.app.features.editgamefiles.genshin

import android.os.Build

/**
 * Substitutes the device's real model into Genshin Impact's `hardware_model_config.json` template.
 *
 * The game looks the file's entries up by the device's model — the reference project
 * `referance/file-engineering/GenshinConfig-main`'s README is explicit that "Your Device Model"
 * must be changed to "the model of the device you are using" before the file is dropped into
 * `/Android/data/com.miHoYo.GenshinImpact/files/`. This is that manual edit, automated, and it is
 * the only transformation the file gets.
 *
 * The substitution is plain text on purpose. The template is *not* valid JSON — it carries an
 * unquoted `ASTC`, an `01`, an `00000001h`, and trailing commas — and that is the exact file the
 * game accepts, shipped byte-for-byte from the reference the way the PUBG `Active.sav` blobs are
 * shipped opaque. Parsing it (impossible anyway) and re-serialising it would silently normalise
 * those quirks into a file that no longer resembles the one the game was verified against.
 */
object GenshinConfigTemplate {

    /** The placeholder the reference template ships with, as a whole line worth of text. */
    private const val PLACEHOLDER = "\"hardwareModel\": \"Your Device Model\""

    /**
     * Returns [template] with the placeholder model replaced by [model].
     *
     * The `"Auto"` entry the template also carries is left untouched — it is the game's own
     * fallback for models that match nothing, not a placeholder. A template without the
     * placeholder comes back unchanged rather than "corrected": if the shipped asset ever stops
     * carrying it, silently substituting nothing would push a file that unlocks nothing, and the
     * mismatch should surface in a test instead.
     */
    fun withDeviceModel(template: String, model: String): String =
        template.replace(PLACEHOLDER, "\"hardwareModel\": \"$model\"")

    /** [withDeviceModel] for the device this app is running on. */
    fun withThisDevice(template: String): String = withDeviceModel(template, Build.MODEL)
}
