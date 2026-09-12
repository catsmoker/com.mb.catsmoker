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
 * Keeps the community packs the user imported, newest first, capped at [MAX_PACKS].
 *
 * Same store shape as [WuwaDeployHistoryStore] (itself ported from the reference's
 * `DeployHistoryStore.kt` + `AtomicFile.kt`): Gson in `filesDir`, `synchronized` around every
 * mutation, insert-at-0 with a trim, a load that falls back to empty on a corrupt file, and a
 * save that lands in a temp sibling and is rename(2)'d over the store so a crash mid-write can
 * never leave it truncated.
 *
 * The pack *contents* live here, not the SAF grant that imported them: the import is a one-shot
 * walk of the picked folder (no persistable permission is taken), so a pack stays deployable
 * after the picker's grant is gone and the app never accumulates tree grants it no longer
 * needs. These are the user's own imports — nothing here is bundled or shipped with the app,
 * and the pack's README travels with it verbatim, disclaimer and all.
 */
class WuwaCommunityPackStore(private val storeFile: File) {

    constructor(context: Context) : this(File(context.filesDir, FILE_NAME))

    /** One imported pack, addressed by id. */
    data class StoredPack(
        val id: String,
        /** The picked folder's own name — the community pack's identity, not ours. */
        val name: String,
        val importedAt: Long,
        val readmes: Map<String, String>,
        val variants: List<WuwaCommunityPack.Variant>,
        val unknownFiles: List<String> = emptyList()
    ) {
        fun formattedImportedAt(): String =
            SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(importedAt))
    }

    private val gson = Gson()
    private val lock = Any()

    fun add(pack: StoredPack) {
        synchronized(lock) {
            val packs = loadLocked()
            packs.add(0, pack)
            while (packs.size > MAX_PACKS) packs.removeAt(packs.size - 1)
            saveLocked(packs)
        }
    }

    fun getPack(id: String): StoredPack? = synchronized(lock) { loadLocked().firstOrNull { it.id == id } }

    fun getAllPacks(): List<StoredPack> = synchronized(lock) { loadLocked().toList() }

    fun delete(id: String): Boolean {
        val removed = synchronized(lock) {
            val packs = loadLocked()
            val before = packs.size
            packs.removeAll { it.id == id }
            val did = packs.size < before
            if (did) saveLocked(packs)
            did
        }
        return removed
    }

    /** A corrupt or absent file is an empty list, not a crash — the next save replaces it. */
    private fun loadLocked(): MutableList<StoredPack> {
        if (!storeFile.exists()) return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<StoredPack>>() {}.type
            gson.fromJson<MutableList<StoredPack>>(storeFile.readText(), type) ?: mutableListOf()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    /** A failed save is swallowed, same choice as the history store: packs are not worth crashing an import over. */
    private fun saveLocked(packs: List<StoredPack>) {
        try {
            storeFile.writeAtomicLocked(gson.toJson(packs))
        } catch (_: Exception) {
        }
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
        private const val FILE_NAME = "wuwa_community_packs.json"

        /** Generous beside the history cap: a pack is bigger than a record, but still bounded. */
        const val MAX_PACKS = 10

        fun newId(): String = UUID.randomUUID().toString()
    }
}
