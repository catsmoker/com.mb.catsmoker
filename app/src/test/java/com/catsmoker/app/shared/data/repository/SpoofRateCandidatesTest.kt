package com.catsmoker.app.shared.data.repository

import com.catsmoker.app.shared.data.model.DeviceProfile
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the frame-rate ladder against
 * `referance/spoofdevice/zygisk-Tweaker-main/module/config/tweaker.json` (read in full) and the
 * selection rule its README states: a panel above every candidate model's tier is lowered to the
 * closest supported value. The module's native picker ships only as compiled `.so` files, so what
 * is pinned here is the documented rule and the JSON's shape — plus the cases the README does not
 * cover, which are stated choices in `pickRateCandidate`'s KDoc rather than reference facts.
 */
class SpoofRateCandidatesTest {

    private fun rung(profileId: String, rateHz: Int) = SpoofRepository.RateCandidate(profileId, rateHz)

    private fun ladderOf(vararg rungs: SpoofRepository.RateCandidate) = rungs.toList()

    // ── pickRateCandidate ───────────────────────────────────────────────────────────

    @Test
    fun picksTheHighestTierThePanelCanHonor() {
        val ladder = ladderOf(rung("pixel", 90), rung("s26", 120))
        // A 144 Hz panel earns the 120 tier, not the 120-and-then-some.
        assertEquals("s26", pick(ladder, 144f))
        assertEquals("s26", pick(ladder, 120f))
        assertEquals("pixel", pick(ladder, 90f))
    }

    @Test
    fun stepsDownToTheClosestSupportedValue() {
        // The README's own example: two models, one supports 120 and the other 90, so a 144 Hz
        // setting is lowered to 120 — and if there were no 120, to 90.
        assertEquals("s26", pick(ladderOf(rung("s26", 120), rung("pixel", 90)), 144f))
        assertEquals("pixel", pick(ladderOf(rung("pixel", 90)), 144f))
    }

    @Test
    fun aPanelBelowEveryTierGetsTheLowestOne() {
        // Not covered by the README: a 60 Hz panel with 90/120 rungs. The rule never raises a rung
        // above what the panel reported, and the closest supported value from below is the lowest.
        assertEquals("pixel", pick(ladderOf(rung("s26", 120), rung("pixel", 90)), 60f))
    }

    @Test
    fun roundsThePanelPeakBeforeComparing() {
        // Android reports nominal 120 Hz panels as modes like 119.999985. An unrounded compare
        // would step the 120 rung down to 90 on a panel that is, for the game's purposes, 120.
        assertEquals("s26", pick(ladderOf(rung("s26", 120), rung("pixel", 90)), 119.999985f))
    }

    @Test
    fun anEmptyLadderPicksNothing() {
        // "No ladder" is null, never a fabricated rung.
        assertNull(SpoofRepository.pickRateCandidate(emptyList(), 144f))
    }

    @Test
    fun tiesKeepTheLadderOrder() {
        val ladder = ladderOf(rung("first", 120), rung("second", 120))
        assertEquals("first", pick(ladder, 144f))
    }

    @Test
    fun theSameProfileAtSeveralRatesCoversTheReferenceRateArray() {
        // tweaker.json lets one model carry rate [90,120]. Here that is the same profile at two
        // rates, and the pick rule lands on that model at either panel — while a cheaper profile
        // below it still wins when the panel cannot honor the array at all.
        val ladder = ladderOf(rung("a", 120), rung("a", 90), rung("b", 60))
        assertEquals("a", pick(ladder, 144f))
        assertEquals("a", pick(ladder, 90f))
        assertEquals("b", pick(ladder, 60f))
    }

    private fun pick(ladder: List<SpoofRepository.RateCandidate>, panelPeakHz: Float): String? =
        SpoofRepository.pickRateCandidate(ladder, panelPeakHz)?.profileId

    // ── resolveProfileForPackage ────────────────────────────────────────────────────

