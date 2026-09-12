package com.catsmoker.app.features.logs

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.catsmoker.app.R
import com.catsmoker.app.system.shell.ShellRunner
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class LogsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shellRunner: ShellRunner
) : ViewModel() {

    data class LogsUiState(
        val logs: List<String> = emptyList(),
        val isLoading: Boolean = false,
        val filterQuery: String = ""
    )

    private val _uiState = MutableStateFlow(LogsUiState())
    val uiState: StateFlow<LogsUiState> = _uiState.asStateFlow()

    init {
        refreshLogs()
    }

    fun refreshLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = true) }
            try {
                // Get last 500 lines of logcat
                val output = shellRunner.exec("logcat -d -t 500")
                val lines = output.split("\n").filter { it.isNotBlank() }
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(logs = lines, isLoading = false) }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(isLoading = false, logs = listOf(context.getString(R.string.core_logs_fetch_error, e.message))) }
                }
            }
        }
    }

    fun onFilterQueryChanged(query: String) {
        _uiState.update { it.copy(filterQuery = query) }
    }

    fun clearLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            shellRunner.exec("logcat -c")
            refreshLogs()
        }
    }

    fun shareLogs() {
        val allLogs = _uiState.value.logs.joinToString("\n")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, allLogs)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.core_logs_share_title)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
