package com.catsmoker.app.features.editgamefiles.wuwa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the WuWa config generator against
 * `referance/gamingtools/WuWa-Config-Android-main/config/ConfigGenerator.kt`, which was read
 * in full before this generator was written: the preset table's exact tuning values, the
 * tier caps and GPU patterns, the targeted-vs-universal DeviceProfiles shapes, the
 * GameUserSettings format, and the forbidden-cvar strip. These are the values the game
 * itself reads — a retuned preset or a renamed cvar would silently do nothing.
 */
class WuWaConfigGeneratorTest {

    private val noDevice = WuWaConfigGenerator.DeviceInfo()
    private val highEndGpu = WuWaConfigGenerator.DeviceInfo(gpu = "Adreno (TM) 830")

    @Test
    fun presetTableIsPinnedToTheReference() {
        assertEquals(8, WuWaConfigGenerator.PRESET_ORDER.size)
        assertEquals(listOf("potato", "endurance", "performance", "competitive", "balanced", "high", "ultra", "cinematic"), WuWaConfigGenerator.PRESET_ORDER)

        val potato = WuWaConfigGenerator.PRESETS.getValue("potato")
        assertEquals(60, potato.screen)
        assertEquals(0, potato.shadow)
        assertEquals(128, potato.shadowRes)
        assertEquals(0, potato.ssr)
        assertEquals(3, potato.mipbias)
        assertEquals(0.3, potato.streaming, 1e-9)
        assertEquals(0.3, potato.vd, 1e-9)
        assertEquals(0.4, potato.flod, 1e-9)
        assertEquals(0, potato.detail)
        assertEquals(5, potato.lod_bias)
        assertEquals(1500, potato.grasscull)
        assertEquals(0, potato.characterDetail)
        assertEquals(0, potato.postProcess)
        assertFalse(potato.staticLighting)
        assertEquals(0, potato.cutsceneQuality)

        val cinematic = WuWaConfigGenerator.PRESETS.getValue("cinematic")
        assertEquals(100, cinematic.screen)
        assertEquals(5, cinematic.shadow)
        assertEquals(4096, cinematic.shadowRes)
        assertEquals(4, cinematic.ssr)
        assertEquals(-2, cinematic.mipbias)
        assertEquals(6.0, cinematic.streaming, 1e-9)
        assertEquals(4.0, cinematic.vd, 1e-9)
        assertEquals(4.0, cinematic.flod, 1e-9)
        assertEquals(7, cinematic.detail)
        assertEquals(-2, cinematic.lod_bias)
        assertEquals(40000, cinematic.grasscull)
        assertEquals(3, cinematic.characterDetail)
        assertEquals(3, cinematic.postProcess)
        assertTrue(cinematic.staticLighting)
        assertEquals(3, cinematic.cutsceneQuality)

        // The 8 presets occupy distinct detail ranks — every q0/q1/q2 gate combination the
        // builders branch on is reachable.
        assertEquals(
            (0..7).toSet(),
            WuWaConfigGenerator.PRESETS.values.map { it.detail }.toSet()
        )
    }

    @Test
    fun engineIniCarriesThePresetCvars() {
        val out = WuWaConfigGenerator.generate("potato", WuWaConfigGenerator.Options(), noDevice)
        val engine = out.engine
        assertTrue(engine.contains("[Core.System]"))
        assertTrue(engine.contains("[SystemSettings]"))
        // Character quality ladder keyed off the preset's detail rank.
        assertTrue(engine.contains("r.KuroMaterialQualityLevel=0"))
        // Potato's perf-tweaks section overrides the shadow section on the same keys, and the
        // dedup pass (last-wins, the reference's own rule) leaves the tweaks' values standing.
        assertTrue(engine.contains("r.Shadow.PerObjectResolutionMax=256"))
        assertTrue(engine.contains("r.Shadow.MaxResolution=512"))
        assertEquals(1, engine.lines().count { it.startsWith("r.Shadow.MaxResolution=") })
        // Mobile rendering / frame display anchors.
        assertTrue(engine.contains("r.VSync=1"))
        // The potato/endurance/performance-only tweaks section is present for potato.
        assertTrue(engine.contains("r.MobileNumDynamicPointLights=0"))
    }

