package com.catsmoker.app.features.editgamefiles.wuwa

/**
 * Pure generator for Wuthering Waves' Unreal Engine config files: `Engine.ini`,
 * `DeviceProfiles.ini`, `GameUserSettings.ini`, plus the optional `Scalability.ini` and
 * `Hardware.ini`.
 *
 * Ported from `referance/gamingtools/WuWa-Config-Android-main/config/ConfigGenerator.kt`,
 * which was read in full before this file was written: the 8-preset table, every section
 * builder and its exact cvar lines, the device-tier ladders, the forbidden-cvar strip, the
 * dedup pass, and the `Core.System` path extraction are line-for-line where the surrounding
 * subsystems allow. The pinned format lives in [WuWaConfigGeneratorTest].
 *
 * Deliberate divergences from the reference, each because the subsystem it hangs off was
 * not ported:
 *  - No **CvarDatabase** (built from the game's own cvar dump), so the reference's
 *    "DB-verified enrichment" section is simply absent — emitting those cvars without the
 *    database would be fabricating the very verification that made them safe.
 *  - No **log-cvar import / per-device CvarOptimizer retune**: the builders run on
 *    [WuWaDeviceInfo] facts this device reported (EGL, Build, DisplayMetricsProvider) plus
 *    whatever [existingEngineIni] could be read, not on a log the user had to go find.
 *    The log-derived axes live downstream instead — [WuwaLogDecryptor]/[WuwaLogParser]
 *    feed [WuwaSmartBrain] and [WuwaBenchmarkTuner], which re-score and re-step from measured
 *    evidence. Thermal throttling is therefore never guessed at inside the generator.
 *  - The reference's [LogInfo] is filled from an uploaded game log. Here
 *    [WuWaDeviceInfo] is filled from real device reads — EGL for the GPU strings,
 *    `Build` for the model and SoC, [com.catsmoker.app.shared.util.DisplayMetricsProvider]
 *    for the resolution — so the same builders run on facts this device reported, not on a
 *    log the user had to go find.
 *  - The header box says CatSmoker instead of the reference app's own name; the ASCII logo
 *    spelled that name and is not something to copy.
 */
object WuWaConfigGenerator {

    /** Ordered preset ids, lowest to highest tier. */
    val PRESET_ORDER = listOf(
        "potato", "endurance", "performance", "competitive", "balanced", "high", "ultra", "cinematic"
    )

    /**
     * The fine-grained tuning profile behind each named preset. Values are the reference's
     * own, per-preset-tuned (not derived): `screen` is the render-percentage, `shadowRes` the
     * per-object shadow map size, `ssr`/`mipbias`/`streaming`/`vd`/`flod`/`lod_bias`/
     * `grasscull` the distance and quality scalars the section builders consume.
     */
    data class PresetProfile(
        val screen: Int,
        val shadow: Int,
        val shadowRes: Int,
        val ssr: Int,
        val mipbias: Int,
        val streaming: Double,
        val vd: Double,
        val flod: Double,
        val detail: Int,
        val lod_bias: Int,
        val grasscull: Int,
        val characterDetail: Int = 2,
        val postProcess: Int = 2,
        val staticLighting: Boolean = true,
        val cutsceneQuality: Int = 2
    ) {
        /**
         * Maps the preset's fine-grained [detail] rank onto the three boolean gates the
         * builders branch on (`>0` / `>1` / `>2`) — the reference's own mapping, kept so every
         * branch keeps its original meaning. The 8 presets occupy distinct ranks (0..7).
         */
        val q0: Boolean get() = detail > 0
        val q1: Boolean get() = detail > 1
        val q2: Boolean get() = detail > 2
    }

    val PRESETS = mapOf(
        "potato" to PresetProfile(
            screen = 60, shadow = 0, shadowRes = 128, ssr = 0, mipbias = 3,
            streaming = 0.3, vd = 0.3, flod = 0.4, detail = 0, lod_bias = 5, grasscull = 1500,
            characterDetail = 0, postProcess = 0, staticLighting = false, cutsceneQuality = 0
        ),
        "endurance" to PresetProfile(
            screen = 70, shadow = 0, shadowRes = 128, ssr = 0, mipbias = 3,
            streaming = 0.4, vd = 0.4, flod = 0.5, detail = 1, lod_bias = 4, grasscull = 2500,
            characterDetail = 0, postProcess = 0, staticLighting = false, cutsceneQuality = 0
        ),
        "performance" to PresetProfile(
            screen = 60, shadow = 0, shadowRes = 256, ssr = 0, mipbias = 3,
            streaming = 0.5, vd = 0.5, flod = 0.6, detail = 2, lod_bias = 3, grasscull = 4500,
            characterDetail = 1, postProcess = 1, staticLighting = false, cutsceneQuality = 1
        ),
        "competitive" to PresetProfile(
            screen = 100, shadow = 2, shadowRes = 256, ssr = 0, mipbias = 1,
            streaming = 1.0, vd = 2.0, flod = 1.0, detail = 3, lod_bias = 1, grasscull = 2000,
            characterDetail = 1, postProcess = 1, staticLighting = false, cutsceneQuality = 1
        ),
        "balanced" to PresetProfile(
            screen = 80, shadow = 2, shadowRes = 1024, ssr = 1, mipbias = 0,
            streaming = 2.0, vd = 1.5, flod = 2.0, detail = 4, lod_bias = 0, grasscull = 15000,
            characterDetail = 2, postProcess = 2, staticLighting = true, cutsceneQuality = 2
        ),
        "high" to PresetProfile(
            screen = 100, shadow = 4, shadowRes = 2048, ssr = 2, mipbias = 0,
            streaming = 3.0, vd = 2.0, flod = 2.5, detail = 5, lod_bias = 0, grasscull = 20000,
            characterDetail = 2, postProcess = 2, staticLighting = true, cutsceneQuality = 2
        ),
        "ultra" to PresetProfile(
            screen = 100, shadow = 5, shadowRes = 2048, ssr = 4, mipbias = -1,
            streaming = 4.0, vd = 3.0, flod = 3.0, detail = 6, lod_bias = -1, grasscull = 30000,
            characterDetail = 3, postProcess = 3, staticLighting = true, cutsceneQuality = 3
        ),
        "cinematic" to PresetProfile(
            screen = 100, shadow = 5, shadowRes = 4096, ssr = 4, mipbias = -2,
            streaming = 6.0, vd = 4.0, flod = 4.0, detail = 7, lod_bias = -2, grasscull = 40000,
            characterDetail = 3, postProcess = 3, staticLighting = true, cutsceneQuality = 3
        )
    )

    /** The options the generator branches on — the reference's `GeneratorOptions`, minus the
     *  options belonging to subsystems not ported (cvar DB, log import, retune). */
    data class Options(
        val fps: Int = 60,
        val unlock120: Boolean = false,
        val unlockUltra: Boolean = true,
        val vsync: Boolean = true,
        val cool: Boolean = true,
        val vulkan: Boolean = false,
        val hzb: Boolean = false,
        val fog: Boolean = false,
        val ca: Boolean = true,
        val disableOutline: Boolean = false,
        val disableRadialBlur: Boolean = false,
        val disableBloom: Boolean = false,
        val disableAutoExposure: Boolean = false,
        val disableSSR: Boolean = false,
        val disableAutoAdjust: Boolean = false,
        val mode: GameMode = GameMode.Overworld,
        val generateScalability: Boolean = false,
        val generateHardware: Boolean = false,
        val allowRestrictedCvars: Boolean = true,
        val enableGSR: Boolean = false,
        val experimentalCvars: Boolean = false
    )

    enum class GameMode(val label: String) {
        Overworld("Overworld"),
        ToA("Tower of Adversity")
    }

    /**
     * Real facts about this device, feeding the same fields the reference's `LogInfo` fed the
     * builders. Every field is nullable because every source can fail; the builders treat null
     * the way the reference treats "no uploaded log" (universal profiles, lowest device tier,
     * 1280x720 fallback) rather than inventing a value.
     */
    data class DeviceInfo(
        val gpu: String? = null,
        val deviceModel: String? = null,
        val socModel: String? = null,
        /** `"1440x3200"` — parsed by [parseResolution]. */
        val resolution: String? = null
    )

    /** The five files a deploy can push; the blank ones are skipped by the deploy channel. */
    data class GeneratedConfigs(
        val engine: String,
        val deviceProfiles: String,
        val gameUserSettings: String,
        val scalability: String = "",
        val hardware: String = ""
    ) {
        /** File name to content, deployment order. Blank entries are left out. */
        fun asMap(): Map<String, String> = buildMap {
            put("Engine.ini", engine)
            put("DeviceProfiles.ini", deviceProfiles)
            put("GameUserSettings.ini", gameUserSettings)
            if (scalability.isNotBlank()) put("Scalability.ini", scalability)
            if (hardware.isNotBlank()) put("Hardware.ini", hardware)
        }
    }

    /**
     * Generates all files for [preset] (unknown ids fall back to `balanced`, like the
     * reference). [existingEngineIni] is the `Engine.ini` already on the device when it could
     * be read: its `[Core.System]` paths are reused verbatim, because that list tracks the
     * game's own installed content plugins and a stale copy could break content resolution.
     */
    fun generate(
        preset: String,
        opts: Options,
        deviceInfo: DeviceInfo,
        existingEngineIni: String? = null
    ): GeneratedConfigs {
        val p = PRESETS[preset] ?: PRESETS.getValue("balanced")
        val corePaths = extractCoreSystemPaths(existingEngineIni)
        return generateWithCorePaths(p, if (preset in PRESETS) preset else "balanced", opts, deviceInfo, corePaths)
    }

