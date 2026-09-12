package com.catsmoker.app.features.editgamefiles

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.catsmoker.app.R
import com.catsmoker.app.features.editgamefiles.grid.GridRoute
import com.catsmoker.app.features.editgamefiles.hsr.HsrGraphicsRoute
import com.catsmoker.app.features.editgamefiles.pubg.PubgSavePatcher
import com.catsmoker.app.features.editgamefiles.wuwa.WuwaConfigRoute
import com.catsmoker.app.shared.data.model.GameType
import com.catsmoker.app.shared.ui.components.ScreenScaffold
import com.catsmoker.app.shared.ui.components.SectionCard
import com.catsmoker.app.shared.ui.theme.CatsmokerTheme
import kotlinx.coroutines.flow.collectLatest
import com.catsmoker.app.shared.ui.components.CatsmokerButton
import com.catsmoker.app.shared.ui.components.CatsmokerOutlinedButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditGameFilesRoute(onBack: () -> Unit) {
    val viewModel: EditGameFilesViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.onCustomFilePicked(it) }
    }
    val safPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { viewModel.onSafPicked(it) }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { viewModel.onCustomFolderPicked(it) }
    }
    val allFilesPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.onStoragePermissionResult()
    }

    // Install state is probed when the view model is built, but the user installs and
    // uninstalls games while this screen sits open. Re-probing on every resume keeps the
    // dots honest without a refresh button the user must think about (PermissionScreen's
    // observer, same reason it re-checks its grants there).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.probeInstallStates()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is EditGameFilesViewModel.EditEvent.Toast -> Toast.makeText(context, event.message, if (event.isLong) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
                EditGameFilesViewModel.EditEvent.LaunchFilePicker -> filePicker.launch("*/*")
                is EditGameFilesViewModel.EditEvent.LaunchSafPicker -> safPicker.launch(null)
                EditGameFilesViewModel.EditEvent.LaunchFolderPicker -> folderPicker.launch(null)
                EditGameFilesViewModel.EditEvent.LaunchAllFilesAccess -> {
                    val intent = viewModel.launchAllFilesAccess()
                    if (intent != null) {
                        try {
                            allFilesPicker.launch(intent)
                        } catch (_: Exception) {
                            try {
                                allFilesPicker.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                            } catch (_: Exception) {
                                allFilesPicker.launch(Intent(Settings.ACTION_SETTINGS))
                            }
                        }
                    }
                }
                EditGameFilesViewModel.EditEvent.ShowZArchiverDialog -> {
                    Toast.makeText(context, context.getString(R.string.gf_zarchiver_copied), Toast.LENGTH_LONG).show()
                    viewModel.launchZArchiver()
                }
            }
        }
    }

    EditGameFilesScreen(
        uiState = uiState,
        onGameSelected = viewModel::onGameSelected,
        onProfileSelected = viewModel::onProfileSelected,
        onApplyProfile = viewModel::onApplyProfile,
        onMethodSelected = viewModel::onMethodSelected,
        onDismissChooser = viewModel::dismissMethodChooser,
        onResetSave = viewModel::onResetSave,
        onResetMethodSelected = viewModel::onResetMethodSelected,
        onDismissResetChooser = viewModel::dismissResetChooser,
        onRestoreBackup = viewModel::onRestoreBackup,
        onDismissBackupDialog = viewModel::dismissBackupDialog,
        onBackupChosen = viewModel::onBackupChosen,
        onDismissRestoreChooser = viewModel::dismissRestoreChooser,
        onRestoreMethodSelected = viewModel::onRestoreMethodSelected,
        onDeleteBackup = viewModel::onDeleteBackup,
        onSelectFile = viewModel::onSelectFile,
        onUploadFile = viewModel::onUploadFile,
        onClearSelection = viewModel::onClearSelection,
        onReadSave = viewModel::onReadSave,
        onSaveEditSelected = viewModel::onSaveEditSelected,
        onApplySaveEdits = viewModel::onApplySaveEdits,
        onRecheckInstalls = viewModel::probeInstallStates,
        onOpenStore = viewModel::openPlayStore,
        onLaunchGame = viewModel::onLaunchGame,
        onBack = onBack
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameSelector(
    selectedGame: GameType,
    installedGames: Map<GameType, Boolean>,
    onGameSelected: (GameType) -> Unit,
    onLaunchGame: () -> Unit,
    isLoading: Boolean
) {
    var showPicker by remember { mutableStateOf(false) }
    val isPubg = selectedGame in PUBG_VARIANTS

    // A tappable field opening the full game list — one entry per *game*, not per variant:
    // five PUBG rows were five near-identical lines, and nine chips needed swiping. PUBG
    // Mobile is a single entry here; picking it (or already having it selected) reveals the
    // variant selector below, which is where the store-variant choice belongs — after the
    // game is chosen, before the profile rows.
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.weight(1f).clickable { showPicker = true },
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = when {
                    selectedGame == GameType.NONE -> stringResource(R.string.gf_tap_to_choose_game)
                    isPubg -> GameType.PUBG_GLOBAL.displayName.removeSuffix(" (Global)")
                    else -> selectedGame.displayName
                },
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.gf_game_label)) },
                placeholder = { Text(stringResource(R.string.gf_tap_to_choose_game)) },
                trailingIcon = { Icon(Icons.Default.KeyboardArrowDown, contentDescription = null) },
                enabled = false,
                colors = OutlinedTextFieldDefaults.colors(
                    disabledTextColor = MaterialTheme.colorScheme.onSurface,
                    disabledBorderColor = MaterialTheme.colorScheme.outline,
                    disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    disabledPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    disabledLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                leadingIcon = if (selectedGame != GameType.NONE) {
                    // The selected game's own dot, so the field itself carries the install
                    // state the dialog rows show — no need to reopen the dialog to see it.
                    { InstallDot(installed = installedGames[selectedGame]) }
                } else null,
                modifier = Modifier.fillMaxWidth()
            )
        }
        FilledIconButton(
            onClick = onLaunchGame,
            enabled = selectedGame != GameType.NONE && !isLoading,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.gf_launch_game_desc))
        }
    }

    // The second selector: which PUBG store-variant the profile flow targets. Visible only
    // once a PUBG entry is the selected game — the group entry in the dialog opens this,
    // it does not silently pick Global.
    if (isPubg) {
        Spacer(modifier = Modifier.height(10.dp))
        Text(stringResource(R.string.gf_version_label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PUBG_VARIANTS.forEach { variant ->
                val variantInstalled = installedGames[variant]
                FilterChip(
                    selected = selectedGame == variant,
                    onClick = { onGameSelected(variant) },
                    label = { Text(variant.variantLabel ?: variant.shortLabel) },
                    leadingIcon = { InstallDot(installed = variantInstalled) },
                    // The dot already says absent; a chip you cannot select says it twice
                    // and blocks re-selecting to read the caption's explanation.
                    enabled = true
                )
            }
        }
    }

    if (showPicker) {
        GamePickerDialog(
            selectedGame = selectedGame,
            installedGames = installedGames,
            onDismiss = { showPicker = false },
            onPick = { game ->
                onGameSelected(game)
                showPicker = false
            }
        )
    }
}

