package com.catsmoker.app.features.editgamefiles.genshin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The device-model substitution for Genshin's `hardware_model_config.json`, against the template
 * the app actually ships.
 *
 * The template comes from the reference project `GenshinConfig-main` byte-for-byte, and it is
 * deliberately not valid JSON (unquoted `ASTC`, `01`, `00000001h`, trailing commas) — the game's
 * lenient parser accepts exactly that shape. These tests pin two things: that the substitution is
 * a text edit which leaves those quirks alone, and that the shipped asset still carries the
 * placeholder the substitution relies on — if the asset ever stops matching, pushing it would
 * silently unlock nothing, and the failure belongs here rather than on a device.
 */
class GenshinConfigTemplateTest {

    /** The tuned entry's opening, quirks included, as the shipped template words it. */
    private val templateExcerpt = """
        {
            "configs": [
                {
                    "hardwareModel": "Your Device Model",
                    "littleCoreCount": 0,
                    "bigCoreCount": 8,
                    "TextureFormats": ASTC,
                    "unityQualityGraphics": 01,
                    "GpuUsageNodeMask": 00000001h,
                },
                {
                    "hardwareModel": "Auto",
                    "TextureFormats": ASTC,
                },
            ]
        }
    """.trimIndent()

    @Test
    fun substitutesThePlaceholderWithTheDeviceModel() {
        val result = GenshinConfigTemplate.withDeviceModel(templateExcerpt, "2210132C")
        assertTrue(result.contains("\"hardwareModel\": \"2210132C\""))
        assertFalse("the placeholder must be gone", result.contains("Your Device Model"))
    }

    @Test
    fun leavesTheAutoEntryAlone() {
        // "Auto" is the game's own fallback for unmatched models, not a placeholder — substituting
        // it would leave the file with two identical model entries and no fallback at all.
        val result = GenshinConfigTemplate.withDeviceModel(templateExcerpt, "2210132C")
        assertTrue(result.contains("\"hardwareModel\": \"Auto\""))
    }

    @Test
    fun textEditOnlyTheInvalidJsonQuirksSurvive() {
        // The file cannot be parsed, so the substitution must be a plain text replace. If any of
        // these tokens changed, the asset was round-tripped through a JSON library and is no longer
        // the file the game was verified against.
        val result = GenshinConfigTemplate.withDeviceModel(templateExcerpt, "2210132C")
        assertTrue(result.contains("ASTC,"))
        assertTrue(result.contains("01,"))
        assertTrue(result.contains("00000001h,"))
        // Trailing commas too: the entry ends in one and must still end in one.
        assertTrue(Regex("00000001h,\\s*},").containsMatchIn(result))
    }

    @Test
    fun templateWithoutThePlaceholderComesBackUnchanged() {
        val noPlaceholder = templateExcerpt.replace("Your Device Model", "2210132C")
        assertEquals(
            "a template with nothing to substitute must not be rewritten",
            noPlaceholder,
            GenshinConfigTemplate.withDeviceModel(noPlaceholder, "GM45K")
        )
    }

    /**
     * The shipped asset itself: it must carry the placeholder, and one substitution must be enough
     * — a second run over an already-substituted file is a no-op, so a retried delivery channel
     * cannot corrupt the file by substituting twice.
     */
    @Test
    fun shippedAssetCarriesThePlaceholderAndSubstitutesOnce() {
        val asset = File("src/main/assets/Genshin/hardware_model_config.json")
        // The unit test's working directory is the module directory; if a different runner puts it
        // elsewhere this check is simply not applicable rather than wrong.
        assumeTrue("shipped Genshin template not found at ${asset.absolutePath}", asset.isFile)
        val shipped = asset.readText()

        assertTrue(
            "the shipped template must carry the placeholder the substitution relies on",
            shipped.contains("\"hardwareModel\": \"Your Device Model\"")
        )
        val substituted = GenshinConfigTemplate.withDeviceModel(shipped, "2210132C")
        assertEquals(
            "substituting twice must be a no-op",
            substituted,
            GenshinConfigTemplate.withDeviceModel(substituted, "2210132C")
        )
        // And the quirks are still there in the real file.
        assertTrue(substituted.contains("ASTC"))
        assertTrue(substituted.contains("00000001h"))
    }
}
