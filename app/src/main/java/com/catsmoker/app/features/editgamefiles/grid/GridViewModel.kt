package com.catsmoker.app.features.editgamefiles.grid

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
 * Screen state for the GRID Autosport graphics editor. Mirrors [com.catsmoker.app.features.editgamefiles.hsr.HsrGraphicsUiState]'s
 * shape: null settings until a read succeeds, with load failures rendered as their own card
 * so "not loaded yet", "cannot load", and "loaded and editable" stay visibly different.
 */
data class GridUiState(
    val loading: Boolean = true,
    val read: GridPreferences.ReadResult? = null,
    /** The working edits — the screen mutates this, [edits] are what an apply pushes. */
    val edits: GridPreferences.Edits = GridPreferences.Edits(),
    /** Canonical ladder/switch names the file does not carry yet — their controls are disabled. */
    val missingKeys: Set<String> = emptySet(),
    val channelUsed: String? = null,
    val gameVersion: String? = null,
    val loadFailure: GridPreferencesManager.ReadResult.Failure? = null,
    val applying: Boolean = false,
    /** What the last apply actually did — success carries the read-back verdict, failure the stage. */
    val lastApply: String? = null,
    val hasBackup: Boolean = false,
    val canUseShell: Boolean = false,
    val hasSafGrant: Boolean = false
) {
    /** A value group is editable only when the file actually carries its keys. */
    fun ladderEnabled(name: String): Boolean = "$name" !in missingKeys && "high_$name" !in missingKeys
}

