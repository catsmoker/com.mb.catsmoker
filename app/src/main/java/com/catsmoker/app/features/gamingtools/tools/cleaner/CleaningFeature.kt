package com.catsmoker.app.features.gamingtools.tools.cleaner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import com.catsmoker.app.R
import com.catsmoker.app.system.shell.ShellRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import kotlin.coroutines.coroutineContext

object CleaningFeature {
    /**
     * The junk buckets, labelled in words a non-technical user can act on.
     *
     * @param isAggressive rendered in red and unticked by default. Not "dangerous" — nothing here
     *   touches the user's own files — but each of these has a visible cost: a log the user might
     *   have wanted for a bug report, or app data that turns out to belong to something still
     *   installed under a name the scan could not resolve.
     */
    enum class Category(val label: String, val isAggressive: Boolean = false) {
        CACHE("App leftovers"),
        TEMP("Temporary files"),
        THUMBNAILS("Photo previews"),

        /**
         * Zero-byte files, hidden ones included.
         *
         * The reference cleaner has this as `clean_empty_file`, on by default, and it is not marked
         * aggressive here for the same reason: a file with no bytes in it has nothing to lose.
         */
        EMPTY_FILES("Empty files"),

        /** Folders whose whole subtree holds no data. `clean_empty_folder` in the reference, also on by default. */
        EMPTY_DIRS("Empty folders"),
        LOGS("Error reports", true),
        CORPSES("Files left by removed apps", true),

        /**
         * Loose installer packages — `.apk`, `.apks`, `.apkm`, `.aab` — found in shared storage,
         * the reference cleaner's `filter_apkFiles` (`cleanApk`, on by default there).
         *
         * Deliberately *not* marked aggressive, matching the reference: an installer left behind
         * after an install is disposable — the store re-downloads it — and `Download`, where
         * almost all of them live, is a protected name, so the walk never claims anything the
         * user put there on purpose unless it is inside an unprotected folder first. What this
         * category actually reaches is installers dropped at the storage root and in app folders
         * like `UCDownloads` (which the built-in blacklist already claims wholesale).
         */
        INSTALLERS("Old installer files")
    }

    /**
     * One junk bucket.
     *
     * @param sizeBytes total measured size of [affectedPaths]. Measured with `File.length()` for
     *   paths this app can read and with `du -sk` for paths only the privileged shell can reach.
     *   Legitimately 0 for [Category.EMPTY_FILES] and [Category.EMPTY_DIRS], whose whole point is
     *   that they hold nothing — which is why [itemCount] is what the UI leads with.
     * @param itemCount number of top-level paths claimed, not the number of files inside them.
     */
    data class ScanResult(
        val category: Category,
        val sizeBytes: Long,
        val itemCount: Int,
        val affectedPaths: List<String>
    )

    /**
     * Outcome of a scan, including what it could *not* reach.
     *
     * The distinction between "scanned and found nothing" and "could not scan" matters: reporting
     * 0 B for the second case is a fabricated result, so the flags below let the UI say which
     * happened.
     */
    data class ScanReport(
        val results: List<ScanResult> = emptyList(),
        /** True when the shared-storage tree was actually walked with file APIs. */
        val scannedSharedStorage: Boolean = false,
        /** True when `Android/data` + `Android/obb` were read through root/Shizuku. */
        val scannedAppData: Boolean = false,
        /** True when the UI should offer the all-files-access settings screen. */
        val needsAllFilesAccess: Boolean = false,
        /** Exactly what this scan could not reach. Rendered verbatim in the UI. */
        val limitations: List<String> = emptyList()
    ) {
        val totalBytes: Long get() = results.sumOf { it.sizeBytes }
        val totalItems: Int get() = results.sumOf { it.itemCount }

        /** False when nothing could be read at all — distinct from "read everything, found nothing". */
        val scannedAnything: Boolean get() = scannedSharedStorage || scannedAppData
    }

    /**
     * What a clean actually did, in figures that were each measured.
     *
     * This replaced a `List<String>` of log lines rendered into a scrolling terminal. The counts are
     * the same ones that log was built from — nothing is summarised away — but they arrive as numbers
     * so the card can state the outcome in a sentence instead of asking the user to read a transcript.
     *
     * @param freedBytes bytes reclaimed, summed from paths measured immediately before deletion and
     *   confirmed gone afterwards. Legitimately 0 when everything removed was empty.
     * @param unmeasuredItems removed, but their size could not be read — so [freedBytes] is a floor,
     *   not the whole figure, and the UI has to say so.
     * @param trimmedInternalCaches whether the framework was additionally asked to drop its own app
     *   caches. That space is not in [freedBytes] because it was never measured.
     */
    data class CleanResult(
        val deletedItems: Int = 0,
        val freedBytes: Long = 0L,
        val unmeasuredItems: Int = 0,
        val failedItems: Int = 0,
        val alreadyGoneItems: Int = 0,
        val protectedSkips: Int = 0,
        val trimmedInternalCaches: Boolean = false,
        /** Which channel did the deleting: "Root", "Shizuku", or "app file access only". */
        val channel: String = "",
        /** True when a privileged channel was available, which decides how a failure is explained. */
        val privileged: Boolean = false
    ) {
        /** True when nothing at all was removed — distinct from "removed things that were empty". */
        val removedNothing: Boolean get() = deletedItems == 0
    }

