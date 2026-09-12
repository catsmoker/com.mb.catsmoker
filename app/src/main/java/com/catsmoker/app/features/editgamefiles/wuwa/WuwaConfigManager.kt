package com.catsmoker.app.features.editgamefiles.wuwa

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.catsmoker.app.features.editgamefiles.ConfigBackupStore
import com.catsmoker.app.system.shell.ShellRunner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deploys generated WuWa config files to the game's UE4 config directory over the app's two
 * write channels, and keeps the game's own tamper monitor in sync.
 *
 * Choreography is the house pattern from [com.catsmoker.app.features.editgamefiles.hsr.HsrGameManager]
 * crossed with `referance/gamingtools/WuWa-Config-Android-main/config/ConfigManager.kt` +
 * `config/HashMonitor.kt`, all read in full before this file was written:
 *
 *  - the game is force-stopped first (a running game rewrites its configs from memory on
 *    exit, which would race the deploy);
 *  - every file the deploy overwrites is backed up first via [ConfigBackupStore], from the
 *    bytes the device actually held — and a failed backup is reported, never silently
 *    treated as "nothing to back up";
 *  - each push is verified by comparing md5 digests **computed on the device** — byte-exact,
 *    unlike comparing shell `cat` output, which cannot be relied on to preserve trailing
 *    bytes;
 *  - after the push, `KuroConfigMonitor.hash` — the per-ini MD5 + ModifyCount file the game
 *    keeps over its own configs — is refreshed through the reference's own algorithm
 *    (snapshot before, reconcile after: the ModifyCount is incremented only when the game
 *    did *not* touch the hash file concurrently), atomically via a `.new` temp + `mv`, with
 *    read-back verification. Exactly as the reference does, hash sync is **skipped on the
 *    SAF channel** — it needs a shell `mv` — and the skip is reported rather than hidden.
 *
 * The Root/Shizuku channel runs the full backup → push → verify → hash-refresh choreography
 * over [ShellRunner] (root/Shizuku `cp`). SAF stays separate: it has no shell at all, so
 * nothing above applies to it.
 */