    private fun store(
        assignments: Map<String, String> = emptyMap(),
        rateAssignments: Map<String, List<SpoofRepository.RateCandidate>> = emptyMap(),
        vararg profileIds: String
    ) = SpoofRepository.StoreData(
        profiles = profileIds.map { SpoofRepository.ProfileEntry(it, "Profile $it", DeviceProfile()) },
        assignments = assignments,
        rateAssignments = rateAssignments
    )

    @Test
    fun resolvePrefersTheLadderOverThePlainAssignment() {
        val data = store(
            assignments = mapOf("com.game" to "single"),
            rateAssignments = mapOf("com.game" to ladderOf(rung("pixel", 90), rung("s26", 120))),
            "single", "pixel", "s26"
        )
        // A 144 Hz panel earns the ladder's 120 rung, not the plain assignment beside it.
        assertEquals(
            data.profiles.first { it.id == "s26" }.profile,
            SpoofRepository.resolveProfileForPackage(data, "com.game", 144f)
        )
        assertEquals(
            data.profiles.first { it.id == "pixel" }.profile,
            SpoofRepository.resolveProfileForPackage(data, "com.game", 90f)
        )
        // A package with neither is unassigned — null, not a guess.
        assertNull(SpoofRepository.resolveProfileForPackage(data, "com.other", 144f))
    }

    @Test
    fun resolveFallsBackToThePlainAssignmentWhenTheLadderIsEmpty() {
        val data = store(
            assignments = mapOf("com.game" to "single"),
            rateAssignments = mapOf("com.game" to emptyList()),
            "single"
        )
        // An empty ladder is no ladder.
        assertEquals(
            data.profiles.first { it.id == "single" }.profile,
            SpoofRepository.resolveProfileForPackage(data, "com.game", 144f)
        )
    }

    @Test
    fun resolveIgnoresRungsWhoseProfileIsGone() {
        val data = store(
            assignments = mapOf("com.game" to "single"),
            // "deleted" names no profile in the store; the surviving rung must still win.
            rateAssignments = mapOf("com.game" to ladderOf(rung("deleted", 165), rung("s26", 120))),
            "single", "s26"
        )
        assertEquals(
            data.profiles.first { it.id == "s26" }.profile,
            SpoofRepository.resolveProfileForPackage(data, "com.game", 144f)
        )
    }

    @Test
    fun resolveFallsThroughWhenEveryRungsProfileIsGone() {
        val data = store(
            assignments = mapOf("com.game" to "single"),
            rateAssignments = mapOf("com.game" to ladderOf(rung("deleted", 120))),
            "single"
        )
        assertEquals(
            data.profiles.first { it.id == "single" }.profile,
            SpoofRepository.resolveProfileForPackage(data, "com.game", 144f)
        )
    }

    // ── legacy store repair ─────────────────────────────────────────────────────────

    @Test
    fun aStoreSavedBeforeTheFieldExistedStillLoads() {
        // Gson bypasses the constructor, so the missing field deserializes as null despite the
        // non-null type. `repaired` is the one place that gets fixed.
        val legacyJson = """
            {"version":1,
             "profiles":[{"id":"p1","name":"P","profile":{}}],
             "assignments":{"com.game":"p1"},
             "globalProperties":{}}
        """.trimIndent()
        val parsed = Gson().fromJson(legacyJson, SpoofRepository.StoreData::class.java)!!
        val repaired = SpoofRepository.repaired(parsed)
        assertEquals(emptyMap<String, List<SpoofRepository.RateCandidate>>(), repaired.rateAssignments)
        assertEquals("p1", repaired.assignments["com.game"])
        // Already-repaired stores pass through unchanged.
        assertEquals(repaired, SpoofRepository.repaired(repaired))
    }

    @Test
    fun laddersSurviveAGsonRoundTrip() {
        val original = store(
            rateAssignments = mapOf(
                "com.game" to ladderOf(rung("a", 90), rung("a", 120), rung("b", 165))
            ),
            profileIds = arrayOf("a", "b")
        )
        val gson = Gson()
        val back = gson.fromJson(gson.toJson(original), SpoofRepository.StoreData::class.java)
        assertEquals(original.rateAssignments, back.rateAssignments)
    }
}
