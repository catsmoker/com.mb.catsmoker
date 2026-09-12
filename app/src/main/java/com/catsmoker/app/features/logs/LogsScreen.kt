package com.catsmoker.app.features.logs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.catsmoker.app.R
import com.catsmoker.app.shared.ui.components.ScreenScaffold
import com.catsmoker.app.shared.ui.theme.logLineColor

@Composable
fun LogsRoute(onBack: () -> Unit) {
    val viewModel: LogsViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsState()

    LogsScreen(
        logs = uiState.logs,
        isLoading = uiState.isLoading,
        filterQuery = uiState.filterQuery,
        onFilterQueryChanged = viewModel::onFilterQueryChanged,
        onRefresh = viewModel::refreshLogs,
        onClear = viewModel::clearLogs,
        onShare = viewModel::shareLogs,
        onBack = onBack
    )
}

@Composable
fun LogsScreen(
    logs: List<String>,
    isLoading: Boolean,
    filterQuery: String,
    onFilterQueryChanged: (String) -> Unit,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
    onShare: () -> Unit,
    onBack: () -> Unit
) {
    val scrollState = rememberLazyListState()
    val filteredLogs = remember(logs, filterQuery) {
        if (filterQuery.isBlank()) logs
        else logs.filter { it.contains(filterQuery, ignoreCase = true) }
    }

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            scrollState.scrollToItem(logs.size - 1)
        }
    }

    ScreenScaffold(
        title = stringResource(R.string.logs_title),
        subtitle = stringResource(R.string.logs_subtitle),
        onBack = onBack,
        trailingContent = {
            IconButton(onClick = onRefresh, enabled = !isLoading) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.logs_refresh), tint = MaterialTheme.colorScheme.onSurface)
            }
            IconButton(onClick = onShare) {
                Icon(Icons.Default.Share, contentDescription = stringResource(R.string.logs_share), tint = MaterialTheme.colorScheme.onSurface)
            }
            IconButton(onClick = { onClear() }) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.logs_clear), tint = MaterialTheme.colorScheme.onSurface)
            }
        }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            OutlinedTextField(
                value = filterQuery,
                onValueChange = onFilterQueryChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.logs_filter_placeholder), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                ),
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                } else if (filteredLogs.isEmpty()) {
                    Text(
                        text = stringResource(R.string.logs_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    LazyColumn(
                        state = scrollState,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                            .padding(8.dp)
                    ) {
                        items(filteredLogs) { line ->
                            Text(
                                text = line,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = logLineColor(line),
                                modifier = Modifier.padding(vertical = 1.dp)
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