    @Test
    fun shadowResolutionIsCappedByDeviceTier() {
        // Cinematic's 4096 on an unidentified (lowest-tier) device degrades to 1024, not explodes.
        val low = WuWaConfigGenerator.generate("cinematic", WuWaConfigGenerator.Options(), noDevice)
        assertTrue(low.engine.contains("r.Shadow.MaxResolution=1024"))
        // On a high-end GPU the full 4096 survives.
        val high = WuWaConfigGenerator.generate("cinematic", WuWaConfigGenerator.Options(), highEndGpu)
        assertTrue(high.engine.contains("r.Shadow.MaxResolution=4096"))
    }

    @Test
    fun gameUserSettingsMatchesTheReferenceFormat() {
        val out = WuWaConfigGenerator.generate(
            "balanced", WuWaConfigGenerator.Options(fps = 90), WuWaConfigGenerator.DeviceInfo(resolution = "1440x3200")
        )
        val gus = out.gameUserSettings
        assertTrue(gus.contains("[ScalabilityGroups]"))
        assertTrue(gus.contains("sg.ResolutionQuality=80"))
        assertTrue(gus.contains("sg.KuroRenderQuality=3"))
        assertTrue(gus.contains("[/Script/Engine.GameUserSettings]"))
        assertTrue(gus.contains("ResolutionSizeX=1440"))
        assertTrue(gus.contains("ResolutionSizeY=3200"))
        assertTrue(gus.contains("FrameRateLimit=90.000000"))
        assertTrue(gus.contains("FramePace=90"))
        assertTrue(gus.contains("bUseVSync=True"))
    }

    @Test
    fun unknownResolutionFallsBackToTheReferenceDefault() {
        val out = WuWaConfigGenerator.generate("balanced", WuWaConfigGenerator.Options(), noDevice)
        assertTrue(out.gameUserSettings.contains("ResolutionSizeX=1280"))
        assertTrue(out.gameUserSettings.contains("ResolutionSizeY=720"))
    }

    @Test
    fun deviceProfilesIsUniversalWhenNothingIsKnown() {
        val out = WuWaConfigGenerator.generate("potato", WuWaConfigGenerator.Options(), noDevice)
        val dp = out.deviceProfiles
        assertTrue(dp.contains("[Android_Low DeviceProfile]"))
        assertTrue(dp.contains("BaseProfileName=Android"))
        assertTrue(dp.contains("+CVars=r.Kuro.MaxFPS.ThirdParty60=1"))
        // Off by default: the 120 unlock is opt-in…
        assertFalse(dp.contains("r.Kuro.MaxFPS.ThirdParty120"))
        // …while the Ultra graphics unlock is on by default (the reference's own default).
        assertTrue(dp.contains("+CVars=r.Kuro.GraphicsQuality.ThirdPartyUltraEnable=1"))
        val off = WuWaConfigGenerator.generate(
            "potato", WuWaConfigGenerator.Options(unlockUltra = false), noDevice
        )
        assertFalse(off.deviceProfiles.contains("ThirdPartyUltraEnable"))
    }

    @Test
    fun deviceProfilesIsTargetedWhenTheGpuIdentifiesItself() {
        val out = WuWaConfigGenerator.generate(
            "balanced", WuWaConfigGenerator.Options(unlock120 = true), WuWaConfigGenerator.DeviceInfo(gpu = "Adreno (TM) 750")
        )
        val dp = out.deviceProfiles
        assertTrue(dp.contains("[Android_Adreno750 DeviceProfile]"))
        // Targeted profiles base themselves on the preset's own tier.
        assertTrue(dp.contains("BaseProfileName=Android_Mid"))
        assertTrue(dp.contains("+CVars=r.Kuro.MaxFPS.ThirdParty120=1"))
    }

