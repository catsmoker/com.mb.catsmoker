package com.catsmoker.app.features.editgamefiles.wuwa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins the auto-tune loop against
 * `referance/gamingtools/WuWa-Config-Android-main/app/src/main/java/com/wuwaconfig/app/config/BenchmarkTuner.kt`,
 * read in full before the port was written: the ladder order and both step rules, the
 * avg/min/stability formulas, the tiered option escalation, and the state file that keeps a
 * loop alive across process death.
 */
class WuwaBenchmarkTunerTest {

    @get:Rule
    val folder = TemporaryFolder()

    // ── pickPresetForFps ───────────────────────────────────────────────────────────

    @Test
    fun stepsDownOneTierUnder85PercentOfTarget() {
        // 45 < 60 * 0.85 = 51 → one tier down the reference's heaviest-first ladder.
        assertEquals("competitive", WuwaBenchmarkTuner.pickPresetForFps("balanced", 45f, 60))
        assertEquals("potato", WuwaBenchmarkTuner.pickPresetForFps("performance", 10f, 60))
    }

    @Test
    fun stepsUpOneTierWithMoreThan15PercentHeadroom() {
        // 80 = target + 33% headroom → one tier up (heavier).
        assertEquals("high", WuwaBenchmarkTuner.pickPresetForFps("balanced", 80f, 60))
        // 66 = target + 10% — above target but inside the 15% band, so it stays put.
        assertEquals("balanced", WuwaBenchmarkTuner.pickPresetForFps("balanced", 66f, 60))
    }

    @Test
    fun keepsPresetInsideTheAcceptanceBand() {
        // 52 >= 60 * 0.85 = 51 and < 60 → close enough, keep the preset.
        assertEquals("balanced", WuwaBenchmarkTuner.pickPresetForFps("balanced", 52f, 60))
    }

    @Test
    fun neverStepsPastTheLadderEnds() {
        // Heaviest already, with headroom — nowhere to step up to.
        assertEquals("cinematic", WuwaBenchmarkTuner.pickPresetForFps("cinematic", 120f, 60))
        // Lightest already, far under target — nowhere to step down to.
        assertEquals("potato", WuwaBenchmarkTuner.pickPresetForFps("potato", 30f, 60))
    }

    @Test
    fun keepsPresetsOutsideTheLadderAndNonPositiveTargets() {
        // The reference's own guard: a custom label must not be "stepped down" into cinematic.
        assertEquals("custom", WuwaBenchmarkTuner.pickPresetForFps("custom", 30f, 60))
        assertEquals("balanced", WuwaBenchmarkTuner.pickPresetForFps("balanced", 30f, 0))
    }

    // ── parseFpsSamples ────────────────────────────────────────────────────────────

    @Test
    fun computesAvgMinAndStabilityFromAverageFpsSamples() {
        // The game's own log format: "…AverageFPS=59.8…". Samples 60, 55, 70, 30 →
        // avg 53.75, min 30, stability: samples >= avg*0.8 (43) are 3 of 4 → 75%.
        val text = listOf(60f, 55f, 70f, 30f).joinToString("\n") { "LogKuro: AverageFPS=$it" }
        val result = WuwaBenchmarkTuner.parseFpsSamples(text)!!
        assertEquals(53.75f, result.avgFps, 0.01f)
        assertEquals(30f, result.minFps, 0.01f)
        assertEquals(75f, result.stabilityPct, 0.01f)
        assertEquals(4, result.sampleCount)
    }

    @Test
    fun readsOnlyTheLast500Lines() {
        // The reference's `-t 500` recency bound: an early sample outside the window must
        // not widen the averages of the session just played.
        val text = buildString {
            append("LogKuro: AverageFPS=120\n")
            append("filler\n".repeat(600))
            append("LogKuro: AverageFPS=60\n")
        }
        val result = WuwaBenchmarkTuner.parseFpsSamples(text)!!
        assertEquals(60f, result.avgFps, 0.01f)
        assertEquals(60f, result.minFps, 0.01f)
        assertEquals(1, result.sampleCount)
    }

