package com.catsmoker.app.features.editgamefiles.hsr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

/**
 * Pins the HSR graphics blob format against the reference implementation
 * (`referance/gamingtools/hsrgraphicdroid-main/data/GraphicsSettings.kt`), the same way
 * `GenshinConfigTemplateTest` pins the Genshin config: the JSON key names, the URL-encoding
 * wrapper, the hidden upscaler fields, and the sibling XML key strings are all part of the
 * verified agreement with the game, not incidental details a refactor is free to change.
 *
 * Needs a real `org.json` on the test classpath — android.jar's copy is a stub.
 */
class HsrGraphicsSettingsTest {

    @Test
    fun roundTripsEveryField() {
        val original = HsrGraphicsSettings(
            fps = 120,
            enableVSync = false,
            renderScale = 0.75,
            resolutionQuality = 2,
            shadowQuality = 1,
            lightQuality = 4,
            characterQuality = 5,
            envDetailQuality = 0,
            reflectionQuality = 3,
            sfxQuality = 2,
            bloomQuality = 1,
            aaMode = 2,
            enableMetalFXSU = true,
            enableHalfResTransparent = true,
            enableSelfShadow = 0,
            dlssQuality = 3,
            particleTrailSmoothness = 7,
            screenWidth = 2560,
            screenHeight = 1440,
            fullscreenMode = 4,
            graphicsQuality = 0,
            enablePsoShaderWarmup = false,
            isUserSave = 1,
            version = 11
        )

        // Only the JSON-half fields survive the blob; the siblings are separate XML entries
        // and come back at their defaults until the manager merges them from the file.
        val throughBlob = HsrGraphicsSettings.fromEncoded(original.toEncoded())
        assertNotNull(throughBlob)
        assertEquals(original.fps, throughBlob!!.fps)
        assertEquals(original.enableVSync, throughBlob.enableVSync)
        assertEquals(original.renderScale, throughBlob.renderScale, 0.0)
        assertEquals(original.aaMode, throughBlob.aaMode)
        assertEquals(original.enableMetalFXSU, throughBlob.enableMetalFXSU)
        assertEquals(original.enableSelfShadow, throughBlob.enableSelfShadow)
        assertEquals(original.dlssQuality, throughBlob.dlssQuality)
        assertEquals(original.particleTrailSmoothness, throughBlob.particleTrailSmoothness)
    }

    @Test
    fun encodedBlobIsUrlEncodedJsonWithTheReferenceKeys() {
        val encoded = HsrGraphicsSettings(fps = 120).toEncoded()

        // The blob the game stores is URLEncoder output, so it opens with %7B ('{').
        assertTrue("blob should be URL-encoded: $encoded", encoded.startsWith("%7B"))

        val decoded = URLDecoder.decode(encoded, "UTF-8")
        // Every JSON key the reference writes, verbatim — a renamed key would silently do
        // nothing in-game, which is the worst failure mode an editor can have.
        for (key in listOf(
            "FPS", "EnableVSync", "RenderScale", "ResolutionQuality", "ShadowQuality",
            "LightQuality", "CharacterQuality", "EnvDetailQuality", "ReflectionQuality",
            "SFXQuality", "BloomQuality", "AAMode", "EnableMetalFXSU",
            "EnableHalfResTransparent", "EnableSelfShadow", "DlssQuality", "ParticleTrailSmoothness"
        )) {
            assertTrue("missing JSON key $key in $decoded", decoded.contains("\"$key\":"))
        }
        // And nothing that belongs to the sibling XML entries must leak into the blob.
        for (nonKey in listOf("Screenmanager", "GraphicsSettings_GraphicsQuality", "IsUserSave")) {
            assertTrue("sibling key $nonKey must not be in the JSON blob", !decoded.contains(nonKey))
        }
    }

    @Test
    fun decodesAReferenceShapedBlob() {
        // Shape a real file's `GraphicsSettings_Model` entry would have: URL-encoded JSON,
        // here with the values the game itself might have written.
        val json = "{\"FPS\":120,\"EnableVSync\":false,\"RenderScale\":1.0," +
            "\"ResolutionQuality\":3,\"ShadowQuality\":3,\"LightQuality\":3," +
            "\"CharacterQuality\":3,\"EnvDetailQuality\":3,\"ReflectionQuality\":3," +
            "\"SFXQuality\":3,\"BloomQuality\":3,\"AAMode\":1," +
            "\"EnableMetalFXSU\":false,\"EnableHalfResTransparent\":false," +
            "\"EnableSelfShadow\":1,\"DlssQuality\":0,\"ParticleTrailSmoothness\":0}"
        val settings = HsrGraphicsSettings.fromEncoded(java.net.URLEncoder.encode(json, "UTF-8"))

        assertNotNull(settings)
        assertEquals(120, settings!!.fps)
        assertEquals(false, settings.enableVSync)
        assertEquals(1.0, settings.renderScale, 0.0)
        assertEquals(1, settings.aaMode)
        // Hidden upscaler fields survive the decode — the write path round-trips them so an
        // editor session cannot reset a value the user set in-game.
        assertEquals(1, settings.enableSelfShadow)
        assertEquals(0, settings.dlssQuality)
    }