    internal fun generateWithCorePaths(
        p: PresetProfile,
        preset: String,
        opts: Options,
        deviceInfo: DeviceInfo,
        corePaths: List<String>
    ): GeneratedConfigs {
        var engine = buildEngineIni(p, preset, opts, deviceInfo, corePaths)
        var deviceProfiles = buildDeviceProfilesIni(p, preset, opts, deviceInfo)
        val gameUserSettings = buildGameUserSettingsIni(p, opts, deviceInfo)
        var scalability = if (opts.generateScalability) buildScalabilityIni(p, opts) else ""
        var hardware = if (opts.generateHardware) buildHardwareIni(p, preset, opts, deviceInfo) else ""

        // Same post-processing order as the reference: dedup, then the forbidden-cvar strip
        // over all five files (only when restricted cvars are disallowed).
        engine = deduplicateIniText(engine)
        if (!opts.allowRestrictedCvars) {
            engine = WuWaForbiddenCvars.stripForbiddenCvars(engine)
            deviceProfiles = WuWaForbiddenCvars.stripForbiddenCvars(deviceProfiles)
            val strippedGus = WuWaForbiddenCvars.stripForbiddenCvars(gameUserSettings)
            scalability = if (scalability.isNotBlank()) WuWaForbiddenCvars.stripForbiddenCvars(scalability) else scalability
            hardware = if (hardware.isNotBlank()) WuWaForbiddenCvars.stripForbiddenCvars(hardware) else hardware
            return GeneratedConfigs(
                engine = engine,
                deviceProfiles = deviceProfiles,
                gameUserSettings = strippedGus,
                scalability = scalability,
                hardware = hardware
            )
        }
        return GeneratedConfigs(
            engine = engine,
            deviceProfiles = deviceProfiles,
            gameUserSettings = gameUserSettings,
            scalability = scalability,
            hardware = hardware
        )
    }

    // ── Device tier ─────────────────────────────────────────────────────────────

    /**
     * The device-tier values every builder shares. Same ladders and GPU patterns as the
     * reference; `hasThermalIssues` is fixed false here because detecting throttling needs
     * the (encrypted) game log — see the class KDoc.
     */
    private data class DeviceTier(
        val isHighEnd: Boolean,
        val isMid: Boolean,
        val streamPool: Int,
        val maxAniso: Int,
        val landscapeCaptureDist: Int,
        val skinCacheMem: Int,
        val ismDist: Int,
        val ismRad: Int,
        val grassCull: Int,
        val npcDist: Int
    )

    private fun computeDeviceTier(deviceInfo: DeviceInfo): DeviceTier {
        val gpu = (deviceInfo.gpu ?: "").lowercase()
        val isHighEnd = HIGH_END_GPU_PATTERNS.any { it.containsMatchIn(gpu) }
        val isMid = MID_GPU_PATTERNS.any { it.containsMatchIn(gpu) }
        return if (isHighEnd) {
            DeviceTier(true, false, 800, 16, 8000, 384, 14000, 18000, 2000, 15000)
        } else if (isMid) {
            DeviceTier(false, true, 500, 8, 6000, 256, 10000, 13000, 1200, 10000)
        } else {
            DeviceTier(false, false, 380, 4, 4000, 192, 7000, 9000, 800, 7000)
        }
    }

    // ── Engine.ini ──────────────────────────────────────────────────────────────

    private fun buildEngineIni(
        p: PresetProfile,
        preset: String,
        opts: Options,
        deviceInfo: DeviceInfo,
        corePaths: List<String>
    ): String {
        val dt = computeDeviceTier(deviceInfo)
        // No log parsing, so Vulkan support is only ever asserted when the user says so.
        val hasVulkan = opts.vulkan
        val lines = mutableListOf<String>()
        lines.add(configHeader(preset, deviceInfo))
        lines.add("")
        corePaths.forEach { lines.add(it) }
        lines.add("")
        lines.add("[SystemSettings]")
        lines.add("")
        lines.addAll(buildCharacterQualitySection(p, opts, dt))
        lines.addAll(buildAntiAliasingSection(p))
        lines.addAll(buildPostProcessingSection(p, opts))
        lines.addAll(buildShadowSection(p, dt))
        lines.addAll(buildTextureStreamingSection(p, dt))
        lines.addAll(buildMobileRenderingSection(p))
        lines.addAll(buildVrsSection())
        lines.addAll(buildEffectsParticlesSection(p))
        lines.addAll(buildWaterReflectionSection(p, opts, dt))
        lines.addAll(buildScreenSpaceEffectsSection(p, opts, dt))
        lines.addAll(buildEnvironmentSection(p, dt))
        lines.addAll(buildNpcWorldSection(p, dt, opts))
        lines.addAll(buildAdvancedLodCullingSection(p))
        lines.addAll(buildAnimationBlueprintSection(p))
        lines.addAll(buildFrameDisplaySection(p, opts, dt))
        lines.addAll(buildPipelineRhiSection())
        lines.addAll(buildThermalStabilitySection(opts, dt, hasVulkan))
        lines.addAll(buildForbiddenCvarOverridesSection())
        lines.addAll(buildPerformanceTweaksSection(p, preset))
        lines.addAll(buildExperimentalCvarsSection(opts))
        lines.addAll(buildGameModeToaSection(opts))
        lines.add("[/Script/Engine.StreamingSettings]")
        lines.add("s.TimeLimitExceededMultiplier=1.5")
        lines.add("s.AsyncLoadingThreadEnabled=1")
        lines.add("s.EventDrivenLoaderEnabled=1")
        lines.add("")
        lines.add("[/Script/Engine.GarbageCollectionSettings]")
        lines.add("gc.LowMemory.TimeBetweenPurgingPendingLevels=20")
        lines.add("")
        lines.addAll(buildGsrSection(opts))
        return lines.joinToString("\n")
    }

    /**
     * Pad/truncate to a fixed width so the ASCII box never misaligns when a device model or
     * GPU string is longer than the field reserves (the reference's own fix).
     */
    private fun configHeader(preset: String, deviceInfo: DeviceInfo): String {
        val timestamp = HEADER_TIME_FMT.format(java.time.LocalDateTime.now())
        val device = (deviceInfo.deviceModel ?: "Generic").take(30).padEnd(30)
        val gpu = (deviceInfo.gpu ?: "Generic GPU").take(30).padEnd(30)
        val presetName = preset.uppercase().take(30).padEnd(30)
        return listOf(
            "; ┌───[ CATSMOKER :: WUWA PERFORMANCE CONFIG ]──────────────────────────────┐",
            "; │   [ ENGINE ] : Unreal Engine 4 / Wuthering Waves                         │",
            "; │   [ PRESET ] : $presetName│",
            "; │   [ DEVICE ] : $device│",
            "; │   [ GPU    ] : $gpu│",
            "; │   [ TIME   ] : $timestamp│",
            "; └──────────────────────────────────────────────────────────────────────────┘",
            ""
        ).joinToString("\n")
    }

    private val HEADER_TIME_FMT =
        java.time.format.DateTimeFormatter.ofPattern("yyyy.MM.dd @ HH:mm", java.util.Locale.US)

    private fun buildCharacterQualitySection(p: PresetProfile, opts: Options, dt: DeviceTier): List<String> {
        val charOutline = if (p.q1) 1200 else if (p.q0) 950 else 850
        val charEyeDist = if (p.q1) 700 else if (p.q0) 550 else 450
        val charLODScale = if (p.q1) 7.0 else if (p.q0) 6.0 else 5.0
        val outlineScale = if (opts.disableOutline) "0" else if (p.q1) "1.3" else if (p.q0) "1.2" else "1.1"
        val autoExposure = if (opts.disableAutoExposure) "0" else "1"
        val radialBlur = if (opts.disableRadialBlur) "0" else if (p.q1) "0.9" else if (p.q0) "0.75" else "0.6"
        val landscapeCaptureSize = if (p.q0) 2 else 1
        return listOf(
            "; ── CHARACTER QUALITY ─────────────────────────────────",
            "r.KuroMaterialQualityLevel=${p.characterDetail.coerceIn(0, 3)}",
            "r.Kuro.KuroToonFFTHighQuality=${if (p.characterDetail >= 2) 1 else 0}",
            "r.Shadow.SkeletalMeshLODBias=${if (p.shadow >= 4) 1 else 2}",
            "r.Kuro.SkeletalMesh.LODScreenSizeScale=$charLODScale",
            "r.Mobile.KuroPostprocess=1",
            "r.Mobile.TonemapperFilm=1",
            "r.Kuro.ToonOutlineDrawDistanceMobile=$charOutline",
            "r.Kuro.ToonEyeTransparentDrawDistanceMobile=$charEyeDist",
            "r.Kuro.ToonFaceShadowMeshDrawDistanceMobile=$charEyeDist",
            "r.Mobile.OutlineScale=$outlineScale",
            "r.Kuro.AutoExposure=$autoExposure",
            "r.Kuro.RadialBlur.MobileIntensityScalar=$radialBlur",
            "Kuro.Blueprint.EnableGameBudget=0",
            "r.Mobile.TreeRimLight=1",
            "r.Kuro.LandscapeCapture=1",
            "r.Kuro.LandscapeCaptureDistance=${dt.landscapeCaptureDist}",
            "r.Mobile.Kuro.LandscapeCaptureSize=$landscapeCaptureSize",
            ""
        )
    }

    private fun buildAntiAliasingSection(p: PresetProfile): List<String> = listOf(
        "; ── ANTI-ALIASING ────────────────────────────────────",
        "r.PostProcessAAQuality=6",
        "r.TemporalAA.Upsampling=1",
        "r.TemporalAA.Algorithm=1",
        "r.TemporalAACatmullRom=1",
        "r.TemporalAACurrentFrameWeight=0.25",
        "r.TemporalAAFilterSize=0.5",
        "r.TemporalAAPauseCorrect=1",
        "r.TemporalAA.MobileFrameWeight=${if (p.q1) 0.08 else 0.12}",
        "r.TemporalAA.MobileStaticFrameWeight=${if (p.q1) 0.3 else 0.5}",
        "r.DefaultFeature.AntiAliasing=2",
        ""
    )

    private fun buildPostProcessingSection(p: PresetProfile, opts: Options): List<String> = listOf(
        "; ── POST PROCESSING ──────────────────────────────────",
        "r.BloomQuality=${if (opts.disableBloom) 0 else if (p.q1) 4 else if (p.q0) 3 else 1}",
        "r.EyeAdaptationQuality=2",
        "r.MotionBlurQuality=0",
        "r.DepthOfFieldQuality=${if (p.q1) 2 else if (p.q0) 1 else 0}",
        "r.LightShaftQuality=${if (p.q0) 1 else 0}",
        "r.LensFlareQuality=0",
        "r.SceneColorFringeQuality=${if (opts.ca) 1 else 0}",
        "r.Tonemapper.GrainQuantization=0",
        "r.DisableDistortion=${if (p.q1) 0 else 1}",
        "r.AmbientOcclusionLevels=${if (p.q1) 1 else 0}",
        "r.KuroTonemapping=3",
        "r.Kuro.KuroBloomEnable=${if (opts.disableBloom) 0 else 1}",
        "r.Kuro.KuroEnableFFTBloom=${if (opts.disableBloom) 0 else if (p.q1) 1 else 0}",
        "r.Kuro.KuroEnableToonFFTBloom=0",
        "r.Kuro.KuroBloomStreak=${if (p.q1) 1 else 0}",
        "r.LightShaftDownSampleFactor=${if (p.q1) 2 else 4}",
        "r.Tonemapper.Quality=4",
        "r.Upscale.Quality=3",
        ""
    )

