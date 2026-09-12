package com.catsmoker.app.features.gamingtools.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the dexopt sweep's package classification against
 * `referance/gamingtools/art/.../data/util/PackageClassifier.kt` (read in full): the overlay/RRO
 * signals — name markers, install paths, the definitive `overlaytarget=` — and the shape of the
 * classification when no dump is available.
 */
class BoosterPackageClassifierTest {

    // --------------------------------------------------------------- name signal

    @Test
    fun overlayNamePatternsAreClassifiedOverlayLike() {
        // The reference's markers, verbatim: auto_generated_rro, .rro, rro_, overlay, bare rro.
        assertTrue(BoosterPackageClassifier.isOverlayLike("com.vendor.auto_generated_rro_product.app", null))
        assertTrue(BoosterPackageClassifier.isOverlayLike("com.vendor.theme.rro", null))
        assertTrue(BoosterPackageClassifier.isOverlayLike("com.vendor.rro_something", null))
        assertTrue(BoosterPackageClassifier.isOverlayLike("com.vendor.overlay.night", null))
        assertTrue(BoosterPackageClassifier.isOverlayLike("com.vendor.ScreenRro", null))
        // Case-insensitive, as in the reference (the whole name is lowercased first).
        assertTrue(BoosterPackageClassifier.isOverlayLike("COM.VENDOR.OVERLAY", null))
    }

    @Test
    fun ordinaryPackageNamesAreNotOverlayLikeByName() {
        assertFalse(BoosterPackageClassifier.isOverlayLike("com.example.game", null))
        assertFalse(BoosterPackageClassifier.isOverlayLike("org.mozilla.firefox", null))
    }

    // --------------------------------------------------------------- APK-path signal (this app's channel)

    @Test
    fun anApkUnderAnOverlayInstallPathIsOverlayLike() {
        // This app's cheap channel: the ApplicationInfo's own sourceDir. The reference reads the
        // same directories out of a dump; here the APK path is tested as a prefix directly.
        for (path in listOf(
            "/product/overlay/FrameworkResOverlay.apk",
            "/system/overlay/SomeOverlay.apk",
            "/vendor/overlay/VendorOverlay.apk",
            "/odm/overlay/OdmOverlay.apk",
            "/overlay/AccentColorOverlay.apk"
        )) {
            assertTrue("$path", BoosterPackageClassifier.isOverlayLike("com.example.quiet", path))
        }
    }

    @Test
    fun anApkInTheNormalInstallPathsIsNotOverlayLike() {
        assertFalse(BoosterPackageClassifier.isOverlayLike("com.example.game", "/data/app/com.example.game/base.apk"))
        assertFalse(BoosterPackageClassifier.isOverlayLike("com.android.chrome", "/product/app/Chrome/Chrome.apk"))
        // A path that merely contains an overlay directory below other segments is not the
        // install-path signal — the reference anchors on the dump's codepath= value, which is the
        // APK's own directory; a prefix test keeps that anchoring here.
        assertFalse(BoosterPackageClassifier.isOverlayLike("com.example.game", "/data/app/overlay/com.example.game/base.apk"))
    }

    // --------------------------------------------------------------- dump signals

    @Test
    fun anOverlayTargetMarkerInTheDumpIsDefinitive() {
        val dump = """
            Package [com.example.quiet] (1234):
              codePath=/data/app/com.example.quiet
              overlayTarget=com.android.systemui
        """.trimIndent()
        // Even a perfectly ordinary name and path lose to the definitive marker.
        assertTrue(BoosterPackageClassifier.isOverlayLike("com.example.quiet", "/data/app/com.example.quiet/base.apk", dump))
    }

    @Test
    fun overlayPathsInTheDumpClassifyOverlayLike() {
        val dump = """
            Package [com.example.quiet] (1234):
              codePath=/product/overlay/FrameworkResOverlay.apk
              resourcePath=/product/overlay/FrameworkResOverlay.apk
        """.trimIndent()
        assertTrue(BoosterPackageClassifier.isOverlayLike("com.example.quiet", null, dump))
    }

    @Test
    fun aDumpWithNeitherSignalLeavesTheNameSignalInPlay() {
        // The reference ORs the name fallback in even when a dump exists — an RRO with an
        // informative name but an odd install location is still caught.
        val dump = """
            Package [com.vendor.rro_quiet] (1234):
              codePath=/data/app/com.vendor.rro_quiet
        """.trimIndent()
        assertTrue(BoosterPackageClassifier.isOverlayLike("com.vendor.rro_quiet", "/data/app/com.vendor.rro_quiet/base.apk", dump))
    }

    @Test
    fun anUninformativeDumpWithACleanNameAndPathClassifiesNotOverlay() {
        val dump = """
            Package [com.example.game] (1234):
              codePath=/data/app/com.example.game
              status=speed-profile
        """.trimIndent()
        assertFalse(BoosterPackageClassifier.isOverlayLike("com.example.game", "/data/app/com.example.game/base.apk", dump))
    }

    // --------------------------------------------------------------- robustness

    @Test
    fun nullPathAndNullDumpFallBackToTheNameAlone() {
        assertFalse(BoosterPackageClassifier.isOverlayLike("com.example.game", null, null))
        assertTrue(BoosterPackageClassifier.isOverlayLike("com.vendor.overlaything", null, null))
    }

    @Test
    fun aBlankDumpCountsAsNoDump() {
        // The reference treats blank dump output as "no dump available" rather than evidence.
        assertFalse(BoosterPackageClassifier.isOverlayLike("com.example.game", null, "   "))
    }
}
