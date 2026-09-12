package com.catsmoker.app.features.editgamefiles.wuwa

/**
 * Recommends a WuWa preset from measurable device facts, plus — when the game's Client.log
 * has been decrypted and analyzed — the gameplay evidence it carries.
 *
 * Ported from `referance/gamingtools/WuWa-Config-Android-main/config/SmartBrain.kt` (+ its
 * GPU tier table from `config/CvarOptimizer.kt`), both read in full before this file was
 * written. The scoring values and the preset ladder thresholds are the reference's own.
 *
 * The two-tier honesty rule this port lives by:
 *
 *  - **Device axes** (RAM, panel resolution, Vulkan support, low-RAM flag) are
 *    always measurable — ActivityManager, PackageManager — and always weighed. The GPU tier
 *    rides with the log that reports the GL_RENDERER string: a measured-but-unrecognised GPU
 *    scores the reference's own −20, while no reading at all (null) stays unscored and is
 *    named in [Recommendation.logOnlyAxes] — mirroring the unread-RAM guard, since the
 *    reference always had its `LogInfo` and never faced a missing GPU.
 *  - **Log axes** (FPS vs the cap, thermal events, GPU OOMs, frame drops, auto-adjust,
 *    texture errors, render scale, the live cvar values) are weighed **only when a decrypted
 *    log supplied them**, and an axis without a reading is named in
 *    [Recommendation.logOnlyAxes] rather than quietly scored as a neutral zero. The
 *    reference's scorer always had its `LogInfo`; this port earns each axis the moment a
 *    [WuwaLogDecryptor]/[WuwaLogParser] analysis provides it, and the ladder widens to the
 *    reference's exact shape only then: cinematic requires a *measured* zero thermal count,
 *    competitive a *measured* FPS-vs-cap gap, and the endurance rungs measured auto-adjust /
 *    thermal counts. Without a log the top of the ladder is ultra (held at the deliberately
 *    conservative ≥85 rather than the reference's ≥80, because claiming cinematic-grade
 *    headroom without any thermal evidence is exactly the "success it had not verified" this
 *    app exists to prevent).
 *
 * The one reference axis-family still unweighed even with a log: the cvar-database axes
 * (unknown / monitored / changed-from-default cvar counts). Weighing them needs the
 * `CvarDatabase` the generator port deliberately does not ship — emitting its verdicts
 * without the database would fabricate the verification that made them safe.
 */
object WuwaSmartBrain {

    /**
     * What the device — and, when analyzed, the decrypted game log — can tell us.
     * null = could not read / not analyzed; an axis left null is never scored.
     */
    data class DeviceSignals(
        /** GL_RENDERER string — the same string the generator's GPU ladders key on. */
        val gpu: String? = null,
        /** Total RAM in MB from ActivityManager. null = unreadable. */
        val ramMb: Int? = null,
        /** Panel resolution label, "WxH". */
        val resolution: String? = null,
        /** PackageManager FEATURE_VULKAN_HARDWARE_VERSION. null = could not check. */
        val vulkanAvailable: Boolean? = null,
        /** ActivityManager.isLowRamDevice. null = could not check. */
        val lowRamDevice: Boolean? = null,
        // ---- measured from the decrypted Client.log; null until one is analyzed ----
        val fpsActual: Float? = null,
        val fpsCap: Int? = null,
        val thermalEvents: Int? = null,
        val gpuOom: Int? = null,
        val dropFrames: Int? = null,
        val autoAdjustTriggers: Int? = null,
        val autoAdjustRecoveries: Int? = null,
        val textureErrors: Int? = null,
        val networkErrors: Int? = null,
        val screenPct: Float? = null,
        /** Live cvar values from the log's `Setting CVar` / `Value remains` lines. */
        val activeCvars: Map<String, String> = emptyMap()
    )

    data class Recommendation(
        val preset: String,
        val score: Int,
        val tier: String,
        /** The scoring axes that actually fired, reference wording ("Flagship GPU: +30"). */
        val signals: List<String>,
        val warnings: List<String>,
        /** Axes not weighed because no reading existed — named, never invented. */
        val logOnlyAxes: List<String>,
        /** True when at least one log-derived axis was scored. */
        val hadLogEvidence: Boolean
    )

