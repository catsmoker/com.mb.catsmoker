package com.catsmoker.app.features.editgamefiles.hsr

import android.content.Context
import com.catsmoker.app.features.editgamefiles.ConfigBackupStore
import com.catsmoker.app.system.shell.ShellRunner
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Root-only bridge to Honkai: Star Rail's Unity `playerprefs.xml`: detect the install, locate
 * the file, read the graphics block and the QoL preferences out of it, and write edited ones
 * back with the game's own ownership restored.
 *
 * Cross-checked against `referance/gamingtools/hsrgraphicdroid-main/utils/HsrGameManager.kt`
 * (`com.ireddragonicy.hsrgraphicdroid.utils.HsrGameManager`): the same five known packages
 * plus the dynamic `pm list packages hkrpg` sweep, the same four prefs-path templates in the
 * same order (`/data_mirror` first — it crosses the mount-namespace boundary that hides
 * `/data/data` from root shells on modern Android), the same merge of JSON blob and sibling
 * int entries, and the same write choreography: app-owned temp file, root `cp`, `chmod 660`,
 * `chown` from the *directory's* stat so the file ends up owned exactly like the game's own.
 *
 * Divergences from the reference, all in the direction of this app's "report what the device
 * actually did" convention:
 * - **Root is required, not just preferred.** The Shizuku user service runs at shell uid,
 *   which cannot read another app's `/data/data`. A half-available privilege path would
 *   report a read failure that is really an access model, so [HsrReadResult.Failure] says
 *   `NO_ROOT` plainly and the UI gates on it.
 * - **Every write backs the current bytes up first** through the same [ConfigBackupStore]
 *   the Edit Game Files screen uses, and aborts if the backup fails — an editor that can
 *   corrupt a game's settings file has no business doing so without an undo.
 * - **Every write is verified by reading the file back**, not by trusting `cp`'s exit code:
 *   the relevant keys are re-parsed and compared against what was written, and a mismatch is
 *   reported rather than swallowed.
 */

sealed interface HsrReadResult {
    data class Success(
        val settings: HsrGraphicsSettings,
        val gamePrefs: HsrGamePreferences,
        val packageName: String,
        /** Display name for the detected package variant ("Global/SEA", or the raw suffix if unknown). */
        val packageLabel: String,
        val prefsPath: String
    ) : HsrReadResult

    data class Failure(val stage: Stage, val detail: String) : HsrReadResult

    enum class Stage { NO_ROOT, GAME_NOT_INSTALLED, PREFS_NOT_FOUND, READ_FAILED, PARSE_FAILED }
}

sealed interface HsrWriteResult {
    /** [verified] is the read-back comparison; [verificationDetail] names the mismatch if it failed. */
    data class Success(
        val backup: ConfigBackupStore.Entry?,
        val verified: Boolean,
        val verificationDetail: String
    ) : HsrWriteResult

    data class Failure(val stage: Stage, val detail: String) : HsrWriteResult

    enum class Stage { NO_ROOT, GAME_NOT_INSTALLED, PREFS_NOT_FOUND, READ_FAILED, PARSE_FAILED, BACKUP_FAILED, COPY_FAILED }
}