    /** Localized bucket label. The enum keeps plain words (no Context), so mapping lives here. */
    @androidx.annotation.StringRes
    internal fun categoryStringRes(category: Category): Int = when (category) {
        Category.CACHE -> R.string.gt_cat_cache
        Category.TEMP -> R.string.gt_cat_temp
        Category.THUMBNAILS -> R.string.gt_cat_thumbs
        Category.EMPTY_FILES -> R.string.gt_cat_empty_files
        Category.EMPTY_DIRS -> R.string.gt_cat_empty_dirs
        Category.LOGS -> R.string.gt_cat_logs
        Category.CORPSES -> R.string.gt_cat_corpses
        Category.INSTALLERS -> R.string.gt_cat_installers
    }

    /** Guard rails so a pathological tree cannot hang the scan or blow the stack. */
    private const val MAX_DEPTH = 12
    private const val MAX_PATHS_PER_CATEGORY = 2000

    /** The storage root the blacklist patterns below are written against. */
    private const val PRIMARY_ROOT = "/storage/emulated/0"

    /**
     * One filesystem block. `du -sk` reports this for a directory holding nothing but itself, so a
     * hit at or below it has no content to reclaim and is not worth listing as junk.
     */
    private const val ONE_BLOCK_BYTES = 4096L

    /** A `du -sk` output line: a kilobyte count, whitespace, then an absolute path. */
    private val DU_LINE = Regex("(\\d+)\\s+(/.+)")

    /** Well-formed package name, which is also guaranteed free of shell metacharacters. */
    private val PACKAGE_NAME = Regex("[A-Za-z0-9_][A-Za-z0-9_.\\-]*")

    /**
     * Directories we never claim or delete, wherever they appear in the tree. Losing any of these
     * is unrecoverable for the user, and no amount of reclaimed space justifies the risk.
     *
     * The scan still walks *inside* them, which is what the reference cleaner does with its own
     * auto-whitelist: `getListFiles` skips adding an auto-whitelisted folder as a deletion
     * candidate but always recurses into it. Refusing to descend would also make several of our own
     * blacklist patterns dead — `Download/MGC_CRASH_LOG`, `Download/UCDownloads` and
     * `DCIM/Camera/thumbnails` all live under a name on this list.
     */
    private val protectedNames = setOf(
        "dcim", "pictures", "movies", "music", "documents", "download", "downloads",
        "whatsapp", "telegram", "signal", "recordings", "screenshots", "books",
        "audiobooks", "podcasts", "ringtones", "alarms", "notifications", "backups",
        "backup", "titaniumbackup", "keys", "obb"
    )

    /**
     * Name fragments that mark a folder as the user's own, copied verbatim from the reference
     * cleaner's `filter_autoWhite`. Matched as substrings because that is how the reference matches
     * them — `autoWhiteList` uses `file.name.lowercase().contains(...)`, so `MyBackup_2024` is
     * covered where an exact-name rule would miss it.
     */
    private val protectedFragments = listOf(
        "backup", "copy", "copies", "important", "do_not_edit", ".stfolder"
    )

