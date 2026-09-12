package com.catsmoker.app.features.editgamefiles.wuwa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the SmartBrain recommender against
 * `referance/gamingtools/WuWa-Config-Android-main/config/SmartBrain.kt` and its GPU tier
 * table in `config/CvarOptimizer.kt`, both read in full before the port was written: the
 * tier patterns and their first-match ordering, the scoring deltas, and the preset ladder
 * thresholds. A wrong tier or a shifted threshold silently recommends a preset the device
 * cannot hold.
 */
class WuwaSmartBrainTest {

    @Test
    fun gpuTierTableIsPinnedToTheReference() {
        // First match wins — the ordering is part of the data.
        assertEquals("flagship", WuwaSmartBrain.gpuTier("Adreno (TM) 830"))
        assertEquals("flagship", WuwaSmartBrain.gpuTier("Adreno (TM) 810"))
        assertEquals("flagship", WuwaSmartBrain.gpuTier("Tensor G4"))
        assertEquals("flagship", WuwaSmartBrain.gpuTier("Dimensity 9400"))
        assertEquals("high", WuwaSmartBrain.gpuTier("Adreno (TM) 750"))
        assertEquals("high", WuwaSmartBrain.gpuTier("Mali-G78"))
        assertEquals("high", WuwaSmartBrain.gpuTier("Kirin 9000"))
        assertEquals("mid_high", WuwaSmartBrain.gpuTier("Adreno (TM) 730"))
        assertEquals("mid_high", WuwaSmartBrain.gpuTier("Adreno (TM) 660"))
        assertEquals("mid_high", WuwaSmartBrain.gpuTier("Xclipse 940"))
        assertEquals("mid", WuwaSmartBrain.gpuTier("Adreno (TM) 620"))
        assertEquals("mid", WuwaSmartBrain.gpuTier("Mali-G68"))
        // The reference's own classification: only mali-g7[6-9]x/8xx/9xx count as high, so
        // the flagship-tier G715/G720 land in "mid" via the 7[0-5] branch. Quirky, but the
        // table is the reference's data and this port pins it, not second-guesses it.
        assertEquals("mid", WuwaSmartBrain.gpuTier("Mali-G715"))
        assertEquals("mid", WuwaSmartBrain.gpuTier("Mali-G720"))
        assertEquals("mid_low", WuwaSmartBrain.gpuTier("Adreno (TM) 540"))
        assertEquals("low", WuwaSmartBrain.gpuTier("Adreno (TM) 430"))
        assertEquals("unknown", WuwaSmartBrain.gpuTier(null))
        assertEquals("unknown", WuwaSmartBrain.gpuTier("PowerVR GM 9446"))
    }

    @Test
    fun flagshipDeviceScoresHighAndTopsOutAtUltra() {
        val rec = WuwaSmartBrain.recommend(
            WuwaSmartBrain.DeviceSignals(
                gpu = "Adreno (TM) 830",
                ramMb = 12000,
                resolution = "1080x2400",
                vulkanAvailable = true,
                lowRamDevice = false
            )
        )
        // 50 + 30 (flagship) + 8 (8GB+) + 8 (Vulkan), no resolution penalty (FHD) = 96.
        assertEquals(96, rec.score)
        assertEquals("ultra", rec.preset)
        assertTrue(rec.signals.any { it == "Flagship GPU: +30" })
        assertTrue(rec.warnings.isEmpty())
    }

    @Test
    fun cinematicIsNeverRecommendedWithoutMeasuredThermals() {
        // Even a perfect measurable score stays at ultra: the reference's cinematic rung
        // requires zero *measured* thermal events, which the encrypted log would supply.
        val rec = WuwaSmartBrain.recommend(
            WuwaSmartBrain.DeviceSignals(
                gpu = "Adreno (TM) 850",
                ramMb = 16000,
                resolution = "1080x2400",
                vulkanAvailable = true,
                lowRamDevice = false
            )
        )
        // 50 + 30 + 8 + 8 = 96 — the clamp only bounds downward; nothing pushes to 100.
        assertEquals(96, rec.score)
        assertEquals("ultra", rec.preset)
    }

    @Test
    fun qhdPanelOnFlagshipStillReachesHigh() {
        val rec = WuwaSmartBrain.recommend(
            WuwaSmartBrain.DeviceSignals(
                gpu = "Adreno (TM) 750",
                ramMb = 8000,
                resolution = "1440x3200",
                vulkanAvailable = true,
                lowRamDevice = false
            )
        )
        // 50 + 20 + 8 + 8 - 4 (QHD) = 82 → high (>= 75 with tier and Vulkan).
        assertEquals(82, rec.score)
        assertEquals("high", rec.preset)
        assertTrue(rec.signals.any { it == "QHD+ resolution: -4" })
    }