/**
 * The full game list — one entry per game. The PUBG variants collapse into the single
 * "PUBG Mobile" row (their dot is green when *any* variant is installed, red only when none
 * of the five is, unknown when unprobed); choosing it reveals the variant chips under the
 * field, so the two decisions — which game, which store version — happen in two steps.
 */
@Composable
private fun GamePickerDialog(
    selectedGame: GameType,
    installedGames: Map<GameType, Boolean>,
    onDismiss: () -> Unit,
    onPick: (GameType) -> Unit
) {
    val otherGroup = listOf(GameType.GENSHIN_IMPACT, GameType.HSR, GameType.WUWA, GameType.GRID)
    val pubgSelected = selectedGame in PUBG_VARIANTS
    // The group dot's three-state rule (any yes → green; all probed and all no → red; a
    // missing probe → unknown) is pure logic, tested in InstallProbesTest.
    val pubgDot = pubgGroupDot(installedGames)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.gf_choose_game_title)) },
        text = {
            Column {
                PickerRow(
                    game = GameType.PUBG_GLOBAL,
                    label = "PUBG Mobile",
                    installed = pubgDot,
                    selected = pubgSelected,
                    showChevron = true,
                    onPick = { onPick(GameType.PUBG_GLOBAL) } // opens the VERSION chips, not a silent Global pick
                )
                otherGroup.forEach { game -> PickerRow(game, installedGames[game], selectedGame == game, onPick) }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.gf_cancel)) }
        }
    )
}