    @Test
    fun optionalFilesAreGeneratedOnlyWhenAsked() {
        val off = WuWaConfigGenerator.generate("balanced", WuWaConfigGenerator.Options(), noDevice)
        assertEquals(setOf("Engine.ini", "DeviceProfiles.ini", "GameUserSettings.ini"), off.asMap().keys)

        val on = WuWaConfigGenerator.generate(
            "balanced",
            WuWaConfigGenerator.Options(generateScalability = true, generateHardware = true),
            noDevice
        )
        assertEquals(
            setOf("Engine.ini", "DeviceProfiles.ini", "GameUserSettings.ini", "Scalability.ini", "Hardware.ini"),
            on.asMap().keys
        )
        assertTrue(on.scalability.contains("[KuroRenderQuality@3]"))
        assertTrue(on.scalability.contains("KuroRenderQuality.LevelName=画质优先"))
        assertTrue(on.hardware.contains("DeviceProfileName=Android_Mid"))
        assertTrue(on.hardware.contains("FramePace=60"))
    }

    @Test
    fun forbiddenCvarsAreStrippedOnlyWhenDisallowed() {
        val allowed = WuWaConfigGenerator.generate("balanced", WuWaConfigGenerator.Options(allowRestrictedCvars = true), noDevice)
        assertTrue(allowed.engine.contains("r.ViewDistanceScale="))

        val stripped = WuWaConfigGenerator.generate("balanced", WuWaConfigGenerator.Options(allowRestrictedCvars = false), noDevice)
        assertFalse(stripped.engine.contains("r.ViewDistanceScale="))
        // The reference strips all five files — GameUserSettings included, even though today's
        // builder emits no r.* keys there. A future cvar must not bypass the gate silently.
        val gusWithForbidden = stripped.gameUserSettings.lines().none { WuWaForbiddenCvars.isForbidden(it.substringBefore('=').trim()) }
        assertTrue(gusWithForbidden)
        // The strip recognises +CVars= directives and case variants.
        val sample = """
            [SystemSettings]
            +CVars=r.DetailMode=2
            r.viewdistancescale=1.5
            r.Kuro.AutoCoolEnable=1
            r.TemporalAA.MobileFrameWeight=0.08
        """.trimIndent()
        val out = WuWaForbiddenCvars.stripForbiddenCvars(sample)
        assertFalse(out.contains("DetailMode"))
        assertFalse(out.contains("iewdistancescale"))
        assertTrue(out.contains("r.Kuro.AutoCoolEnable=1"))
        assertTrue(out.contains("r.TemporalAA.MobileFrameWeight=0.08"))
    }

    @Test
    fun coreSystemPathsComeFromTheDeviceWhenPresent() {
        val deviceIni = """
            [Core.System]
            Paths=../../../Engine/Content
            Paths=%GAMEDIR%Content
            Paths=../../../SomePlugin/Content

            [OtherSection]
            Paths=should-not-be-picked-up
        """.trimIndent()
        val paths = WuWaConfigGenerator.extractCoreSystemPaths(deviceIni)
        assertEquals(
            listOf(
                "[Core.System]",
                "Paths=../../../Engine/Content",
                "Paths=%GAMEDIR%Content",
                "Paths=../../../SomePlugin/Content"
            ),
            paths
        )

        // No file, no section, or a section with nothing under it → the snapshot list.
        assertEquals(WuWaConfigGenerator.DEFAULT_CORE_SYSTEM, WuWaConfigGenerator.extractCoreSystemPaths(null))
        assertEquals(WuWaConfigGenerator.DEFAULT_CORE_SYSTEM, WuWaConfigGenerator.extractCoreSystemPaths("[Other]\nFoo=1"))
        assertTrue(WuWaConfigGenerator.extractCoreSystemPaths("[Core.System]\n\n[Next]").size > 1)
        assertTrue(WuWaConfigGenerator.DEFAULT_CORE_SYSTEM.any { it.startsWith("Paths=") })

        // And the generated Engine.ini reuses them verbatim.
        val out = WuWaConfigGenerator.generate("balanced", WuWaConfigGenerator.Options(), noDevice, deviceIni)
        assertTrue(out.engine.contains("Paths=../../../SomePlugin/Content"))
        assertFalse(out.engine.contains("should-not-be-picked-up"))
    }

