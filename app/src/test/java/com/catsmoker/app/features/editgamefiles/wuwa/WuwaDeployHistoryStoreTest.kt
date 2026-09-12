package com.catsmoker.app.features.editgamefiles.wuwa

import com.catsmoker.app.features.editgamefiles.wuwa.WuwaDeployHistoryStore.FileRecord
import com.catsmoker.app.features.editgamefiles.wuwa.WuwaDeployHistoryStore.FileVerification
import com.catsmoker.app.features.editgamefiles.wuwa.WuwaDeployHistoryStore.Record
import com.catsmoker.app.features.editgamefiles.wuwa.WuwaDeployHistoryStore.Status
import com.catsmoker.app.features.editgamefiles.wuwa.WuwaDeployHistoryStore.Verification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins the deploy-history store against
 * `referance/gamingtools/WuWa-Config-Android-main/config/DeployHistoryStore.kt` + its
 * `util/AtomicFile.kt`, both read in full before the port was written: the newest-20 cap, the
 * insert-at-0 ordering, load-falls-back-to-empty on a corrupt file, the swallowed failed save,
 * and the temp-sibling-then-rename save that never leaves a truncated store.
 */
class WuwaDeployHistoryStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun newStore(): WuwaDeployHistoryStore =
        WuwaDeployHistoryStore(folder.newFile("wuwa_deploy_history.json"))

    private fun record(id: String, timestamp: Long = System.currentTimeMillis()): Record = Record(
        id = id,
        timestamp = timestamp,
        preset = "balanced",
        channel = "ROOT_OR_SHIZUKU",
        gameStopped = true,
        hashSynced = true,
        hashDetail = "hash monitor synced and verified",
        files = listOf(
            FileRecord("Engine.ini", pushed = true, verified = true, backupTaken = true, md5 = "0123456789abcdef0123456789abcdef"),
            FileRecord("Scalability.ini", pushed = true, verified = true, backupTaken = false, md5 = "fedcba9876543210fedcba9876543210")
        )
    )

    @Test
    fun recordsRoundTripThroughGson() {
        val store = newStore()
        val original = record("r1")
        store.addRecord(original)

        // A fresh instance reads from disk — the fields that survive the round trip are the
        // ones a later verify relies on: names, digests, and the tri-states.
        val reloaded = WuwaDeployHistoryStore(storeFileOf(store)).getAllRecords()
        assertEquals(listOf(original), reloaded)
        // verification is absent from the JSON until one is attached — null, not a crash.
        assertNull(reloaded.single().verification)
    }

    @Test
    fun newestRecordIsFirstAndTheCapKeepsTwenty() {
        val store = newStore()
        repeat(25) { i -> store.addRecord(record("r$i", timestamp = 1_000_000L + i)) }
        val all = store.getAllRecords()
        assertEquals(WuwaDeployHistoryStore.MAX_RECORDS, all.size)
        // Newest first, oldest aged out — the reference's trim, silently and on purpose.
        assertEquals("r24", all.first().id)
        assertEquals("r5", all.last().id)
    }

    @Test
    fun aCorruptStoreFileLoadsAsEmptyAndSurvivesTheNextWrite() {
        val file = folder.newFile("wuwa_deploy_history.json")
        file.writeText("not json at all {{{")
        val store = WuwaDeployHistoryStore(file)
        assertTrue(store.getAllRecords().isEmpty())

        store.addRecord(record("after-corruption"))
        assertEquals(1, WuwaDeployHistoryStore(file).getAllRecords().size)
    }

    @Test
    fun updateVerificationAttachesAndThenReplaces() {
        val store = newStore()
        store.addRecord(record("r1"))

        val first = Verification(
            timestamp = 100L, channelUsed = "root / Shizuku shell",
            files = listOf(FileVerification("Engine.ini", Status.MATCH, "0123456789abcdef0123456789abcdef"))
        )
        val updated = store.updateVerification("r1", first)
        assertNotNull(updated)
        assertEquals(first, updated?.verification)
        assertEquals(first, store.getRecord("r1")?.verification)

        // A second check replaces the first — the history shows the latest re-read, not a log of them.
        val second = Verification(
            timestamp = 200L, channelUsed = "wireless ADB",
            files = listOf(FileVerification("Engine.ini", Status.CHANGED, "now aaa (was bbb)"))
        )
        assertEquals(second, store.updateVerification("r1", second)?.verification)
        assertEquals(second, WuwaDeployHistoryStore(storeFileOf(store)).getRecord("r1")?.verification)

        // An id that no longer exists (trimmed or cleared) is null, not an exception.
        assertNull(store.updateVerification("gone", second))
    }

    @Test
    fun deleteRemovesOnlyTheNamedRecordAndClearEmptiesTheStore() {
        val store = newStore()
        store.addRecord(record("r1"))
        store.addRecord(record("r2"))

        assertTrue(store.deleteRecord("r1"))
        assertFalse(store.deleteRecord("r1")) // already gone — false, not a crash
        assertEquals(listOf("r2"), store.getAllRecords().map { it.id })

        store.clear()
        assertTrue(store.getAllRecords().isEmpty())
        // clear persists: a reload also sees an empty store.
        assertTrue(WuwaDeployHistoryStore(storeFileOf(store)).getAllRecords().isEmpty())
    }

    @Test
    fun savingNeverLeavesATempSiblingBehind() {
        val dir = folder.newFolder()
        val file = dir.resolve("wuwa_deploy_history.json")
        val store = WuwaDeployHistoryStore(file)
        repeat(5) { i -> store.addRecord(record("r$i")) }

        // The atomic write renames its temp away; only the store itself may remain.
        val names = dir.listFiles()!!.map { it.name }
        assertEquals(listOf("wuwa_deploy_history.json"), names)
    }

    /** The store keeps its file private; the test needs the path to build a second reader. */
    private fun storeFileOf(store: WuwaDeployHistoryStore): java.io.File {
        // The constructor's file is not exposed, so find it by name in the temp root — the
        // TemporaryFolder root is the only directory this test writes into.
        return folder.root.listFiles()!!.first { it.isFile && it.name == "wuwa_deploy_history.json" }
    }
}