    /**
     * The reference's GPU tier table (`CvarOptimizer.GPU_TIER_PATTERNS`), verbatim and in
     * order — first match wins, so the ordering is part of the data. Coarser than the
     * generator's own HIGH_END/MID/LOW ladders (which cap shadow resolution): those tune
     * cvars, this one scores headroom, and keeping the two tables separate keeps each pinned
     * against the reference that defined it.
     */
    private val GPU_TIER_PATTERNS = listOf(
        Regex("""adreno.*8[3-9]\d|adreno.*8[12]\d""") to "flagship",
        Regex("""tensor\s*g[345]""") to "flagship",
        Regex("""dimensity\s*9[3-9]\d\d?""") to "flagship",
        Regex("""apple\s*(m[34]|a18)""") to "flagship",
        Regex("""adreno.*7[5-9]\d|adreno.*8[0]\d""") to "high",
        Regex("""tensor\s*g[12]""") to "high",
        Regex("""dimensity\s*(9[0-2]\d|8[5-9]\d)""") to "high",
        Regex("""exynos\s*2200""") to "high",
        Regex("""kirin\s*9000""") to "high",
        Regex("""mali-g(7[6-9]|8\d|9\d)\d?""") to "high",
        Regex("""apple\s*(m[12]|a1[67])""") to "high",
        Regex("""adreno.*7[0-4]\d|adreno.*6[5-9]\d""") to "mid_high",
        Regex("""dimensity\s*(8[0-4]\d|7[3-9]\d)""") to "mid_high",
        Regex("""tensor""") to "mid_high",
        Regex("""exynos\s*2[1-3]00""") to "mid_high",
        Regex("""kirin\s*9[1-9]\d\d?""") to "mid_high",
        Regex("""xclipse""") to "mid_high",
        Regex("""apple\s*a1[45]""") to "mid_high",
        Regex("""adreno.*6[0-4]\d|mali-g(6\d|7[0-5])\d?|mali-g615""") to "mid",
        Regex("""dimensity\s*[0-9]{3}""") to "mid",
        Regex("""exynos\s*[0-9]{4}""") to "mid",
        Regex("""kirin\s*[0-9]{4}""") to "mid",
        Regex("""apple\s*a1[23]""") to "mid",
        Regex("""adreno.*5\d\d|mali-g5\d?""") to "mid_low",
        Regex("""adreno.*[34]\d\d|mali-g[34]""") to "low"
    )

    fun gpuTier(gpu: String?): String {
        val g = gpu?.lowercase() ?: return "unknown"
        for ((pattern, tier) in GPU_TIER_PATTERNS) {
            if (pattern.containsMatchIn(g)) return tier
        }
        return "unknown"
    }

    private fun hasCvar(cvars: Map<String, String>, key: String): Boolean =
        cvars.any { it.key.equals(key, ignoreCase = true) }

    private fun cvarValue(cvars: Map<String, String>, key: String): String? =
        cvars.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value

