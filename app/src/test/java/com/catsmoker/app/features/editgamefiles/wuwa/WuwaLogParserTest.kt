package com.catsmoker.app.features.editgamefiles.wuwa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the Client.log parser against
 * `referance/gamingtools/WuWa-Config-Android-main/config/LogParser.kt` (read in full before
 * the port): the verbatim regexes, first-match-wins field extraction, the dynamic-atlas
 * exclusion from texture errors, the Chinese auto-adjust strings, cvar extraction with last
 * write winning, and the post-loop graphics-API resolution order.
 *
 * The fixture lines follow the shapes in the reference's own sample decrypted log
 * (`Mobile-WuWa-Config-main/misc/Client Log Decryptor/.../Trial1/Client_decrypted.txt`).
 */
class WuwaLogParserTest {

    private fun log(vararg lines: String): String = lines.joinToString("\n")

    @Test
    fun realisticLogYieldsTheDeviceAndPerformanceFields() {
        val info = WuwaLogParser.parseLog(
            log(
                "Log file open, [2026.06.08-06.13.33]",
                "[GameThread]LogPakFile: Mounting pak files.",
                "K#GPUFamily: Adreno (TM) 750",
                "K#DeviceModel: 2210132C",
                "LogInit: CPU: Qualcomm Kryo",
                "PhysicalMemoryMB: 11800",
                "LogInit: OS: Android (14)",
                "Resolution 1080x2400",
                "Selected Device Profile: [Android_ASTC_Vulkan]",
                "LogRHI: Initializing Vulkan RHI",
                "r.FramePace: set 60",
                "AverageFPS: 55.3",
                "IsLowMemoryMobile: True"
            )
        )
        assertEquals("Adreno (TM) 750", info.gpu)
        assertEquals("2210132C", info.deviceModel)
        assertEquals(11800, info.ramMb)
        assertEquals("14", info.androidVersion)
        assertEquals("1080x2400", info.resolution)
        assertEquals("Android_ASTC_Vulkan", info.deviceProfile)
        assertEquals(60, info.fpsCap)
        assertEquals(55.3f, info.fpsActual)
        assertEquals(true, info.isLowMem)
        // Explicit LogRHI wins the API resolution, and Vulkan is therefore "available".
        assertEquals("Vulkan", info.gameApi)
        assertEquals("Vulkan", info.api)
        assertEquals("available", info.vulkanStatus)
    }

    @Test
    fun diagnosticCountsIncludeTheExclusions() {
        val info = WuwaLogParser.parseLog(
            log(
                "thermal throttle detected",
                "thermal limit event", // second thermal line — counted
                "out of memory for texture pool",
                "non-streamed mips found for texture",
                // Atlas + texture-error phrase on one line: the dynamic-atlas exclusion is
                // what keeps this out of textureErrors.
                "LogDynamicAtlas: failed to load texture, Error pixel format 4 not supported",
                "frame drop detected",
                "hitch detected on frame 123",
                "自动渲染调节触发前",
                "自动渲染调节恢复前",
                "connection refused while downloading"
            )
        )
        assertEquals(2, info.thermalEvents)
        assertEquals(1, info.gpuOom)
        // The dynamic-atlas line is not a streaming/VRAM texture error — counting it would
        // falsely flag low-end devices.
        assertEquals(1, info.textureErrors)
        assertEquals(2, info.dropFrames)
        assertEquals(1, info.autoAdjustTriggers)
        assertEquals(1, info.autoAdjustRecoveries)
        assertEquals(1, info.networkErrors)
    }

    @Test
    fun cvarsAreExtractedWithLastWriteWinningAndForbiddenOnesCounted() {
        // The `Setting CVar [[k:v]]` line is the sample log's own shape (LogConfig). The
        // `Value remains` lines below follow the reference regex's own wording — the real
        // log's ignored-set warnings put the variable name *before* "Value remains", so on a
        // real log these two regexes stay quiet; the port keeps them verbatim either way.
        val info = WuwaLogParser.parseLog(
            log(
                "Setting CVar [[r.BloomQuality:3]]",
                "Value remains '80.0' for variable 'r.ScreenPercentage'",
                "Value remains '100.0' for variable 'r.ScreenPercentage'",
                // The screenPct/shadowQ regexes want the key space-prefixed after the value.
                "Value remains '100.0' set by project r.ScreenPercentage",
                "Value remains '2' for variable 'sg.ShadowQuality'",
                "Value remains '2' set by project sg.ShadowQuality"
            )
        )
        assertEquals("3", info.activeCvars["r.BloomQuality"])
        // The game's own console semantics: the later write replaces the earlier one.
        assertEquals("100.0", info.activeCvars["r.ScreenPercentage"])
        assertEquals("2", info.activeCvars["sg.ShadowQuality"])
        assertEquals(100.0f, info.screenPct)
        assertEquals(2, info.shadowQ)
        // r.ScreenPercentage is on the forbidden list; sg.ShadowQuality and r.BloomQuality are not.
        assertEquals(1, info.forbiddenCvars)
    }

    @Test
    fun glProfileSuffixAndRhiCvarResolveTheApiWhenNoLogRhiLineExists() {
        val viaProfile = WuwaLogParser.parseLog(
            log("Selected Device Profile: [Android_ASTC_GL]")
        )
        assertEquals("OpenGL ES", viaProfile.gameApi)
        assertEquals("not_available", viaProfile.vulkanStatus)

        val viaCvar = WuwaLogParser.parseLog(
            log("Setting CVar [[r.RHI:Vulkan]]")
        )
        assertEquals("Vulkan", viaCvar.gameApi)
        assertEquals("available", viaCvar.vulkanStatus)
    }

    @Test
    fun missingFieldsStayNullRatherThanInvented() {
        val info = WuwaLogParser.parseLog("Log file open, [2026.06.08-06.13.33]")
        assertNull(info.gpu)
        assertNull(info.deviceModel)
        assertNull(info.ramMb)
        assertNull(info.fpsCap)
        assertNull(info.fpsActual)
        assertNull(info.api)
        assertNull(info.vulkanStatus)
        assertEquals(0, info.thermalEvents)
        assertEquals(0, info.activeCvars.size)
    }
}
