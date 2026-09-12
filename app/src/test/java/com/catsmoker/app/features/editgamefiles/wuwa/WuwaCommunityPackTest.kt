package com.catsmoker.app.features.editgamefiles.wuwa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure half of the community pack importer against the two pack shapes in
 * `referance/gamingtools/Mobile-WuWa-Config-main/Community Configs/` (all READMEs and inis
 * read in full before this file was written): the root-only pack (Mythos: README + Engine.ini
 * at the root), the variant-folder pack (@Kodoupulse: root README, `No Vulkan/` and
 * `With Vulkan/` folders, an `Extra/` notes folder), and the forbidden-cvars gate the pack
 * path must obey exactly as the generator's own "Allow restricted cvars" rule does.
 */
class WuwaCommunityPackTest {

    private fun mythosPack() = listOf(
        WuwaCommunityPack.PackFile("README.md", "Device Model: IQOO 15\n"),
        WuwaCommunityPack.PackFile("Engine.ini", "[/Script/Engine.RendererSettings]\nr.MaxAnisotropy=16\n")
    )

    private fun kodoupulsePack() = listOf(
        WuwaCommunityPack.PackFile("README.md", "Device Model: Tecno Camon 30 pro 5g,"),
        WuwaCommunityPack.PackFile("No Vulkan/Engine.ini", "; low-end\nr.MotionBlurQuality=0\n"),
        WuwaCommunityPack.PackFile("With Vulkan/Engine.ini", "r.Desktop.SupportGPUScene=1\n"),
        WuwaCommunityPack.PackFile("With Vulkan/DeviceProfiles.ini", "[Android]\nDeviceProfileName=Android_High\n"),
        WuwaCommunityPack.PackFile("Extra/README.md", "Currently practicing Github\n")
    )

    // ── parse: the two reference pack shapes ────────────────────────────────────────

    @Test
    fun aRootOnlyPackIsOneVariantNamedForThePackRoot() {
        val pack = WuwaCommunityPack.parse(mythosPack())
        assertTrue(pack.isDeployable)
        assertEquals(1, pack.variants.size)
        assertEquals("(pack root)", pack.variants[0].name)
        assertEquals(setOf("Engine.ini"), pack.variants[0].files.keys)
        assertEquals(mapOf("README.md" to "Device Model: IQOO 15\n"), pack.readmes)
        assertTrue(pack.unknownFiles.isEmpty())
    }

    @Test
    fun aVariantFolderPackKeepsFoldersApartAndNotesAreNotVariants() {
        val pack = WuwaCommunityPack.parse(kodoupulsePack())
        assertEquals(2, pack.variants.size)
        assertEquals("No Vulkan", pack.variants[0].name)
        assertEquals(setOf("Engine.ini"), pack.variants[0].files.keys)
        assertEquals("With Vulkan", pack.variants[1].name)
        // The With Vulkan folder carries two of the monitored inis — both deploy together.
        assertEquals(setOf("Engine.ini", "DeviceProfiles.ini"), pack.variants[1].files.keys)
        // Extra/ holds a README and no ini: not a variant, but its README travels with the pack.
        assertTrue(pack.readmes.containsKey("Extra/README.md"))
        assertEquals(2, pack.readmes.size)
    }

    @Test
    fun iniNamesAreCanonicalizedCaseInsensitively() {
        val pack = WuwaCommunityPack.parse(
            listOf(WuwaCommunityPack.PackFile("engine.ini", "r.MaxAnisotropy=16\n"))
        )
        // Community zips are not consistent about case; the game's config dir is.
        assertEquals(setOf("Engine.ini"), pack.variants[0].files.keys)
    }

    @Test
    fun unknownFilesAreReportedNotImported() {
        val pack = WuwaCommunityPack.parse(
            listOf(
                WuwaCommunityPack.PackFile("Engine.ini", "r.MaxAnisotropy=16\n"),
                WuwaCommunityPack.PackFile("install.bat", "@echo off\n"),
                WuwaCommunityPack.PackFile("notes/screenshot.png", "not text at all")
            )
        )
        assertEquals(listOf("install.bat", "notes/screenshot.png"), pack.unknownFiles)
        assertEquals(setOf("Engine.ini"), pack.variants[0].files.keys)
    }