    fun recommend(s: DeviceSignals): Recommendation {
        var score = 50
        val signals = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        var logEvidence = false

        val tier = gpuTier(s.gpu)
        // Null means no reading exists (no log analyzed yet) — not a weak GPU. Like unread
        // RAM below, it stays unscored and is named in logOnlyAxes; a non-null string nobody
        // recognises still scores the reference's own −20.
        val gpuMeasured = s.gpu != null
        if (!gpuMeasured) {
            signals.add("GPU unread: +0 (not scored)")
        } else when (tier) {
            "flagship" -> {
                score += 30; signals.add("Flagship GPU: +30")
            }
            "high" -> {
                score += 20; signals.add("High-end GPU: +20")
            }
            "mid_high" -> {
                score += 10; signals.add("Mid-high GPU: +10")
            }
            "mid" -> { /* +0 */ }
            "mid_low" -> {
                score -= 10; signals.add("Low-mid GPU: -10")
            }
            else -> {
                score -= 20; signals.add("Low-end GPU: -20")
            }
        }

        val ram = s.ramMb ?: 0
        when {
            ram >= 8000 -> {
                score += 8; signals.add("8GB+ RAM: +8")
            }
            ram >= 6000 -> {
                score += 5; signals.add("6GB+ RAM: +5")
            }
            ram in 4000..5999 -> { /* +0 */ }
            ram in 1..3999 -> {
                score -= 15; signals.add("<4GB RAM: -15")
            }
        }

        if (s.vulkanAvailable == true) {
            score += 8
            signals.add("Vulkan: +8")
        }

        // ---- log axes: each block runs only when the log supplied the reading ----

        s.fpsActual?.let { actual ->
            logEvidence = true
            s.fpsCap?.let { cap ->
                if (cap > 0) {
                    val dropPct = ((cap - actual) / cap) * 100
                    when {
                        dropPct > 30 -> {
                            score -= 18; signals.add("FPS drop >30%: -18")
                        }
                        dropPct > 20 -> {
                            score -= 12; signals.add("FPS drop 20-30%: -12")
                        }
                        dropPct > 10 -> {
                            score -= 6; signals.add("FPS drop 10-20%: -6")
                        }
                        actual >= cap * 0.95f -> {
                            score += 5; signals.add("FPS at target: +5")
                        }
                    }
                }
            }
            if (actual < 30) {
                score -= 15; signals.add("FPS <30: -15")
            }
            if (actual in 30f..44f) {
                score -= 8; signals.add("FPS 30-45: -8")
            }
        }

        s.thermalEvents?.let { t ->
            logEvidence = true
            when {
                t >= 5 -> {
                    score -= 20; signals.add("Thermal throttling x$t: -20")
                }
                t >= 3 -> {
                    score -= 12; signals.add("Thermal events x$t: -12")
                }
                t >= 1 -> {
                    score -= 5; signals.add("Thermal events x$t: -5")
                }
            }
        }
        s.gpuOom?.let { oom ->
            logEvidence = true
            when {
                oom >= 3 -> {
                    score -= 30; signals.add("GPU OOM x$oom: -30")
                }
                oom >= 2 -> {
                    score -= 20; signals.add("GPU OOM x$oom: -20")
                }
                oom >= 1 -> {
                    score -= 12; signals.add("GPU OOM x$oom: -12")
                }
            }
        }
        s.dropFrames?.let { drops ->
            logEvidence = true
            when {
                drops >= 15 -> {
                    score -= 10; signals.add("Frame drops x$drops: -10")
                }
                drops >= 5 -> {
                    score -= 5; signals.add("Frame drops x$drops: -5")
                }
            }
        }
        s.autoAdjustTriggers?.let { triggers ->
            logEvidence = true
            when {
                triggers >= 30 -> {
                    score -= 15; signals.add("Extreme auto-adjust x$triggers: -15")
                    warnings.add("Auto quality system triggered ${triggers}x — device struggling heavily")
                }
                triggers >= 15 -> {
                    score -= 8; signals.add("Frequent auto-adjust x$triggers: -8")
                }
                triggers >= 5 -> {
                    score -= 3; signals.add("Occasional auto-adjust x$triggers: -3")
                }
            }
            if (triggers > 0 && triggers > (s.autoAdjustRecoveries ?: 0) * 3) {
                warnings.add("Auto-adjust unable to stabilize (triggers=$triggers, recoveries=${s.autoAdjustRecoveries ?: 0})")
            }
        }
        s.networkErrors?.let { net ->
            logEvidence = true
            when {
                net >= 10 -> {
                    score -= 10; signals.add("Network issues x$net: -10")
                }
                net >= 5 -> {
                    score -= 5; signals.add("Network issues x$net: -5")
                }
            }
        }
        s.screenPct?.let { sp ->
            logEvidence = true
            when {
                sp >= 125f -> {
                    score += 5; signals.add("Very high render scale: +5")
                }
                sp >= 110f -> {
                    score += 3; signals.add("High render scale: +3")
                }
                sp < 70f -> {
                    score -= 10; signals.add("Low render scale <70%: -10")
                }
                else -> {}
            }
        }

        // Resolution penalties use the shorter panel edge — a 1440x3200 phone is a QHD panel.
        val res = parseResolution(s.resolution)
        val effectiveRes = res?.let { minOf(it.first, it.second) } ?: 0
        val isHighRes = effectiveRes >= 1440
        val is4k = effectiveRes >= 2160
        if (is4k) {
            score -= 10
            signals.add("4K resolution: -10")
            // The combo extra assumes a measured weak tier — an unread GPU is not evidence.
            if (gpuMeasured && tier in listOf("mid", "mid_low", "low", "unknown")) {
                score -= 8
                signals.add("4K on $tier GPU: -8")
                warnings.add("4K on mid/low-end GPU — ultra preset not advisable")
            }
        } else if (isHighRes) {
            score -= 4
            signals.add("QHD+ resolution: -4")
            if (gpuMeasured && (tier == "mid_low" || tier == "low" || tier == "unknown")) {
                score -= 6
                signals.add("QHD+ on $tier GPU: -6")
            }
        }

        s.textureErrors?.let { tex ->
            logEvidence = true
            if (tex > 0) {
                val texPenalty = minOf(tex, 20)
                score -= texPenalty
                signals.add("Texture errors x$tex: -$texPenalty")
                if (tex >= 5) warnings.add("Frequent texture errors — possible VRAM pressure, lower texture quality")
            }
        }

        // Live-cvar combos — the reference's second cvar block, which needs no database.
        val cvars = s.activeCvars
        if (cvars.isNotEmpty()) {
            logEvidence = true
            val shadowQ = cvarValue(cvars, "sg.ShadowQuality")?.toIntOrNull()
            val texQ = cvarValue(cvars, "sg.TextureQuality")?.toIntOrNull()
            val resScale = cvarValue(cvars, "r.ScreenPercentage")?.toFloatOrNull()
            val fpsLimit = cvarValue(cvars, "r.FramePace")?.toIntOrNull()
            val ssao = hasCvar(cvars, "r.Mobile.SSAO")
            val fsr = hasCvar(cvars, "r.FidelityFX.FSR.RCAS")
            val bloom = cvarValue(cvars, "r.BloomQuality")?.toIntOrNull()

            if (shadowQ != null) {
                when {
                    shadowQ >= 3 && (tier == "mid_low" || tier == "low") -> {
                        score -= 6; signals.add("High shadows on low GPU: -6")
                    }
                    shadowQ >= 3 && ram < 6000 -> {
                        score -= 4; signals.add("High shadows + <6GB RAM: -4")
                    }
                }
            }
            if (texQ != null && texQ >= 3 && ram < 6000) {
                score -= 5
                signals.add("High textures + <6GB RAM: -5")
                if ((s.textureErrors ?: 0) > 0) {
                    score -= 4
                    signals.add("High textures + texture errors: -4")
                }
            }
            if (resScale != null && resScale > 100f) {
                val overScale = ((resScale - 100) / 10).toInt()
                score -= minOf(overScale, 8)
                signals.add("Render scale >100%: -${minOf(overScale, 8)}")
            }
            if (fpsLimit != null && fpsLimit >= 90 && (s.thermalEvents ?: 0) >= 3) {
                score -= 6
                signals.add("High FPS target + thermal: -6")
                warnings.add("High FPS target (${fpsLimit}fps) on thermally throttled device")
            }
            if (fsr) {
                score -= 8
                signals.add("FSR RCAS enabled: -8")
            }
            if (ssao && (tier == "mid_low" || tier == "low")) {
                score -= 5
                signals.add("SSAO on low GPU: -5")
            }
            if (bloom != null && bloom >= 3 && (s.thermalEvents ?: 0) >= 3) {
                score -= 3
                signals.add("High bloom + thermal: -3")
            }
        }

        if (s.lowRamDevice == true) {
            score -= 15
            signals.add("Low memory device: -15")
        }

        // This guard is this port's own (the reference defaults unread RAM to 0): an unread
        // RAM must not masquerade as a low-RAM reading.
        val isLowRam = ram < 6000
        if (isLowRam && isHighRes && s.ramMb != null) {
            score -= 5
            signals.add("Low RAM + high res: -5")
            warnings.add("High resolution on device with <6GB RAM")
        }
        if ((s.thermalEvents ?: 0) >= 3 && (tier == "mid_low" || tier == "low")) {
            val extra = (s.thermalEvents ?: 0).coerceAtMost(6)
            score -= extra
            signals.add("Thermal + low GPU combo: -$extra")
        }
        if ((s.gpuOom ?: 0) > 0 && (s.textureErrors ?: 0) > 3) {
            score -= 5
            signals.add("OOM + texture errors: -5")
        }

        score = score.coerceIn(0, 100)

        if ((s.gpuOom ?: 0) > 0) warnings.add("GPU OOM detected — performance preset recommended")
        if ((s.thermalEvents ?: 0) >= 3) warnings.add("Heavy thermal throttling — consider lower preset")

        val preset = recommendPreset(score, tier, isHighRes, s.vulkanAvailable, s.lowRamDevice, s)
        return Recommendation(preset, score, tier, signals, warnings, logOnlyAxes(s), logEvidence)
    }