    @Test
    fun noSamplesIsNotAZeroMeasurement() {
        // "Could not measure" is null, never a fabricated 0 FPS the device never reported.
        assertNull(WuwaBenchmarkTuner.parseFpsSamples(""))
        assertNull(WuwaBenchmarkTuner.parseFpsSamples("filler\n".repeat(600)))
    }

    // ── adjustOptionsForFps ────────────────────────────────────────────────────────

    @Test
    fun escalatesOptionsByGapSize() {
        val base = WuWaConfigGenerator.Options()
        // gap 20 → SSR off
        assertEquals(true, WuwaBenchmarkTuner.adjustOptionsForFps(base, 40f, 60).disableSSR)
        // gap 12 → bloom off, nothing else
        val bloom = WuwaBenchmarkTuner.adjustOptionsForFps(base, 48f, 60)
        assertEquals(true, bloom.disableBloom)
        assertEquals(false, bloom.disableSSR)
        // gap 9 → radial blur off
        assertEquals(true, WuwaBenchmarkTuner.adjustOptionsForFps(base, 51f, 60).disableRadialBlur)
        // gap 6 → auto exposure off
        assertEquals(true, WuwaBenchmarkTuner.adjustOptionsForFps(base, 54f, 60).disableAutoExposure)
        // gap 3 → below every tier, unchanged
        assertEquals(base, WuwaBenchmarkTuner.adjustOptionsForFps(base, 57f, 60))
        // at or above target → unchanged
        assertEquals(base, WuwaBenchmarkTuner.adjustOptionsForFps(base, 60f, 60))
    }

    @Test
    fun escalatesToTheNextTierWhenTheFirstIsAlreadySpent() {
        // SSR already disabled and the gap still > 15 → the reference's else-if chain falls
        // through to bloom, so the next round differs from the one just measured.
        val already = WuWaConfigGenerator.Options(disableSSR = true)
        val adjusted = WuwaBenchmarkTuner.adjustOptionsForFps(already, 40f, 60)
        assertTrue(adjusted.disableSSR)
        assertEquals(true, adjusted.disableBloom)
    }

    // ── persisted state ────────────────────────────────────────────────────────────

    @Test
    fun tunerStateSurvivesAPersistenceRoundTrip() {
        val file = folder.newFile("wuwa_tuner_state.json")
        val original = WuwaBenchmarkTuner.TunerState(
            stage = WuwaBenchmarkTuner.TunerStage.WAITING_FOR_PLAY,
            round = 3,
            preset = "high",
            options = WuWaConfigGenerator.Options(
                fps = 90, disableBloom = true, generateScalability = true,
                mode = WuWaConfigGenerator.GameMode.ToA
            ),
            targetFps = 90,
            results = listOf(
                WuwaBenchmarkTuner.RoundResult(1, "ultra", 55f, 40f, 80f),
                WuwaBenchmarkTuner.RoundResult(2, "high", 61f, 50f, 92f)
            )
        )
        WuwaBenchmarkTuner.saveState(original, file)
        assertEquals(original, WuwaBenchmarkTuner.loadState(file))

        WuwaBenchmarkTuner.clearState(file)
        assertNull(WuwaBenchmarkTuner.loadState(file))
    }

    @Test
    fun missingOrCorruptStateFileMeansNeverStarted() {
        val missing = folder.newFile("never_written.json")
        assertNull(WuwaBenchmarkTuner.loadState(missing))
        missing.writeText("not json")
        assertNull(WuwaBenchmarkTuner.loadState(missing))
    }

    // ── the ladder itself ──────────────────────────────────────────────────────────

    @Test
    fun ladderIsTheReferenceOrderOverTheGeneratorIds() {
        // Heaviest first, verbatim from the reference. Note the one pair where the reference
        // disagrees with its own generator: the ladder ranks endurance above performance,
        // while the generator's detail ranks say the opposite. Pinned as the reference has
        // it, not reconciled — see WuwaBenchmarkTuner's KDoc.
        assertEquals(
            listOf("cinematic", "ultra", "high", "balanced", "competitive", "endurance", "performance", "potato"),
            WuwaBenchmarkTuner.PRESET_ORDER
        )
        // Every rung is a preset the generator knows and the chip row can display.
        assertEquals(WuWaConfigGenerator.PRESET_ORDER.toSet(), WuwaBenchmarkTuner.PRESET_ORDER.toSet())
    }
}