    private fun buildShadowSection(p: PresetProfile, dt: DeviceTier): List<String> {
        val shadowCascade = if (p.shadow >= 4) 3 else 2
        // Preset-tuned shadowRes, capped by device tier so a 4096 cinematic preset on a weak
        // GPU degrades gracefully instead of exploding (the reference's own cap).
        val tierCap = if (dt.isHighEnd) 4096 else if (dt.isMid) 2048 else 1024
        val shadowRes = minOf(p.shadowRes, tierCap)
        return listOf(
            "; ── SHADOW ───────────────────────────────────────────",
            "r.AllowStaticLighting=${if (p.staticLighting) 1 else 0}",
            "r.Shadow.KuroEnablePointLightShadow=${if (p.shadow >= 3) 1 else 0}",
            "r.Shadow.CSM.MaxMobileCascades=$shadowCascade",
            "r.Shadow.RadiusThresholdFar=${if (p.shadow >= 3) "0.06" else "0.12"}",
            "r.Shadow.UnbuiltPreviewInGame=1",
            "r.Kuro.GlobalLightQuality_PC=${if (p.shadow >= 4) 4 else if (p.shadow >= 2) 3 else 2}",
            "r.Kuro.GlobalLightShadowQuality_PC=${if (p.shadow >= 4) 4 else if (p.shadow >= 2) 3 else 2}",
            "r.Shadow.RadiusThreshold=${if (p.shadow >= 3) 0.06 else 0.12}",
            "r.Shadow.PerObjectResolutionMax=$shadowRes",
            "r.Shadow.MaxResolution=$shadowRes",
            "r.Shadow.RadiusThresholdOverrideEnable=0",
            "r.Shadow.PerObjectResolutionMin=64",
            "r.MobileNumDynamicPointLights=2",
            "r.Shadow.SinglePass=1",
            "r.Shadow.DirectLightCacheMaxKeepFrameInterval=1",
            "r.Shadow.ForceSerialSingleRenderPass=0",
            ""
        )
    }

    private fun buildTextureStreamingSection(p: PresetProfile, dt: DeviceTier): List<String> = listOf(
        "; ── TEXTURE STREAMING ────────────────────────────────",
        "r.TextureStreaming=1",
        "r.Streaming.MipBias=${if (p.mipbias < 0) 0 else p.mipbias}",
        "r.MaxAnisotropy=${dt.maxAniso}",
        "r.streaming.TexturePoolSizeMode=1",
        "r.Streaming.KuroMinFOVFactorForStreaming=0.2",
        "r.Streaming.GroupBoost.MediumNpcTextureFactor=${if (p.q0) "1.5" else "1.2"}",
        // Preset `streaming` scales the tier pool: potato 0.3x … cinematic 6x, clamped to
        // the physical tier budget.
        "r.Streaming.PoolSizeForMeshes=${(dt.streamPool * 0.3 * p.streaming).toInt().coerceIn(96, dt.streamPool)}",
        "r.Streaming.UsingKuroStreamingPriority=2",
        "r.Streaming.AmortizeCPUToGPUCopy=1",
        "r.Streaming.DefragDynamicBounds=1",
        "r.Streaming.CheckBuildStatus=0",
        "r.Streaming.UseAllMips=${if (p.mipbias > 1) 0 else 1}",
        ""
    )

    private fun buildMobileRenderingSection(p: PresetProfile): List<String> = listOf(
        "; ── MOBILE RENDERING ─────────────────────────────────",
        "r.Mobile.ShadingPath=1",
        "r.Mobile.UseFSRUpscale=${if (p.q1) 0 else 1}",
        "r.MobileMSAA=0",
        "r.Mobile.HBAO=${if (p.q0) 1 else 0}",
        "r.Mobile.HBAO.BlurType=1",
        "r.Mobile.HBAO.LargeAOFactor=0.5",
        "r.Mobile.HBAO.SmallAOFactor=1.0",
        "r.Mobile.PixelProjectedReflectionQuality=${if (p.q1) 1 else 0}",
        "r.Mobile.EnableStaticAndCSMShadowReceivers=1",
        ""
    )

    private fun buildVrsSection(): List<String> = listOf(
        "; ── VRS (Variable Rate Shading) ───────────────────────",
        "r.VRS.EnableMaterial=1",
        "r.VRS.EnableMesh=1",
        ""
    )

    private fun buildEffectsParticlesSection(p: PresetProfile): List<String> = listOf(
        "; ── EFFECTS / PARTICLES (GPU particle offload for thermal/perf) ──",
        "fx.KuroUseGPUParticles=0",
        "Niagara.GPUDrawIndirectArgsBufferSlack=4096",
        "fx.Niagara.QualityLevel=${if (p.q1) 2 else 1}",
        "r.EmitterSpawnRateScale=${if (p.q1) "1.0" else if (p.q0) "0.8" else "0.6"}",
        "FX.MaxCPUParticlesPerEmitter=${if (p.q1) 100 else 50}",
        "FX.MaxGPUParticlesSpawnedPerFrame=${if (p.q1) 4096 else 2048}",
        ""
    )

    private fun buildWaterReflectionSection(p: PresetProfile, opts: Options, dt: DeviceTier): List<String> {
        val lines = mutableListOf<String>()
        lines.add("; ── WATER / REFLECTION ───────────────────────────────")
        if (opts.disableSSR) {
            lines.add("; SSR disabled by user toggle")
            lines.add("r.Mobile.WaterSSR=0")
            lines.add("r.Mobile.WaterSSRStep=0")
            lines.add("r.Mobile.SSR=0")
            lines.add("r.Mobile.SceneObjMobileSSR=0")
            lines.add("r.Kuro.EnablePlanarReflection=0")
        } else {
            val ssrStep = when {
                p.ssr >= 4 -> 16
                p.ssr >= 2 -> 12
                else -> 8
            }
            lines.add("r.Mobile.WaterSSR=${if (dt.isHighEnd && p.ssr > 0) 1 else 0}")
            lines.add("r.Mobile.WaterSSRStep=$ssrStep")
            lines.add("r.Mobile.SSR=${if (dt.isHighEnd && p.ssr > 0) 1 else 0}")
            lines.add("r.Mobile.SceneObjMobileSSR=${if (dt.isHighEnd && p.ssr >= 2) 1 else 0}")
            lines.add("r.Kuro.EnablePlanarReflection=${if (dt.isHighEnd && p.ssr >= 3) 1 else 0}")
            lines.add("r.SSR.MaxRoughness=${if (p.ssr >= 4) 1.0 else 0.6}")
            lines.add("r.SSR.HalfResSceneColor=1")
        }
        lines.add("r.DistanceFieldAO=0")
        lines.add("")
        return lines
    }

    private fun buildScreenSpaceEffectsSection(p: PresetProfile, opts: Options, dt: DeviceTier): List<String> {
        val lines = mutableListOf<String>()
        lines.add("; ── SCREEN-SPACE EFFECTS ────────────────────────────")
        lines.add("r.SSGI.Enable=${if (p.q1) 1 else 0}")
        lines.add("r.SubsurfaceScattering=${if (p.q1) 1 else 0}")
        lines.add("r.SSFS.HighQuality=${if (p.q1) 1 else 0}")
        lines.add("r.SSFS.FullPrecision=${if (p.q1) 1 else 0}")
        lines.add("r.SSS.HalfRes=${if (p.q1) 0 else 1}")
        lines.add("r.SSS.Quality=${if (p.q1) 2 else 1}")
        if (p.detail >= 4 && !opts.disableSSR) {
            lines.add("; Cinematic premium — flagship only")
            lines.add("r.Kuro.EnablePlanarReflection=1")
            lines.add("r.ContactShadows=1")
            lines.add("r.SSGI.Enable=${if (dt.isHighEnd) 1 else 0}")
        }
        lines.add("foliage.DitheredLOD=1")
        lines.add("r.Shadow.MinResolution=64")
        lines.add("r.Shadow.FadeResolution=128")
        lines.add("r.Shadow.TexelsPerPixel=${if (p.q2) 2.0 else if (p.q0) 1.5 else 1.0}")
        lines.add("")
        return lines
    }

    private fun buildEnvironmentSection(p: PresetProfile, dt: DeviceTier): List<String> {
        val lines = mutableListOf<String>()
        lines.add("; ── ENVIRONMENT ──────────────────────────────────────")
        lines.add("r.Kuro.SuperFarFogGlobalDistanceScale=${if (p.q1) 1 else 0}")
        lines.add("r.LightFunctionQuality=1")
        lines.add("r.Kuro.LightFunction=1")
        lines.add("r.FogVisibilityCulling.Enable=1")
        lines.add("r.FogVisibilityCulling.Opacity=${if (p.q1) "0.8" else "0.5"}")
        lines.add("foliage.LODOptimize=1")
        lines.add("r.EnableAggressivePVS=1")
        val viewDistance = p.vd.coerceIn(0.2, 5.0)
        // Preset-tuned grasscull is the target cull distance; the device tier supplies a
        // safety ceiling (×20 of its own cull budget) so a cinematic preset on a weak phone
        // is clamped instead of exploding draw distance.
        val grassBase = minOf(p.grasscull, dt.grassCull * GRASSCULL_TIER_CEILING_MULT)
        val foliageLod = p.flod.coerceIn(0.3, 5.0)
        lines.add("r.ViewDistanceScale=$viewDistance")
        lines.add("r.Kuro.MobileISMDecideDistance=${dt.ismDist}.0")
        lines.add("r.Kuro.MobileISMMeshRadiusMax=${dt.ismRad}.0")
        lines.add("r.Kuro.Foliage.MobileGrassCullDistanceMax=$grassBase")
        lines.add("r.Kuro.Foliage.MobileGrass3_0CullDistanceMax=$grassBase")
        lines.add("r.Kuro.Foliage.MobileMiddleCullDistanceMin=${(grassBase * 1.8).toInt()}")
        lines.add("r.Kuro.Foliage.MobileMiddleCullDistanceMax=${(grassBase * 2.2).toInt()}")
        lines.add("r.Kuro.Foliage.MobileFarCullDistanceMin=${(grassBase * 2.8).toInt()}")
        lines.add("r.Kuro.Foliage.MobileFarCullDistanceMax=${(grassBase * 3.2).toInt()}")
        lines.add("foliage.DensityScale=${if (dt.isHighEnd && p.q1) 1.5 else if (p.q0) 1.0 else 0.6}")
        lines.add("grass.DensityScale=${if (dt.isHighEnd && p.q1) 1.5 else if (p.q0) 1.0 else 0.6}")
        lines.add("foliage.LODDistanceScale=${"%.2f".format(foliageLod)}")
        lines.add("")
        return lines
    }

