package com.catsmoker.app.features.editgamefiles.grid

/**
 * GRID Autosport's graphics settings, as Feral Interactive stores them.
 *
 * The game keeps everything in `android/data/com.feralinteractive.gridas/files/
 * feral_app_support/preferences` — a Windows-registry-shaped XML: nested `<key name=…>`
 * scopes holding typed `<value name=… type="integer|string|binary">` entries. It is *not*
 * Unity's playerprefs format and not valid plain XML config either — the file carries binary
 * blobs (touch-saturation doubles, analytics ids) and per-device state that must survive any
 * edit untouched.
 *
 * Cross-checked against `referance/file-engineering/
 * Change-Grid-Autosport-Mobile-Graphics-All-Devices--main`, both halves read in full before
 * this file was written: its README (the manual transfer-edit-transfer-back workflow, the
 * exact value entries to edit, the device-tier recommendations) and its shipped `preferences`
 * file (756 lines — the format itself, the value names, and the traps). What the reference
 * establishes, and what this editor therefore does:
 *
 * - **Targeted substitution, never a whole-file template.** The reference README says it in
 *   so many words — "If your version is different, edit the settings manually instead of
 *   replacing the entire file." The shipped file carries `GameVersionString`,
 *   `GameInstallVersion`, binary blobs and `SeenSpecificationAlert*` state that a wholesale
 *   replacement would clobber. Every edit here rewrites exactly the `<value>` entries named
 *   below and leaves every other byte in place.
 * - **The editable set is the README's set**: resolution (`ScreenW`/`ScreenH`/
 *   `FixedScreenHeight`), frame rate (`gfxconfig_max_fps`/`gfxconfig_high_max_fps`), the
 *   quality-ladder strings, and the on/off switches (including the README's "keep OFF for
 *   better performance" performance keys). `gfxconfigpowersaver_*` — the game's own
 *   power-saver preset table — is deliberately not touched, and neither are the preset-name
 *   strings the game manages itself.
 * - **Exact name matching, because value names repeat across scopes.** The same file records
 *   `…\Setup\ScreenH` *and* `MaxFramesPerSecond` in two different keys (Setup holds 0,
 *   `IndirectX\Direct3D\Config` holds 60), and `AutoValueRemap\GPURemap\values` carries
 *   `Software\Feral Interactive\GRID Autosport\Setup\ScreenH` as a remap marker with the
 *   value 1. Anchoring on `name="ScreenH"` — quote to quote — cannot reach any of those.
 * - **Absent keys are reported, never invented.** The game writes its `gfxconfig_*` entries
 *   when its own graphics screen has been used; a fresh install may not carry them yet.
 *   Substitution cannot append a `<value>` into the right key scope, so a key this file does
 *   not hold is refused with its name rather than half-applied.
 *
 * Everything here is pure and JVM-testable; reading and writing the file live in
 * [GridPreferencesManager].
 */
object GridPreferences {

    /** The game's package — the path in the reference README is the only verified install. */
    const val PACKAGE = "com.feralinteractive.gridas"

    /** File name, exactly as the game and the reference README spell it — no extension. */
    const val FILE_NAME = "preferences"

    /** Path relative to the storage root the shell channels address. */
    const val RELATIVE_DIR = "Android/data/$PACKAGE/files/feral_app_support"

    /** The quality ladder the game's own preset table ships (its powersaver block holds `ultralow`). */
    val QUALITY_TIERS = listOf("ultralow", "low", "medium", "high", "ultrahigh")

    /** Anisotropic filtering's ladder allows `off` (the game's powersaver preset holds it). */
    const val ANISOTROPIC_KEY = "anisotropic_filtering"

    /**
     * Quality-ladder strings, by their suffix after `gfxconfig_`. Writing one writes its
     * `gfxconfig_<name>` and `gfxconfig_high_<name>` twins — the README lists both sets as
     * the editable values, and editing one without the other would leave the High preset
     * disagreeing with the settings the user just chose.
     */
    val LADDER_KEYS = listOf(
        "car_reflection", "cloth", "crowd", "dynamic_ambient_occ", "mirrors",
        "night_lighting", "objects", "particles", "shaders", "shadows",
        "textures", "track", "trees", "vehicles", "voice_count", "water"
    )

