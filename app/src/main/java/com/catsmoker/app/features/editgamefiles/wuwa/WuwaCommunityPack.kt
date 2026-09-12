package com.catsmoker.app.features.editgamefiles.wuwa

/**
 * The community pack half of the WuWa importer: turn whatever folder of files a user picked
 * into named config variants plus the pack's own READMEs, and screen every ini against
 * [WuWaForbiddenCvars] before any of it can be deployed.
 *
 * The pack shape comes from `referance/gamingtools/Mobile-WuWa-Config-main/Community Configs/`
 * (both packs read in full before this file was written), which is the TODO's named source:
 *
 * - **Mythos Overdrive Config** — `README.md` + `Engine.ini` at the pack root. One variant.
 * - **@Kodoupulse LowEnd Config** — `README.md` at the root, then variant *folders*: `No Vulkan/`
 *   (Engine.ini), `With Vulkan/` (Engine.ini + DeviceProfiles.ini), and an `Extra/` folder that
 *   holds only another README. So a folder is a variant only when it directly carries at least
 *   one of the five monitored inis; a folder of notes is not a variant, but its README still
 *   travels with the pack.
 *
 * Deliberate decisions, because a pack is *not* a generated config:
 *
 * - **The packs are never bundled.** The source repo's own README is a community disclaimer —
 *   "I do not personaly own or create these configurations… entirely submitted and updated by
 *   the community through Pull Requests". Importing what the user picked keeps the app out of
 *   the business of endorsing one stranger's cvar list, which is the same reason
 *   `SpoofRepository.getPresets()` stays author-curated. The README travels with the pack —
 *   its device/chipset/FPS claims and its WARNING are the author's words, shown as such.
 * - **The forbidden-cvars list is a gate, not a courtesy flag.** [WuWaForbiddenCvars] exists
 *   because the game's integrity checks watch those keys; the generator strips them when
 *   "Allow restricted cvars" is off. A community pack gets the same rule with the same teeth:
 *   a variant that carries restricted cvars is refused as-is and can only be deployed through
 *   [stripVariant], which removes exactly those lines and reports the count. An imported pack
 *   must not be a way around the gate the generator already obeys.
 *
 * Everything here is pure and JVM-testable; the SAF folder walk and the JSON store live in
 * [WuwaConfigManager] and [WuwaCommunityPackStore].
 */
object WuwaCommunityPack {

    /**
     * The five ini files the game's own monitor watches — the same list as
     * [WuwaConfigManager.monitoredFiles] and the reference's `GamePaths.MONITORED_FILES`.
     * A pack file whose name matches one of these (case-insensitively — community zips are
     * not consistent) is a config; everything else is either a README or an unknown file.
     */
    val KNOWN_INI_NAMES: List<String> = listOf(
        "Engine.ini", "DeviceProfiles.ini", "GameUserSettings.ini", "Scalability.ini", "Hardware.ini"
    )

    /** One file the picker found, addressed by its path relative to the pack root. */
    data class PackFile(val relativePath: String, val content: String)

    /**
     * One deployable folder of a pack. [files] maps the canonical ini name to its content; a
     * pack that ships `engine.ini` deploys as `Engine.ini`, because the game's config dir is
     * case-sensitive on the read the deploy verifies. [forbidden] maps those same names to the
     * restricted cvar keys found in them — empty means the variant deploys as-is.
     */
    data class Variant(
        val name: String,
        val files: Map<String, String>,
        val forbidden: Map<String, List<String>>
    ) {
        val forbiddenCount: Int get() = forbidden.values.sumOf { it.size }
    }

    /** One parsed pack: READMEs keyed by relative path, variants, and files that are neither. */
    data class Pack(
        val readmes: Map<String, String>,
        val variants: List<Variant>,
        val unknownFiles: List<String>
    ) {
        /** A pack with no variant has nothing deployable — the import layer refuses it. */
        val isDeployable: Boolean get() = variants.isNotEmpty()
    }

    /** One `Label: value` line out of a README, with the WARNING labels flagged for the UI. */
    data class Fact(val label: String, val value: String, val warning: Boolean = false)

    /** A variant put through the restricted-cvar strip: what will actually be deployed. */
    data class StrippedVariant(val files: Map<String, String>, val removedLines: Map<String, Int>) {
        val removedCount: Int get() = removedLines.values.sum()
    }