    @Test
    fun aPackWithNoKnownIniIsNotDeployable() {
        val pack = WuwaCommunityPack.parse(
            listOf(WuwaCommunityPack.PackFile("README.md", "nothing here"))
        )
        assertFalse(pack.isDeployable)
        assertTrue(pack.variants.isEmpty())
    }

    @Test
    fun backslashPathsAreNormalizedLikeZipListings() {
        val pack = WuwaCommunityPack.parse(
            listOf(WuwaCommunityPack.PackFile("With Vulkan\\Engine.ini", "r.MaxAnisotropy=16\n"))
        )
        assertEquals("With Vulkan", pack.variants[0].name)
    }

    // ── the forbidden-cvars gate ─────────────────────────────────────────────────────

    @Test
    fun theScanFindsRestrictedKeysInAllTheirSpellings() {
        val ini = """
            [/Script/Engine.RendererSettings]
            r.MaxAnisotropy=16
            r.ScreenPercentage=50
            +CVars=r.DetailMode=0
            R.SHADOW.MAXCSMRESOLUTION=512
            ;r.ViewDistanceScale=0.1
        """.trimIndent()
        // Case-insensitive, through the +CVars= directive, and comments are skipped — the
        // same rules and the same key list the generator's restricted-cvars switch uses.
        assertEquals(
            listOf("r.ScreenPercentage", "r.DetailMode", "R.SHADOW.MAXCSMRESOLUTION"),
            WuwaCommunityPack.forbiddenCvarsIn(ini)
        )
    }

    @Test
    fun theScanAndTheStripCannotDisagree() {
        val ini = "r.MaxAnisotropy=16\nr.DetailMode=0\nr.MipMapLODBias=4\nkeep=1"
        // The strip removes exactly the lines the scan counted — pinned against a file with
        // no trailing newline, where a naive line-count diff would be off by one.
        val variant = WuwaCommunityPack.Variant(
            name = "v", files = mapOf("Engine.ini" to ini), forbidden = mapOf()
        )
        val stripped = WuwaCommunityPack.stripVariant(variant)
        assertEquals(2, stripped.removedLines["Engine.ini"])
        assertEquals(
            listOf("r.MaxAnisotropy=16", "keep=1"),
            stripped.files["Engine.ini"]!!.lines().filter { it.isNotBlank() }
        )
        // Everything not forbidden is byte-for-byte where it stays, section headers included.
        assertTrue(stripped.files["Engine.ini"]!!.contains("keep=1"))
    }

    @Test
    fun aCleanVariantStripsToItselfWithZeroRemoved() {
        // No trailing newline: the strip's appendLine adds one back on the last line, and the
        // removed-count comes from the scan (0), not from a line diff that the added newline
        // would throw off.
        val ini = "[Section]\nr.MaxAnisotropy=16"
        val variant = WuwaCommunityPack.Variant(
            name = "v", files = mapOf("Engine.ini" to ini), forbidden = mapOf()
        )
        val stripped = WuwaCommunityPack.stripVariant(variant)
        assertEquals(0, stripped.removedCount)
        assertEquals("$ini\n", stripped.files["Engine.ini"])
    }

    @Test
    fun forbiddenCountsSumAcrossAVariantsFiles() {
        val variant = WuwaCommunityPack.Variant(
            name = "v",
            files = mapOf(
                "Engine.ini" to "r.DetailMode=0",
                "Scalability.ini" to "r.ScreenPercentage=50"
            ),
            forbidden = mapOf()
        )
        assertEquals(2, WuwaCommunityPack.forbiddenCvarsIn(variant.files["Engine.ini"]!!).size +
            WuwaCommunityPack.forbiddenCvarsIn(variant.files["Scalability.ini"]!!).size)
    }