@Singleton
class WuwaConfigManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shellRunner: ShellRunner
) {

    private val backupStore by lazy { ConfigBackupStore(context) }
    private val safPrefs by lazy { context.getSharedPreferences("custom_upload_prefs", Context.MODE_PRIVATE) }

    enum class Channel { ROOT_OR_SHIZUKU, SAF }

    /** What one file's deploy actually did. */
    data class FileDeployResult(
        val name: String,
        /** null = a backup entry was not applicable (no prior file on the device). */
        val backup: ConfigBackupStore.Entry?,
        /** A prior file existed but the backup write failed — reported, not swallowed. */
        val backupFailed: Boolean,
        val pushed: Boolean,
        val verified: Boolean,
        val detail: String,
        /**
         * The digest the device held right after the push — computed by `md5sum` on the device
         * (shell) or locally over the read-back bytes (SAF). "" when nothing was pushed.
         * Recorded by the deploy history so a later verify can say "changed since deploy" as a
         * measured fact rather than a guess.
         */
        val md5: String = ""
    )

    data class DeploySuccess(
        val channel: Channel,
        /** null = the stop was not attempted (SAF channel has no privilege to stop the game). */
        val gameStopped: Boolean?,
        val files: List<FileDeployResult>,
        /** null = hash sync not applicable (SAF channel — needs shell `mv`). */
        val hashSynced: Boolean?,
        val hashDetail: String
    )

    sealed class DeployResult {
        data class Success(val success: DeploySuccess) : DeployResult()
        data class Failure(val stage: String, val detail: String) : DeployResult()
    }

    /**
     * One verified write. `pushed` alone means the bytes reached the device but read-back
     * disagreed; only `pushed && verified` is a good deploy.
     */
    data class WriteOutcome(val pushed: Boolean, val verified: Boolean, val detail: String)

    /**
     * The privileged file operations the shell channel needs. [ShellChannel] is
     * libsu/Shizuku `cp`. Read the file, run a command, write bytes — nothing else.
     */
    private interface DeviceChannel {
        suspend fun run(vararg args: String): ShellRunner.ExecResult

        /** The file's exact bytes, or null when it cannot be read (which includes "absent"). */
        suspend fun readBytes(path: String): ByteArray?

        suspend fun writeBytes(path: String, bytes: ByteArray): WriteOutcome
    }

    private val tempDir: File get() = context.externalCacheDir ?: context.cacheDir

    private inner class ShellChannel : DeviceChannel {
        override suspend fun run(vararg args: String): ShellRunner.ExecResult =
            shellRunner.execSafeResult(*args)

        override suspend fun readBytes(path: String): ByteArray? {
            // cp to an app-owned temp, then read in-process — byte-exact, unlike cat output.
            // null = unreadable or absent; an empty-but-present file comes back as an empty
            // array so callers can tell "no prior file" apart from "prior file, zero bytes".
            val temp = File.createTempFile("wuwa_pull_", null, tempDir)
            return try {
                temp.delete()
                val cp = shellRunner.execSafeResult("cp", "-f", path, temp.absolutePath)
                if (!cp.isSuccess || !temp.exists()) {
                    null
                } else {
                    runCatching { temp.readBytes() }.getOrNull()
                }
            } finally {
                temp.delete()
            }
        }

        override suspend fun writeBytes(path: String, bytes: ByteArray): WriteOutcome {
            val temp = File.createTempFile("wuwa_push_", null, tempDir)
            return try {
                temp.writeBytes(bytes)
                val cp = shellRunner.execSafeResult("cp", "-f", temp.absolutePath, path)
                when {
                    !cp.isSuccess -> WriteOutcome(
                        pushed = false, verified = false,
                        detail = enrichScopedStorageError(cp.text.ifBlank { "cp exited ${cp.exitCode}" })
                    )
                    else -> {
                        val verified = verifyPushedBytes(temp.absolutePath, path)
                        WriteOutcome(
                            pushed = true, verified = verified,
                            detail = if (verified) "pushed, md5 matches"
                            else "pushed but NOT verified — read-back disagrees"
                        )
                    }
                }
            } finally {
                temp.delete()
            }
        }
    }

    /**
     * The 5 ini files the game's own monitor watches — the deploy list and the hash-refresh
     * list are the same list, from `GamePaths.MONITORED_FILES` in the reference.
     */
    val monitoredFiles = listOf(
        "Engine.ini", "DeviceProfiles.ini", "GameUserSettings.ini", "Scalability.ini", "Hardware.ini"
    )

    fun backups(): List<ConfigBackupStore.Entry> = backupStore.list(PACKAGE)

    /**
     * The `Engine.ini` currently on the device, when it can be read — its `[Core.System]`
     * paths are reused verbatim by the generator, because that list tracks the game's
     * installed content plugins and a stale copy could break content resolution.
     *
     * Read through the byte-exact channel (never `cat` into a String): the paths block is
     * ASCII, but the read shares the deploy's contract — what the device held, not what a
     * shell printout preserved.
     */
    suspend fun readExistingEngineIni(): String? {
        if (canDeployViaShell()) {
            val bytes = ShellChannel().readBytes("$CONFIG_DIR/Engine.ini")
            if (bytes != null && bytes.isNotEmpty()) {
                val text = bytes.toString(Charsets.UTF_8)
                if (text.isNotBlank()) return text
            }
        }
        return null
    }

    /** Root needs no ceremony; Shizuku must be alive and granted. Requests permission when pending. */
    fun canDeployViaShell(): Boolean {
        if (shellRunner.isRootAvailable()) return true
        return try {
            rikka.shizuku.Shizuku.pingBinder() && run {
                if (rikka.shizuku.Shizuku.checkSelfPermission() ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    true
                } else {
                    rikka.shizuku.Shizuku.requestPermission(0)
                    false
                }
            }
        } catch (_: Throwable) {
            false
        }
    }

    // ------------------------------------------------------------------- SAF channel

    fun hasSafTreeUri(): Boolean = safTreeUri() != null

    /** SAF target directory, walking (and creating) the config-dir segments under the picked tree. */
    private fun safConfigDir(): DocumentFile? = safDir(CONFIG_REL, createMissing = true)

    /**
     * Walks `rel`'s segments under the picked SAF tree. [createMissing] is true only for the
     * config dir a deploy writes into; the log dir is walked read-only — creating game
     * directories the game itself never made would be side-effecting a read.
     */
    private fun safDir(rel: String, createMissing: Boolean): DocumentFile? {
        val treeUri = safTreeUri() ?: return null
        val base = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        var current = base
        for (segment in rel.split('/').filter { it.isNotBlank() }) {
            current = current.findFile(segment)
                ?: (if (createMissing) current.createDirectory(segment) else null)
                ?: return null
        }
        return current
    }

    private fun safTreeUri(): Uri? =
        safPrefs.getString(SAF_PREFS_KEY, null)?.toUri()

    /** Persists the SAF grant the folder picker returned; the pending action then re-runs. */
    fun onSafFolderPicked(uri: Uri): Boolean = try {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        safPrefs.edit { putString(SAF_PREFS_KEY, uri.toString()) }
        true
    } catch (_: Exception) {
        false
    }

    // ---------------------------------------------------------------- community packs

    private val communityPackStore by lazy { WuwaCommunityPackStore(context) }

    sealed class PackImportResult {
        data class Read(val pack: WuwaCommunityPackStore.StoredPack) : PackImportResult()
        data class Failure(val detail: String) : PackImportResult()
    }

    fun communityPacks(): List<WuwaCommunityPackStore.StoredPack> = communityPackStore.getAllPacks()

    fun deleteCommunityPack(id: String): Boolean = communityPackStore.delete(id)

    /**
     * Imports a community pack folder the user just picked — a one-shot recursive walk of the
     * tree, no persistable permission taken (the pack's contents land in the store; the grant is
     * left to die with the picker). Depth is bounded because a pack is two levels of shape
     * (root README + variant folders), not an arbitrary tree; anything deeper is refused with
     * its path rather than silently ignored.
     *
     * The walk itself never decides what a pack is — it hands every file it finds to
     * [WuwaCommunityPack.parse], which owns the variant/README/unknown rules. A folder with no
     * known ini anywhere is a refusal ("nothing deployable"), and a single unreadable file is a
     * refusal too: the grant covers the whole tree, so a read failure means something real
     * happened, and importing a pack with a silent hole in it would deploy half a config.
     */
    fun importCommunityPack(uri: Uri): PackImportResult {
        val root = try {
            DocumentFile.fromTreeUri(context, uri)
        } catch (_: Exception) {
            null
        } ?: return PackImportResult.Failure("That folder could not be opened as a document tree.")
        val name = root.name ?: "community pack"
        val files = mutableListOf<WuwaCommunityPack.PackFile>()
        val walkFailure = walkPackFolder(root, "", 0, files)
        if (walkFailure != null) return PackImportResult.Failure(walkFailure)
        if (files.isEmpty()) return PackImportResult.Failure("That folder is empty.")
        val parsed = WuwaCommunityPack.parse(files)
        if (!parsed.isDeployable) {
            return PackImportResult.Failure(
                "No config file found — a pack needs at least one of " +
                    WuwaCommunityPack.KNOWN_INI_NAMES.joinToString(", ") + " somewhere in the folder."
            )
        }
        val pack = WuwaCommunityPackStore.StoredPack(
            id = WuwaCommunityPackStore.newId(),
            name = name,
            importedAt = System.currentTimeMillis(),
            readmes = parsed.readmes,
            variants = parsed.variants,
            unknownFiles = parsed.unknownFiles
        )
        communityPackStore.add(pack)
        return PackImportResult.Read(pack)
    }

    /**
     * Collects every file under `folder`, building `/`-joined paths relative to the pack root.
     * Returns the first failure's path, or null when the walk completed. Symlink-shaped loops
     * cannot occur in a document tree, so depth alone bounds the recursion.
     */
    private fun walkPackFolder(
        folder: DocumentFile,
        prefix: String,
        depth: Int,
        out: MutableList<WuwaCommunityPack.PackFile>
    ): String? {
        if (depth > MAX_PACK_WALK_DEPTH) return "${prefix.ifEmpty { "(pack root)" }} is nested too deep — a pack is a root plus variant folders."
        val entries = try {
            folder.listFiles()
        } catch (_: Exception) {
            null
        } ?: return "${prefix.ifEmpty { "(pack root)" }} could not be listed."
        for (entry in entries) {
            val path = if (prefix.isEmpty()) entry.name ?: "(unnamed)" else "$prefix/${entry.name ?: "(unnamed)"}"
            if (entry.isDirectory) {
                val failure = walkPackFolder(entry, path, depth + 1, out)
                if (failure != null) return failure
            } else {
                val content = try {
                    context.contentResolver.openInputStream(entry.uri)?.use { stream ->
                        stream.readBytes().toString(Charsets.UTF_8)
                    }
                } catch (_: Exception) {
                    null
                } ?: return "$path could not be read."
                out.add(WuwaCommunityPack.PackFile(relativePath = path, content = content))
            }
        }
        return null
    }

    // ---------------------------------------------------------------- deploy

    suspend fun deployViaShell(configs: Map<String, String>): DeployResult = withContext(Dispatchers.IO) {
        if (!canDeployViaShell()) {
            return@withContext DeployResult.Failure("channel", "Neither root nor Shizuku is available — use SAF instead.")
        }
        deployOver(ShellChannel(), Channel.ROOT_OR_SHIZUKU, configs)
    }

    /**
     * The root/Shizuku choreography. SAF cannot use it: it has no `am force-stop`,
     * no `mkdir -p`, no `mv` — see [deployViaSaf].
     */
    private suspend fun deployOver(
        channel: DeviceChannel,
        channelKind: Channel,
        configs: Map<String, String>
    ): DeployResult {
        val mkdir = channel.run("mkdir", "-p", CONFIG_DIR)
        if (!mkdir.isSuccess) {
            return DeployResult.Failure("mkdir", mkdir.text.ifBlank { "mkdir exited ${mkdir.exitCode}" })
        }

        // A running game rewrites its configs from memory on exit — stop it first, and report
        // the refusal if the stop is refused (the deploy continues; the report names it).
        val stop = channel.run("am", "force-stop", PACKAGE)

        // Snapshot the game's own hash monitor before touching anything: after the deploy,
        // a changed snapshot means the game wrote concurrently and ModifyCount must NOT be
        // incremented (the reference's reconcile rule).
        val hashSnapshot = readFileViaChannel(channel, HASH_MONITOR_PATH)

        val results = mutableListOf<FileDeployResult>()
        for ((name, content) in configs) {
            val destPath = "$CONFIG_DIR/$name"
            // Backup the bytes the device actually holds — only when there is a file to
            // read; an empty pull means no prior file, not a failed backup.
            val priorBytes = channel.readBytes(destPath)
            var backup: ConfigBackupStore.Entry? = null
            var backupFailed = false
            if (priorBytes != null && priorBytes.isNotEmpty()) {
                backup = backupStore.save(PACKAGE, name, priorBytes)
                backupFailed = backup == null
            }
            val outcome = channel.writeBytes(destPath, content.toByteArray())
            val digest = if (outcome.pushed) md5OfFile(channel, destPath) else ""
            results.add(
                FileDeployResult(
                    name, backup, backupFailed,
                    pushed = outcome.pushed, verified = outcome.verified, detail = outcome.detail,
                    md5 = digest
                )
            )
        }

        if (results.none { it.pushed }) {
            return DeployResult.Failure("push", results.firstOrNull()?.detail ?: "no files were written")
        }

        val hashOutcome = refreshConfigHashes(channel, incrementModifyCount = gameTouchedHashFile(channel, hashSnapshot) == false)
        return DeployResult.Success(
            DeploySuccess(
                channel = channelKind,
                gameStopped = stop.isSuccess,
                files = results,
                hashSynced = hashOutcome.first,
                hashDetail = hashOutcome.second
            )
        )
    }

    suspend fun deployViaSaf(configs: Map<String, String>): DeployResult = withContext(Dispatchers.IO) {
        val dir = safConfigDir()
            ?: return@withContext DeployResult.Failure(
                "saf-folder",
                "No SAF folder picked for ${PACKAGE.substringAfterLast('.')} yet — pick the game's Android data folder."
            )

        val results = mutableListOf<FileDeployResult>()
        for ((name, content) in configs) {
            try {
                val existing = dir.findFile(name)
                // Backup before delete-and-recreate, which would otherwise leave the old
                // generation unreachable through the provider.
                var backup: ConfigBackupStore.Entry? = null
                var backupFailed = false
                if (existing != null) {
                    val bytes = runCatching {
                        context.contentResolver.openInputStream(existing.uri)?.use { it.readBytes() }
                    }.getOrNull()
                    if (bytes != null && bytes.isNotEmpty()) {
                        backup = backupStore.save(PACKAGE, name, bytes)
                        backupFailed = backup == null
                    }
                }
                existing?.delete()
                val target = dir.createFile(MIME_BINARY, name)
                    ?: run {
                        results.add(FileDeployResult(name, backup, backupFailed, pushed = false, verified = false,
                            detail = "the provider refused to create the file"))
                        continue
                    }
                val bytes = content.toByteArray()
                context.contentResolver.openOutputStream(target.uri, "w")?.use { it.write(bytes) }
                    ?: run {
                        results.add(FileDeployResult(name, backup, backupFailed, pushed = false, verified = false,
                            detail = "the provider refused to open the file for writing"))
                        continue
                    }
                val verified = runCatching {
                    context.contentResolver.openInputStream(target.uri)?.use { it.readBytes() }
                }.getOrNull()?.contentEquals(bytes) == true
                results.add(
                    FileDeployResult(
                        name, backup, backupFailed, pushed = true, verified = verified,
                        detail = if (verified) "pushed, read-back matches" else "pushed but NOT verified — read-back disagrees",
                        // Read-back confirmed the device holds exactly `bytes`, so their local
                        // md5 is the device's digest. Unverified: record nothing rather than a
                        // digest we cannot vouch for.
                        md5 = if (verified) computeMd5(bytes) else ""
                    )
                )
            } catch (e: Exception) {
                results.add(FileDeployResult(name, null, false, pushed = false, verified = false, detail = e.message ?: "SAF write failed"))
            }
        }

        if (results.none { it.pushed }) {
            return@withContext DeployResult.Failure("push", results.firstOrNull()?.detail ?: "no files were written")
        }

        // The reference's own explicit skip: the hash monitor refresh needs a shell `mv`,
        // which SAF does not have. Reported, not hidden.
        DeployResult.Success(
            DeploySuccess(
                channel = Channel.SAF,
                gameStopped = null,
                files = results,
                hashSynced = null,
                hashDetail = "skipped on SAF — needs shell mv (the reference skips it here too)"
            )
        )
    }

    /**
     * Puts a backup entry back, backup-first like every other write. Prefers root/Shizuku;
     * falls back to the SAF channel when shell is unavailable but a folder grant exists —
     * a SAF-channel user with backups must not be told "neither root nor Shizuku" on restore
     * when a SAF write path exists. Only when neither channel is available is that refusal
     * reported.
     */
    suspend fun restoreBackup(entry: ConfigBackupStore.Entry): DeployResult = withContext(Dispatchers.IO) {
        val bytes = runCatching { backupStore.readBytes(entry) }.getOrNull()
        if (bytes == null) {
            return@withContext DeployResult.Failure("backup", "that backup's file is unreadable")
        }
        val name = entry.file.name.substringAfter('_') // "<timestamp>_<file name>"
        if (name.isBlank() || '/' in name || name !in monitoredFiles) {
            return@withContext DeployResult.Failure("backup", "that backup's file name is not a known config file")
        }
        val configs = mapOf(name to bytes.toString(Charsets.UTF_8))
        if (canDeployViaShell()) {
            deployViaShell(configs)
        } else {
            deployViaSaf(configs)
        }
    }

    // --------------------------------------------------------------- deploy history
    //
    // Ported from the reference's DeployHistoryStore usage: every successful deploy (and every
    // restore — it overwrites the same files) leaves a record. The reference then compares a
    // log-derived outcome against a log-derived baseline; this port verifies what it can
    // measure — the on-device digests recorded at deploy time — and leaves the log-only FPS /
    // thermal / OOM / drop axes out rather than inventing them (see WuwaDeployHistoryStore's
    // KDoc).

    private val historyStore by lazy { WuwaDeployHistoryStore(context) }

    fun history(): List<WuwaDeployHistoryStore.Record> = historyStore.getAllRecords()

    /** Builds and stores one record of what the deploy actually did; returns it for the UI. */
    fun recordDeploy(preset: String, success: DeploySuccess): WuwaDeployHistoryStore.Record {
        val record = WuwaDeployHistoryStore.Record(
            id = WuwaDeployHistoryStore.newId(),
            timestamp = System.currentTimeMillis(),
            preset = preset,
            channel = success.channel.name,
            gameStopped = success.gameStopped,
            hashSynced = success.hashSynced,
            hashDetail = success.hashDetail,
            files = success.files.map {
                WuwaDeployHistoryStore.FileRecord(
                    name = it.name,
                    pushed = it.pushed,
                    verified = it.verified,
                    backupTaken = it.backup != null,
                    md5 = it.md5
                )
            }
        )
        historyStore.addRecord(record)
        return record
    }

    fun deleteHistoryRecord(id: String) = historyStore.deleteRecord(id)

    fun clearHistory() = historyStore.clear()

    // ------------------------------------------------------------------- game log
    //
    // The game's own Client.log — the only source for the axes no device read can supply
    // (thermal events, GPU OOMs, actual FPS vs the cap). It is XOR-obfuscated, so it must be
    // read **byte-exactly**: a shell `cat` into a String mangles the bytes the decryptor keys
    // on. Every channel here hands back the raw bytes; decryption is [WuwaLogDecryptor]'s job.

    /** What reading Client.log actually did. */
    sealed class ClientLogResult {
        /** The log's exact bytes, and which channel read them. */
        data class Read(val bytes: ByteArray, val channelUsed: String) : ClientLogResult()

        /**
         * Named refusals, kept distinct: no channel at all, the log absent (the game writes it
         * only after a play session), and present-but-unreadable are three different facts.
         */
        data class Failure(val detail: String) : ClientLogResult()
    }

    /**
     * Reads the encrypted `Client.log` byte-exactly, preferring root/Shizuku, then
     * the SAF provider (which reads in-process — byte-exact by nature).
     */
    suspend fun readClientLog(): ClientLogResult = withContext(Dispatchers.IO) {
        if (canDeployViaShell()) {
            val bytes = ShellChannel().readBytes(LOG_PATH)
            if (bytes != null && bytes.isNotEmpty()) {
                return@withContext ClientLogResult.Read(bytes, "root / Shizuku shell")
            }
        }
        val safRead = readLogViaSaf()
        if (safRead != null) return@withContext safRead
        ClientLogResult.Failure(
            "Client.log could not be read — it is written only after a play session, and no root/Shizuku " +
                "or SAF folder is available to reach it right now."
        )
    }

    private fun readLogViaSaf(): ClientLogResult.Read? {
        // Read-only walk: the game made these directories, and a read must not add to them.
        val dir = safDir(LOGS_REL, createMissing = false) ?: return null
        val log = runCatching { dir.findFile(LOG_FILE_NAME) }.getOrNull() ?: return null
        val bytes = runCatching {
            context.contentResolver.openInputStream(log.uri)?.use { it.readBytes() }
        }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        return ClientLogResult.Read(bytes, "SAF")
    }

    // ------------------------------------------------------------------- installed profile
    //
    // The identity half of the reference's ProfileExtractor.readProfile: the game's own
    // LocalStorage/DeviceStorage databases (pulled through this manager's byte-exact channels,
    // not the reference's base64-over-stdout) plus a settings count over each deployed ini.

    sealed class InstalledProfileResult {
        data class Read(val profile: WuwaProfileExtractor.InstalledProfile) : InstalledProfileResult()
        data class Failure(val detail: String) : InstalledProfileResult()
    }

    /**
     * Reads everything the installed game can say about itself: the log-derived device facts
     * (when Client.log exists — it is written only after a play session), the two databases'
     * player fields, and a settings count over each monitored ini.
     *
     * The halves fail independently and say so: a missing log leaves [InstalledProfile.log] null
     * but the database fields still read; unreadable databases leave [InstalledProfile.dbChannel]
     * null but the log half still parsed; only a total failure — no channel, and nothing read —
     * is a [InstalledProfileResult.Failure].
     */
    suspend fun readInstalledProfile(): InstalledProfileResult = withContext(Dispatchers.IO) {
        // ── Log half ──
        var logInfo: WuwaLogParser.LogInfo? = null
        var logChannel: String? = null
        when (val read = readClientLog()) {
            is ClientLogResult.Read -> {
                val (text, _) = WuwaLogDecryptor.decodeLogBytes(read.bytes)
                logInfo = WuwaLogParser.parseLog(text)
                logChannel = read.channelUsed
            }
            is ClientLogResult.Failure -> Unit // the log is optional; its absence is not a failure
        }

        // ── Database half ──
        val localBytes = readGameFileBytes("files/UE4Game/Client/Client/Saved/LocalStorage/LocalStorage.db")
        val deviceBytes = readGameFileBytes("files/UE4Game/Client/Client/Saved/DeviceSaved/DeviceStorage.db")
        var dbChannel: String? = null
        var uid: String? = null
        var serverLevels: List<Pair<String, Int>> = emptyList()
        var lastLoginTime: String? = null
        var towerFloor: Int? = null
        var weeklyRogueScore: Int? = null
        var battlePassPurchased: Boolean? = null
        var loopTowerSeason: Int? = null
        var gameVersion: String? = null
        var patchVersion: String? = null
        var launcherVersion: String? = null
        var language: String? = null
        if (localBytes != null || deviceBytes != null) {
            dbChannel = fileChannelLabel()
            val localDb = openPulledDb(localBytes, "profile_LocalStorage.db")
            val devDb = openPulledDb(deviceBytes, "profile_DeviceStorage.db")
            try {
                uid = queryDb(localDb, "RecentlyLoginUID")?.filter { it.isDigit() }
                val uidStr = uid ?: ""
                serverLevels = WuwaProfileExtractor.parseServerLevels(queryDb(localDb, "SdkLevelData"))
                lastLoginTime = WuwaProfileExtractor.formatTimestamp(
                    WuwaProfileExtractor.cleanString(queryDb(localDb, "LoginTime_$uidStr"))
                )
                towerFloor = queryDb(localDb, "AdventrueTower_$uidStr")?.toIntOrNull()
                weeklyRogueScore = queryDb(localDb, "AdventrueWeeklyRogue_$uidStr")?.toIntOrNull()
                battlePassPurchased =
                    queryDb(localDb, "BattlePassPayButton_$uidStr")?.contains("1B") == true
                loopTowerSeason = queryDb(localDb, "LoopTowerSeason_$uidStr")?.toIntOrNull()
                gameVersion = WuwaProfileExtractor.cleanString(queryDb(devDb, "Version_Resource"))
                patchVersion = WuwaProfileExtractor.cleanString(queryDb(devDb, "PatchVersion"))
                launcherVersion = WuwaProfileExtractor.cleanString(queryDb(devDb, "Version_Launcher"))
                language = WuwaProfileExtractor.languageName(
                    WuwaProfileExtractor.cleanString(queryDb(devDb, "UseLanguage_en"))
                )
            } finally {
                localDb?.close()
                devDb?.close()
                File(context.cacheDir, "profile_LocalStorage.db").delete()
                File(context.cacheDir, "profile_DeviceStorage.db").delete()
            }
        }

        // ── Ini settings counts ──
        val iniCounts = linkedMapOf<String, Int>()
        for (name in monitoredFiles) {
            val content = readGameFileText("$CONFIG_REL/$name")
            iniCounts[name] = WuwaProfileExtractor.countIniSettings(content)
        }

        if (logInfo == null && dbChannel == null && iniCounts.values.all { it == 0 }) {
            return@withContext InstalledProfileResult.Failure(
                "Nothing could be read — the databases need root, Shizuku, or a picked " +
                    "SAF folder, and Client.log is written only after a play session."
            )
        }
        InstalledProfileResult.Read(
            WuwaProfileExtractor.InstalledProfile(
                uid = uid,
                server = serverLevels.firstOrNull()?.first,
                playerLevel = serverLevels.firstOrNull()?.second,
                serverLevels = serverLevels,
                lastLoginTime = lastLoginTime,
                towerFloor = towerFloor,
                weeklyRogueScore = weeklyRogueScore,
                battlePassPurchased = battlePassPurchased,
                loopTowerSeason = loopTowerSeason,
                gameVersion = gameVersion,
                patchVersion = patchVersion,
                launcherVersion = launcherVersion,
                language = language,
                iniSettingCounts = iniCounts,
                log = logInfo,
                dbChannel = dbChannel,
                logChannel = logChannel
            )
        )
    }

    /** The exact bytes of a file under the game's data root, over root/Shizuku or SAF. */
    private suspend fun readGameFileBytes(rel: String): ByteArray? {
        if (canDeployViaShell()) {
            ShellChannel().readBytes("$GAME_DATA_ROOT/$rel")?.let { if (it.isNotEmpty()) return it }
        }
        // SAF: read-only walk — the game made these directories; a read must not add to them.
        val file = safDir(rel.substringBeforeLast('/'), createMissing = false)
            ?.let { dir -> runCatching { dir.findFile(rel.substringAfterLast('/')) }.getOrNull() }
            ?: return null
        return runCatching {
            context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /** A text file under the game's data root as text, or "" — the ini counts treat "" as zero. */
    private suspend fun readGameFileText(rel: String): String {
        // Byte-exact like every other read here: `cat` stdout cannot be trusted to preserve
        // trailing bytes, and this text feeds the profile's ini counts.
        if (canDeployViaShell()) {
            val bytes = ShellChannel().readBytes("$GAME_DATA_ROOT/$rel")
            if (bytes != null && bytes.isNotEmpty()) {
                val text = bytes.toString(Charsets.UTF_8)
                if (text.isNotBlank()) return text
            }
        }
        return readGameFileBytes(rel)?.toString(Charsets.UTF_8) ?: ""
    }

    private fun fileChannelLabel(): String = when {
        canDeployViaShell() -> "root / Shizuku shell"
        else -> "SAF"
    }

    /** Opens a pulled database read-only from a cache copy; null when absent or not a database. */
    private fun openPulledDb(bytes: ByteArray?, cacheName: String): android.database.sqlite.SQLiteDatabase? {
        if (bytes == null) return null
        return try {
            val file = File(context.cacheDir, cacheName)
            file.writeBytes(bytes)
            android.database.sqlite.SQLiteDatabase.openDatabase(
                file.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            )
        } catch (_: Exception) {
            // Not a database, corrupt, or an unreadable copy — one null field set, not a failure
            // of the whole profile.
            null
        }
    }

    private fun queryDb(db: android.database.sqlite.SQLiteDatabase?, key: String): String? {
        if (db == null) return null
        return try {
            db.rawQuery("SELECT value FROM LocalStorage WHERE key=?", arrayOf(key)).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }


    /**
     * Re-reads every recorded file's md5 from the device and compares it with the digest the
     * deploy recorded — the narrowed equivalent of the reference's after-the-fact outcome read.
     * Prefers root/Shizuku, then SAF (which reads through the provider and hashes
     * locally — byte-exact in-process, same as its deploy-time digest).
     *
     * @return the updated record with the new [WuwaDeployHistoryStore.Verification] attached,
     *   or null when no channel is available to verify with — reported, never faked.
     */
    suspend fun verifyRecord(record: WuwaDeployHistoryStore.Record): WuwaDeployHistoryStore.Record? =
        withContext(Dispatchers.IO) {
            val verification = when {
                canDeployViaShell() -> verifyViaDeviceChannel(ShellChannel(), record, "root / Shizuku shell")
                else -> verifyViaSaf(record)
            } ?: return@withContext null
            historyStore.updateVerification(record.id, verification)
        }

    private suspend fun verifyViaDeviceChannel(
        channel: DeviceChannel,
        record: WuwaDeployHistoryStore.Record,
        channelLabel: String
    ): WuwaDeployHistoryStore.Verification {
        val files = record.files.map { f ->
            if (f.md5.isBlank()) {
                WuwaDeployHistoryStore.FileVerification(
                    f.name, WuwaDeployHistoryStore.Status.NOT_RECORDED,
                    "the deploy recorded no md5 for this file (nothing pushed, or push unverified)"
                )
            } else {
                val r = channel.run("md5sum", "$CONFIG_DIR/${f.name}")
                if (r.isSuccess) {
                    val digest = r.stdout.trim().split(Regex("\\s+")).firstOrNull() ?: ""
                    if (digest.length != 32) {
                        WuwaDeployHistoryStore.FileVerification(
                            f.name, WuwaDeployHistoryStore.Status.UNREADABLE, r.text.ifBlank { "md5sum output unparseable" }
                        )
                    } else if (digest == f.md5) {
                        WuwaDeployHistoryStore.FileVerification(f.name, WuwaDeployHistoryStore.Status.MATCH, digest)
                    } else {
                        WuwaDeployHistoryStore.FileVerification(
                            f.name, WuwaDeployHistoryStore.Status.CHANGED, "now $digest (was ${f.md5})"
                        )
                    }
                } else if (r.text.contains("No such file", ignoreCase = true)) {
                    // A missing file is a fact about the game directory, not a read failure.
                    WuwaDeployHistoryStore.FileVerification(f.name, WuwaDeployHistoryStore.Status.MISSING, r.text)
                } else {
                    WuwaDeployHistoryStore.FileVerification(f.name, WuwaDeployHistoryStore.Status.UNREADABLE, r.text.ifBlank { "md5sum exited ${r.exitCode}" })
                }
            }
        }
        return WuwaDeployHistoryStore.Verification(System.currentTimeMillis(), channelLabel, files)
    }

    private fun verifyViaSaf(record: WuwaDeployHistoryStore.Record): WuwaDeployHistoryStore.Verification? {
        val dir = safConfigDir() ?: return null
        val files = record.files.map { f ->
            when {
                f.md5.isBlank() -> WuwaDeployHistoryStore.FileVerification(
                    f.name, WuwaDeployHistoryStore.Status.NOT_RECORDED,
                    "the deploy recorded no md5 for this file (nothing pushed, or push unverified)"
                )
                else -> {
                    val target = runCatching { dir.findFile(f.name) }.getOrNull()
                    when {
                        target == null -> WuwaDeployHistoryStore.FileVerification(
                            f.name, WuwaDeployHistoryStore.Status.MISSING, "not found under the SAF folder"
                        )
                        else -> {
                            val bytes = runCatching {
                                context.contentResolver.openInputStream(target.uri)?.use { it.readBytes() }
                            }.getOrNull()
                            when {
                                bytes == null -> WuwaDeployHistoryStore.FileVerification(
                                    f.name, WuwaDeployHistoryStore.Status.UNREADABLE, "the provider refused to open the file"
                                )
                                else -> {
                                    val digest = computeMd5(bytes)
                                    if (digest == f.md5) {
                                        WuwaDeployHistoryStore.FileVerification(f.name, WuwaDeployHistoryStore.Status.MATCH, digest)
                                    } else {
                                        WuwaDeployHistoryStore.FileVerification(
                                            f.name, WuwaDeployHistoryStore.Status.CHANGED, "now $digest (was ${f.md5})"
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return WuwaDeployHistoryStore.Verification(System.currentTimeMillis(), "SAF", files)
    }

    /** md5 the pushed temp and the destination *on the device* and compare the digests. */
    private suspend fun verifyPushedBytes(tempPath: String, destPath: String): Boolean {
        val r = shellRunner.execSafeResult("md5sum", tempPath, destPath)
        if (!r.isSuccess) return false
        val digests = r.stdout.lines().mapNotNull { it.trim().split(Regex("\\s+")).firstOrNull() }
        return digests.size >= 2 && digests[0] == digests[1]
    }

    private suspend fun readFileViaChannel(channel: DeviceChannel, path: String): String {
        val r = channel.run("cat", path)
        return if (r.isSuccess) r.stdout else ""
    }

    /** True only when the game rewrote the hash file between snapshot and now. */
    private suspend fun gameTouchedHashFile(channel: DeviceChannel, snapshot: String): Boolean =
        readFileViaChannel(channel, HASH_MONITOR_PATH) != snapshot

    /**
     * Scoped storage on some ROMs refuses shell writes under `Android/data` even for root
     * and shell uids — surface that as the actionable instruction rather than a bare
     * "Permission denied" (the reference's `enrichScopedStorageError`).
     */
    private fun enrichScopedStorageError(text: String): String =
        if (text.contains("Permission denied", ignoreCase = true)) {
            "$text — this ROM blocks shell writes to Android data; use SAF instead"
        } else {
            text
        }

    private fun computeMd5(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("MD5").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    // ------------------------------------------------------- hash monitor refresh
    //
    // Ported from the reference's HashMonitor.kt (read in full before writing): parse the
    // existing per-ini sections, recompute each Hash, keep the game's own ModifyCount (capped
    // at 8) and LastModifiedTime, increment only when the game did not write concurrently,
    // write atomically through a `.new` temp + mv, and verify by read-back.

    private suspend fun refreshConfigHashes(channel: DeviceChannel, incrementModifyCount: Boolean): Pair<Boolean, String> {
        // A deploy and a concurrent edit both stage to the same device path; serialize so the
        // second cannot clobber the first's temp.
        return hashMutex.withLock {
            try {
                val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val existing = readFileViaChannel(channel, HASH_MONITOR_PATH)
                val existingLines = existing.lines().toMutableList()
                val hasExistingContent = existingLines.any { it.trim().startsWith("[") }

                val updates = mutableMapOf<String, Map<String, String>>()
                for (name in monitoredFiles) {
                    val hash = md5OfFile(channel, "$CONFIG_DIR/$name")
                    var prevCount: Int? = null
                    var prevTime = ""
                    var inSection = false
                    for (line in existingLines) {
                        val t = line.trim()
                        if (t.equals("[$name]", ignoreCase = true)) {
                            inSection = true
                            continue
                        }
                        if (inSection && HASH_SECTION_REGEX.containsMatchIn(t)) break
                        if (inSection && t.startsWith("ModifyCount=")) {
                            prevCount = t.removePrefix("ModifyCount=").toIntOrNull()
                        }
                        if (inSection && t.startsWith("LastModifiedTime=")) {
                            prevTime = t.removePrefix("LastModifiedTime=").trim()
                        }
                    }
                    val baseCount = prevCount?.coerceIn(0, 8) ?: 0
                    val displayCount = if (incrementModifyCount) minOf(baseCount + 1, 8) else baseCount
                    updates[name] = mapOf(
                        "Hash" to hash,
                        "ModifyCount" to displayCount.toString(),
                        "LastModifiedTime" to (prevTime.ifBlank { now })
                    )
                }

                val patched = mutableListOf<String>()
                if (hasExistingContent) {
                    var currentSection = ""
                    val seenKeys = mutableSetOf<String>()
                    for (line in existingLines) {
                        val trimmed = line.trim()
                        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                            val sectionName = trimmed.removePrefix("[").removeSuffix("]")
                            flushSection(patched, updates, currentSection, seenKeys)
                            currentSection = sectionName
                            seenKeys.clear()
                            patched.add(line)
                        } else if (updates.containsKey(currentSection)) {
                            val eq = trimmed.indexOf('=')
                            if (eq > 0) {
                                val key = trimmed.substring(0, eq).trim()
                                val replacement = updates[currentSection]?.get(key)
                                if (replacement != null) {
                                    if (seenKeys.add("$key=$replacement")) patched.add("$key=$replacement")
                                } else if (key !in HASH_KEYS || seenKeys.add(trimmed)) {
                                    patched.add(line)
                                }
                            } else {
                                patched.add(line)
                            }
                        } else {
                            patched.add(line)
                        }
                    }
                    flushSection(patched, updates, currentSection, seenKeys)
                    // Sections the game's file did not have yet.
                    for ((name, patch) in updates) {
                        if (existingLines.none { it.trim().equals("[$name]", ignoreCase = true) }) {
                            patched.add("")
                            patched.add("[$name]")
                            for (key in HASH_KEYS) patched.add("$key=${patch[key] ?: ""}")
                        }
                    }
                } else {
                    for (name in monitoredFiles) {
                        patched.add("[$name]")
                        for (key in HASH_KEYS) patched.add("$key=${updates[name]?.get(key) ?: ""}")
                        patched.add("")
                    }
                }

                val newContent = patched.joinToString("\n").trimEnd() + "\n"
                val newTempPath = "$HASH_MONITOR_PATH.new"
                val outcome = channel.writeBytes(newTempPath, newContent.toByteArray())
                if (!outcome.pushed) {
                    channel.run("rm", "-f", newTempPath)
                    return@withLock false to "hash refresh failed: ${outcome.detail}"
                }
                val mv = channel.run("mv", newTempPath, HASH_MONITOR_PATH)
                if (!mv.isSuccess) {
                    channel.run("rm", "-f", newTempPath)
                    return@withLock false to "hash refresh failed: ${mv.text.ifBlank { "mv exited ${mv.exitCode}" }}"
                }
                val readBack = readFileViaChannel(channel, HASH_MONITOR_PATH)
                if (readBack.isBlank()) {
                    return@withLock true to "hash synced (could not verify — read-back empty)"
                }
                if (readBack.trim() == newContent.trim()) {
                    return@withLock true to "hash monitor synced and verified"
                }
                return@withLock false to "hash synced but NOT verified — read-back disagrees"
            } catch (e: Exception) {
                false to "hash refresh failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private fun flushSection(
        patched: MutableList<String>,
        updates: MutableMap<String, Map<String, String>>,
        section: String,
        seenKeys: MutableSet<String>
    ) {
        val patch = updates.remove(section) ?: return
        for (key in HASH_KEYS) {
            val line = "$key=${patch[key] ?: continue}"
            if (seenKeys.add(line)) patched.add(line)
        }
    }

    /**
     * md5 of a file **as the device computes it** — `md5sum` in the privileged shell, so the
     * digest is over the exact bytes on disk (an in-app `cat` cannot be trusted to preserve
     * trailing newlines). Empty when the file cannot be read.
     */
    private suspend fun md5OfFile(channel: DeviceChannel, path: String): String {
        val r = channel.run("md5sum", path)
        if (!r.isSuccess) return ""
        val digest = r.stdout.trim().split(Regex("\\s+")).firstOrNull() ?: ""
        return if (digest.length == 32) digest else ""
    }

    companion object {
        /** The only package the reference's verified paths apply to — the global release. */
        const val PACKAGE = "com.kurogame.wutheringwaves.global"

        /** Config dir relative to the game's Android data root. */
        private const val CONFIG_REL = "files/UE4Game/Client/Client/Saved/Config/Android"

        /** Log dir relative to the game's Android data root — GamePaths.LOG_PATH in the reference. */
        private const val LOGS_REL = "files/UE4Game/Client/Client/Saved/Logs"

        private const val LOG_FILE_NAME = "Client.log"

        val CONFIG_DIR = "/storage/emulated/0/Android/data/$PACKAGE/$CONFIG_REL"
        val LOG_PATH = "/storage/emulated/0/Android/data/$PACKAGE/$LOGS_REL/$LOG_FILE_NAME"

        /** The game's Android data root — the base every relative game path (DBs, configs) hangs off. */
        val GAME_DATA_ROOT = "/storage/emulated/0/Android/data/$PACKAGE"
        const val HASH_MONITOR_PATH =
            "/storage/emulated/0/Android/data/$PACKAGE/files/UE4Game/Client/Client/Config/Kuro/KuroConfigMonitor.hash"

        private const val SAF_PREFS_KEY = "saf_tree_uri_$PACKAGE"
        private const val MIME_BINARY = "application/octet-stream"

        /** Community pack walk bound: root README + variant folders is two levels; anything past this is not a pack. */
        private const val MAX_PACK_WALK_DEPTH = 3

        private val HASH_SECTION_REGEX = Regex("^\\[[A-Za-z0-9_\\-]+\\.ini\\]$", RegexOption.IGNORE_CASE)
        private val HASH_KEYS = listOf("Hash", "ModifyCount", "LastModifiedTime")

        // Shared across ViewModel instances: every deploy stages to the same device paths.
        private val hashMutex = Mutex()
    }
}