    /**
     * The reference's ladder, with its log-gated rungs gated the honest way: each needs a
     * *measured* value, and without one the B20 narrowing applies (no cinematic, no
     * competitive, no endurance, and ultra held at the conservative ≥85 — see class KDoc).
     */
    private fun recommendPreset(
        score: Int,
        tier: String,
        isHighRes: Boolean,
        vulkan: Boolean?,
        lowRamDevice: Boolean?,
        s: DeviceSignals
    ): String {
        val tierLimited = tier == "flagship" || tier == "high"
        val thermal = s.thermalEvents
        return when {
            (s.gpuOom ?: 0) >= 2 -> "potato"
            lowRamDevice == true || score <= 20 -> "potato"
            thermal != null && score >= 85 && vulkan == true && tier == "flagship" && !isHighRes && thermal == 0 -> "cinematic"
            thermal != null && score >= 80 && vulkan == true && tier == "flagship" && !isHighRes -> "ultra"
            thermal == null && score >= 85 && vulkan == true && tier == "flagship" && !isHighRes -> "ultra"
            score >= 75 && tierLimited && vulkan == true -> "high"
            score >= 70 && tierLimited -> "high"
            score >= 45 && s.fpsActual != null && s.fpsCap != null && (s.fpsCap - s.fpsActual.toInt()) > 15 -> "competitive"
            score >= 40 -> "balanced"
            (s.autoAdjustTriggers ?: 0) > 10 && score >= 25 -> "endurance"
            thermal != null && thermal >= 3 && score >= 30 -> "endurance"
            (s.autoAdjustTriggers ?: 0) > 10 -> "performance"
            score >= 20 -> "performance"
            else -> "potato"
        }
    }