    private fun buildNpcWorldSection(p: PresetProfile, dt: DeviceTier, opts: Options): List<String> = listOf(
        "; ── NPC & WORLD ──────────────────────────────────────",
        "r.Kuro.NpcDisappearDistance=${dt.npcDist}",
        "r.LandscapeReverseLODScaleFactor=${if (p.q1) 2 else 3}",
        "r.LandscapeLOD0ScreenSizeScale=2",
        "r.KuroMaxFOVForLOD=${if (p.q1) 85 else 80}",
        "r.MDCFallback.EnabledLOD=1",
        "r.BBM.LODBias=${if (p.q1) 0 else 1}",
        "lod.TemporalLag=1",
        "r.RenderTargetPoolMin=${if (p.q1) 150 else if (p.q0) 80 else 64}",
        "r.Streaming.FullyLoadUsedTextures=${if (p.q0) 1 else 0}",
        "r.AllowPrecomputedVisibility=1",
        "r.HZBOcclusion=${if (opts.hzb) 1 else 0}",
        "r.EnableMeshPassProcessorsCache=1",
        "r.EnableGetDynElemsCache=1",
        "r.MorphTarget.EnableSplit=1",
        "r.MorphTarget.UnloadDelayTime=${if (p.q1) 30 else if (p.q0) 10 else 3}",
        ""
    )

    private fun buildAdvancedLodCullingSection(p: PresetProfile): List<String> = listOf(
        "; ── ADVANCED LOD / CULLING ──────────────────────────",
        "r.CullDistanceVolume.Enable=1",
        "r.UseClusteredDeferredShading=1",
        "r.AllowOcclusionQueries=1",
        "r.MinScreenRadiusForLights=${if (p.q1) 0.02 else 0.04}",
        "r.MinScreenRadiusForCSMDepth=${if (p.q1) 0.01 else 0.02}",
        "r.StaticMeshLODDistanceScale=${if (p.q1) 1.0 else if (p.q0) 0.85 else 0.7}",
        "r.ScreenSizeCullRatioFactor=${if (p.q1) 0.5 else 3.0}",
        "r.ParallelFrustumCull=1",
        "wp.Runtime.PlannedLoadingRangeScale=${if (p.q1) 5 else if (p.q0) 3 else 2}",
        ""
    )

    private fun buildAnimationBlueprintSection(p: PresetProfile): List<String> = listOf(
        "; ── ANIMATION & BLUEPRINT ───────────────────────────",
        "a.URO.Enable=1",
        "a.URO.ForceAnimRate=${if (p.q1) 1 else 0}",
        "a.URO.ForceInterpolation=1",
        ""
    )

    private fun buildFrameDisplaySection(p: PresetProfile, opts: Options, dt: DeviceTier): List<String> = listOf(
        "; ── FRAME & DISPLAY ──────────────────────────────────",
        "r.Kuro.Movie.EnableCGMovieRendering=${if (p.cutsceneQuality >= 1) 1 else 0}",
        "r.MobileHDR=1",
        "r.VSync=${if (opts.vsync) 1 else 0}",
        "r.SkinCache.SceneMemoryLimitInMB=${dt.skinCacheMem}",
        "r.ShaderPipelineCache.Enabled=1",
        "r.ShaderPipelineCache.PrecompileCheckCacheHash=1",
        "r.ShaderPipelineCache.BatchSize=128",
        "r.PSO.CompilationMode=0",
        "r.kuro.LGUIBlurTexture.save=0",
        "r.KuroFI.Enable=${if (p.q1) 1 else 0}",
        "r.FinishCurrentFrame=0",
        "r.DontLimitOnBattery=1",
        ""
    )

    private fun buildPipelineRhiSection(): List<String> = listOf(
        "; ── PIPELINE / RHI ───────────────────────────────────",
        "r.PSO.CacheEvictScheme=1",
        "r.pso.evictiontime=20",
        "r.RHICmdBypass=1",
        "r.RHICmdUseParallelAlgorithms=1",
        "r.RHICmdUseThread=1",
        "r.RHICmdAsyncRHIThreadDispatch=1",
        ""
    )

    private fun buildThermalStabilitySection(opts: Options, dt: DeviceTier, hasVulkan: Boolean): List<String> {
        val lines = mutableListOf<String>()
        lines.add("; ── THERMAL & STABILITY ──────────────────────────────")
        if (opts.disableAutoAdjust) {
            lines.add("; Auto quality adjustment disabled by user")
            lines.add("r.Kuro.AutoCoolEnable=0")
            lines.add("r.Kuro.AutoCoolUIEnable=0")
            lines.add("r.Kuro.AutoExposure=0")
            lines.add("t.MaxFPS=${opts.fps}")
        } else {
            lines.add("r.Kuro.AutoCoolEnable=${if (opts.cool) 1 else 0}")
            lines.add("r.Kuro.AutoCoolUIEnable=${if (opts.cool) 1 else 0}")
        }
        if (hasVulkan) {
            lines.add("r.Vulkan.RobustBufferAccess=1")
            lines.add("r.Vulkan.DescriptorSetLayoutMode=2")
            lines.add("r.Vulkan.PipelineLRUCapactiy=128")
        } else {
            lines.add("; Vulkan not detected")
        }
        lines.add("")
        return lines
    }

    private fun buildForbiddenCvarOverridesSection(): List<String> = listOf(
        "; ── FORBIDDEN CVAR OVERRIDES ──────────────────────────",
        "; Disabling known problematic CVars detected in log",
        "r.FidelityFX.FSR.RCAS.Enabled=0",
        "r.TemporalAA.Sharpness=0",
        "r.Mobile.SSAO=0",
        "r.DefaultFeature.LensFlare=0",
        ""
    )

    private fun buildPerformanceTweaksSection(p: PresetProfile, preset: String): List<String> {
        val lines = mutableListOf<String>()
        if (preset == "potato" || preset == "endurance" || preset == "performance") {
            lines.add("; ── PERFORMANCE TWEAKS ───────────────────────────")
            lines.add("; HZB occlusion — skip rendering hidden objects (saves GPU)")
            lines.add("r.HZBOcclusion=1")
            lines.add("")
            lines.add("; Kill reflection environments, light functions, local light specular")
            lines.add("r.ReflectionEnvironment=0")
            lines.add("r.LightFunctionQuality=0")
            lines.add("r.Mobile.DisableLocalLightSpecularDistance=0")
            if (preset != "endurance") {
                lines.add("r.Mobile.EnableStaticAndCSMShadowReceivers=0")
            } else {
                lines.add("; Endurance keeps static shadow receivers (cheap static lighting)")
                lines.add("r.Mobile.EnableStaticAndCSMShadowReceivers=1")
            }
            lines.add("")
            lines.add("; Dynamic / movable light reduction")
            lines.add("r.MobileNumDynamicPointLights=0")
            lines.add("r.Mobile.AllowMovableDirectionalLights=1")
            lines.add("r.Mobile.EnableMovableSpotlights=0")
            lines.add("r.Mobile.EnableMovableSpotlightsShadow=0")
            lines.add("r.Mobile.EnableKuroSpotlightsShadow=0")
            lines.add("r.Mobile.EnableMovableLightCSMShaderCulling=1")
            lines.add("")
            lines.add("; Shadow quality — absolute minimum")
            lines.add("r.ShadowQuality=1")
            lines.add("r.Shadow.CSM.MaxCascades=1")
            lines.add("r.Shadow.CSM.MaxMobileCascades=1")
            lines.add("r.Shadow.MaxResolution=512")
            lines.add("r.Shadow.PerObjectResolutionMax=256")
            lines.add("r.Shadow.MinResolution=32")
            lines.add("r.Shadow.TexelsPerPixel=0.5")
            lines.add("r.Shadow.RadiusThreshold=0.08")
            lines.add("r.Shadow.DistanceScale=0.4")
            lines.add("r.Shadow.CSM.TransitionScale=0.3")
            lines.add("")
            lines.add("; Heavy lighting systems off")
            lines.add("r.DistanceFieldShadowing=0")
            lines.add("r.CapsuleShadows=0")
            lines.add("r.ContactShadows=0")
            lines.add("r.VolumetricFog=0")
            lines.add("r.LightShaftDownSampleFactor=8")
            lines.add("")
            lines.add("; Screen-space effects — minimum")
            lines.add("r.SSGI.Enable=0")
            lines.add("r.SubsurfaceScattering=0")
            lines.add("r.SSR.HalfResSceneColor=1")
            lines.add("r.SSR.MaxRoughness=0.4")
            lines.add("r.EyeAdaptationQuality=0")
            lines.add("")
            lines.add("; LOD & culling — aggressive")
            lines.add("r.LandscapeLOD0ScreenSizeScale=3")
            lines.add("r.MinScreenRadiusForLights=0.06")
            lines.add("r.MinScreenRadiusForCSMDepth=0.03")
            lines.add("r.StaticMeshLODDistanceScale=${"%.2f".format(1.0 + p.lod_bias * 0.1)}")
            lines.add("r.ScreenSizeCullRatioFactor=5.0")
            lines.add("foliage.DensityScale=0.5")
            lines.add("grass.DensityScale=0.4")
            lines.add("foliage.LODDistanceScale=${"%.2f".format(0.6 + p.lod_bias * 0.1)}")
            lines.add("")
            lines.add("; Thermal, bloom, volumetric clouds & misc")
            lines.add("r.Kuro.KuroEnableFFTBloom=0")
            lines.add("r.Kuro.KuroBloomStreak=0")
            lines.add("r.KuroVolumeCloudEnable=0")
            lines.add("r.Kuro.AutoCoolEnable=1")
            lines.add("a.URO.ForceAnimRate=0")
            lines.add("")
        }
        return lines
    }

