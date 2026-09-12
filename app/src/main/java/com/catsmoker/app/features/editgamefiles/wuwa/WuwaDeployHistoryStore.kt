package com.catsmoker.app.features.editgamefiles.wuwa

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Records every WuWa config deploy, newest first, capped at [MAX_RECORDS].
 *
 * Ported from `referance/gamingtools/WuWa-Config-Android-main/config/DeployHistoryStore.kt`
 * (read in full before this file was written) and its `util/AtomicFile.kt`: Gson in `filesDir`,
 * `synchronized` around every mutation, insert-at-0 with a trim, load-that-falls-back-to-empty
 * on a corrupt file, and a save that lands in a temp sibling and is rename(2)'d over the store
 * so a crash mid-write can never leave it truncated.
 *
 * The deliberate narrowing, same as [WuwaSmartBrain]'s: the reference's `updateOutcome` and
 * `compare` consume `LogInfo` parsed out of the game's Client.log — a baseline and an
 * after-the-fact FPS/thermal/OOM/drop reading, so it can print "deploy gained 12 FPS".
 * A record here carries what the deploy itself measured: per-file push/verify outcome, the
 * **on-device** md5 digest of each pushed file, whether the game was stopped, and the
 * hash-monitor result — all nullable where "not applicable" and "could not read" differ.
 * After-the-fact verification re-reads those digests from the device
 * (see [WuwaConfigManager.verifyRecord]) instead of pretending to a gameplay outcome. What
 * the reference's record had that this one leaves to the log pipeline: baseline/outcome FPS,
 * thermal, OOM, frame drops, and the client-log snippet — [WuwaLogParser]/[WuwaSmartBrain]/
 * [WuwaBenchmarkTuner] own those axes once a decrypted Client.log exists, and the history
 * stays out of their way rather than zero-filling them, per the house rule that a printed 0
 * the device never reported is worse than an absent field.
 */
class WuwaDeployHistoryStore(private val storeFile: File) {

    constructor(context: Context) : this(File(context.filesDir, FILE_NAME))

    /** What one deployed file did, and the digest the device held right after the push. */
    data class FileRecord(
        val name: String,
        val pushed: Boolean,
        val verified: Boolean,
        val backupTaken: Boolean,
        /** md5 as the device computed it (locally over the read-back bytes on SAF). "" = none recorded. */
        val md5: String = ""
    )

    /** One file's re-check against its recorded digest. */
    enum class Status { MATCH, CHANGED, MISSING, UNREADABLE, NOT_RECORDED }

    data class FileVerification(
        val name: String,
        val status: Status,
        /** Digest now held, refusal text, or why there is nothing to compare. */
        val detail: String
    )

    /** The result of one after-the-fact re-read, over whichever channel was available. */
    data class Verification(
        val timestamp: Long,
        /** The channel the re-read ran over — a digest read over ADB is not the same trust level as one over root. */
        val channelUsed: String,
        val files: List<FileVerification>
    )

    data class Record(
        val id: String,
        val timestamp: Long,
        /** Preset id ("balanced"…), or "restore" for a backup put back. */
        val preset: String,
        /** [WuwaConfigManager.Channel] name — stored as a string so old files survive an enum rename. */
        val channel: String,
        /** null = not attempted (SAF). Tri-state, exactly as the deploy reported it. */
        val gameStopped: Boolean? = null,
        val hashSynced: Boolean? = null,
        val hashDetail: String = "",
        val files: List<FileRecord> = emptyList(),
        val verification: Verification? = null
    ) {
        fun formattedTimestamp(): String =
            SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(timestamp))
    }

    private val gson = Gson()
    private val lock = Any()

    fun addRecord(record: Record) {
        synchronized(lock) {
            val records = loadLocked()
            records.add(0, record)
            while (records.size > MAX_RECORDS) records.removeAt(records.size - 1)
            saveLocked(records)
        }
    }

    fun getRecord(id: String): Record? = synchronized(lock) { loadLocked().firstOrNull { it.id == id } }

    fun getAllRecords(): List<Record> = synchronized(lock) { loadLocked().toList() }

    /**
     * Attaches (or replaces) a record's after-the-fact verification. The reference's
     * `updateOutcome` filled log-derived outcome fields the same way; this is the narrowed
     * equivalent over measured digests.
     *
     * @return the updated record, or null when the id no longer exists (trimmed out or cleared).
     */
    fun updateVerification(id: String, verification: Verification): Record? {
        synchronized(lock) {
            val records = loadLocked()
            val index = records.indexOfFirst { it.id == id }
            if (index < 0) return null
            val updated = records[index].copy(verification = verification)
            records[index] = updated
            saveLocked(records)
            return updated
        }
    }

    fun deleteRecord(id: String): Boolean {
        synchronized(lock) {
            val records = loadLocked()
            val before = records.size
            records.removeAll { it.id == id }
            val removed = records.size < before
            if (removed) saveLocked(records)
            return removed
        }
    }

    fun clear() {
        synchronized(lock) { saveLocked(mutableListOf()) }
    }

    /** A corrupt or absent file is an empty history, not a crash — the next save replaces it. */
    private fun loadLocked(): MutableList<Record> {
        if (!storeFile.exists()) return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<Record>>() {}.type
            gson.fromJson<MutableList<Record>>(storeFile.readText(), type) ?: mutableListOf()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    /** A failed save is swallowed, exactly as the reference chose: history is not worth crashing a deploy over. */
    private fun saveLocked(records: List<Record>) {
        try {
            storeFile.writeAtomicLocked(gson.toJson(records))
        } catch (_: Exception) {
        }
    }

    /**
     * The reference's `File.writeAtomic`: content lands in a temp sibling, then rename(2)'d
     * over the store. Readers see either the old file or the new one, never a truncated one.
     */
    private fun File.writeAtomicLocked(text: String) {
        val tmp = File(parentFile, "$name.tmp-${System.nanoTime()}")
        try {
            tmp.writeText(text)
            if (!tmp.renameTo(this)) {
                if (exists() && !delete()) throw IllegalStateException("Cannot replace $path")
                tmp.copyTo(this, overwrite = true)
                tmp.delete()
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    companion object {
        private const val FILE_NAME = "wuwa_deploy_history.json"

        /** The reference's cap: 20 records, oldest silently aging out. */
        const val MAX_RECORDS = 20

        /** Convenience for callers building records ([WuwaConfigManager.recordDeploy]). */
        fun newId(): String = UUID.randomUUID().toString()
    }
}
