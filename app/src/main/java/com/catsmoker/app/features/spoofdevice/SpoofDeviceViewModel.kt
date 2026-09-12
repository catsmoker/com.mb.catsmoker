package com.catsmoker.app.features.spoofdevice

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import androidx.core.content.edit
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.catsmoker.app.BuildConfig
import com.catsmoker.app.R
import com.catsmoker.app.features.gamingtools.engine.DisplayRefreshRateProvider
import com.catsmoker.app.features.spoofdevice.tools.MagiskModuleBuilder
import com.catsmoker.app.shared.data.model.DevicePreset
import com.catsmoker.app.shared.data.model.DeviceProfile
import com.catsmoker.app.shared.data.model.LSPosedConfig
import com.catsmoker.app.shared.data.repository.SpoofRepository
import com.catsmoker.app.shared.util.RandomGenerator
import com.catsmoker.app.system.shell.ShellRunner
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class SpoofDeviceViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shellRunner: ShellRunner,
    // The panel's peak decides which rung of a package's frame-rate ladder wins, so the refresh
    // reader is injected here too — the display-fact owners are split by question, not by caller.
    private val displayRefreshRate: DisplayRefreshRateProvider,
    val repository: SpoofRepository
) : ViewModel() {

    data class AppEntry(
        val label: String,
        val packageName: String,
        val icon: Bitmap,
        val systemApp: Boolean,
        val assignedProfileName: String? = null
    )

    data class UiState(
        val isRooted: Boolean = false,
        val isRefreshing: Boolean = false,
        /** True while the Magisk ZIP is being built + written, so the card can show busy. */
        val isGeneratingMagisk: Boolean = false,
        /** False until the store has actually been read, so an empty list is not read as "none". */
        val storeLoaded: Boolean = false,
        val profiles: List<SpoofRepository.ProfileEntry> = emptyList(),
        val assignments: Map<String, String> = emptyMap(),
        /** Per-package frame-rate ladders; a non-empty one owns the package over [assignments]. */
        val rateAssignments: Map<String, List<SpoofRepository.RateCandidate>> = emptyMap(),
        /**
         * The panel's measured peak in Hz, as the ladder picker read it. Zero until the first read,
         * so the UI can decline to name a winner rather than guess one.
         */
        val panelPeakHz: Float = 0f,
        val apps: List<AppEntry> = emptyList(),
        val isLoadingApps: Boolean = false,
        val safeModePackages: Set<String> = emptySet(),
        val applyScreenMetrics: Boolean = false
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()

    private val deviceContext by lazy { context.createDeviceProtectedStorageContext() }
    private val userPrefs by lazy { openLsposedPrefs(context) }
    private val devicePrefs by lazy { openLsposedPrefs(deviceContext) }

    init {
        refreshStatus()
        observeStore()
    }

    /**
     * Mirrors every published store snapshot into [uiState].
     *
     * This single collector is what makes a newly created profile appear straight away: the
     * repository publishes a whole new snapshot per change, so no call site has to remember to
     * re-emit the list — and none can accidentally publish a list it has already mutated in place,
     * which is what previously left the screen showing stale profiles.
     */
    private fun observeStore() {
        viewModelScope.launch {
            repository.state.collect { data ->
                if (data == null) return@collect
                _uiState.update { state ->
                    // Re-read with every snapshot: the peak is what picks the ladder winner, and a
                    // foldable posture change or display switch must re-pick on the next publish.
                    val panelPeakHz = displayRefreshRate.getMaxHardwareRefreshRate()
                    state.copy(
                        storeLoaded = true,
                        profiles = data.profiles,
                        assignments = data.assignments,
                        rateAssignments = data.rateAssignments,
                        panelPeakHz = panelPeakHz,
                        safeModePackages = parseSafeModePackages(
                            data.globalProperties[LSPosedConfig.KEY_SAFE_MODE_PACKAGES]
                        ),
                        applyScreenMetrics = LSPosedConfig.isFlagEnabled(
                            data.globalProperties[LSPosedConfig.KEY_APPLY_SCREEN_METRICS]
                        ),
                        // Assignment labels are derived here, so they cannot drift from the store.
                        apps = state.apps.map { app ->
                            val assigned = assignedLabelFor(data, app.packageName, panelPeakHz)
                            if (app.assignedProfileName == assigned) app
                            else app.copy(assignedProfileName = assigned)
                        }
                    )
                }
            }
        }
        // The flow only carries a snapshot once something has read the file.
        viewModelScope.launch { repository.loadData() }
    }

    /**
     * The label an app row shows: the ladder winner with its tier when the package has a ladder,
     * otherwise the plainly assigned profile's name.
     *
     * The winner shown is the one the picker would actually publish for the same peak, so the row
     * can never claim a rung the panel would not earn.
     */
    private fun assignedLabelFor(
        data: SpoofRepository.StoreData,
        packageName: String,
        panelPeakHz: Float
    ): String? {
        val ladder = data.rateAssignments[packageName].orEmpty()
            .filter { candidate -> data.profiles.any { it.id == candidate.profileId } }
        if (ladder.isNotEmpty() && panelPeakHz > 0f) {
            val winner = SpoofRepository.pickRateCandidate(ladder, panelPeakHz)
            val name = winner?.let { w -> data.profiles.firstOrNull { it.id == w.profileId }?.name }
            if (name != null && winner != null) return context.getString(
                R.string.spoof_ladder_rung, name, winner.rateHz
            )
            return context.getString(R.string.spoof_ladder_label)
        }
        return data.assignments[packageName]?.let { id ->
            data.profiles.firstOrNull { it.id == id }?.name
        }
    }

    /** Same split the reference uses, so a config written by either side parses identically. */
    private fun parseSafeModePackages(raw: String?): Set<String> =
        raw?.split(',', '\n')?.map(String::trim)?.filter(String::isNotEmpty)?.toSet() ?: emptySet()

    /**
     * Runs a store mutation and reports a persistence failure rather than letting the UI imply the
     * change was saved.
     *
     * @return true when the new snapshot reached disk and was published.
     */
    private suspend fun mutateStore(
        transform: (SpoofRepository.StoreData) -> SpoofRepository.StoreData
    ): Boolean =
        try {
            repository.update(transform)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _toasts.tryEmit(
                context.getString(
                    R.string.spoof_error_save,
                    e.message ?: context.getString(R.string.spoof_error_write_failed)
                )
            )
            false
        }

    /**
     * Re-probes root and reports what the shell actually answered.
     *
     * The probe is forced. [ShellRunner.isRootAvailable] serves a cached verdict for
     * [ShellRunner.ROOT_CHECK_COOLDOWN_MS] so the many callers that ask per command do not each pay
     * for a shell round-trip — but this one is user-initiated, and an unforced call made it a lie:
     * a user who granted root in Magisk after the first probe got the stale `false` back plus a
     * "Status Refreshed" toast, and "Open Root Manager" stayed hidden with no way to reveal it.
     * [com.catsmoker.app.features.permissions.PermissionViewModel] forces for the same reason.
     */
    fun refreshStatus() {
        _uiState.update { it.copy(isRefreshing = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val rooted = shellRunner.isRootAvailable(force = true)
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(isRooted = rooted, isRefreshing = false) }
                // Naming the verdict is the point of the refresh: "Status Refreshed" alone left a
                // user staring at a hidden root-only button with no idea the probe said no.
                _toasts.tryEmit(
                    if (rooted) context.getString(R.string.spoof_root_available)
                    else context.getString(R.string.spoof_root_unavailable)
                )
            }
        }
    }

    // --- Profile Management ---

    fun createProfile(name: String, preset: DevicePreset? = null) {
        viewModelScope.launch {
            val profileName = name.trim()
            if (profileName.isEmpty()) {
                _toasts.tryEmit(context.getString(R.string.spoof_error_name_empty))
                return@launch
            }
            val newProfile = preset?.profile?.copy() ?: DeviceProfile().apply { applyFallbacks() }
            val entry = SpoofRepository.ProfileEntry(
                UUID.randomUUID().toString(),
                profileName,
                newProfile
            )
            // A new list rather than an in-place add: the published snapshot genuinely differs, so
            // the collector pushes it to the list screen without waiting for a navigation.
            if (mutateStore { it.copy(profiles = it.profiles + entry) }) {
                _toasts.tryEmit(context.getString(R.string.spoof_toast_created, profileName))
            }
        }
    }

    fun updateProfile(profileId: String, name: String, profile: DeviceProfile) {
        viewModelScope.launch {
            val profileName = name.trim()
            if (profileName.isEmpty()) {
                _toasts.tryEmit(context.getString(R.string.spoof_error_name_empty))
                return@launch
            }
            if (repository.loadData().profiles.none { it.id == profileId }) {
                _toasts.tryEmit(context.getString(R.string.spoof_error_gone))
                return@launch
            }
            val saved = mutateStore { current ->
                current.copy(
                    profiles = current.profiles.map { entry ->
                        if (entry.id == profileId) {
                            SpoofRepository.ProfileEntry(profileId, profileName, profile)
                        } else {
                            entry
                        }
                    }
                )
            }
            if (saved) {
                syncLsposedConfig()
                _toasts.tryEmit(context.getString(R.string.spoof_toast_updated))
            }
        }
    }

    fun deleteProfile(profileId: String) {
        viewModelScope.launch {
            val data = repository.loadData()
            if (data.profiles.size <= 1) {
                _toasts.tryEmit(context.getString(R.string.spoof_error_last))
                return@launch
            }
            if (data.profiles.firstOrNull()?.id == profileId) {
                _toasts.tryEmit(context.getString(R.string.spoof_error_default))
                return@launch
            }
            val saved = mutateStore { current ->
                current.copy(
                    profiles = current.profiles.filterNot { it.id == profileId },
                    // An assignment names a profile by id, so it cannot outlive the profile — and
                    // so does every rung of every frame-rate ladder, with an emptied ladder
                    // removed entirely rather than left as a package key resolving to nothing.
                    assignments = current.assignments.filterValues { it != profileId },
                    rateAssignments = current.rateAssignments
                        .mapValues { (_, ladder) -> ladder.filterNot { it.profileId == profileId } }
                        .filterValues { it.isNotEmpty() }
                )
            }
            if (saved) {
                syncLsposedConfig()
                _toasts.tryEmit(context.getString(R.string.spoof_toast_deleted))
            }
        }
    }

    // --- App Assignments ---

    fun loadApps() {
        if (_uiState.value.apps.isNotEmpty()) return
        _uiState.update { it.copy(isLoadingApps = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val pm = context.packageManager
            val packages = pm.getInstalledPackages(0)
            val apps = packages.mapNotNull { pkg ->
                val appInfo = pkg.applicationInfo ?: return@mapNotNull null
                if (pkg.packageName == context.packageName || pkg.packageName == "android") return@mapNotNull null
                
                AppEntry(
                    label = appInfo.loadLabel(pm).toString(),
                    packageName = pkg.packageName,
                    icon = appInfo.loadIcon(pm).toBitmap(96, 96),
                    systemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                )
            }.sortedBy { it.label }

            withContext(Dispatchers.Main) {
                // Labels come from the same derivation the store collector uses, so a ladder
                // package shows its winner here too rather than looking unassigned until the next
                // store change.
                val data = repository.state.value
                _uiState.update { state ->
                    state.copy(
                        isLoadingApps = false,
                        apps = apps.map { app ->
                            app.copy(
                                assignedProfileName = data
                                    ?.let { assignedLabelFor(it, app.packageName, state.panelPeakHz) }
                            )
                        }
                    )
                }
            }
        }
    }

    fun assignProfile(packageName: String, profileId: String?) {
        viewModelScope.launch {
            val saved = mutateStore { current ->
                val assignments = LinkedHashMap(current.assignments)
                if (profileId == null) {
                    assignments.remove(packageName)
                } else {
                    assignments[packageName] = profileId
                }
                current.copy(
                    assignments = assignments,
                    // A single explicit choice replaces the ladder it would otherwise be shadowed
                    // by — "this one profile" and "a rung per tier" are different answers, and
                    // leaving both would let a stale ladder win after the user picked the other.
                    rateAssignments = current.rateAssignments - packageName
                )
            }
            // The collector re-derives every app's label from the new snapshot.
            if (saved) syncLsposedConfig()
        }
    }

    /**
     * Adds one rung to a package's frame-rate ladder: the profile to show the game when the
     * panel's peak earns [rateHz].
     *
     * The reference's `rate` can be an array (`[90,120]`) on one model; here that is spelled out
     * as the same profile added at each rate it covers, which the pick rule treats identically.
     * Adding a pair that already exists is a no-op, and the package's plain assignment is cleared
     * because a non-empty ladder owns the package.
     */
    fun assignRateCandidate(packageName: String, profileId: String, rateHz: Int) {
        viewModelScope.launch {
            if (rateHz <= 0) {
                _toasts.tryEmit(context.getString(R.string.spoof_error_rate))
                return@launch
            }
            if (repository.loadData().profiles.none { it.id == profileId }) {
                _toasts.tryEmit(context.getString(R.string.spoof_error_gone))
                return@launch
            }
            val saved = mutateStore { current ->
                val ladder = current.rateAssignments[packageName].orEmpty()
                    .filterNot { it.profileId == profileId && it.rateHz == rateHz } +
                    SpoofRepository.RateCandidate(profileId, rateHz)
                current.copy(
                    rateAssignments = current.rateAssignments + (packageName to ladder),
                    assignments = current.assignments - packageName
                )
            }
            if (saved) syncLsposedConfig()
        }
    }

    /** Removes one rung (profile *and* rate identify it); the last rung removes the ladder. */
    fun removeRateCandidate(packageName: String, profileId: String, rateHz: Int) {
        viewModelScope.launch {
            val saved = mutateStore { current ->
                val ladder = current.rateAssignments[packageName].orEmpty()
                    .filterNot { it.profileId == profileId && it.rateHz == rateHz }
                val rateAssignments =
                    if (ladder.isEmpty()) current.rateAssignments - packageName
                    else current.rateAssignments + (packageName to ladder)
                current.copy(rateAssignments = rateAssignments)
            }
            if (saved) syncLsposedConfig()
        }
    }

    // --- Global Config ---

    fun toggleScreenMetrics(enabled: Boolean) {
        viewModelScope.launch {
            val saved = mutateStore { current ->
                current.copy(
                    globalProperties = current.globalProperties +
                        (LSPosedConfig.KEY_APPLY_SCREEN_METRICS to enabled.toString())
                )
            }
            if (saved) syncLsposedConfig()
        }
    }

    fun toggleSafeMode(packageName: String, enabled: Boolean) {
        viewModelScope.launch {
            val saved = mutateStore { current ->
                val packages = parseSafeModePackages(
                    current.globalProperties[LSPosedConfig.KEY_SAFE_MODE_PACKAGES]
                ).toMutableSet()

                if (enabled) packages.add(packageName) else packages.remove(packageName)

                val globals = LinkedHashMap(current.globalProperties)
                // Dropping the key when nothing is listed keeps the rendered config clean, the same
                // way the reference's updateSafeModePackages does.
                if (packages.isEmpty()) {
                    globals.remove(LSPosedConfig.KEY_SAFE_MODE_PACKAGES)
                } else {
                    globals[LSPosedConfig.KEY_SAFE_MODE_PACKAGES] = packages.joinToString(",")
                }
                current.copy(globalProperties = globals)
            }
            // Safe mode rides along inside every rendered profile, so the hooks need the republish.
            if (saved) syncLsposedConfig()
        }
    }

    // --- Legacy / Root Support ---

    /**
     * Exports the first stored profile as a flashable Magisk module.
     *
     * The module carries the profile's **model identity only** — four `*.model` keys plus the
     * PixelProps game switch — not the profile. See [MagiskModuleBuilder.MODEL_KEYS]: this text goes
     * to `resetprop` before the framework starts, and a device-wide `ro.hardware` or
     * `ro.product.cpu.abi` that disagrees with the real silicon costs a boot rather than a detection.
     * The in-process channels still deliver the whole profile, so nothing the user configured is
     * lost — it is delivered by the path that cannot brick anything.
     *
     * Each refusal is reported rather than swallowed: the old body returned silently when no profile
     * existed, leaving the button looking broken.
     */
    fun installBundledMagiskZip() {
        if (_uiState.value.isGeneratingMagisk) return
        _uiState.update { it.copy(isGeneratingMagisk = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val data = repository.loadData()
                val entry = data.profiles.firstOrNull()
                if (entry == null) {
                    _toasts.emit(context.getString(R.string.spoof_error_no_profile))
                    return@launch
                }

                val rendered = repository.renderConfig(entry.profile, data.globalProperties)
                val spec = MagiskModuleBuilder.ModuleSpec(
                    systemProperties = LSPosedConfig.filterToSystemProperties(rendered),
                    versionName = BuildConfig.VERSION_NAME,
                    versionCode = BuildConfig.VERSION_CODE,
                    profileName = entry.name,
                    spoofedModel = entry.profile.model,
                    // This app's own APK rides along so the module can restore it after the app is
                    // gone — installed once by service.sh, only when this package is absent. A read
                    // failure just omits the member; the spoofed module itself is still complete.
                    companionApk = readOwnApk()
                )

                val flashed = MagiskModuleBuilder.bootSafeProperties(spec)
                if (flashed.isEmpty()) {
                    // Not a write failure — a profile with no model has nothing this channel can
                    // deliver. Saying so beats a ZIP that flashes cleanly and changes nothing.
                    _toasts.emit(context.getString(R.string.spoof_error_no_model, entry.name))
                    return@launch
                }

                saveModuleToDownloads(spec)
                // The held-back count is named here as well as in the installer banner: a
                // five-property module reads as broken unless the omission is stated as a choice,
                // and a user may never read the banner as carefully as a toast they asked for.
                val held = MagiskModuleBuilder.omittedKeys(spec).size
                val caveat = if (held == 0) "" else
                    context.getString(R.string.spoof_magisk_held, held)
                // Same for the bundled APK: its size is the reason a user might not want it, so the
                // toast says it is there rather than letting the ZIP quietly double.
                val bundled = if (spec.companionApk != null) context.getString(R.string.spoof_magisk_bundled) else ""
                _toasts.emit(
                    context.getString(
                        R.string.spoof_magisk_saved, entry.name, flashed.size, caveat, bundled
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _toasts.emit(
                    context.getString(
                        R.string.spoof_error_zip,
                        e.message ?: context.getString(R.string.spoof_error_write_failed)
                    )
                )
            } finally {
                _uiState.update { it.copy(isGeneratingMagisk = false) }
            }
        }
    }

    /**
     * This device's own APK, or null when it cannot be read — the running `sourceDir` is readable by
     * the app itself on every Android this app supports, so null in practice means a split-APK
     * layout we did not anticipate rather than a permission problem, and the module simply ships
     * without the recovery member.
     */
    private fun readOwnApk(): ByteArray? = try {
        context.applicationInfo.sourceDir?.let { File(it).readBytes() }
    } catch (_: Exception) {
        null
    }

    /** @throws java.io.IOException when Downloads could not be written, so the caller can report it. */
    private fun saveModuleToDownloads(spec: MagiskModuleBuilder.ModuleSpec) {
        val name = "catsmoker_spoof_magisk.zip"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                ?: throw IOException("MediaStore refused a Downloads entry")
            context.contentResolver.openOutputStream(uri).use { out ->
                MagiskModuleBuilder.write(
                    out ?: throw IOException("Could not open $name for writing"),
                    spec
                )
            }
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            FileOutputStream(File(dir, name)).use { out -> MagiskModuleBuilder.write(out, spec) }
        }
    }

    fun launchRootManager() {
        val pm = context.packageManager
        val pkgs = arrayOf("com.topjohnwu.magisk", "me.weishu.kernelsu", "me.bmax.apatch")
        val intent = pkgs.firstNotNullOfOrNull { pm.getLaunchIntentForPackage(it) }
        if (intent != null) {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } else {
            _toasts.tryEmit(context.getString(R.string.spoof_error_no_manager))
        }
    }

    /**
     * Mirrors the current assignments into Settings.Global and tells running targets to reload.
     *
     * [com.catsmoker.app.system.config.SpoofConfigProvider] is the primary channel, but
     * package-visibility filtering hides our authority from most apps on API 30+, so the Xposed
     * module needs a copy it can read without touching us. Writing there needs shell rights —
     * Shizuku is enough, root is not required.
     */
    private fun syncLsposedConfig() {
        viewModelScope.launch(Dispatchers.IO) {
            val data = repository.loadData()

            // Per-package sections: this is what makes an assignment actually reach the hooks.
            // Ladder packages resolve through the same picker the provider uses, against the peak
            // measured now — the config a rung's profile renders to must be the one the panel
            // earned at the moment of publishing, not a winner frozen earlier.
            val panelPeakHz = displayRefreshRate.getMaxHardwareRefreshRate()
            val sections = LSPosedConfig.renderSections(
                (data.assignments.keys + data.rateAssignments.keys).mapNotNull { packageName ->
                    val profile = SpoofRepository.resolveProfileForPackage(data, packageName, panelPeakHz)
                        ?: return@mapNotNull null
                    packageName to repository.renderConfig(profile, data.globalProperties)
                }.toMap()
            )

            // The single-profile fallback can only hold one, so prefer one the user assigned
            // over whichever profile happens to sit first in the list.
            val assignedId = data.assignments.values.firstOrNull()
            val fallbackProfile = data.profiles.firstOrNull { it.id == assignedId }?.profile
                ?: data.profiles.firstOrNull()?.profile
            val rendered = fallbackProfile?.let { repository.renderConfig(it, data.globalProperties) }

            if (rendered != null) writeStringToBoth(LSPosedConfig.KEY_DEVICE_PROPS, rendered)

            if (shellRunner.hasPrivilege()) {
                putGlobalBase64(LSPosedConfig.KEY_GLOBAL_PROFILES_B64, sections)
                // The legacy channel's target list stays assignment-only on purpose: that channel
                // carries one profile for every listed package, so a ladder package listed here
                // would receive some other package's winner instead of its own section above.
                putGlobalBase64(
                    LSPosedConfig.KEY_GLOBAL_TARGET_PACKAGES_B64,
                    data.assignments.keys.joinToString("\n")
                )
                if (rendered != null) putGlobalBase64(LSPosedConfig.KEY_GLOBAL_DEVICE_PROPS_B64, rendered)
                // repository.save() already broadcast, but that was before these writes landed.
                context.sendBroadcast(Intent(LSPosedConfig.ACTION_CONFIG_CHANGED))
            }
        }
    }

    private suspend fun putGlobalBase64(key: String, value: String) {
        val encoded = Base64.encodeToString(value.toByteArray(), Base64.NO_WRAP)
        // `settings put` succeeds with empty stdout, so the exit code is the only usable signal.
        val result = shellRunner.execSafeResult("settings", "put", "global", key, encoded)
        if (!result.isSuccess) _toasts.tryEmit(context.getString(R.string.spoof_error_publish, key))
    }

    private fun readStringPref(k: String, d: String): String =
        devicePrefs.getString(k, userPrefs.getString(k, null)) ?: d

    private fun writeStringToBoth(k: String, v: String) {
        userPrefs.edit(commit = true) { putString(k, v) }
        devicePrefs.edit(commit = true) { putString(k, v) }
    }

    @Suppress("DEPRECATION")
    private fun openLsposedPrefs(context: Context): SharedPreferences =
        try {
            context.getSharedPreferences(LSPosedConfig.PREFS_NAME, Context.MODE_WORLD_READABLE)
        } catch (_: SecurityException) {
            context.getSharedPreferences(LSPosedConfig.PREFS_NAME, Context.MODE_PRIVATE)
        }
}