    /**
     * On/off switches, canonical name → every value name the switch writes. `multisampling`
     * writes three (the README's performance list holds all of `multisampling`,
     * `gfxconfig_high_multisampling` and `gfxconfig_default_multisampling`); the others write
     * themselves and their `high_` twin.
     */
    val SWITCH_TARGETS: Map<String, List<String>> = mapOf(
        "advanced_fog" to listOf("advanced_fog", "high_advanced_fog"),
        "advanced_lighting" to listOf("advanced_lighting", "high_advanced_lighting"),
        "dynamic_ambient_occ_soft" to listOf("dynamic_ambient_occ_soft", "high_dynamic_ambient_occ_soft"),
        "global_illumination" to listOf("global_illumination", "high_global_illumination"),
        "groundCover" to listOf("groundCover", "high_groundCover"),
        "skidmarks" to listOf("skidmarks", "high_skidmarks"),
        "multisampling" to listOf("multisampling", "high_multisampling", "default_multisampling")
    )

    /** Read side: what the device's file actually holds. Null means the key is absent. */
    data class ReadResult(
        /** False when the text is not the expected registry shape — callers must refuse to edit. */
        val isFeralRegistry: Boolean,
        /** `GameVersionString` — the reference README's "check before replacing" fact. */
        val gameVersion: String?,
        val screenWidth: Int?,
        val screenHeight: Int?,
        val fixedScreenHeight: Int?,
        val maxFps: Int?,
        val highMaxFps: Int?,
        /** Canonical ladder name → current tier, or null when the file does not carry it yet. */
        val ladder: Map<String, String?>,
        /** Anisotropic filtering's current tier (its ladder also allows `off`), or null. */
        val anisotropic: String?,
        /** Canonical switch name → current state, or null when the file does not carry it yet. */
        val switches: Map<String, Boolean?>
    )

    /** The user's requested changes. A null field means "leave that group alone". */
    data class Edits(
        /** Internal render height; also written to `ScreenH` and `FixedScreenHeight`. */
        val screenHeight: Int? = null,
        /** `ScreenW`. Null derives it from the file's own current aspect ratio. */
        val screenWidth: Int? = null,
        /** FPS cap for both `gfxconfig_max_fps` and `gfxconfig_high_max_fps`. */
        val fps: Int? = null,
        /** Canonical ladder name → target tier. */
        val ladder: Map<String, String> = emptyMap(),
        /** Anisotropic filtering's target tier. */
        val anisotropic: String? = null,
        /** Canonical switch name → target state. */
        val switches: Map<String, Boolean> = emptyMap()
    )

    /** The outcome of one apply. [changed] names values actually rewritten; [refused] why not. */
    data class Applied(
        val xml: String,
        val changed: List<String>,
        /** `"name: reason"` entries for keys that were absent or held a value we refuse to overwrite. */
        val refused: List<String>
    )

    private val VALUE_REGEX =
        Regex("""<value name="([^"]+)" type="([^"]+)">([^<]*)</value>""")

    /**
     * Parses the graphics values out of the file's text. Nothing here judges the file beyond
     * shape ([ReadResult.isFeralRegistry]) — a game update that renamed a key shows up as an
     * absent entry, which is the truth, rather than as a parse error hiding a partial read.
     */
    fun parse(xml: String): ReadResult {
        val values = readValues(xml)
        val gameVersion = values["GameVersionString"]?.second?.takeIf { it.isNotBlank() }
        return ReadResult(
            isFeralRegistry = xml.contains("<registry") && xml.contains("Feral Interactive"),
            gameVersion = gameVersion,
            screenWidth = values.intValue("ScreenW"),
            screenHeight = values.intValue("ScreenH"),
            fixedScreenHeight = values.intValue("FixedScreenHeight"),
            maxFps = values.intValue("gfxconfig_max_fps"),
            highMaxFps = values.intValue("gfxconfig_high_max_fps"),
            ladder = LADDER_KEYS.associateWith { key -> values.stringValue("gfxconfig_$key") },
            anisotropic = values.stringValue("gfxconfig_$ANISOTROPIC_KEY"),
            switches = SWITCH_TARGETS.keys.associateWith { key ->
                when (values.stringValue("gfxconfig_$key")) {
                    "on" -> true
                    "off" -> false
                    else -> null
                }
            }
        )
    }