    /**
     * Parses a flat list of picked files into a [Pack]. Rules, shaped by the two reference
     * packs: paths are normalized to `/`; a README is any file named `readme` (with any
     * extension, any case) and is kept whole under its relative path; a folder is a variant
     * when it directly contains at least one known ini, with the pack root itself a variant
     * named "(pack root)"; unknown files are reported, never imported. A variant is named for
     * its leaf folder — unless two folders share a leaf, in which case the full relative path
     * disambiguates, so a deploy lookup by name can never collapse two different folders.
     */
    fun parse(files: List<PackFile>): Pack {
        val readmes = linkedMapOf<String, String>()
        val unknownFiles = mutableListOf<String>()
        // folder path ("" = pack root) → canonical ini name → content
        val folders = linkedMapOf<String, MutableMap<String, String>>()
        for (file in files) {
            val path = file.relativePath.replace('\\', '/').trim('/')
            if (path.isEmpty()) continue
            val name = path.substringAfterLast('/')
            if (isReadmeName(name)) {
                readmes[path] = file.content
                continue
            }
            val canonical = canonicalIniName(name)
            if (canonical != null) {
                folders.getOrPut(path.substringBeforeLast('/', "")) { linkedMapOf() }[canonical] = file.content
            } else {
                unknownFiles.add(path)
            }
        }
        val leafNames = folders.keys.map { dir ->
            if (dir.isEmpty()) "(pack root)" else dir.substringAfterLast('/')
        }
        val leafCounts = leafNames.groupingBy { it }.eachCount()
        val variants = folders.entries
            .filter { it.value.isNotEmpty() }
            .sortedBy { (dir, _) -> if (dir.isEmpty()) 0 else 1 }
            .map { (dir, inis) ->
                val leaf = if (dir.isEmpty()) "(pack root)" else dir.substringAfterLast('/')
                Variant(
                    // Leaf name keeps the reference packs' short labels ("No Vulkan"); only when
                    // two folders share a leaf does the full relative path disambiguate, so a
                    // variant lookup by name can never collapse two different folders.
                    name = if ((leafCounts[leaf] ?: 0) > 1) dir else leaf,
                    files = inis,
                    forbidden = inis.mapValues { (_, content) -> forbiddenCvarsIn(content) }
                )
            }
        return Pack(readmes = readmes, variants = variants, unknownFiles = unknownFiles)
    }

    /** The README's own name test: `readme` with any extension, any case. */
    private fun isReadmeName(name: String): Boolean =
        name.substringBeforeLast('.', name).equals("readme", ignoreCase = true)

    /** Maps a pack file's name onto the canonical monitored name, or null when it is not one. */
    private fun canonicalIniName(name: String): String? =
        KNOWN_INI_NAMES.firstOrNull { it.equals(name, ignoreCase = true) }

    /**
     * The restricted cvar keys one ini carries, in file order, as they appear — the same key
     * extraction [WuWaForbiddenCvars.stripForbiddenCvars] strips by (comment lines skipped,
     * `+CVars=`/`-CVars=` directives stripped before the `=` split), so what this reports and
     * what the strip removes can never disagree.
     */
    fun forbiddenCvarsIn(iniContent: String): List<String> {
        val found = mutableListOf<String>()
        for (line in iniContent.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith(";") || trimmed.startsWith("#") ||
                trimmed.startsWith("//")
            ) {
                continue
            }
            val body = trimmed.removePrefix("+CVars=").removePrefix("-CVars=").trim()
            val eqIdx = body.indexOf('=')
            val keyPart = if (eqIdx >= 0) body.substring(0, eqIdx).trim() else body
            if (WuWaForbiddenCvars.isForbidden(keyPart)) found.add(keyPart)
        }
        return found
    }

    /**
     * Removes exactly the restricted cvar lines from a variant, file by file, and reports how
     * many lines each file lost — the gate's other door: the pack still deploys, and the
     * deploy report states the strip rather than implying the pack was applied verbatim.
     *
     * The count is [forbiddenCvarsIn]'s count, not a before/after line diff: the scan and the
     * strip share one key predicate, so they cannot disagree — while a line-count diff would
     * be thrown off by the trailing-newline a file without one gains through the strip.
     */
    fun stripVariant(variant: Variant): StrippedVariant {
        val files = linkedMapOf<String, String>()
        val removed = linkedMapOf<String, Int>()
        for ((name, content) in variant.files) {
            files[name] = WuWaForbiddenCvars.stripForbiddenCvars(content)
            removed[name] = forbiddenCvarsIn(content).size
        }
        return StrippedVariant(files = files, removedLines = removed)
    }

    /**
     * Pulls a README's `Label: value` lines out as facts — the pack READMEs' own shape
     * (Device Model, Chipset, RAM, OS Version, Game Version, Average FPS, Temperature Range,
     * Testing Duration, WARNING…). A label's value absorbs the non-blank lines that follow it
     * until a line that is itself a label, a bullet, or a heading — the reference packs write
     * multi-line WARNING and "Other Processors Tested" lists this way. Prose lines and bullets
     * are not facts; the full README text is always shown alongside, never replaced by this.
     */
    fun readmeFacts(readme: String): List<Fact> {
        val facts = mutableListOf<Fact>()
        var current: Int = -1
        for (raw in readme.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) {
                current = -1
                continue
            }
            val match = Regex("^([A-Za-z][A-Za-z0-9 ./&+()'-]{1,40}):\\s*(.*)$").find(line)
            if (match != null) {
                val label = match.groupValues[1].trim()
                val value = match.groupValues[2].trim()
                // The packs' labels all start uppercase (Device Model, Chipset, RAM, WARNING,
                // References); a lowercase "label" is prose or a URL scheme (https:), not a
                // fact — and digit-led lines like a 12:30 timestamp never match the regex at
                // all. Any label-shaped line still *ends* the previous fact, so a stray
                // "references:" is never glued onto the previous value either.
                val warning = label.equals("warning", ignoreCase = true)
                if (warning || label.first().isUpperCase()) {
                    facts.add(Fact(label = label, value = value, warning = warning))
                    current = facts.size - 1
                } else {
                    current = -1
                }
                continue
            }
            if (current >= 0 && !line.startsWith("*") && !line.startsWith(">") &&
                !line.startsWith("#") && !line.startsWith("-") && !line.startsWith("[")
            ) {
                facts[current] = facts[current].let { it.copy(value = (it.value + " " + line).trim()) }
            } else {
                current = -1
            }
        }
        return facts
    }
}
