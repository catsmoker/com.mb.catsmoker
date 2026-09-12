package com.catsmoker.app.features.editgamefiles.wuwa

/**
 * Single-pass parser for a decrypted WuWa `Client.log`, producing the device facts, live
 * performance readings and diagnostic counts the app can act on.
 *
 * Ported from `referance/gamingtools/WuWa-Config-Android-main/config/LogParser.kt`'s
 * `parseLog`, read in full before this file was written: the regexes are verbatim, the
 * first-match-wins field extraction, the combined single-pass flag matcher, the
 * dynamic-atlas exclusion from texture errors (its "Error pixel format" warnings are
 * unrelated to streaming/VRAM pressure — counting them would falsely flag low-end devices),
 * and the post-loop graphics-API resolution order (explicit LogRHI → `_GL` profile suffix →
 * `r.RHI` cvar → per-line flags). [WuwaForbiddenCvars.isForbidden] supplies the forbidden
 * count — the app already ships that key list verbatim from the same reference.
 *
 * Deliberately not ported: `parseBattleStats` (Chinese in-game event strings for a battle
 * statistics screen this app does not have). The Convene-URL extraction the reference runs
 * over the same decrypted text lives in [WuwaGacha.extractConveneUrl] instead — next to the
 * pool table and pity math that consume it — so both readers share one regex without a
 * second copy to drift.
 */
object WuwaLogParser {

    data class LogInfo(
        val gpu: String?,
        val deviceModel: String?,
        val socName: String?,
        val socCode: String?,
        val cpuName: String?,
        val ramMb: Int?,
        val androidVersion: String?,
        val resolution: String?,
        val gameApi: String?,
        val api: String?,
        /** "available" / "not_available" / null = could not tell from the log. */
        val vulkanStatus: String?,
        val deviceProfile: String?,
        val fpsCap: Int?,
        val fpsActual: Float?,
        val screenPct: Float?,
        val shadowQ: Int?,
        val qualityMode: String?,
        val isLowMem: Boolean?,
        val textureErrors: Int,
        val gpuOom: Int,
        val dropFrames: Int,
        val forbiddenCvars: Int,
        val thermalEvents: Int,
        val autoAdjustTriggers: Int,
        val autoAdjustRecoveries: Int,
        val networkErrors: Int,
        val activeCvars: Map<String, String>
    )