    private val blacklistPatterns = listOf(
        ".*\\.log$",
        ".*\\.tmp$",
        ".*/log",
        ".*/Logs",
        ".*/logs",
        "/storage/emulated/0/.*albumthumbs\\?",
        "/storage/emulated/0/Android/data/.*/fastbot",
        "/storage/emulated/0/Android/data/.*/files/tombstone_.*",
        "/storage/emulated/0/Android/data/.*/files/al",
        "/storage/emulated/0/Android/data/.*/files/il2cpp",
        "/storage/emulated/0/Android/data/.*/files/supersonicads",
        "/storage/emulated/0/Android/data/.*/files/Unity/.*/Analytics",
        "/storage/emulated/0/Android/data/.*/files/UnityServicesCachedMetrics",
        "/storage/emulated/0/Android/data/.*/files/.*\\.pangled",
        "/storage/emulated/0/Android/data/.*/files/\\..*\\.vguard",
        "/storage/emulated/0/Android/data/.*/files/.*mobvista",
        "/storage/emulated/0/Android/data/.*/files/.*splashad",
        "/storage/emulated/0/Android/data/.*/files/.*UnityAdsVideoCache",
        "/storage/emulated/0/Android/data/com\\.facebook\\.katana/files/fb_temp",
        "/storage/emulated/0/Android/data/com\\.facebook\\.katana/files/secure_shared",
        "/storage/emulated/0/Android/data/com\\.ss\\.android\\.ugc\\.trill/files/monitor_data_switch",
        "/storage/emulated/0/Android/\\..*\\.vguard",
        "/storage/emulated/0/ApkEditor/tmp",
        "/storage/emulated/0/ColorOS",
        "/storage/emulated/0/com\\.UCMobile\\.intl",
        "/storage/emulated/0/DCIM/Camera/\\.escheck\\.tmp",
        "/storage/emulated/0/DCIM/Camera/thumbnails",
        "/storage/emulated/0/Download/AppMonitorSDKLogs",
        "/storage/emulated/0/Download/MGC_CRASH_LOG",
        "/storage/emulated/0/Download/UCDownloads",
        "/storage/emulated/0/MIUI/debug_log",
        "/storage/emulated/0/MIUI/BugReportCache",
        "/storage/emulated/0/supercache",
        "/storage/emulated/0/Tencent",
        "/storage/emulated/0/UCShare",
        "/storage/emulated/0/UnityAdsVideoCache",
        "/storage/emulated/0/\\.chartboost",
        "/storage/emulated/0/\\.DataStorage",
        "/storage/emulated/0/\\.dev",
        "/storage/emulated/0/\\.estrongs",
        "/storage/emulated/0/\\.ext4",
        "/storage/emulated/0/\\.sstmp",
        "/storage/emulated/0/\\.userReturn",
        "/storage/emulated/0/\\.Uc2DataStorage",
        "/storage/emulated/0/\\.UTSystemConfig",
        "/storage/emulated/0/\\.Uc2UTSystemConfig",
        "/storage/emulated/0/crash\\.txt$",
        "/storage/emulated/0/logcat\\.txt$",
        "/storage/emulated/0/gltools_crashlog\\.txt$",
        "/storage/emulated/0/.*Analytics",
        "/storage/emulated/0/.*Cache",
        "/storage/emulated/0/.*cache",
        "/storage/emulated/0/.*/\\.DS_Store$",
        "/storage/emulated/0/.*/\\.nomedia$",
        "/storage/emulated/0/.*/\\.spotlight-V100",
        "/storage/emulated/0/.*/\\.Trash",
        "/storage/emulated/0/.*\\.exo$",
        "/storage/emulated/0/.*\\.thumb[0-9]",
        "/storage/emulated/0/.*\\.thumbnails\\?$",
        "/storage/emulated/0/.*thumbs?\\.db",
        "/storage/emulated/0/.*/bugreports",
        "/storage/emulated/0/.*/Bugreport",
        "/storage/emulated/0/.*/desktop\\.ini$",
        "/storage/emulated/0/.*/fseventd",
        "/storage/emulated/0/.*/leakcanary",
        "/storage/emulated/0/.*/LOST\\.DIR$"
    ).map { it.toRegex(RegexOption.IGNORE_CASE) }

    /**
     * @return true when this app's own file APIs can read shared storage.
     *
     * From API 30 the plain read permission only covers media, so all-files access is the only
     * thing that makes a full storage walk possible — which is exactly the check the reference
     * cleaner performs before scanning.
     */
    fun hasStorageAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    /**
     * Walks external storage and buckets junk by [Category].
     *
     * Two channels are used, because neither alone can see everything:
     *  - file APIs for shared storage, which needs all-files access on API 30+;
     *  - a privileged shell for `Android/data` and `Android/obb`, which no app's file APIs can
     *    reach from API 30 onwards no matter which permissions are held.
     *
     * Whatever a channel cannot cover is recorded in [ScanReport.limitations] instead of quietly
     * contributing 0 B, so the UI never presents "no access" as "nothing to clean".
     *
     * @param shellRunner privileged channel (root first, Shizuku otherwise). Also the source of the
     *   authoritative installed-package list: without it [Category.CORPSES] is skipped entirely,
     *   because package-visibility filtering makes `getInstalledApplications` under-report installed
     *   apps and healthy `Android/data` directories would be misread as leftovers.
     */
    suspend fun scan(
        context: Context,
        shellRunner: ShellRunner,
        userKeepEntries: Set<String> = emptySet(),
        userCleanPatterns: Set<String> = emptySet()
    ): ScanReport =
        withContext(Dispatchers.IO) {
            val root = Environment.getExternalStorageDirectory()
            val rootPath = root.absolutePath
            val limitations = mutableListOf<String>()

            val directAccess = hasStorageAccess(context)
            val privileged = shellRunner.hasPrivilege()

            // Compiled once per scan; invalid patterns were already rejected at write time, and
            // an invalid one that arrived some other way is skipped by the compiler.
            val userClean = CleanerPatternMatcher.compileBlacklist(userCleanPatterns)

            // A granted permission is not proof the volume is readable, so the listing itself is
            // the test. null here is precisely the case that used to empty every bucket silently.
            val rootListing = if (directAccess) root.listFiles() else null

            if (!directAccess) {
                limitations += if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    context.getString(R.string.gt_scan_lim_files)
                } else {
                    context.getString(R.string.gt_scan_lim_perm)
                }
            } else if (rootListing == null) {
                limitations += context.getString(R.string.gt_scan_lim_mount)
            }
            if (!privileged) {
                limitations += context.getString(R.string.gt_scan_lim_priv)
            }