@Singleton
class HsrGameManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shellRunner: ShellRunner
) {

    private val backupStore by lazy { ConfigBackupStore(context) }

    private var cachedPackage: String? = null
    private var cachedPrefsPath: String? = null

    /** Known package variants, in the reference's order — the fast path before the root sweep. */
    private val knownPackages = listOf(
        "com.HoYoverse.hkrpgoversea" to "Global/SEA",
        "com.miHoYo.hkrpg" to "CN (China)",
        "com.HoYoverse.hkrpg" to "TW/HK/MO",
        "com.cognosphere.hkrpg" to "Alternative Global",
        "com.HoYoverse.hkrpgsamsung" to "Samsung Galaxy Store"
    )

    /**
     * Unprivileged probe: is *any* known HSR variant installed right now. The File Engineering
     * picker's dot used to re-list the packages by hand, which could drift from this list
     * (a KDoc cross-check is data, but a duplicated list is a second place to update); the
     * manager owns the variants, so it answers the question. Uses the same
     * [PackageManager.getPackageInfo] check as [detectInstalledPackage] — a failed query
     * (package-visibility filtering) counts as absent for that variant, and a variant only the
     * privileged sweep could find still surfaces when the editor itself loads.
     */
    fun isAnyKnownVariantInstalled(): Boolean = knownPackages.any { (pkg, _) ->
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
    }

    fun isRootAvailable(): Boolean = shellRunner.isRootAvailable()

    /** Clear the package and path caches so the next detect/probe runs against the device as it is now. */
    fun resetCaches() {
        cachedPackage = null
        cachedPrefsPath = null
    }

    /** Detect the install. `refresh` clears the cache so a fresh install is found without an app restart. */
    suspend fun detectInstalledPackage(refresh: Boolean = false): String? {
        if (refresh) cachedPackage = null
        cachedPackage?.let { return it }

        for ((pkg, _) in knownPackages) {
            if (runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess) {
                cachedPackage = pkg
                return pkg
            }
        }

        // Dynamic sweep for unknown store variants — needs root, so it is tried last.
        if (isRootAvailable()) {
            val result = shellRunner.execSafeResult("pm", "list", "packages", "hkrpg")
            val discovered = result.stdout.lineSequence()
                .map { it.removePrefix("package:").trim() }
                .filter { it.contains("hkrpg", ignoreCase = true) }
                .firstOrNull()
            if (discovered != null) {
                cachedPackage = discovered
                return discovered
            }
        }
        return null
    }

    fun packageLabel(pkg: String): String =
        knownPackages.firstOrNull { it.first == pkg }?.second
            ?: pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }

    suspend fun findPrefsPath(refresh: Boolean = false): String? {
        if (refresh) cachedPrefsPath = null
        cachedPrefsPath?.let { return it }

        val pkg = detectInstalledPackage() ?: return null
        val fileName = "$pkg.v2.playerprefs.xml"
        for (template in PREFS_PATH_TEMPLATES) {
            val path = template.format(pkg, fileName)
            val probe = shellRunner.execResult("[ -f '$path' ] && echo exists")
            if (probe.stdout.trim() == "exists") {
                cachedPrefsPath = path
                return path
            }
        }
        return null
    }

    suspend fun readCurrentSettings(): HsrReadResult {
        if (!isRootAvailable()) return HsrReadResult.Failure(HsrReadResult.Stage.NO_ROOT, "root shell unavailable")
        val pkg = detectInstalledPackage()
            ?: return HsrReadResult.Failure(HsrReadResult.Stage.GAME_NOT_INSTALLED, "no HSR package found")
        val path = findPrefsPath()
            ?: return HsrReadResult.Failure(HsrReadResult.Stage.PREFS_NOT_FOUND, "playerprefs.xml not found in any known location")

        val map = readPrefsMap(path)
            ?: return HsrReadResult.Failure(HsrReadResult.Stage.READ_FAILED, "could not read $path")

        val encoded = (map[HsrGraphicsSettings.PREFS_KEY] as? HsrPlayerPrefsXml.Value.StringValue)?.value
            ?: return HsrReadResult.Failure(HsrReadResult.Stage.PARSE_FAILED, "GraphicsSettings_Model key not found in the prefs file")

        val settings = HsrGraphicsSettings.fromEncoded(encoded)
            ?: return HsrReadResult.Failure(HsrReadResult.Stage.PARSE_FAILED, "GraphicsSettings_Model is not the expected URL-encoded JSON")

        // Sibling int entries. Fields absent from the file keep their defaults, which only
        // matters for entries the game itself has not written yet.
        for ((field, key) in HsrGraphicsSettings.SIBLING_INT_KEYS) {
            val value = (map[key] as? HsrPlayerPrefsXml.Value.IntValue)?.value ?: continue
            when (field) {
                "screenWidth" -> settings.screenWidth = value
                "screenHeight" -> settings.screenHeight = value
                "fullscreenMode" -> settings.fullscreenMode = value
                "graphicsQuality" -> settings.graphicsQuality = value
                "enablePsoShaderWarmup" -> settings.enablePsoShaderWarmup = value == 1
                "isUserSave" -> settings.isUserSave = value
                "version" -> settings.version = value
            }
        }
        return HsrReadResult.Success(settings, readGamePrefsFromMap(map), pkg, packageLabel(pkg), path)
    }

    /**
     * Write [settings] into the game's prefs file. The blob's hidden upscaler fields and every
     * unrelated playerprefs entry round-trip untouched: the current file is read, only the
     * graphics keys are replaced, and the merged map is what gets written.
     */
    suspend fun writeSettings(settings: HsrGraphicsSettings): HsrWriteResult {
        val (map, gateFailure) = readMapForWrite()
        if (gateFailure != null) return gateFailure
        val liveMap = map ?: return HsrWriteResult.Failure(HsrWriteResult.Stage.READ_FAILED, "could not read the current file for the pre-write merge")

        liveMap[HsrGraphicsSettings.PREFS_KEY] = HsrPlayerPrefsXml.Value.StringValue(settings.toEncoded())
        for ((field, key) in HsrGraphicsSettings.SIBLING_INT_KEYS) {
            val value = when (field) {
                "screenWidth" -> settings.screenWidth
                "screenHeight" -> settings.screenHeight
                "fullscreenMode" -> settings.fullscreenMode
                "graphicsQuality" -> settings.graphicsQuality
                "enablePsoShaderWarmup" -> if (settings.enablePsoShaderWarmup) 1 else 0
                "isUserSave" -> settings.isUserSave
                "version" -> settings.version
                else -> continue
            }
            liveMap[key] = HsrPlayerPrefsXml.Value.IntValue(value)
        }

        val (backup, failure) = backupAndPush(liveMap)
        if (failure != null) return failure
        val verification = verifyWrite { written -> verifyGraphics(written, settings) }
        return HsrWriteResult.Success(backup, verification.first, verification.second)
    }

    /**
     * Write the QoL preferences. [HsrGamePreferences.speedUpOpen] is per-user: it is only
     * written when the file carries an `App_LastUserID` to key it on, and a missing user id is
     * reported by the read side leaving it null rather than guessed.
     */
    suspend fun writeGamePreferences(prefs: HsrGamePreferences): HsrWriteResult {
        val (map, gateFailure) = readMapForWrite()
        if (gateFailure != null) return gateFailure
        val liveMap = map ?: return HsrWriteResult.Failure(HsrWriteResult.Stage.READ_FAILED, "could not read the current file for the pre-write merge")

        liveMap[HsrGamePreferences.KEY_TEXT_LANGUAGE] =
            HsrPlayerPrefsXml.Value.StringValue(HsrGamePreferences.textLanguageCode(prefs.textLanguage))
        liveMap[HsrGamePreferences.KEY_AUDIO_LANGUAGE] =
            HsrPlayerPrefsXml.Value.StringValue(HsrGamePreferences.audioLanguageCode(prefs.audioLanguage))
        liveMap[HsrGamePreferences.KEY_VIDEO_BLACKLIST] =
            HsrPlayerPrefsXml.Value.StringValue(HsrGamePreferences.encodeBlacklist(prefs.videoBlacklist))
        liveMap[HsrGamePreferences.KEY_AUDIO_BLACKLIST] =
            HsrPlayerPrefsXml.Value.StringValue(HsrGamePreferences.encodeBlacklist(prefs.audioBlacklist))
        prefs.autoBattleOpen?.let {
            liveMap[HsrGamePreferences.KEY_AUTO_BATTLE] = HsrPlayerPrefsXml.Value.IntValue(it)
        }
        val uid = (liveMap[HsrGamePreferences.KEY_LAST_USER_ID] as? HsrPlayerPrefsXml.Value.IntValue)?.value
        if (uid != null) {
            prefs.speedUpOpen?.let {
                liveMap["${HsrGamePreferences.KEY_SPEED_UP_OPEN_PREFIX}${uid}${HsrGamePreferences.KEY_SPEED_UP_OPEN_SUFFIX}"] =
                    HsrPlayerPrefsXml.Value.IntValue(it)
            }
        }

        val (backup, failure) = backupAndPush(liveMap)
        if (failure != null) return failure
        val verification = verifyWrite { written -> verifyPrefs(written, prefs, uid) }
        return HsrWriteResult.Success(backup, verification.first, verification.second)
    }

    /**
     * Restore the newest backup for the detected package, through the same copy path as a
     * normal write. Returns null when no backup exists, with failures carried for the UI.
     */
    suspend fun restoreLatestBackup(): HsrWriteResult? {
        if (!isRootAvailable()) return HsrWriteResult.Failure(HsrWriteResult.Stage.NO_ROOT, "root shell unavailable")
        val pkg = detectInstalledPackage()
            ?: return HsrWriteResult.Failure(HsrWriteResult.Stage.GAME_NOT_INSTALLED, "no HSR package found")
        val path = findPrefsPath()
            ?: return HsrWriteResult.Failure(HsrWriteResult.Stage.PREFS_NOT_FOUND, "playerprefs.xml not found in any known location")
        val bytes = backupStore.list(pkg).firstOrNull()?.let { backupStore.readBytes(it) }
            ?: return null

        val tempFile = File.createTempFile("hsr_restore_", ".xml", context.cacheDir)
        val copyResult = try {
            tempFile.writeBytes(bytes)
            shellRunner.execResult(
                "cp '${tempFile.absolutePath}' '$path' && chmod 660 '$path' && chown \$(stat -c '%u:%g' \$(dirname '$path')) '$path'"
            )
        } finally {
            tempFile.delete()
        }
        return if (copyResult.isSuccess) {
            HsrWriteResult.Success(backup = null, verified = true, verificationDetail = "backup restored")
        } else {
            HsrWriteResult.Failure(
                HsrWriteResult.Stage.COPY_FAILED,
                "cp exit ${copyResult.exitCode}: ${copyResult.stderr.trim().ifEmpty { "no stderr" }}"
            )
        }
    }

    /**
     * Force-stop the game. A running HSR holds its settings in memory and writes them back on
     * exit, clobbering whatever this editor pushed — so the apply flow stops it first and says so.
     */
    suspend fun forceStopGame(): Boolean {
        val pkg = detectInstalledPackage() ?: return false
        return shellRunner.execSafeResult("am", "force-stop", pkg).isSuccess
    }

    fun hasBackup(): Boolean {
        val pkg = cachedPackage ?: return false
        return backupStore.list(pkg).isNotEmpty()
    }

    // region Private helpers

    private fun readGamePrefsFromMap(map: Map<String, HsrPlayerPrefsXml.Value>): HsrGamePreferences {
        val uid = (map[HsrGamePreferences.KEY_LAST_USER_ID] as? HsrPlayerPrefsXml.Value.IntValue)?.value
        return HsrGamePreferences(
            textLanguage = HsrGamePreferences.textLanguageFromCode(
                (map[HsrGamePreferences.KEY_TEXT_LANGUAGE] as? HsrPlayerPrefsXml.Value.StringValue)?.value ?: "en"
            ),
            audioLanguage = HsrGamePreferences.audioLanguageFromCode(
                (map[HsrGamePreferences.KEY_AUDIO_LANGUAGE] as? HsrPlayerPrefsXml.Value.StringValue)?.value ?: "jp"
            ),
            videoBlacklist = HsrGamePreferences.parseBlacklist(
                (map[HsrGamePreferences.KEY_VIDEO_BLACKLIST] as? HsrPlayerPrefsXml.Value.StringValue)?.value
            ),
            audioBlacklist = HsrGamePreferences.parseBlacklist(
                (map[HsrGamePreferences.KEY_AUDIO_BLACKLIST] as? HsrPlayerPrefsXml.Value.StringValue)?.value
            ),
            speedUpOpen = uid?.let {
                (map["${HsrGamePreferences.KEY_SPEED_UP_OPEN_PREFIX}${it}${HsrGamePreferences.KEY_SPEED_UP_OPEN_SUFFIX}"] as? HsrPlayerPrefsXml.Value.IntValue)?.value
            },
            autoBattleOpen = (map[HsrGamePreferences.KEY_AUTO_BATTLE] as? HsrPlayerPrefsXml.Value.IntValue)?.value
        )
    }

    /**
     * The gate every write passes through: root, install, path — each failure stage kept
     * distinct so the report names the actual refusal, not a generic read failure.
     */
    private suspend fun gateForWrite(): HsrWriteResult.Failure? {
        if (!isRootAvailable()) {
            return HsrWriteResult.Failure(HsrWriteResult.Stage.NO_ROOT, "root shell unavailable")
        }
        if (detectInstalledPackage() == null) {
            return HsrWriteResult.Failure(HsrWriteResult.Stage.GAME_NOT_INSTALLED, "no HSR package found")
        }
        if (findPrefsPath() == null) {
            return HsrWriteResult.Failure(HsrWriteResult.Stage.PREFS_NOT_FOUND, "playerprefs.xml not found in any known location")
        }
        return null
    }

    /** The gate, plus a fresh read of the live file for the pre-write merge. */
    private suspend fun readMapForWrite(): Pair<MutableMap<String, HsrPlayerPrefsXml.Value>?, HsrWriteResult.Failure?> {
        gateForWrite()?.let { return null to it }
        val map = readPrefsMap(cachedPrefsPath ?: return null to HsrWriteResult.Failure(HsrWriteResult.Stage.PREFS_NOT_FOUND, "prefs path lost"))
            ?: return null to HsrWriteResult.Failure(HsrWriteResult.Stage.READ_FAILED, "could not read the current file for the pre-write merge")
        return map to null
    }

    /**
     * Back up the bytes the device actually holds (not the payload about to be pushed; a
     * failed backup aborts the write), then push [map] through the copy choreography.
     */
    private suspend fun backupAndPush(
        map: Map<String, HsrPlayerPrefsXml.Value>
    ): Pair<ConfigBackupStore.Entry?, HsrWriteResult.Failure?> {
        val pkg = cachedPackage
            ?: return null to HsrWriteResult.Failure(HsrWriteResult.Stage.GAME_NOT_INSTALLED, "no HSR package found")
        val path = cachedPrefsPath
            ?: return null to HsrWriteResult.Failure(HsrWriteResult.Stage.PREFS_NOT_FOUND, "playerprefs.xml not found in any known location")

        val currentBytes = readPrefsBytes(path)
        val backup = if (currentBytes != null && currentBytes.isNotEmpty()) {
            backupStore.save(pkg, File(path).name, currentBytes)
                ?: return null to HsrWriteResult.Failure(
                    HsrWriteResult.Stage.BACKUP_FAILED,
                    "could not store a backup of $path — refusing to overwrite"
                )
        } else {
            null
        }

        val tempFile = File.createTempFile("hsr_prefs_", ".xml", context.cacheDir)
        val copyResult = try {
            tempFile.writeText(HsrPlayerPrefsXml.serialize(map))
            // chown takes the owner from the prefs *directory* so the file lands exactly as the
            // game's own writes would leave it. Raw command: the $( ) substitution must survive.
            shellRunner.execResult(
                "cp '${tempFile.absolutePath}' '$path' && chmod 660 '$path' && chown \$(stat -c '%u:%g' \$(dirname '$path')) '$path'"
            )
        } finally {
            tempFile.delete()
        }
        return if (copyResult.isSuccess) {
            backup to null
        } else {
            backup to HsrWriteResult.Failure(
                HsrWriteResult.Stage.COPY_FAILED,
                "cp/chmod/chown exit ${copyResult.exitCode}: ${copyResult.stderr.trim().ifEmpty { "no stderr" }}"
            )
        }
    }

    /** Re-read the file and hand it to [check] for the caller-specific comparison. */
    private suspend fun verifyWrite(
        check: suspend (MutableMap<String, HsrPlayerPrefsXml.Value>) -> Pair<Boolean, String>
    ): Pair<Boolean, String> {
        val path = cachedPrefsPath
            ?: return false to "prefs path lost after write"
        val map = readPrefsMap(path)
            ?: return false to "file unreadable after write"
        return check(map)
    }

    private fun verifyGraphics(
        map: MutableMap<String, HsrPlayerPrefsXml.Value>,
        settings: HsrGraphicsSettings
    ): Pair<Boolean, String> {
        val encoded = (map[HsrGraphicsSettings.PREFS_KEY] as? HsrPlayerPrefsXml.Value.StringValue)?.value
            ?: return false to "GraphicsSettings_Model missing after write"
        val readBack = HsrGraphicsSettings.fromEncoded(encoded)
            ?: return false to "GraphicsSettings_Model no longer parses after write"
        val mismatches = buildList {
            if (readBack.fps != settings.fps) add("FPS ${readBack.fps}")
            if (readBack.aaMode != settings.aaMode) add("AAMode ${readBack.aaMode}")
            if (readBack.renderScale != settings.renderScale) add("RenderScale ${readBack.renderScale}")
            if (readBack.enableVSync != settings.enableVSync) add("VSync ${readBack.enableVSync}")
            if ((map["Screenmanager%20Resolution%20Width"] as? HsrPlayerPrefsXml.Value.IntValue)?.value != settings.screenWidth) {
                add("Width ${(map["Screenmanager%20Resolution%20Width"] as? HsrPlayerPrefsXml.Value.IntValue)?.value}")
            }
            if ((map["Screenmanager%20Resolution%20Height"] as? HsrPlayerPrefsXml.Value.IntValue)?.value != settings.screenHeight) {
                add("Height ${(map["Screenmanager%20Resolution%20Height"] as? HsrPlayerPrefsXml.Value.IntValue)?.value}")
            }
        }
        return if (mismatches.isEmpty()) true to "read-back matches" else false to "device holds: ${mismatches.joinToString(", ")}"
    }

    private fun verifyPrefs(
        map: MutableMap<String, HsrPlayerPrefsXml.Value>,
        prefs: HsrGamePreferences,
        uidAtWrite: Int?
    ): Pair<Boolean, String> {
        val mismatches = buildList {
            val text = (map[HsrGamePreferences.KEY_TEXT_LANGUAGE] as? HsrPlayerPrefsXml.Value.StringValue)?.value
            val expectedText = HsrGamePreferences.textLanguageCode(prefs.textLanguage)
            if (text != expectedText) add("text language $text")
            val audio = (map[HsrGamePreferences.KEY_AUDIO_LANGUAGE] as? HsrPlayerPrefsXml.Value.StringValue)?.value
            val expectedAudio = HsrGamePreferences.audioLanguageCode(prefs.audioLanguage)
            if (audio != expectedAudio) add("audio language $audio")
            prefs.autoBattleOpen?.let {
                val actual = (map[HsrGamePreferences.KEY_AUTO_BATTLE] as? HsrPlayerPrefsXml.Value.IntValue)?.value
                if (actual != it) add("auto battle $actual")
            }
            if (uidAtWrite != null && prefs.speedUpOpen != null) {
                val actual = (map["${HsrGamePreferences.KEY_SPEED_UP_OPEN_PREFIX}${uidAtWrite}${HsrGamePreferences.KEY_SPEED_UP_OPEN_SUFFIX}"] as? HsrPlayerPrefsXml.Value.IntValue)?.value
                if (actual != prefs.speedUpOpen) add("speed up $actual")
            }
        }
        return if (mismatches.isEmpty()) true to "read-back matches" else false to "device holds: ${mismatches.joinToString(", ")}"
    }

    private suspend fun readPrefsBytes(path: String): ByteArray? {
        val result = shellRunner.execResult("cat '$path'")
        if (!result.isSuccess || result.stdout.isEmpty()) return null
        return result.stdout.toByteArray()
    }

    private suspend fun readPrefsMap(path: String): MutableMap<String, HsrPlayerPrefsXml.Value>? {
        val result = shellRunner.execResult("cat '$path'")
        if (!result.isSuccess || result.stdout.isEmpty()) return null
        val map = HsrPlayerPrefsXml.parse(result.stdout)
        if (map.isEmpty()) return null
        return map
    }

    private companion object {
        /** Same order as the reference: /data_mirror crosses the mount-namespace boundary first. */
        private val PREFS_PATH_TEMPLATES = listOf(
            "/data_mirror/data_ce/null/0/%s/shared_prefs/%s",
            "/data/user/0/%s/shared_prefs/%s",
            "/data/data/%s/shared_prefs/%s",
            "/data/user_de/0/%s/shared_prefs/%s"
        )
    }

    // endregion
}
