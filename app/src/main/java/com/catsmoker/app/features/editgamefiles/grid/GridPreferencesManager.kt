package com.catsmoker.app.features.editgamefiles.grid

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.catsmoker.app.features.editgamefiles.ConfigBackupStore
import com.catsmoker.app.system.shell.ShellRunner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads and writes GRID Autosport's `preferences` file — the registry-XML graphics store
 * [GridPreferences] edits — over this app's two write channels, with the same
 * backup-then-write-then-read-back choreography every other game-file editor here uses.
 *
 * The feature's reason to exist is the reference README's own headline: *no root required*.
 * Unlike the HSR editor — whose settings hide in `/data/data` behind a root-only wall —
 * Feral games keep this file in `Android/data/…/files/feral_app_support/`, which Android 11+
 * exposes to the user through SAF. So the channel set here is deliberate:
 *
 * - **Root / Shizuku shell** ([ShellRunner]) — the same `cp` staging [HsrGameManager] and
 *   the WuWa deployer use; also the only channel that can `am force-stop` the game first.
 * - **SAF** — the no-root path itself. The user picks the game's `Android/data` folder once
 *   (the same persisted grant the custom-upload flow stores under the game's package name);
 *   the edit reads the current file through the provider, and writes it back through the
 *   same handle. What SAF *cannot* do is stop the game — the UI states that plainly instead
 *   of pretending, because a running GRID overwrites this file from memory on exit and
 *   would clobber the edit.
 *
 * Divergences from [HsrGameManager], all forced by the file rather than taste: this file is
 * shared state, not a per-package blob, so backups key on the package with the file's own
 * name; and verification is byte-level — the written file is read back and compared against
 * the exact bytes pushed, since a registry XML's binary blobs leave no room for a
 * "close enough" comparison.
 */
@Singleton
class GridPreferencesManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shellRunner: ShellRunner
) {

    private val backupStore by lazy { ConfigBackupStore(context) }

    private val safPrefs by lazy {
        context.getSharedPreferences("custom_upload_prefs", Context.MODE_PRIVATE)
    }

    private val absoluteDir: String get() = "/storage/emulated/0/${GridPreferences.RELATIVE_DIR}"
    private val absolutePath: String get() = "$absoluteDir/${GridPreferences.FILE_NAME}"

    // ------------------------------------------------------------------ channel gates

    fun canUseShell(): Boolean = shellRunner.hasPrivilege()

    /** The persisted SAF grant for the game's Android/data root, as the custom-upload flow stores it. */
    fun safTreeUri(): Uri? =
        safPrefs.getString("saf_tree_uri_${GridPreferences.PACKAGE}", null)?.toUri()

    fun hasSafGrant(): Boolean = safTreeUri() != null

    /** Whether any channel at all can reach the file — the UI's capability headline. */
    fun anyChannelAvailable(): Boolean = canUseShell() || hasSafGrant()

    /**
     * Persists the SAF grant the folder picker returned — the same store and key shape the
     * custom-upload flow uses, so a grant taken here also serves that flow and vice versa.
     * The ViewModel calls this instead of writing prefs itself; the manager owns its store.
     */
    fun onSafFolderPicked(uri: Uri): Boolean = try {
        context.contentResolver.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        safPrefs.edit { putString("saf_tree_uri_${GridPreferences.PACKAGE}", uri.toString()) }
        true
    } catch (_: Exception) {
        false
    }

    /** Whether any backup exists for this game — the restore button's gate. */
    fun hasBackup(): Boolean = backupStore.list(GridPreferences.PACKAGE).isNotEmpty()

    fun backups(): List<ConfigBackupStore.Entry> = backupStore.list(GridPreferences.PACKAGE)

    // ------------------------------------------------------------------ read

    sealed class ReadResult {
        data class Success(
            val read: GridPreferences.ReadResult,
            val xml: String,
            val channelUsed: String
        ) : ReadResult()

        data class Failure(val stage: Stage, val detail: String) : ReadResult()

        enum class Stage {
            /** No channel is available at all — explains the SAF pick in the UI. */
            NO_CHANNEL,
            /** A channel exists but the game's package is not installed at all. */
            GAME_NOT_INSTALLED,
            /** The game is installed but its folder or the file inside it is not — never run. */
            FILE_NOT_FOUND,
            /** The channel answered but the read failed. */
            READ_FAILED
        }
    }

    /**
     * Reads the file through the best available channel, in the order every channel here
     * agrees on: root/Shizuku shell first, then SAF. The
     * raw text is returned alongside the parsed [GridPreferences.ReadResult] so a later
     * edit applies to exactly the bytes that were read.
     *
     * The failure stages are separated the way HSR's read result is: before reporting
     * FILE_NOT_FOUND, an unprivileged package probe distinguishes "game not installed" from
     * "installed but never ran" — the two used to share one message, and "preference file not
     * found" under an uninstalled game was the exact confusion the File Engineering gate was
     * built to remove.
     */
    suspend fun readCurrent(): ReadResult = withContext(Dispatchers.IO) {
        if (canUseShell()) {
            val bytes = pullViaShell()
            if (bytes != null) return@withContext parseRead(bytes, "root / Shizuku shell")
        }
        if (hasSafGrant()) {
            val bytes = readViaSafBytes()
            if (bytes != null && bytes.isNotEmpty()) return@withContext parseRead(bytes, "SAF")
        }
        if (!canUseShell() && !hasSafGrant()) {
            return@withContext ReadResult.Failure(
                ReadResult.Stage.NO_CHANNEL,
                "No channel can reach the game's folder — grant root or Shizuku, or pick the game's Android/data folder for SAF."
            )
        }
        // Every channel had a look and none found the file. Whether the game itself is there
        // decides which fact to report: an uninstalled game says so plainly; an installed one
        // that never ran keeps the run-it-once message.
        val installed = runCatching {
            context.packageManager.getPackageInfo(GridPreferences.PACKAGE, 0)
        }.isSuccess
        if (!installed) {
            return@withContext ReadResult.Failure(
                ReadResult.Stage.GAME_NOT_INSTALLED,
                "${GridPreferences.PACKAGE} is not installed on this device."
            )
        }
        ReadResult.Failure(
            ReadResult.Stage.FILE_NOT_FOUND,
            "The preferences file was not found at feral_app_support/preferences — GRID Autosport is installed but has not written the file yet; run it once, then retry."
        )
    }

    private fun parseRead(bytes: ByteArray, channel: String): ReadResult.Success {
        val xml = bytes.toString(Charsets.UTF_8)
        return ReadResult.Success(GridPreferences.parse(xml), xml, channel)
    }

    // ------------------------------------------------------------------ write

    sealed class WriteResult {
        data class Success(
            val backup: ConfigBackupStore.Entry?,
            val changed: List<String>,
            val refused: List<String>,
            /** Read-back verdict: the written bytes were compared against what was pushed. */
            val verified: Boolean,
            val channelUsed: String,
            /** True when this channel could `am force-stop` the game before writing. */
            val gameStopped: Boolean?
        ) : WriteResult()

        data class Failure(val stage: Stage, val detail: String) : WriteResult()

        enum class Stage {
            NO_CHANNEL, FILE_NOT_FOUND, READ_FAILED, BACKUP_FAILED, WRITE_FAILED
        }
    }

    /**
     * Reads the file, applies [edits] through [GridPreferences.apply], backs up the current
     * bytes, and pushes the edited text back through the first channel that can — root/Shizuku,
     * then SAF. Every step that fails is named in the result rather than folded
     * into a generic failure, and the push is verified by reading the written file back.
     */
    suspend fun applyEdits(edits: GridPreferences.Edits): WriteResult = withContext(Dispatchers.IO) {
        // A fresh read, not the screen's cached copy: the game may have rewritten the file
        // since it was loaded, and an edit applied to a stale base would resurrect old values.
        val current = readCurrent()
        if (current is ReadResult.Failure) {
            return@withContext WriteResult.Failure(current.stage.toWriteStage(), current.detail)
        }
        val sourceXml = (current as ReadResult.Success).xml

        val applied = GridPreferences.apply(sourceXml, edits)
        if (applied.changed.isEmpty()) {
            return@withContext WriteResult.Failure(
                WriteResult.Stage.READ_FAILED,
                "nothing was edited — " + applied.refused.joinToString("; ")
            )
        }

        val newBytes = applied.xml.toByteArray(Charsets.UTF_8)

        // Shell channel: backup from the live file, stop the game, stage-and-cp, verify by md5.
        if (canUseShell()) {
            val backup = backupViaShell()
            if (backup === BACKUP_FAILED) {
                return@withContext WriteResult.Failure(
                    WriteResult.Stage.BACKUP_FAILED,
                    "could not store a backup of the current file — refusing to overwrite"
                )
            }
            val stop = shellRunner.execSafeResult("am", "force-stop", GridPreferences.PACKAGE)
            val outcome = pushViaShell(newBytes)
            if (outcome == null) {
                return@withContext WriteResult.Failure(
                    WriteResult.Stage.WRITE_FAILED,
                    "the shell refused the write — check the storage path"
                )
            }
            return@withContext WriteResult.Success(
                backup = backup as? ConfigBackupStore.Entry,
                changed = applied.changed,
                refused = applied.refused,
                verified = outcome,
                channelUsed = "root / Shizuku shell",
                gameStopped = stop.isSuccess
            )
        }

        // SAF: the no-root path. No force-stop exists here — stated, not hidden.
        val dir = safDataRoot()
            ?: return@withContext WriteResult.Failure(
                WriteResult.Stage.NO_CHANNEL,
                "the picked SAF folder is no longer accessible — pick the game's Android/data folder again"
            )
        val supportDir = dir.findFile("files")?.findFile("feral_app_support")
            ?: return@withContext WriteResult.Failure(
                WriteResult.Stage.FILE_NOT_FOUND,
                "files/feral_app_support was not found under the picked folder — pick the game's own Android/data folder"
            )
        val target = supportDir.findFile(GridPreferences.FILE_NAME)
            ?: return@withContext WriteResult.Failure(
                WriteResult.Stage.FILE_NOT_FOUND,
                "preferences was not found in feral_app_support — run the game once first"
            )
        val prior = runCatching {
            context.contentResolver.openInputStream(target.uri)?.use { it.readBytes() }
        }.getOrNull()
        val backup = prior?.takeIf { it.isNotEmpty() }
            ?.let { backupStore.save(GridPreferences.PACKAGE, GridPreferences.FILE_NAME, it) }
        if (prior != null && prior.isNotEmpty() && backup == null) {
            return@withContext WriteResult.Failure(
                WriteResult.Stage.BACKUP_FAILED,
                "could not store a backup of the current file — refusing to overwrite"
            )
        }
        val written = runCatching {
            context.contentResolver.openOutputStream(target.uri, "w")?.use { it.write(newBytes) }
        }.getOrNull()
        if (written == null) {
            return@withContext WriteResult.Failure(
                WriteResult.Stage.WRITE_FAILED,
                "the provider refused to open the file for writing — close the game and try again"
            )
        }
        val readBack = runCatching {
            context.contentResolver.openInputStream(target.uri)?.use { it.readBytes() }
        }.getOrNull()
        val verified = readBack?.contentEquals(newBytes) == true
        WriteResult.Success(
            backup = backup,
            changed = applied.changed,
            refused = applied.refused,
            verified = verified,
            channelUsed = "SAF",
            gameStopped = null
        )
    }

    /** Restores the newest backup through whichever channel can reach the file. Null = none exists. */
    suspend fun restoreLatestBackup(): WriteResult? = withContext(Dispatchers.IO) {
        val bytes = backupStore.list(GridPreferences.PACKAGE)
            .firstOrNull()?.let { backupStore.readBytes(it) } ?: return@withContext null
        pushBytes(bytes, "backup restored")
    }

    // ------------------------------------------------------------------ internals

    private fun ReadResult.Stage.toWriteStage(): WriteResult.Stage = when (this) {
        ReadResult.Stage.NO_CHANNEL -> WriteResult.Stage.NO_CHANNEL
        ReadResult.Stage.GAME_NOT_INSTALLED -> WriteResult.Stage.FILE_NOT_FOUND
        ReadResult.Stage.FILE_NOT_FOUND -> WriteResult.Stage.FILE_NOT_FOUND
        ReadResult.Stage.READ_FAILED -> WriteResult.Stage.READ_FAILED
    }

    /** Sentinel for a backup attempt that read the file but could not store it. */
    private val BACKUP_FAILED = Any()

    /**
     * Backs up the file through the shell channel. Returns the entry, null when there was
     * nothing to back up, or [BACKUP_FAILED] when a storeable file could not be stored.
     */
    private suspend fun backupViaShell(): Any? {
        val bytes = pullViaShell() ?: return null
        if (bytes.isEmpty()) return null
        return backupStore.save(GridPreferences.PACKAGE, GridPreferences.FILE_NAME, bytes)
            ?: BACKUP_FAILED
    }

    /**
     * The shell pull — `cp` to an app-owned temp and read in-process, byte-exact the way
     * WuwaConfigManager's ShellChannel pulls, because `cat` over the shell mangles nothing
     * here but costs nothing to be careful about either.
     */
    private suspend fun pullViaShell(): ByteArray? {
        val tempDir = context.externalCacheDir ?: context.cacheDir
        val temp = File.createTempFile("grid_pull_", null, tempDir)
        return try {
            temp.delete()
            val cp = shellRunner.execSafeResult("cp", "-f", absolutePath, temp.absolutePath)
            if (!cp.isSuccess || !temp.exists() || temp.length() == 0L) null
            else runCatching { temp.readBytes() }.getOrNull()
        } finally {
            temp.delete()
        }
    }

    private suspend fun pushViaShell(bytes: ByteArray): Boolean? {
        val tempDir = context.externalCacheDir ?: context.cacheDir
        val temp = File.createTempFile("grid_push_", null, tempDir)
        return try {
            temp.writeBytes(bytes)
            shellRunner.execSafeResult("mkdir", "-p", absoluteDir)
            val cp = shellRunner.execSafeResult("cp", "-f", temp.absolutePath, absolutePath)
            if (!cp.isSuccess) null
            else md5MatchesViaShell(temp)
        } finally {
            temp.delete()
        }
    }

    /** Read-back verification through the shell: the pushed md5 against the file's md5. */
    private suspend fun md5MatchesViaShell(source: File): Boolean {
        val local = computeMd5(source.readBytes())
        val remote = shellRunner.execSafeResult("md5sum", absolutePath)
            .stdout.trim().substringBefore(' ')
        return remote.isNotEmpty() && remote == local
    }

    private fun safDataRoot(): DocumentFile? = safTreeUri()?.let {
        runCatching { DocumentFile.fromTreeUri(context, it) }.getOrNull()
    }

    private fun computeMd5(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("MD5").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Reads the file through the persisted SAF grant. Returns the exact bytes, or null when
     * any link in the chain — grant, tree, directories, file, stream — is missing.
     */
    private fun readViaSafBytes(): ByteArray? {
        val dir = safDataRoot() ?: return null
        val supportDir = dir.findFile("files")?.findFile("feral_app_support") ?: return null
        val target = supportDir.findFile(GridPreferences.FILE_NAME) ?: return null
        return runCatching {
            context.contentResolver.openInputStream(target.uri)?.use { it.readBytes() }
        }.getOrNull()
    }

    /** Bytes up through root/Shizuku or SAF, for restores. */
    private suspend fun pushBytes(bytes: ByteArray, what: String): WriteResult {
        if (canUseShell()) {
            val outcome = pushViaShell(bytes)
            if (outcome != null) {
                return WriteResult.Success(
                    backup = null, changed = listOf(what), refused = emptyList(),
                    verified = outcome, channelUsed = "root / Shizuku shell", gameStopped = null
                )
            }
        }
        val dir = safDataRoot() ?: return WriteResult.Failure(
            WriteResult.Stage.NO_CHANNEL, "no channel can reach the game's folder"
        )
        val supportDir = dir.findFile("files")?.findFile("feral_app_support")
            ?: return WriteResult.Failure(WriteResult.Stage.FILE_NOT_FOUND, "feral_app_support not found")
        val target = supportDir.findFile(GridPreferences.FILE_NAME)
            ?: return WriteResult.Failure(WriteResult.Stage.FILE_NOT_FOUND, "preferences not found")
        return try {
            context.contentResolver.openOutputStream(target.uri, "w")?.use { it.write(bytes) }
            val readBack = context.contentResolver.openInputStream(target.uri)?.use { it.readBytes() }
            WriteResult.Success(
                backup = null, changed = listOf(what), refused = emptyList(),
                verified = readBack?.contentEquals(bytes) == true,
                channelUsed = "SAF", gameStopped = null
            )
        } catch (e: Exception) {
            WriteResult.Failure(WriteResult.Stage.WRITE_FAILED, e.message ?: "SAF write failed")
        }
    }
}
