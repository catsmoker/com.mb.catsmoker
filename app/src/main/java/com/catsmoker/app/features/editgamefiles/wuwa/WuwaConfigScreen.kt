package com.catsmoker.app.features.editgamefiles.wuwa

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.catsmoker.app.R
import com.catsmoker.app.features.editgamefiles.ConfigBackupStore
import com.catsmoker.app.features.gamingtools.ui.CollapsibleExplainer
import com.catsmoker.app.shared.ui.components.ScreenScaffold
import com.catsmoker.app.shared.ui.components.SectionCard
import kotlinx.coroutines.flow.collectLatest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.catsmoker.app.shared.ui.components.CatsmokerButton
import com.catsmoker.app.shared.ui.components.CatsmokerOutlinedButton

/**
 * Wuthering Waves config generator. Cloned from the HSR Graphics screen's shape rather than
 * drawn from blank, per the house convention. Unlike HSR there is no load-failure gate: the
 * generator works from device facts and falls back on its own when a read fails, so the
 * screen always renders — a missing game install or an unreadable GPU is a warning card,
 * not a dead end.
 *
 * Hosted two ways: as a destination of its own (non-null [onBack]) or embedded inside File
 * Engineering's game selector (null — no second header below that screen's).
 *
 * Card rhythm is the host's: cards carry no vertical padding of their own and the content
 * column spaces its children 12 dp apart, so an embedded editor reads as more sections of
 * the File Engineering screen rather than a different screen pasted below the selector —
 * the per-card `vertical = 8.dp` padding used to read exactly that way. Action buttons are
 * uppercase to match the host's APPLY PROFILE / RESTORE BACKUP idiom.
 */
@Composable
fun WuwaConfigRoute(onBack: (() -> Unit)? = null) {
    val viewModel: WuwaConfigViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // SAF folder picker: the tree grant lives under the game's Android/data root. The deploy
    // that asked for it is parked in the ViewModel and re-runs once the grant lands.
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.onSafFolderPicked(uri)
    }

    // One collector for every one-shot event: toasts show inline, the SAF folder request is
    // answered by the picker launcher above. A second collector on the same no-replay flow
    // would race it — a third event kind must extend this `when`, not add a collector.
    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is WuwaConfigViewModel.WuwaEvent.Toast ->
                    Toast.makeText(context, event.message, if (event.isLong) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
                is WuwaConfigViewModel.WuwaEvent.LaunchFolderPicker ->
                    runCatching { folderPicker.launch(null) }
            }
        }
    }

    // Community pack picker: a separate one-shot tree pick. No persistable grant is taken —
    // the import copies everything it finds into the app's own store.
    val packFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.importCommunityPack(uri)
    }

    WuwaConfigScreen(
        uiState = uiState,
        onSelectPreset = viewModel::selectPreset,
        onUpdateOptions = viewModel::updateOptions,
        onDeploy = viewModel::onDeploy,
        onRestoreBackup = viewModel::onRestoreBackup,
        onApplyRecommendation = viewModel::applyRecommendation,
        onAnalyzeLog = viewModel::analyzeGameLog,
        onReadProfile = viewModel::readInstalledProfile,
        onImportPack = { runCatching { packFolderPicker.launch(null) } },
        onDeployPackVariant = viewModel::deployCommunityVariant,
        onDeployPackVariantStripped = viewModel::deployCommunityVariantStripped,
        onDeletePack = viewModel::deleteCommunityPack,
        onVerifyHistory = viewModel::verifyHistoryRecord,
        onDeleteHistory = viewModel::deleteHistoryRecord,
        onClearHistory = viewModel::clearHistory,
        onStartAutoTune = viewModel::startAutoTune,
        onTunerRoundPlayed = viewModel::onTunerRoundPlayed,
        onApplyTunerResult = viewModel::applyTunerResult,
        onStopTuner = viewModel::stopTuner,
        onResetTuner = viewModel::resetTuner,
        onFetchGacha = viewModel::fetchGachaFromLog,
        onRestoreGacha = viewModel::restoreGachaFromCache,
        onClearGacha = viewModel::clearGachaCache,
        onRefresh = viewModel::refresh,
        onBack = onBack
    )
}

/** Localized preset names. The keys stay identifiers; only the display text is localized. */
@Composable
private fun presetLabel(preset: String): String = when (preset) {
    "potato" -> stringResource(R.string.gf_preset_potato)
    "endurance" -> stringResource(R.string.gf_preset_endurance)
    "performance" -> stringResource(R.string.gf_preset_performance)
    "competitive" -> stringResource(R.string.gf_preset_competitive)
    "balanced" -> stringResource(R.string.gf_preset_balanced)
    "high" -> stringResource(R.string.gf_preset_high)
    "ultra" -> stringResource(R.string.gf_preset_ultra)
    "cinematic" -> stringResource(R.string.gf_preset_cinematic)
    else -> preset
}

/** Localized game-mode names. Mirrors [WuWaConfigGenerator.GameMode.label]. */
@Composable
private fun gameModeLabel(mode: WuWaConfigGenerator.GameMode): String = when (mode) {
    WuWaConfigGenerator.GameMode.Overworld -> stringResource(R.string.gf_mode_overworld)
    WuWaConfigGenerator.GameMode.ToA -> stringResource(R.string.gf_mode_toa)
}