    @Test
    fun fourKOnMidTierGpuIsPenalizedAndWarned() {
        val rec = WuwaSmartBrain.recommend(
            WuwaSmartBrain.DeviceSignals(
                gpu = "Adreno (TM) 620",
                ramMb = 6000,
                resolution = "2160x3840",
                vulkanAvailable = false,
                lowRamDevice = false
            )
        )
        // 50 + 0 (mid) + 5 (6GB+) - 10 (4K) - 8 (4K on mid) = 37 → performance (< 40).
        assertEquals(37, rec.score)
        assertEquals("performance", rec.preset)
        assertTrue(rec.warnings.any { it.contains("4K on mid/low-end GPU") })
    }

    @Test
    fun lowRamDeviceGoesStraightToPotato() {
        val rec = WuwaSmartBrain.recommend(
            WuwaSmartBrain.DeviceSignals(
                gpu = "Adreno (TM) 750",
                ramMb = 3000,
                resolution = "720x1600",
                vulkanAvailable = true,
                lowRamDevice = true
            )
        )
        assertEquals("potato", rec.preset)
        assertTrue(rec.signals.any { it == "Low memory device: -15" })
    }

    @Test
    fun unreadGpuIsNeutralButUnrecognisedGpuScoresLowEnd() {
        // Null = no reading (no log analyzed yet): unscored and named, mirroring unread RAM —
        // the reference always had its LogInfo and never faced a missing GPU.
        val unread = WuwaSmartBrain.recommend(
            WuwaSmartBrain.DeviceSignals(gpu = null, ramMb = 8000, resolution = "1080x2400")
        )
        // 50 + 0 (unread GPU) + 8 (8GB+), Vulkan unchecked.
        assertEquals(58, unread.score)
        assertTrue(unread.signals.any { it == "GPU unread: +0 (not scored)" })
        assertTrue(unread.logOnlyAxes.any { it.contains("GPU tier") })
        // A measured-but-unrecognised GPU still scores the reference's own −20.
        val unknown = WuwaSmartBrain.recommend(
            WuwaSmartBrain.DeviceSignals(gpu = "PowerVR GM 9446", ramMb = 8000, resolution = "1080x2400")
        )
        // 50 - 20 (unknown tier) + 8 (8GB+), Vulkan unchecked.
        assertEquals(38, unknown.score)
        assertTrue(unknown.signals.any { it == "Low-end GPU: -20" })
        // A mid-high GPU with identical everything-else lands a preset above that.
        val midHigh = WuwaSmartBrain.recommend(
            WuwaSmartBrain.DeviceSignals(gpu = "Adreno (TM) 730", ramMb = 8000, resolution = "1080x2400")
        )
        assertEquals(68, midHigh.score)
        assertEquals("balanced", midHigh.preset)
    }

    @Test
    fun lowRamPlusQhdCarriesTheComboPenalty() {
        val rec = WuwaSmartBrain.recommend(
            WuwaSmartBrain.DeviceSignals(
                gpu = "Adreno (TM) 660",
                ramMb = 4000,
                resolution = "1440x3200",
                vulkanAvailable = false,
                lowRamDevice = false
            )
        )
        // 50 + 10 (mid_high) + 0 (4-6GB) - 4 (QHD) - 5 (low RAM + high res) = 51.
        assertEquals(51, rec.score)
        assertTrue(rec.signals.any { it == "Low RAM + high res: -5" })
        assertTrue(rec.warnings.any { it.contains("<6GB RAM") })
    }

    @Test
    fun logOnlyAxesAreReportedNotInvented() {
        val rec = WuwaSmartBrain.recommend(WuwaSmartBrain.DeviceSignals())
        // Every log-dependent axis is named, so the user knows what the score does not see.
        assertTrue(rec.logOnlyAxes.any { it.contains("thermal") })
        assertTrue(rec.logOnlyAxes.any { it.contains("GPU out-of-memory") })
        assertTrue(rec.logOnlyAxes.any { it.contains("FPS") })
        // And none of the log-only signal strings appear as scored signals.
        assertFalse(rec.signals.any { it.contains("thermal", ignoreCase = true) })
        assertFalse(rec.signals.any { it.contains("FPS") })
        // Nothing was measured, so nothing was claimed as evidence.
        assertFalse(rec.hadLogEvidence)
    }

    // ---- log-measured axes: the rungs that open up only with a decrypted Client.log ----