    private fun buildExperimentalCvarsSection(opts: Options): List<String> {
        val lines = mutableListOf<String>()
        if (opts.experimentalCvars) {
            lines.add("; ── EXPERIMENTAL CVars (verified on Adreno 618) ─────")
            lines.add("r.renderswitch.water=0")
            lines.add("r.renderswitch.hlod=0")
            lines.add("r.renderswitch.character=0")
            lines.add("r.renderswitch.gridhlod=0")
            lines.add("r.kuro.hidehlod=1")
            lines.add("r.kuro.waterraindrop=0")
            lines.add("r.kuro.enablekurovolumegodray=0")
            lines.add("r.kuro.temporaryenablefsr=0")
            lines.add("r.kuro.lensflarecolorthresholdrange=999")
            lines.add("r.kuro.grassinteractionrange=0")
            lines.add("r.kuro.basepassvelocity=1")
            lines.add("r.mobile.enablewater=0")
            lines.add("r.mobile.usescreenpassssr=0")
            lines.add("r.mobile.hzb=1")
            lines.add("r.mobile.enablemobiledeferredlighting=0")
            lines.add("r.mobile.enablelandscapessr=0")
            lines.add("r.imp.kuroimposterskipifiobusy=1")
            lines.add("r.kuro.landscapeusemodifiedlod=2")
            lines.add("r.mobile.enableoutlinevelocity=0")
            lines.add("r.kuro.kurodisabletoonvelocity=1")
            lines.add("r.mobile.basepassvelocity=0")
            lines.add("r.mobile.rendervelocity=0")
            lines.add("r.mobile.enablestaticmeshvelocity=0")
            lines.add("")
        }
        return lines
    }

    private fun buildGameModeToaSection(opts: Options): List<String> {
        val lines = mutableListOf<String>()
        if (opts.mode == GameMode.ToA) {
            lines.add("")
            lines.add("; ── GAME MODE: TOWER OF ADVERSITY ─────────────────")
            lines.add("; Closed boss/echo arena — fewer open-world objects to render,")
            lines.add("; so environment is lightened while character/boss quality is kept.")
            lines.add("r.Fog=0")
            lines.add("r.KuroVolumeCloudEnable=0")
            lines.add("r.VolumetricFog=0")
            lines.add("foliage.DensityScale=0.35")
            lines.add("grass.DensityScale=0.35")
            lines.add("foliage.LODDistanceScale=0.6")
            lines.add("r.Kuro.Foliage.MobileGrassCullDistanceMax=2500")
            lines.add("r.Kuro.Foliage.MobileGrass3_0CullDistanceMax=2500")
            lines.add("r.Kuro.Foliage.MobileMiddleCullDistanceMin=3500")
            lines.add("r.Kuro.Foliage.MobileMiddleCullDistanceMax=4500")
            lines.add("r.Kuro.Foliage.MobileFarCullDistanceMin=5500")
            lines.add("r.Kuro.Foliage.MobileFarCullDistanceMax=6500")
            lines.add("r.Kuro.NpcDisappearDistance=8000")
            lines.add("r.Kuro.MobileISMDecideDistance=12000.0")
            lines.add("r.Kuro.MobileISMMeshRadiusMax=2000.0")
            lines.add("r.Mobile.WaterSSR=0")
            lines.add("r.Mobile.SSR=0")
            lines.add("r.Mobile.SceneObjMobileSSR=0")
            lines.add("r.Kuro.EnablePlanarReflection=0")
            lines.add("r.MobileNumDynamicPointLights=1")
        }
        return lines
    }

    private fun buildGsrSection(opts: Options): List<String> {
        val lines = mutableListOf<String>()
        if (opts.enableGSR) {
            lines.add("; ── GAME SUPER RESOLUTION (GSR Upscaling) ───────")
            lines.add("[/Script/GSRTUModule.GSRSettings]")
            lines.add("r.sgsr2.enabled=1")
            lines.add("r.sgsr2.history=1")
            lines.add("r.sgsr2.tunemipbias=0")
            lines.add("")
        }
        return lines
    }

    // ── DeviceProfiles.ini ──────────────────────────────────────────────────────

    private fun buildDeviceProfilesIni(
        p: PresetProfile,
        preset: String,
        opts: Options,
        deviceInfo: DeviceInfo
    ): String {
        val dt = computeDeviceTier(deviceInfo)
        val gpu = (deviceInfo.gpu ?: "").lowercase()
        val socText =
            listOfNotNull(deviceInfo.socModel, deviceInfo.deviceModel).joinToString(" ").lowercase()
        val texBias = if (p.q1) 80 else if (p.q0) 200 else 400
        val charOutline = if (p.q1) 1200 else if (p.q0) 950 else 850
        val charEyeDist = if (p.q1) 700 else if (p.q0) 550 else 450
        val charLODScale = if (p.q1) 7.0 else if (p.q0) 6.0 else 5.0

        fun profileFromChipset(): String? {
            for ((pattern, profile) in CHIPSET_PROFILES) {
                if (pattern.containsMatchIn(socText)) return profile
            }
            for ((pattern, profile) in GPU_ONLY_PROFILES) {
                if (pattern.containsMatchIn(gpu)) return profile
            }
            return null
        }

        val presetBaseProfile = when (preset) {
            "potato", "endurance", "performance", "competitive" -> "Android_Low"
            "balanced" -> "Android_Mid"
            "high" -> "Android_VeryHigh"
            "ultra", "cinematic" -> "Android_Ultra"
            else -> "Android_Mid"
        }

        fun universalProfilesForPreset(): List<String> = when (preset) {
            "potato", "endurance", "performance", "competitive" -> listOf("Android_Low")
            "high" -> listOf("Android_VeryHigh")
            "ultra", "cinematic" -> listOf("Android_Ultra")
            else -> listOf("Android_Mid")
        }

        fun profileCVarLines(): List<String> = mutableListOf(
            "; Device tier — follows selected preset, not forced high",
            "+CVars=r.Mobile.DeviceEvaluation=${
                when (preset) {
                    "potato", "endurance" -> 0
                    "performance", "competitive" -> 1
                    "balanced" -> 2
                    else -> 3
                }
            }",
            "",
            "; Texture LOD",
            "+CVars=r.streaming.QualityExtraLODBiasSetting=$texBias",
            "",
            "; Character quality",
            "+CVars=r.Kuro.ToonOutlineDrawDistanceMobile=$charOutline",
            "+CVars=r.Kuro.ToonEyeTransparentDrawDistanceMobile=$charEyeDist",
            "+CVars=r.Kuro.ToonFaceShadowMeshDrawDistanceMobile=$charEyeDist",
            "+CVars=r.Kuro.SkeletalMesh.LODScreenSizeScale=$charLODScale",
            "",
            "; Imposter",
            "+CVars=r.imp.SSMbScaleLod0=0.0",
            "+CVars=r.imp.SSMbScaleLod1=0.0",
            "",
            "; ISM draw distances",
            "+CVars=r.Kuro.MobileISMDecideDistance=${dt.ismDist}.0",
            "+CVars=r.Kuro.MobileISMMeshRadiusMax=${dt.ismRad}.0",
            "",
            "; Foliage cull",
            "+CVars=r.Kuro.Foliage.MobileGrassCullDistanceMax=${dt.grassCull}",
            "+CVars=r.Kuro.Foliage.MobileGrass3_0CullDistanceMax=${dt.grassCull}",
            "+CVars=r.Kuro.Foliage.MobileMiddleCullDistanceMin=${(dt.grassCull * 1.8).toInt()}",
            "+CVars=r.Kuro.Foliage.MobileMiddleCullDistanceMax=${(dt.grassCull * 2.2).toInt()}",
            "+CVars=r.Kuro.Foliage.MobileFarCullDistanceMin=${(dt.grassCull * 2.8).toInt()}",
            "+CVars=r.Kuro.Foliage.MobileFarCullDistanceMax=${(dt.grassCull * 3.2).toInt()}",
            "",
            "; FPS unlock",
            "+CVars=r.Kuro.MaxFPS.ThirdParty60=1"
        ).apply {
            if (opts.unlock120) add("+CVars=r.Kuro.MaxFPS.ThirdParty120=1")
            if (opts.unlockUltra) add("+CVars=r.Kuro.GraphicsQuality.ThirdPartyUltraEnable=1")
        }

        // The reference's two shapes: a targeted profile when the device identified itself
        // (there, via the game log; here, via real GPU/SoC reads), and the universal preset
        // profile when nothing did.
        val chipsetProfile = profileFromChipset()
        if (chipsetProfile == null && deviceInfo.gpu == null && deviceInfo.deviceModel == null) {
            val profiles = universalProfilesForPreset()
            val rootProfile = profiles[0]
            val rootBaseProfile = if (presetBaseProfile == "Android_Ultra") "Android_VeryHigh" else "Android"
            return mutableListOf<String>().apply {
                add(configHeader(preset, deviceInfo))
                add("[DeviceProfiles]")
                profiles.forEach { add("+DeviceProfileNameAndTypes=$it,Android") }
                add("")
                add("; Universal Android preset — device could not be identified")
                add("; Preset base profile: $presetBaseProfile")
                add("[$rootProfile DeviceProfile]")
                add("DeviceType=Android")
                add("BaseProfileName=$rootBaseProfile")
                add("")
                addAll(profileCVarLines())
                add("")
            }.joinToString("\n")
        }

