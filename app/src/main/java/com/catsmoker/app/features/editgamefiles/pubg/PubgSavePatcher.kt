package com.catsmoker.app.features.editgamefiles.pubg

/**
 * Byte-level read and patch of PUBG Mobile's `Active.sav` — an UE4 GVAS binary — built from the
 * closed-source reference tools in `referance/pubg tools closed source referance/`, whose APKs
 * were unpacked and their bundled saves read byte-by-byte to establish the layout below.
 *
 * **The mechanism.** tq.tech.Fps's `assets/savedit.sh` (read in full) is the whole idea: dump the
 * user's own save, sed-rewrite `BattleFPS`, `LobbyFPS` and `FPSLevel` to 8, apply, and move the
 * regenerated `Active_new.sav` back. That is read-modify-write of a handful
 * of ints inside the save the game itself wrote — not a whole-file replacement. The GFX tool
 * (`eu.tsoml.graphicssettings`) instead ships dozens of whole saves with the same fields
 * pre-set. This patcher is that `sed`, natively: locate each field, verify its layout, rewrite
 * its int32 in place — no dump/apply binary needed, and every other setting in the save
 * (sensitivity, iPad view, everything) survives untouched.
 *
 * **The layout** (verified by hex dump against every save in both tools' APKs and against the
 * blobs this app already bundles under `assets/PUBG/`): each property is
 *
 * - int32 LE (len+1) — the name fstr's length prefix, the length including the NUL
 * - ASCII name, then a 0x00 terminator
 * - int32 LE (len+1), the type string `"IntProperty"`, then 0x00
 * - int64 LE 4 — the declared value size
 * - one byte — hasGuid, 0 for these fields
 * - int32 LE — the value
 *
 * Only `IntProperty` is handled, and only with size 4 and hasGuid 0: `Active.sav` also carries
 * Bool, Array and Map properties (each with a different body layout — a Bool keeps its value in
 * the hasGuid slot, Array and Map have no hasGuid byte at all), and a full GVAS serializer that
 * mis-steps on one of those would corrupt a save the game spent hours writing. The scanner
 * matches a field only when the name's length prefix checks out (the same letters inside some
 * string value must not match) and the type/size/hasGuid bytes are exactly as verified;
 * anything else is reported and left alone. Fields match the first occurrence whose length
 * prefix is valid — property names are unique in a top-level GVAS properties list.
 */
object PubgSavePatcher {

    /** The fields this editor knows, spelled exactly as the save names them. */
    const val FIELD_BATTLE_FPS = "BattleFPS"
    const val FIELD_LOBBY_FPS = "LobbyFPS"
    const val FIELD_FPS_LEVEL = "FPSLevel"
    const val FIELD_BATTLE_RENDER = "BattleRenderQuality"
    const val FIELD_LOBBY_RENDER = "LobbyRenderQuality"
    const val FIELD_TP_VIEW = "TpViewValue"
    const val FIELD_FP_VIEW = "FpViewValue"

    val KNOWN_FIELDS = listOf(FIELD_BATTLE_FPS, FIELD_LOBBY_FPS, FIELD_FPS_LEVEL, FIELD_BATTLE_RENDER, FIELD_LOBBY_RENDER)

    /**
     * The camera-view pair, patchable through [VIEW_PRESETS] only — both fields always move
     * together, never alone. Listed separately from [KNOWN_FIELDS] so the five-tier summary
     * line stays the headline; they are still editable, just not in that line.
     */
    val VIEW_FIELDS = listOf(FIELD_TP_VIEW, FIELD_FP_VIEW)