    /**
     * Applies [edits] as targeted substitutions and reports exactly what happened. The result's
     * text is byte-identical to the source everywhere except the `<value>` entries named in
     * [Applied.changed] — binary blobs, unknown keys, indentation and ordering all survive.
     */
    fun apply(sourceXml: String, edits: Edits): Applied {
        val values = readValues(sourceXml)
        val changed = mutableListOf<String>()
        val refused = mutableListOf<String>()
        var xml = sourceXml

        fun substituteInt(name: String, newValue: Int, label: String) {
            val current = values[name]
            when {
                current == null -> refused.add("$label: not present in the file — open the game's graphics options once")
                !current.second.matches(Regex("-?\\d+")) ->
                    refused.add("$label: holds \"${current.second}\", not a number — refusing to guess")
                else -> {
                    xml = replaceValue(xml, name, newValue.toString())
                    changed.add(name)
                }
            }
        }

        fun substituteString(name: String, newValue: String, label: String) {
            val current = values[name]
            when {
                current == null -> refused.add("$label: not present in the file — open the game's graphics options once")
                !current.second.matches(Regex("[a-zA-Z]+")) ->
                    refused.add("$label: holds \"${current.second}\" — refusing to overwrite an unexpected value")
                else -> {
                    xml = replaceValue(xml, name, newValue)
                    changed.add(name)
                }
            }
        }

        // Resolution: three entries written consistently, the way the reference README edits
        // them. Width falls back to the file's own aspect ratio, then to 16:9.
        if (edits.screenHeight != null) {
            val height = edits.screenHeight
            val width = edits.screenWidth
                ?: deriveWidth(values.intValue("ScreenW"), values.intValue("ScreenH"), height)
            substituteInt("ScreenW", width, "ScreenW")
            substituteInt("ScreenH", height, "ScreenH")
            substituteInt("FixedScreenHeight", height, "FixedScreenHeight")
        }

        if (edits.fps != null) {
            substituteInt("gfxconfig_max_fps", edits.fps, "gfxconfig_max_fps")
            substituteInt("gfxconfig_high_max_fps", edits.fps, "gfxconfig_high_max_fps")
        }

        for ((key, tier) in edits.ladder) {
            substituteString("gfxconfig_$key", tier, "gfxconfig_$key")
            substituteString("gfxconfig_high_$key", tier, "gfxconfig_high_$key")
        }

        if (edits.anisotropic != null) {
            substituteString("gfxconfig_$ANISOTROPIC_KEY", edits.anisotropic, "gfxconfig_$ANISOTROPIC_KEY")
            substituteString("gfxconfig_high_$ANISOTROPIC_KEY", edits.anisotropic, "gfxconfig_high_$ANISOTROPIC_KEY")
        }

        for ((canonical, targets) in SWITCH_TARGETS) {
            val state = edits.switches[canonical] ?: continue
            for (target in targets) {
                substituteString("gfxconfig_$target", if (state) "on" else "off", "gfxconfig_$target")
            }
        }

        return Applied(xml = xml, changed = changed, refused = refused)
    }

    /**
     * Width that keeps the file's own aspect ratio at [newHeight]. A file whose current
     * resolution is unreadable or degenerate falls back to 16:9 — the ratio every value in
     * the reference file is written at (1280×720) — rather than failing the whole edit.
     */
    fun deriveWidth(currentWidth: Int?, currentHeight: Int?, newHeight: Int): Int {
        if (currentWidth != null && currentHeight != null && currentHeight > 0 && currentWidth > 0) {
            return Math.round(newHeight.toLong() * currentWidth / currentHeight.toDouble()).toInt()
        }
        return Math.round(newHeight * 16.0 / 9.0).toInt()
    }

    /**
     * First occurrence of each value name, name → (type, body). First wins: a name that
     * repeats across scopes is exactly the ambiguity the exact-match write path also steers
     * around, and the keys this editor owns are unique in the verified file.
     */
    private fun readValues(xml: String): Map<String, Pair<String, String>> {
        val map = linkedMapOf<String, Pair<String, String>>()
        for (match in VALUE_REGEX.findAll(xml)) {
            val (name, type, body) = match.destructured
            if (!map.containsKey(name)) map[name] = type to body
        }
        return map
    }

    private fun Map<String, Pair<String, String>>.intValue(name: String): Int? =
        this[name]?.second?.toIntOrNull()

    private fun Map<String, Pair<String, String>>.stringValue(name: String): String? =
        this[name]?.second?.takeIf { it.isNotBlank() }

    /**
     * Rewrites one value's body, matching on the exact name quote-to-quote so a longer
     * path-shaped name (`…\Setup\ScreenH`) can never be caught by a shorter key (`ScreenH`).
     * The value's own `type` attribute is matched loosely and preserved verbatim — the edit
     * changes what the game reads, not how it is typed.
     */
    private fun replaceValue(xml: String, name: String, newBody: String): String {
        val regex = Regex("""(<value name="$name" type="[^"]*">)[^<]*(</value>)""")
        return regex.replace(xml) { match -> match.groupValues[1] + newBody + match.groupValues[2] }
    }
}
