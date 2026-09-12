package com.catsmoker.app.features.spoofdevice

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.catsmoker.app.R
import com.catsmoker.app.shared.ui.components.ScreenScaffold
import com.catsmoker.app.shared.ui.components.SectionCard
import com.catsmoker.app.shared.ui.components.CatsmokerButton

@Composable
fun ProfilesListScreen(
    uiState: SpoofDeviceViewModel.UiState,
    onNavigateToEditor: (String) -> Unit,
    onCreateProfile: (String) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onBack: () -> Unit
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var newProfileName by remember { mutableStateOf("") }

    ScreenScaffold(
        title = stringResource(R.string.spoof_profiles_title),
        subtitle = stringResource(R.string.spoof_profiles_subtitle),
        onBack = onBack,
        trailingContent = {
            IconButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    ) {
        if (!uiState.storeLoaded) {
            // Saying "no profiles" before the store has been read would be inventing an answer.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (uiState.profiles.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.spoof_list_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(uiState.profiles) { index, entry ->
                    ProfileItem(
                        name = entry.name,
                        details = "${entry.profile.brand} ${entry.profile.model}",
                        canDelete = index != 0,
                        onEdit = { onNavigateToEditor(entry.id) },
                        onDelete = { onDeleteProfile(entry.id) }
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text(stringResource(R.string.spoof_dialog_new)) },
            text = {
                OutlinedTextField(
                    value = newProfileName,
                    onValueChange = { newProfileName = it },
                    label = { Text(stringResource(R.string.spoof_field_profile_name)) },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                CatsmokerButton(
                    onClick = {
                        if (newProfileName.isNotBlank()) {
                            onCreateProfile(newProfileName)
                            newProfileName = ""
                            showCreateDialog = false
                        }
                    }
                ) {
                    Text(stringResource(R.string.spoof_action_create))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text(stringResource(R.string.spoof_action_cancel))
                }
            }
        )
    }
}

@Composable
fun ProfileItem(
    name: String,
    details: String,
    canDelete: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    SectionCard(
        modifier = Modifier.fillMaxWidth().clickable { onEdit() }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(Icons.Default.PhoneAndroid, null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f)) {
                Text(name, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                Text(details, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (canDelete) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
