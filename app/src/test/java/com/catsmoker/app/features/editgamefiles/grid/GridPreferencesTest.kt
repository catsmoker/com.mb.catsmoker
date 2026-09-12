package com.catsmoker.app.features.editgamefiles.grid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the GRID Autosport preferences editor against the reference file in
 * `referance/file-engineering/Change-Grid-Autosport-Mobile-Graphics-All-Devices--main`,
 * read in full before this test was written. The fixture below carries that file's real
 * traps: the `AutoValueRemap\GPURemap\values` scope whose names *contain* the graphics keys
 * as path suffixes, binary blobs, the `IndirectX\Direct3D\Config` scope's own
 * `MaxFramesPerSecond`, and the version strings the reference README warns to check.
 */
class GridPreferencesTest {

    /** Mirrors the reference file's structure, abbreviated to the parts that bite. */
    private fun referenceShapedXml(): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <registry>
            <key name="HKEY_CURRENT_USER">
                <key name="AutoValueRemap">
                    <key name="GPURemap">
                        <key name="values">
                            <value name="Software\Feral Interactive\GRID Autosport\Setup\FullScreen" type="integer">1</value>
                            <value name="Software\Feral Interactive\GRID Autosport\Setup\ScreenH" type="integer">1</value>
                            <value name="Software\Feral Interactive\GRID Autosport\Setup\ScreenW" type="integer">1</value>
                        </key>
                    </key>
                </key>
                <key name="Software">
                    <key name="Feral Interactive">
                        <key name="GRID Autosport">
                            <key name="Setup">
                                <value name="CardRenderer" type="string">Mali-G710 2410MB</value>
                                <value name="FeralAnalyticsInstallID" type="string">IJaKDJAVkITTuRHe+vi2uQPK</value>
                                <value name="BufferedPacketData" type="binary">752f94d2e2d16b8e</value>
                                <value name="ConsoleUIScale" type="binary">000000000000e83f</value>
                                <value name="GameVersionString" type="string">GRID™ Autosport v1.10.5</value>
                                <value name="FixedScreenHeight" type="integer">720</value>
                                <value name="MaxFramesPerSecond" type="integer">0</value>
                                <value name="ScreenH" type="integer">720</value>
                                <value name="ScreenW" type="integer">1280</value>
                                <value name="gfxconfig_advanced_lighting" type="string">off</value>
                                <value name="gfxconfig_anisotropic_filtering" type="string">ultrahigh</value>
                                <value name="gfxconfig_default_multisampling" type="string">off</value>
                                <value name="gfxconfig_high_anisotropic_filtering" type="string">ultrahigh</value>
                                <value name="gfxconfig_high_max_fps" type="integer">60</value>
                                <value name="gfxconfig_high_multisampling" type="string">off</value>
                                <value name="gfxconfig_high_shadows" type="string">high</value>
                                <value name="gfxconfig_max_fps" type="integer">60</value>
                                <value name="gfxconfig_multisampling" type="string">off</value>
                                <value name="gfxconfig_preset_name" type="string">custom</value>
                                <value name="gfxconfig_shadows" type="string">high</value>
                                <value name="gfxconfigpowersaver_max_fps" type="integer">30</value>
                                <value name="gfxconfigpowersaver_shadows" type="string">ultralow</value>
                                <key name="DynamicResolutionScaling">
                                    <value name="Enable" type="integer">1</value>
                                    <value name="FPSTarget" type="binary">0000000000003e40</value>
                                </key>
                            </key>
                        </key>
                    </key>
                </key>
                <key name="IndirectX">
                    <key name="Direct3D">
                        <key name="Config">
                            <value name="MaxFramesPerSecond" type="integer">60</value>
                        </key>
                    </key>
                </key>
            </key>
            <key name="HKEY_LOCAL_MACHINE">
            </key>
        </registry>
    """.trimIndent() + "\n"

    // ── parse: the read side reports what the file holds ──────────────────────────────

    @Test
    fun parseReadsTheGraphicsValuesAndTheVersion() {
        val read = GridPreferences.parse(referenceShapedXml())
        assertTrue(read.isFeralRegistry)
        assertEquals("GRID™ Autosport v1.10.5", read.gameVersion)
        assertEquals(1280, read.screenWidth)
        assertEquals(720, read.screenHeight)
        assertEquals(720, read.fixedScreenHeight)
        assertEquals(60, read.maxFps)
        assertEquals(60, read.highMaxFps)
        assertEquals("high", read.ladder["shadows"])
        assertEquals("ultrahigh", read.anisotropic)
        assertEquals(false, read.switches["advanced_lighting"])
        assertEquals(null, read.switches["advanced_fog"]) // absent from the fixture
    }

    @Test
    fun pathShapedRemapNamesAreNotGraphicsValues() {
        val read = GridPreferences.parse(referenceShapedXml())
        // The remap scope carries `…\Setup\ScreenH` = 1. If the parser matched on
        // substring, screenWidth/screenHeight would read 1, and an aspect-ratio derivation
        // built on that would be garbage.
        assertEquals(1280, read.screenWidth)
        assertEquals(720, read.screenHeight)
    }

    @Test
    fun aForeignFileIsFlaggedAsNotTheExpectedRegistry() {
        val read = GridPreferences.parse("<playerprefs><entry name='x'/></playerprefs>")
        assertFalse(read.isFeralRegistry)
        assertNull(read.gameVersion)
    }

    // ── apply: targeted substitution only ─────────────────────────────────────────────

    @Test
    fun resolutionEditsWriteAllThreeEntriesAndKeepTheAspectRatio() {
        val applied = GridPreferences.apply(
            referenceShapedXml(),
            GridPreferences.Edits(screenHeight = 1080)
        )
        assertTrue(applied.refused.isEmpty())
        assertTrue(applied.changed.containsAll(listOf("ScreenW", "ScreenH", "FixedScreenHeight")))
        val read = GridPreferences.parse(applied.xml)
        assertEquals(1920, read.screenWidth)
        assertEquals(1080, read.screenHeight)
        assertEquals(1080, read.fixedScreenHeight)
    }

    @Test
    fun anExplicitWidthWinsOverTheDerivedOne() {
        val applied = GridPreferences.apply(
            referenceShapedXml(),
            GridPreferences.Edits(screenHeight = 1080, screenWidth = 2560)
        )
        assertEquals(2560, GridPreferences.parse(applied.xml).screenWidth)
    }

    @Test
    fun fpsEditsBothCapsAndNothingElse() {
        val applied = GridPreferences.apply(
            referenceShapedXml(),
            GridPreferences.Edits(fps = 90)
        )
        val read = GridPreferences.parse(applied.xml)
        assertEquals(90, read.maxFps)
        assertEquals(90, read.highMaxFps)
        // Powersaver and the Direct3D scope's MaxFramesPerSecond are the game's own; the
        // README's editable list does not name them. The fixture holds TWO MaxFramesPerSecond
        // entries (Setup: 0, IndirectX\Direct3D\Config: 60) — neither moves.
        assertEquals("0", readValue(applied.xml, "MaxFramesPerSecond"))
        assertEquals(60, readLastValue(applied.xml, "MaxFramesPerSecond")!!.toInt())
        assertEquals(30, readValue(applied.xml, "gfxconfigpowersaver_max_fps")!!.toInt())
    }

    @Test
    fun ladderEditsWriteTheBaseAndHighTwins() {
        val applied = GridPreferences.apply(
            referenceShapedXml(),
            GridPreferences.Edits(ladder = mapOf("shadows" to "medium"))
        )
        assertTrue(applied.refused.isEmpty())
        val read = GridPreferences.parse(applied.xml)
        assertEquals("medium", read.ladder["shadows"])
        assertEquals("medium", readValue(applied.xml, "gfxconfig_high_shadows"))
        // Everything outside the edit round-trips untouched.
        assertEquals("custom", readValue(applied.xml, "gfxconfig_preset_name"))
        assertEquals("Mali-G710 2410MB", readValue(applied.xml, "CardRenderer"))
    }

    @Test
    fun theMultisamplingSwitchWritesAllThreeNames() {
        val applied = GridPreferences.apply(
            referenceShapedXml(),
            GridPreferences.Edits(switches = mapOf("multisampling" to true))
        )
        assertEquals(3, applied.changed.size)
        assertEquals("on", readValue(applied.xml, "gfxconfig_multisampling"))
        assertEquals("on", readValue(applied.xml, "gfxconfig_high_multisampling"))
        assertEquals("on", readValue(applied.xml, "gfxconfig_default_multisampling"))
    }

    @Test
    fun untouchedBytesSurviveIncludingBinaryBlobsAndVersionStrings() {
        val source = referenceShapedXml()
        val applied = GridPreferences.apply(source, GridPreferences.Edits(fps = 120))
        val changedLines = applied.xml.lines().filter { it != source.lines().find { l -> l.contains("gfxconfig_max_fps") || l.contains("gfxconfig_high_max_fps") } }
        val sourceLines = source.lines()
        // Every line that is not one of the two edited values is byte-identical, in order.
        assertEquals(
            sourceLines.filterNot { it.contains("gfxconfig_max_fps") || it.contains("gfxconfig_high_max_fps") },
            changedLines.filterNot { it.contains("gfxconfig_max_fps") || it.contains("gfxconfig_high_max_fps") }
        )
        assertTrue(applied.xml.contains("752f94d2e2d16b8e"))
        assertTrue(applied.xml.contains("GRID™ Autosport v1.10.5"))
        assertTrue(applied.xml.contains("IJaKDJAVkITTuRHe+vi2uQPK"))
    }

    @Test
    fun absentKeysAreRefusedNotInvented() {
        val applied = GridPreferences.apply(
            referenceShapedXml(),
            GridPreferences.Edits(
                ladder = mapOf("crowd" to "low"), // not in the fixture
                switches = mapOf("skidmarks" to false) // not in the fixture
            )
        )
        assertTrue(applied.changed.isEmpty())
        // crowd and high_crowd, skidmarks and high_skidmarks — every twin is named.
        assertEquals(4, applied.refused.size)
        assertTrue(applied.refused.any { it.startsWith("gfxconfig_crowd:") })
        assertTrue(applied.refused.any { it.startsWith("gfxconfig_skidmarks:") })
    }

    @Test
    fun aNonNumericBodyIsRefusedRatherThanGuessed() {
        val poisoned = referenceShapedXml()
            .replace("""<value name="ScreenW" type="integer">1280</value>""", """<value name="ScreenW" type="integer">unset</value>""")
        val applied = GridPreferences.apply(poisoned, GridPreferences.Edits(screenHeight = 1080))
        assertTrue(applied.refused.any { it.startsWith("ScreenW:") })
        // The two numeric siblings still went through — the report names what did happen.
        assertTrue(applied.changed.containsAll(listOf("ScreenH", "FixedScreenHeight")))
    }

    // ── deriveWidth ───────────────────────────────────────────────────────────────────

    @Test
    fun deriveWidthKeepsTheFileOwnsAspectRatio() {
        assertEquals(1920, GridPreferences.deriveWidth(1280, 720, 1080))
        assertEquals(2560, GridPreferences.deriveWidth(1600, 900, 1440))
    }

    @Test
    fun deriveWidthFallsBackTo16By9WhenTheFileHoldsNothing() {
        assertEquals(1920, GridPreferences.deriveWidth(null, null, 1080))
        assertEquals(1280, GridPreferences.deriveWidth(0, 0, 720))
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────

    private fun readValue(xml: String, name: String): String? {
        val match = Regex("""<value name="$name" type="[^"]*">([^<]*)</value>""").find(xml)
        return match?.groupValues?.get(1)
    }

    /** The last occurrence — the second of a duplicated name lives in a deeper scope. */
    private fun readLastValue(xml: String, name: String): String? {
        val matches = Regex("""<value name="$name" type="[^"]*">([^<]*)</value>""").findAll(xml).toList()
        return matches.lastOrNull()?.groupValues?.get(1)
    }
}
