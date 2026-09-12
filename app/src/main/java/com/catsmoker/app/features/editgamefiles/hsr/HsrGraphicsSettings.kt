package com.catsmoker.app.features.editgamefiles.hsr

import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Honkai: Star Rail's graphics block, as the game stores it.
 *
 * The game keeps one URL-encoded JSON blob under the `GraphicsSettings_Model` key inside its
 * Unity `playerprefs.xml`, plus a handful of sibling XML *int* entries (resolution, master
 * quality, shader warmup, user-save marker, settings version) that are deliberately **not**
 * part of the JSON. This type covers the JSON half and mirrors the siblings as plain fields;
 * [HsrGameManager] merges the two on read and splits them back out on write — exactly the
 * split the reference makes, because writing a sibling key into the JSON would silently do
 * nothing and writing a JSON key as a sibling would corrupt the blob.
 *
 * Every field the reference parses is parsed here, including the hidden upscaler toggles
 * ([enableMetalFXSU], [dlssQuality], [enableHalfResTransparent], [enableSelfShadow],
 * [particleTrailSmoothness]): they are round-tripped on write so an editor session cannot
 * reset a value the user set in-game. Their editor surfaces are a separate backlog item; the
 * codec must not wait for the UI.
 *
 * Cross-checked against `referance/gamingtools/hsrgraphicdroid-main/data/GraphicsSettings.kt`
 * (`com.ireddragonicy.hsrgraphicdroid.data.GraphicsSettings`): same JSON keys, same
 * URL-encoding wrapper, same defaults, same value ranges — including the quirks that matter,
 * like [sfxQuality] using a shifted scale (0 is invalid; 1–5 are the real steps) and sibling
 * `graphicsQuality` 0 meaning "Custom", which is what lets the per-slider values survive the
 * game's own preset system.
 */