    /**
     * Other int fields real saves carry, reported by [read] but never written by this editor.
     *
     * The deep decompile (2026-09-07) pattern-scanned all 34 GVAS saves bundled in the two
     * closed reference tools' APKs: these are the additional [IntProperty] fields found, each
     * observed with only one to four distinct values from one author's tools. That is
     * observation, not verification — unlike the FPS tiers (pinned per-tier by BattleGrounds_GFX's
     * own assets), the render ladder (pinned by the GFX tool's `Render Quality/` tree) and the
     * camera-view pair below (pinned by tq's phone-vs-iPad saves), no reference demonstrates
     * what any of these values *mean* across a tier range, so editing them would be guessing
     * with the user's save. They are read so the summary shows the whole file — a save whose
     * FPS fields disagree with its render style is visible at a glance — and stay out of
     * [patch]'s reach.
     */
    val OBSERVED_FIELDS = listOf(
        "ArtQuality",
        "BattleRenderStyle",
        "CameraLensSensibility",
        "FireCameraLensSensibility",
        "GFBestQBattle",
        "GFBestQLobby",
        "GraphicFavor",
        "Gyroscope",
        "HitEffectColor",
        "HurtEffectColor",
        "JoystickSprintSensitity",
        "LobbyRenderStyle",
        "LobbyStyleID",
        "MainCityFPS",
        "MainCityRenderQuality",
        "ManorRenderQuality",
        "OpenMirrorMode",
        "ReceiverSetting",
        "SidewaysMode",
        "SoundVisualizationType"
    )

    private const val GVAS_MAGIC = "GVAS"
    private const val INT_PROPERTY = "IntProperty"
    private const val NUL: Byte = 0

    /** What one field read turned out to be — the three outcomes are kept distinct on purpose. */
    sealed class FieldOutcome {
        /** Present with the verified layout; [value] is what the file holds. */
        data class Value(val value: Int) : FieldOutcome()

        /** The save does not carry this field at all — nothing to read, nothing to write. */
        object Absent : FieldOutcome()

        /** The name matched, but the bytes around it are not the verified IntProperty layout. */
        data class Unrecognized(val reason: String) : FieldOutcome()
    }

