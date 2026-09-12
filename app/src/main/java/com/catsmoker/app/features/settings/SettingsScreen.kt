package com.catsmoker.app.features.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.catsmoker.app.R
import com.catsmoker.app.shared.ui.components.QuickActionButton
import com.catsmoker.app.shared.ui.components.ScreenScaffold
import com.catsmoker.app.shared.ui.components.SectionCard
import com.catsmoker.app.shared.ui.theme.CatsmokerTheme
import com.catsmoker.app.shared.ui.components.CatsmokerButton
import com.catsmoker.app.shared.ui.components.LanguageOptions
import com.catsmoker.app.shared.ui.components.ThemeModeOptions
import com.catsmoker.app.shared.ui.components.languageDisplayName
import com.catsmoker.app.system.config.AppearanceStore

@Composable
fun SettingsRoute(onBack: () -> Unit, onOpenPermissions: () -> Unit, onOpenLogs: () -> Unit) {
    val viewModel: SettingsViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.toasts.collect { message ->
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // A new language only re-resolves resources on recreate — the ViewModel asks, the
    // activity obeys. Theme changes need none of this; they recompose live.
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            if (event == SettingsViewModel.UiEvent.RecreateActivity) {
                (context as? android.app.Activity)?.recreate()
            }
        }
    }

    SettingsScreen(
        adsEnabled = uiState.adsEnabled,
        autoCheck = uiState.autoCheck,
        isUpdating = uiState.isUpdating,
        updateProgress = uiState.updateProgress,
        themeMode = uiState.themeMode,
        languageTag = uiState.languageTag,
        onAdsToggled = viewModel::onAdsToggled,
        onAutoCheckToggled = viewModel::onAutoCheckToggled,
        onThemeModeChanged = viewModel::onThemeModeChanged,
        onLanguageChanged = viewModel::onLanguageChanged,
        onOpenPermissions = onOpenPermissions,
        onOpenLogs = onOpenLogs,
        onBack = onBack,
        onCheckUpdates = viewModel::onCheckUpdates
    )

    uiState.updateDialog?.let { dialog ->
        AlertDialog(
            onDismissRequest = viewModel::dismissUpdateDialog,
            title = { Text(stringResource(R.string.sys_update_available, dialog.tagName)) },
            text = { Text(stringResource(R.string.sys_update_prompt)) },
            confirmButton = { TextButton(onClick = viewModel::startUpdateDownload) { Text(stringResource(R.string.sys_download)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissUpdateDialog) { Text(stringResource(R.string.sys_later)) } }
        )
    }
}

@Composable
fun SettingsScreen(
    adsEnabled: Boolean,
    autoCheck: Boolean,
    isUpdating: Boolean,
    updateProgress: Float,
    themeMode: AppearanceStore.ThemeMode,
    languageTag: String,
    onAdsToggled: (Boolean) -> Unit,
    onAutoCheckToggled: (Boolean) -> Unit,
    onThemeModeChanged: (AppearanceStore.ThemeMode) -> Unit,
    onLanguageChanged: (String) -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenLogs: () -> Unit,
    onBack: () -> Unit,
    onCheckUpdates: () -> Unit
) {
    var themeDialog by remember { mutableStateOf(false) }
    var languageDialog by remember { mutableStateOf(false) }

    ScreenScaffold(
        title = stringResource(R.string.sys_settings_title),
        subtitle = stringResource(R.string.sys_settings_subtitle),
        onBack = onBack
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            Text(stringResource(R.string.sys_section_appearance), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
            SectionCard {
                SettingsOptionRow(
                    title = stringResource(R.string.sys_theme_title),
                    value = themeModeLabel(themeMode),
                    onClick = { themeDialog = true }
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                SettingsOptionRow(
                    title = stringResource(R.string.sys_language_title),
                    value = languageDisplayName(languageTag),
                    onClick = { languageDialog = true }
                )
            }
            Text(
                stringResource(R.string.sys_language_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))
            Text(stringResource(R.string.sys_section_general), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
            SectionCard {
                SettingsToggle(stringResource(R.string.sys_ads_title), adsEnabled, onAdsToggled)
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text(stringResource(R.string.sys_section_updates), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
            SectionCard {
                if (isUpdating) {
                    Column {
                        Text(stringResource(R.string.sys_updating), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(progress = { updateProgress }, modifier = Modifier.fillMaxWidth())
                    }
                } else {
                    CatsmokerButton(onClick = onCheckUpdates, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.sys_check_updates)) }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                SettingsToggle(stringResource(R.string.sys_auto_check), autoCheck, onAutoCheckToggled)
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text(stringResource(R.string.sys_section_management), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
            QuickActionButton(
                title = stringResource(R.string.sys_permissions_title),
                subtitle = stringResource(R.string.sys_permissions_subtitle),
                iconContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                iconContentColor = MaterialTheme.colorScheme.primary,
                onClick = onOpenPermissions,
                isFullWidth = true,
                showChevron = true,
                icon = { Icon(Icons.Default.Security, null) }
            )

            Spacer(modifier = Modifier.height(12.dp))
            QuickActionButton(
                title = stringResource(R.string.sys_logs_title),
                subtitle = stringResource(R.string.sys_logs_subtitle),
                iconContainerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.1f),
                iconContentColor = MaterialTheme.colorScheme.onSurface,
                onClick = onOpenLogs,
                isFullWidth = true,
                showChevron = true,
                icon = { Icon(Icons.Default.Terminal, null) }
            )
        }
    }

    if (themeDialog) {
        AlertDialog(
            onDismissRequest = { themeDialog = false },
            title = { Text(stringResource(R.string.sys_theme_title)) },
            text = {
                ThemeModeOptions(
                    selected = themeMode,
                    onSelect = { onThemeModeChanged(it); themeDialog = false }
                )
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { themeDialog = false }) { Text(stringResource(R.string.sys_later)) } }
        )
    }

    if (languageDialog) {
        AlertDialog(
            onDismissRequest = { languageDialog = false },
            title = { Text(stringResource(R.string.sys_language_title)) },
            text = {
                LanguageOptions(
                    selectedTag = languageTag,
                    onSelect = { onLanguageChanged(it); languageDialog = false }
                )
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { languageDialog = false }) { Text(stringResource(R.string.sys_later)) } }
        )
    }
}

/** One tappable row stating the current choice — the dialog owns the changing, this owns the showing. */
@Composable
private fun SettingsOptionRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface)
            Text(value, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        }
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun themeModeLabel(mode: AppearanceStore.ThemeMode): String = when (mode) {
    AppearanceStore.ThemeMode.SYSTEM -> stringResource(R.string.sys_theme_system)
    AppearanceStore.ThemeMode.DARK -> stringResource(R.string.sys_theme_dark)
    AppearanceStore.ThemeMode.LIGHT -> stringResource(R.string.sys_theme_light)
}

@Composable
fun SettingsToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun SettingsPreview() {
    CatsmokerTheme {
        SettingsScreen(
            adsEnabled = true,
            autoCheck = false,
            isUpdating = false,
            updateProgress = 0f,
            themeMode = AppearanceStore.ThemeMode.SYSTEM,
            languageTag = AppearanceStore.LANGUAGE_SYSTEM,
            onAdsToggled = {},
            onAutoCheckToggled = {},
            onThemeModeChanged = {},
            onLanguageChanged = {},
            onOpenPermissions = {},
            onOpenLogs = {},
            onBack = {},
            onCheckUpdates = {}
        )
    }
}