        val profile = chipsetProfile ?: presetBaseProfile
        val baseProfile = if (chipsetProfile != null) presetBaseProfile else "Android"
        return mutableListOf<String>().apply {
            add(configHeader(preset, deviceInfo))
            add("[DeviceProfiles]")
            add("+DeviceProfileNameAndTypes=$profile,Android")
            add("")
            add("; Targeted Android profile — generated from detected SoC/chipset")
            add("; GPU: ${deviceInfo.gpu ?: "unknown"}")
            add("; SoC: ${deviceInfo.socModel ?: "unknown"}")
            add("; Preset base profile: $presetBaseProfile")
            add("[$profile DeviceProfile]")
            add("DeviceType=Android")
            add("BaseProfileName=$baseProfile")
            add("")
            addAll(profileCVarLines())
            add("")
        }.joinToString("\n")
    }

    // ── GameUserSettings.ini ────────────────────────────────────────────────────

    private fun buildGameUserSettingsIni(p: PresetProfile, opts: Options, deviceInfo: DeviceInfo): String {
        val deviceRes = parseResolution(deviceInfo.resolution)
        val (resW, resH) = if (deviceRes != null && deviceRes.first >= 720) deviceRes else (1280 to 720)
        val viewQ = if (p.q1) 3 else if (p.q0) 2 else 1
        val shadowQ = if (p.shadow >= 4) 3 else if (p.shadow >= 2) 2 else 1
        val postQ = p.postProcess.coerceIn(0, 3)
        val texQ = if (p.q1) 3 else if (p.q0) 2 else 1
        val fxQ = if (p.q1) 2 else if (p.q0) 1 else 0
        val kuroQ = if (p.q1) 3 else 2
        val aaQ = if (p.q0) 2 else 1
        return listOf(
            "; WuWa GameUserSettings.ini — CatSmoker", "",
            "[ScalabilityGroups]",
            "sg.ResolutionQuality=${p.screen}",
            "sg.ViewDistanceQuality=$viewQ",
            "sg.AntiAliasingQuality=$aaQ",
            "sg.ShadowQuality=$shadowQ",
            "sg.PostProcessQuality=$postQ",
            "sg.TextureQuality=$texQ",
            "sg.EffectsQuality=$fxQ",
            "sg.FoliageQuality=${if (p.q1) 2 else if (p.q0) 1 else 0}",
            "sg.ShadingQuality=${if (p.q1) 3 else 2}",
            "sg.KuroRenderQuality=$kuroQ",
            "sg.KuroLocalRenderQuality=0",
            "sg.RayTracingQuality=0",
            "",
            "[/Script/Engine.GameUserSettings]",
            "bUseVSync=${if (opts.vsync) "True" else "False"}",
            "bUseDynamicResolution=False",
            "ResolutionSizeX=$resW",
            "ResolutionSizeY=$resH",
            "LastUserConfirmedResolutionSizeX=$resW",
            "LastUserConfirmedResolutionSizeY=$resH",
            "WindowPosX=-1",
            "WindowPosY=-1",
            "FullscreenMode=0",
            "GameQualitySettingLevel=$kuroQ",
            "LastConfirmedFullscreenMode=0",
            "PreferredFullscreenMode=0",
            "Version=5",
            "AudioQualityLevel=0",
            "LastConfirmedAudioQualityLevel=0",
            "FrameRateLimit=${opts.fps}.000000",
            "FramePace=${opts.fps}",
            "DesiredScreenWidth=$resW",
            "bUseDesiredScreenHeight=False",
            "DesiredScreenHeight=$resH",
            "LastUserConfirmedDesiredScreenWidth=$resW",
            "LastUserConfirmedDesiredScreenHeight=$resH",
            "LastRecommendedScreenWidth=-1.000000",
            "LastRecommendedScreenHeight=-1.000000",
            "LastCPUBenchmarkResult=-1.000000",
            "LastGPUBenchmarkResult=-1.000000",
            "LastGPUBenchmarkMultiplier=1.000000",
            "bUseHDRDisplayOutput=False",
            "HDRDisplayOutputNits=1000",
            "",
            "[Internationalization]",
            "Culture=en",
            "",
            "[ShaderPipelineCache.CacheFile]",
            "LastOpened=Client"
        ).joinToString("\n")
    }

    // ── Scalability.ini ─────────────────────────────────────────────────────────

    private fun buildScalabilityIni(p: PresetProfile, @Suppress("UNUSED_PARAMETER") opts: Options): String {
        val viewQ = if (p.q1) 3 else if (p.q0) 2 else 1
        val shadowQ = if (p.shadow >= 4) 3 else if (p.shadow >= 2) 2 else 1
        val postQ = p.postProcess.coerceIn(0, 3)
        val texQ = if (p.q1) 3 else if (p.q0) 2 else 1
        val fxQ = if (p.q1) 2 else if (p.q0) 1 else 0
        val folQ = if (p.q1) 2 else if (p.q0) 1 else 0
        val kuroQ = if (p.q1) 3 else 2
        val aaQ = if (p.q0) 2 else 1
        val shaQ = if (p.q1) 3 else 2

        val header = listOf(
            "; WuWa Scalability.ini — CatSmoker",
            "",
            "[ScalabilitySettings]",
            "ResolutionQuality=${p.screen}.0",
            "ViewDistanceQuality=$viewQ",
            "AntiAliasingQuality=$aaQ",
            "ShadowQuality=$shadowQ",
            "PostProcessQuality=$postQ",
            "TextureQuality=$texQ",
            "EffectsQuality=$fxQ",
            "FoliageQuality=$folQ",
            "ShadingQuality=$shaQ",
            "KuroRenderQuality=$kuroQ",
            "KuroLocalRenderQuality=0"
        )

        val sections = listOf(
            listOf("", "[ViewDistanceQuality@0]", "r.ViewDistanceScale=0.70", "r.SkeletalMeshLODBias=0", "r.NeverOcclusionTestDistance=0"),
            listOf("", "[ViewDistanceQuality@1]", "r.ViewDistanceScale=0.85", "r.SkeletalMeshLODBias=0"),
            listOf("", "[ViewDistanceQuality@2]", "r.ViewDistanceScale=1.0", "r.SkeletalMeshLODBias=0"),
            listOf("", "[ViewDistanceQuality@3]", "r.ViewDistanceScale=1.0", "r.SkeletalMeshLODBias=0"),
            listOf("", "[AntiAliasingQuality@0]", "r.PostProcessAAQuality=0", "r.MotionBlurQuality=0", "r.AmbientOcclusionLevels=-1", "r.AmbientOcclusionMaxQuality=0"),
            listOf("", "[AntiAliasingQuality@1]", "r.PostProcessAAQuality=2", "r.MotionBlurQuality=1"),
            listOf("", "[AntiAliasingQuality@2]", "r.PostProcessAAQuality=3", "r.MotionBlurQuality=2"),
            listOf("", "[AntiAliasingQuality@3]", "r.PostProcessAAQuality=3", "r.MotionBlurQuality=3", "r.AmbientOcclusionLevels=0"),
            listOf("", "[ShadowQuality@0]", "r.ShadowQuality=1", "r.Shadow.CSM.MaxCascades=1", "r.Shadow.CSM.MaxMobileCascades=1", "r.Shadow.MaxResolution=128", "r.LightFunctionQuality=0"),
            listOf("", "[ShadowQuality@1]", "r.ShadowQuality=2", "r.Shadow.CSM.MaxCascades=3", "r.Shadow.MaxResolution=256", "r.LightFunctionQuality=1", "r.Shadow.MobileDistributionOverride=3.0"),
            listOf("", "[ShadowQuality@2]", "r.ShadowQuality=2", "r.Shadow.CSM.MaxCascades=3", "r.Shadow.MaxResolution=512", "r.LightFunctionQuality=1", "r.Shadow.CacheDirectLightShadow=3", "r.Shadow.MobileDistributionOverride=2.5"),
            listOf("", "[ShadowQuality@3]", "r.ShadowQuality=2", "r.Shadow.CSM.MaxCascades=3", "r.Shadow.MaxResolution=512", "r.LightFunctionQuality=1", "r.Shadow.CacheDirectLightShadow=3", "r.Shadow.MobileDistributionOverride=2.0"),
            listOf("", "[PostProcessQuality@0]", "r.MotionBlurQuality=0", "r.RenderTargetPoolMin=300", "r.AmbientOcclusionRadiusScale=1.2"),
            listOf("", "[PostProcessQuality@1]", "r.MotionBlurQuality=1", "r.RenderTargetPoolMin=400"),
            listOf("", "[PostProcessQuality@2]", "r.MotionBlurQuality=2", "r.RenderTargetPoolMin=500"),
            listOf("", "[PostProcessQuality@3]", "r.MotionBlurQuality=3", "r.RenderTargetPoolMin=600"),
            listOf(
                "", "[TextureQuality@0]",
                "r.Streaming.MipBias=16", "r.Streaming.PoolSize=300",
                "r.Streaming.PoolSizeForMeshes=300", "r.Streaming.Boost=0.3",
                "r.Streaming.MaxNumTexturesToStreamPerFrame=1",
                "r.TranslucencyLightingVolumeDim=24", "r.VT.MaxAnisotropy=4"
            ),
            listOf("", "[TextureQuality@1]", "r.Streaming.MipBias=8", "r.Streaming.PoolSize=400", "r.Streaming.PoolSizeForMeshes=400", "r.Streaming.Boost=0.5"),
            listOf("", "[TextureQuality@2]", "r.Streaming.MipBias=4", "r.Streaming.PoolSize=600", "r.Streaming.PoolSizeForMeshes=600", "r.Streaming.Boost=0.8"),
            listOf("", "[TextureQuality@3]", "r.Streaming.MipBias=0", "r.Streaming.PoolSize=800", "r.Streaming.PoolSizeForMeshes=800", "r.Streaming.Boost=1.0"),
            listOf("", "[EffectsQuality@0]", "r.DetailMode=0", "r.SSR.Quality=0", "r.SSR.HalfResSceneColor=1", "r.RefractionQuality=0", "r.SceneColorFormat=2", "r.TranslucencyVolumeBlur=0"),
            listOf("", "[EffectsQuality@1]", "r.DetailMode=1", "r.SSR.Quality=1", "r.SSR.HalfResSceneColor=1", "r.RefractionQuality=1"),
            listOf("", "[EffectsQuality@2]", "r.DetailMode=2", "r.SSR.Quality=2", "r.SSR.HalfResSceneColor=0", "r.RefractionQuality=2"),
            listOf("", "[FoliageQuality@0]", "foliage.DensityScale=1.0", "foliage.DensityType=0", "foliage.DensityScaleLOD.DensityType=0", "foliage.DensityScaleLOD.DistanceType=0", "grass.DensityScale=0.8", "grass.CullDistanceScale=0.8"),
            listOf("", "[FoliageQuality@1]", "foliage.DensityScale=1.0", "foliage.DensityType=1", "foliage.DensityScaleLOD.DensityType=1", "foliage.DensityScaleLOD.DistanceType=1", "grass.DensityScale=1.0", "grass.CullDistanceScale=0.9"),
            listOf("", "[FoliageQuality@2]", "foliage.DensityScale=1.0", "foliage.DensityType=2", "foliage.DensityScaleLOD.DensityType=2", "foliage.DensityScaleLOD.DistanceType=2", "grass.DensityScale=1.0", "grass.CullDistanceScale=1.0"),
            listOf("", "[ShadingQuality@2]", "r.HairStrands.SkyAO.SampleCount=4", "r.HairStrands.SkyLighting.IntegrationType=2", "r.HairStrands.Visibility.MSAA.SamplePerPixel=4"),
            listOf("", "[ShadingQuality@3]", "r.HairStrands.SkyAO.SampleCount=4", "r.HairStrands.SkyLighting.IntegrationType=2", "r.HairStrands.Visibility.MSAA.SamplePerPixel=4"),
            listOf(
                "", "[KuroRenderQuality@1]",
                "KuroRenderQuality.LevelName=均衡",
                "r.StaticMeshLODDistanceScale=2",
                "r.ScreenSizeCullRatioFactor=80",
                "r.DrawKuroPPLensflare=1",
                "r.Kuro.NpcDisappearDistance=1400",
                "r.Kuro.SkeletalMesh.LODDistanceScale=0.4",
                "r.Kuro.FloatingStaticMeshTickFactor=1.8",
                "r.Kuro.FlickerLightActorTickFactor=6.0",
                "r.Kuro.MaterialDesktopQualityShoulderRender=1",
                "r.Kuro.GlobalPointCloudStreamEnabled=0",
                "foliage.DensityType=0",
                "foliage.DensityScaleLOD.Switch=0"
            ),
            listOf(
                "", "[KuroRenderQuality@2]",
                "KuroRenderQuality.LevelName=画质优先偏性能",
                "r.StaticMeshLODDistanceScale=1.5",
                "r.ScreenSizeCullRatioFactor=60",
                "r.DrawKuroPPLensflare=1",
                "r.Kuro.NpcDisappearDistance=1600",
                "r.Kuro.SkeletalMesh.LODDistanceScale=0.5",
                "r.Kuro.FloatingStaticMeshTickFactor=1.5",
                "r.Kuro.FlickerLightActorTickFactor=4.0",
                "r.Kuro.MaterialDesktopQualityShoulderRender=2",
                "r.Kuro.GlobalPointCloudStreamEnabled=0",
                "foliage.DensityType=1",
                "foliage.DensityScaleLOD.Switch=0"
            ),
            listOf(
                "", "[KuroRenderQuality@0]",
                "KuroRenderQuality.LevelName=极致性能",
                "r.StaticMeshLODDistanceScale=3",
                "r.ScreenSizeCullRatioFactor=150",
                "r.DrawKuroPPLensflare=0",
                "r.Kuro.NpcDisappearDistance=1000",
                "r.Kuro.SkeletalMesh.LODDistanceScale=0.2",
                "r.Kuro.FloatingStaticMeshTickFactor=2.4",
                "r.Kuro.FlickerLightActorTickFactor=12.0",
                "r.Kuro.MaterialDesktopQualityShoulderRender=0",
                "r.Kuro.GlobalPointCloudStreamEnabled=0",
                "foliage.DensityType=0",
                "foliage.DensityScaleLOD.Switch=0"
            ),
            listOf(
                "", "[KuroRenderQuality@3]",
                "KuroRenderQuality.LevelName=画质优先",
                "r.StaticMeshLODDistanceScale=1",
                "r.ScreenSizeCullRatioFactor=40",
                "r.DrawKuroPPLensflare=1",
                "r.Kuro.NpcDisappearDistance=1800",
                "r.Kuro.SkeletalMesh.LODDistanceScale=0.6",
                "r.Kuro.FloatingStaticMeshTickFactor=1.2",
                "r.Kuro.FlickerLightActorTickFactor=2.4",
                "r.Kuro.MaterialDesktopQualityShoulderRender=3",
                "r.Kuro.GlobalPointCloudStreamEnabled=0",
                "foliage.DensityType=1",
                "foliage.DensityScaleLOD.Switch=0"
            ),
            listOf(
                "", "[KuroLocalRenderQuality@0]",
                "KuroRenderQuality.LevelName=极致性能",
                "r.StaticMeshLODDistanceScale=3",
                "r.ScreenSizeCullRatioFactor=150",
                "r.DrawKuroPPLensflare=0",
                "r.Kuro.NpcDisappearDistance=1000",
                "r.Kuro.SkeletalMesh.LODDistanceScale=0.2",
                "r.Kuro.FloatingStaticMeshTickFactor=2.4",
                "r.Kuro.FlickerLightActorTickFactor=12.0",
                "r.Kuro.MaterialDesktopQualityShoulderRender=0",
                "r.Kuro.GlobalPointCloudStreamEnabled=0"
            )
        )

        return (header + sections.flatten()).joinToString("\n")
    }

    // ── Hardware.ini ────────────────────────────────────────────────────────────

    private fun buildHardwareIni(
        p: PresetProfile,
        preset: String,
        opts: Options,
        deviceInfo: DeviceInfo
    ): String {
        val dt = computeDeviceTier(deviceInfo)
        val presetLabel = when (preset) {
            "potato", "endurance", "performance", "competitive" -> "Low"
            "balanced" -> "Mid"
            "high" -> "High"
            else -> "Ultra"
        }
        return listOf(
            "; WuWa Hardware.ini — CatSmoker",
            "; Generated for ${deviceInfo.deviceModel ?: "Android device"}",
            "",
            "[DeviceProfile]",
            "DeviceProfileName=Android_$presetLabel",
            "DeviceType=Android",
            "",
            "; FPS cap based on preset",
            "FramePace=${opts.fps}",
            "",
            "; Anisotropic filtering",
            "+CVars=r.MaxAnisotropy=${dt.maxAniso}",
            "",
            "; LOD bias — same ladder as Engine.ini so files never disagree",
            "+CVars=r.Streaming.MipBias=${if (p.mipbias < 0) 0 else p.mipbias}",
            "",
            "; Foliage LOD — preset-tuned value shared with Engine.ini",
            "+CVars=foliage.LODDistanceScale=${"%.2f".format(p.flod.coerceIn(0.3, 5.0))}"
        ).joinToString("\n")
    }

    // ── Small pure helpers (the reference's ConfigGenUtil, the parts this port uses) ──

    /**
     * Reuses the `[Core.System]` paths from the `Engine.ini` already on the device — that
     * list tracks the game's installed content plugins, so the live one beats any snapshot.
     * A missing section or a null file falls back to [DEFAULT_CORE_SYSTEM].
     */
    internal fun extractCoreSystemPaths(engineIni: String?): List<String> {
        if (engineIni == null) return DEFAULT_CORE_SYSTEM
        val lines = engineIni.lines()
        val inCore = lines.indexOfFirst { it.trim().equals("[Core.System]", ignoreCase = true) }
        if (inCore == -1) return DEFAULT_CORE_SYSTEM
        val paths = mutableListOf("[Core.System]")
        for (i in (inCore + 1) until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            if (line.trim().startsWith("[")) break
            if (line.trim().startsWith("Paths=", ignoreCase = true)) paths.add(line.trimEnd())
        }
        return if (paths.size > 1) paths else DEFAULT_CORE_SYSTEM
    }

    /**
     * The reference's snapshot of one game version's `[Core.System]` list. Only used when the
     * device's own `Engine.ini` could not be read — see [extractCoreSystemPaths].
     */
    val DEFAULT_CORE_SYSTEM = listOf(
        "[Core.System]",
        "Paths=../../../Engine/Content",
        "Paths=%GAMEDIR%Content",
        "Paths=../../../Engine/Plugins/ThirdParty/ImpostorBaker/Content",
        "Paths=../../../Engine/Plugins/json2struct/Content",
        "Paths=../../../Engine/Plugins/Experimental/FieldSystemPlugin/Content",
        "Paths=../../../Client/Plugins/LGUI/LGUI/Content",
        "Paths=../../../Engine/Plugins/PrefabSystem/Content",
        "Paths=../../../Engine/Plugins/FX/Niagara/Content",
        "Paths=../../../Client/Plugins/Kuro/KuroGameplay/Content",
        "Paths=../../../Client/Plugins/Puerts/Puerts/Content",
        "Paths=../../../Client/Plugins/Wwise/Content",
        "Paths=../../../Engine/Plugins/Editor/GeometryMode/Content",
        "Paths=../../../Engine/Plugins/MovieScene/SequencerScripting/Content",
        "Paths=../../../Engine/Plugins/Experimental/PythonScriptPlugin/Content",
        "Paths=../../../Client/Plugins/CrashSight/Content",
        "Paths=../../../Engine/Plugins/ThirdParty/QuickEditor/Content",
        "Paths=../../../Client/Plugins/Sharphereal/Content",
        "Paths=../../../Engine/Plugins/Experimental/GeometryProcessing/Content",
        "Paths=../../../Client/Plugins/Kuro/TASdkPlugin/Content",
        "Paths=../../../Client/Plugins/Kuro/KRDataAnalyticsPlugin/Content",
        "Paths=../../../Engine/Plugins/rdLODtools/Content",
        "Paths=../../../Client/Plugins/AudioMaterialPlugin/Content",
        "Paths=../../../Engine/Plugins/Runtime/Nvidia/DLSS/Content",
        "Paths=../../../Engine/Plugins/Runtime/HoudiniEngine/Content",
        "Paths=../../../Client/Plugins/Kuro/KuroHotPatch/Content",
        "Paths=../../../Client/Plugins/Kuro/KuroImposter/Content",
        "Paths=../../../Client/Plugins/Kuro/KuroAutomationTool/Content",
        "Paths=../../../Engine/Plugins/FX/HoudiniNiagara/Content",
        "Paths=../../../Client/Plugins/LogicDriverLite/Content",
        "Paths=../../../Engine/Plugins/Runtime/AudioSynesthesia/Content",
        "Paths=../../../Engine/Plugins/Experimental/ControlRig/Content",
        "Paths=../../../Engine/Plugins/Media/MediaCompositing/Content",
        "Paths=../../../Engine/Plugins/Runtime/Synthesis/Content",
        "Paths=../../../Engine/Plugins/SequenceDialogue/Content",
        "Paths=../../../Client/Plugins/Puerts/ReactUMG/Content",
        "Paths=../../../Client/Plugins/genesis-ue-plugin/RenderExporter/Content",
        "Paths=../../../Engine/Plugins/KuroiOSDelegate/Content",
        "Paths=../../../Client/Plugins/Kuro/KuroGameplayUI/Content",
        "Paths=../../../Engine/Plugins/Runtime/Nvidia/OpacityMicroMap/Content",
        "Paths=../../../Engine/Plugins/Experimental/ColorCorrectRegions/Content",
        "Paths=../../../Engine/Plugins/Compositing/OpenCVLensDistortion/Content",
        "Paths=../../../Engine/Plugins/Experimental/FastGeoStreaming/Content",
        "Paths=../../../Client/Plugins/Kuro/KuroWorldPartition/Content",
        "Paths=../../../Client/Plugins/BlockoutToolsPlugin/Content",
        "Paths=../../../Client/Plugins/ComfyTextures/Content",
        "Paths=../../../Client/Plugins/KuroComputeShader/Content",
        "Paths=../../../Client/Plugins/KuroTDM/Content",
        "Paths=../../../Client/Plugins/Kuro/ImposterBaker/Content",
        "Paths=../../../Client/Plugins/Kuro/KuroDynamicMeshBatch/Content",
        "Paths=../../../Client/Plugins/Kuro/KuroGachaTools/Content",
        "Paths=../../../Client/Plugins/Kuro/KuroPerfCat/Content",
        "Paths=../../../Client/Plugins/Kuro/KuroPSOTools/Content",
        "Paths=../../../Client/Plugins/Kuro/KuroPushSdk/Content",
        "Paths=../../../Client/Plugins/MeshBlend/Content",
        "Paths=../../../Client/Plugins/SdkParamExtend/Content",
        "Paths=../../../Client/Plugins/SpinePlugin/Content",
        "Paths=../../../Client/Plugins/TFlow/Content",
        "Paths=../../../Client/Plugins/TpSafe/Content",
        "Paths=../../../Engine/Plugins/AFME/Content",
        "Paths=../../../Engine/Plugins/Animation/ACLPlugin/Content",
        "Paths=../../../Engine/Plugins/AssetChecker/Content",
        "Paths=../../../Engine/Plugins/AssetMemoryAnalyzer/Content",
        "Paths=../../../Engine/Plugins/DawnSDK/DawnSDK/Content",
        "Paths=../../../Engine/Plugins/Editor/SpeedTreeImporter/Content",
        "Paths=../../../Engine/Plugins/Experimental/ChaosClothEditor/Content",
        "Paths=../../../Engine/Plugins/Experimental/ChaosNiagara/Content",
        "Paths=../../../Engine/Plugins/Experimental/ChaosSolverPlugin/Content",
        "Paths=../../../Engine/Plugins/GSR/Content",
        "Paths=../../../Engine/Plugins/KuroFI/Content",
        "Paths=../../../Engine/Plugins/MagicDawn/Content",
        "Paths=../../../Engine/Plugins/MFRCModule/Content",
        "Paths=../../../Engine/Plugins/MTKCompensatedTimeStep/Content",
        "Paths=../../../Engine/Plugins/MagtModule/Content",
        "Paths=../../../Engine/Plugins/Runtime/Intel/XeSS/Content",
        "Paths=../../../Engine/Plugins/Runtime/Nvidia/NRD/Content"
    )

    /**
     * Drops earlier duplicate cvar lines, keeping the last occurrence — UE reads
     * `SystemSettings` last-wins, and the ToA/perf-tweak sections intentionally override
     * earlier ones, so only *involuntary* duplicates (same key, same file, both live) are
     * removed. The reference's own algorithm, including the prefix family.
     */
    internal fun deduplicateIniText(text: String): String {
        val lines = text.lines()
        val seen = mutableMapOf<String, Int>()
        val toRemove = mutableSetOf<Int>()
        for ((i, line) in lines.withIndex()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith(";") || trimmed.startsWith("#") ||
                trimmed.startsWith("//") || trimmed.startsWith("[")
            ) continue
            val cvarLine = trimmed.removePrefix("+CVars=").removePrefix("-CVars=").trim()
            if (cvarLine.isEmpty() || cvarLine.startsWith(";") || cvarLine.startsWith("#") ||
                cvarLine.startsWith("//") || cvarLine.startsWith("[")
            ) continue
            val eq = cvarLine.indexOf('=')
            if (eq <= 0) continue
            val key = cvarLine.substring(0, eq).trim().lowercase()
            if (!CVAR_PREFIXES.any { key.startsWith(it) }) continue
            val prev = seen[key]
            if (prev != null) toRemove.add(prev)
            seen[key] = i
        }
        if (toRemove.isEmpty()) return text
        return lines.filterIndexed { i, _ -> i !in toRemove }.joinToString("\n")
    }

    /** `"1440 x 3200"`, `"1440*3200"` and `"1440x3200"` all parse; anything else is null. */
    internal fun parseResolution(res: String?): Pair<Int, Int>? {
        if (res.isNullOrBlank()) return null
        val parts = res.trim().split(Regex("\\s*[xX*]\\s*"))
        val w = parts.firstOrNull()?.toIntOrNull() ?: return null
        val h = parts.getOrNull(1)?.toIntOrNull() ?: return null
        if (w <= 0 || h <= 0) return null
        return w to h
    }

    private val CVAR_PREFIXES = listOf(
        "a.", "bbm.", "compat.", "cook.", "fx.", "foliage.", "gc.", "grass.",
        "kuro.", "lod.", "n.", "niagara.", "r.", "s.", "sg.", "slate.",
        "t.", "tick.", "vr.", "wp."
    )

    // The reference's GPU tier patterns: high-end Adreno 740+/8xx and modern Mali,
    // upper-mid Adreno 6xx/71x-73x and Mali G5x/G6x.
    private val HIGH_END_GPU_PATTERNS = listOf(
        Regex("""adreno.*7[4-9]\d"""),
        Regex("""adreno.*8\d{2}"""),
        Regex("""mali-g(7[2-9]\d|8\d{1,2}|9\d{1,2})""")
    )
    private val MID_GPU_PATTERNS = listOf(
        Regex("""adreno.*6\d{2}"""),
        Regex("""adreno.*7[1-3]\d"""),
        Regex("""mali-g(5\d{1,2}|6\d{1,2})""")
    )

    // Chipset → game device-profile name, the reference's own table. Matched against the
    // SoC/model text first, then against the GPU string alone. Adreno patterns use `.*`
    // between name and number because a real GL_RENDERER reads "Adreno (TM) 750".
    private val CHIPSET_PROFILES = listOf(
        Regex("""snapdragon\s*8\s*elite|sm8750|adreno.*830""", RegexOption.IGNORE_CASE) to "Android_Adreno830",
        Regex("""snapdragon\s*8\s*gen\s*3|sm8650|adreno.*750""", RegexOption.IGNORE_CASE) to "Android_Adreno750",
        Regex("""snapdragon\s*8\s*gen\s*2|sm8550|adreno.*740""", RegexOption.IGNORE_CASE) to "Android_Adreno740",
        Regex("""snapdragon\s*8\s*\+?\s*gen\s*1|sm8475|sm8450|adreno.*730""", RegexOption.IGNORE_CASE) to "Android_Adreno7xx",
        Regex("""snapdragon\s*7|sm7\d{3}|adreno.*7""", RegexOption.IGNORE_CASE) to "Android_Adreno7xx",
        Regex("""snapdragon\s*6|snapdragon\s*695|snapdragon\s*680|sm6\d{3}|adreno.*6""", RegexOption.IGNORE_CASE) to "Android_Adreno6xx",
        Regex("""adreno.*5""", RegexOption.IGNORE_CASE) to "Android_Adreno5xx",
        Regex("""adreno.*4""", RegexOption.IGNORE_CASE) to "Android_Adreno4xx",
        Regex("""dimensity\s*94|mali-g925""", RegexOption.IGNORE_CASE) to "Android_Mali_G925",
        Regex("""dimensity\s*93|mali-g720""", RegexOption.IGNORE_CASE) to "Android_Mali_G720",
        Regex("""dimensity\s*92|mali-g715""", RegexOption.IGNORE_CASE) to "Android_Mali_G715",
        Regex("""dimensity\s*90|mali-g710""", RegexOption.IGNORE_CASE) to "Android_Mali_G710",
        Regex("""dimensity\s*8|mali-g61[0-9]|mali-g615""", RegexOption.IGNORE_CASE) to "Android_Mali_G615",
        Regex("""dimensity\s*7|mali-g6""", RegexOption.IGNORE_CASE) to "Android_Mali_G61x",
        Regex("""dimensity\s*6|mali-g57""", RegexOption.IGNORE_CASE) to "Android_Mali_G57",
        Regex("""exynos\s*24|xclipse\s*9""", RegexOption.IGNORE_CASE) to "Android_Xclipse9xx",
        Regex("""exynos\s*13|xclipse\s*5""", RegexOption.IGNORE_CASE) to "Android_Xclipse5xx",
        Regex("""kirin|maleoon""", RegexOption.IGNORE_CASE) to "Android_Maleoon"
    )
    private val GPU_ONLY_PROFILES = listOf(
        Regex("""adreno.*830""", RegexOption.IGNORE_CASE) to "Android_Adreno830",
        Regex("""adreno.*750""", RegexOption.IGNORE_CASE) to "Android_Adreno750",
        Regex("""adreno.*740""", RegexOption.IGNORE_CASE) to "Android_Adreno740",
        Regex("""adreno.*730""", RegexOption.IGNORE_CASE) to "Android_Adreno7xx",
        Regex("""adreno.*7""", RegexOption.IGNORE_CASE) to "Android_Adreno7xx",
        Regex("""adreno.*6""", RegexOption.IGNORE_CASE) to "Android_Adreno6xx",
        Regex("""adreno.*5""", RegexOption.IGNORE_CASE) to "Android_Adreno5xx",
        Regex("""adreno.*4""", RegexOption.IGNORE_CASE) to "Android_Adreno4xx",
        Regex("""mali-g925""", RegexOption.IGNORE_CASE) to "Android_Mali_G925",
        Regex("""mali-g720""", RegexOption.IGNORE_CASE) to "Android_Mali_G720",
        Regex("""mali-g715""", RegexOption.IGNORE_CASE) to "Android_Mali_G715",
        Regex("""mali-g710""", RegexOption.IGNORE_CASE) to "Android_Mali_G710",
        Regex("""mali-g615""", RegexOption.IGNORE_CASE) to "Android_Mali_G615",
        Regex("""mali-g6""", RegexOption.IGNORE_CASE) to "Android_Mali_G61x",
        Regex("""mali-g57""", RegexOption.IGNORE_CASE) to "Android_Mali_G57",
        Regex("""xclipse\s*9""", RegexOption.IGNORE_CASE) to "Android_Xclipse9xx",
        Regex("""xclipse\s*5""", RegexOption.IGNORE_CASE) to "Android_Xclipse5xx",
        Regex("""maleoon""", RegexOption.IGNORE_CASE) to "Android_Maleoon"
    )

    private const val GRASSCULL_TIER_CEILING_MULT = 20
}
