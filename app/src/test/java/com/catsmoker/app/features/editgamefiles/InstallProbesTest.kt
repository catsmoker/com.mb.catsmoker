package com.catsmoker.app.features.editgamefiles

import com.catsmoker.app.shared.data.model.GameType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the picker's PUBG group-dot rule: any install = green; all five probed and none
 * installed = red; a missing probe anywhere = unknown, never a false red.
 */
class InstallProbesTest {

    private val variants = PUBG_VARIANTS

    @Test
    fun `any installed variant shows green regardless of missing probes`() {
        // One installed, the rest unprobed — still green; "unknown" must not outvote a yes.
        assertEquals(true, pubgGroupDot(mapOf(GameType.PUBG_KRJP to true)))
        assertEquals(true, pubgGroupDot(mapOf(GameType.PUBG_GLOBAL to true, GameType.BGMI to false)))
        assertEquals(true, pubgGroupDot(variants.associateWith { false } + (GameType.PUBG_VN to true)))
    }

    @Test
    fun `all probed and none installed shows red`() {
        assertEquals(false, pubgGroupDot(variants.associateWith { false }))
    }

    @Test
    fun `empty map is unknown, not red`() {
        assertEquals(null, pubgGroupDot(emptyMap()))
    }

    @Test
    fun `one missing probe among all-absent makes the dot unknown`() {
        // Four probed and absent, one never answered — the fifth might still be installed.
        val four = variants.dropLast(1).associateWith { false }
        assertEquals(null, pubgGroupDot(four))
    }
}
