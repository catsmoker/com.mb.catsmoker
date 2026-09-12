package com.catsmoker.app.features.editgamefiles.wuwa

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.catsmoker.app.R
import com.catsmoker.app.features.editgamefiles.ConfigBackupStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
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
import javax.inject.Inject

/** Which of the two write channels a deploy was asked to use. */
enum class WuwaDeployChannel { SHELL, SAF }

/**
 * One analyzed Client.log: the parsed facts, which channel read the bytes, whether the
 * decryptor actually ran (a plaintext log is read as-is, never reported as decrypted), and
 * the line count the parse covered.
 */
data class WuwaLogAnalysis(
    val info: WuwaLogParser.LogInfo,
    val channelUsed: String,
    val decrypted: Boolean,
    val lineCount: Int
)

/**
 * Screen state for the Wuthering Waves config generator.
 *
 * The generated previews are recomputed from (preset, options) on every change —
 * the generator is pure and cheap, so what the preview shows is always exactly what a deploy
 * would push. Device-specific inputs are deliberately not read: the generator runs on its
 * universal lowest-tier fallback. [deployReport]/[deployFailure] carry what the last deploy *actually did*, per
 * the house convention: per-file push/verify status, whether the game was stopped, whether
 * the game's hash monitor was synced — with "not applicable" (null) kept distinct from both.
 */
data class WuwaConfigUiState(
    val loading: Boolean = true,
    /** null = the package could not be checked (query threw). */
    val gameInstalled: Boolean? = null,
    val preset: String = "balanced",
    // The generator's own defaults leave the two optional files off; the screen turns them on
    // so a first deploy delivers the full five-file set the game's monitor watches.
    val options: WuWaConfigGenerator.Options = WuWaConfigGenerator.Options(
        generateScalability = true,
        generateHardware = true
    ),
    val previews: Map<String, String> = emptyMap(),
    val applying: Boolean = false,
    val deployReport: WuwaConfigManager.DeploySuccess? = null,
    val deployFailure: String? = null,
    val backups: List<ConfigBackupStore.Entry> = emptyList(),
    val canShell: Boolean = false,
    val hasSafUri: Boolean = false,
    val recommendation: WuwaSmartBrain.Recommendation? = null,
    /** The last Client.log analysis, when one has been read and parsed. */
    val logAnalysis: WuwaLogAnalysis? = null,
    /** True while a log read/decrypt/parse is running. */
    val analyzingLog: Boolean = false,
    /** Why the last log analysis could not happen, when it could not. */
    val logFailure: String? = null,
    /** The last installed-profile reading — databases + ini counts (+ log facts when a log exists). */
    val installedProfile: WuwaProfileExtractor.InstalledProfile? = null,
    /** True while an installed-profile read is running. */
    val readingProfile: Boolean = false,
    /** Why the last installed-profile read could not happen, when it could not. */
    val profileFailure: String? = null,
    /** Newest-first deploy history; null-verification records have not been re-checked. */
    val history: List<WuwaDeployHistoryStore.Record> = emptyList(),
    /** The record whose after-the-fact verification is running, if any. */
    val verifyingHistoryId: String? = null,
    /** The auto-tune loop's state; null = never started or cleared. */
    val tuner: WuwaBenchmarkTuner.TunerState? = null,
    /** True while a tuner deploy or measurement is running. */
    val tunerBusy: Boolean = false,
    /** Imported community packs, newest first. */
    val communityPacks: List<WuwaCommunityPackStore.StoredPack> = emptyList(),
    /** True while a picked pack folder is being walked and parsed. */
    val importingPack: Boolean = false,
    /** Why the last pack import could not happen, when it could not. */
    val packFailure: String? = null,
    /** The "packId/variantName" whose deploy is running, if any. */
    val deployingPackKey: String? = null,
    // ----------------------------------------------------------------- Convene (gacha) tracker
    /** The last successful Convene fetch: totals, per-pool pity predictions, records. */
    val gachaData: WuwaGacha.GachaData? = null,
    /** Summary of the 12-hour cache, when one is live — restore offers this without a refetch. */
    val gachaCacheSummary: WuwaGachaHistoryStore.Entry? = null,
    /** True while a Convene fetch (URL parse → per-pool POST → aggregate) is running. */
    val gachaLoading: Boolean = false,
    /** Why the last Convene read could not happen, when it could not. */
    val gachaFailure: String? = null
)