    fun parseLog(text: String): LogInfo {
        var gpu: String? = null
        var deviceModel: String? = null
        var socName: String? = null
        var socCode: String? = null
        var cpuName: String? = null
        var ramMb: Int? = null
        var androidVersion: String? = null
        var resolution: String? = null
        var deviceProfile: String? = null
        var fpsCap: Int? = null
        var fpsActual: Float? = null
        var screenPct: Float? = null
        var shadowQ: Int? = null
        var qualityMode: String? = null
        var isLowMem: Boolean? = null
        var textureErrors = 0
        var gpuOom = 0
        var dropFrames = 0
        var thermalEvents = 0
        var autoAdjustTriggers = 0
        var autoAdjustRecoveries = 0
        var networkErrors = 0
        val activeCvars = mutableMapOf<String, String>()
        var gameApi: String? = null
        var hasVulkanRhi = false
        var hasOpenGl = false
        var hasVulkan = false
        var hasDirectX = false
        var hasMetal = false

        for (line in text.lineSequence()) {
            // ── Counting (single pass) ──
            // The dynamic-atlas exclusion is the reference's own: those warnings are not
            // streaming/VRAM pressure, and counting them would flag low-end devices as
            // VRAM-starved.
            val oomHit = OOM_RE.containsMatchIn(line)
            if (oomHit) gpuOom++
            if (!oomHit && !TEXTURE_SKIP_RE.containsMatchIn(line) && TEXTURE_HIT_RE.containsMatchIn(line)) {
                textureErrors++
            }
            if (FRAME_DROP_RE.containsMatchIn(line)) dropFrames++
            if (THERMAL_RE.containsMatchIn(line)) thermalEvents++
            if (ADJUST_TRIGGER_RE.containsMatchIn(line)) autoAdjustTriggers++
            if (ADJUST_RECOVER_RE.containsMatchIn(line)) autoAdjustRecoveries++
            if (NETWORK_RE.containsMatchIn(line)) networkErrors++

            // ── Flags (combined single-pass match) ──
            LOW_MEM_RE.find(line)?.let { m ->
                isLowMem = m.groupValues[1].lowercase() == "true"
            }
            FLAG_RE.findAll(line).forEach { m ->
                val g = m.groupValues
                if (g[1].isNotEmpty()) hasVulkanRhi = true
                if (g[2].isNotEmpty() || g[3].isNotEmpty()) hasOpenGl = true
                if (g[4].isNotEmpty()) hasVulkan = true
                if (g[5].isNotEmpty()) hasDirectX = true
                if (g[6].isNotEmpty()) hasMetal = true
            }
            // "vulkanrhi" contains "vulkan", so mirror the original substring behaviour.
            if (hasVulkanRhi) hasVulkan = true

            // ── Field extraction (first match wins) ──
            if (gpu == null) {
                GPU_RE.find(line)?.let { gpu = it.groupValues[1].trim() }
                if (gpu == null) {
                    GPU_LOGINIT_RE.find(line)?.let { gpu = it.groupValues[1].trim() }
                }
                if (gpu == null) {
                    GPU_GENERIC_RE.find(line)?.let { gpu = it.groupValues[1].trim() }
                }
            }
            if (deviceModel == null) {
                DEVMODEL_RE.find(line)?.let { deviceModel = it.groupValues[1].trim() }
                if (deviceModel == null) {
                    DEVMODEL_FALLBACK_RE.find(line)?.let { deviceModel = it.groupValues[1].trim() }
                }
            }
            if (socName == null) SOC_RE.find(line)?.let { socName = it.value }
            if (socCode == null) SOC_CODE_RE.find(line)?.let { socCode = it.groupValues[1] }
            if (cpuName == null) CPU_RE.find(line)?.let { cpuName = it.groupValues[1].trim() }
            if (ramMb == null) {
                RAM_RE.find(line)?.let { ramMb = it.groupValues[1].toIntOrNull() }
                if (ramMb == null) {
                    RAM_GB_RE.find(line)?.let {
                        ramMb = (it.groupValues[1].toFloatOrNull()?.times(1024))?.toInt()
                    }
                }
            }
            if (androidVersion == null) {
                OS_RE.find(line)?.let { androidVersion = it.groupValues[1] }
            }
            if (resolution == null) {
                RES_RE.find(line)?.let {
                    resolution = "${it.groupValues[1]}x${it.groupValues[2]}"
                }
            }
            if (resolution == null) {
                VIEWPORT_RE.find(line)?.let {
                    val w = it.groupValues[1].toFloatOrNull()?.toInt()?.toString() ?: it.groupValues[1]
                    val h = it.groupValues[2].toFloatOrNull()?.toInt()?.toString() ?: it.groupValues[2]
                    resolution = "${w}x$h"
                }
            }
            if (deviceProfile == null) {
                DEV_PROFILE_RE.find(line)?.let { deviceProfile = it.groupValues[1] }
            }
            if (fpsCap == null) {
                FRAME_PACE_RE.find(line)?.let {
                    fpsCap = it.groupValues[1].toIntOrNull()
                }
            }
            if (fpsActual == null) {
                AVG_FPS_RE.find(line)?.let { fpsActual = it.groupValues[1].toFloatOrNull() }
            }
            if (screenPct == null) {
                SCREEN_PCT_RE.find(line)?.let { screenPct = it.groupValues[1].toFloatOrNull() }
            }
            if (shadowQ == null) {
                SHADOW_Q_RE.find(line)?.let { shadowQ = it.groupValues[1].toIntOrNull() }
            }
            if (qualityMode == null) {
                QUALITY_MODE_RE.find(line)?.let { qualityMode = it.groupValues[1] }
            }
            // ── CVar extraction (last write wins, like the game's own console) ──
            CVar_SETTING_RE.find(line)?.let {
                val value = it.groupValues[2].trim().substringBefore(';').trim()
                activeCvars[it.groupValues[1].trim()] = value
            }
            CVar_VALUE_RE.find(line)?.let {
                val value = it.groupValues[1].trim().substringBefore(';').trim()
                activeCvars[it.groupValues[2].trim()] = value
            }

            // ── Game API from LogRHI line ──
            if (gameApi == null) {
                RHI_RE.find(line)?.let { m ->
                    val rhi = m.groupValues[1]
                    gameApi =
                        when {
                            "Vulkan" in rhi -> "Vulkan"
                            "OpenGL" in rhi -> "OpenGL ES"
                            "DirectX" in rhi -> "DirectX"
                            "Metal" in rhi -> "Metal"
                            else -> null
                        }
                }
            }
        }

        val forbiddenCvars = activeCvars.keys.count { WuWaForbiddenCvars.isForbidden(it) }

        // ── Post-loop API resolution (single source of truth) ──
        val explicitApi =
            gameApi
                ?: deviceProfile?.let { if (it.endsWith("_GL", ignoreCase = true)) "OpenGL ES" else null }
                ?: apiFromRhiToken(activeCvars["r.RHI"])
        gameApi = explicitApi
        val api = explicitApi ?: apiFromFlags(hasVulkan, hasOpenGl, hasDirectX, hasMetal)

        val vulkanStatus =
            when (explicitApi) {
                "Vulkan" -> "available"
                "OpenGL ES" -> "not_available"
                else ->
                    when {
                        hasVulkanRhi -> "available"
                        hasOpenGl -> "not_available"
                        else -> null
                    }
            }

        return LogInfo(
            gpu = gpu,
            deviceModel = deviceModel,
            socName = socName,
            socCode = socCode,
            cpuName = cpuName,
            ramMb = ramMb,
            androidVersion = androidVersion,
            resolution = resolution,
            gameApi = gameApi,
            api = api,
            vulkanStatus = vulkanStatus,
            deviceProfile = deviceProfile,
            fpsCap = fpsCap,
            fpsActual = fpsActual,
            screenPct = screenPct,
            shadowQ = shadowQ,
            qualityMode = qualityMode,
            isLowMem = isLowMem,
            textureErrors = textureErrors,
            gpuOom = gpuOom,
            dropFrames = dropFrames,
            forbiddenCvars = forbiddenCvars,
            thermalEvents = thermalEvents,
            autoAdjustTriggers = autoAdjustTriggers,
            autoAdjustRecoveries = autoAdjustRecoveries,
            networkErrors = networkErrors,
            activeCvars = activeCvars
        )
    }