data class HsrGraphicsSettings(
    // JSON blob members.
    var fps: Int = 60,
    var enableVSync: Boolean = true,
    var renderScale: Double = 1.0,
    var resolutionQuality: Int = 3,
    var shadowQuality: Int = 3,
    var lightQuality: Int = 3,
    var characterQuality: Int = 3,
    var envDetailQuality: Int = 3,
    var reflectionQuality: Int = 3,
    var sfxQuality: Int = 3,
    var bloomQuality: Int = 3,
    var aaMode: Int = 1,
    // Hidden upscaler/detail toggles — round-tripped, no editor surface yet.
    var enableMetalFXSU: Boolean = false,
    var enableHalfResTransparent: Boolean = false,
    var enableSelfShadow: Int = 1,
    var dlssQuality: Int = 0,
    var particleTrailSmoothness: Int = 0,
    // Sibling XML int entries, not part of the JSON blob.
    var screenWidth: Int = 1920,
    var screenHeight: Int = 1080,
    var fullscreenMode: Int = -1,
    var graphicsQuality: Int = 3,
    var enablePsoShaderWarmup: Boolean = true,
    var isUserSave: Int = 1,
    var version: Int = 10
) {

    companion object {
        /** The JSON blob's key inside the playerprefs map. */
        const val PREFS_KEY = "GraphicsSettings_Model"

        fun fromEncoded(encoded: String): HsrGraphicsSettings? = try {
            val json = JSONObject(URLDecoder.decode(encoded, "UTF-8"))
            HsrGraphicsSettings(
                fps = json.optInt("FPS", 60),
                enableVSync = json.optBoolean("EnableVSync", true),
                renderScale = json.optDouble("RenderScale", 1.0),
                resolutionQuality = json.optInt("ResolutionQuality", 3),
                shadowQuality = json.optInt("ShadowQuality", 3),
                lightQuality = json.optInt("LightQuality", 3),
                characterQuality = json.optInt("CharacterQuality", 3),
                envDetailQuality = json.optInt("EnvDetailQuality", 3),
                reflectionQuality = json.optInt("ReflectionQuality", 3),
                sfxQuality = json.optInt("SFXQuality", 3),
                bloomQuality = json.optInt("BloomQuality", 3),
                aaMode = json.optInt("AAMode", 1),
                enableMetalFXSU = json.optBoolean("EnableMetalFXSU", false),
                enableHalfResTransparent = json.optBoolean("EnableHalfResTransparent", false),
                enableSelfShadow = json.optInt("EnableSelfShadow", 1),
                dlssQuality = json.optInt("DlssQuality", 0),
                particleTrailSmoothness = json.optInt("ParticleTrailSmoothness", 0)
            )
        } catch (_: Exception) {
            null
        }

        /**
         * The sibling XML int keys the game reads alongside the blob, mapped to this class's
         * field names. Kept here so the read and the write can never disagree about which
         * entries belong to graphics — the same mistake the reference guards against by keeping
         * the list in one place.
         */
        internal val SIBLING_INT_KEYS = listOf(
            "screenWidth" to "Screenmanager%20Resolution%20Width",
            "screenHeight" to "Screenmanager%20Resolution%20Height",
            "fullscreenMode" to "Screenmanager%20Fullscreen%20mode",
            "graphicsQuality" to "GraphicsSettings_GraphicsQuality",
            "enablePsoShaderWarmup" to "GraphicsSettings_EnablePsoShaderWarmup",
            "isUserSave" to "GraphicsSettings_IsUserSave",
            "version" to "GraphicsSettings_Version"
        )

        /** The fixed resolutions behind the preset chips. The reference's own five. */
        fun resolutionFor(preset: String): Pair<Int, Int>? = when (preset) {
            "360p" -> 640 to 360
            "720p" -> 1280 to 720
            "1080p" -> 1920 to 1080
            "1440p" -> 2560 to 1440
            "4K" -> 3840 to 2160
            else -> null
        }
    }

    fun toEncoded(): String {
        val json = JSONObject()
        json.put("FPS", fps)
        json.put("EnableVSync", enableVSync)
        json.put("RenderScale", renderScale)
        json.put("ResolutionQuality", resolutionQuality)
        json.put("ShadowQuality", shadowQuality)
        json.put("LightQuality", lightQuality)
        json.put("CharacterQuality", characterQuality)
        json.put("EnvDetailQuality", envDetailQuality)
        json.put("ReflectionQuality", reflectionQuality)
        json.put("SFXQuality", sfxQuality)
        json.put("BloomQuality", bloomQuality)
        json.put("AAMode", aaMode)
        json.put("EnableMetalFXSU", enableMetalFXSU)
        json.put("EnableHalfResTransparent", enableHalfResTransparent)
        json.put("EnableSelfShadow", enableSelfShadow)
        json.put("DlssQuality", dlssQuality)
        json.put("ParticleTrailSmoothness", particleTrailSmoothness)
        return URLEncoder.encode(json.toString(), "UTF-8")
    }

    /**
     * The plain-English name of one 0–5 quality step. The reference's own mapping, verbatim:
     * 0 Very Low … 5 Ultra. SFX is the exception — see [sfxQualityName].
     */
    fun qualityName(quality: Int): String = when (quality) {
        0 -> "Very Low"
        1 -> "Low"
        2 -> "Medium"
        3 -> "High"
        4 -> "Very High"
        5 -> "Ultra"
        else -> "Unknown"
    }

    /** SFX's own scale: 0 is "invalid" (the game resets it), 1–5 are the steps. */
    fun sfxQualityName(quality: Int): String = when (quality) {
        0 -> "Invalid"
        1 -> "Very Low"
        2 -> "Low"
        3 -> "Medium"
        4 -> "High"
        5 -> "Very High"
        else -> "Unknown"
    }

    fun aaModeName(): String = when (aaMode) {
        0 -> "Off"
        1 -> "TAA"
        2 -> "FXAA"
        else -> "Off"
    }

    /**
     * Names for the hidden upscaler/detail toggles. Cross-checked against the reference's
     * `getSelfShadowName`/`getDlssName`/`getParticleTrailName` — including that Self Shadow
     * is a 0–2 level (not a bool) and Particle Trail Smoothness a 0–3 level.
     */
    fun selfShadowName(): String = when (enableSelfShadow) {
        0 -> "Off"
        1 -> "Low"
        2 -> "High"
        else -> "Off"
    }

    fun dlssName(): String = when (dlssQuality) {
        0 -> "Off"
        1 -> "Quality"
        2 -> "Balanced"
        3 -> "Performance"
        4 -> "Ultra Performance"
        else -> "Off"
    }

    fun particleTrailName(): String = when (particleTrailSmoothness) {
        0 -> "Off"
        1 -> "Low"
        2 -> "Medium"
        3 -> "High"
        else -> "Off"
    }

    fun resolutionPreset(): String = when {
        screenWidth == 640 && screenHeight == 360 -> "360p"
        screenWidth == 1280 && screenHeight == 720 -> "720p"
        screenWidth == 1920 && screenHeight == 1080 -> "1080p"
        screenWidth == 2560 && screenHeight == 1440 -> "1440p"
        screenWidth == 3840 && screenHeight == 2160 -> "4K"
        else -> "${screenWidth}x${screenHeight}"
    }

    fun applyResolutionPreset(preset: String) {
        resolutionFor(preset)?.let { (width, height) ->
            screenWidth = width
            screenHeight = height
        }
    }
}
