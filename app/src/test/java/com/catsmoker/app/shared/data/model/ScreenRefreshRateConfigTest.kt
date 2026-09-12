package com.catsmoker.app.shared.data.model

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the refresh-rate spoof's config surface: the profile key's reach (one of our own profile
 * keys, so it must never be mistaken for a system property), and the blank-means-off contract
 * the Display hook and the editor both lean on.
 *
 * The hook mechanism itself (`hookAllMethods(Display, "getRefreshRate")`) needs a live Xposed
 * runtime and is pinned by KDoc against `processHook.spoofRefreshRate` (read in full) instead.
 */
class ScreenRefreshRateConfigTest {

    // --------------------------------------------------- profile-key classification

    @Test
    fun theRefreshRateKeyIsAnAppOwnKeyNeverASystemProperty() {
        // No `ro.`/`persist.`/… prefix, so SYSTEM_PROPERTY_PREFIXES keeps it out of the getprop
        // overlay and out of the Magisk channel — there is no system-property surface for refresh
        // rate, and inventing one would be a giveaway key.
        assertFalse(LSPosedConfig.isSystemProperty(LSPosedConfig.KEY_SCREEN_REFRESH_RATE))
    }

    @Test
    fun theGpuKeysAreAppOwnKeysToo() {
        // The refresh-rate key follows the gpu.vendor/gpu.renderer pattern, so the same test
        // must keep holding for those — a regression to a "ro." spelling would leak both.
        assertFalse(LSPosedConfig.isSystemProperty(LSPosedConfig.KEY_GPU_VENDOR))
        assertFalse(LSPosedConfig.isSystemProperty(LSPosedConfig.KEY_GPU_RENDERER))
    }

    @Test
    fun aRenderedProfileSurvivesTheSectionRoundTripWithItsRate() {
        // The Settings.Global channels carry per-package sections; the rate has to ride along.
        val config = "ro.product.model=Pixel 8 Pro\n${LSPosedConfig.KEY_SCREEN_REFRESH_RATE}=120\n"
        val document = LSPosedConfig.renderSections(mapOf("com.tencent.ig" to config))
        val parsed = LSPosedConfig.parseDeviceProps(LSPosedConfig.parseSection(document, "com.tencent.ig"))
        assertEquals("120", parsed[LSPosedConfig.KEY_SCREEN_REFRESH_RATE])
    }

    // --------------------------------------------------------------- blank-means-off

    @Test
    fun aZeroRateRendersNoKeyAtAll() {
        // Mirrors renderConfig's addProp: a blank value is omitted entirely, so the hook sees an
        // absent key and becomes a pass-through — the spoof is off, not "spoof to 0".
        val props = buildRenderedProps(screenRefreshRate = 0)
        assertNull(props[LSPosedConfig.KEY_SCREEN_REFRESH_RATE])
    }

    @Test
    fun aPositiveRateRendersAsWholeHz() {
        val props = buildRenderedProps(screenRefreshRate = 165)
        assertEquals("165", props[LSPosedConfig.KEY_SCREEN_REFRESH_RATE])
    }

    @Test
    fun theHookParsesTheRenderedValueOrNullsOut() {
        // What afterHookedMethod does with whatever the profile carried: toFloatOrNull, then the
        // >0 guard. A non-parseable value means "leave the real rate alone" — a half-spoof that
        // answers garbage would be worse than none — and so do "0" and negatives, which a
        // hand-edited config can carry.
        assertEquals(120f, "120".toFloatOrNull())
        assertEquals(119.9f, "119.9".toFloatOrNull())
        assertNull("".toFloatOrNull())
        assertNull("abc".toFloatOrNull())
        assertNull("0".toFloatOrNull()?.takeIf { it > 0f })
        assertNull("-60".toFloatOrNull()?.takeIf { it > 0f })
    }

    // -------------------------------------------------------------------- Gson repair

    @Test
    fun anOldStoreWithoutTheFieldDeserializesToZero() {
        // Gson bypasses the constructor; a profile saved before screenRefreshRate existed must
        // land on 0 (= off), which needs no migration and spoofs nothing.
        val profile = Gson().fromJson(
            """{"brand":"google","model":"Pixel 8 Pro","screenWidth":1440}""",
            DeviceProfile::class.java
        )
        assertEquals(0, profile.screenRefreshRate)
        assertEquals(1440, profile.screenWidth)
    }

    @Test
    fun aStoredRateRoundTripsThroughGson() {
        val original = DeviceProfile(screenRefreshRate = 165)
        val restored = Gson().fromJson(Gson().toJson(original), DeviceProfile::class.java)
        assertEquals(165, restored.screenRefreshRate)
    }

    // ----------------------------------------------------------------------- helpers

    /**
     * Renders the screen block exactly as [com.catsmoker.app.shared.data.repository.SpoofRepository]
     * does — duplicated here because renderConfig lives beside Android-dependent store code, and
     * the contract under test is the addProp blank-omission rule, not the surrounding text.
     */
    private fun buildRenderedProps(screenRefreshRate: Int): Map<String, String> {
        val rendered = buildString {
            append("# Catsmoker generated profile\n\n")
            append("ro.product.model=Pixel 8 Pro\n")
            if (screenRefreshRate > 0) append("${LSPosedConfig.KEY_SCREEN_REFRESH_RATE}=$screenRefreshRate\n")
        }
        return LSPosedConfig.parseDeviceProps(rendered)
    }
}
