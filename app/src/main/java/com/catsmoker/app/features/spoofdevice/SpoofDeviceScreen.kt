package com.catsmoker.app.features.spoofdevice

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.catsmoker.app.R
import com.catsmoker.app.shared.ui.components.QuickActionButton
import com.catsmoker.app.shared.ui.components.ScreenScaffold
import com.catsmoker.app.shared.ui.components.SectionCard
import com.catsmoker.app.shared.ui.theme.CatsmokerTheme

@Composable
fun SpoofRoute(
    onNavigate: (String) -> Unit,
    onBack: () -> Unit
) {
    val viewModel: SpoofDeviceViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.toasts.collect { message ->
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    SpoofDeviceScreen(
        uiState = uiState,
        onNavigate = onNavigate,
        onDownloadMagisk = viewModel::installBundledMagiskZip,
        onOpenRootManager = viewModel::launchRootManager,
        onRefresh = viewModel::refreshStatus,
        onBack = onBack
    )
}

@Composable
fun SpoofDeviceScreen(
    uiState: SpoofDeviceViewModel.UiState,
    onNavigate: (String) -> Unit,
    onDownloadMagisk: () -> Unit,
    onOpenRootManager: () -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit
) {
    ScreenScaffold(
        title = stringResource(R.string.dash_spoof_title),
        subtitle = stringResource(R.string.spoof_home_subtitle),
        onBack = onBack
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)
        ) {
            // Status Card
            SectionCard {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text(stringResource(R.string.spoof_home_status), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusIndicator(active = uiState.isRooted)
                            Text(stringResource(R.string.spoof_home_root), color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                        }
                    }
                    if (uiState.isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, null, tint = MaterialTheme.colorScheme.primary) }
                    }
                }
                if (uiState.isRefreshing) {
                    Spacer(modifier = Modifier.height(12.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(stringResource(R.string.spoof_home_management), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SpoofMenuCard(
                    title = stringResource(R.string.spoof_menu_profiles),
                    subtitle = stringResource(R.string.spoof_menu_identities, uiState.profiles.size),
                    icon = Icons.Default.AccountBox,
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigate("spoof_profiles") }
                )
                SpoofMenuCard(
                    title = stringResource(R.string.spoof_menu_apps),
                    // Ladder packages are hooked too — they just get their identity per tier, so
                    // counting only the plain assignments would under-report.
                    subtitle = stringResource(R.string.spoof_menu_hooked, (uiState.assignments.keys + uiState.rateAssignments.keys).size),
                    icon = Icons.Default.Apps,
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigate("spoof_apps") }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SpoofMenuCard(
                    title = stringResource(R.string.spoof_menu_safe),
                    subtitle = stringResource(R.string.spoof_menu_apps_count, uiState.safeModePackages.size),
                    icon = Icons.Default.Security,
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigate("spoof_safe_mode") }
                )
                SpoofMenuCard(
                    title = stringResource(R.string.spoof_menu_diag),
                    subtitle = stringResource(R.string.spoof_menu_verify),
                    icon = Icons.Default.Troubleshoot,
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigate("spoof_diagnostics") }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(stringResource(R.string.spoof_home_advanced), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SectionCard {
                QuickActionButton(
                    title = stringResource(R.string.spoof_magisk_title),
                    subtitle = if (uiState.isGeneratingMagisk) stringResource(R.string.spoof_magisk_building) else stringResource(R.string.spoof_magisk_desc),
                    iconContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    iconContentColor = MaterialTheme.colorScheme.onSurface,
                    icon = { Icon(Icons.Default.FileDownload, null) },
                    onClick = onDownloadMagisk,
                    isFullWidth = true,
                    isLoading = uiState.isGeneratingMagisk
                )
                
                if (uiState.isRooted) {
                    Spacer(modifier = Modifier.height(8.dp))
                    QuickActionButton(
                        title = stringResource(R.string.spoof_root_manager_title),
                        subtitle = stringResource(R.string.spoof_root_manager_desc),
                        iconContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        iconContentColor = MaterialTheme.colorScheme.onSurface,
                        icon = { Icon(Icons.Default.Settings, null) },
                        onClick = onOpenRootManager,
                        isFullWidth = true
                    )
                }
            }
        }
    }
}

@Composable
fun SpoofMenuCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    SectionCard(
        modifier = modifier.clickable { onClick() }
    ) {
        Column(horizontalAlignment = Alignment.Start) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun StatusIndicator(active: Boolean) {
    Box(
        modifier = Modifier.padding(end = 8.dp).size(8.dp).clip(RoundedCornerShape(4.dp))
            .background(if (active) Color.Green else Color.Red)
    )
}
