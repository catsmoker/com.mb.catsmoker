package com.catsmoker.app.features.editgamefiles

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Timestamped backups of the game config files this screen overwrites, kept in the app's own
 * storage so restoring never needs a second privileged write to make the backup in the first
 * place.
 *
 * From the references' safety-backup systems — `referance/gamingtools/hsrgraphicdroid-main`
 * ("Safety Backup System", snapshot before every write) and
 * `referance/gamingtools/WuWa-Config-Android-main/config/BackupStore.kt` (timestamped files,
 * newest-first listing, a retention cap). Both agree on the part that matters here: the backup
 * is taken *before* the overwrite, from the bytes the device actually had — not from the asset
 * this app was about to push, which would make "restore" a second copy of the thing being
 * reverted.
 *
 * Every overwrite channel in [EditGameFilesViewModel] hands this store the current file's
 * bytes it already pulled for its own prefer-existing logic, so the backup costs one extra
 * local write, not an extra privileged round-trip. A channel that cannot read the current file
 * backs up nothing rather than backing up the wrong thing — an unread config means the restore
 * path could not have put it back anyway.
 */
class ConfigBackupStore(context: Context) {

    /** One saved config file. [timestamp] is when the backup was taken, not the file's own mtime. */
    data class Entry(val file: File, val timestamp: Long, val sizeBytes: Long) {
        /** Human-readable "5 Sep 2026, 14:03" for the restore dialog. */
        fun formattedTimestamp(): String =
            SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(timestamp))

        fun formattedSize(): String = when {
            sizeBytes >= 1024 -> "${sizeBytes / 1024} KB"
            else -> "$sizeBytes B"
        }
    }

    private val rootDir = File(context.filesDir, "config_backups")

    /**
     * Saves the bytes the game's config file held immediately before an overwrite.
     *
     * @return the entry saved, or null when the bytes could not be written — a failed backup is
     *   reported to the caller and never silently treated as "no backup needed".
     */
    fun save(packageName: String, saveFile: String, bytes: ByteArray): Entry? {
        if (bytes.isEmpty()) return null
        return try {
            val dir = File(rootDir, packageName)
            if (!dir.exists() && !dir.mkdirs()) return null
            // The timestamp is both the name and the sort key, so "newest first" survives the
            // directory listing without a stat per file.
            val timestamp = System.currentTimeMillis()
            val file = File(dir, "${timestamp}_${saveFile.replace('/', '_')}")
            file.writeBytes(bytes)
            prune(dir)
            Entry(file, timestamp, bytes.size.toLong())
        } catch (_: Exception) {
            null
        }
    }

    /** The saved backups for one game, newest first. */
    fun list(packageName: String): List<Entry> {
        val dir = File(rootDir, packageName)
        val files = dir.listFiles() ?: return emptyList()
        return files
            .filter { it.isFile }
            .mapNotNull { f ->
                val timestamp = f.name.substringBefore('_').toLongOrNull() ?: return@mapNotNull null
                Entry(f, timestamp, f.length())
            }
            .sortedByDescending { it.timestamp }
    }

    fun readBytes(entry: Entry): ByteArray? = runCatching { entry.file.readBytes() }.getOrNull()

    fun delete(entry: Entry): Boolean = runCatching { entry.file.delete() }.getOrDefault(false)

    /**
     * Keeps the newest [MAX_PER_GAME] backups per game. Without a cap, an unnoticed crash loop
     * in the game plus an auto-apply would grow this directory forever; with one, the oldest
     * state silently ages out, which is the lesser surprise and what both references chose.
     */
    private fun prune(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        if (files.size <= MAX_PER_GAME) return
        files.sortedBy { it.name }.take(files.size - MAX_PER_GAME).forEach { it.delete() }
    }

    private companion object {
        private const val MAX_PER_GAME = 10
    }
}
