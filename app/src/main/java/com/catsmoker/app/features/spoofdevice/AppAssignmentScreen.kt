package com.catsmoker.app.features.spoofdevice

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.catsmoker.app.R
import com.catsmoker.app.shared.data.repository.SpoofRepository
import com.catsmoker.app.shared.ui.components.ScreenScaffold
import com.catsmoker.app.shared.ui.components.SectionCard
import kotlin.math.roundToInt
import com.catsmoker.app.shared.ui.components.CatsmokerOutlinedButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppAssignmentScreen(
    uiState: SpoofDeviceViewModel.UiState,
    onLoadApps: () -> Unit,
    onAssignProfile: (String, String?) -> Unit,
    onAssignRateCandidate: (String, String, Int) -> Unit,
    onRemoveRateCandidate: (String, String, Int) -> Unit,
    onBack: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedApp by remember { mutableStateOf<SpoofDeviceViewModel.AppEntry?>(null) }

    LaunchedEffect(Unit) {
        onLoadApps()
    }

    val filteredApps = remember(uiState.apps, searchQuery) {
        uiState.apps.filter {
            it.label.contains(searchQuery, ignoreCase = true) || it.packageName.contains(searchQuery, ignoreCase = true)
        }.sortedWith(
            compareByDescending<SpoofDeviceViewModel.AppEntry> { it.assignedProfileName != null }
                .thenBy { it.label.lowercase() }
        )
    }

    ScreenScaffold(
        title = stringResource(R.string.spoof_apps_title),
        subtitle = stringResource(R.string.spoof_apps_subtitle),
        onBack = onBack
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            SectionCard {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text(stringResource(R.string.spoof_search_apps)) },
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Default.Search, null) }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (uiState.isLoadingApps) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredApps) { app ->
                        AppItem(
                            app = app,
                            onClick = { selectedApp = app }
                        )
                    }
                }
            }
        }
    }

    selectedApp?.let { app ->
        AssignProfileDialog(
            app = app,
            uiState = uiState,
            onAssignProfile = { profileId ->
                onAssignProfile(app.packageName, profileId)
                selectedApp = null
            },
            onDismiss = { selectedApp = null },
            onAssignRateCandidate = { profileId, rateHz ->
                onAssignRateCandidate(app.packageName, profileId, rateHz)
            },
            onRemoveRateCandidate = { profileId, rateHz ->
                onRemoveRateCandidate(app.packageName, profileId, rateHz)
            }
        )
    }
}

/**
 * The per-app assignment dialog: a single-profile pick, and above it the frame-rate ladder.
 *
 * The ladder is the `zygisk-Tweaker-main` mechanism — a game whose device table offers several
 * tiers gets a different identity per tier, and the winner is picked against the panel's measured
 * peak — so the dialog states which rung the panel currently earns rather than leaving the choice
 * looking arbitrary. Building a ladder clears the single assignment and vice versa, because a
 * non-empty ladder owns the package.
 */
@Composable
private fun AssignProfileDialog(
    app: SpoofDeviceViewModel.AppEntry,
    uiState: SpoofDeviceViewModel.UiState,
    onAssignProfile: (String?) -> Unit,
    onDismiss: () -> Unit,
    onAssignRateCandidate: (String, Int) -> Unit,
    onRemoveRateCandidate: (String, Int) -> Unit
) {
    val ladder = uiState.rateAssignments[app.packageName].orEmpty()
    // The rung the panel's measured peak actually earns, so the stated pick is the one that
    // would publish — not a guess at which tier the user probably wanted.
    val winner = if (uiState.panelPeakHz > 0f) SpoofRepository.pickRateCandidate(ladder, uiState.panelPeakHz) else null

    var addProfileId by remember(app.packageName) { mutableStateOf<String?>(null) }
    var rateText by remember(app.packageName) { mutableStateOf("") }
    val rateHz = rateText.trim().toIntOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.spoof_assign_title, app.label)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.spoof_ladder_title),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(R.string.spoof_ladder_desc),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 17.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (ladder.isEmpty()) {
                    Text(
                        stringResource(R.string.spoof_ladder_empty),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    ladder.forEach { rung ->
                        val profileName = uiState.profiles.firstOrNull { it.id == rung.profileId }?.name
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.spoof_ladder_rung,
                                    profileName ?: stringResource(R.string.spoof_ladder_deleted),
                                    rung.rateHz
                                ),
                                fontSize = 13.sp,
                                color = if (profileName != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = { onRemoveRateCandidate(rung.profileId, rung.rateHz) }
                            ) { Text(stringResource(R.string.spoof_action_remove), fontSize = 12.sp) }
                        }
                    }
                    if (winner != null) {
                        val winnerName = uiState.profiles.firstOrNull { it.id == winner.profileId }?.name
                        if (winnerName != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                stringResource(
                                    R.string.spoof_ladder_winner,
                                    uiState.panelPeakHz.roundToInt(),
                                    winnerName,
                                    winner.rateHz
                                ),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                // One rung at a time: each rung is a deliberate (identity, tier) pair rather than
                // a bulk selection, and naming the pair out loud is what keeps a ladder legible.
                var pickerExpanded by remember(app.packageName) { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box {
                        val pickerLabel = addProfileId?.let { id ->
                            uiState.profiles.firstOrNull { it.id == id }?.name
                        } ?: stringResource(R.string.spoof_ladder_profile)
                        CatsmokerOutlinedButton(onClick = { pickerExpanded = true }) {
                            Text(pickerLabel, maxLines = 1, fontSize = 12.sp)
                        }
                        DropdownMenu(expanded = pickerExpanded, onDismissRequest = { pickerExpanded = false }) {
                            uiState.profiles.forEach { profile ->
                                DropdownMenuItem(
                                    text = { Text(profile.name) },
                                    onClick = {
                                        addProfileId = profile.id
                                        pickerExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = rateText,
                        onValueChange = { rateText = it.filter(Char::isDigit).take(3) },
                        label = { Text(stringResource(R.string.spoof_ladder_hz)) },
                        singleLine = true,
                        modifier = Modifier.width(84.dp)
                    )
                    TextButton(
                        onClick = {
                            onAssignRateCandidate(addProfileId!!, rateHz!!)
                            addProfileId = null
                            rateText = ""
                        },
                        enabled = addProfileId != null && rateHz != null && rateHz > 0
                    ) { Text(stringResource(R.string.spoof_action_add)) }
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.spoof_assign_none), color = MaterialTheme.colorScheme.error) },
                    onClick = { onAssignProfile(null) }
                )
                uiState.profiles.forEach { profile ->
                    DropdownMenuItem(
                        text = { Text(profile.name) },
                        onClick = { onAssignProfile(profile.id) }
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.spoof_action_cancel)) }
        }
    )
}

@Composable
fun AppItem(app: SpoofDeviceViewModel.AppEntry, onClick: () -> Unit) {
    SectionCard(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Image(
                bitmap = app.icon.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(40.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(app.label, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                Text(app.packageName, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (app.assignedProfileName != null) {
                Text(
                    text = app.assignedProfileName,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 12.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                )
            }
        }
    }
}