    /**
     * Every `AverageFPS` reading in [text], in file order. [parseLog]'s `fpsActual` keeps
     * first-match-wins because it answers "what did the log's session average"; the benchmark
     * tuner instead needs the whole series to compute its own avg/min/stability over the
     * samples the game actually reported.
     */
    internal fun averageFpsSamples(text: String): List<Float> =
        AVG_FPS_RE.findAll(text).mapNotNull { it.groupValues[1].toFloatOrNull() }.toList()

    /** Maps an RHI CVar value (e.g. from `r.RHI`) to a normalized API name. */
    private fun apiFromRhiToken(token: String?): String? =
        when {
            token == null -> null
            "Vulkan" in token -> "Vulkan"
            "OpenGL" in token -> "OpenGL ES"
            else -> null
        }

    /** Derives the rendering API from the per-line graphics-API flags. */
    private fun apiFromFlags(
        hasVulkan: Boolean,
        hasOpenGl: Boolean,
        hasDirectX: Boolean,
        hasMetal: Boolean
    ): String? =
        when {
            hasVulkan -> "Vulkan"
            hasOpenGl -> "OpenGL ES"
            hasDirectX -> "DirectX"
            hasMetal -> "Metal"
            else -> null
        }

    // Regexes verbatim from the reference's LogParser, including its own comments where
    // the shape is load-bearing.

    private val FRAME_DROP_RE =
        Regex("""frame\s*drop|hitch\s*detected|stutter\s*detected""", RegexOption.IGNORE_CASE)
    private val THERMAL_RE =
        Regex("""thermal\s*(?:throttle|limit|event|warning)""", RegexOption.IGNORE_CASE)
    private val ADJUST_TRIGGER_RE = Regex("""自动渲染调节触发前""")
    private val ADJUST_RECOVER_RE = Regex("""自动渲染调节恢复前""")