    data class ReadResult(
        val fields: Map<String, FieldOutcome>,
        val sizeBytes: Int,
        val isGvas: Boolean
    ) {
        /**
         * One line for the UI: the five editable fields — what the file holds, with anomalies
         * named rather than hidden. The [OBSERVED_FIELDS] entries are [observedSummary]'s line,
         * kept out of this one so the editable five stay the headline.
         */
        fun summary(): String = KNOWN_FIELDS.mapNotNull { name ->
            val outcome = fields[name] ?: return@mapNotNull null
            when (outcome) {
                is FieldOutcome.Value -> "$name ${outcome.value}"
                is FieldOutcome.Absent -> "$name absent"
                is FieldOutcome.Unrecognized -> "$name unreadable"
            }
        }.joinToString(" · ")

        /**
         * The observed fields as their own line, or null when the save carries none — kept out
         * of [summary] so the editable five stay the headline and the snackbar line stays short.
         */
        fun observedSummary(): String? = OBSERVED_FIELDS.mapNotNull { name ->
            when (val outcome = fields[name] ?: return@mapNotNull null) {
                is FieldOutcome.Value -> "$name ${outcome.value}"
                is FieldOutcome.Absent -> null
                is FieldOutcome.Unrecognized -> "$name unreadable"
            }
        }.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /**
     * Reads every editable field plus every [OBSERVED_FIELDS] entry out of [data]. Never
     * throws — a bad file reads as [FieldOutcome.Unrecognized].
     */
    fun read(data: ByteArray): ReadResult = ReadResult(
        fields = (KNOWN_FIELDS + VIEW_FIELDS + OBSERVED_FIELDS).associateWith { readField(data, it) },
        sizeBytes = data.size,
        isGvas = startsWithGvas(data)
    )

    fun readField(data: ByteArray, name: String): FieldOutcome = when (val located = locate(data, name)) {
        is Locate.Found -> FieldOutcome.Value(getIntLE(data, located.valueOffset))
        is Locate.Missing ->
            if (located.absent) FieldOutcome.Absent else FieldOutcome.Unrecognized(located.reason)
    }

    sealed class PatchResult {
        /** A copy of the input with every requested field rewritten to its new value. */
        data class Ok(val data: ByteArray, val applied: Map<String, Int>) : PatchResult()

        /** Nothing was written; every refusal is named so the UI can carry the reasons through. */
        data class Refused(val reasons: Map<String, String>) : PatchResult()
    }

    /**
     * Returns [data] with every field in [edits] rewritten — or refuses the whole patch when any
     * one of them cannot be located with the verified layout. All-or-nothing on purpose: the FPS
     * fields belong together (a lone `BattleFPS` beside an old `FPSLevel` is a disagreement
     * inside one file), and a half-patched save is a worse state than an unpatched one.
     */
    fun patch(data: ByteArray, edits: Map<String, Int>): PatchResult {
        if (edits.isEmpty()) return PatchResult.Ok(data, emptyMap())
        val offsets = mutableMapOf<String, Int>()
        val reasons = mutableMapOf<String, String>()
        for (name in edits.keys) {
            when (val located = locate(data, name)) {
                is Locate.Found -> offsets[name] = located.valueOffset
                is Locate.Missing -> reasons[name] = located.reason
            }
        }
        if (reasons.isNotEmpty()) return PatchResult.Refused(reasons)
        val out = data.copyOf()
        for ((name, value) in edits) putIntLE(out, offsets.getValue(name), value)
        return PatchResult.Ok(out, edits)
    }

    /**
     * FPS tiers, pinned by the open reference `referance/gamingtools/BattleGrounds_GFX-main`'s
     * own tier assets — `app/src/main/assets/Active_60.sav` etc., each named for its tier and
     * carrying exactly these values for BattleFPS/LobbyFPS/FPSLevel:
     * 60 → 6/6/6, 90 → 7/7/6, 120 → 8/8/7.
     *
     * tq.tech.Fps's unlock (closed reference) writes 8/8/8 instead — its `FPSLevel` 8 disagrees
     * with GFX's 7 at 120 FPS. GFX is followed because it is the established cross-check for
     * this screen; the divergence is stated, not averaged away.
     */
    data class FpsTier(val fps: Int, val battleFps: Int, val lobbyFps: Int, val fpsLevel: Int)

    val FPS_TIERS = listOf(
        FpsTier(60, battleFps = 6, lobbyFps = 6, fpsLevel = 6),
        FpsTier(90, battleFps = 7, lobbyFps = 7, fpsLevel = 6),
        FpsTier(120, battleFps = 8, lobbyFps = 8, fpsLevel = 7)
    )

    /** The three FPS fields one tier writes, as a [patch] edit set. */
    fun fpsEdits(fps: Int): Map<String, Int> =
        FPS_TIERS.firstOrNull { it.fps == fps }?.let {
            mapOf(FIELD_BATTLE_FPS to it.battleFps, FIELD_LOBBY_FPS to it.lobbyFps, FIELD_FPS_LEVEL to it.fpsLevel)
        } ?: emptyMap()

    /**
     * Render-quality tiers, pinned by the closed GFX tool's own assets —
     * `assets/files/Render Quality/{Smooth,Balanced,HD,HDR,Ultra,Super Smooth}/Active.sav`:
     * `BattleRenderQuality` and `LobbyRenderQuality` always move together, 1 through 6.
     */
    data class RenderTier(val label: String, val value: Int)

    val RENDER_TIERS = listOf(
        RenderTier("Smooth", 1),
        RenderTier("Balanced", 2),
        RenderTier("HD", 3),
        RenderTier("HDR", 4),
        RenderTier("Ultra", 5),
        RenderTier("Super Smooth", 6)
    )

    /** The two render-quality fields one tier writes, as a [patch] edit set. */
    fun renderEdits(value: Int): Map<String, Int> =
        mapOf(FIELD_BATTLE_RENDER to value, FIELD_LOBBY_RENDER to value)

    /**
     * Camera-view presets, pinned by tq.tech.Fps's own shipped saves: every phone-context save
     * (its 120fps/bgm root profiles, `referance/New folder/active-sav/{1,2,5}`) carries
     * `TpViewValue` 90 with `FpViewValue` 103, while its tablet-view save
     * (`active-sav/3`, byte-identical to this app's `assets/PUBG/TabletView` blob) carries
     * 110 with 103. The *unit* — FOV degrees, camera-distance scale, multiplier — is not
     * established by any reference (no decompiled tool code reads or writes either field;
     * they appear only in save data), so the presets are the two device-shaped points the
     * references actually ship and nothing beyond them.
     */
    data class ViewPreset(val label: String, val tpView: Int, val fpView: Int)

    val VIEW_PRESETS = listOf(
        ViewPreset("Phone (90/103)", tpView = 90, fpView = 103),
        ViewPreset("Tablet Wide (110/103)", tpView = 110, fpView = 103)
    )

    /** The two camera-view fields one preset writes, as a [patch] edit set. */
    fun viewEdits(tpView: Int, fpView: Int): Map<String, Int> =
        mapOf(FIELD_TP_VIEW to tpView, FIELD_FP_VIEW to fpView)

    /** Where one field's int32 lives, or why it cannot be written honestly. */
    private sealed interface Locate {
        data class Found(val valueOffset: Int) : Locate
        data class Missing(val absent: Boolean, val reason: String) : Locate
    }

    private fun locate(data: ByteArray, name: String): Locate {
        if (!startsWithGvas(data)) {
            return Locate.Missing(absent = false, reason = "file does not start with the GVAS magic")
        }
        val nameBytes = name.toByteArray(Charsets.US_ASCII) + NUL
        val typeBytes = INT_PROPERTY.toByteArray(Charsets.US_ASCII) + NUL
        var from = 0
        while (true) {
            val i = indexOf(data, nameBytes, from)
                ?: return Locate.Missing(absent = true, reason = "not in the file")
            // The name fstr carries its length (name + NUL) immediately before the name — a match
            // without that prefix is the same letters inside some other value, not a property.
            if (i >= 4 && getIntLE(data, i - 4) == nameBytes.size) {
                val afterName = i + nameBytes.size
                val typeLen = if (afterName + 4 <= data.size) getIntLE(data, afterName) else -1
                val afterType = afterName + 4 + typeLen
                // 13 = the int64 size + the hasGuid byte + the int32 value that must follow.
                return when {
                    typeLen != typeBytes.size || afterType + 13 > data.size ->
                        Locate.Missing(false, "the $INT_PROPERTY block is truncated or absent")
                    !matchesAt(data, afterName + 4, typeBytes) ->
                        Locate.Missing(false, "type is not $INT_PROPERTY")
                    getLongLE(data, afterType) != 4L ->
                        Locate.Missing(false, "declared value size is ${getLongLE(data, afterType)}, not 4")
                    data[afterType + 8] != NUL ->
                        Locate.Missing(false, "hasGuid byte is not 0")
                    else -> Locate.Found(afterType + 9)
                }
            }
            from = i + 1
        }
    }

    private fun startsWithGvas(data: ByteArray): Boolean {
        val magic = GVAS_MAGIC.toByteArray(Charsets.US_ASCII)
        return matchesAt(data, 0, magic)
    }

    private fun indexOf(data: ByteArray, pattern: ByteArray, from: Int): Int? {
        var i = from
        outer@ while (i <= data.size - pattern.size) {
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) {
                    i++
                    continue@outer
                }
            }
            return i
        }
        return null
    }

    private fun matchesAt(data: ByteArray, offset: Int, pattern: ByteArray): Boolean {
        if (offset < 0 || offset + pattern.size > data.size) return false
        for (j in pattern.indices) {
            if (data[offset + j] != pattern[j]) return false
        }
        return true
    }

    private fun getIntLE(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            (data[offset + 3].toInt() shl 24)

    private fun getLongLE(data: ByteArray, offset: Int): Long {
        var value = 0L
        for (k in 7 downTo 0) value = (value shl 8) or (data[offset + k].toLong() and 0xFF)
        return value
    }

    private fun putIntLE(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value shr 8) and 0xFF).toByte()
        data[offset + 2] = ((value shr 16) and 0xFF).toByte()
        data[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }
}