@Composable
private fun PickerRow(
    game: GameType,
    installed: Boolean?,
    selected: Boolean,
    onPick: (GameType) -> Unit,
    label: String = game.displayName,
    showChevron: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onPick(game) }
            .padding(horizontal = 4.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        InstallDot(installed = installed)
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = stringResource(R.string.gf_selected_desc),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        } else if (showChevron) {
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = stringResource(R.string.gf_choose_version_desc),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** Install-state dot beside a game chip: filled green, hollow unknown, red absent. */
@Composable
private fun InstallDot(installed: Boolean?) {
    val color = when (installed) {
        true -> Color(0xFF81C784)
        false -> MaterialTheme.colorScheme.error
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
fun EditGameFilesScreen(
    uiState: EditGameFilesViewModel.UiState,
    onGameSelected: (GameType) -> Unit,
    onProfileSelected: (Int) -> Unit,
    onApplyProfile: () -> Unit,
    onMethodSelected: (Int) -> Unit,
    onDismissChooser: () -> Unit,
    onResetSave: () -> Unit,
    onResetMethodSelected: (Int) -> Unit,
    onDismissResetChooser: () -> Unit,
    onRestoreBackup: () -> Unit,
    onDismissBackupDialog: () -> Unit,
    onBackupChosen: (ConfigBackupStore.Entry) -> Unit,
    onDismissRestoreChooser: () -> Unit,
    onRestoreMethodSelected: (Int) -> Unit,
    onDeleteBackup: (ConfigBackupStore.Entry) -> Unit,
    onSelectFile: () -> Unit,
    onUploadFile: () -> Unit,
    onClearSelection: () -> Unit,
    onReadSave: () -> Unit = {},
    onSaveEditSelected: (String, Int?) -> Unit = { _, _ -> },
    onApplySaveEdits: () -> Unit = {},
    onRecheckInstalls: () -> Unit = {},
    onOpenStore: (String) -> Unit = {},
    onLaunchGame: () -> Unit,
    onBack: () -> Unit
) {
    ScreenScaffold(
        title = stringResource(R.string.dash_edit_files_title),
        subtitle = stringResource(R.string.gf_edit_files_subtitle),
        onBack = onBack
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)
        ) {
            // The warning as one line, not a card: it must be seen, but it does not need a
            // whole surface plus 24dp of spacing above the picker to say two sentences.
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    stringResource(R.string.gf_edit_files_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
                )
            }

            SectionCard {
                Column {
                    Text(stringResource(R.string.gf_game_section), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(10.dp))
                    GameSelector(
                        selectedGame = uiState.selectedGame,
                        installedGames = uiState.installedGames,
                        onGameSelected = onGameSelected,
                        onLaunchGame = onLaunchGame,
                        isLoading = uiState.isLoading
                    )
                    if (uiState.selectedGame != GameType.NONE && !uiState.selectedGame.embeddedEditor &&
                        uiState.installedGames[uiState.selectedGame] != false
                    ) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            uiState.selectedGame.displayName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // The three read-modify-write editors (HSR / WuWa / GRID) render right here, below
            // the selector — the screen swaps its body instead of navigating away, so every
            // game is edited from one place. Each brings its own Route (view model, event
            // toasts, pickers) and gets no back arrow of its own: this screen's header is the
            // only header. Their editors own their games' files, so none of the profile-push
            // UI below is shown for them.
            if (uiState.selectedGame.embeddedEditor) {
                Spacer(modifier = Modifier.height(12.dp))
                when (uiState.selectedGame) {
                    GameType.HSR -> HsrGraphicsEmbedded()
                    GameType.WUWA -> WuwaConfigEmbedded()
                    GameType.GRID -> GridEmbedded()
                    else -> {}
                }
            } else if (uiState.selectedGame != GameType.NONE) {
                Spacer(modifier = Modifier.height(12.dp))

                // The same gate the embedded editors apply at load: when the probe says the
                // game is absent, a GAME NOT FOUND card replaces every working section — the
                // "preferences file not found" those editors reported for an uninstalled game
                // was exactly this fact discovered one step later than it needed to be. Unknown
                // (unprobed) shows the sections; only a confirmed "no" hides them. The whole
                // profile-push body sits inside the else branch — an earlier version gated only
                // the PROFILE card and left custom upload and the save editor reachable for a
                // game that was not there.
                if (uiState.installedGames[uiState.selectedGame] == false) {
                    GameNotFoundCard(
                        gameName = uiState.selectedGame.displayName,
                        packageName = uiState.gamePackageName,
                        onRecheck = onRecheckInstalls,
                        onOpenStore = onOpenStore
                    )
                } else {
                    SectionCard {
                        Column {
                            Text(stringResource(R.string.gf_profile_section), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            // Only this card's own jobs light the bar: push / reset / restore /
                            // backup-delete. Other cards stay dark while their buttons disable.
                            val profileBusy = uiState.busyArea == EditGameFilesViewModel.BusyArea.PROFILE_PUSH ||
                                uiState.busyArea == EditGameFilesViewModel.BusyArea.RESET ||
                                uiState.busyArea == EditGameFilesViewModel.BusyArea.RESTORE ||
                                uiState.busyArea == EditGameFilesViewModel.BusyArea.BACKUP_DELETE
                            if (profileBusy) {
                                Spacer(modifier = Modifier.height(8.dp))
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            // Per-game labels from the view model — PUBG has two profiles, Genshin one —
                            // so the list is whatever the selected game actually offers.
                            uiState.profileLabels.forEachIndexed { index, profile ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable { onProfileSelected(index) }.padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(selected = uiState.selectedProfile == index, onClick = { onProfileSelected(index) })
                                    Text(profile, color = MaterialTheme.colorScheme.onSurface)
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            CatsmokerButton(
                                onClick = onApplyProfile,
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !uiState.isLoading
                            ) {
                                if (uiState.busyArea == EditGameFilesViewModel.BusyArea.PROFILE_PUSH) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.gf_working))
                                } else {
                                    Text(stringResource(R.string.gf_apply_profile))
                                }
                            }
                            // Games with a resettable file only (config's resetFilePath): deleting it lets
                            // the game regenerate from defaults — the revert for every push above. The
                            // label is the game's own file name; Genshin has no reset and hides the button.
                            if (uiState.canReset && uiState.configFileLabel != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                CatsmokerOutlinedButton(
                                    onClick = onResetSave,
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !uiState.isLoading,
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                ) {
                                    if (uiState.busyArea == EditGameFilesViewModel.BusyArea.RESET) {
                                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(stringResource(R.string.gf_working))
                                    } else {
                                        Text(stringResource(R.string.gf_reset_file, uiState.configFileLabel ?: "").uppercase())
                                    }
                                }
                            }
                            // Every overwrite above takes a timestamped backup first; this is the way
                            // back to any of them. Available for every game, unlike the reset.
                            Spacer(modifier = Modifier.height(8.dp))
                            CatsmokerOutlinedButton(
                                onClick = onRestoreBackup,
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !uiState.isLoading
                            ) {
                                if (uiState.busyArea == EditGameFilesViewModel.BusyArea.RESTORE ||
                                    uiState.busyArea == EditGameFilesViewModel.BusyArea.BACKUP_DELETE
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.gf_working))
                                } else {
                                    Text(stringResource(R.string.gf_restore_backup))
                                }
                            }
                        }
                    }

                    // Custom upload only exists for games whose config is an opaque save blob the
                    // user might bring from elsewhere (PUBG's Active.sav) — hidden for templated
                    // configs like Genshin, where a dropped-in file would break the model lookup.
                    if (uiState.canUploadCustom) {
                        Spacer(modifier = Modifier.height(12.dp))

                        SectionCard {
                            Column {
                                Text(stringResource(R.string.gf_custom_upload), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                if (uiState.busyArea == EditGameFilesViewModel.BusyArea.CUSTOM_UPLOAD) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(uiState.selectedItemText, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    CatsmokerOutlinedButton(onClick = onSelectFile, modifier = Modifier.weight(1f), enabled = !uiState.isLoading) { Text(stringResource(R.string.gf_select)) }
                                    CatsmokerButton(onClick = onUploadFile, modifier = Modifier.weight(1f), enabled = !uiState.isLoading) {
                                        if (uiState.busyArea == EditGameFilesViewModel.BusyArea.CUSTOM_UPLOAD) {
                                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                        } else {
                                            Text(stringResource(R.string.gf_upload))
                                        }
                                    }
                                }
                                if (uiState.selectedItemText != stringResource(R.string.selected_item_placeholder)) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    TextButton(onClick = onClearSelection, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                                        Text(stringResource(R.string.gf_clear_selection), color = Color.Red.copy(alpha = 0.7f))
                                    }
                                }
                            }
                        }
                    }

                    // The inline save editor: read-modify-write of the ints the game's own save
                    // carries — the closed-source references' core mechanism, without replacing
                    // the file. Only for the game whose layout is verified ([canPatchSave]).
                    if (uiState.canPatchSave) {
                        Spacer(modifier = Modifier.height(12.dp))
                        PubgSaveEditorSection(
                            uiState = uiState,
                            onReadSave = onReadSave,
                            onSaveEditSelected = onSaveEditSelected,
                            onApplySaveEdits = onApplySaveEdits
                        )
                    }
                }
            }
        }
    }

    if (uiState.showMethodChooser) {
        AlertDialog(
            onDismissRequest = onDismissChooser,
            title = { Text(stringResource(R.string.gf_choose_method_title)) },
            text = { Text(stringResource(R.string.gf_choose_method_body)) },
            confirmButton = {
                Column {
                    TextButton(onClick = { onMethodSelected(0) }) { Text(stringResource(R.string.gf_method_root)) }
                    TextButton(onClick = { onMethodSelected(1) }) { Text(stringResource(R.string.gf_method_shizuku)) }
                    TextButton(onClick = { onMethodSelected(2) }) { Text(stringResource(R.string.gf_method_saf)) }
                    TextButton(onClick = { onMethodSelected(3) }) { Text(stringResource(R.string.gf_method_zarchiver)) }
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissChooser) { Text(stringResource(R.string.gf_cancel)) }
            }
        )
    }

    if (uiState.showResetChooser) {
        AlertDialog(
            onDismissRequest = onDismissResetChooser,
            title = { Text(stringResource(R.string.gf_reset_save_title)) },
            text = { Text(stringResource(R.string.gf_reset_save_body, uiState.configFileLabel ?: stringResource(R.string.gf_the_save_file))) },
            confirmButton = {
                Column {
                    TextButton(onClick = { onResetMethodSelected(0) }) { Text(stringResource(R.string.gf_method_root)) }
                    TextButton(onClick = { onResetMethodSelected(1) }) { Text(stringResource(R.string.gf_method_shizuku)) }
                    TextButton(onClick = { onResetMethodSelected(2) }) { Text(stringResource(R.string.gf_method_saf)) }
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissResetChooser) { Text(stringResource(R.string.gf_cancel)) }
            }
        )
    }

    if (uiState.showBackupDialog) {
        AlertDialog(
            onDismissRequest = onDismissBackupDialog,
            title = { Text(stringResource(R.string.gf_restore_backup_title)) },
            text = {
                // Newest first — the top row is the state immediately before the last overwrite,
                // which is the one "undo" means.
                Column {
                    uiState.backups.forEach { backup ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onBackupChosen(backup) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(backup.formattedTimestamp(), color = MaterialTheme.colorScheme.onSurface)
                                Text(
                                    backup.formattedSize(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { onDeleteBackup(backup) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.gf_delete_backup_desc),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onDismissBackupDialog) { Text(stringResource(R.string.gf_cancel)) }
            }
        )
    }

    if (uiState.showRestoreChooser) {
        AlertDialog(
            onDismissRequest = onDismissRestoreChooser,
            title = { Text(stringResource(R.string.gf_restore_via_title)) },
            text = { Text(stringResource(R.string.gf_restore_via_body)) },
            confirmButton = {
                Column {
                    TextButton(onClick = { onRestoreMethodSelected(0) }) { Text(stringResource(R.string.gf_method_root)) }
                    TextButton(onClick = { onRestoreMethodSelected(1) }) { Text(stringResource(R.string.gf_method_shizuku)) }
                    TextButton(onClick = { onRestoreMethodSelected(2) }) { Text(stringResource(R.string.gf_method_saf)) }
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissRestoreChooser) { Text(stringResource(R.string.gf_cancel)) }
            }
        )
    }
}

/**
 * The GAME NOT FOUND card, shown in place of every working section when the probe confirms
 * the game's package is absent. Names the exact package the probe checked (a one-size-fits-all
 * "no PUBG-family package" line once claimed this under Genshin, which never looks at a PUBG
 * package), re-checks on tap for the just-installed case, and offers the store listing.
 */
@Composable
private fun GameNotFoundCard(
    gameName: String,
    packageName: String?,
    onRecheck: () -> Unit,
    onOpenStore: (String) -> Unit
) {
    SectionCard {
        Column {
            Text(stringResource(R.string.gf_game_not_found), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, letterSpacing = 1.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                stringResource(R.string.gf_game_not_installed, gameName) +
                    (if (packageName != null) stringResource(R.string.gf_package_not_found, packageName) else "") +
                    stringResource(R.string.gf_install_run_once),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CatsmokerOutlinedButton(onClick = onRecheck, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.gf_recheck))
                }
                if (packageName != null) {
                    CatsmokerButton(onClick = { onOpenStore(packageName) }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.gf_open_play_store))
                    }
                }
            }
        }
    }
}

