package com.catsmoker.app.features.editgamefiles.wuwa

import android.content.Context
import com.google.gson.Gson
import java.io.File
import java.util.UUID

/**
 * 12-hour cache of the last Convene fetch, so the pity screen survives process death and a
 * flight-mode relaunch still shows the last real read.
 *
 * Ported from `referance/gamingtools/WuWa-Config-Android-main/config/GachaHistoryStore.kt`
 * (read in full before this file was written) and its `util/AtomicFile.kt`: one JSON file in
 * `filesDir`, a TTL checked at load (an expired or corrupt file is deleted and reported as
 * absent, never surfaced as data), an entry that keeps a small summary alongside the full
 * `GachaData` Gson dump, `synchronized` around every mutation, and a save that lands in a
 * temp sibling and is rename(2)'d over the store so a crash mid-write cannot truncate it —
 * the same write shape [WuwaDeployHistoryStore] uses.
 */
class WuwaGachaHistoryStore(private val storeFile: File) {

    constructor(context: Context) : this(File(context.filesDir, FILE_NAME))

    data class Entry(
        val id: String,
        val expiresAt: Long,
        val totalPulls: Int,
        val fiveStars: Int,
        /** Gson of [WuwaGacha.GachaData] — the restore path, kept out of the summary fields. */
        val fullDataJson: String,
    )

    private val gson = Gson()
    private val lock = Any()

    /**
     * The cached entry, or null when there is none, it has expired, or the file is corrupt.
     * Expiry and corruption delete the file: a stale read-out must not survive to be shown.
     */
    fun load(): Entry? {
        if (!storeFile.exists()) return null
        return synchronized(lock) {
            try {
                val entry = gson.fromJson(storeFile.readText(), Entry::class.java)
                if (entry != null && System.currentTimeMillis() >= entry.expiresAt) {
                    storeFile.delete()
                    null
                } else {
                    entry
                }
            } catch (_: Exception) {
                storeFile.delete()
                null
            }
        }
    }

    /** The cached [WuwaGacha.GachaData], or null when there is nothing restorable. */
    fun loadData(): WuwaGacha.GachaData? {
        val entry = load() ?: return null
        return try {
            gson.fromJson(entry.fullDataJson, WuwaGacha.GachaData::class.java)
        } catch (_: Exception) {
            null
        }
    }

    fun save(data: WuwaGacha.GachaData) {
        val now = System.currentTimeMillis()
        val entry = Entry(
            id = UUID.randomUUID().toString().take(8),
            expiresAt = now + TTL_HOURS * 60 * 60 * 1000,
            totalPulls = data.totalPulls,
            fiveStars = data.fiveStars,
            fullDataJson = gson.toJson(data),
        )
        synchronized(lock) {
            try {
                storeFile.writeAtomicLocked(gson.toJson(entry))
            } catch (_: Exception) {
            }
        }
    }

    fun delete() {
        synchronized(lock) { storeFile.delete() }
    }

    /** Hours until the cache expires, rounded down, floored at 0 — for the UI's "cached, N h left". */
    fun remainingHours(now: Long = System.currentTimeMillis()): Long {
        val entry = load() ?: return 0
        return maxOf((entry.expiresAt - now) / (60 * 60 * 1000), 0L)
    }

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
        private const val FILE_NAME = "wuwa_gacha_history.json"

        /** The reference's TTL: the record id in the URL expires fast, so a stale cache lies. */
        const val TTL_HOURS = 12L
    }
}