            val installedPackages = resolveInstalledPackages(context, shellRunner)
            if (installedPackages == null) {
                limitations += context.getString(R.string.gt_scan_lim_pkgs)
            }

            val buckets = Buckets()

            if (rootListing != null) {
                walkSharedStorage(rootPath, installedPackages, userKeepEntries, userClean, buckets)
            }

            val scannedAppData = if (privileged) {
                scanAppDataViaShell(context, shellRunner, rootPath, installedPackages, buckets, limitations)
            } else {
                false
            }

            // A capped bucket is still a partial answer, so say so rather than presenting the first
            // 2000 paths as the whole picture.
            for (category in buckets.truncated) {
                limitations += context.getString(
                    R.string.gt_scan_lim_truncated,
                    context.getString(categoryStringRes(category)),
                    MAX_PATHS_PER_CATEGORY
                )
            }

            ScanReport(
                results = buckets.toResults(),
                scannedSharedStorage = rootListing != null,
                scannedAppData = scannedAppData,
                needsAllFilesAccess = !directAccess,
                limitations = limitations
            )
        }

    /**
     * One directory the walk actually listed, and what it directly holds.
     *
     * Empty-folder detection needs the whole subtree rather than a single listing. `A/B/C` with only
     * `C` empty sheds one level per run otherwise — which is why the reference cleaner ships a
     * "run N times" setting to converge on it. Recording the tree during the walk and resolving it
     * afterwards reaches the same end state from one scan.
     */
    private class DirScan(val depth: Int, val isProtected: Boolean) {
        /** Child directories that were listed as well, so their contents are known. */
        val childDirs = mutableListOf<String>()

        /**
         * True once something is found that this directory cannot be considered empty over: a file
         * with bytes in it, a child claimed for another category, or anything the walk could not
         * verify (an unreadable listing, a symlink, a subtree past [MAX_DEPTH]). Never delete a
         * folder whose contents we did not actually establish.
         */
        var hasContent = false

        /** Zero-byte files directly inside, in scan order. */
        val emptyFiles = mutableListOf<String>()
    }

    /**
     * Walks shared storage and claims junk.
     *
     * Iterative on purpose: recursion overflows on deep trees, and an explicit stack lets us cap
     * depth. The walk runs in two phases because emptiness is a property of a subtree, not of one
     * listing — phase one records the tree and claims everything that can be judged on the spot,
     * phase two resolves which folders hold no data at all.
     */
    private suspend fun walkSharedStorage(
        rootPath: String,
        installedPackages: Set<String>?,
        userKeep: Set<String>,
        userClean: List<Regex>,
        buckets: Buckets
    ) {
        // Insertion order is pre-order, so a parent is always recorded before its children. Phase
        // two relies on that in both directions: reversed for the bottom-up resolve, forward so the
        // topmost empty folder is the one claimed.
        val dirs = LinkedHashMap<String, DirScan>()
        dirs[rootPath] = DirScan(0, isProtected = true)

        val stack = ArrayDeque<String>()
        stack.addLast(rootPath)
        var visited = 0

        while (stack.isNotEmpty()) {
            val path = stack.removeLast()
            val node = dirs[path] ?: continue
            val children = File(path).listFiles()
            if (children == null) {
                // The parent listed, this one did not: contents unknown, so it is not "empty".
                node.hasContent = true
                continue
            }

            for (child in children) {
                if (++visited % 512 == 0) coroutineContext.ensureActive()

                // Links can point back up the tree or off it, so they are never followed, never
                // deleted, and never treated as absence of content.
                if (isSymlink(child)) {
                    node.hasContent = true
                    continue
                }

                val childProtected = isProtected(child)

                if (child.isDirectory) {
                    // A protected name is never claimed, so a keep entry has nothing to save it
                    // from; a user clean rule on a protected directory is refused the same way —
                    // the walk never re-opens DCIM or Download to deletion, however the rules are
                    // written. Unprotected directories get the full classification below.
                    val category = if (childProtected) {
                        null
                    } else {
                        classifyDirectory(child, rootPath, installedPackages, userKeep, userClean)
                    }
                    if (category != null) {
                        // Claim the whole directory and stop: descending would double-count its
                        // contents against a second category.
                        buckets.add(category, child.absolutePath, sizeOf(child))
                        node.hasContent = true
                        continue
                    }
                    if (node.depth + 1 > MAX_DEPTH) {
                        node.hasContent = true
                        continue
                    }
                    dirs[child.absolutePath] = DirScan(node.depth + 1, childProtected)
                    node.childDirs += child.absolutePath
                    stack.addLast(child.absolutePath)
                } else when {
                    // User keep rules are judged before anything else, so an entry here overrides
                    // even the built-in blacklist — the whitelist is the user's "no, this one"
                    // answer, exactly the precedence the reference gives it by skipping
                    // whitelisted files at listing time.
                    CleanerPatternMatcher.isWhitelisted(child.absolutePath, child.name, userKeep) ->
                        node.hasContent = true
                    // A user clean pattern is judged before the built-in rules: the user named
                    // this path for removal, so it is claimed under LOGS (the built-in
                    // delete-by-rule bucket) whatever it looks like. Protected files never reach
                    // here, so the guard below stands above user rules, not below them.
                    CleanerPatternMatcher.matchesBlacklist(child.absolutePath, userClean) -> {
                        buckets.add(Category.LOGS, child.absolutePath, child.length())
                        node.hasContent = true
                    }
                    // A name the user marked as theirs is left alone whatever its size, and it is
                    // left alone here rather than at delete time so the list the user reviews is
                    // exactly the list that gets removed. It also stops the enclosing folder from
                    // being claimed, which would delete this file along with it.
                    childProtected -> node.hasContent = true
                    matchesBlacklist(child.absolutePath, rootPath) -> {
                        buckets.add(Category.LOGS, child.absolutePath, child.length())
                        node.hasContent = true
                    }
                    // Loose installer packages, the reference cleaner's `filter_apkFiles`.
                    CleanerPatternMatcher.isInstallerFile(child.name) -> {
                        buckets.add(Category.INSTALLERS, child.absolutePath, child.length())
                        node.hasContent = true
                    }
                    // Zero bytes: junk by the reference cleaner's `isFileEmpty` rule, whether or not
                    // the name starts with a dot. Claimed in phase two, because an enclosing folder
                    // that holds nothing else is the better thing to remove.
                    child.length() == 0L -> node.emptyFiles += child.absolutePath
                    else -> node.hasContent = true
                }
            }
        }

        claimEmptyEntries(dirs, buckets)
    }

    /**
     * Phase two: claims the folders that hold no data, then the zero-byte files left over.
     *
     * A folder qualifies when its entire subtree contains nothing but folders and zero-byte files —
     * the rule the reference cleaner sketches in the commented-out branch of `isDirectoryEmpty` and
     * otherwise reaches by running several times over. Resolving it here means one scan is enough
     * and the user is shown the outermost folder rather than the innermost.
     */
    private fun claimEmptyEntries(dirs: LinkedHashMap<String, DirScan>, buckets: Buckets) {
        // Bottom-up: reversed insertion order visits every child before its parent.
        val reclaimable = mutableSetOf<String>()
        for ((path, node) in dirs.entries.reversed()) {
            // A protected folder is never a candidate, and neither is any ancestor of one:
            // reclaiming the ancestor would take the protected folder with it. The storage root is
            // marked protected for exactly that reason, so it can never be claimed either.
            if (node.isProtected || node.hasContent) continue
            if (node.childDirs.any { it !in reclaimable }) continue
            reclaimable += path
        }

        // Top-down, so the outermost qualifying folder is claimed and Buckets drops the rest as
        // covered by an ancestor.
        for (path in dirs.keys) {
            if (path in reclaimable) buckets.add(Category.EMPTY_DIRS, path, 0L)
        }

        // Whatever zero-byte files are not already inside a claimed folder. Their size is 0 because
        // that is what they measure — the count is the real figure here.
        for (node in dirs.values) {
            for (file in node.emptyFiles) buckets.add(Category.EMPTY_FILES, file, 0L)
        }
    }

    /**
     * Reads the junk that file APIs cannot see.
     *
     * `Android/data` and `Android/obb` are closed to every app's file APIs from API 30 onwards, and
     * all-files access does not lift that, so a privileged shell is the only honest way to find,
     * size or remove app caches there. `du -sk` supplies the sizes because `File.length()` returns
     * 0 on those paths for the same reason.
     *
     * @return true when the directory was reachable and therefore actually scanned.
     */
    private suspend fun scanAppDataViaShell(
        context: Context,
        shellRunner: ShellRunner,
        rootPath: String,
        installedPackages: Set<String>?,
        buckets: Buckets,
        limitations: MutableList<String>
    ): Boolean {
        val dataDir = "$rootPath/Android/data"
        if (!shellRunner.execSafeResult("test", "-d", dataDir).isSuccess) {
            limitations += context.getString(R.string.gt_scan_lim_no_datadir, dataDir)
            return false
        }

        val globsByCategory = linkedMapOf(
            Category.CACHE to listOf(
                "$dataDir/*/cache",
                "$dataDir/*/code_cache",
                "$dataDir/*/files/cache",
                "$dataDir/*/files/.cache"
            ),
            Category.TEMP to listOf(
                "$dataDir/*/files/temp",
                "$dataDir/*/files/tmp",
                "$dataDir/*/cache/tmp"
            ),
            Category.LOGS to listOf(
                "$dataDir/*/files/log",
                "$dataDir/*/files/logs",
                "$dataDir/*/files/Logs",
                "$dataDir/*/files/tombstone_*",
                "$dataDir/*/files/crashlytics"
            )
        )

        for ((category, globs) in globsByCategory) {
            coroutineContext.ensureActive()
            for ((path, size) in duSizes(shellRunner, globs)) {
                if (size > ONE_BLOCK_BYTES) buckets.add(category, path, size)
            }
        }

        if (installedPackages != null) {
            coroutineContext.ensureActive()
            for ((path, size) in shellCorpses(shellRunner, rootPath, installedPackages)) {
                buckets.add(Category.CORPSES, path, size)
            }
        }
        return true
    }

    /**
     * Measures each match of [globs] with `du -sk`.
     *
     * Both privileged channels run the command through `sh -c`, so the shell expands the globs and
     * `du -sk` prints one line per match. A pattern matching nothing is passed through literally,
     * `du` fails on it, and its error goes to /dev/null — so unmatched patterns simply produce no
     * line. A non-zero exit is therefore normal here and only the absence of output means failure.
     */
    private suspend fun duSizes(shellRunner: ShellRunner, globs: List<String>): List<Pair<String, Long>> {
        if (globs.isEmpty()) return emptyList()
        val result = shellRunner.execResult("du -sk ${globs.joinToString(" ")} 2>/dev/null")
        return result.stdout.lineSequence().mapNotNull { line ->
            val match = DU_LINE.matchEntire(line.trim()) ?: return@mapNotNull null
            val kb = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            match.groupValues[2] to kb * 1024L
        }.toList()
    }

    /**
     * Finds `Android/data` and `Android/obb` directories belonging to packages that are no longer
     * installed. Only well-formed package names qualify: a stray folder is not proof of an
     * uninstalled app, and the name restriction also keeps every path shell-safe.
     */
    private suspend fun shellCorpses(
        shellRunner: ShellRunner,
        rootPath: String,
        installed: Set<String>
    ): List<Pair<String, Long>> {
        val orphans = mutableListOf<String>()
        for (dirName in listOf("data", "obb")) {
            val dir = "$rootPath/Android/$dirName"
            val listing = shellRunner.execSafeResult("ls", "-1", dir)
            if (!listing.isSuccess) continue
            listing.stdout.lineSequence()
                .map { it.trim() }
                .filter { it.contains('.') && PACKAGE_NAME.matches(it) && it !in installed }
                .forEach { orphans += "$dir/$it" }
        }
        // Exact paths, so every one of them produces a du line.
        return duSizes(shellRunner, orphans)
    }

    /** Collects claimed paths per category, with the size measured by whichever channel found them. */
    private class Buckets {
        private val entries = linkedMapOf<Category, MutableList<Pair<String, Long>>>()
        private val claimed = mutableSetOf<String>()

        /** Categories that hit [MAX_PATHS_PER_CATEGORY], so the scan can say it stopped short. */
        val truncated = linkedSetOf<Category>()

        fun add(category: Category, path: String, sizeBytes: Long) {
            val list = entries.getOrPut(category) { mutableListOf() }
            if (list.size >= MAX_PATHS_PER_CATEGORY) {
                truncated += category
                return
            }
            // Both channels can reach the same path on API 27-29; counting it twice would inflate
            // the total, and so would counting a directory already covered by a claimed parent.
            if (path in claimed || isCoveredByAncestor(path)) return
            claimed.add(path)
            list.add(path to sizeBytes)
        }

        private fun isCoveredByAncestor(path: String): Boolean {
            var separator = path.lastIndexOf('/')
            while (separator > 0) {
                if (path.substring(0, separator) in claimed) return true
                separator = path.lastIndexOf('/', separator - 1)
            }
            return false
        }

        fun toResults(): List<ScanResult> = entries
            .filterValues { it.isNotEmpty() }
            .map { (category, items) ->
                ScanResult(
                    category = category,
                    sizeBytes = items.sumOf { it.second },
                    itemCount = items.size,
                    affectedPaths = items.map { it.first }
                )
            }
    }

    private fun classifyDirectory(
        dir: File,
        rootPath: String,
        installedPackages: Set<String>?,
        userKeep: Set<String>,
        userClean: List<Regex>
    ): Category? = when {
        // A user keep entry overrides every rule below it — the whitelist is judged first, the
        // same order the reference applies (whitelist at listing time, filters after).
        CleanerPatternMatcher.isWhitelisted(dir.absolutePath, dir.name, userKeep) -> null
        // A user clean rule claims the directory wholesale, before the built-in rules get a say.
        // Protected names never reach here (the walk gates them), so Download or DCIM cannot be
        // aimed at — the built-in guard stands above user rules, not below them.
        CleanerPatternMatcher.matchesBlacklist(dir.absolutePath, userClean) -> Category.LOGS
        dir.name.equals("cache", ignoreCase = true) -> Category.CACHE
        dir.name.equals("thumbnails", ignoreCase = true) ||
            dir.name.equals(".thumbnails", ignoreCase = true) -> Category.THUMBNAILS
        dir.name.equals("temp", ignoreCase = true) ||
            dir.name.equals("tmp", ignoreCase = true) ||
            // The reference's filter_genericFolders carries the other two spellings ("Temporary",
            // "temporary"); matching case-insensitively reaches the same set in one test.
            dir.name.equals("temporary", ignoreCase = true) -> Category.TEMP
        installedPackages != null && isCorpse(dir, installedPackages) -> Category.CORPSES
        matchesBlacklist(dir.absolutePath, rootPath) -> Category.LOGS
        // Emptiness is deliberately not decided here: it depends on the whole subtree, which the
        // walk only knows once it has finished. See claimEmptyEntries.
        else -> null
    }

    /**
     * @return every installed package name, or null when we cannot enumerate them reliably.
     *   `pm list packages` runs with shell/root identity and is not subject to the calling
     *   app's package-visibility filter, unlike PackageManager.
     */
    private suspend fun resolveInstalledPackages(context: Context, shellRunner: ShellRunner): Set<String>? {
        if (!shellRunner.hasPrivilege()) return null
        val result = shellRunner.execResult("pm list packages")
        if (!result.isSuccess) return null
        val packages = result.stdout.lineSequence()
            .mapNotNull { line -> line.trim().removePrefix("package:").takeIf { it.isNotEmpty() } }
            .toSet()
        if (packages.isEmpty()) return null

        // Union with whatever PackageManager can see, so a truncated shell listing can never
        // cause a live app's data directory to be flagged as a corpse.
        val visible = runCatching {
            context.packageManager.getInstalledApplications(0).map { it.packageName }
        }.getOrDefault(emptyList())
        return packages + visible
    }

    private fun matchesBlacklist(path: String, rootPath: String): Boolean {
        // The patterns are written against the primary user's root. Normalising means they also
        // match on a secondary user or work profile, where the root is /storage/emulated/<userId>.
        val normalized = if (rootPath != PRIMARY_ROOT && path.startsWith(rootPath)) {
            PRIMARY_ROOT + path.removePrefix(rootPath)
        } else {
            path
        }
        return blacklistPatterns.any { it.matches(normalized) }
    }

    private fun isProtected(file: File): Boolean {
        val name = file.name.lowercase()
        return name in protectedNames || protectedFragments.any { name.contains(it) }
    }

    /**
     * @return true when [file] is a link rather than a real entry.
     *
     * `Files.isSymbolicLink` is one `lstat`, so it answers for the entry itself without resolving
     * anything. Comparing a file's canonical path against its absolute path — the obvious
     * alternative — is both slower and wrong here: on a device where the storage root is itself a
     * link, `getExternalStorageDirectory()` returning `/sdcard` which resolves to
     * `/storage/emulated/0`, every single entry under it looks like a symlink and the scan finds
     * nothing at all.
     */
    private fun isSymlink(file: File): Boolean = try {
        Files.isSymbolicLink(file.toPath())
    } catch (_: Exception) {
        true // Cannot tell — treat as unsafe.
    }

    private fun isCorpse(file: File, installed: Set<String>): Boolean {
        val parent = file.parentFile?.name ?: return false
        val grandParent = file.parentFile?.parentFile?.name ?: return false
        if (parent != "data" && parent != "obb") return false
        if (grandParent != "Android") return false
        // Only well-formed package names; a stray folder is not proof of an uninstalled app.
        if (!file.name.contains('.')) return false
        return file.name !in installed
    }

    /** Recursive byte total. `File.length()` on a directory reports the inode, not its contents. */
    private fun sizeOf(file: File): Long {
        if (isSymlink(file)) return 0L
        if (!file.isDirectory) return file.length()
        var total = 0L
        val stack = ArrayDeque<Pair<File, Int>>()
        stack.addLast(file to 0)
        while (stack.isNotEmpty()) {
            val (dir, depth) = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (child in children) {
                if (isSymlink(child)) continue
                if (child.isDirectory) {
                    if (depth < MAX_DEPTH) stack.addLast(child to depth + 1)
                } else {
                    total += child.length()
                }
            }
        }
        return total
    }

    /**
     * Deletes the scanned paths and reports what happened.
     *
     * Every figure below comes from a path that was measured immediately before deletion and whose
     * removal was then confirmed: `File.exists()` for paths this app can read, `test -e` through the
     * privileged shell for `Android/data` and `Android/obb`, which file APIs report as absent even
     * when they are there.
     */
    suspend fun clean(shellRunner: ShellRunner, results: List<ScanResult>): CleanResult =
        withContext(Dispatchers.IO) {
            val privileged = shellRunner.hasPrivilege()
            val channel = when {
                shellRunner.isRootAvailable() -> "Root"
                privileged -> "Shizuku"
                else -> "app file access only"
            }
            var trimmedInternalCaches = false
            if (privileged) {
                // Asks the framework to drop its own internal app caches. That space is not part of
                // the scanned paths, so it is deliberately left out of the freed total below.
                shellRunner.trimCaches()
                trimmedInternalCaches = true
            }

            var deleted = 0
            var failed = 0
            var unmeasured = 0
            var alreadyGone = 0
            var protectedSkips = 0
            var freedBytes = 0L

            for (result in results) {
                for (path in result.affectedPaths) {
                    coroutineContext.ensureActive()
                    val file = File(path)
                    if (isProtected(file)) {
                        // The scan already refuses to claim these, so this is a backstop. It is
                        // counted rather than skipped silently: an item that was listed and then
                        // not removed has to be accounted for somewhere.
                        protectedSkips++
                        continue
                    }

                    val visible = file.exists()
                    if (!visible && !(privileged && shellExists(shellRunner, path))) {
                        alreadyGone++
                        continue
                    }

                    val size = if (visible) sizeOf(file) else shellSize(shellRunner, path)
                    if (deleteWithFallback(file, shellRunner, privileged)) {
                        deleted++
                        if (size != null) freedBytes += size else unmeasured++
                    } else {
                        failed++
                    }
                }
            }

            CleanResult(
                deletedItems = deleted,
                freedBytes = freedBytes,
                unmeasuredItems = unmeasured,
                failedItems = failed,
                alreadyGoneItems = alreadyGone,
                protectedSkips = protectedSkips,
                trimmedInternalCaches = trimmedInternalCaches,
                channel = channel,
                privileged = privileged
            )
        }

    private suspend fun deleteWithFallback(file: File, shellRunner: ShellRunner, privileged: Boolean): Boolean {
        val direct = try {
            if (file.isDirectory) file.deleteRecursively() else file.delete()
        } catch (_: Exception) {
            false
        }
        // A partial recursive delete returns true with the directory still standing, so the
        // existence check is what decides, not the return value.
        if (direct && !file.exists()) return true
        if (!privileged) return false

        shellRunner.execSafe("rm", "-rf", file.absolutePath)
        // File.exists() answers "false" for Android/data whether or not the path is really gone,
        // so success has to be confirmed through the same channel that did the deleting.
        return !shellExists(shellRunner, file.absolutePath)
    }

    /** Existence as the privileged shell sees it, which is the only view valid for Android/data. */
    private suspend fun shellExists(shellRunner: ShellRunner, path: String): Boolean =
        shellRunner.execSafeResult("test", "-e", path).isSuccess

    /** @return size in bytes per `du -sk`, or null when it could not be measured. */
    private suspend fun shellSize(shellRunner: ShellRunner, path: String): Long? {
        val result = shellRunner.execSafeResult("du", "-sk", path)
        val line = result.stdout.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            ?: return null
        val match = DU_LINE.matchEntire(line) ?: return null
        return match.groupValues[1].toLongOrNull()?.times(1024L)
    }
}