@HiltViewModel
class GridViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val manager: GridPreferencesManager
) : ViewModel() {

    sealed class GridEvent {
        data class Toast(val message: String, val isLong: Boolean = false) : GridEvent()
        data object LaunchFolderPicker : GridEvent()
    }

    private val _uiState = MutableStateFlow(GridUiState())
    val uiState: StateFlow<GridUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<GridEvent>()
    val events: SharedFlow<GridEvent> = _events.asSharedFlow()

    init {
        refresh()
    }

    /** Full reload — re-reads the file through the best channel and re-derives the gates. */
    fun refresh() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    loading = true,
                    loadFailure = null,
                    canUseShell = manager.canUseShell(),
                    hasSafGrant = manager.hasSafGrant()
                )
            }
            when (val result = manager.readCurrent()) {
                is GridPreferencesManager.ReadResult.Success -> _uiState.update {
                    it.copy(
                        loading = false,
                        read = result.read,
                        edits = GridPreferences.Edits(),
                        missingKeys = missingKeys(result.read),
                        channelUsed = result.channelUsed,
                        gameVersion = result.read.gameVersion,
                        hasBackup = manager.hasBackup()
                    )
                }
                is GridPreferencesManager.ReadResult.Failure -> _uiState.update {
                    it.copy(
                        loading = false,
                        loadFailure = result,
                        read = null,
                        hasBackup = manager.hasBackup()
                    )
                }
            }
        }
    }

    /** Every key the file does not carry yet, spelled the way the UI's controls name them. */
    private fun missingKeys(read: GridPreferences.ReadResult): Set<String> = buildSet {
        if (read.screenWidth == null || read.screenHeight == null || read.fixedScreenHeight == null) {
            add("resolution")
        }
        if (read.maxFps == null || read.highMaxFps == null) add("fps")
        read.ladder.forEach { (key, value) -> if (value == null) add(key) }
        if (read.anisotropic == null) add(GridPreferences.ANISOTROPIC_KEY)
        read.switches.forEach { (key, value) -> if (value == null) add(key) }
    }

    // ── working-copy mutations ───────────────────────────────────────────────────────

    fun setResolution(height: Int) = _uiState.update {
        it.copy(edits = it.edits.copy(screenHeight = height, screenWidth = null))
    }

    fun setFps(fps: Int) = _uiState.update { it.copy(edits = it.edits.copy(fps = fps)) }

    fun setLadder(name: String, tier: String) = _uiState.update {
        it.copy(edits = it.edits.copy(ladder = it.edits.ladder + (name to tier)))
    }

    fun setAnisotropic(tier: String) = _uiState.update {
        it.copy(edits = it.edits.copy(anisotropic = tier))
    }

    fun setSwitch(name: String, on: Boolean) = _uiState.update {
        it.copy(edits = it.edits.copy(switches = it.edits.switches + (name to on)))
    }

    // ── apply / restore ──────────────────────────────────────────────────────────────

    fun onApply() {
        val edits = _uiState.value.edits
        if (_uiState.value.applying) return
        if (edits == GridPreferences.Edits()) {
            viewModelScope.launch { _events.emit(GridEvent.Toast(context.getString(R.string.gf_grid_nothing_changed), false)) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(applying = true, lastApply = null) }
            when (val result = manager.applyEdits(edits)) {
                is GridPreferencesManager.WriteResult.Success -> {
                    val report = buildString {
                        append(context.getString(R.string.gf_grid_applied_via, result.channelUsed))
                        if (result.gameStopped == true) append(context.getString(R.string.gf_grid_stopped_first))
                        if (result.gameStopped == null) append(context.getString(R.string.gf_grid_not_stopped))
                        append(result.backup?.let { context.getString(R.string.gf_grid_backup_taken, it.formattedTimestamp()) } ?: context.getString(R.string.gf_grid_no_backup))
                        append(context.getString(if (result.verified) R.string.gf_grid_verified else R.string.gf_grid_not_verified))
                        if (result.refused.isNotEmpty()) append(context.getString(R.string.gf_grid_refused, result.refused.joinToString("; ")))
                    }
                    _uiState.update { it.copy(applying = false, lastApply = report) }
                    _events.emit(
                        GridEvent.Toast(
                            context.getString(if (result.verified) R.string.gf_grid_applied_ok else R.string.gf_grid_applied_unverified),
                            true
                        )
                    )
                    refresh()
                }
                is GridPreferencesManager.WriteResult.Failure -> {
                    _uiState.update {
                        it.copy(applying = false, lastApply = context.getString(R.string.gf_failed_stage_detail, result.stage, result.detail))
                    }
                    _events.emit(GridEvent.Toast(context.getString(R.string.gf_apply_failed_detail, result.detail), true))
                }
            }
        }
    }

    fun onRestoreBackup() {
        viewModelScope.launch {
            when (val result = manager.restoreLatestBackup()) {
                null -> _events.emit(GridEvent.Toast(context.getString(R.string.gf_no_backup_yet), false))
                is GridPreferencesManager.WriteResult.Success -> {
                    _uiState.update { it.copy(lastApply = context.getString(R.string.gf_grid_restored, result.channelUsed, result.verified.toString())) }
                    _events.emit(GridEvent.Toast(context.getString(R.string.gf_backup_restored), false))
                    refresh()
                }
                is GridPreferencesManager.WriteResult.Failure -> {
                    _uiState.update { it.copy(lastApply = context.getString(R.string.gf_restore_failed_stage, result.stage, result.detail)) }
                    _events.emit(GridEvent.Toast(context.getString(R.string.gf_restore_failed_detail, result.detail), true))
                }
            }
        }
    }

    fun hasBackup(): Boolean = manager.hasBackup()

    /** The SAF path needs a one-time pick of the game's Android/data root. */
    fun onSafFolderPicked(uri: android.net.Uri) {
        val kept = manager.onSafFolderPicked(uri)
        if (!kept) {
            viewModelScope.launch {
                _events.emit(GridEvent.Toast(context.getString(R.string.gf_grid_folder_grant_lost), true))
            }
        }
        refresh()
    }

    fun requestFolderPicker() {
        viewModelScope.launch { _events.emit(GridEvent.LaunchFolderPicker) }
    }
}