    @Test
    fun rejectsGarbageAsNullRatherThanDefaults() {
        assertNull(HsrGraphicsSettings.fromEncoded("not a blob at all"))
        assertNull(HsrGraphicsSettings.fromEncoded(""))
        // Valid URL-encoding of something that is not JSON.
        assertNull(HsrGraphicsSettings.fromEncoded(java.net.URLEncoder.encode("[1,2,3]", "UTF-8")))
    }

    @Test
    fun siblingXmlKeysArePinnedVerbatim() {
        // The literal entry names inside the game's playerprefs.xml, including the %20 escapes
        // Unity leaves in them. Cross-checked against the reference's writeSettings.
        val keys = HsrGraphicsSettings.SIBLING_INT_KEYS.associate { (field, key) -> field to key }
        assertEquals("Screenmanager%20Resolution%20Width", keys["screenWidth"])
        assertEquals("Screenmanager%20Resolution%20Height", keys["screenHeight"])
        assertEquals("Screenmanager%20Fullscreen%20mode", keys["fullscreenMode"])
        assertEquals("GraphicsSettings_GraphicsQuality", keys["graphicsQuality"])
        assertEquals("GraphicsSettings_EnablePsoShaderWarmup", keys["enablePsoShaderWarmup"])
        assertEquals("GraphicsSettings_IsUserSave", keys["isUserSave"])
        assertEquals("GraphicsSettings_Version", keys["version"])
    }

    @Test
    fun qualityScalesMatchTheReferenceNames() {
        val settings = HsrGraphicsSettings()
        assertEquals("Very Low", settings.qualityName(0))
        assertEquals("High", settings.qualityName(3))
        assertEquals("Ultra", settings.qualityName(5))
        // SFX's shifted scale: 0 invalid, 1–5 the steps.
        assertEquals("Invalid", settings.sfxQualityName(0))
        assertEquals("Very Low", settings.sfxQualityName(1))
        assertEquals("Very High", settings.sfxQualityName(5))
        assertEquals("TAA", HsrGraphicsSettings(aaMode = 1).aaModeName())
    }

    @Test
    fun hiddenUpscalerScalesMatchTheReferenceNames() {
        // Self Shadow is a 0-2 level, DLSS a 0-4 quality ladder, Particle Trail a 0-3 level —
        // all pinned against the reference's getSelfShadowName/getDlssName/getParticleTrailName.
        assertEquals("Off", HsrGraphicsSettings(enableSelfShadow = 0).selfShadowName())
        assertEquals("Low", HsrGraphicsSettings(enableSelfShadow = 1).selfShadowName())
        assertEquals("High", HsrGraphicsSettings(enableSelfShadow = 2).selfShadowName())
        assertEquals("Off", HsrGraphicsSettings(dlssQuality = 0).dlssName())
        assertEquals("Quality", HsrGraphicsSettings(dlssQuality = 1).dlssName())
        assertEquals("Balanced", HsrGraphicsSettings(dlssQuality = 2).dlssName())
        assertEquals("Performance", HsrGraphicsSettings(dlssQuality = 3).dlssName())
        assertEquals("Ultra Performance", HsrGraphicsSettings(dlssQuality = 4).dlssName())
        assertEquals("Off", HsrGraphicsSettings(particleTrailSmoothness = 0).particleTrailName())
        assertEquals("Medium", HsrGraphicsSettings(particleTrailSmoothness = 2).particleTrailName())
        assertEquals("High", HsrGraphicsSettings(particleTrailSmoothness = 3).particleTrailName())
    }

    @Test
    fun resolutionPresetsRoundTrip() {
        val settings = HsrGraphicsSettings()
        for (preset in listOf("360p", "720p", "1080p", "1440p", "4K")) {
            settings.applyResolutionPreset(preset)
            assertEquals(preset, settings.resolutionPreset())
        }
        // A non-preset resolution still displays rather than pretending to be one.
        settings.screenWidth = 1234
        settings.screenHeight = 567
        assertEquals("1234x567", settings.resolutionPreset())
    }
}
