package com.catsmoker.app.features.editgamefiles

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.catsmoker.app.R
import com.catsmoker.app.features.editgamefiles.genshin.GenshinConfigTemplate
import com.catsmoker.app.features.editgamefiles.grid.GridPreferences
import com.catsmoker.app.features.editgamefiles.hsr.HsrGameManager
import com.catsmoker.app.features.editgamefiles.pubg.PubgSavePatcher
import com.catsmoker.app.features.editgamefiles.wuwa.WuwaConfigManager
import com.catsmoker.app.shared.data.model.GameConfig
import com.catsmoker.app.shared.data.model.GameProfile
import com.catsmoker.app.shared.data.model.GameType
import com.catsmoker.app.system.shell.ShellRunner
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
import rikka.shizuku.Shizuku
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.EnumMap
import javax.inject.Inject

@HiltViewModel
class EditGameFilesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shellRunner: ShellRunner,
    private val hsrGameManager: HsrGameManager,
) : ViewModel(), Shizuku.OnRequestPermissionResultListener {

    sealed class EditEvent {
        data class Toast(val message: String, val isLong: Boolean = false) : EditEvent()
        object LaunchFilePicker : EditEvent()
        data class LaunchSafPicker(val dir: String) : EditEvent()
        object LaunchFolderPicker : EditEvent()
        object LaunchAllFilesAccess : EditEvent()
        object ShowZArchiverDialog : EditEvent()
    }

    /** Which card started the running job. One job at a time: the active card shows the bar + spinner, the rest only disable. */
    enum class BusyArea {
        PROFILE_PUSH,
        CUSTOM_UPLOAD,
        SAVE_READ,
        SAVE_APPLY,
        RESET,
        RESTORE,
        BACKUP_DELETE
    }

    data class UiState(
        val isLoading: Boolean = false,
        val busyArea: BusyArea? = null,
        val selectedGame: GameType = GameType.NONE,
        /**
         * Per-game install state, probed once when the screen opens — `null` = not probed yet,
         * so a chip can show "unknown" instead of guessing. The profile games used to have no
         * surface for this at all (the fact came out only when LAUNCH GAME was pressed), which
         * is why selecting a not-installed game looked identical to selecting an installed one
         * until the very last step.
         */
        val installedGames: Map<GameType, Boolean> = emptyMap(),
        val selectedProfile: Int = 0,
        /** Labels of the selected game's profiles — per-game, since Genshin has one and PUBG two. */
        val profileLabels: List<String> = emptyList(),
        /**
         * The selected game's own config-file name for UI labels — the reset button and its
         * dialog once hardcoded "ACTIVE.SAV" and showed it under Genshin too. Null when no
         * game is selected; the reset button itself is hidden when the game has no reset.
         */
        val configFileLabel: String? = null,
        /**
         * The selected game's package name, so the GAME NOT FOUND card can name the exact
         * package the probe looked for instead of a one-size-fits-all claim. The card's
         * message once hardcoded "no PUBG-family package …" and said it under Genshin, whose
         * probe never looks at a PUBG package at all. Null for the embedded editors (their
         * packages resolve inside their own managers) — they render their own failure cards.
         */
        val gamePackageName: String? = null,
        val canReset: Boolean = false,
        /** Custom upload is a PUBG `Active.sav` affordance — hidden for games with templated configs. */
        val canUploadCustom: Boolean = false,
        /** The inline save editor exists for this game — PUBG's verified GVAS layout only. */
        val canPatchSave: Boolean = false,
        /**
         * What the game's save actually holds, read through the editor's channel. Null until a
         * read succeeds; the editor's chips stay disabled until then — it edits the values the
         * file carries, never assumed defaults.
         */
        val saveRead: PubgSavePatcher.ReadResult? = null,
        /**
         * The last apply's read-back — what the file holds *now*, per the house rule that the
         * device's own answer is the report. Null when nothing has been applied yet.
         */
        val savePatchReport: String? = null,
        /** The save editor's pending edits, keyed by property name — chip selections before APPLY. */
        val saveEdits: Map<String, Int> = emptyMap(),
        val selectedItemText: String = "",
        val showMethodChooser: Boolean = false,
        val showResetChooser: Boolean = false,
        /** Saved backups of the selected game's config, newest first — populated when the dialog opens. */
        val backups: List<ConfigBackupStore.Entry> = emptyList(),
        val showBackupDialog: Boolean = false,
        val showRestoreChooser: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<EditEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<EditEvent> = _events.asSharedFlow()

    private val gameConfigs: MutableMap<GameType, GameConfig> = EnumMap(GameType::class.java)
    private val customUploadPrefs by lazy { context.getSharedPreferences("custom_upload_prefs", Context.MODE_PRIVATE) }

    private var selectedFileUri: Uri? = null
    private var selectedAssetPath: String? = null
    private var pendingAction: (() -> Unit)? = null
    private var customPackagePending: String? = null
    private var pendingRestore: ConfigBackupStore.Entry? = null

    private val backupStore by lazy { ConfigBackupStore(context) }

    init {
        initializeGameConfigs()
        _uiState.update {
            it.copy(selectedItemText = context.getString(R.string.selected_item_placeholder))
        }
        probeInstallStates()
        Shizuku.addRequestPermissionResultListener(this)
    }

    override fun onCleared() {
        Shizuku.removeRequestPermissionResultListener(this)
    }

    override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            checkAndStartShizukuAction()
        } else {
            showSnackbar(context.getString(R.string.gf_shizuku_denied))
        }
    }

    private fun initializeGameConfigs() {
        // PUBG-family variants share the same `ShadowTrackerExtra` save layout, so one config
        // builder serves them all. The package set is the variants the reference
        // `referance/gamingtools/BattleGrounds_GFX-main` writes `Active.sav` to — a working
        // tool that verifies the folder the user picks, so its list is verified evidence of
        // which packages actually carry that path. `com.pubg.newstate` is not among them
        // (New State lays its data out differently and no reference verifies a save path for
        // it), so it is not added here on pattern-matching faith.
        val pubgGames = listOf(
            GameType.PUBG_GLOBAL to "com.tencent.ig",
            GameType.PUBG_KRJP to "com.pubg.krmobile",
            GameType.PUBG_VN to "com.vng.pubgmobile",
            GameType.BGMI to "com.pubg.imobile",
            GameType.PUBG_REKOO to "com.rekoo.pubgm"
        )
        pubgGames.forEach { (type, pkg) ->
            gameConfigs[type] = buildPubgConfig(pkg)
        }
        gameConfigs[GameType.GENSHIN_IMPACT] = buildGenshinConfig()
    }

    private fun buildPubgConfig(packageName: String): GameConfig {
        return GameConfig(
            packageName = packageName,
            saveDir = "/Android/data/$packageName/files/UE4Game/ShadowTrackerExtra/ShadowTrackerExtra/Saved/SaveGames/",
            saveFile = "Active.sav",
            profiles = listOf(
                GameProfile(context.getString(R.string.gf_profile_unlock_120), "PUBG/MaxFPS/Active.sav"),
                GameProfile(context.getString(R.string.gf_profile_tablet), "PUBG/TabletView/Active.sav")
            ),
            resetFilePath = "/storage/emulated/0/Android/data/$packageName" +
                "/files/UE4Game/ShadowTrackerExtra/ShadowTrackerExtra/Saved/SaveGames/Active.sav",
            // The custom-upload slot exists for this file: an opaque save blob the user may
            // have obtained elsewhere. Templated configs (Genshin) don't get the section.
            allowCustomUpload = true,
            // The save is a verified GVAS layout, so the inline read-modify-write editor is
            // available too — the closed-source references' core mechanism (their savedit.sh
            // sed-rewrites these same ints inside the user's own save).
            supportsSavePatching = true
        )
    }

    /**
     * Genshin Impact, from the reference project `GenshinConfig-main`.
     *
     * The game reads `hardware_model_config.json` out of its own `files/` directory and looks the
     * entries up by the device's real model, so the tuned entry in the bundled template carries a
     * placeholder model that every delivery channel substitutes with `Build.MODEL` — the reference
     * README's manual instruction ("`Your Device Model` must match the model of the device you are
     * using"), automated. The asset is the reference's file byte-for-byte; it is deliberately not
     * valid JSON (unquoted `ASTC`, `01`, `00000001h`, trailing commas), and the game's lenient
     * parser accepts it exactly as shipped — so it is never parsed or re-serialised on the way
     * through, for the same reason the PUBG `Active.sav` blobs are shipped opaque.
     */
    private fun buildGenshinConfig(): GameConfig {
        return GameConfig(
            packageName = "com.miHoYo.GenshinImpact",
            saveDir = "/Android/data/com.miHoYo.GenshinImpact/files/",
            saveFile = "hardware_model_config.json",
            profiles = listOf(
                GameProfile(context.getString(R.string.gf_profile_genshin), "Genshin/hardware_model_config.json")
            ),
            requiresDeviceModel = true
        )
    }

    /**
     * One package-manager probe per game, off the main thread, so every picker row can show
     * install state up front. Public: the ON_RESUME hook and the GAME NOT FOUND card's
     * Re-check button re-run it — the probe ran once per view model for most of this screen's
     * life, which made the dots lie after an install/uninstall while the app stayed open: the
     * user installs the game, comes back, and the red dot claims it is still missing.
     * [PackageManager.getPackageInfo] — the same check WuWa's own
     * editor makes (`WuwaConfigViewModel.refresh`), not `getLaunchIntentForPackage`: a
     * launch intent can be null for a real install (games that ship no MAIN launcher
     * activity, or launcher-disabled installs), and the dot must not call that "not
     * installed". What a game without a launch intent *does* break is launching, which is
     * why the launch button's own probe stays `getLaunchIntentForPackage` — it reports the
     * device's honest "Game not installed" when the launcher is what's missing.
     *
     * Package ownership: every game's package set has one owner. Profile games and Genshin
     * carry theirs in [gameConfigs]; WuWa's and GRID's live as constants in their editors
     * ([WuwaConfigManager.PACKAGE], [GridPreferences.PACKAGE]); HSR's five variants live only
     * in [HsrGameManager] — the manager answers the probe itself. The root-only dynamic sweep
     * (HSR store variants beyond the known five) stays the editor's load-time probe; a variant
     * found only there shows "unknown" here rather than a false "not installed".
     */
    fun probeInstallStates() {
        viewModelScope.launch(Dispatchers.IO) {
            val states: Map<GameType, Boolean> = gameConfigs.keys.plus(EMBEDDED_EDITOR_GAMES).mapNotNull { type ->
                val installed: Boolean? = when (type) {
                    // HSR's variant list has one owner: the editor's manager, which answers
                    // the probe itself. The root-only dynamic sweep (unknown store variants)
                    // stays the editor's load-time probe; a variant found only there shows
                    // "unknown" here, never a false "no".
                    GameType.HSR -> hsrGameManager.isAnyKnownVariantInstalled()
                    // WuWa and GRID resolve their single packages at editor load; the package
                    // constants live in the same features, so the dots can answer here too.
                    GameType.WUWA -> probePackage(WuwaConfigManager.PACKAGE)
                    GameType.GRID -> probePackage(GridPreferences.PACKAGE)
                    // Everything else carries exactly one package in its config; NONE has no
                    // config and gets no dot.
                    else -> gameConfigs[type]?.packageName?.let { probePackage(it) }
                }
                if (installed == null) return@mapNotNull null
                type to installed
            }.toMap()
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(installedGames = states) }
            }
        }
    }

    /**
     * One package-manager probe: true = installed, false = query answered and the package is
     * not there, null = the query threw (package-visibility filtering) — "unknown", never a
     * false "not installed". This is what lets a dot say "I don't know" instead of guessing.
     */
    private fun probePackage(pkg: String): Boolean? = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    } catch (_: Exception) {
        null
    }

    /**
     * Selecting a game. Editor-backed entries (HSR, WuWa, GRID — [GameType.embeddedEditor])
     * select like any other game: the screen swaps its body to that game's inline editor, and
     * every profile-push control below is hidden for them, because their editors own their
     * games' files and pushing a bundled template over them is exactly what those games must
     * not get.
     */
    fun onGameSelected(game: GameType) {
        val config = gameConfigs[game]
        _uiState.update {
            it.copy(
                selectedGame = game,
                selectedProfile = 0,
                profileLabels = config?.profiles?.map(GameProfile::label).orEmpty(),
                configFileLabel = config?.resetFileLabel,
                // The exact package the GAME NOT FOUND card should name — the one the probe
                // actually checked, not a family-level guess.
                gamePackageName = config?.packageName,
                canReset = config?.resetFilePath != null,
                canUploadCustom = config?.allowCustomUpload == true,
                canPatchSave = config?.supportsSavePatching == true,
                // A fresh selection starts the editor from "read the file first" — a stale
                // read from the previously selected game would describe the wrong file.
                saveRead = null,
                savePatchReport = null,
                saveEdits = emptyMap()
            )
        }
    }
    fun onProfileSelected(profile: Int) = _uiState.update { it.copy(selectedProfile = profile) }

    fun onApplyProfile() {
        if (setSelectedAssetPathFromProfile()) {
            _uiState.update { it.copy(showMethodChooser = true) }
        }
    }

    // ------------------------------------------------------------- save editor (PUBG)

    /**
     * Reads the game's save through root or Shizuku and reports what it holds. The editor edits
     * only values it has seen in the file, so this runs before the chips enable — the same
     * read-back-first discipline Gaming Mode uses before it writes anything.
     */
    fun onReadSave() {
        val config = gameConfigs[_uiState.value.selectedGame] ?: return
        if (config.packageName !in PUBG_PACKAGES) return
        launchIoWithLoading(BusyArea.SAVE_READ) {
            val bytes = pullSaveBytes(config) ?: return@launchIoWithLoading true
            val read = PubgSavePatcher.read(bytes)
            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        saveRead = read,
                        saveEdits = emptyMap(),
                        savePatchReport = null
                    )
                }
                when {
                    !read.isGvas -> showSnackbar(context.getString(R.string.gf_not_pubg_save))
                    else -> showSnackbar(read.summary())
                }
            }
            true
        }
    }

    /** Selects or clears one tier's edit set from the editor's chips. */
    fun onSaveEditSelected(name: String, value: Int?) {
        _uiState.update { state ->
            val edits = state.saveEdits.toMutableMap()
            if (value == null) {
                // Render quality and the camera pair are edited as sets — clearing the anchor
                // field clears its partner too, so no half-set ever survives a deselect.
                edits.keys.removeAll { key ->
                    key == name ||
                        (name == PubgSavePatcher.FIELD_BATTLE_RENDER && key == PubgSavePatcher.FIELD_LOBBY_RENDER) ||
                        (name == PubgSavePatcher.FIELD_TP_VIEW && key == PubgSavePatcher.FIELD_FP_VIEW)
                }
            } else {
                edits.putAll(
                    when (name) {
                        PubgSavePatcher.FIELD_BATTLE_FPS -> PubgSavePatcher.fpsEdits(value)
                        PubgSavePatcher.FIELD_BATTLE_RENDER -> PubgSavePatcher.renderEdits(value)
                        // Both camera fields move together — a lone TpViewValue beside the file's
                        // own FpViewValue would be a half-identity the references never ship.
                        PubgSavePatcher.FIELD_TP_VIEW -> PubgSavePatcher.viewEdits(
                            tpView = value,
                            fpView = PubgSavePatcher.VIEW_PRESETS
                                .firstOrNull { it.tpView == value }?.fpView ?: value
                        )
                        else -> mapOf(name to value)
                    }
                )
            }
            state.copy(saveEdits = edits)
        }
    }

    /**
     * Applies the selected edits to the save: pull, back up, patch, push, read back. All four
     * steps run through the same privileged channel as every other overwrite here — pull and
     * push via the direct binder channel (root cp fallback), the patch itself in-process
     * ([PubgSavePatcher]), and the pre-write backup through [ConfigBackupStore] like every
     * other write this screen makes.
     */
    fun onApplySaveEdits() {
        val config = gameConfigs[_uiState.value.selectedGame] ?: return
        val edits = _uiState.value.saveEdits
        if (edits.isEmpty()) {
            showSnackbar(context.getString(R.string.gf_pick_tier_first))
            return
        }
        launchIoWithLoading(BusyArea.SAVE_APPLY) {
            val original = pullSaveBytes(config)
                ?: return@launchIoWithLoading true // the pull already reported its failure
            val patched = when (val result = PubgSavePatcher.patch(original, edits)) {
                is PubgSavePatcher.PatchResult.Ok -> result
                is PubgSavePatcher.PatchResult.Refused -> {
                    withContext(Dispatchers.Main) {
                        showSnackbar(context.getString(R.string.gf_save_changed,
                            result.reasons.entries.joinToString(" · ") { "${it.key} — ${it.value}" }))
                    }
                    return@launchIoWithLoading true
                }
            }
            backupStore.save(config.packageName, config.saveFile, original)
            if (!pushSaveBytes(config, patched.data)) return@launchIoWithLoading true

            // The push is not the report — read the file back and say what it holds now.
            val readBack = pullSaveBytes(config)?.let { PubgSavePatcher.read(it) }
            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        saveRead = readBack ?: it.saveRead,
                        saveEdits = emptyMap(),
                        savePatchReport = readBack?.summary()
                    )
                }
                showSnackbar(
                    if (readBack != null) context.getString(R.string.gf_applied_holds, readBack.summary())
                    else context.getString(R.string.gf_written_no_readback)
                )
            }
            true
        }
    }

    /**
     * The save's bytes, or null with the failure already reported. Two distinct nulls:
     * [PullOutcome.MISSING] means the privileged channel answered and the file is not there
     * (the game has not created it yet); [PullOutcome.NO_CHANNEL] means no privileged channel
     * could answer at all. The old single message collapsed both into "launch the game once",
     * which read as nonsense on a device where root and Shizuku were both gone.
     */
    private sealed interface PullOutcome {
        data class Ok(val bytes: ByteArray) : PullOutcome
        data object Missing : PullOutcome
        data object NoChannel : PullOutcome
    }

    private suspend fun pullSaveOutcome(config: GameConfig): PullOutcome {
        val destPath = (Environment.getExternalStorageDirectory().path + config.saveDir) + config.saveFile

        // Direct binder read first: one call inside the service's own process. This removed
        // the multi-second apply on Shizuku devices, where every `cp` used to cost a fork and
        // the first one after a cold start additionally the user-service spawn. forceBind
        // clears a stale block-cache first — an explicit user action deserves one real
        // attempt, not the cached memory of a previous failure. When the user service itself
        // cannot start (wedged daemon, version-gated restart), readFileDirect still answers
        // through the reference's remote-shell channel, so this line no longer collapses to
        // null just because a helper refused to spawn. Its empty answer is the file's absence
        // — the existence probe every write below is gated on.
        shellRunner.readFileDirect(destPath, forceBind = true)?.let { bytes ->
            return if (bytes.isNotEmpty()) PullOutcome.Ok(bytes) else PullOutcome.Missing
        }

        // Root path (readFileDirect returns null without any Shizuku channel): a straight
        // read through the root shell's own process.
        if (shellRunner.isRootAvailable()) {
            val pull = File.createTempFile("pubgread_", "_" + config.saveFile, context.externalCacheDir ?: context.cacheDir)
            try {
                pull.delete()
                val result = shellRunner.execSafeResult("cp", "-f", destPath, pull.absolutePath)
                if (result.isSuccess && pull.exists() && pull.length() > 0L) {
                    return PullOutcome.Ok(pull.readBytes())
                }
                return PullOutcome.Missing
            } finally {
                pull.delete()
            }
        }

        // Every Shizuku channel answered with null and root is absent. This is now genuinely
        // "no channel": the binder does not answer, the permission is missing, or the remote
        // process itself refused — not merely "the helper did not start".
        return PullOutcome.NoChannel
    }

    private suspend fun pullSaveBytes(config: GameConfig): ByteArray? {
        val outcome = pullSaveOutcome(config)
        val bytes: ByteArray? = when (outcome) {
            is PullOutcome.Ok -> outcome.bytes
            PullOutcome.Missing -> {
                withContext(Dispatchers.Main) {
                    showSnackbar(
                        context.getString(R.string.gf_save_not_created, config.saveFile, config.saveDir)
                    )
                }
                null
            }
            PullOutcome.NoChannel -> {
                withContext(Dispatchers.Main) {
                    showSnackbar(
                        if (Shizuku.pingBinder()) context.getString(R.string.gf_shizuku_channel_unavailable)
                        else context.getString(R.string.gf_no_privileged_channel)
                    )
                }
                null
            }
        }
        // The reference tool reports its Shizuku failures through the command's own exit code,
        // never through a helper-lifecycle state; mirror that by dropping the stale proxy when
        // a read came back channel-less while the binder still pings, so the next attempt
        // starts from a fresh bind rather than a cached dead one.
        if (outcome is PullOutcome.NoChannel && Shizuku.pingBinder()) shellRunner.markShizukuServiceUnreachable()
        return bytes
    }

    /**
     * Writes [bytes] over the game's save and reports what storage actually holds, not what
     * was sent: the direct binder channel's own flush-and-sync answer first, else the cp
     * exit code over root, else the shell fallback — each verified by a read-back at the
     * caller ([onApplySaveEdits]).
     */
    private suspend fun pushSaveBytes(config: GameConfig, bytes: ByteArray): Boolean {
        val destDir = Environment.getExternalStorageDirectory().path + config.saveDir
        val destPath = destDir + config.saveFile

        // Direct binder write: the service mkdirs, writes, flushes and fsyncs inside its own
        // process — one call, and the returned boolean is the report. When the helper will
        // not start, writeFileDirect falls through to the remote-shell channel (staged in
        // /data/local/tmp, `cp`'d over — the reference's own staging shape), so a wedged
        // daemon no longer ends the apply.
        val direct = shellRunner.writeFileDirect(destPath, bytes)
        if (direct == true) return true
        if (direct == false) {
            withContext(Dispatchers.Main) {
                showSnackbar(context.getString(R.string.gf_write_refused, destPath))
            }
            return false
        }
        // null = no Shizuku channel answered; fall through to the root channel.

        if (shellRunner.isRootAvailable()) {
            val pushFile = File.createTempFile("pubgpush_", "_" + config.saveFile, context.externalCacheDir ?: context.cacheDir)
            try {
                pushFile.writeBytes(bytes)
                val mkdir = shellRunner.execSafeResult("mkdir", "-p", destDir)
                if (!mkdir.isSuccess) {
                    withContext(Dispatchers.Main) {
                        showSnackbar(context.getString(R.string.gf_mkdir_failed, mkdir.exitCode, destDir))
                    }
                    return false
                }
                val result = shellRunner.execSafeResult("cp", "-f", pushFile.absolutePath, destPath)
                if (!result.isSuccess) {
                    withContext(Dispatchers.Main) {
                        showSnackbar(context.getString(R.string.gf_cp_failed, result.exitCode, result.stderr.ifBlank { context.getString(R.string.gf_no_output) }))
                    }
                    return false
                }
                return true
            } finally {
                pushFile.delete()
            }
        }

        val pushFile = File.createTempFile("pubgpush_", "_" + config.saveFile, context.externalCacheDir ?: context.cacheDir)
        try {
            pushFile.writeBytes(bytes) // written fresh by this app, so no privileged-uid leftover sits on it
            shellRunner.execSafe("mkdir", "-p", destDir)
            val result = shellRunner.execSafeResult("cp", "-f", pushFile.absolutePath, destPath)
            if (!result.isSuccess) {
                withContext(Dispatchers.Main) {
                    showSnackbar(context.getString(R.string.gf_cp_failed, result.exitCode, result.stderr.ifBlank { context.getString(R.string.gf_no_output) }))
                }
                return false
            }
            return true
        } finally {
            pushFile.delete()
        }
    }

    fun dismissMethodChooser() = _uiState.update { it.copy(showMethodChooser = false) }

    /**
     * Entry point for the reset channel (TODO.md B14): deletes the game's own `Active.sav` so
     * it regenerates one from defaults on the next launch — the clean revert for everything
     * this screen pushed, which the reference implements as `deleteActiveSavWithShizuku()` /
     * `deleteActiveSavWithSAF()` in `referance/gamingtools/BattleGrounds_GFX-main`.
     */
    fun onResetSave() {
        val config = gameConfigs[_uiState.value.selectedGame] ?: return
        if (config.resetFilePath == null) {
            showSnackbar(context.getString(R.string.gf_not_reset_deletable))
            return
        }
        _uiState.update { it.copy(showResetChooser = true) }
    }

    fun dismissResetChooser() = _uiState.update { it.copy(showResetChooser = false) }

    fun onResetMethodSelected(which: Int) {
        _uiState.update { it.copy(showResetChooser = false) }
        when (which) {
            0 -> performRootAction { resetViaShizuku() }
            1 -> resetViaShizuku()
            2 -> resetViaSaf()
        }
    }

    /**
     * Runs [action] through the privileged shell when root is present — the method chooser's
     * "ROOT" entry — falling back to the Shizuku path when it is not. Kept as a gate rather
     * than a separate copy of every action so each channel keeps its own single implementation.
     */
    private fun performRootAction(action: () -> Unit) {
        if (!shellRunner.isRootAvailable(force = true)) {
            showSnackbar(context.getString(R.string.gf_root_fallback))
            action()
            return
        }
        action()
    }

    private fun resetViaShizuku() {
        val config = gameConfigs[_uiState.value.selectedGame] ?: return
        val path = config.resetFilePath ?: return
        if (!Shizuku.pingBinder()) {
            showSnackbar(context.getString(R.string.gf_shizuku_not_running))
            return
        }
        launchIoWithLoading(BusyArea.RESET) {
            // Existence probe first: rm -f succeeds on a file that is not there, so the old
            // exit-code-only report announced "deleted" for a file that never existed. The
            // empty read answers "not there" and gets its own message — "nothing to reset",
            // matching what the SAF reset path already reported.
            val existing = shellRunner.readFileDirect(path, forceBind = true)
            val message = when {
                existing == null ->
                    if (Shizuku.pingBinder()) context.getString(R.string.gf_shizuku_channel_unavailable_short)
                    else context.getString(R.string.gf_no_privileged_channel)
                existing.isEmpty() -> context.getString(R.string.gf_not_there_nothing, config.resetFileLabel)
                else -> {
                    val result = shellRunner.execSafeResult("rm", "-f", path)
                    when {
                        result.isSuccess ->
                            context.getString(R.string.gf_deleted_rebuilds, config.resetFileLabel)
                        else -> context.getString(R.string.gf_rm_failed, result.exitCode, result.stderr.ifBlank { context.getString(R.string.gf_no_output) })
                    }
                }
            }
            withContext(Dispatchers.Main) { showSnackbar(message) }
            true
        }
    }

    private fun resetViaSaf() {
        val config = gameConfigs[_uiState.value.selectedGame] ?: return
        if (config.resetFilePath == null) return
        val treeUri = getCustomGameTreeUri(config.packageName)
        if (treeUri != null) {
            performSafReset(treeUri, config)
        } else {
            // Deleting needs a persisted tree grant for the game's data folder — same shape as
            // the custom-upload channel: remember the package, park the real work in
            // pendingAction, and onCustomFolderPicked runs it once the grant exists.
            customPackagePending = config.packageName
            pendingAction = {
                getCustomGameTreeUri(config.packageName)?.let { performSafReset(it, config) }
            }
            _events.tryEmit(EditEvent.LaunchFolderPicker)
        }
    }

    private fun performSafReset(treeUri: Uri, config: GameConfig) {
        val path = config.resetFilePath ?: return
        launchIoWithLoading(BusyArea.RESET) {
            when (deleteSafDocument(treeUri, config, path)) {
                SafDeleteResult.DELETED -> showSnackbar(context.getString(R.string.gf_deleted_rebuilds, config.resetFileLabel))
                SafDeleteResult.NOT_FOUND -> showSnackbar(context.getString(R.string.gf_not_found_nothing, config.resetFileLabel))
                SafDeleteResult.FAILED -> showSnackbar(context.getString(R.string.gf_provider_refused_delete))
            }
            true
        }
    }

    /** Keeps the three SAF outcomes distinct rather than collapsing to a boolean. */
    private enum class SafDeleteResult { DELETED, NOT_FOUND, FAILED }

    private fun deleteSafDocument(
        treeUri: Uri,
        config: GameConfig,
        absolutePath: String
    ): SafDeleteResult {
        // The tree grant is rooted at the game's Android/data/<pkg> folder, and resetFilePath is
        // absolute from /storage/emulated/0 — strip the game-data prefix to get the document-id
        // tail, the same construction the reference builds from its picked folder id.
        val gameDataRoot = "/storage/emulated/0/Android/data/${config.packageName}"
        return try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri) +
                absolutePath.removePrefix(gameDataRoot)
            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            val exists = DocumentFile.fromSingleUri(context, uri)?.exists() == true
            when {
                !exists -> SafDeleteResult.NOT_FOUND
                DocumentsContract.deleteDocument(context.contentResolver, uri) -> SafDeleteResult.DELETED
                else -> SafDeleteResult.FAILED
            }
        } catch (_: Exception) {
            SafDeleteResult.FAILED
        }
    }

    /**
     * Backup & restore channel (TODO.md B13). Every overwrite this screen performs first saves
     * the game's current file through [ConfigBackupStore]; this is the way back. From the
     * references' safety-backup systems — `hsrgraphicdroid-main` ("Safety Backup System") and
     * `WuWa-Config-Android-main/config/BackupStore.kt` — which both restore by pushing the saved
     * bytes back through the *same* channel that overwrote them, never by a side door.
     */
    fun onRestoreBackup() {
        val config = gameConfigs[_uiState.value.selectedGame] ?: return
        val backups = backupStore.list(config.packageName)
        if (backups.isEmpty()) {
            showSnackbar(context.getString(R.string.gf_no_backups))
            return
        }
        _uiState.update { it.copy(backups = backups, showBackupDialog = true) }
    }

    fun dismissBackupDialog() = _uiState.update { it.copy(showBackupDialog = false) }

    fun onBackupChosen(entry: ConfigBackupStore.Entry) {
        _uiState.update { it.copy(showBackupDialog = false, showRestoreChooser = true) }
        pendingRestore = entry
    }

    fun dismissRestoreChooser() {
        pendingRestore = null
        _uiState.update { it.copy(showRestoreChooser = false) }
    }

    fun onRestoreMethodSelected(which: Int) {
        _uiState.update { it.copy(showRestoreChooser = false) }
        val entry = pendingRestore ?: return
        pendingRestore = null
        when (which) {
            0 -> performRootAction { restoreViaShizuku(entry) }
            1 -> restoreViaShizuku(entry)
            2 -> restoreViaSaf(entry)
        }
    }

    fun onDeleteBackup(entry: ConfigBackupStore.Entry) {
        val config = gameConfigs[_uiState.value.selectedGame] ?: return
        launchIoWithLoading(BusyArea.BACKUP_DELETE) {
            if (!backupStore.delete(entry)) {
                withContext(Dispatchers.Main) { showSnackbar(context.getString(R.string.gf_delete_backup_failed)) }
            } else {
                val remaining = backupStore.list(config.packageName)
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(backups = remaining, showBackupDialog = remaining.isNotEmpty()) }
                }
            }
            true
        }
    }

    private fun restoreViaShizuku(entry: ConfigBackupStore.Entry) {
        val config = gameConfigs[_uiState.value.selectedGame] ?: return
        if (!Shizuku.pingBinder()) {
            showSnackbar(context.getString(R.string.gf_shizuku_not_running))
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            showSnackbar(context.getString(R.string.gf_shizuku_permission_required))
            return
        }
        launchIoWithLoading(BusyArea.RESTORE) {
            val bytes = backupStore.readBytes(entry)
                ?: return@launchIoWithLoading run {
                    withContext(Dispatchers.Main) { showSnackbar(context.getString(R.string.gf_backup_unreadable)) }
                    true
                }
            // The restore payload is written by this app under a fresh name, then copied over the
            // game's file by the privileged shell — the same ownership split as a profile push,
            // so no privileged-uid leftover can sit on a path this app later writes.
            val tempFile = File.createTempFile("restore_", "_" + config.saveFile, context.externalCacheDir ?: context.cacheDir)
            try {
                tempFile.writeBytes(bytes)
                val destDir = Environment.getExternalStorageDirectory().path + config.saveDir
                val destPath = destDir + config.saveFile
                shellRunner.execSafe("mkdir", "-p", destDir)
                val result = shellRunner.execSafeResult("cp", "-f", tempFile.absolutePath, destPath)
                val message = when {
                    result.isSuccess -> context.getString(R.string.gf_restored_backup, entry.formattedTimestamp())
                    else -> context.getString(R.string.gf_cp_failed, result.exitCode, result.stderr.ifBlank { context.getString(R.string.gf_no_output) })
                }
                withContext(Dispatchers.Main) { showSnackbar(message) }
            } finally {
                tempFile.delete()
            }
            true
        }
    }

    private fun restoreViaSaf(entry: ConfigBackupStore.Entry) {
        val config = gameConfigs[_uiState.value.selectedGame] ?: return
        val treeUri = getCustomGameTreeUri(config.packageName)
        if (treeUri != null) {
            performSafRestore(treeUri, config, entry)
        } else {
            // Same shape as the reset channel: remember the game, park the restore in
            // pendingAction, and the folder grant runs it once it exists.
            customPackagePending = config.packageName
            pendingAction = {
                getCustomGameTreeUri(config.packageName)?.let { performSafRestore(it, config, entry) }
            }
            _events.tryEmit(EditEvent.LaunchFolderPicker)
        }
    }

    private fun performSafRestore(treeUri: Uri, config: GameConfig, entry: ConfigBackupStore.Entry) {
        launchIoWithLoading(BusyArea.RESTORE) {
            val bytes = backupStore.readBytes(entry)
            if (bytes == null) {
                withContext(Dispatchers.Main) { showSnackbar(context.getString(R.string.gf_backup_unreadable)) }
                return@launchIoWithLoading true
            }
            val dir = getOrCreateTargetDir(treeUri, config)
            if (dir == null) {
                withContext(Dispatchers.Main) { showSnackbar(context.getString(R.string.gf_provider_refused_folder)) }
                return@launchIoWithLoading true
            }
            val target = dir.findFile(config.saveFile) ?: dir.createFile(MIME_BINARY, config.saveFile)
            if (target == null) {
                withContext(Dispatchers.Main) { showSnackbar(context.getString(R.string.gf_provider_refused_create)) }
                return@launchIoWithLoading true
            }
            context.contentResolver.openOutputStream(target.uri, "w")?.use { it.write(bytes) }
                ?: return@launchIoWithLoading run {
                    withContext(Dispatchers.Main) { showSnackbar(context.getString(R.string.gf_provider_refused_write)) }
                    true
                }
            withContext(Dispatchers.Main) { showSnackbar(context.getString(R.string.gf_restored_backup, entry.formattedTimestamp())) }
            true
        }
    }

    fun onMethodSelected(which: Int) {
        _uiState.update { it.copy(showMethodChooser = false) }
        when (which) {
            0 -> performRootAction { checkAndStartShizukuAction() }
            1 -> checkAndStartShizukuAction()
            2 -> {
                val config = gameConfigs[_uiState.value.selectedGame]
                _events.tryEmit(EditEvent.LaunchSafPicker(config?.saveDir ?: ""))
            }
            3 -> {
                pendingAction = { handleZArchiverAction() }
                handleZArchiverAction()
            }
        }
    }

    fun onSelectFile() {
        _events.tryEmit(EditEvent.LaunchFilePicker)
    }

    fun onCustomFilePicked(uri: Uri) {
        setCustomSelection(uri)
    }

    fun onUploadFile() {
        uploadCustomContent(
            selectedFileUri,
            context.getString(R.string.gf_select_file_first),
            context.getString(R.string.custom_upload_success_file),
            shAction = { uri, pkg -> uploadCustomFileWithShizuku(uri, pkg) },
            safAction = { uri, pkg -> uploadCustomFileWithSaf(uri, pkg) }
        )
    }

    fun onClearSelection() {
        setCustomSelection(null)
    }

    fun onSafPicked(treeUri: Uri) {
        performSafFileCopy(treeUri, _uiState.value.selectedGame)
    }

    /**
     * The bytes a delivery channel should push for [assetPath], after the game's own transform.
     *
     * Only the model-naming games have one: Genshin's template must carry the device's real
     * `Build.MODEL` or the game's lookup misses it and the pushed file changes nothing. Binary
     * blobs (PUBG) pass through untouched.
     */
    private fun assetBytes(config: GameConfig, assetPath: String): ByteArray {
        val raw = context.assets.open(assetPath).use { it.readBytes() }
        if (!config.requiresDeviceModel) return raw
        return GenshinConfigTemplate.withThisDevice(String(raw, Charsets.UTF_8))
            .toByteArray(Charsets.UTF_8)
    }

    private fun performSafFileCopy(treeUri: Uri, game: GameType) {
        val config = gameConfigs[game] ?: return
        val assetPath = requireSelectedAssetPath() ?: return
        launchIoWithLoading(BusyArea.PROFILE_PUSH, successMessage = context.getString(R.string.gf_success_saf)) {
            val pickedDir = DocumentFile.fromTreeUri(context, treeUri) ?: throw IOException("Cannot write")
            val existingFile = pickedDir.findFile(config.saveFile)
            val existingBytes = if (existingFile != null && existingFile.length() > 0L) {
                runCatching {
                    context.contentResolver.openInputStream(existingFile.uri)?.use { it.readBytes() }
                }.getOrNull()
            } else null
            // Safety backup before the overwrite — the bytes the device actually held, never
            // the payload about to replace them. A file the game never wrote backs up nothing.
            if (existingBytes != null && existingBytes.isNotEmpty()) {
                backupStore.save(config.packageName, config.saveFile, existingBytes)
            }
            // A model-named template is always the thing to push: the file already sitting there is
            // the game's stock config, and re-writing it would be a no-op dressed as success.
            // PUBG keeps its existing behaviour — the game's current save is preferred, and a
            // missing or unreadable save is a seed: the bundled profile's bytes re-add the file
            // and the game takes over from its next launch (same rule as the Shizuku channel).
            val inputBytes = if (config.requiresDeviceModel || existingBytes == null || existingBytes.isEmpty()) {
                assetBytes(config, assetPath)
            } else {
                existingBytes
            }
            val file = existingFile ?: pickedDir.createFile(MIME_BINARY, config.saveFile) ?: throw IOException("Cannot create file")
            context.contentResolver.openOutputStream(file.uri, "w")?.use { it.write(inputBytes) }
            true
        }
    }

    fun onStoragePermissionResult() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            pendingAction?.invoke()
            pendingAction = null
        }
    }

    fun onCustomFolderPicked(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            customPackagePending?.let { pkg ->
                customUploadPrefs.edit { putString(getCustomPrefsKey(pkg), uri.toString()) }
            }
            pendingAction?.invoke()
            pendingAction = null
        } catch (_: Exception) {}
    }

    fun onLaunchGame() {
        val config = gameConfigs[_uiState.value.selectedGame] ?: return
        val intent = context.packageManager.getLaunchIntentForPackage(config.packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } else {
            showSnackbar(context.getString(R.string.gf_game_not_installed_toast))
        }
    }

    private fun setSelectedAssetPathFromProfile(): Boolean {
        val config = gameConfigs[_uiState.value.selectedGame] ?: return false
        val profiles = config.profiles
        // A profile index the game does not have (the leftover PUBG selection after switching to a
        // one-profile game) is clamped, not treated as missing — the user still gets a file to push.
        selectedAssetPath = profiles.getOrNull(
            _uiState.value.selectedProfile.coerceAtMost(profiles.lastIndex)
        )?.assetPath
        if (selectedAssetPath == null) {
            showSnackbar(context.getString(R.string.apply_profile_first))
            return false
        }
        return true
    }

    private fun setCustomSelection(fileUri: Uri?) {
        selectedFileUri = fileUri
        _uiState.update {
            it.copy(
                selectedItemText = if (fileUri != null) {
                    context.getString(R.string.selected_item, getDisplayName(fileUri) ?: fileUri.toString())
                } else {
                    context.getString(R.string.selected_item_placeholder)
                }
            )
        }
    }

    private fun uploadCustomContent(
        uri: Uri?,
        msg: String,
        success: String,
        shAction: suspend (Uri, GameConfig) -> Boolean,
        safAction: (Uri, GameConfig) -> Boolean
    ) {
        val config = gameConfigs[_uiState.value.selectedGame] ?: return
        if (uri == null) {
            showSnackbar(context.getString(R.string.custom_upload_failed, msg))
            return
        }
        launchIoWithLoading(BusyArea.CUSTOM_UPLOAD, successMessage = success) {
            if (canUseShizukuForCustom()) shAction(uri, config)
            else safAction(uri, config)
        }
    }

    private fun canUseShizukuForCustom(): Boolean {
        if (!Shizuku.pingBinder()) return false
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(0)
            return false
        }
        return true
    }

    private suspend fun uploadCustomFileWithShizuku(fileUri: Uri, config: GameConfig): Boolean {
        // The game's own save directory and file, straight from the config — this path used to
        // hardcode PUBG's UE4Game tree, which sent a Genshin upload to a directory the game never
        // reads.
        val saveDir = "/storage/emulated/0" + config.saveDir.trimEnd('/')
        val targetPath = "$saveDir/${config.saveFile}"
        val tempFile = File(context.externalCacheDir ?: context.cacheDir, "Custom_${config.saveFile}")
        return try {
            context.contentResolver.openInputStream(fileUri)?.use { input ->
                FileOutputStream(tempFile).use { output -> input.copyTo(output) }
            } ?: return false
            // Existence probe before the overwrite: a custom file dropped onto a save the game
            // has never written would sit in a directory tree the game may not even have
            // created, and the push would report success for a file nothing reads. Same gate
            // as the profile push and the save editor.
            val existingBytes = shellRunner.readFileDirect(targetPath, forceBind = true)
            if (existingBytes == null || existingBytes.isEmpty()) {
                withContext(Dispatchers.Main) {
                    showSnackbar(
                        context.getString(R.string.gf_custom_target_missing, config.saveFile, saveDir)
                    )
                }
                return false
            }
            // Safety backup before the overwrite: the bytes the device actually held, fetched by
            // the same probe — no second pull, and only a real file is ever backed up.
            if (existingBytes.isNotEmpty()) {
                runCatching { existingBytes }.getOrNull()?.let { bytes ->
                    backupStore.save(config.packageName, config.saveFile, bytes)
                }
            }
            shellRunner.execSafe("mkdir", "-p", saveDir)
            shellRunner.execSafe("cp", "-f", tempFile.absolutePath, targetPath)
            true
        } catch (_: Exception) {
            false
        } finally {
            tempFile.delete()
        }
    }

    private fun uploadCustomFileWithSaf(fileUri: Uri, config: GameConfig): Boolean {
        val treeUri = getCustomGameTreeUri(config.packageName)
            ?: run {
                requestCustomGameFolderAccess(config.packageName)
                return false
            }
        return try {
            val saveGamesDir = getOrCreateTargetDir(treeUri, config) ?: return false
            // Safety backup before the delete-and-recreate below, which would otherwise leave
            // the old generation unreachable through the provider.
            saveGamesDir.findFile(config.saveFile)?.takeIf { it.length() > 0L }?.let { existing ->
                runCatching {
                    context.contentResolver.openInputStream(existing.uri)?.use { it.readBytes() }
                }.getOrNull()?.let { bytes ->
                    backupStore.save(config.packageName, config.saveFile, bytes)
                }
            }
            saveGamesDir.findFile(config.saveFile)?.delete()
            val target = saveGamesDir.createFile(MIME_BINARY, config.saveFile) ?: return false
            context.contentResolver.openInputStream(fileUri)?.use { input ->
                context.contentResolver.openOutputStream(target.uri, "w")?.use { output -> input.copyTo(output) }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun requestCustomGameFolderAccess(packageName: String) {
        pendingAction = null
        customPackagePending = packageName
        _events.tryEmit(EditEvent.LaunchFolderPicker)
        showSnackbar(context.getString(R.string.custom_upload_need_folder_access, packageName))
    }

    private fun getCustomGameTreeUri(packageName: String): Uri? =
        customUploadPrefs.getString(getCustomPrefsKey(packageName), null)?.toUri()

    private fun getCustomPrefsKey(packageName: String): String = "saf_tree_uri_$packageName"

    private fun getOrCreateTargetDir(treeUri: Uri, config: GameConfig): DocumentFile? {
        val base = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        // The tree the user picked is the game's Android/data root, and the config's saveDir says
        // where the game actually reads from — PUBG nests seven levels, Genshin sits directly in
        // files/. Derived rather than hardcoded so a game added without its own branch works.
        val pathSegments = config.saveDir.trim('/').split('/').filter { it.isNotBlank() }
        var current = base
        for (segment in pathSegments) {
            val next = current.findFile(segment) ?: current.createDirectory(segment)
            if (next == null) return null
            current = next
        }
        return current
    }

    private fun getDisplayName(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1 && it.moveToFirst()) it.getString(index) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun checkAndStartShizukuAction() {
        val config = gameConfigs[_uiState.value.selectedGame] ?: return
        if (selectedAssetPath == null) {
            showSnackbar(context.getString(R.string.apply_profile_first))
            return
        }
        if (!Shizuku.pingBinder()) {
            showSnackbar(context.getString(R.string.gf_shizuku_not_running))
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(0)
        } else {
            performShizukuCopy(config)
        }
    }

    private fun performShizukuCopy(config: GameConfig) {
        val assetPath = requireSelectedAssetPath() ?: return
        launchIoWithLoading(BusyArea.PROFILE_PUSH) {
            var pushFile: File? = null
            try {
                val cacheDir = context.externalCacheDir ?: context.cacheDir
                val destDir = Environment.getExternalStorageDirectory().path + config.saveDir
                val destPath = destDir + config.saveFile

                // The probe keeps "no channel" distinct from "missing": null aborts (nothing can
                // be reported honestly without a channel), empty means the game has not written
                // its save yet — and that is a seed, not a refusal. The reference tools push the
                // same way (BattleGrounds_GFX stages in /data/local/tmp and moves, never checking
                // first): applying a profile re-adds the file with the bundled profile's bytes
                // and the game takes over from its next launch. The save editor keeps its own
                // refusal (pullSaveBytes → Missing) because patching needs the file's real bytes.
                val existingBytes = shellRunner.readFileDirect(destPath, forceBind = true)
                if (existingBytes == null) {
                    withContext(Dispatchers.Main) {
                        showSnackbar(
                            if (Shizuku.pingBinder()) context.getString(R.string.gf_shizuku_channel_unavailable_short)
                            else context.getString(R.string.gf_no_privileged_channel)
                        )
                    }
                    return@launchIoWithLoading false
                }
                val existing = existingBytes.takeIf { it.isNotEmpty() }

                // Safety backup before the overwrite — the bytes the device actually held, not
                // the payload about to replace them (see ConfigBackupStore). A file the game
                // never wrote backs up nothing. A failed backup does not block the push: the
                // user asked for the apply, and the restore path simply won't have this
                // generation to return to.
                existing?.let { bytes ->
                    runCatching { backupStore.save(config.packageName, config.saveFile, bytes) }
                }

                // PUBG prefers the game's current save — the bundled asset only seeds a first
                // apply; a model-named template is always the payload, because the file already
                // on the device is the stock one the push exists to replace.
                val inputBytes: ByteArray =
                    if (!config.requiresDeviceModel && existing != null) existing
                    else assetBytes(config, assetPath)

                // The push file is created and written by this app alone, under a fresh name on
                // every run, so no privileged-uid leftover can ever be sitting on it.
                pushFile = File.createTempFile("push_", "_" + config.saveFile, cacheDir)
                pushFile.writeBytes(inputBytes)

                shellRunner.execSafe("mkdir", "-p", destDir)
                shellRunner.execSafe("cp", "-f", pushFile.absolutePath, destPath)

                withContext(Dispatchers.Main) {
                    showSnackbar(context.getString(R.string.gf_success_shizuku))
                }
                true
            } catch (e: Exception) {
                throw e
            } finally {
                pushFile?.delete()
            }
        }
    }

    private fun handleZArchiverAction() {
        if (!checkStoragePermission()) return
        val config = gameConfigs[_uiState.value.selectedGame] ?: return
        val assetPath = requireSelectedAssetPath() ?: return
        launchIoWithLoading(BusyArea.PROFILE_PUSH) {
            pasteFileToDownloads(config, assetPath)
            withContext(Dispatchers.Main) { _events.tryEmit(EditEvent.ShowZArchiverDialog) }
            true
        }
    }

    @Throws(IOException::class)
    private fun pasteFileToDownloads(config: GameConfig, assetPath: String) {
        // Exported through the same transform as a direct push, so the manual file the user moves
        // with ZArchiver carries this device's model rather than the placeholder.
        val inputBytes = assetBytes(config, assetPath)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                android.content.ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, config.saveFile)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
            ) ?: throw IOException("MediaStore failed")
            context.contentResolver.openOutputStream(uri)?.use { it.write(inputBytes) }
        } else {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), config.saveFile).writeBytes(inputBytes)
        }
    }

    fun launchZArchiver(): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(ZARCHIVER_PACKAGE)
        return if (intent != null) {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } else {
            openPlayStore(ZARCHIVER_PACKAGE)
        }
    }

    /**
     * Opens the game's Play Store listing — the GAME NOT FOUND card's one-tap install path,
     * same fallback chain [launchZArchiver] has always used: market app first, browser URL
     * when no market app answers. Never throws; false only means every launcher refused.
     */
    fun openPlayStore(packageName: String): Boolean {
        return try {
            val marketIntent = Intent(Intent.ACTION_VIEW, "market://details?id=$packageName".toUri()).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(marketIntent)
            true
        } catch (_: Exception) {
            try {
                val webIntent = Intent(
                    Intent.ACTION_VIEW,
                    "https://play.google.com/store/apps/details?id=$packageName&hl=en".toUri()
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(webIntent)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun checkStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                _events.tryEmit(EditEvent.LaunchAllFilesAccess)
                return false
            }
            return true
        }
        val granted = context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            _events.tryEmit(EditEvent.LaunchAllFilesAccess)
        }
        return granted
    }

    fun launchAllFilesAccess(): Intent? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return try {
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = "package:${context.packageName}".toUri()
                }
            } catch (_: Exception) {
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            }
        }
        return null
    }

    fun showSnackbar(message: String) {
        _events.tryEmit(EditEvent.Toast(message, isLong = true))
    }

    private fun requireSelectedAssetPath(): String? {
        val path = selectedAssetPath
        if (path == null) showSnackbar(context.getString(R.string.apply_profile_first))
        return path
    }

    private fun launchIoWithLoading(
        area: BusyArea,
        successMessage: String? = null,
        task: suspend () -> Boolean
    ) {
        _uiState.update { it.copy(isLoading = true, busyArea = area) }
        viewModelScope.launch(Dispatchers.IO) {
            val ok = try {
                task()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { showSnackbar(context.getString(R.string.gf_failed, e.message)) }
                false
            }
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(isLoading = false, busyArea = null) }
                if (ok && successMessage != null) showSnackbar(successMessage)
            }
        }
    }

    private companion object {
        private const val MIME_BINARY = "application/octet-stream"
        private const val ZARCHIVER_PACKAGE = "ru.zdevs.zarchiver"

        /**
         * The packages whose saves the inline editor touches — exactly the variants [initializeGameConfigs]
         * builds a PUBG config for, kept in one place so the editor's gate can never widen past it.
         */
        private val PUBG_PACKAGES = setOf(
            "com.tencent.ig", "com.pubg.krmobile", "com.vng.pubgmobile", "com.pubg.imobile", "com.rekoo.pubgm"
        )

        /**
         * The embedded editors (HSR/WuWa/GRID) are not in [gameConfigs] — their install probes
         * used to run only because the old `packages` list happened to name them. Listed here
         * so the probe loop covers them explicitly: each branch resolves its own packages.
         */
        private val EMBEDDED_EDITOR_GAMES = listOf(GameType.HSR, GameType.WUWA, GameType.GRID)
    }
}
