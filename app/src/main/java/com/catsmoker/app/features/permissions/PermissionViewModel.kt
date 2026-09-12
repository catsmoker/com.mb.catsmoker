package com.catsmoker.app.features.permissions

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.catsmoker.app.system.config.AppearanceStore
import com.catsmoker.app.system.shell.ShellRunner
import com.topjohnwu.superuser.Shell
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
import javax.inject.Inject

@HiltViewModel
class PermissionViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shellRunner: ShellRunner,
) : ViewModel() {

    data class UiState(
        /** First-run step one: theme + language, one full screen with no scrolling. */
        val isAppearanceStep: Boolean = true,
        /** First-run step two: the terms checkbox gates its own Continue. */
        val isAgreementStep: Boolean = true,
        val themeMode: AppearanceStore.ThemeMode = AppearanceStore.ThemeMode.SYSTEM,
        /** Staged language tag — saved only on Continue, since it needs a recreate. */
        val languageTag: String = AppearanceStore.LANGUAGE_SYSTEM,
        val isAgreed: Boolean = false,
        val rootGranted: Boolean = false,
        val notifGranted: Boolean = false,
        val storageGranted: Boolean = false,
        val batteryGranted: Boolean = false,
        val overlayGranted: Boolean = false,
        val usageGranted: Boolean = false,
        val shizukuGranted: Boolean = false,
        val micGranted: Boolean = false,
        val bluetoothGranted: Boolean = false,
    )

    /** One-shot UI actions the screen itself must perform (activity recreate for locale). */
    sealed interface UiEvent {
        data object RecreateActivity : UiEvent
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    init {
        // Reopened from Settings after onboarding (`is_first_run` — what MainActivity gates
        // the start destination on — already false): skip both gates and land on the plain
        // permission list. The flags are committed before navigation and the ViewModel
        // survives the language recreate, so this holds.
        if (!context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                .getBoolean("is_first_run", true)) {
            _uiState.update { it.copy(isAppearanceStep = false, isAgreementStep = false) }
        }
        _uiState.update {
            it.copy(
                themeMode = AppearanceStore.themeModeSync(context),
                languageTag = AppearanceStore.languageTag(context)
            )
        }
        viewModelScope.launch {
            shellRunner.shizukuHasPermission.collect { granted ->
                _uiState.update { it.copy(shizukuGranted = granted) }
            }
        }
    }

    fun onAgreedChange(agreed: Boolean) {
        _uiState.update { it.copy(isAgreed = agreed) }
    }

    /** Theme previews live — the flow recomposes the whole app around the onboarding. */
    fun onThemeModeChanged(mode: AppearanceStore.ThemeMode) {
        AppearanceStore.setThemeMode(context, mode)
        _uiState.update { it.copy(themeMode = mode) }
    }

    /** Staged only: the locale needs an activity recreate, which the step's Continue triggers. */
    fun onLanguageChanged(tag: String) {
        _uiState.update { it.copy(languageTag = tag) }
    }

    /**
     * Step one's Continue: commits theme + language, marks the gate answered and advances.
     * The language flag is committed (and the recreate requested) before advancing, so a
     * mid-restart process death cannot re-show this step or lose the picked language.
     */
    fun onAppearanceContinue() {
        val state = _uiState.value
        AppearanceStore.setThemeMode(context, state.themeMode)
        val previous = AppearanceStore.languageTag(context)
        AppearanceStore.setLanguage(context, state.languageTag)
        AppearanceStore.setChosen(context)
        _uiState.update { it.copy(isAppearanceStep = false) }
        if (state.languageTag != previous) {
            _events.tryEmit(UiEvent.RecreateActivity)
        }
    }

    /** Step two's Continue: advances only when the terms checkbox is checked. */
    fun onAgreementContinue(): Boolean {
        val current = _uiState.value
        if (!current.isAgreed) return false
        _uiState.update { it.copy(isAgreementStep = false) }
        return true
    }

    /**
     * Step three's DONE. Commits the onboarding flag before navigating so a first run
     * must not re-enter onboarding, and a Settings revisit lands on the permission list.
     */
    fun onDone(onDone: () -> Unit) {
        viewModelScope.launch {
            context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit {
                putBoolean("is_first_run", false)
            }
            withContext(Dispatchers.Main) { onDone() }
        }
    }

    fun refreshStates() {
        checkRootPermission { rooted -> _uiState.update { it.copy(rootGranted = rooted) } }
        shellRunner.refreshShizukuPermission()
        _uiState.update {
            it.copy(
                notifGranted = checkNotificationPermission(),
                storageGranted = checkStoragePermission(),
                batteryGranted = checkBatteryPermission(),
                overlayGranted = checkOverlayPermission(),
                usageGranted = checkUsagePermission(),
                shizukuGranted = shellRunner.shizukuHasPermission.value,
                micGranted = checkMicPermission(),
                bluetoothGranted = checkBluetoothPermission()
            )
        }
    }

    private fun checkMicPermission(): Boolean =
        context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun checkBluetoothPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else true

    fun requestRootPermission() {
        viewModelScope.launch(Dispatchers.IO) {
            // Trigger root request
            Shell.cmd("id").exec()
            refreshStates()
        }
    }

    fun requestShizukuPermission() {
        try {
            if (Shizuku.pingBinder()) {
                Shizuku.requestPermission(0)
            }
        } catch (_: Exception) {}
    }

    private fun checkRootPermission(callback: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val isRooted = try { shellRunner.isRootAvailable(force = true) } catch (_: Exception) { false }
            withContext(Dispatchers.Main) { callback(isRooted) }
        }
    }

    private fun checkBatteryPermission(): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)

    private fun checkNotificationPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true

    private fun checkStoragePermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    private fun checkOverlayPermission(): Boolean = Settings.canDrawOverlays(context)

    private fun checkUsagePermission(): Boolean = try {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) {
        false
    }

    private fun checkShizukuPermission(): Boolean = shellRunner.shizukuHasPermission.value
}