    // ── README facts ─────────────────────────────────────────────────────────────────

    @Test
    fun readmeFactsParseThePacksLabelValueShape() {
        val readme = """
            Device Model: IQOO 15

            Chipset: Snapdragon 8 Elite Gen 5

            Average FPS: 58–60 FPS

            Testing Duration: 30 minutes (combat + open world)
        """.trimIndent()
        val facts = WuwaCommunityPack.readmeFacts(readme)
        assertEquals(
            listOf("Device Model", "Chipset", "Average FPS", "Testing Duration"),
            facts.map { it.label }
        )
        assertEquals("IQOO 15", facts[0].value)
        assertFalse(facts.any { it.warning })
    }

    @Test
    fun warningIsAFlaggedFactAndAbsorbsItsContinuationLines() {
        val readme = """
            Testing Duration: 30 minutes
            WARNING:
            It Might/May Crash at some Devices with Lower Ram(8gb or Bellow).
            It's highly advised to use a peltier cooler or a fan while playing.

            Other Processors Tested:
            Helio G99,
            Snapdragon 8s Gen3
        """.trimIndent()
        val facts = WuwaCommunityPack.readmeFacts(readme)
        val warning = facts.first { it.warning }
        // The WARNING label's own line is empty; the two lines that follow are its body.
        assertEquals(
            "It Might/May Crash at some Devices with Lower Ram(8gb or Bellow). " +
                "It's highly advised to use a peltier cooler or a fan while playing.",
            warning.value
        )
        // A multi-line list under its own label is one fact, not three.
        val processors = facts.first { it.label == "Other Processors Tested" }
        assertEquals("Helio G99, Snapdragon 8s Gen3", processors.value)
    }

    @Test
    fun proseBulletsAndSingleWordLabelsAreNotFacts() {
        val readme = """
            Im @Kodoupulse From YouTube Thats Where I Upload The Gameplays
            * **Want to share a config?** Submit a Pull Request.
            12:30 some log line
            Device Model: Tecno Camon 30 pro 5g,
            (Might be Different to Everyone)
            References: UE 4.26 & UE 5 Console Commands
        """.trimIndent()
        val facts = WuwaCommunityPack.readmeFacts(readme)
        // "References" is a single-word label but starts uppercase, so it IS a fact — the
        // packs carry it that way ("References: UE 4.26 & UE 5 Console Commands"). The pinned
        // rule is that only lowercase label-shaped lines (URL schemes, prose) are not facts.
        assertEquals(2, facts.size)
        assertEquals("Device Model", facts[0].label)
        // The parenthetical continuation is absorbed, and "References:" ends the fact rather
        // than being glued onto its value.
        assertEquals("Tecno Camon 30 pro 5g, (Might be Different to Everyone)", facts[0].value)
        assertEquals("References", facts[1].label)
        assertEquals("UE 4.26 & UE 5 Console Commands", facts[1].value)
    }

    @Test
    fun readmeNamesAreMatchedByCaseAndExtension() {
        val pack = WuwaCommunityPack.parse(
            listOf(
                WuwaCommunityPack.PackFile("Readme.txt", "notes"),
                WuwaCommunityPack.PackFile("with-vulkan/README.MD", "variant notes")
            )
        )
        assertEquals(setOf("Readme.txt", "with-vulkan/README.MD"), pack.readmes.keys)
    }

    @Test
    fun duplicateLeafFolderNamesAreDisambiguatedByPath() {
        // Two nested folders sharing a leaf name must not collapse at the name lookup the
        // deploy uses (firstOrNull) — the second occurrence carries its full relative path.
        val pack = WuwaCommunityPack.parse(
            listOf(
                WuwaCommunityPack.PackFile("a/Preset/Engine.ini", "r.MaxAnisotropy=16\n"),
                WuwaCommunityPack.PackFile("b/Preset/Engine.ini", "r.MaxAnisotropy=8\n")
            )
        )
        assertEquals(2, pack.variants.size)
        assertEquals(2, pack.variants.map { it.name }.toSet().size)
    }
}
