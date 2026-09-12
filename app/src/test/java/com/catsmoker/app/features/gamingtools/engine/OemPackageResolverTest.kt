package com.catsmoker.app.features.gamingtools.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the OEM surface moved out of GamingEngine against
 * `referance/gamingtools/booster/.../gaming/OemPackageResolver.kt` and its
 * `EsportsOptimizationEngine.kt` (both read in full): the verbatim vivo suspend list and its
 * disjointness from this app's own load-bearing vivo lists, the four-key whitelist table, and
 * the CSV append rule.
 */
class OemPackageResolverTest {

    // --------------------------------------------------------------- suspend list

    @Test
    fun theVivoSuspendListMatchesTheReferenceVerbatim() {
        val expected = listOf(
            "com.vivo.appstore", "com.bbk.updater", "com.vivo.website", "com.vivo.cardstore",
            "com.vivo.assistant", "com.vivo.hiboard", "com.vivo.globalsearch", "com.vivo.magazine",
            "com.bbk.theme", "com.vivo.theme.effect", "com.vivo.video.floating",
            "com.vivo.weather", "com.vivo.weather.provider", "com.vivo.healthwidget",
            "com.vivo.stepcount", "com.vivo.exhealth", "com.bbk.cloud",
            "com.vivo.imanager", "com.vivo.safecenter", "com.vivo.xspace",
            "com.vivo.doubleinstance", "com.vivo.musicwidgetmix", "com.vivo.smartshot",
            "com.vivo.nps"
        )
        assertEquals(expected, OemPackageResolver.VIVO_SAFE_TO_SUSPEND)
    }

    @Test
    fun theSuspendListIsDisjointFromTheLoadBearingVivoLists() {
        // GamingEngine.systemCritical / gamingDaemons — the vivo processes the device actually
        // needs (power daemons, fingerprints, game cube itself). A package in both lists would
        // freeze something the phone depends on mid-session.
        val loadBearing = setOf(
            "com.vivo.pem", "com.vivo.abe", "com.vivo.daemonService", "com.vivo.sps",
            "com.vivo.pie", "com.vivo.fingerprintui", "com.vivo.fingerprint",
            "com.vivo.fingerprintvit", "com.vivo.faceui", "com.vivo.faceunlock",
            "com.vivo.systemuiplugin", "com.vivo.networkstate", "com.vivo.connbase",
            "com.android.systemui", "com.android.phone", "com.mediatek.ims",
            "com.vivo.gamecube", "com.vivo.gamewatch", "com.vivo.game",
            "com.iqoo.powersaving", "com.microsoft.deviceintegrationservice"
        )
        val overlap = OemPackageResolver.VIVO_SAFE_TO_SUSPEND intersect loadBearing
        assertTrue("suspend list must never touch the load-bearing set: $overlap", overlap.isEmpty())
    }

    @Test
    fun aNonVivoDeviceGetsNoSuspendPackages() {
        assertTrue(OemPackageResolver.packagesToSuspend(isVivoOrIqoo = false) { true }.isEmpty())
    }

    @Test
    fun aVivoDeviceGetsOnlyItsInstalledPackages() {
        val installed = setOf("com.vivo.appstore", "com.bbk.cloud", "com.vivo.nps")
        val result = OemPackageResolver.packagesToSuspend(isVivoOrIqoo = true) { it in installed }
        assertEquals(listOf("com.vivo.appstore", "com.bbk.cloud", "com.vivo.nps"), result)
    }

    @Test
    fun aVivoDeviceWithNothingPreinstalledSuspendsNothing() {
        assertTrue(OemPackageResolver.packagesToSuspend(isVivoOrIqoo = true) { false }.isEmpty())
    }

    // --------------------------------------------------------------- whitelist keys

    @Test
    fun theWhitelistTableCarriesAllFourReferenceKeys() {
        assertEquals(
            listOf(
                "game_cube_apps",
                "speed_mode_apps",
                "vivo_high_refresh_rate_apps",
                "vivo_screen_refresh_rate_apps_list"
            ),
            OemPackageResolver.VIVO_GAME_WHITELIST_KEYS
        )
    }

    // --------------------------------------------------------------- CSV append

    @Test
    fun appendAddsThePackageToAnEmptyList() {
        assertEquals("com.game.one", OemPackageResolver.appendPackage("", "com.game.one"))
        assertEquals("com.game.one", OemPackageResolver.appendPackage("null", "com.game.one"))
    }

    @Test
    fun appendJoinsAnExistingListWithoutReordering() {
        assertEquals("a,b,com.game.one", OemPackageResolver.appendPackage("a, b", "com.game.one"))
    }

    @Test
    fun appendIsIdempotent() {
        assertEquals("a,b", OemPackageResolver.appendPackage("a,b", "b"))
        assertEquals("a,b", OemPackageResolver.appendPackage("a,b", "b"))
    }

    @Test
    fun appendTrimsWhitespaceItemsAndDropsEmpties() {
        assertEquals("a,b,c", OemPackageResolver.appendPackage(" a , ,b ,", "c"))
    }

    @Test
    fun membershipIsExactNotASubstring() {
        // "b" is not a member of "abc" even though it is a substring of it.
        assertEquals("abc,b", OemPackageResolver.appendPackage("abc", "b"))
        assertFalse(OemPackageResolver.appendPackage("abc", "b") == "abc")
    }
}
