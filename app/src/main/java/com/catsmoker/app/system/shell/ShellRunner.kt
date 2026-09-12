package com.catsmoker.app.system.shell

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.catsmoker.app.BuildConfig
import com.catsmoker.app.IFileService
import com.topjohnwu.superuser.Shell
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.io.File
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/**
 * Single entry point for privileged execution. Prefers root, then Shizuku (an already-bound
 * user service, else the one-shot remote shell — see [execShizukuRemote]), then an
 * unprivileged shell. Never retries downward from root.
 */
@Singleton
class ShellRunner @Inject constructor(
    @ApplicationContext private val context: Context
) : Shizuku.OnRequestPermissionResultListener {

    /** Outcome of a command, including the exit code so callers can tell success from silence. */
    data class ExecResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    ) {
        val isSuccess: Boolean get() = exitCode == 0

        /** stdout when present, otherwise stderr — for surfacing a single line to the user. */
        val text: String get() = stdout.ifBlank { stderr }

        companion object {
            val FAILED = ExecResult(-1, "", "")
        }
    }

    private val _shizukuHasPermission = MutableStateFlow(false)
    val shizukuHasPermission = _shizukuHasPermission.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    @Volatile
    private var cachedRootAvailable: Boolean? = null

    @Volatile
    private var lastRootCheckTime = 0L

    @Volatile
    private var fileService: IFileService? = null

    /**
     * When the last bind attempt failed, until when to stop retrying. A dead Shizuku made
     * every command pay the full 3×(5 s timeout + 500 ms backoff) retry loop — the source of
     * the multi-second "apply" the save editor showed on no-privilege devices — for a bind
     * that cannot succeed until the user restarts Shizuku anyway.
     */
    @Volatile
    private var bindBlockedUntil: Long = 0L

    /** Non-null only while a bind is in flight; concurrent callers await the same result. */
    private var pendingBind: CompletableDeferred<IFileService?>? = null
    private val bindMutex = Mutex()

    /** Live connection object; used as an identity token so a stale callback cannot clobber it. */
    @Volatile
    private var activeConnection: ServiceConnection? = null

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { refreshShizukuPermission() }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        // Shizuku went away: the user service died with it, so drop the stale proxy — and
        // allow binding again, since a restarted Shizuku can accept a fresh bind.
        fileService = null
        activeConnection = null
        bindBlockedUntil = 0L
        _shizukuHasPermission.value = false
    }

    override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
        val granted = grantResult == PackageManager.PERMISSION_GRANTED
        _shizukuHasPermission.value = granted
        if (!granted) return
        // Root outranks Shizuku, so do not spawn the helper process when root can already do the
        // work. execResult() binds lazily if root turns out to be unavailable later.
        scope.launch(Dispatchers.IO) {
            if (!isRootAvailable()) {
                bindBlockedUntil = 0L // a fresh grant makes a bind attempt meaningful again
                bindUserService()
            }
        }
    }

    init {
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            // Sticky: fires immediately if the binder arrived before this singleton was created.
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(this)
            Shizuku.addRequestPermissionResultListener(this)
        } catch (_: Throwable) {
        }
    }

    // ---------------------------------------------------------------- privileges

    fun isRootAvailable(force: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        val cached = cachedRootAvailable
        if (!force && cached != null && (now - lastRootCheckTime) < ROOT_CHECK_COOLDOWN_MS) {
            return cached
        }

        val rooted = try {
            Shell.getShell().isRoot
        } catch (_: Throwable) {
            Shell.isAppGrantedRoot() == true
        }

        cachedRootAvailable = rooted
        lastRootCheckTime = now
        return rooted
    }

    fun hasPrivilege(): Boolean = isRootAvailable() || _shizukuHasPermission.value

    /**
     * Drops the cached user-service proxy so the next command rebinds from scratch. Callers
     * use this when a command came back channel-less while Shizuku's binder still answers —
     * the proxy is provably stale, and holding it only guarantees the next attempt fails the
     * same way. Not the same as [bindBlockedUntil]'s failure block: this one *enables* a
     * retry instead of suppressing it.
     */
    fun markShizukuServiceUnreachable() {
        fileService = null
        activeConnection = null
        bindBlockedUntil = 0L
    }

    fun refreshShizukuPermission() {
        if (!Shizuku.pingBinder()) {
            // Shizuku can be killed without the binder-dead callback ever firing.
            fileService = null
            activeConnection = null
            _shizukuHasPermission.value = false
            return
        }
        try {
            val granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            _shizukuHasPermission.value = granted
            if (!granted) {
                fileService = null
                return
            }
            // The root probe forks a shell, so it must not run on the caller's thread.
            scope.launch(Dispatchers.IO) {
                if (!isRootAvailable()) {
                    bindBlockedUntil = 0L // binder is alive and permission granted — retry is meaningful
                    bindUserService()
                }
            }
        } catch (_: Throwable) {
            _shizukuHasPermission.value = false
        }
    }

    // ------------------------------------------------------------- user service

    private fun userServiceArgs() = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, FILE_SERVICE_CLASS)
    )
        // daemon(true) keeps the helper alive across app restarts, so later binds are instant.
        .daemon(true)
        .processNameSuffix("service")
        .tag(USER_SERVICE_TAG)
        // Bumping versionCode forces Shizuku to restart the helper instead of reusing an
        // old process whose AIDL no longer matches ours.
        .version(BuildConfig.VERSION_CODE)
        .debuggable(BuildConfig.DEBUG)

    /**
     * Binds the user service, retrying a few times. Concurrent callers share one attempt.
     * @return the live proxy, or null when Shizuku is unavailable or the bind never completed.
     */
    private suspend fun bindUserService(): IFileService? {
        fileService?.let { if (Shizuku.pingBinder()) return it else fileService = null }
        if (!_shizukuHasPermission.value || !Shizuku.pingBinder()) return null
        // A bind that just failed cannot succeed again until Shizuku itself restarts (the
        // dead-listener clears this); stop paying the retry loop on every command meanwhile.
        if (System.currentTimeMillis() < bindBlockedUntil) return null

        val deferred: CompletableDeferred<IFileService?>
        var isOwner = false
        bindMutex.withLock {
            deferred = pendingBind ?: CompletableDeferred<IFileService?>().also {
                pendingBind = it
                isOwner = true
            }
        }
        // Someone else is already binding — just wait for their outcome.
        if (!isOwner) return deferred.await()

        try {
            var bound: IFileService? = null
            for (attempt in 1..MAX_BIND_RETRIES) {
                // Completes the moment this attempt's onServiceConnected fires — the callback
                // is the fast wake, and the deferred must exist before the connection object
                // that closes over it.
                val pendingConnected = CompletableDeferred<IFileService?>()
                val connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                        if (activeConnection !== this) return
                        fileService = service?.let { IFileService.Stub.asInterface(it) }
                        pendingConnected.complete(fileService)
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        if (activeConnection !== this) return
                        fileService = null
                    }
                }
                activeConnection = connection

                val requested = try {
                    Shizuku.bindUserService(userServiceArgs(), connection)
                    true
                } catch (t: Throwable) {
                    Log.w(TAG, "bindUserService threw on attempt $attempt", t)
                    false
                }

                if (requested) {
                    // The callback path is the fast wake (await returns the moment
                    // onServiceConnected completes the deferred); the poll loop is the safety
                    // net for a callback that never fires. Either one returns the proxy.
                    bound = withTimeoutOrNull(BIND_TIMEOUT_MS) {
                        pendingConnected.await()
                        fileService
                    }
                    if (bound != null) break
                }

                if (attempt < MAX_BIND_RETRIES) delay(BIND_RETRY_DELAY_MS)
            }
            if (bound == null) {
                Log.w(TAG, "Shizuku user service did not bind after $MAX_BIND_RETRIES attempts")
                bindBlockedUntil = System.currentTimeMillis() + BIND_BLOCK_MS.inWholeMilliseconds
            }
            deferred.complete(bound)
            return bound
        } catch (t: Throwable) {
            deferred.complete(null)
            return null
        } finally {
            bindMutex.withLock { if (pendingBind === deferred) pendingBind = null }
        }
    }

    // ------------------------------------------------------------------ execute

    /** Joins [args] into a command line, quoting any argument that needs it. */
    private fun joinArgs(args: Array<out String>): String = args.joinToString(" ") { arg ->
        if (arg.isEmpty() || arg.any { it.isWhitespace() || it in SHELL_METACHARACTERS }) {
            "'" + arg.replace("'", "'\\''") + "'"
        } else {
            arg
        }
    }

    /** Executes a command with each argument safely quoted. */
    suspend fun execSafe(vararg args: String): String = exec(joinArgs(args))

    /** Executes a command with each argument safely quoted, reporting the exit code. */
    suspend fun execSafeResult(vararg args: String): ExecResult = execResult(joinArgs(args))

    suspend fun exec(command: String): String = execResult(command).stdout

    /**
     * Runs [command] through the best available channel and reports stdout, stderr and the
     * exit code. Prefer this over [exec] whenever success matters: many `settings put` and
     * `cmd` invocations succeed with completely empty stdout.
     */
    suspend fun execResult(command: String): ExecResult = withContext(Dispatchers.IO) {
        if (isRootAvailable()) {
            val result = runCatching { Shell.cmd(command).exec() }.getOrNull()
            if (result != null) {
                if (!result.isSuccess) Log.w(TAG, "Root exec failed (exit ${result.code}): $command")
                // Root is the highest privilege we have; retrying with less cannot help.
                return@withContext ExecResult(result.code, result.out.joinToString("\n"), "")
            }
        }

        bindUserService()?.let { service ->
            try {
                val remote = service.executeForResult(arrayOf("sh", "-c", command))
                return@withContext ExecResult(
                    exitCode = remote.exitCode,
                    stdout = remote.output.orEmpty(),
                    stderr = remote.error.orEmpty()
                )
            } catch (t: Throwable) {
                Log.w(TAG, "Shizuku service exec failed, dropping proxy", t)
                fileService = null
            }
        }

        // No bound user service. The reference's own channel needs none: BattleGrounds_GFX
        // (referance/gamingtools/BattleGrounds_GFX-main MainActivity2/3 executeShellCommand)
        // forks every command through Shizuku's one-shot remote process — shell UID, no
        // helper to bind, so a helper that refuses to start cannot take the command down.
        execShizukuRemote(command)?.let { return@withContext it }

        val result = runCatching { Shell.cmd(command).exec() }.getOrNull()
            ?: return@withContext ExecResult.FAILED
        if (!result.isSuccess) {
            // FLAG_REDIRECT_STDERR folds stderr into stdout, so result.err is empty here.
            Log.w(TAG, "Unprivileged shell failed (exit ${result.code}): $command")
        }
        ExecResult(result.code, result.out.joinToString("\n"), "")
    }

    /**
     * One-shot command in Shizuku's own process (shell UID) via the private `Shizuku.newProcess`
     * — the same reflective call BattleGrounds_GFX uses, fed [stdin] before stdout is read.
     * This needs only the Shizuku binder and the permission grant: no user-service spawn, no
     * bind handshake, no daemonized helper to go stale, which is exactly why it works where
     * [bindUserService] reports "helper could not be started" (a version-gated or wedged
     * helper leaves this channel fully alive). Both pipes are drained concurrently and the
     * wait is bounded — a wedged remote process must not hold a binder thread, the same rule
     * FileService's KDoc states for its service. Binary-safe for [readFileDirect]: the
     * trimmed stdout travels back as ISO-8859-1, a byte-for-byte mapping, so a save's GVAS
     * bytes are not mangled by any text decode. Null when the binder is gone, the permission
     * is missing, or the call itself fails.
     */
    private fun execShizukuRemoteRaw(command: String, stdin: ByteArray): ExecResult? {
        if (!Shizuku.pingBinder()) return null
        if (runCatching { Shizuku.checkSelfPermission() }.getOrNull() != PackageManager.PERMISSION_GRANTED) return null
        val newProcess: Method = runCatching {
            Shizuku::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
            ).apply { isAccessible = true }
        }.getOrNull() ?: return null

        return try {
            val process = newProcess.invoke(null, arrayOf("sh", "-c", command), null, null)
                as? java.lang.Process ?: return null
            // Both pipes must be drained concurrently; reading stdout to EOF first deadlocks
            // the moment stderr fills its 64 KB buffer.
            val stderr = StringBuilder()
            val errDrainer = Thread {
                runCatching { process.errorStream.bufferedReader().readText() }
                    .onSuccess { stderr.append(it) }
            }.apply { isDaemon = true; start() }
            val stdout = try {
                process.outputStream.use { it.write(stdin) }
                process.inputStream.bufferedReader().readText()
            } catch (_: Exception) {
                ""
            }
            val finished = process.waitFor(REMOTE_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) process.destroy()
            errDrainer.join(DRAIN_JOIN_MILLIS)
            val exitCode = if (finished) process.exitValue() else -1
            if (exitCode != 0) {
                Log.w(TAG, "Shizuku remote exec exit $exitCode: ${stderr.toString().ifBlank { "no stderr" }}")
            }
            ExecResult(exitCode, stdout.trimEnd('\n'), stderr.toString().trimEnd('\n'))
        } catch (t: Throwable) {
            Log.w(TAG, "Shizuku remote exec threw", t)
            null
        }
    }

    suspend fun trimCaches() {
        exec("pm trim-caches 4G")
    }

    // -------------------------------------------------------------- file bytes

    /**
     * Reads [path] as bytes through Shizuku. The user service is first — one binder call, no
     * fork; the remote-shell channel is the fallback when the helper cannot start. Binary-safe
     * by construction: the service returns the file's own bytes, and the remote channel pipes
     * them through stdin (not a shell variable or a text pipe that mangles non-UTF8), which is
     * why a save's GVAS bytes survive `cat > stage` + `cp` untouched — the same move
     * BattleGrounds_GFX makes with its whole-save template push.
     *
     * Three outcomes, kept distinct for the caller:
     * - non-null, non-empty — the file's bytes;
     * - non-null, **empty** — the channel answered but nothing was read: the file is missing
     *   or unreadable (`cat` exits 1 on both). This is the existence probe — callers must not
     *   read it as "no channel";
     * - null — no channel answered at all (binder gone, permission missing, reflection dead).
     *
     * [forceBind] makes a blocked bind pay one attempt — used by the save editor's READ step,
     * where "the helper wasn't up yet" must not read as "Shizuku is broken".
     */
    suspend fun readFileDirect(path: String, forceBind: Boolean = false): ByteArray? = withContext(Dispatchers.IO) {
        if (forceBind) bindBlockedUntil = 0L
        runCatching { bindUserService()?.readFile(path) }.getOrNull()
            ?: execShizukuRemoteRaw("cat ${joinArgs(arrayOf(path))}", ByteArray(0))?.let { result ->
                if (result.isSuccess) {
                    result.stdout.toByteArray(Charsets.ISO_8859_1)
                } else {
                    // The channel ran and the read failed — a missing (or unreadable) file,
                    // not a dead channel. Empty is the caller's "nothing there" signal.
                    ByteArray(0)
                }
            }
    }

    /**
     * Writes [bytes] over [path]; the boolean is the channel's own flush answer, not just "a
     * command was sent". The user service writes and fsyncs inside its own process; the
     * remote-shell fallback stages the bytes in /data/local/tmp (shell-uid-writable, the same
     * staging file BattleGrounds_GFX uses) and `cp`s them over the target — the cp exit code
     * is that channel's report. Null when no service is bound at all (no answer), distinct
     * from a false (the channel refused).
     */
    suspend fun writeFileDirect(path: String, bytes: ByteArray): Boolean? = withContext(Dispatchers.IO) {
        runCatching { bindUserService()?.writeFile(path, bytes) }.getOrNull()
            ?: writeFileViaRemoteShell(path, bytes)
    }

    /**
     * The remote-shell write path. A null return means the channel never answered (binder
     * gone, permission missing); false means it answered and refused — the caller reports
     * those differently. The staging file is unlinked in a finally so no shell-owned leftover
     * accumulates in /data/local/tmp.
     */
    private suspend fun writeFileViaRemoteShell(path: String, bytes: ByteArray): Boolean? {
        val stage = "/data/local/tmp/catsmoker_write_" + System.currentTimeMillis() + ".tmp"
        val write = execShizukuRemoteRaw("cat > $stage", bytes)
            ?: return null // binder/permission never answered — no channel at all
        if (!write.isSuccess) return false
        val copy = execShizukuRemote("cp -f ${joinArgs(arrayOf(stage))} ${joinArgs(arrayOf(path))}")
        execShizukuRemote("rm -f ${joinArgs(arrayOf(stage))}")
        return copy?.isSuccess
    }

    /** [execShizukuRemoteRaw] with no stdin — the plain remote `sh -c` path. */
    private fun execShizukuRemote(command: String): ExecResult? =
        execShizukuRemoteRaw(command, ByteArray(0))

    // ------------------------------------------------------------------ thermal

    private enum class ThermalStrategy { SYSFS_DIRECT, DUMPSYS, SERVICE_SYSFS, SHELL_SYSFS }

    /** Cached winning strategy; reset to null whenever it stops producing readings. */
    @Volatile
    private var resolvedThermalStrategy: ThermalStrategy? = null

    /** Discovered (type, temp) sysfs file pairs, cached after the first successful sweep. */
    @Volatile
    private var sysfsZones: List<Pair<File, File>>? = null

    /**
     * Reads thermal sensors, preferring whichever channel worked last time.
     * Direct sysfs comes first because it needs no privileges and no process fork.
     *
     * @return raw text for [com.catsmoker.app.features.main.engine.parsers.ThermalServiceParser],
     *   or an empty string when no channel produced anything.
     */
    suspend fun readThermal(): String = withContext(Dispatchers.IO) {
        resolvedThermalStrategy?.let { cached ->
            val output = runThermalStrategy(cached)
            if (output.isNotBlank()) return@withContext output
            // The cached path went quiet (SELinux change, HAL restart) — re-resolve next time.
            resolvedThermalStrategy = null
        }

        for (strategy in ThermalStrategy.entries) {
            val output = runThermalStrategy(strategy)
            if (output.isNotBlank()) {
                resolvedThermalStrategy = strategy
                return@withContext output
            }
        }
        ""
    }

    private suspend fun runThermalStrategy(strategy: ThermalStrategy): String = when (strategy) {
        ThermalStrategy.SYSFS_DIRECT -> readSysfsDirect()
        ThermalStrategy.DUMPSYS -> if (hasPrivilege()) exec("dumpsys thermalservice") else ""
        ThermalStrategy.SERVICE_SYSFS -> if (isRootAvailable()) {
            // Root can read the zones directly through SHELL_SYSFS; binding the Shizuku helper
            // would spawn a second privileged process for nothing.
            ""
        } else {
            runCatching { bindUserService()?.readSysfsThermal().orEmpty() }.getOrDefault("")
        }
        ThermalStrategy.SHELL_SYSFS -> if (hasPrivilege()) {
            exec(
                "for z in /sys/class/thermal/thermal_zone*; do " +
                    "echo \"\$(cat \$z/type 2>/dev/null):\$(cat \$z/temp 2>/dev/null)\"; done"
            )
        } else {
            ""
        }
    }

    private fun readSysfsDirect(): String {
        val zones = sysfsZones ?: discoverSysfsZones().also { sysfsZones = it }
        if (zones.isEmpty()) return ""
        val sb = StringBuilder()
        for ((typeFile, tempFile) in zones) {
            val type = runCatching { typeFile.readText().trim() }.getOrNull()?.ifEmpty { null } ?: continue
            val temp = runCatching { tempFile.readText().trim() }.getOrNull()?.ifEmpty { null } ?: continue
            sb.append(type).append(':').append(temp).append('\n')
        }
        if (sb.isEmpty()) sysfsZones = null // Permissions changed; rediscover next call.
        return sb.toString()
    }

    private fun discoverSysfsZones(): List<Pair<File, File>> = try {
        File("/sys/class/thermal")
            .listFiles { f -> f.isDirectory && f.name.startsWith("thermal_zone") }
            ?.sortedBy { it.name }
            ?.mapNotNull { zone ->
                val type = File(zone, "type")
                val temp = File(zone, "temp")
                if (type.canRead() && temp.canRead()) type to temp else null
            }
            .orEmpty()
    } catch (_: Throwable) {
        emptyList()
    }

    // --------------------------------------------------------------- /proc/stat

    private enum class ProcStatStrategy { DIRECT, ROOT_SHELL, SERVICE, SHELL }

    @Volatile
    private var resolvedProcStatStrategy: ProcStatStrategy? = null

    /**
     * Reads /proc/stat, which SELinux hides from untrusted_app on most Android 10+ builds.
     *
     * Order is cheapest-and-highest-privilege first: a plain read when SELinux allows it, then a
     * root shell, then — only when there is no root — the Shizuku binder, which avoids a fork but
     * costs a helper process.
     *
     * @return the file contents, or an empty string when no channel can reach it.
     */
    suspend fun readProcStat(): String = withContext(Dispatchers.IO) {
        resolvedProcStatStrategy?.let { cached ->
            val output = runProcStatStrategy(cached)
            if (output.isNotBlank()) return@withContext output
            resolvedProcStatStrategy = null
        }

        for (strategy in ProcStatStrategy.entries) {
            val output = runProcStatStrategy(strategy)
            if (output.isNotBlank()) {
                resolvedProcStatStrategy = strategy
                return@withContext output
            }
        }
        ""
    }

    private suspend fun runProcStatStrategy(strategy: ProcStatStrategy): String = when (strategy) {
        ProcStatStrategy.DIRECT -> runCatching { File(PROC_STAT).readText() }.getOrDefault("")
        // exec() routes through root when it is available, so this is the root channel.
        ProcStatStrategy.ROOT_SHELL -> if (isRootAvailable()) exec("cat $PROC_STAT") else ""
        ProcStatStrategy.SERVICE -> if (isRootAvailable()) {
            ""
        } else {
            runCatching { bindUserService()?.readProcStat().orEmpty() }.getOrDefault("")
        }
        ProcStatStrategy.SHELL -> if (hasPrivilege()) exec("cat $PROC_STAT") else ""
    }

    // ---------------------------------------------------------------- lifecycle

    /**
     * Best-effort cancellation of the long-running privileged work we spawn (the ART dexopt
     * sweep). Both libsu and the Shizuku binder call are blocking, so the only lever we have
     * is to kill the compiler process the platform forked on our behalf.
     */
    suspend fun killCurrentProcess() {
        if (!hasPrivilege()) return
        for (pattern in COMPILE_PROCESS_PATTERNS) {
            exec("pkill -f $pattern")
        }
    }

    private companion object {
        const val TAG = "ShellRunner"
        const val FILE_SERVICE_CLASS = "com.catsmoker.app.features.editgamefiles.service.FileService"
        const val USER_SERVICE_TAG = "catsmoker_file_service"
        const val ROOT_CHECK_COOLDOWN_MS = 2000L
        const val PROC_STAT = "/proc/stat"
        // One attempt, short timeout. This used to be 3×5 s, back when the user service was the
        // only Shizuku channel — a wedged helper then cost every command up to 16 s before
        // failing. With execShizukuRemote as the live fallback, retries only delay the channel
        // that actually works: a healthy daemon(true) helper answers a bind in milliseconds, so
        // one 3 s attempt distinguishes "alive" from "wedged" just as well.
        const val MAX_BIND_RETRIES = 1
        val BIND_TIMEOUT_MS = 3000.milliseconds
        val BIND_RETRY_DELAY_MS = 500.milliseconds
        val BIND_POLL_MS = 50.milliseconds
        /** How long a failed bind stops further attempts — see [bindBlockedUntil]. */
        val BIND_BLOCK_MS = 10_000.milliseconds
        val REMOTE_COMMAND_TIMEOUT_SECONDS = 30L
        val DRAIN_JOIN_MILLIS = 1000L
        val SHELL_METACHARACTERS = charArrayOf(
            '"', '\'', '$', '`', '\\', '!', '*', '?', '[', ']', '(', ')', '{', '}',
            '|', '&', ';', '<', '>', '~', '#'
        )
        val COMPILE_PROCESS_PATTERNS = listOf("dex2oat", "dex2oat64")
    }
}