@Composable
fun WuwaConfigScreen(
    uiState: WuwaConfigUiState,
    onSelectPreset: (String) -> Unit,
    onUpdateOptions: ((WuWaConfigGenerator.Options) -> WuWaConfigGenerator.Options) -> Unit,
    onDeploy: (WuwaDeployChannel) -> Unit,
    onRestoreBackup: (ConfigBackupStore.Entry) -> Unit,
    onApplyRecommendation: () -> Unit,
    onAnalyzeLog: () -> Unit,
    onReadProfile: () -> Unit,
    onImportPack: () -> Unit,
    onDeployPackVariant: (String, String) -> Unit,
    onDeployPackVariantStripped: (String, String) -> Unit,
    onDeletePack: (String) -> Unit,
    onVerifyHistory: (WuwaDeployHistoryStore.Record) -> Unit,
    onDeleteHistory: (WuwaDeployHistoryStore.Record) -> Unit,
    onClearHistory: () -> Unit,
    onStartAutoTune: () -> Unit,
    onTunerRoundPlayed: () -> Unit,
    onApplyTunerResult: () -> Unit,
    onStopTuner: () -> Unit,
    onResetTuner: () -> Unit,
    onFetchGacha: () -> Unit,
    onRestoreGacha: () -> Unit,
    onClearGacha: () -> Unit,
    onRefresh: () -> Unit,
    onBack: (() -> Unit)? = null
) {
    // One body, two hosts: standalone (own ScreenScaffold header + scroll) or embedded in File
    // Engineering's selector, where the host already scrolled and padded the page — a second
    // header would double it, and a nested verticalScroll measured under the host's infinite
    // height constraints crashes, so embedded renders the Column bare. The 12 dp rhythm
    // matches the host's card spacing (see the class KDoc).
    val content: @Composable () -> Unit = {
        Column(
            modifier = if (onBack == null) Modifier
            else Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
        ) {
            if (uiState.loading) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 96.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
                return@Column
            }

            // The same gate the File Engineering screen applies to the profile games: when
            // the package probe says the game is absent, an install-first card replaces the
            // whole editor. Generating configs for a game that is not there invites pushing
            // them nowhere; unknown (probe could not answer) still shows the editor.
            if (uiState.gameInstalled == false) {
                SectionCard {
                    Column {
                        Text(stringResource(R.string.gf_game_not_found), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, letterSpacing = 1.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.gf_wuwa_not_installed_body, WuwaConfigManager.PACKAGE),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CatsmokerOutlinedButton(onClick = onRefresh, modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.gf_recheck))
                            }
                        }
                    }
                }
                return@Column
            }

            uiState.recommendation?.let { rec ->
                RecommendCard(rec, uiState.preset, onApplyRecommendation)
                Spacer(modifier = Modifier.height(12.dp))
            }

            // The recommendation's apply target sits directly below it — the chips used to
            // live five cards down, past the log, gacha, profile and auto-tune cards.
            SectionCard {
                Text(stringResource(R.string.gf_preset_section), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    WuWaConfigGenerator.PRESET_ORDER.forEach { preset ->
                        FilterChip(
                            selected = uiState.preset == preset,
                            onClick = { onSelectPreset(preset) },
                            label = { Text(presetLabel(preset)) },
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.gf_wuwa_preset_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            GameLogCard(uiState, onAnalyzeLog)

            Spacer(modifier = Modifier.height(12.dp))

            GachaCard(uiState, onFetchGacha, onRestoreGacha, onClearGacha)

            Spacer(modifier = Modifier.height(12.dp))

            InstalledProfileCard(uiState, onReadProfile)

            Spacer(modifier = Modifier.height(12.dp))

            SectionCard {
                Text(stringResource(R.string.gf_frame_rate), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    listOf(30, 60, 90, 120).forEach { fps ->
                        FilterChip(
                            selected = uiState.options.fps == fps,
                            onClick = { onUpdateOptions { it.copy(fps = fps) } },
                            label = { Text("$fps") },
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                WuwaSwitchRow(
                    stringResource(R.string.gf_wuwa_unlock120_t),
                    stringResource(R.string.gf_wuwa_unlock120_c),
                    uiState.options.unlock120
                ) { v -> onUpdateOptions { it.copy(unlock120 = v) } }
                WuwaSwitchRow(
                    stringResource(R.string.gf_wuwa_ultra_t),
                    stringResource(R.string.gf_wuwa_ultra_c),
                    uiState.options.unlockUltra
                ) { v -> onUpdateOptions { it.copy(unlockUltra = v) } }
                WuwaSwitchRow(
                    stringResource(R.string.gf_wuwa_vsync_t),
                    stringResource(R.string.gf_wuwa_vsync_c),
                    uiState.options.vsync
                ) { v -> onUpdateOptions { it.copy(vsync = v) } }
            }

            Spacer(modifier = Modifier.height(12.dp))

            SectionCard {
                Text(stringResource(R.string.gf_game_mode), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Row {
                    WuWaConfigGenerator.GameMode.entries.forEach { mode ->
                        FilterChip(
                            selected = uiState.options.mode == mode,
                            onClick = { onUpdateOptions { it.copy(mode = mode) } },
                            label = { Text(gameModeLabel(mode)) },
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.gf_wuwa_mode_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            SectionCard {
                Text(stringResource(R.string.gf_effects), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
                Spacer(modifier = Modifier.height(8.dp))
                WuwaSwitchRow(stringResource(R.string.gf_fx_outline_t), stringResource(R.string.gf_fx_outline_c), !uiState.options.disableOutline) { v ->
                    onUpdateOptions { it.copy(disableOutline = !v) }
                }
                WuwaSwitchRow(stringResource(R.string.gf_fx_radial_t), stringResource(R.string.gf_fx_radial_c), !uiState.options.disableRadialBlur) { v ->
                    onUpdateOptions { it.copy(disableRadialBlur = !v) }
                }
                WuwaSwitchRow(stringResource(R.string.gf_fx_bloom_t), stringResource(R.string.gf_fx_bloom_c), !uiState.options.disableBloom) { v ->
                    onUpdateOptions { it.copy(disableBloom = !v) }
                }
                WuwaSwitchRow(stringResource(R.string.gf_fx_exposure_t), stringResource(R.string.gf_fx_exposure_c), !uiState.options.disableAutoExposure) { v ->
                    onUpdateOptions { it.copy(disableAutoExposure = !v) }
                }
                WuwaSwitchRow(stringResource(R.string.gf_fx_ssr_t), stringResource(R.string.gf_fx_ssr_c), !uiState.options.disableSSR) { v ->
                    onUpdateOptions { it.copy(disableSSR = !v) }
                }
                WuwaSwitchRow(stringResource(R.string.gf_fx_ca_t), stringResource(R.string.gf_fx_ca_c), uiState.options.ca) { v ->
                    onUpdateOptions { it.copy(ca = v) }
                }
                WuwaSwitchRow(stringResource(R.string.gf_fx_hzb_t), stringResource(R.string.gf_fx_hzb_c), uiState.options.hzb) { v ->
                    onUpdateOptions { it.copy(hzb = v) }
                }
                WuwaSwitchRow(stringResource(R.string.gf_fx_cool_t), stringResource(R.string.gf_fx_cool_c), uiState.options.cool) { v ->
                    onUpdateOptions { it.copy(cool = v) }
                }
                WuwaSwitchRow(
                    stringResource(R.string.gf_fx_vulkan_t),
                    stringResource(R.string.gf_fx_vulkan_c),
                    uiState.options.vulkan
                ) { v -> onUpdateOptions { it.copy(vulkan = v) } }
                WuwaSwitchRow(
                    stringResource(R.string.gf_fx_noauto_t),
                    stringResource(R.string.gf_fx_noauto_c),
                    uiState.options.disableAutoAdjust
                ) { v -> onUpdateOptions { it.copy(disableAutoAdjust = v) } }
            }

            Spacer(modifier = Modifier.height(12.dp))

            SectionCard {
                Text(stringResource(R.string.gf_files_section), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
                Spacer(modifier = Modifier.height(8.dp))
                WuwaSwitchRow(
                    stringResource(R.string.gf_files_scal_t),
                    stringResource(R.string.gf_files_scal_c),
                    uiState.options.generateScalability
                ) { v -> onUpdateOptions { it.copy(generateScalability = v) } }
                WuwaSwitchRow(
                    stringResource(R.string.gf_files_hw_t),
                    stringResource(R.string.gf_files_hw_c),
                    uiState.options.generateHardware
                ) { v -> onUpdateOptions { it.copy(generateHardware = v) } }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.gf_files_always),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            SectionCard {
                Text(stringResource(R.string.gf_advanced), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
                Spacer(modifier = Modifier.height(8.dp))
                WuwaSwitchRow(
                    stringResource(R.string.gf_adv_restricted_t),
                    stringResource(R.string.gf_adv_restricted_c),
                    uiState.options.allowRestrictedCvars
                ) { v -> onUpdateOptions { it.copy(allowRestrictedCvars = v) } }
                WuwaSwitchRow(
                    stringResource(R.string.gf_adv_exp_t),
                    stringResource(R.string.gf_adv_exp_c),
                    uiState.options.experimentalCvars
                ) { v -> onUpdateOptions { it.copy(experimentalCvars = v) } }
                WuwaSwitchRow(
                    stringResource(R.string.gf_adv_gsr_t),
                    stringResource(R.string.gf_adv_gsr_c),
                    uiState.options.enableGSR
                ) { v -> onUpdateOptions { it.copy(enableGSR = v) } }
            }

            Spacer(modifier = Modifier.height(12.dp))

            SectionCard {
                Text(stringResource(R.string.gf_preview), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
                Spacer(modifier = Modifier.height(8.dp))
                if (uiState.previews.isEmpty()) {
                    Text(stringResource(R.string.gf_preview_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    uiState.previews.forEach { (name, content) ->
                        var expanded by remember(name) { mutableStateOf(false) }
                        TextButton(onClick = { expanded = !expanded }) {
                            Text(
                                if (expanded) stringResource(R.string.gf_preview_open, name) else stringResource(R.string.gf_preview_closed, name, content.length),
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        if (expanded) {
                            Surface(
                                color = Color.Black.copy(alpha = 0.35f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    content,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    color = Color.White.copy(alpha = 0.8f),
                                    modifier = Modifier
                                        .padding(10.dp)
                                        .heightIn(max = 320.dp)
                                        .verticalScroll(rememberScrollState())
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            SectionCard {
                Text(stringResource(R.string.gf_deploy), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.gf_deploy_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                CatsmokerButton(
                    onClick = { onDeploy(WuwaDeployChannel.SHELL) },
                    enabled = !uiState.applying && uiState.previews.isNotEmpty() && uiState.canShell,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (uiState.applying) stringResource(R.string.gf_deploying) else stringResource(R.string.gf_deploy_shell))
                }
                Spacer(modifier = Modifier.height(8.dp))
                CatsmokerOutlinedButton(
                    onClick = { onDeploy(WuwaDeployChannel.SAF) },
                    enabled = !uiState.applying && uiState.previews.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (uiState.hasSafUri) stringResource(R.string.gf_deploy_saf) else stringResource(R.string.gf_deploy_saf_pick))
                }
                if (!uiState.canShell) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.gf_deploy_noshell),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                uiState.deployFailure?.let { failure ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(failure, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }

                uiState.deployReport?.let { report -> DeployReportCard(report) }
            }

            CommunityPacksCard(
                uiState = uiState,
                onImportPack = onImportPack,
                onDeployPackVariant = onDeployPackVariant,
                onDeployPackVariantStripped = onDeployPackVariantStripped,
                onDeletePack = onDeletePack
            )

            if (uiState.backups.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                SectionCard {
                    Text(stringResource(R.string.gf_backups), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.gf_backups_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    uiState.backups.forEach { entry ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    entry.file.name.substringAfter('_'),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    "${entry.formattedTimestamp()} · ${entry.formattedSize()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = { onRestoreBackup(entry) }) { Text(stringResource(R.string.gf_restore)) }
                        }
                    }
                }
            }

            // Auto-tune sits with the history: the loop is the measured before/after FPS
            // comparison the history card's explainer points at, one Client.log read per round.
            Spacer(modifier = Modifier.height(12.dp))
            AutoTuneCard(
                uiState = uiState,
                onStart = onStartAutoTune,
                onPlayed = onTunerRoundPlayed,
                onApplyResult = onApplyTunerResult,
                onStop = onStopTuner,
                onReset = onResetTuner
            )

            if (uiState.history.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.gf_history), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
                        Spacer(modifier = Modifier.weight(1f))
                        TextButton(onClick = onClearHistory) { Text(stringResource(R.string.gf_clear_all)) }
                    }
                    Text(
                        stringResource(R.string.gf_history_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    uiState.history.forEach { record ->
                        HistoryRecordRow(
                            record = record,
                            verifying = uiState.verifyingHistoryId == record.id,
                            onVerify = { onVerifyHistory(record) },
                            onDelete = { onDeleteHistory(record) }
                        )
                    }
                }
            }
        }
    }

    if (onBack == null) {
        content()
    } else {
        ScreenScaffold(
            title = stringResource(R.string.gf_wuwa_standalone_title),
            subtitle = "Wuthering Waves",
            onBack = onBack
        ) {
            content()
        }
    }
}

@Composable
private fun CommunityPacksCard(
    uiState: WuwaConfigUiState,
    onImportPack: () -> Unit,
    onDeployPackVariant: (String, String) -> Unit,
    onDeployPackVariantStripped: (String, String) -> Unit,
    onDeletePack: (String) -> Unit
) {
    SectionCard {
        Text(
            stringResource(R.string.gf_packs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            stringResource(R.string.gf_packs_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        CatsmokerButton(onClick = onImportPack, enabled = !uiState.importingPack, modifier = Modifier.fillMaxWidth()) {
            Text(if (uiState.importingPack) stringResource(R.string.gf_importing) else stringResource(R.string.gf_import_pack))
        }
        uiState.packFailure?.let { failure ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(failure, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        uiState.communityPacks.forEach { pack ->
            Spacer(modifier = Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    pack.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { onDeletePack(pack.id) }) { Text(stringResource(R.string.gf_delete)) }
            }
            Text(
                stringResource(R.string.gf_pack_meta, pack.formattedImportedAt(), pack.variants.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // The root README is the pack's own voice; nested ones (variant notes, "Extra")
            // stay in the verbatim block below.
            val rootReadme = pack.readmes.entries
                .firstOrNull { !it.key.contains('/') }?.value
                ?: pack.readmes.values.firstOrNull()
            rootReadme?.let { readme ->
                Spacer(modifier = Modifier.height(8.dp))
                WuwaCommunityPack.readmeFacts(readme).forEach { fact ->
                    Text(
                        "${fact.label}: ${fact.value}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (fact.warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                        fontWeight = if (fact.warning) FontWeight.Bold else null
                    )
                }
            }
            if (pack.readmes.isNotEmpty()) {
                CollapsibleExplainer(
                    title = stringResource(R.string.gf_pack_readme),
                    lines = pack.readmes.entries.flatMap { (path, text) ->
                        listOf("── $path ──") + text.lines()
                    },
                    initiallyExpanded = false
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            pack.variants.forEach { variant ->
                val busy = uiState.deployingPackKey == "${pack.id}/${variant.name}"
                Surface(
                    color = Color.Black.copy(alpha = 0.25f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            variant.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            stringResource(R.string.gf_files_list, variant.files.keys.joinToString(", ")),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (variant.forbiddenCount == 0) {
                            Spacer(modifier = Modifier.height(8.dp))
                            CatsmokerButton(
                                onClick = { onDeployPackVariant(pack.id, variant.name) },
                                enabled = !uiState.applying && !busy,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (busy) stringResource(R.string.gf_deploying) else stringResource(R.string.gf_deploy_variant))
                            }
                        } else {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.gf_pack_refused, variant.forbiddenCount),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(
                                variant.forbidden.entries.joinToString { (file, keys) ->
                                    "$file: ${keys.joinToString()}"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            CatsmokerOutlinedButton(
                                onClick = { onDeployPackVariantStripped(pack.id, variant.name) },
                                enabled = !uiState.applying && !busy,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (busy) stringResource(R.string.gf_deploying) else stringResource(R.string.gf_deploy_stripped))
                            }
                        }
                    }
                }
            }
            if (pack.unknownFiles.isNotEmpty()) {
                Text(
                    stringResource(R.string.gf_pack_unknown, pack.unknownFiles.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun GameLogCard(uiState: WuwaConfigUiState, onAnalyzeLog: () -> Unit) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.gf_game_log), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
            Spacer(modifier = Modifier.weight(1f))
            uiState.logAnalysis?.let {
                Text(
                    stringResource(if (it.decrypted) R.string.gf_decrypted else R.string.gf_plaintext),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF81C784)
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            stringResource(R.string.gf_wuwa_log_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        CatsmokerButton(onClick = onAnalyzeLog, enabled = !uiState.analyzingLog, modifier = Modifier.fillMaxWidth()) {
            Text(if (uiState.analyzingLog) stringResource(R.string.gf_analyzing) else stringResource(R.string.gf_analyze_log))
        }
        uiState.logFailure?.let { failure ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(failure, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        uiState.logAnalysis?.let { analysis ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                stringResource(R.string.gf_wuwa_log_read, analysis.channelUsed, analysis.lineCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            val info = analysis.info
            StatusLine(stringResource(R.string.gf_sl_gpu), info.gpu ?: stringResource(R.string.gf_not_in_log))
            StatusLine(stringResource(R.string.gf_sl_model), info.deviceModel ?: "—")
            StatusLine(stringResource(R.string.gf_sl_api), info.api ?: stringResource(R.string.gf_could_not_tell))
            StatusLine(stringResource(R.string.gf_sl_fps_actual), info.fpsActual?.let { "%.0f".format(it) } ?: "—")
            StatusLine(stringResource(R.string.gf_sl_fps_cap), info.fpsCap?.toString() ?: "—")
            StatusLine(stringResource(R.string.gf_sl_render_scale), info.screenPct?.let { "${"%.0f".format(it)}%" } ?: "—")
            CollapsibleExplainer(
                title = stringResource(R.string.gf_diag_counts),
                lines = buildList {
                    add(stringResource(R.string.gf_diag_thermal, info.thermalEvents))
                    add(stringResource(R.string.gf_diag_oom, info.gpuOom))
                    add(stringResource(R.string.gf_diag_drops, info.dropFrames))
                    add(stringResource(R.string.gf_diag_tex, info.textureErrors))
                    add(stringResource(R.string.gf_diag_autoadj, info.autoAdjustTriggers, info.autoAdjustRecoveries))
                    add(stringResource(R.string.gf_diag_net, info.networkErrors))
                    add(stringResource(R.string.gf_diag_forbidden, info.forbiddenCvars))
                    add(stringResource(R.string.gf_diag_profile, info.deviceProfile ?: "—"))
                    if (info.activeCvars.isNotEmpty()) {
                        add("")
                        add(stringResource(R.string.gf_diag_cvars, info.activeCvars.size))
                        info.activeCvars.entries.sortedBy { it.key }.take(30).forEach { (k, v) -> add("$k = $v") }
                    }
                },
                initiallyExpanded = false
            )
        }
    }
}

/**
 * The Convene (gacha) record tracker. One tap reads the game's own Client.log for the record
 * URL the Convene History page writes there, queries every banner pool with it, and shows the
 * pull totals and per-pool pity state. Not a performance feature — it rides the same log
 * channel and 12-hour cache the config work uses, so it costs the user nothing extra.
 *
 * The prediction lines carry what the math actually holds: the pool label, the 50/50 /
 * Guaranteed / 75/25 status, pulls since the last ★5 against the soft-pity threshold, and the
 * estimate. "cached" marks a restore from the store rather than a live fetch — a cached read
 * is up to 12 h old, and the record id behind it expires faster than that.
 */
@Composable
private fun GachaCard(
    uiState: WuwaConfigUiState,
    onFetch: () -> Unit,
    onRestore: () -> Unit,
    onClear: () -> Unit
) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.gf_convene), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
            Spacer(modifier = Modifier.weight(1f))
            uiState.gachaCacheSummary?.let {
                Text(
                    stringResource(R.string.gf_wuwa_cached, it.totalPulls),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF81C784)
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            stringResource(R.string.gf_wuwa_gacha_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        CatsmokerButton(onClick = onFetch, enabled = !uiState.gachaLoading, modifier = Modifier.fillMaxWidth()) {
            Text(if (uiState.gachaLoading) stringResource(R.string.gf_querying) else stringResource(R.string.gf_fetch_convene))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            CatsmokerOutlinedButton(onClick = onRestore, enabled = uiState.gachaCacheSummary != null, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.gf_show_cached))
            }
            CatsmokerOutlinedButton(onClick = onClear, enabled = uiState.gachaCacheSummary != null || uiState.gachaData != null, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.gf_clear))
            }
        }
        uiState.gachaFailure?.let { failure ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(failure, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        uiState.gachaData?.let { data ->
            Spacer(modifier = Modifier.height(8.dp))
            StatusLine(stringResource(R.string.gf_sl_total_pulls), data.totalPulls.toString())
            StatusLine(stringResource(R.string.gf_sl_5star), data.fiveStars.toString())
            StatusLine(stringResource(R.string.gf_sl_4star), data.fourStars.toString())
            if (data.avgPity5 > 0) StatusLine(stringResource(R.string.gf_sl_avg5), "%.1f".format(data.avgPity5))
            if (data.avgPity4 > 0) StatusLine(stringResource(R.string.gf_sl_avg4), "%.1f".format(data.avgPity4))
            data.predictions.forEach { pred ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.gf_gacha_pool_line, pred.poolLabel, pred.status) +
                        if (pred.currentCharacterName.isNotBlank()) stringResource(R.string.gf_gacha_pool_char, pred.currentCharacterName) else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
                StatusLine(stringResource(R.string.gf_sl_since5), stringResource(R.string.gf_gacha_since5_value, pred.pullsSinceLastFive, pred.softPityThreshold))
                StatusLine(stringResource(R.string.gf_sl_est5), stringResource(R.string.gf_gacha_est5_value, pred.estimatedNextFive, pred.pullsUntilHardPity))
                StatusLine(stringResource(R.string.gf_sl_since4), pred.pullsSinceLastFourStar.toString())
            }
        }
    }
}

/**
 * The installed game's own record of itself: the LocalStorage/DeviceStorage database fields (UID,
 * server, level, last login, versions, language) plus a settings count over each deployed ini —
 * the numbers a user compares against "what the generator promised to write". Its device facts
 * half comes from the log, so it is null until the game has been played once.
 */
@Composable
private fun InstalledProfileCard(uiState: WuwaConfigUiState, onReadProfile: () -> Unit) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.gf_installed_profile),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.weight(1f))
            uiState.installedProfile?.dbChannel?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = Color(0xFF81C784))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            stringResource(R.string.gf_wuwa_profile_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        CatsmokerButton(onClick = onReadProfile, enabled = !uiState.readingProfile, modifier = Modifier.fillMaxWidth()) {
            Text(if (uiState.readingProfile) stringResource(R.string.gf_reading_profile) else stringResource(R.string.gf_read_profile))
        }
        uiState.profileFailure?.let { failure ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(failure, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        uiState.installedProfile?.let { profile ->
            Spacer(modifier = Modifier.height(8.dp))
            StatusLine(stringResource(R.string.gf_sl_uid), profile.uid ?: stringResource(R.string.gf_not_recorded))
            StatusLine(stringResource(R.string.gf_sl_server), profile.server ?: "—")
            StatusLine(stringResource(R.string.gf_sl_level), profile.playerLevel?.toString() ?: "—")
            StatusLine(stringResource(R.string.gf_sl_last_login), profile.lastLoginTime ?: "—")
            StatusLine(stringResource(R.string.gf_sl_client), profile.gameVersion ?: "—")
            StatusLine(stringResource(R.string.gf_sl_patch), profile.patchVersion ?: "—")
            StatusLine(stringResource(R.string.gf_sl_language), profile.language ?: "—")
            CollapsibleExplainer(
                title = stringResource(R.string.gf_progress_inis),
                lines = buildList {
                    profile.serverLevels.drop(1).forEach { (region, level) -> add(stringResource(R.string.gf_prof_region_level, region, level)) }
                    profile.towerFloor?.let { add(stringResource(R.string.gf_prof_tower, it)) }
                    profile.weeklyRogueScore?.let { add(stringResource(R.string.gf_prof_rogue, it)) }
                    profile.loopTowerSeason?.let { add(stringResource(R.string.gf_prof_loop, it)) }
                    profile.battlePassPurchased?.let {
                        add(stringResource(if (it) R.string.gf_prof_bp_yes else R.string.gf_prof_bp_no))
                    }
                    add("")
                    add(stringResource(R.string.gf_prof_ini_header))
                    profile.iniSettingCounts.entries.forEach { (name, count) -> add(stringResource(R.string.gf_prof_ini_count, name, count)) }
                    profile.log?.let { log ->
                        add("")
                        add(
                            stringResource(
                                R.string.gf_prof_log_line,
                                log.gpu ?: "—",
                                log.ramMb?.let { stringResource(R.string.gf_prof_ram, it) } ?: stringResource(R.string.gf_prof_ram_unknown),
                                log.androidVersion ?: "—",
                                log.resolution ?: stringResource(R.string.gf_prof_res_unknown)
                            )
                        )
                    }
                },
                initiallyExpanded = false
            )
        }
    }
}

/**
 * The auto-tune benchmark loop's card. The reference drove this with modal dialogs; this
 * screen is card-based, so the loop lives inline — which also means its state is still on
 * screen when the user comes back from the game, which is exactly when they need it.
 */
@Composable
private fun AutoTuneCard(
    uiState: WuwaConfigUiState,
    onStart: () -> Unit,
    onPlayed: () -> Unit,
    onApplyResult: () -> Unit,
    onStop: () -> Unit,
    onReset: () -> Unit
) {
    SectionCard {
        val tuner = uiState.tuner
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.gf_autotune), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
            Spacer(modifier = Modifier.weight(1f))
            Text(
                stringResource(
                    when (tuner?.stage) {
                        null, WuwaBenchmarkTuner.TunerStage.IDLE -> R.string.gf_tuner_idle
                        WuwaBenchmarkTuner.TunerStage.DEPLOYING -> R.string.gf_tuner_deploying
                        WuwaBenchmarkTuner.TunerStage.WAITING_FOR_PLAY -> R.string.gf_tuner_goplay
                        WuwaBenchmarkTuner.TunerStage.CAPTURING -> R.string.gf_tuner_measuring
                        WuwaBenchmarkTuner.TunerStage.COMPLETE -> if (tuner.error != null) R.string.gf_tuner_stopped else R.string.gf_tuner_done
                    }
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(8.dp))

        if (tuner == null || tuner.stage == WuwaBenchmarkTuner.TunerStage.IDLE) {
            Text(
                stringResource(R.string.gf_tuner_intro, WuwaBenchmarkTuner.MAX_ROUNDS),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            val canTune = uiState.canShell || uiState.hasSafUri
            CatsmokerButton(onClick = onStart, enabled = !uiState.tunerBusy && canTune, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.gf_start_tune))
            }
            if (!canTune) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(R.string.gf_tuner_need_channel),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@SectionCard
        }

        StatusLine(stringResource(R.string.gf_sl_round), stringResource(R.string.gf_tuner_round_of, tuner.round, WuwaBenchmarkTuner.MAX_ROUNDS))
        StatusLine(stringResource(R.string.gf_sl_preset), presetLabel(tuner.preset))
        StatusLine(stringResource(R.string.gf_sl_target), stringResource(R.string.gf_tuner_target_fps, tuner.targetFps))

        when (tuner.stage) {
            WuwaBenchmarkTuner.TunerStage.DEPLOYING -> {
                Spacer(modifier = Modifier.height(8.dp))
                Text(stringResource(R.string.gf_tuner_deploying_round, tuner.round), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
            WuwaBenchmarkTuner.TunerStage.WAITING_FOR_PLAY -> {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.gf_tuner_play_then_measure, WuwaBenchmarkTuner.LINE_WINDOW),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                CatsmokerButton(onClick = onPlayed, enabled = !uiState.tunerBusy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.gf_measure_now))
                }
                TextButton(onClick = onStop, enabled = !uiState.tunerBusy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.gf_stop_tuning))
                }
            }
            WuwaBenchmarkTuner.TunerStage.CAPTURING -> {
                Spacer(modifier = Modifier.height(8.dp))
                Text(stringResource(R.string.gf_measuring_log), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
            WuwaBenchmarkTuner.TunerStage.COMPLETE -> {
                Spacer(modifier = Modifier.height(8.dp))
                if (tuner.error != null) {
                    Text(tuner.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                } else {
                    val last = tuner.results.lastOrNull()
                    val met = last != null && last.avgFps >= tuner.targetFps
                    Text(
                        stringResource(
                            if (met) R.string.gf_tuner_best_met else R.string.gf_tuner_best_close,
                            presetLabel(tuner.finalPreset ?: tuner.preset)
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (tuner.finalPreset != null) {
                        CatsmokerButton(onClick = onApplyResult, enabled = !uiState.tunerBusy, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.gf_apply_best))
                        }
                    }
                    CatsmokerOutlinedButton(onClick = onReset, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.gf_dismiss))
                    }
                }
            }
            WuwaBenchmarkTuner.TunerStage.IDLE -> {}
        }

        if (tuner.results.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            CollapsibleExplainer(
                title = stringResource(R.string.gf_measured_rounds, tuner.results.size),
                lines = tuner.results.map { r ->
                    stringResource(
                        R.string.gf_tuner_round_line,
                        r.round,
                        presetLabel(r.preset),
                        "%.1f".format(r.avgFps),
                        "%.1f".format(r.minFps),
                        "%.0f".format(r.stabilityPct)
                    )
                },
                initiallyExpanded = true
            )
        }
    }
}

@Composable
private fun RecommendCard(
    rec: WuwaSmartBrain.Recommendation,
    currentPreset: String,
    onApply: () -> Unit
) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.gf_smart_recommend), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
            Spacer(modifier = Modifier.weight(1f))
            Text(
                stringResource(R.string.gf_wuwa_score, rec.score, rec.tier),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    presetLabel(rec.preset),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    if (currentPreset == rec.preset) stringResource(R.string.gf_wuwa_already_selected) else stringResource(R.string.gf_wuwa_rec_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            CatsmokerButton(onClick = onApply, enabled = currentPreset != rec.preset) {
                Text(stringResource(R.string.gf_apply))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        CollapsibleExplainer(
            title = stringResource(R.string.gf_wuwa_why_score, rec.signals.size, rec.warnings.size),
            lines = buildList {
                rec.signals.forEach { add(it) }
                if (rec.warnings.isNotEmpty()) {
                    add("")
                    add(stringResource(R.string.gf_wuwa_warnings))
                    rec.warnings.forEach { add("! $it") }
                }
                add("")
                if (rec.hadLogEvidence) {
                    add(stringResource(R.string.gf_wuwa_scored_log_1))
                    add(stringResource(R.string.gf_wuwa_scored_log_2))
                } else {
                    add(stringResource(R.string.gf_wuwa_no_log_1))
                    add(stringResource(R.string.gf_wuwa_no_log_2))
                }
                rec.logOnlyAxes.forEach { add("· $it") }
                if (!rec.hadLogEvidence) {
                    add("")
                    add(stringResource(R.string.gf_wuwa_no_evidence_1))
                    add(stringResource(R.string.gf_wuwa_no_evidence_2))
                    add(stringResource(R.string.gf_wuwa_no_evidence_3))
                }
            },
            initiallyExpanded = false
        )
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(110.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f), fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun WuwaSwitchRow(title: String, caption: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Channel label for a stored record's channel string ([WuwaConfigManager.Channel.name]). */
@Composable
private fun historyChannelLabel(channel: String): String = when (channel) {
    WuwaConfigManager.Channel.ROOT_OR_SHIZUKU.name -> stringResource(R.string.gf_hist_channel_shell)
    WuwaConfigManager.Channel.SAF.name -> "SAF"
    // Old records from before wireless ADB was removed keep their label.
    "ADB" -> stringResource(R.string.gf_hist_channel_adb)
    else -> channel
}

@Composable
private fun historyStatusLabel(status: WuwaDeployHistoryStore.Status): String = when (status) {
    WuwaDeployHistoryStore.Status.MATCH -> stringResource(R.string.gf_hist_status_match)
    WuwaDeployHistoryStore.Status.CHANGED -> stringResource(R.string.gf_hist_status_changed)
    WuwaDeployHistoryStore.Status.MISSING -> stringResource(R.string.gf_hist_status_missing)
    WuwaDeployHistoryStore.Status.UNREADABLE -> stringResource(R.string.gf_hist_status_unreadable)
    WuwaDeployHistoryStore.Status.NOT_RECORDED -> stringResource(R.string.gf_hist_status_norecord)
}

@Composable
private fun HistoryRecordRow(
    record: WuwaDeployHistoryStore.Record,
    verifying: Boolean,
    onVerify: () -> Unit,
    onDelete: () -> Unit
) {
    val presetName = if (record.preset == "restore") stringResource(R.string.gf_restore_label) else presetLabel(record.preset)
    val verifiedCount = record.files.count { it.verified }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.gf_history_title, presetName, historyChannelLabel(record.channel)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    stringResource(
                        R.string.gf_history_subtitle,
                        record.formattedTimestamp(),
                        record.files.size,
                        verifiedCount
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onVerify, enabled = !verifying) {
                Text(if (verifying) stringResource(R.string.gf_verifying) else stringResource(R.string.gf_verify))
            }
            TextButton(onClick = onDelete) { Text(stringResource(R.string.gf_delete)) }
        }
        record.verification?.let { v ->
            val mismatched = v.files.count {
                it.status != WuwaDeployHistoryStore.Status.MATCH && it.status != WuwaDeployHistoryStore.Status.NOT_RECORDED
            }
            Text(
                if (mismatched > 0) stringResource(
                    R.string.gf_history_checked_diff,
                    SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(v.timestamp)),
                    v.channelUsed,
                    v.files.count { it.status == WuwaDeployHistoryStore.Status.MATCH },
                    mismatched
                ) else stringResource(
                    R.string.gf_history_checked_ok,
                    SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(v.timestamp)),
                    v.channelUsed,
                    v.files.count { it.status == WuwaDeployHistoryStore.Status.MATCH }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (mismatched > 0) MaterialTheme.colorScheme.error else Color(0xFF81C784)
            )
        }
        CollapsibleExplainer(
            title = stringResource(R.string.gf_history_what),
            lines = buildList {
                add(
                    when (record.gameStopped) {
                        true -> stringResource(R.string.gf_hs_stopped)
                        false -> stringResource(R.string.gf_hs_stop_refused)
                        null -> stringResource(R.string.gf_hs_not_stopped)
                    }
                )
                record.files.forEach { f ->
                    val backupNote = when {
                        f.backupTaken -> stringResource(R.string.gf_hist_backup_taken)
                        !f.pushed -> ""
                        else -> stringResource(R.string.gf_file_no_backup)
                    }
                    val md5Note = if (f.md5.isNotBlank()) stringResource(R.string.gf_hist_md5, f.md5.take(8)) else ""
                    add(
                        (if (!f.pushed) stringResource(R.string.gf_file_refused, f.name)
                        else if (f.verified) stringResource(R.string.gf_file_pushed_ok, f.name)
                        else stringResource(R.string.gf_file_pushed_unver, f.name)) + backupNote + md5Note
                    )
                }
                add(
                    when (record.hashSynced) {
                        true -> stringResource(R.string.gf_hash_ok)
                        false -> stringResource(R.string.gf_hash_fail, record.hashDetail)
                        null -> stringResource(R.string.gf_hash_na, record.hashDetail)
                    }
                )
                record.verification?.let { v ->
                    add("")
                    add(stringResource(R.string.gf_recheck_line, v.channelUsed))
                    v.files.forEach { add(stringResource(R.string.gf_verify_file_line, it.name, historyStatusLabel(it.status), it.detail)) }
                }
            },
            initiallyExpanded = false
        )
    }
}

@Composable
private fun DeployReportCard(report: WuwaConfigManager.DeploySuccess) {    Spacer(modifier = Modifier.height(12.dp))
    CollapsibleExplainer(
        title = stringResource(R.string.gf_deploy_report_title),
        lines = buildList {
            add(stringResource(R.string.gf_channel_line, when (report.channel) {
                WuwaConfigManager.Channel.ROOT_OR_SHIZUKU -> stringResource(R.string.gf_hist_channel_shell)
                WuwaConfigManager.Channel.SAF -> "SAF"
            }))
            add(
                when (report.gameStopped) {
                    true -> stringResource(R.string.gf_hs_stopped)
                    false -> stringResource(R.string.gf_hs_stop_refused)
                    null -> stringResource(R.string.gf_hs_not_stopped)
                }
            )
            report.files.forEach { f ->
                val backupNote = when {
                    f.backup != null -> stringResource(R.string.gf_file_backup_taken, f.backup.formattedTimestamp())
                    f.backupFailed -> stringResource(R.string.gf_file_backup_failed)
                    else -> stringResource(R.string.gf_file_no_prior)
                }
                add(
                    (if (!f.pushed) stringResource(R.string.gf_file_refused_detail, f.name, f.detail)
                    else if (f.verified) stringResource(R.string.gf_file_pushed_ok, f.name)
                    else stringResource(R.string.gf_file_pushed_unver, f.name)) + backupNote
                )
            }
            add(
                when (report.hashSynced) {
                    true -> stringResource(R.string.gf_hash_ok)
                    false -> stringResource(R.string.gf_hash_fail, report.hashDetail)
                    null -> stringResource(R.string.gf_hash_na, report.hashDetail)
                }
            )
        },
        initiallyExpanded = true
    )
}