    @Test
    fun deduplicationKeepsTheLastOccurrence() {
        val text = """
            [SystemSettings]
            r.Foo=1
            ; comment stays
            r.Foo=2
            r.Bar=3
            [Section]
            r.Foo=4
        """.trimIndent()
        val deduped = WuWaConfigGenerator.deduplicateIniText(text)
        // Dedup is purely per key, file-global, last-wins — exactly like the reference: both
        // earlier r.Foo occurrences go, whatever section they sat in.
        assertFalse(deduped.contains("r.Foo=1"))
        assertFalse(deduped.contains("r.Foo=2"))
        assertTrue(deduped.contains("r.Foo=4"))
        assertTrue(deduped.contains("; comment stays"))
        assertTrue(deduped.contains("r.Bar=3"))
    }

    @Test
    fun resolutionParserAcceptsTheReferenceSeparators() {
        assertEquals(1440 to 3200, WuWaConfigGenerator.parseResolution("1440x3200"))
        assertEquals(1080 to 2400, WuWaConfigGenerator.parseResolution("1080 X 2400"))
        assertEquals(720 to 1600, WuWaConfigGenerator.parseResolution("720*1600"))
        assertNull(WuWaConfigGenerator.parseResolution(null))
        assertNull(WuWaConfigGenerator.parseResolution(""))
        assertNull(WuWaConfigGenerator.parseResolution("garbage"))
        assertNull(WuWaConfigGenerator.parseResolution("720x"))
    }

    @Test
    fun unknownPresetFallsBackToBalanced() {
        val out = WuWaConfigGenerator.generate("no-such-preset", WuWaConfigGenerator.Options(), noDevice)
        // Balanced renders at 80% and sg.ResolutionQuality=80.
        assertTrue(out.gameUserSettings.contains("sg.ResolutionQuality=80"))
    }

    @Test
    fun toaModeOverridesTheEnvironment() {
        val out = WuWaConfigGenerator.generate(
            "high", WuWaConfigGenerator.Options(mode = WuWaConfigGenerator.GameMode.ToA), highEndGpu
        )
        assertTrue(out.engine.contains("GAME MODE: TOWER OF ADVERSITY"))
        assertTrue(out.engine.contains("r.Kuro.Foliage.MobileGrassCullDistanceMax=2500"))
        // The ToA section comes after the environment section, so last-wins dedup leaves ToA's
        // value standing for keys it overrides.
        val grassLines = out.engine.lines().filter { it.startsWith("r.Kuro.Foliage.MobileGrassCullDistanceMax=") }
        assertEquals(1, grassLines.size)
        assertEquals("r.Kuro.Foliage.MobileGrassCullDistanceMax=2500", grassLines[0])
    }

    @Test
    fun thermalSectionPinsMaxFpsWhenAutoAdjustIsOff() {
        val out = WuWaConfigGenerator.generate(
            "balanced", WuWaConfigGenerator.Options(disableAutoAdjust = true, fps = 120), noDevice
        )
        assertTrue(out.engine.contains("t.MaxFPS=120"))
        assertTrue(out.engine.contains("r.Kuro.AutoCoolEnable=0"))
    }

    @Test
    fun headerNamesCatSmokerAndThePreset() {
        val out = WuWaConfigGenerator.generate("ultra", WuWaConfigGenerator.Options(), noDevice)
        assertTrue(out.engine.contains("CATSMOKER :: WUWA"))
        assertTrue(out.engine.contains("ULTRA"))
    }
}
