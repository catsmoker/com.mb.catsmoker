package com.catsmoker.app.features.editgamefiles.hsr

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.catsmoker.app.R
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Screen state for the Honkai: Star Rail graphics editor.
 *
 * [settings] is null until a root read succeeds; the screen renders its failure states from
 * [loadFailure] instead, so "not loaded yet", "cannot load", and "loaded and editable" stay
 * three visibly different things. The settings object held here is the working copy — the
 * device's own values are re-read after every successful apply, so what the screen shows
 * afterwards is what the device holds, not what we asked it to hold.
 */
data class HsrGraphicsUiState(
    val loading: Boolean = true,
    val settings: HsrGraphicsSettings? = null,
    val gamePrefs: HsrGamePreferences? = null,
    val packageName: String? = null,
    val packageLabel: String? = null,
    val prefsPath: String? = null,
    val loadFailure: HsrReadResult.Failure? = null,
    val applying: Boolean = false,
    val applyingPrefs: Boolean = false,
    /** What the last apply actually did — success carries the read-back verdict, failure the stage. */
    val lastApply: String? = null,
    val lastPrefsApply: String? = null,
    val hasBackup: Boolean = false
)

@HiltViewModel
class HsrGraphicsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gameManager: HsrGameManager
) : ViewModel() {

    sealed class HsrEvent {
        data class Toast(val message: String, val isLong: Boolean = false) : HsrEvent()
    }

    private val _uiState = MutableStateFlow(HsrGraphicsUiState())
    val uiState: StateFlow<HsrGraphicsUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<HsrEvent>()
    val events: SharedFlow<HsrEvent> = _events.asSharedFlow()

    init {
        refresh()
    }

    /** Full reload — clears the manager's package/path caches so a fresh install is found. */
    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, loadFailure = null) }
            gameManager.resetCaches()
            when (val result = gameManager.readCurrentSettings()) {
                is HsrReadResult.Success -> _uiState.update {
                    it.copy(
                        loading = false,
                        settings = result.settings,
                        gamePrefs = result.gamePrefs,
                        packageName = result.packageName,
                        packageLabel = result.packageLabel,
                        prefsPath = result.prefsPath,
                        hasBackup = gameManager.hasBackup()
                    )
                }
                is HsrReadResult.Failure -> _uiState.update {
                    it.copy(loading = false, loadFailure = result, hasBackup = gameManager.hasBackup())
                }
            }
        }
    }

    fun updateSettings(transform: (HsrGraphicsSettings) -> HsrGraphicsSettings) {
        _uiState.update { state ->
            state.settings?.let { state.copy(settings = transform(it)) } ?: state
        }
    }

    fun updatePrefs(transform: (HsrGamePreferences) -> HsrGamePreferences) {
        _uiState.update { state ->
            state.gamePrefs?.let { state.copy(gamePrefs = transform(it)) } ?: state
        }
    }

    /** Same flow as [onApply], against the QoL keys. Speed-up needs a logged-in user id. */
    fun onApplyPrefs() {
        val prefs = _uiState.value.gamePrefs ?: return
        if (_uiState.value.applyingPrefs) return
        viewModelScope.launch {
            _uiState.update { it.copy(applyingPrefs = true, lastPrefsApply = null) }

            val gameStopped = gameManager.forceStopGame()
            when (val result = gameManager.writeGamePreferences(prefs)) {
                is HsrWriteResult.Success -> {
                    val stopLine = if (gameStopped) context.getString(R.string.gf_hsr_stopped_first) else ""
                    val verdict = if (result.verified) {
                        context.getString(R.string.gf_hsr_verified)
                    } else {
                        context.getString(R.string.gf_hsr_not_verified, result.verificationDetail)
                    }
                    val backupLine = result.backup?.let { context.getString(R.string.gf_hsr_backup_taken, it.formattedTimestamp()) } ?: context.getString(R.string.gf_hsr_no_backup)
                    _uiState.update { it.copy(applyingPrefs = false, lastPrefsApply = "${stopLine}$backupLine, $verdict") }
                    _events.emit(HsrEvent.Toast(context.getString(if (result.verified) R.string.gf_hsr_prefs_verified else R.string.gf_hsr_readback_off), true))

                    (gameManager.readCurrentSettings() as? HsrReadResult.Success)?.let { fresh ->
                        _uiState.update { it.copy(gamePrefs = fresh.gamePrefs, hasBackup = gameManager.hasBackup()) }
                    }
                }
                is HsrWriteResult.Failure -> {
                    _uiState.update {
                        it.copy(applyingPrefs = false, lastPrefsApply = context.getString(R.string.gf_failed_stage_detail, result.stage, result.detail))
                    }
                    _events.emit(HsrEvent.Toast(context.getString(R.string.gf_apply_failed_detail, result.detail), true))
                }
            }
        }
    }

    /**
     * Stop the game (it would clobber the file with in-memory settings on exit), push the
     * working copy, then re-read the device. The report names every step that actually happened.
     */
    fun onApply() {
        val settings = _uiState.value.settings ?: return
        if (_uiState.value.applying) return
        viewModelScope.launch {
            _uiState.update { it.copy(applying = true, lastApply = null) }

            val gameStopped = gameManager.forceStopGame()
            when (val result = gameManager.writeSettings(settings)) {
                is HsrWriteResult.Success -> {
                    val stopLine = if (gameStopped) context.getString(R.string.gf_hsr_stopped_first) else ""
                    val verdict = if (result.verified) {
                        context.getString(R.string.gf_hsr_verified)
                    } else {
                        context.getString(R.string.gf_hsr_not_verified, result.verificationDetail)
                    }
                    val backupLine = result.backup?.let { context.getString(R.string.gf_hsr_backup_taken, it.formattedTimestamp()) } ?: context.getString(R.string.gf_hsr_no_backup)
                    _uiState.update { it.copy(applying = false, lastApply = "${stopLine}$backupLine, $verdict") }
                    _events.emit(HsrEvent.Toast(context.getString(if (result.verified) R.string.gf_hsr_gfx_verified else R.string.gf_hsr_readback_off), true))

                    // Show what the device now holds, not what we asked for.
                    (gameManager.readCurrentSettings() as? HsrReadResult.Success)?.let { fresh ->
                        _uiState.update { it.copy(settings = fresh.settings, gamePrefs = fresh.gamePrefs, hasBackup = gameManager.hasBackup()) }
                    }
                }
                is HsrWriteResult.Failure -> {
                    _uiState.update {
                        it.copy(applying = false, lastApply = context.getString(R.string.gf_failed_stage_detail, result.stage, result.detail))
                    }
                    _events.emit(HsrEvent.Toast(context.getString(R.string.gf_apply_failed_detail, result.detail), true))
                }
            }
        }
    }

    fun onRestoreBackup() {
        viewModelScope.launch {
            when (val result = gameManager.restoreLatestBackup()) {
                null -> _events.emit(HsrEvent.Toast(context.getString(R.string.gf_no_backup_yet), false))
                is HsrWriteResult.Success -> {
                    _uiState.update { it.copy(lastApply = context.getString(R.string.gf_hsr_restored_ok)) }
                    _events.emit(HsrEvent.Toast(context.getString(R.string.gf_backup_restored), false))
                    (gameManager.readCurrentSettings() as? HsrReadResult.Success)?.let { fresh ->
                        _uiState.update { it.copy(settings = fresh.settings, gamePrefs = fresh.gamePrefs, hasBackup = gameManager.hasBackup()) }
                    }
                }
                is HsrWriteResult.Failure -> {
                    _uiState.update { it.copy(lastApply = context.getString(R.string.gf_restore_failed_stage, result.stage, result.detail)) }
                    _events.emit(HsrEvent.Toast(context.getString(R.string.gf_restore_failed_detail, result.detail), true))
                }
            }
        }
    }
}
