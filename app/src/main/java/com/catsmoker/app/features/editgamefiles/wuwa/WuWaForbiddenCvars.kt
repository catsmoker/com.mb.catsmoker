package com.catsmoker.app.features.editgamefiles.wuwa

/**
 * The "forbidden" cvars: keys the game's anti-cheat / integrity checks are known to watch,
 * stripped from generated configs when the user turns restricted cvars off. Key list and
 * matching rules (case-insensitive, `+`/`-` variants, `r.` re-prefixed for bare names) are
 * `referance/gamingtools/WuWa-Config-Android-main/config/ForbiddenCvars.kt` verbatim — that
 * file was read before this one was written, and the exact spelling of every key is the
 * whole payload here: a renamed key strips nothing.
 *
 * Two spellings of the CppEffectsSystem key are kept even though one is surely a typo,
 * because which one the watch list uses is the reference's finding, not ours to correct.
 */
object WuWaForbiddenCvars {

    val ALL: Set<String> = setOf(
        "r.Kuro.SkeletalMesh.LODDistanceScale",
        "r.Streaming.Boost",
        "r.Streaming.PoolSize",
        "r.Streaming.LimitPoolSizeTOVRAM",
        "r.Shadow.MaxCSMResolution",
        "r.Streaming.MinBoost",
        "r.MipMapLODBias",
        "r.TextureGroup.Landscape.TextureLODBias",
        "r.Kuro.TexturePool.ExtraBudgetMB",
        "r.Streaming.CPUReadback",
        "r.Streaming.UseAsyncCPUReadback",
        "r.Streaming.MaxNumTexturesToStreamPerFrame",
        "r.Streaming.MinMipForSplitRequest",
        "r.Streaming.UseFixedPoolsize",
        "r.Streaming.UseAllMips",
        "r.Streaming.MaxTempMemoryAllowed",
        "r.RayTracing.LimitDevice",
        "r.DetailMode",
        "r.MaterialQualityLevel",
        "r.KuroMaterialQualityLevel",
        "r.ViewDistanceScale",
        "Kuro.CppEffectsSystem.UseLowMemoryPlayerEffectLruCapacity",
        "Kuro.CppEffectSystem.UseLowMemoryPlayerEffectLruCapacity",
        "r.AsyncComputePSO",
        "r.Streamline.DLSSG.RetainResourcesWhenOff",
        "r.MobileContentScaleFactor",
        "r.SecondaryScreenPercentage.GameViewport",
        "r.ScreenPercentage",
        "r.AFME.Enable",
        "r.MFRC.Enable",
        "r.FEstimation.Option"
    )

    private val commonVariants: Set<String> = run {
        val variants = mutableSetOf<String>()
        for (key in ALL) {
            val lower = key.lowercase()
            variants.add(lower)
            variants.add("+" + lower)
            variants.add("-" + lower)
            if (!lower.startsWith("r.") && !lower.startsWith("kuro.")) {
                variants.add("r." + lower)
            }
        }
        variants
    }

    fun isForbidden(cvarKey: String): Boolean =
        commonVariants.contains(cvarKey.trim().lowercase())

    /**
     * Removes forbidden cvar lines from ini text, keeping everything else — comments,
     * section headers, blank lines — byte-for-byte where it stays. The leading `+CVars=` /
     * `-CVars=` directive is stripped *before* splitting on `=`; otherwise the delimiter
     * inside the directive would be parsed as part of the key (the reference's own comment
     * and fix).
     */
    fun stripForbiddenCvars(iniContent: String): String {
        val sb = StringBuilder()
        for (line in iniContent.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith(";") || trimmed.startsWith("#") ||
                trimmed.startsWith("//") || trimmed.isEmpty()
            ) {
                sb.appendLine(line)
                continue
            }
            val body = trimmed.removePrefix("+CVars=").removePrefix("-CVars=").trim()
            val eqIdx = body.indexOf('=')
            val keyPart = if (eqIdx >= 0) body.substring(0, eqIdx).trim() else body.trim()
            if (!isForbidden(keyPart)) {
                sb.appendLine(line)
            }
        }
        return sb.toString()
    }
}