    // Combined matchers — each scans the line once instead of one substring
    // search per keyword, cutting the per-line cost from ~22 scans to a handful.
    private val TEXTURE_SKIP_RE = Regex("""logdynamicatlas""", RegexOption.IGNORE_CASE)
    private val TEXTURE_HIT_RE = Regex("""non-streamed mips|failed to load texture""", RegexOption.IGNORE_CASE)
    private val OOM_RE = Regex("""out of memory|gpu oom|vulkanoom""", RegexOption.IGNORE_CASE)
    private val NETWORK_RE =
        Regex(
            """timeout|connection refused|connection reset|unreachable|dns fail|dns failure|socket error|network fail|network failure|ping loss""",
            RegexOption.IGNORE_CASE
        )
    private val LOW_MEM_RE = Regex("""islowmemorymobile:\s*(true|false)""", RegexOption.IGNORE_CASE)
    private val FLAG_RE =
        Regex("""(vulkanrhi)|(opengl es)|(opengl)|(vulkan)|(directx)|(metal)""", RegexOption.IGNORE_CASE)

    private val GPU_RE = Regex("""K#GPUFamily\s*:\s*([^\r\n]+)""", RegexOption.IGNORE_CASE)
    private val GPU_LOGINIT_RE = Regex("""LogInit.*GPU:\s*([^,\r\n]+)""", RegexOption.IGNORE_CASE)
    private val GPU_GENERIC_RE =
        Regex("""(adreno\s*\d+|mali-g\d+|mali-\d+|xclipse\s*\d+|maleoon)""", RegexOption.IGNORE_CASE)
    private val DEVMODEL_RE =
        Regex("""K#DeviceModel\s*:\s*([^\r\n]+)""", RegexOption.IGNORE_CASE)
    private val DEVMODEL_FALLBACK_RE =
        Regex("""DeviceModel\s*:\s*([^\r\n,\]]+)""", RegexOption.IGNORE_CASE)
    private val SOC_RE =
        Regex("""(snapdragon|dimensity|exynos|kirin|helio)\s*\w*""", RegexOption.IGNORE_CASE)
    private val SOC_CODE_RE = Regex("""rHn:(\w+)""", RegexOption.IGNORE_CASE)
    private val CPU_RE = Regex("""LogInit.*CPU:\s*([^,\r\n]+)""", RegexOption.IGNORE_CASE)
    private val RAM_RE = Regex("""PhysicalMemoryMB:\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val RAM_GB_RE =
        Regex("""Platform has ~\s*([\d.]+)\s*GB""", RegexOption.IGNORE_CASE)
    private val OS_RE = Regex("""LogInit.*OS:\s*Android\s*\((\d+)\)""", RegexOption.IGNORE_CASE)
    private val RES_RE =
        Regex("""Resolution\s+(\d+)\s*[,xX×]?\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val VIEWPORT_RE =
        Regex("""ViewportSize\s+([\d.]+),\s*([\d.]+)""", RegexOption.IGNORE_CASE)
    private val DEV_PROFILE_RE =
        Regex("""Selected Device Profile:\s*\[([^\]]+)\]""", RegexOption.IGNORE_CASE)
    private val FRAME_PACE_RE =
        Regex(
            """r\.FramePace\s*:\s*(?:requesting\s+\d+,\s*)?set\s*(?:as\s+)?(\d+)""",
            RegexOption.IGNORE_CASE
        )
    private val AVG_FPS_RE = Regex("""AverageFPS\s*[=:]\s*([\d.]+)""", RegexOption.IGNORE_CASE)
    private val SCREEN_PCT_RE =
        Regex("""Value remains '(\d+\.?\d*)' .* r\.ScreenPercentage""", RegexOption.IGNORE_CASE)
    private val SHADOW_Q_RE =
        Regex("""Value remains '(\d+)' .* sg\.ShadowQuality""", RegexOption.IGNORE_CASE)
    private val QUALITY_MODE_RE =
        Regex("""sg\.KuroRenderQuality\s*=\s*"(.*)"""", RegexOption.IGNORE_CASE)
    private val CVar_SETTING_RE =
        Regex("""Setting CVar \[\[([^:]+):([^\]]+)\]\]""", RegexOption.IGNORE_CASE)
    private val CVar_VALUE_RE =
        Regex("""Value remains '([^']+)' .* variable '([^']+)'""", RegexOption.IGNORE_CASE)
    private val RHI_RE =
        Regex("""LogRHI:\s*Initializing\s+(\S+(?:\s+\S+)*?)\s*RHI""", RegexOption.IGNORE_CASE)
}