    /** Axes this recommendation could not weigh — only those actually missing a reading. */
    private fun logOnlyAxes(s: DeviceSignals): List<String> = buildList {
        if (s.gpu == null) add("GPU tier (no log analyzed yet)")
        if (s.fpsActual == null || s.fpsCap == null) add("actual FPS vs the cap")
        if (s.thermalEvents == null) add("thermal throttling events")
        if (s.gpuOom == null) add("GPU out-of-memory count")
        if (s.dropFrames == null) add("frame drops")
        if (s.autoAdjustTriggers == null) add("auto-adjust triggers / recoveries")
        if (s.textureErrors == null) add("texture errors")
        if (s.networkErrors == null) add("network errors")
        if (s.screenPct == null) add("render scale")
        if (s.activeCvars.isEmpty()) add("active cvars")
        // Never weighable: needs the CvarDatabase the generator port deliberately omits.
        add("cvar database axes (unknown / monitored / changed-from-default counts)")
    }

    private fun parseResolution(res: String?): Pair<Int, Int>? {
        if (res == null) return null
        val parts = res.trim().split(Regex("\\s*[xX*]\\s*"))
        val w = parts.firstOrNull()?.toIntOrNull() ?: return null
        val h = parts.getOrNull(1)?.toIntOrNull() ?: return null
        return w to h
    }
}
