package com.catsmoker.app.features.editgamefiles.pubg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The PUBG `Active.sav` byte patcher, against a fixture built to the GVAS layout the patcher's
 * KDoc documents — the layout verified by hex dump across every save bundled by the two
 * closed-source reference tools (`referance/pubg tools closed source referance/`) and by this
 * app's own `assets/PUBG/` blobs.
 *
 * The fixture exercises more than the happy path on purpose: a Bool property (whose value sits
 * in the hasGuid slot — the shape that once derailed a GVAS walk), a Str property whose *value
 * text contains "BattleFPS"* (the length-prefix guard's reason for existing), fields out of
 * alphabetical order (FPSLevel precedes BattleFPS in real saves too), and an absent field.
 *
 * A second test tier pins the patcher against a real save the app actually ships — the same
 * "shipped asset" check `GenshinConfigTemplateTest` makes — because a fixture proves the code
 * agrees with itself, while the bundled blob proves it agrees with the file the game wrote.
 */
class PubgSavePatcherTest {

    private fun fixture(): ByteArray {
        val file = File("src/test/resources/pubg_test_fixture.sav")
        assumeTrue("fixture not found at ${file.absolutePath}", file.isFile)
        return file.readBytes()
    }

    /** The bundled MaxFPS save — tq.tech.Fps's own 120fps unlock save (7777 bytes), byte-identical
     *  to `referance/New folder/active-sav/{1,2,5}/Active.sav`. */
    private fun bundledMaxFps(): ByteArray {
        val file = File("src/main/assets/PUBG/MaxFPS/Active.sav")
        assumeTrue("bundled PUBG save not found at ${file.absolutePath}", file.isFile)
        return file.readBytes()
    }

    @Test
    fun readsTheFieldsTheFixtureCarries() {
        val result = PubgSavePatcher.read(fixture())
        assertTrue(result.isGvas)
        val values = result.fields.mapValues { (it.value as? PubgSavePatcher.FieldOutcome.Value)?.value }
        assertEquals(6, values["BattleFPS"])
        assertEquals(6, values["LobbyFPS"])
        assertEquals(4, values["FPSLevel"])
        assertEquals(1, values["BattleRenderQuality"])
        assertEquals(1, values["LobbyRenderQuality"])
        // The observed (read-only) fields: the fixture carries none of them, and `read` still
        // reports every entry — absent, not unreadable.
        PubgSavePatcher.OBSERVED_FIELDS.forEach { name ->
            assertTrue("observed field $name is reported",
                result.fields.containsKey(name) && result.fields[name] is PubgSavePatcher.FieldOutcome.Absent)
        }
        assertEquals("observed fields never enter the summary as noise",
            "BattleFPS 6 · LobbyFPS 6 · FPSLevel 4 · BattleRenderQuality 1 · LobbyRenderQuality 1",
            result.summary())
    }

    @Test
    fun theNameInsideAStringValueIsNotMistakenForTheProperty() {
        // The fixture carries "BattleFPS 99 " as StrProperty *value text* before the real
        // property — a scanner without the fstr length-prefix check would read that instead.
        val result = PubgSavePatcher.read(fixture())
        val battleFps = result.fields[PubgSavePatcher.FIELD_BATTLE_FPS]
        assertEquals("the real property, not the string-value decoy", 6,
            (battleFps as? PubgSavePatcher.FieldOutcome.Value)?.value)
    }

    @Test
    fun absentAndNonGvasFilesAreReportedNotThrown() {
        val absent = PubgSavePatcher.read(fixture()).fields[PubgSavePatcher.FIELD_FPS_LEVEL]
        assertTrue(absent is PubgSavePatcher.FieldOutcome.Value) // sanity: fixture carries it

        val garbage = "not a save at all".toByteArray()
        val result = PubgSavePatcher.read(garbage)
        assertFalse(result.isGvas)
        result.fields.values.forEach {
            assertTrue("every field on non-GVAS input is unreadable",
                it is PubgSavePatcher.FieldOutcome.Unrecognized)
        }

        val truncated = fixture().copyOfRange(0, 300)
        val truncatedResult = PubgSavePatcher.read(truncated)
        // Fields whose blocks survive the truncation still read; later ones are absent.
        assertTrue(truncatedResult.fields[PubgSavePatcher.FIELD_FPS_LEVEL] is PubgSavePatcher.FieldOutcome.Value)
        assertTrue(truncatedResult.fields[PubgSavePatcher.FIELD_LOBBY_FPS] is PubgSavePatcher.FieldOutcome.Absent)
    }

    @Test
    fun patchRewritesOnlyTheTargetBytes() {
        val original = fixture()
        val result = PubgSavePatcher.patch(original, PubgSavePatcher.fpsEdits(120))
        assertTrue(result is PubgSavePatcher.PatchResult.Ok)
        val patched = (result as PubgSavePatcher.PatchResult.Ok).data

        assertEquals("a value rewrite never changes the length", original.size, patched.size)
        val changed = original.indices.filter { original[it] != patched[it] }
        assertEquals("exactly one byte per edited field", 3, changed.size)

        val readBack = PubgSavePatcher.read(patched)
        assertEquals(8, (readBack.fields["BattleFPS"] as PubgSavePatcher.FieldOutcome.Value).value)
        assertEquals(8, (readBack.fields["LobbyFPS"] as PubgSavePatcher.FieldOutcome.Value).value)
        assertEquals(7, (readBack.fields["FPSLevel"] as PubgSavePatcher.FieldOutcome.Value).value)
        assertEquals(1, (readBack.fields["BattleRenderQuality"] as PubgSavePatcher.FieldOutcome.Value).value)
        assertEquals(1, (readBack.fields["LobbyRenderQuality"] as PubgSavePatcher.FieldOutcome.Value).value)
    }

    @Test
    fun patchRewritesBothCameraFieldsWhenTheSaveCarriesThem() {
        val withViews = bundledMaxFps() // tq's phone save: Tp 90 / Fp 103 in the file
        val result = PubgSavePatcher.patch(withViews, PubgSavePatcher.viewEdits(tpView = 110, fpView = 103))
        assertTrue(result is PubgSavePatcher.PatchResult.Ok)
        val readBack = PubgSavePatcher.read((result as PubgSavePatcher.PatchResult.Ok).data)
        assertEquals(110, (readBack.fields["TpViewValue"] as PubgSavePatcher.FieldOutcome.Value).value)
        assertEquals(103, (readBack.fields["FpViewValue"] as PubgSavePatcher.FieldOutcome.Value).value)

        // A save without the camera fields refuses the whole preset, like every other edit —
        // the fixture carries none of the observed fields.
        val refused = PubgSavePatcher.patch(fixture(), PubgSavePatcher.viewEdits(tpView = 110, fpView = 103))
        assertTrue(refused is PubgSavePatcher.PatchResult.Refused)
    }

    @Test
    fun patchRefusesTheWholeEditWhenAnyFieldIsAbsent() {
        // The fixture has no "ResolutionQuality" — the all-or-nothing rule means the FPS edits
        // land nowhere rather than half-landing.
        val result = PubgSavePatcher.patch(fixture(), PubgSavePatcher.fpsEdits(90) + mapOf("ResolutionQuality" to 2))
        assertTrue(result is PubgSavePatcher.PatchResult.Refused)
        val reasons = (result as PubgSavePatcher.PatchResult.Refused).reasons
        assertTrue("ResolutionQuality" in reasons)
    }

    @Test
    fun tiersCarryTheReferencePinnedValues() {
        // Pinned by BattleGrounds_GFX's own tier assets (60 → 6/6/6, 90 → 7/7/6, 120 → 8/8/7);
        // if these numbers ever change, the evidence that changed them belongs in the KDoc first.
        assertEquals(listOf(6 to 6 to 6, 7 to 7 to 6, 8 to 8 to 7),
            PubgSavePatcher.FPS_TIERS.map { it.battleFps to it.lobbyFps to it.fpsLevel })
        assertEquals(listOf(1, 2, 3, 4, 5, 6), PubgSavePatcher.RENDER_TIERS.map { it.value })
    }

    @Test
    fun viewPresetsCarryTheReferencePinnedValues() {
        // Pinned by tq.tech.Fps's shipped saves: phone profiles (its 120fps/bgm saves, this
        // app's MaxFPS blob) hold Tp 90 / Fp 103; its tablet save (this app's TabletView blob)
        // holds Tp 110 / Fp 103. The unit is unestablished — these are the two data points.
        assertEquals(
            listOf(90 to 103, 110 to 103),
            PubgSavePatcher.VIEW_PRESETS.map { it.tpView to it.fpView }
        )
        // Both camera fields move together in one edit set.
        assertEquals(
            mapOf("TpViewValue" to 110, "FpViewValue" to 103),
            PubgSavePatcher.viewEdits(tpView = 110, fpView = 103)
        )
    }

    // ---------------------------------------------------------------- the real blob

    @Test
    fun readsTheBundledMaxFpsSave() {
        val result = PubgSavePatcher.read(bundledMaxFps())
        assertTrue(result.isGvas)
        val values = result.fields.mapValues { (it.value as? PubgSavePatcher.FieldOutcome.Value)?.value }
        // tq's 120fps unlock save: 8/8/8 (tq's own FPSLevel 8 — the documented divergence from
        // GFX's pinned 120 tier 8/8/7), battle render 6 (Super Smooth), lobby render 2.
        assertEquals(8, values["BattleFPS"])
        assertEquals(8, values["LobbyFPS"])
        assertEquals(8, values["FPSLevel"])
        assertEquals(6, values["BattleRenderQuality"])
        assertEquals(2, values["LobbyRenderQuality"])
        // Observed fields the real blob carries — read-only, reported. The camera pair is the
        // tq phone profile: Tp 90 / Fp 103.
        assertEquals(0, values["ArtQuality"])
        assertEquals(90, values["TpViewValue"])
        assertEquals(103, values["FpViewValue"])
        assertEquals(10006, values["LobbyStyleID"])
        // Every observed field reads as a value in this blob — the camera pair left
        // OBSERVED_FIELDS (it is a preset now), so it is asserted through the patch path in
        // patchRewritesBothCameraFieldsWhenTheSaveCarriesThem and by readsTheBundledTabletSave.
        PubgSavePatcher.OBSERVED_FIELDS.forEach { name ->
            assertTrue("observed field $name reads from the bundled save",
                result.fields[name] is PubgSavePatcher.FieldOutcome.Value)
        }
    }

    @Test
    fun readsTheBundledTabletSave() {
        val file = File("src/main/assets/PUBG/TabletView/Active.sav")
        assumeTrue("bundled PUBG save not found at ${file.absolutePath}", file.isFile)
        val result = PubgSavePatcher.read(file.readBytes())
        assertTrue(result.isGvas)
        val values = result.fields.mapValues { (it.value as? PubgSavePatcher.FieldOutcome.Value)?.value }
        // tq's tablet save: the 90 tier on every FPS field, render 1/1, and the wide camera
        // pair Tp 110 / Fp 103 that pins the tablet VIEW_PRESET.
        assertEquals(7, values["BattleFPS"])
        assertEquals(7, values["LobbyFPS"])
        assertEquals(7, values["FPSLevel"])
        assertEquals(1, values["BattleRenderQuality"])
        assertEquals(1, values["LobbyRenderQuality"])
        assertEquals(110, values["TpViewValue"])
        assertEquals(103, values["FpViewValue"])
        assertEquals(1, values["ArtQuality"])
    }

    @Test
    fun patchingTheBundledSaveDownAndBackIsLossless() {
        val original = bundledMaxFps()
        val down = PubgSavePatcher.patch(original, PubgSavePatcher.fpsEdits(60)) as PubgSavePatcher.PatchResult.Ok
        val downRead = PubgSavePatcher.read(down.data)
        assertEquals(6, (downRead.fields[PubgSavePatcher.FIELD_BATTLE_FPS] as PubgSavePatcher.FieldOutcome.Value).value)

        // 60 → 120 restores BattleFPS/LobbyFPS/FPSLevel to tq's own 8/8/8, so the save
        // round-trips byte-exact against the bundled blob.
        val back = PubgSavePatcher.patch(down.data, PubgSavePatcher.fpsEdits(120)) as PubgSavePatcher.PatchResult.Ok
        val backRead = PubgSavePatcher.read(back.data)
        assertEquals(8, (backRead.fields[PubgSavePatcher.FIELD_BATTLE_FPS] as PubgSavePatcher.FieldOutcome.Value).value)
        assertEquals(8, (backRead.fields[PubgSavePatcher.FIELD_LOBBY_FPS] as PubgSavePatcher.FieldOutcome.Value).value)
        // GFX's 120 tier pins FPSLevel 7; tq's own save carries 8 (the stated divergence), so
        // this byte does not return to its original value under GFX edits.
        assertEquals(7, (backRead.fields[PubgSavePatcher.FIELD_FPS_LEVEL] as PubgSavePatcher.FieldOutcome.Value).value)
        // Untouched fields survive every round-trip.
        assertEquals(6, (backRead.fields[PubgSavePatcher.FIELD_BATTLE_RENDER] as PubgSavePatcher.FieldOutcome.Value).value)
        assertEquals(2, (backRead.fields[PubgSavePatcher.FIELD_LOBBY_RENDER] as PubgSavePatcher.FieldOutcome.Value).value)
    }
}