@HiltViewModel
class WuwaConfigViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val manager: WuwaConfigManager
) : ViewModel() {

    sealed class WuwaEvent {
        data class Toast(val message: String, val isLong: Boolean = false) : WuwaEvent()
        /** SAF deploy was requested with no persisted tree grant — the Route launches the picker. */
        data object LaunchFolderPicker : WuwaEvent()
    }

    private val _uiState = MutableStateFlow(WuwaConfigUiState())
    val uiState: StateFlow<WuwaConfigUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<WuwaEvent>()
    val events: SharedFlow<WuwaEvent> = _events.asSharedFlow()

    /** Parked deploy that waits for the SAF folder grant the picker is about to produce. */
    private var pendingSafDeploy: Map<String, String>? = null

    /** Parked backup restore waiting for the same grant (the entry to put back). */
    private var pendingSafRestore: ConfigBackupStore.Entry? = null

    /**
     * Parked *pack* deploy waiting for the same grant: pack id, variant name, and whether the
     * restricted cvars are to be stripped. Separate from [pendingSafDeploy] because a pack
     * deploy re-reads its files from the store when the grant lands rather than holding them.
     */
    private var pendingSafPackDeploy: Triple<String, String, Boolean>? = null

    /**
     * The auto-tune loop's persisted state. Same filesDir-JSON shape as the deploy history
     * store; the reference's `benchmark_tuner_state.json` kept the loop alive across process
     * death and this file carries that contract here.
     */
    private val tunerStateFile = java.io.File(context.filesDir, "wuwa_tuner_state.json")

    /** The 12-hour Convene cache — same filesDir-JSON shape, reference's `gacha_history.json`. */
    private val gachaHistoryStore = WuwaGachaHistoryStore(context)

    init {
        refresh()
        resumeTunerState()
        restoreGachaCache()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            data class RefreshOutcome(
                val installed: Boolean,
                val engineIni: String?,
                val recommendation: WuwaSmartBrain.Recommendation
            )
            val outcome = withContext(Dispatchers.IO) {
                // SmartBrain's measurable inputs are real reads: ActivityManager RAM + low-RAM
                // flag and the Vulkan feature flag. GPU/resolution arrive via the decrypted log
                // once one is analyzed (see computeRecommendation); until then the unknown tier
                // scores the reference's conservative −20.
                val installed = try {
                    context.packageManager.getPackageInfo(WuwaConfigManager.PACKAGE, 0)
                    true
                } catch (_: Exception) {
                    false
                }
                // A refresh keeps the log axes an earlier analysis earned — only a fresh
                // analyzeGameLog() changes them.
                val rec = computeRecommendation(
                    gpu = null,
                    resolution = null,
                    log = _uiState.value.logAnalysis?.info
                )
                RefreshOutcome(
                    installed = installed,
                    engineIni = if (manager.canDeployViaShell()) {
                        manager.readExistingEngineIni()
                    } else null,
                    recommendation = rec
                )
            }
            _uiState.update {
                it.copy(
                    loading = false,
                    gameInstalled = outcome.installed,
                    canShell = manager.canDeployViaShell(),
                    hasSafUri = manager.hasSafTreeUri(),
                    backups = manager.backups(),
                    recommendation = outcome.recommendation,
                    history = manager.history(),
                    communityPacks = manager.communityPacks()
                )
            }
            regenerate(outcome.engineIni)
        }
    }

    fun selectPreset(preset: String) {
        _uiState.update { it.copy(preset = preset) }
        regenerate()
    }

    /**
     * Builds the SmartBrain recommendation from real device reads (ActivityManager RAM +
     * low-RAM flag, the Vulkan feature flag), merged with the log-measured axes **only where
     * an analyzed log supplied them**. GPU/resolution come from the decrypted log when one
     * has been analyzed ([WuwaLogParser.gpu]/[WuwaLogParser.resolution] — the same source the
     * reference scores); without a log the GPU tier is unknown and scores the reference's own
     * conservative −20, so the ladder never emits tiers needing unmeasured evidence.
     */
    private suspend fun computeRecommendation(
        gpu: String?,
        resolution: String?,
        log: WuwaLogParser.LogInfo?
    ): WuwaSmartBrain.Recommendation = withContext(Dispatchers.IO) {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val ramMb = (memInfo.totalMem / (1024L * 1024L)).toInt()
        WuwaSmartBrain.recommend(
            WuwaSmartBrain.DeviceSignals(
                gpu = gpu ?: log?.gpu,
                ramMb = ramMb,
                resolution = resolution ?: log?.resolution,
                vulkanAvailable = try {
                    context.packageManager.hasSystemFeature(
                        android.content.pm.PackageManager.FEATURE_VULKAN_HARDWARE_VERSION
                    )
                } catch (_: Exception) {
                    null
                },
                lowRamDevice = try {
                    activityManager.isLowRamDevice
                } catch (_: Exception) {
                    null
                },
                fpsActual = log?.fpsActual,
                fpsCap = log?.fpsCap,
                thermalEvents = log?.thermalEvents,
                gpuOom = log?.gpuOom,
                dropFrames = log?.dropFrames,
                autoAdjustTriggers = log?.autoAdjustTriggers,
                autoAdjustRecoveries = log?.autoAdjustRecoveries,
                textureErrors = log?.textureErrors,
                networkErrors = log?.networkErrors,
                screenPct = log?.screenPct,
                activeCvars = log?.activeCvars ?: emptyMap()
            )
        )
    }

    /** Applies the SmartBrain recommendation — a one-tap `selectPreset`, nothing more. */
    fun applyRecommendation() {
        val rec = _uiState.value.recommendation ?: return
        selectPreset(rec.preset)
        viewModelScope.launch {
            _events.emit(WuwaEvent.Toast(context.getString(R.string.gf_wuwa_rec_applied, rec.preset, rec.score), true))
        }
    }

    fun updateOptions(transform: (WuWaConfigGenerator.Options) -> WuWaConfigGenerator.Options) {
        _uiState.update { it.copy(options = transform(it.options)) }
        regenerate()
    }

    private fun regenerate(existingEngineIni: String? = null) {
        val state = _uiState.value
        viewModelScope.launch(Dispatchers.IO) {
            // The device's own [Core.System] paths are worth a privileged read, but only when
            // root/Shizuku exists; otherwise the generator's snapshot fallback applies.
            val existing = existingEngineIni
                ?: if (state.canShell) manager.readExistingEngineIni() else null
            val configs = WuWaConfigGenerator.generate(
                state.preset,
                state.options,
                WuWaConfigGenerator.DeviceInfo(),
                existing
            )
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(previews = configs.asMap()) }
            }
        }
    }

    fun onDeploy(channel: WuwaDeployChannel) {
        val state = _uiState.value
        if (state.applying) return
        val files = state.previews
        if (files.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(applying = true, deployFailure = null) }
            if (channel == WuwaDeployChannel.SAF && !manager.hasSafTreeUri()) {
                // No persisted grant yet: park the deploy and ask for the folder. The picked
                // grant re-runs it — same parking pattern as the custom-upload channel.
                pendingSafDeploy = files
                _uiState.update { it.copy(applying = false) }
                _events.emit(WuwaEvent.LaunchFolderPicker)
                return@launch
            }
            val result = when (channel) {
                WuwaDeployChannel.SHELL -> manager.deployViaShell(files)
                WuwaDeployChannel.SAF -> manager.deployViaSaf(files)
            }
            handleDeployResult(result, _uiState.value.preset)
        }
    }

    /** Completes a parked SAF deploy once the folder grant exists. */
    fun onSafFolderPicked(uri: Uri) {
        viewModelScope.launch {
            if (!manager.onSafFolderPicked(uri)) {
                _events.emit(WuwaEvent.Toast(context.getString(R.string.gf_grant_lost), true))
                pendingSafDeploy = null
                pendingSafPackDeploy = null
                pendingSafRestore = null
                return@launch
            }
            _uiState.update { it.copy(hasSafUri = true) }
            val parked = pendingSafDeploy
            pendingSafDeploy = null
            if (parked != null) {
                _uiState.update { it.copy(applying = true, deployFailure = null) }
                handleDeployResult(manager.deployViaSaf(parked), _uiState.value.preset)
            }
            // A parked pack deploy waited for the same grant; it runs after (never beside) a
            // parked generated deploy — only one can have been parked at a time in practice.
            val parkedPack = pendingSafPackDeploy
            pendingSafPackDeploy = null
            if (parkedPack != null) {
                deployPackVariant(parkedPack.first, parkedPack.second, parkedPack.third)
            }
            // A parked restore waited for the same grant; it runs last.
            val parkedRestore = pendingSafRestore
            pendingSafRestore = null
            if (parkedRestore != null) {
                onRestoreBackup(parkedRestore)
            }
        }
    }

    private suspend fun handleDeployResult(result: WuwaConfigManager.DeployResult, preset: String) {
        when (result) {
            is WuwaConfigManager.DeployResult.Success -> {
                val s = result.success
                val verifiedCount = s.files.count { it.verified }
                val failed = s.files.filter { !it.pushed }
                val detail = buildString {
                    append(context.getString(R.string.gf_dep_files, s.files.size, verifiedCount))
                    if (failed.isNotEmpty()) append(context.getString(R.string.gf_dep_refused, failed.size, failed.joinToString { "${it.name} (${it.detail})" }))
                    append(context.getString(R.string.gf_dep_dot))
                    append(
                        when {
                            s.gameStopped == true -> context.getString(R.string.gf_dep_stopped)
                            s.gameStopped == false -> context.getString(R.string.gf_dep_stop_refused)
                            else -> context.getString(R.string.gf_dep_saf)
                        }
                    )
                    append(
                        when (s.hashSynced) {
                            true -> context.getString(R.string.gf_dep_hash_ok)
                            false -> context.getString(R.string.gf_dep_hash_fail, s.hashDetail)
                            null -> context.getString(R.string.gf_dep_hash_na, s.hashDetail)
                        }
                    )
                }
                // Record what the device actually did before the state update, so the history
                // section reflects this deploy the moment the report does.
                manager.recordDeploy(preset, s)
                _uiState.update {
                    it.copy(
                        applying = false, deployReport = s, deployFailure = null,
                        backups = manager.backups(), history = manager.history()
                    )
                }
                _events.emit(
                    WuwaEvent.Toast(
                        context.getString(if (failed.isEmpty() && s.files.all { it.verified }) R.string.gf_deployed_ok else R.string.gf_deployed_issues),
                        true
                    )
                )
                _events.emit(WuwaEvent.Toast(detail, true))
            }
            is WuwaConfigManager.DeployResult.Failure -> {
                _uiState.update { it.copy(applying = false, deployFailure = context.getString(R.string.gf_failed_stage_detail, result.stage, result.detail)) }
                _events.emit(WuwaEvent.Toast(context.getString(R.string.gf_deploy_failed, result.detail), true))
            }
        }
    }

    fun onRestoreBackup(entry: ConfigBackupStore.Entry) {
        viewModelScope.launch {
            when (val result = manager.restoreBackup(entry)) {
                is WuwaConfigManager.DeployResult.Success -> {
                    _events.emit(WuwaEvent.Toast(context.getString(R.string.gf_backup_restored_name, entry.file.name.substringAfter('_')), true))
                    handleDeployResult(result, "restore")
                }
                is WuwaConfigManager.DeployResult.Failure -> {
                    if (result.stage == "saf-folder") {
                        // No grant yet: park the restore behind the picker like a deploy.
                        pendingSafRestore = entry
                        _events.emit(WuwaEvent.LaunchFolderPicker)
                    } else {
                        _events.emit(WuwaEvent.Toast(context.getString(R.string.gf_restore_failed_detail, result.detail), true))
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- deploy history

    /**
     * Re-reads the record's files on the device and compares each digest with the one the
     * deploy recorded. "No channel available" is reported as that, never as a failed check.
     */
    fun verifyHistoryRecord(record: WuwaDeployHistoryStore.Record) {
        if (_uiState.value.verifyingHistoryId != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(verifyingHistoryId = record.id) }
            try {
                val updated = manager.verifyRecord(record)
                if (updated == null) {
                    _events.emit(
                        WuwaEvent.Toast(context.getString(R.string.gf_verify_none), true)
                    )
                } else {
                    val v = updated.verification
                    val summary = v?.files?.groupBy { it.status }?.entries
                        ?.joinToString { (status, list) -> "${list.size} ${status.name.lowercase()}" }
                        ?: "no files recorded"
                    _events.emit(WuwaEvent.Toast(context.getString(R.string.gf_verified_summary, v?.channelUsed, summary), true))
                    _uiState.update { it.copy(history = manager.history()) }
                }
            } finally {
                _uiState.update { it.copy(verifyingHistoryId = null) }
            }
        }
    }

    fun deleteHistoryRecord(record: WuwaDeployHistoryStore.Record) {
        manager.deleteHistoryRecord(record.id)
        _uiState.update { it.copy(history = manager.history()) }
    }

    fun clearHistory() {
        manager.clearHistory()
        _uiState.update { it.copy(history = emptyList()) }
    }

    // ------------------------------------------------------------------- game log

    /**
     * Reads the game's encrypted Client.log (byte-exactly — the manager never `cat`s it into a
     * String), decrypts, parses, and re-scores the recommendation with the axes only the log
     * can measure. A read failure is surfaced as the device's own refusal text, never as an
     * empty analysis that would look like a clean log.
     */
    fun analyzeGameLog() {
        if (_uiState.value.analyzingLog) return
        viewModelScope.launch {
            _uiState.update { it.copy(analyzingLog = true, logFailure = null) }
            try {
                val read = manager.readClientLog()
                when (read) {
                    is WuwaConfigManager.ClientLogResult.Failure -> {
                        _uiState.update { it.copy(logFailure = read.detail) }
                        _events.emit(WuwaEvent.Toast(read.detail, true))
                    }
                    is WuwaConfigManager.ClientLogResult.Read -> withContext(Dispatchers.IO) {
                        val (text, decode) = WuwaLogDecryptor.decodeLogBytes(read.bytes)
                        val info = WuwaLogParser.parseLog(text)
                        val analysis = WuwaLogAnalysis(
                            info = info,
                            channelUsed = read.channelUsed,
                            decrypted = decode == WuwaLogDecryptor.DecodeResult.DECRYPTED,
                            lineCount = text.lineSequence().count()
                        )
                        val rec = computeRecommendation(null, null, info)
                        withContext(Dispatchers.Main) {
                            _uiState.update { it.copy(logAnalysis = analysis, recommendation = rec) }
                        }
                        _events.emit(
                            WuwaEvent.Toast(
                                context.getString(
                                    R.string.gf_log_rescored,
                                    context.getString(if (analysis.decrypted) R.string.gf_log_decrypted else R.string.gf_log_plain),
                                    read.channelUsed,
                                    analysis.lineCount
                                ),
                                isLong = true
                            )
                        )
                    }
                }
            } finally {
                _uiState.update { it.copy(analyzingLog = false) }
            }
        }
    }

    /**
     * Reads the installed game's own record of itself — the LocalStorage/DeviceStorage databases
     * (UID, server, level, last login, tower progress, versions, language) plus a settings count
     * over each deployed ini, merged with the log's device facts when Client.log exists. The halves
     * fail independently inside the read; only a total failure reaches [WuwaConfigUiState.profileFailure].
     */
    fun readInstalledProfile() {
        if (_uiState.value.readingProfile) return
        viewModelScope.launch {
            _uiState.update { it.copy(readingProfile = true, profileFailure = null) }
            try {
                when (val result = manager.readInstalledProfile()) {
                    is WuwaConfigManager.InstalledProfileResult.Failure -> {
                        _uiState.update { it.copy(profileFailure = result.detail) }
                        _events.emit(WuwaEvent.Toast(result.detail, true))
                    }
                    is WuwaConfigManager.InstalledProfileResult.Read -> {
                        _uiState.update { it.copy(installedProfile = result.profile) }
                    }
                }
            } finally {
                _uiState.update { it.copy(readingProfile = false) }
            }
        }
    }

    // ---------------------------------------------------------------- community packs

    /**
     * Imports the pack folder the user picked: a one-shot walk, parse, and store — the pack's
     * files and READMEs land in the app's own store, so the picked folder's grant can die with
     * the picker. The refusal text is the device's/document provider's own words, and a pack
     * with nothing deployable is refused before it can sit in the list looking usable.
     */
    fun importCommunityPack(uri: Uri) {
        if (_uiState.value.importingPack) return
        viewModelScope.launch {
            _uiState.update { it.copy(importingPack = true, packFailure = null) }
            try {
                when (val result = withContext(Dispatchers.IO) { manager.importCommunityPack(uri) }) {
                    is WuwaConfigManager.PackImportResult.Failure -> {
                        _uiState.update { it.copy(packFailure = result.detail) }
                        _events.emit(WuwaEvent.Toast(result.detail, true))
                    }
                    is WuwaConfigManager.PackImportResult.Read -> {
                        val pack = result.pack
                        _uiState.update { it.copy(communityPacks = manager.communityPacks()) }
                        val notes = mutableListOf<String>()
                        notes.add(context.getString(R.string.gf_pack_variants, pack.variants.size, pack.variants.joinToString { it.name }))
                        if (pack.variants.any { it.forbiddenCount > 0 }) {
                            notes.add(context.getString(R.string.gf_pack_restricted))
                        }
                        if (pack.unknownFiles.isNotEmpty()) notes.add(context.getString(R.string.gf_pack_unknown_n, pack.unknownFiles.size))
                        _events.emit(WuwaEvent.Toast(context.getString(R.string.gf_pack_imported_toast, pack.name, notes.joinToString("; ")), true))
                    }
                }
            } finally {
                _uiState.update { it.copy(importingPack = false) }
            }
        }
    }

    fun deleteCommunityPack(id: String) {
        manager.deleteCommunityPack(id)
        _uiState.update { it.copy(communityPacks = manager.communityPacks()) }
    }

    /** Deploys a pack variant verbatim — refused here too if it carries restricted cvars. */
    fun deployCommunityVariant(packId: String, variantName: String) {
        deployPackVariant(packId, variantName, strip = false)
    }

    /** Deploys a pack variant with the restricted cvar lines removed, reporting the strip. */
    fun deployCommunityVariantStripped(packId: String, variantName: String) {
        deployPackVariant(packId, variantName, strip = true)
    }

    /**
     * The pack deploy itself. Channel preference is root/Shizuku → SAF:
     * SAF cannot stop the game or sync its hash monitor, so it is the last resort. The deploy
     * reuses the deploy machinery a generated config goes through — backups, md5 read-back,
     * game stop, hash sync, deploy history — and the history label names the pack and variant
     * (plus "(restricted stripped)" when the gate's other door was taken), so a pack deploy is
     * distinguishable from a generated one in the record, not just in the moment.
     */
    private fun deployPackVariant(packId: String, variantName: String, strip: Boolean) {
        val state = _uiState.value
        if (state.applying || state.deployingPackKey != null) return
        val pack = state.communityPacks.firstOrNull { it.id == packId } ?: return
        val variant = pack.variants.firstOrNull { it.name == variantName } ?: return
        if (!strip && variant.forbiddenCount > 0) {
            // The screen does not offer this button, but the gate is enforced here too —
            // an imported pack is not a way around the restricted-cvar rule the generator obeys.
            viewModelScope.launch {
                _events.emit(
                    WuwaEvent.Toast(
                        context.getString(R.string.gf_pack_refused_toast, variant.name, variant.forbiddenCount),
                        true
                    )
                )
            }
            return
        }
        viewModelScope.launch {
            val key = "$packId/$variantName"
            // `applying` is set too so the generated-config deploy buttons disable while this
            // runs — it is the same deploy machinery, and two deploys must not interleave.
            _uiState.update { it.copy(deployingPackKey = key, applying = true, deployFailure = null) }
            try {
                val stripped = if (strip) WuwaCommunityPack.stripVariant(variant) else null
                val files = stripped?.files ?: variant.files
                if (manager.canDeployViaShell()) {
                    handleDeployResult(manager.deployViaShell(files), packDeployLabel(pack, variant, stripped != null))
                } else if (manager.hasSafTreeUri()) {
                    handleDeployResult(manager.deployViaSaf(files), packDeployLabel(pack, variant, stripped != null))
                } else {
                    // No channel yet: park the deploy behind the game-folder grant the picker
                    // is about to produce — same parking pattern as the generated deploy.
                    pendingSafPackDeploy = Triple(packId, variantName, strip)
                    _uiState.update { it.copy(applying = false) }
                    _events.emit(WuwaEvent.LaunchFolderPicker)
                }
            } finally {
                _uiState.update { it.copy(deployingPackKey = null) }
            }
        }
    }

    private fun packDeployLabel(
        pack: WuwaCommunityPackStore.StoredPack,
        variant: WuwaCommunityPack.Variant,
        stripped: Boolean
    ): String = "community: ${pack.name}/${variant.name}" + if (stripped) " (restricted stripped)" else ""

    // ----------------------------------------------------------------- Convene (gacha) tracker

    /**
     * Brings the 12-hour cache onto the screen at init: its summary is offered as a restore,
     * and a cache still alive after process death is shown read-only until the user refetches.
     * A load never throws — expiry and corruption were handled as absence inside the store.
     */
    private fun restoreGachaCache() {
        viewModelScope.launch(Dispatchers.IO) {
            val entry = gachaHistoryStore.load()
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(gachaCacheSummary = entry) }
            }
        }
    }

    /**
     * The whole Convene read in one tap: extract the record URL from the game's decrypted
     * Client.log, parse it, POST one query per pool, aggregate, and cache for 12 h.
     *
     * Each stage that can fail does so with its own words — no log at all, no URL in the log
     * (the user has to open Convene History in-game once first; that is what writes it), a URL
     * missing its parameters, and the fetcher's two distinct failures (transport vs
     * every-pool-rejected) all say different things. Nothing here reports a fabricated empty
     * history: the "must not masquerade as empty" rule from the reference is enforced inside
     * [WuwaGacha.combinePoolResults] and its message is surfaced verbatim.
     */
    fun fetchGachaFromLog() {
        if (_uiState.value.gachaLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(gachaLoading = true, gachaFailure = null) }
            try {
                val read = manager.readClientLog()
                if (read is WuwaConfigManager.ClientLogResult.Failure) {
                    failGacha(read.detail)
                    return@launch
                }
                val bytes = (read as WuwaConfigManager.ClientLogResult.Read).bytes
                val (text, _) = withContext(Dispatchers.IO) { WuwaLogDecryptor.decodeLogBytes(bytes) }
                val url = withContext(Dispatchers.IO) { WuwaGacha.extractConveneUrl(text) }
                if (url == null) {
                    failGacha(context.getString(R.string.gf_gacha_no_url))
                    return@launch
                }
                val params = WuwaGacha.parseUrl(url)
                if (params == null) {
                    failGacha(context.getString(R.string.gf_gacha_bad_url))
                    return@launch
                }
                val result = WuwaGachaFetcher.fetchAllRecords(params)
                when {
                    result.isSuccess -> {
                        val data = result.getOrThrow()
                        withContext(Dispatchers.IO) { gachaHistoryStore.save(data) }
                        _uiState.update {
                            it.copy(
                                gachaData = data,
                                gachaCacheSummary = gachaHistoryStore.load(),
                                gachaFailure = null
                            )
                        }
                        _events.emit(
                            WuwaEvent.Toast(
                                context.getString(
                                    R.string.gf_gacha_loaded,
                                    data.totalPulls,
                                    data.fiveStars,
                                    data.fourStars,
                                    data.poolsWithData.size
                                ),
                                true
                            )
                        )
                    }
                    else -> failGacha(context.getString(R.string.gf_gacha_failed, result.exceptionOrNull()?.message))
                }
            } finally {
                _uiState.update { it.copy(gachaLoading = false) }
            }
        }
    }

    /** Puts the cached [WuwaGacha.GachaData] back on screen without a refetch. */
    fun restoreGachaFromCache() {
        val data = gachaHistoryStore.loadData()
        if (data == null) {
            viewModelScope.launch {
                _events.emit(WuwaEvent.Toast(context.getString(R.string.gf_gacha_no_cache), false))
            }
            return
        }
        _uiState.update { it.copy(gachaData = data) }
    }

    fun clearGachaCache() {
        gachaHistoryStore.delete()
        _uiState.update { it.copy(gachaData = null, gachaCacheSummary = null) }
    }

    private suspend fun failGacha(detail: String) {
        _uiState.update { it.copy(gachaFailure = detail) }
        _events.emit(WuwaEvent.Toast(detail, true))
    }

    // ------------------------------------------------------------------- auto-tune

    /**
     * Restores a tuner loop interrupted by process death. A state persisted mid-deploy or

     * mid-measurement cannot be resumed where it stopped — the write or the read is already
     * gone — so those stages are demoted to WAITING_FOR_PLAY and the user re-confirms they
     * played, exactly the reference's own resume rule.
     */
    private fun resumeTunerState() {
        viewModelScope.launch(Dispatchers.IO) {
            val saved = WuwaBenchmarkTuner.loadState(tunerStateFile) ?: return@launch
            val resumed =
                if (saved.stage == WuwaBenchmarkTuner.TunerStage.DEPLOYING ||
                    saved.stage == WuwaBenchmarkTuner.TunerStage.CAPTURING
                ) {
                    saved.copy(stage = WuwaBenchmarkTuner.TunerStage.WAITING_FOR_PLAY)
                        .also { WuwaBenchmarkTuner.saveState(it, tunerStateFile) }
                } else {
                    saved
                }
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(tuner = resumed) }
            }
        }
    }

    /**
     * Starts the loop from what the screen already shows: the selected preset and the
     * frame-rate target are the first round's deployment, the same fields the reference's
     * `startTuner` took from its own generator options.
     */
    fun startAutoTune() {
        if (_uiState.value.tunerBusy) return
        val s = _uiState.value
        val state = WuwaBenchmarkTuner.TunerState(
            stage = WuwaBenchmarkTuner.TunerStage.DEPLOYING,
            round = 1,
            preset = s.preset,
            options = s.options,
            targetFps = s.options.fps
        )
        viewModelScope.launch {
            _uiState.update { it.copy(tuner = state, tunerBusy = true) }
            WuwaBenchmarkTuner.saveState(state, tunerStateFile)
            val next = withContext(Dispatchers.IO) { deployTunerRound(state) }
            finishTunerStep(next)
        }
    }

    /**
     * The user says they played a session. Measures the game's log, records the round, and
     * either completes the loop or deploys the next round's preset — deploy, measure, decide,
     * the reference's `captureAndAnalyze` in one place.
     */
    fun onTunerRoundPlayed() {
        val current = _uiState.value.tuner ?: return
        if (_uiState.value.tunerBusy || current.stage != WuwaBenchmarkTuner.TunerStage.WAITING_FOR_PLAY) return
        viewModelScope.launch {
            val capturing = current.copy(stage = WuwaBenchmarkTuner.TunerStage.CAPTURING)
            _uiState.update { it.copy(tuner = capturing, tunerBusy = true) }
            WuwaBenchmarkTuner.saveState(capturing, tunerStateFile)
            val afterCapture = withContext(Dispatchers.IO) { captureTunerRound(capturing) }
            val next =
                if (afterCapture.stage == WuwaBenchmarkTuner.TunerStage.DEPLOYING) {
                    withContext(Dispatchers.IO) { deployTunerRound(afterCapture) }
                } else {
                    afterCapture
                }
            finishTunerStep(next)
        }
    }

    /** Applies the loop's answer: the best preset and the option toggles it measured with. */
    fun applyTunerResult() {
        val t = _uiState.value.tuner ?: return
        val preset = t.finalPreset ?: return
        _uiState.update { it.copy(preset = preset, options = t.options) }
        regenerate()
        viewModelScope.launch {
            _events.emit(WuwaEvent.Toast(context.getString(R.string.gf_tuner_applied, preset), true))
        }
    }

    /** Stops the loop between rounds. The rounds already measured stay in the results list. */
    fun stopTuner() {
        val current = _uiState.value.tuner ?: return
        if (_uiState.value.tunerBusy) return
        val stopped = current.copy(
            stage = WuwaBenchmarkTuner.TunerStage.COMPLETE,
            finalPreset = null,
            error = "Stopped by you after round ${current.round}."
        )
        WuwaBenchmarkTuner.saveState(stopped, tunerStateFile)
        _uiState.update { it.copy(tuner = stopped) }
    }

    /** Clears a finished loop so the card returns to its Start state. */
    fun resetTuner() {
        WuwaBenchmarkTuner.clearState(tunerStateFile)
        _uiState.update { it.copy(tuner = null) }
    }

    /**
     * Deploys one round. The channel is chosen root/Shizuku → SAF, the same preference the
     * manual deploy buttons state: SAF cannot stop the game or sync its config hash monitor,
     * so it is the tuner's last resort and the round report says which one ran. The deploy is
     * recorded in the deploy history like any manual one — the loop's rounds are deploys.
     */
    private suspend fun deployTunerRound(
        state: WuwaBenchmarkTuner.TunerState
    ): WuwaBenchmarkTuner.TunerState = withContext(Dispatchers.IO) {
        fun stopped(detail: String) = state.copy(
            stage = WuwaBenchmarkTuner.TunerStage.COMPLETE,
            error = detail
        )
        val existing =
            if (manager.canDeployViaShell()) manager.readExistingEngineIni() else null
        val configs = WuWaConfigGenerator.generate(
            state.preset,
            state.options,
            WuWaConfigGenerator.DeviceInfo(),
            existing
        )
        val result = when {
            manager.canDeployViaShell() -> manager.deployViaShell(configs.asMap())
            manager.hasSafTreeUri() -> manager.deployViaSaf(configs.asMap())
            else -> null
        }
        when (result) {
            null -> stopped(context.getString(R.string.gf_tuner_no_channel))
            is WuwaConfigManager.DeployResult.Failure ->
                stopped(context.getString(R.string.gf_tuner_dep_failed, result.stage, result.detail))
            is WuwaConfigManager.DeployResult.Success -> {
                manager.recordDeploy(state.preset, result.success)
                state.copy(stage = WuwaBenchmarkTuner.TunerStage.WAITING_FOR_PLAY)
            }
        }
    }

    /**
     * Measures one round from the game's own log — the one channel that knows what FPS the
     * session actually reached. No `AverageFPS` reading in the window is a *stop*, not a
     * measurement of zero: the game only writes those lines while playing, so the honest
     * answer is to say that and let the user try again after really playing.
     */
    private suspend fun captureTunerRound(
        state: WuwaBenchmarkTuner.TunerState
    ): WuwaBenchmarkTuner.TunerState = withContext(Dispatchers.IO) {
        fun stopped(detail: String) = state.copy(
            stage = WuwaBenchmarkTuner.TunerStage.COMPLETE,
            error = detail
        )
        when (val read = manager.readClientLog()) {
            is WuwaConfigManager.ClientLogResult.Failure ->
                stopped(context.getString(R.string.gf_tuner_no_log, read.detail))
            is WuwaConfigManager.ClientLogResult.Read -> {
                val (text, _) = WuwaLogDecryptor.decodeLogBytes(read.bytes)
                val measured = WuwaBenchmarkTuner.parseFpsSamples(text)
                        ?: return@withContext stopped(
                            context.getString(R.string.gf_tuner_no_fps, WuwaBenchmarkTuner.LINE_WINDOW)
                        )
                val results = state.results + WuwaBenchmarkTuner.RoundResult(
                    round = state.round,
                    preset = state.preset,
                    avgFps = measured.avgFps,
                    minFps = measured.minFps,
                    stabilityPct = measured.stabilityPct
                )
                if (measured.avgFps >= state.targetFps || state.round >= WuwaBenchmarkTuner.MAX_ROUNDS) {
                    state.copy(
                        stage = WuwaBenchmarkTuner.TunerStage.COMPLETE,
                        results = results,
                        finalPreset = state.preset
                    )
                } else {
                    state.copy(
                        stage = WuwaBenchmarkTuner.TunerStage.DEPLOYING,
                        round = state.round + 1,
                        preset = WuwaBenchmarkTuner.pickPresetForFps(state.preset, measured.avgFps, state.targetFps),
                        options = WuwaBenchmarkTuner.adjustOptionsForFps(state.options, measured.avgFps, state.targetFps),
                        results = results
                    )
                }
            }
        }
    }

    /** Persists and publishes a finished step, and says what happened in plain words. */
    private suspend fun finishTunerStep(next: WuwaBenchmarkTuner.TunerState) {
        WuwaBenchmarkTuner.saveState(next, tunerStateFile)
        _uiState.update {
            it.copy(
                tuner = next,
                tunerBusy = false,
                history = manager.history(),
                backups = manager.backups()
            )
        }
        when {
            next.stage == WuwaBenchmarkTuner.TunerStage.WAITING_FOR_PLAY ->
                _events.emit(
                    WuwaEvent.Toast(
                        context.getString(R.string.gf_tuner_round_done, next.round),
                        true
                    )
                )
            next.stage == WuwaBenchmarkTuner.TunerStage.COMPLETE && next.error != null ->
                _events.emit(WuwaEvent.Toast(context.getString(R.string.gf_tuner_stopped_toast, next.error), true))
            // A completed loop is announced by the card itself, next to its results.
        }
    }
}