    @Test
    fun measuredCleanLogUnlocksCinematic() {
        val rec = WuwaSmartBrain.recommend(
            WuwaSmartBrain.DeviceSignals(
                gpu = "Adreno (TM) 830",
                ramMb = 12000,
                resolution = "1080x2400",
                vulkanAvailable = true,
                lowRamDevice = false,
                fpsActual = 118f,
                fpsCap = 120,
                thermalEvents = 0,
                gpuOom = 0,
                dropFrames = 0,
                autoAdjustTriggers = 0,
                autoAdjustRecoveries = 0,
                textureErrors = 0,
                networkErrors = 0,
                screenPct = 100f
            )
        )
        // 50 + 30 + 8 + 8 + 5 (FPS at target: 118 >= 120*0.95) = 101, clamped to 100.
        assertEquals(100, rec.score)
        assertEquals("cinematic", rec.preset)
        assertTrue(rec.hadLogEvidence)
        assertTrue(rec.signals.any { it == "FPS at target: +5" })
        // Every counted log axis had a reading; "active cvars" is empty and stays unweighed
        // alongside the one axis that needs a CvarDatabase this port deliberately does not ship.
        assertEquals(
            listOf("active cvars", "cvar database axes (unknown / monitored / changed-from-default counts)"),
            rec.logOnlyAxes
        )
    }

    @Test
    fun measuredFpsGapUnlocksCompetitive() {
        val rec = WuwaSmartBrain.recommend(
            WuwaSmartBrain.DeviceSignals(
                gpu = "Adreno (TM) 730",
                ramMb = 12000,
                resolution = "1080x2400",
                vulkanAvailable = true,
                lowRamDevice = false,
                fpsActual = 40f,
                fpsCap = 120,
                thermalEvents = 0,
                gpuOom = 0,
                dropFrames = 0,
                autoAdjustTriggers = 0,
                autoAdjustRecoveries = 0,
                textureErrors = 0,
                networkErrors = 0,
                screenPct = 100f
            )
        )
        // 50 + 10 (mid_high) + 8 + 8 - 18 (drop >30%) - 8 (FPS 30-45) = 50.
        assertEquals(50, rec.score)
        // The reference's competitive rung: score >= 45 and a measured (cap - actual) > 15.
        assertEquals("competitive", rec.preset)
        assertTrue(rec.signals.any { it == "FPS drop >30%: -18" })
    }

    @Test
    fun measuredThermalAndDropsReachEndurance() {
        val rec = WuwaSmartBrain.recommend(
            WuwaSmartBrain.DeviceSignals(
                gpu = "Adreno (TM) 750",
                ramMb = 8000,
                resolution = "1080x2400",
                vulkanAvailable = true,
                lowRamDevice = false,
                fpsActual = 43f,
                fpsCap = 60,
                thermalEvents = 5,
                gpuOom = 0,
                dropFrames = 16,
                autoAdjustTriggers = 0,
                autoAdjustRecoveries = 0,
                textureErrors = 0,
                networkErrors = 0,
                screenPct = 100f
            )
        )
        // 50 + 20 (high) + 8 + 8 - 12 (drop 20-30%: (60-43)/60 = 28.3) - 8 (FPS 30-45)
        // - 20 (thermal x5) - 10 (drops x16) = 36.
        assertEquals(36, rec.score)
        // Below balanced's 40 — the reference's endurance rung for measured thermal >= 3.
        assertEquals("endurance", rec.preset)
        assertTrue(rec.signals.any { it == "Thermal throttling x5: -20" })
        assertTrue(rec.warnings.any { it.contains("Heavy thermal throttling") })
    }

    @Test
    fun measuredGpuOomForcesPotatoEvenOnFlagshipHardware() {
        val rec = WuwaSmartBrain.recommend(
            WuwaSmartBrain.DeviceSignals(
                gpu = "Adreno (TM) 830",
                ramMb = 12000,
                resolution = "1080x2400",
                vulkanAvailable = true,
                lowRamDevice = false,
                gpuOom = 3,
                thermalEvents = 0
            )
        )
        // 50 + 30 + 8 + 8 - 30 (OOM x3) = 66 — but the reference's ladder overrides the
        // score outright: measured gpuOom >= 2 means potato, whatever the silicon.
        assertEquals("potato", rec.preset)
        assertTrue(rec.signals.any { it == "GPU OOM x3: -30" })
        assertTrue(rec.warnings.any { it.contains("GPU OOM detected") })
    }

    @Test
    fun scoreIsClampedToTheReferenceRange() {
        val worst = WuwaSmartBrain.recommend(
            WuwaSmartBrain.DeviceSignals(
                gpu = "Adreno (TM) 320",
                ramMb = 2000,
                resolution = "2160x3840",
                vulkanAvailable = false,
                lowRamDevice = true
            )
        )
        assertEquals(0, worst.score)
        assertEquals("potato", worst.preset)
    }
}