/**
 * The PUBG save editor: one READ step, then frame-rate and render-quality chip rows cloned from
 * the GRID editor's ladder rows. The chips stay disabled until a read has succeeded — the
 * editor edits values the file carries, it never assumes defaults — and the APPLY writes through
 * the same pull/backup/patch/push/read-back pipeline as everything else here.
 */
@Composable
private fun PubgSaveEditorSection(
    uiState: EditGameFilesViewModel.UiState,
    onReadSave: () -> Unit,
    onSaveEditSelected: (String, Int?) -> Unit,
    onApplySaveEdits: () -> Unit
) {
    val read = uiState.saveRead

    SectionCard {
        Column {
            Text(stringResource(R.string.gf_save_editor), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            // Same card for read + apply: the bar shows for either, the spinner only on the tapped button.
            val saveBusy = uiState.busyArea == EditGameFilesViewModel.BusyArea.SAVE_READ ||
                uiState.busyArea == EditGameFilesViewModel.BusyArea.SAVE_APPLY
            if (saveBusy) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                stringResource(R.string.gf_save_editor_desc, uiState.configFileLabel ?: stringResource(R.string.gf_save_fallback)),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (read == null) {
                CatsmokerButton(onClick = onReadSave, modifier = Modifier.fillMaxWidth(), enabled = !uiState.isLoading) {
                    if (uiState.busyArea == EditGameFilesViewModel.BusyArea.SAVE_READ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.gf_reading))
                    } else {
                        Text(stringResource(R.string.gf_read_save))
                    }
                }
            } else {
                Text(
                    stringResource(R.string.gf_file_holds, read.summary()),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
                val observed = read.observedSummary()
                if (observed != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.gf_also_holds, observed),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            uiState.savePatchReport?.let { report ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(R.string.gf_last_apply, report),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(stringResource(R.string.gf_frame_rate), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(6.dp))
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PubgSavePatcher.FPS_TIERS.forEach { tier ->
                    val selected = uiState.saveEdits[PubgSavePatcher.FIELD_BATTLE_FPS] == tier.battleFps &&
                        uiState.saveEdits[PubgSavePatcher.FIELD_LOBBY_FPS] == tier.lobbyFps &&
                        uiState.saveEdits[PubgSavePatcher.FIELD_FPS_LEVEL] == tier.fpsLevel
                    FilterChip(
                        selected = selected,
                        enabled = read != null && !uiState.isLoading,
                        onClick = { onSaveEditSelected(PubgSavePatcher.FIELD_BATTLE_FPS, tier.fps) },
                        label = { Text("${tier.fps} FPS") }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(stringResource(R.string.gf_render_quality), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(6.dp))
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PubgSavePatcher.RENDER_TIERS.forEach { tier ->
                    val selected = uiState.saveEdits[PubgSavePatcher.FIELD_BATTLE_RENDER] == tier.value &&
                        uiState.saveEdits[PubgSavePatcher.FIELD_LOBBY_RENDER] == tier.value
                    FilterChip(
                        selected = selected,
                        enabled = read != null && !uiState.isLoading,
                        onClick = { onSaveEditSelected(PubgSavePatcher.FIELD_BATTLE_RENDER, tier.value) },
                        label = { Text(tier.label) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(stringResource(R.string.gf_camera_view), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(6.dp))
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PubgSavePatcher.VIEW_PRESETS.forEach { preset ->
                    val selected = uiState.saveEdits[PubgSavePatcher.FIELD_TP_VIEW] == preset.tpView &&
                        uiState.saveEdits[PubgSavePatcher.FIELD_FP_VIEW] == preset.fpView
                    FilterChip(
                        selected = selected,
                        enabled = read != null && !uiState.isLoading &&
                            // A preset can only be offered when the file carries both camera fields —
                            // a save without them has nothing to patch.
                            read.fields[PubgSavePatcher.FIELD_TP_VIEW] is PubgSavePatcher.FieldOutcome.Value &&
                            read.fields[PubgSavePatcher.FIELD_FP_VIEW] is PubgSavePatcher.FieldOutcome.Value,
                        onClick = { onSaveEditSelected(PubgSavePatcher.FIELD_TP_VIEW, preset.tpView) },
                        label = { Text(preset.label) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CatsmokerOutlinedButton(
                    onClick = onReadSave,
                    modifier = Modifier.weight(1f),
                    enabled = !uiState.isLoading
                ) {
                    if (uiState.busyArea == EditGameFilesViewModel.BusyArea.SAVE_READ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.gf_reread))
                    }
                }
                CatsmokerButton(
                    onClick = onApplySaveEdits,
                    modifier = Modifier.weight(1f),
                    enabled = read != null && uiState.saveEdits.isNotEmpty() && !uiState.isLoading
                ) {
                    if (uiState.busyArea == EditGameFilesViewModel.BusyArea.SAVE_APPLY) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.gf_applying))
                    } else {
                        Text(stringResource(R.string.gf_apply))
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun EditGameFilesPreview() {
    CatsmokerTheme {
        EditGameFilesScreen(
            uiState = EditGameFilesViewModel.UiState(
                selectedGame = GameType.PUBG_GLOBAL,
                installedGames = mapOf(GameType.PUBG_GLOBAL to true),
                profileLabels = listOf("Unlock 120 FPS", "Tablet View (Wide)"),
                configFileLabel = "Active.sav",
                canReset = true,
                selectedItemText = "Active.sav selected",
                canUploadCustom = true,
                canPatchSave = true,
                saveRead = PubgSavePatcher.ReadResult(
                    fields = mapOf(
                        PubgSavePatcher.FIELD_BATTLE_FPS to PubgSavePatcher.FieldOutcome.Value(6),
                        PubgSavePatcher.FIELD_LOBBY_FPS to PubgSavePatcher.FieldOutcome.Value(6),
                        PubgSavePatcher.FIELD_FPS_LEVEL to PubgSavePatcher.FieldOutcome.Value(6),
                        PubgSavePatcher.FIELD_BATTLE_RENDER to PubgSavePatcher.FieldOutcome.Value(1),
                        PubgSavePatcher.FIELD_LOBBY_RENDER to PubgSavePatcher.FieldOutcome.Value(1)
                    ),
                    sizeBytes = 7693,
                    isGvas = true
                ),
                savePatchReport = null
            ),
            onGameSelected = {},
            onProfileSelected = {},
            onApplyProfile = {},
            onMethodSelected = {},
            onDismissChooser = {},
            onResetSave = {},
            onResetMethodSelected = {},
            onDismissResetChooser = {},
            onRestoreBackup = {},
            onDismissBackupDialog = {},
            onBackupChosen = {},
            onDismissRestoreChooser = {},
            onRestoreMethodSelected = {},
            onDeleteBackup = {},
            onSelectFile = {},
            onUploadFile = {},
            onClearSelection = {},
            onReadSave = {},
            onSaveEditSelected = { _, _ -> },
            onApplySaveEdits = {},
            onRecheckInstalls = {},
            onOpenStore = {},
            onLaunchGame = {},
            onBack = {}
        )
    }
}

/**
 * The three editor Routes, hosted inside File Engineering instead of reached as screens of
 * their own — one screen per job was the structure the user pushed back on ("too many
 * screens"). Each wrapper is the feature's own Route with `onBack = null`, so the editor
 * renders its content without a second header/back-arrow below this screen's header; every
 * other behaviour (its view model, its event toasts, its pickers) is exactly what the old
 * dedicated screen ran, unchanged.
 *
 * These live in this file rather than the editor packages because the hosting choice is the
 * File Engineering screen's own decision — the editor features stay agnostic about where they
 * are embedded.
 */

/** Honkai: Star Rail's editor, embedded in File Engineering. */
@Composable
private fun HsrGraphicsEmbedded() {
    HsrGraphicsRoute(onBack = null)
}

/** Wuthering Waves' config generator, embedded in File Engineering. */
@Composable
private fun WuwaConfigEmbedded() {
    WuwaConfigRoute(onBack = null)
}

/** GRID Autosport's editor, embedded in File Engineering. */
@Composable
private fun GridEmbedded() {
    GridRoute(onBack = null)
}
