package com.catsmoker.app.features.editgamefiles.hsr

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.catsmoker.app.R
import com.catsmoker.app.features.gamingtools.ui.CollapsibleExplainer
import com.catsmoker.app.shared.ui.components.ScreenScaffold
import com.catsmoker.app.shared.ui.components.SectionCard
import com.catsmoker.app.shared.ui.theme.CatsmokerTheme
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.roundToInt
import com.catsmoker.app.shared.ui.components.CatsmokerButton
import com.catsmoker.app.shared.ui.components.CatsmokerOutlinedButton

/**
 * Honkai: Star Rail graphics editor. Root-only by mechanism — the game's settings live inside
 * its private storage — so the screen renders three visibly different states: loading, a
 * failure card that names the stage (and explains the root requirement when that is the
 * stage), and the editor itself. Cloned from the Edit Game Files screen's shape rather than
 * drawn from blank, per this codebase's convention that a new screen starts from the closest
 * existing one.
 *
 * Hosted two ways: as a destination of its own (non-null [onBack], the header and back arrow
 * are this route's own), or embedded inside File Engineering's game selector (null — no
 * second header below that screen's, whose back arrow is the way out).
 *
 * Card rhythm is the host's: cards carry no vertical padding of their own and the content
 * column spaces its children 12 dp apart, so an embedded editor reads as more sections of
 * the File Engineering screen rather than a different screen pasted below the selector —
 * the per-card `vertical = 8.dp` padding used to read exactly that way. Action buttons are
 * uppercase to match the host's APPLY PROFILE / RESTORE BACKUP idiom.
 */
@Composable
fun HsrGraphicsRoute(onBack: (() -> Unit)? = null) {
    val viewModel: HsrGraphicsViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is HsrGraphicsViewModel.HsrEvent.Toast ->
                    Toast.makeText(context, event.message, if (event.isLong) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
            }
        }
    }

    HsrGraphicsScreen(
        uiState = uiState,
        onUpdate = viewModel::updateSettings,
        onUpdatePrefs = viewModel::updatePrefs,
        onApply = viewModel::onApply,
        onApplyPrefs = viewModel::onApplyPrefs,
        onRestoreBackup = viewModel::onRestoreBackup,
        onRefresh = viewModel::refresh,
        onBack = onBack
    )
}

@Composable
fun HsrGraphicsScreen(
    uiState: HsrGraphicsUiState,
    onUpdate: ((HsrGraphicsSettings) -> HsrGraphicsSettings) -> Unit,
    onUpdatePrefs: ((HsrGamePreferences) -> HsrGamePreferences) -> Unit,
    onApply: () -> Unit,
    onApplyPrefs: () -> Unit,
    onRestoreBackup: () -> Unit,
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
            when {
                uiState.loading -> Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 96.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }

                uiState.loadFailure != null -> HsrLoadFailureCard(uiState.loadFailure, onRefresh)

                uiState.settings != null -> {
                    val settings = uiState.settings

                    StatusCard(uiState)

                    Spacer(modifier = Modifier.height(12.dp))

                    SectionCard {
                        Text(stringResource(R.string.gf_frame_rate), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(30, 60, 120).forEach { fps ->
                                FilterChip(
                                    selected = settings.fps == fps,
                                    onClick = { onUpdate { it.copy(fps = fps, graphicsQuality = 0) } },
                                    label = { Text("$fps FPS") }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.gf_hsr_fps_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    SectionCard {
                        Text(stringResource(R.string.gf_render_scale_sync), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
                        Spacer(modifier = Modifier.height(12.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.gf_hsr_render_scale), modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
                            Text("%.2fx".format(settings.renderScale), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = settings.renderScale.toFloat(),
                            onValueChange = { raw ->
                                onUpdate { it.copy(renderScale = (raw * 100).roundToInt() / 100.0) }
                            },
                            valueRange = 0.5f..2.0f,
                            steps = 29
                        )
                        Text(
                            stringResource(R.string.gf_hsr_render_caption),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.gf_hsr_vsync), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
                                Text(stringResource(R.string.gf_hsr_vsync_caption), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = settings.enableVSync,
                                onCheckedChange = { checked -> onUpdate { it.copy(enableVSync = checked) } }
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.gf_hsr_pso), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
                                Text(stringResource(R.string.gf_hsr_pso_caption), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = settings.enablePsoShaderWarmup,
                                onCheckedChange = { checked -> onUpdate { it.copy(enablePsoShaderWarmup = checked) } }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    SectionCard {
                        Text(stringResource(R.string.gf_quality), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        QualitySlider(stringResource(R.string.gf_hsr_q_resolution), settings.resolutionQuality, qualityNameFor(settings.resolutionQuality)) { v -> onUpdate { it.copy(resolutionQuality = v) } }
                        QualitySlider(stringResource(R.string.gf_hsr_q_shadow), settings.shadowQuality, qualityNameFor(settings.shadowQuality)) { v -> onUpdate { it.copy(shadowQuality = v) } }
                        QualitySlider(stringResource(R.string.gf_hsr_q_light), settings.lightQuality, qualityNameFor(settings.lightQuality)) { v -> onUpdate { it.copy(lightQuality = v) } }
                        QualitySlider(stringResource(R.string.gf_hsr_q_character), settings.characterQuality, qualityNameFor(settings.characterQuality)) { v -> onUpdate { it.copy(characterQuality = v) } }
                        QualitySlider(stringResource(R.string.gf_hsr_q_environment), settings.envDetailQuality, qualityNameFor(settings.envDetailQuality)) { v -> onUpdate { it.copy(envDetailQuality = v) } }
                        QualitySlider(stringResource(R.string.gf_hsr_q_reflection), settings.reflectionQuality, qualityNameFor(settings.reflectionQuality)) { v -> onUpdate { it.copy(reflectionQuality = v) } }
                        QualitySlider(stringResource(R.string.gf_hsr_q_sfx), settings.sfxQuality, sfxQualityNameFor(settings.sfxQuality)) { v -> onUpdate { it.copy(sfxQuality = v) } }
                        QualitySlider(stringResource(R.string.gf_hsr_q_bloom), settings.bloomQuality, qualityNameFor(settings.bloomQuality)) { v -> onUpdate { it.copy(bloomQuality = v) } }
                        Text(
                            stringResource(R.string.gf_hsr_sfx_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    SectionCard {
                        Text(stringResource(R.string.gf_hidden_upscaling), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.gf_hsr_hidden_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.gf_hsr_metalfx), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
                                Text(stringResource(R.string.gf_hsr_metalfx_caption), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = settings.enableMetalFXSU,
                                onCheckedChange = { checked -> onUpdate { it.copy(enableMetalFXSU = checked) } }
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.gf_hsr_halfres), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
                                Text(stringResource(R.string.gf_hsr_halfres_caption), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = settings.enableHalfResTransparent,
                                onCheckedChange = { checked -> onUpdate { it.copy(enableHalfResTransparent = checked) } }
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.gf_hsr_dlss), modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
                                Text(dlssNameFor(settings.dlssQuality), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            }
                            Slider(
                                value = settings.dlssQuality.toFloat(),
                                onValueChange = { raw -> onUpdate { it.copy(dlssQuality = raw.roundToInt()) } },
                                valueRange = 0f..4f,
                                steps = 3
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.gf_hsr_self_shadow), modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
                                Text(selfShadowNameFor(settings.enableSelfShadow), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            }
                            Slider(
                                value = settings.enableSelfShadow.toFloat(),
                                onValueChange = { raw -> onUpdate { it.copy(enableSelfShadow = raw.roundToInt()) } },
                                valueRange = 0f..2f,
                                steps = 1
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.gf_hsr_particle), modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
                                Text(particleTrailNameFor(settings.particleTrailSmoothness), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            }
                            Slider(
                                value = settings.particleTrailSmoothness.toFloat(),
                                onValueChange = { raw -> onUpdate { it.copy(particleTrailSmoothness = raw.roundToInt()) } },
                                valueRange = 0f..3f,
                                steps = 2
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    SectionCard {
                        Text(stringResource(R.string.gf_anti_aliasing), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(0 to stringResource(R.string.gf_off), 1 to "TAA", 2 to "FXAA").forEach { (mode, name) ->
                                FilterChip(
                                    selected = settings.aaMode == mode,
                                    onClick = { onUpdate { it.copy(aaMode = mode) } },
                                    label = { Text(name) }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    SectionCard {
                        Text(stringResource(R.string.gf_res_decoupling), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("360p", "720p", "1080p", "1440p", "4K").forEach { preset ->
                                FilterChip(
                                    selected = settings.resolutionPreset() == preset,
                                    onClick = {
                                        val dims = HsrGraphicsSettings.resolutionFor(preset)
                                        if (dims != null) {
                                            onUpdate { it.copy(screenWidth = dims.first, screenHeight = dims.second) }
                                        }
                                    },
                                    label = { Text(preset) }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.gf_hsr_current_res, settings.screenWidth, settings.screenHeight),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    CatsmokerButton(
                        onClick = onApply,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !uiState.applying
                    ) {
                        if (uiState.applying) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.Black)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.gf_applying))
                        } else {
                            Text(stringResource(R.string.gf_apply_to_game))
                        }
                    }
                    if (uiState.hasBackup) {
                        Spacer(modifier = Modifier.height(8.dp))
                        CatsmokerOutlinedButton(onClick = onRestoreBackup, modifier = Modifier.fillMaxWidth(), enabled = !uiState.applying) {
                            Text(stringResource(R.string.gf_restore_last_backup))
                        }
                    }

                    uiState.gamePrefs?.let { prefs ->
                        Spacer(modifier = Modifier.height(12.dp))

                        SectionCard {
                            Text(stringResource(R.string.gf_game_prefs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.gf_hsr_prefs_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(12.dp))

                            LanguageDropdown(
                                label = stringResource(R.string.gf_hsr_text_lang),
                                options = HsrGamePreferences.TEXT_LANGUAGES,
                                selected = prefs.textLanguage,
                                onSelect = { code -> onUpdatePrefs { it.copy(textLanguage = code) } }
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            LanguageDropdown(
                                label = stringResource(R.string.gf_hsr_voice_lang),
                                options = HsrGamePreferences.AUDIO_LANGUAGES,
                                selected = prefs.audioLanguage,
                                onSelect = { code -> onUpdatePrefs { it.copy(audioLanguage = code) } }
                            )

                            Spacer(modifier = Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.gf_hsr_autobattle), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
                                    Text(
                                        if (prefs.autoBattleOpen != null) stringResource(R.string.gf_hsr_autobattle_known)
                                        else stringResource(R.string.gf_hsr_autobattle_missing),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = prefs.autoBattleOpen == 1,
                                    enabled = prefs.autoBattleOpen != null,
                                    onCheckedChange = { checked -> onUpdatePrefs { it.copy(autoBattleOpen = if (checked) 1 else 0) } }
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.gf_hsr_speedup), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
                                    Text(
                                        if (prefs.speedUpOpen != null) stringResource(R.string.gf_hsr_speedup_known)
                                        else stringResource(R.string.gf_hsr_speedup_missing),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = prefs.speedUpOpen == 1,
                                    enabled = prefs.speedUpOpen != null,
                                    onCheckedChange = { checked -> onUpdatePrefs { it.copy(speedUpOpen = if (checked) 1 else 0) } }
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            if (prefs.videoBlacklist.isNotEmpty()) {
                                CollapsibleExplainer(
                                    title = stringResource(R.string.gf_hsr_skipped_scenes, prefs.videoBlacklist.size),
                                    lines = prefs.videoBlacklist
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            if (prefs.audioBlacklist.isNotEmpty()) {
                                CollapsibleExplainer(
                                    title = stringResource(R.string.gf_hsr_skipped_voice, prefs.audioBlacklist.size),
                                    lines = prefs.audioBlacklist
                                )
                            }

                            uiState.lastPrefsApply?.let { report ->
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(stringResource(R.string.gf_last_apply, report), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        CatsmokerButton(
                            onClick = onApplyPrefs,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !uiState.applyingPrefs
                        ) {
                            if (uiState.applyingPrefs) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.Black)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.gf_applying))
                            } else {
                                Text(stringResource(R.string.gf_apply_prefs))
                            }
                        }
                    }
                }
            }
        }
    }

    if (onBack == null) {
        content()
    } else {
        ScreenScaffold(
            title = stringResource(R.string.gf_hsr_standalone_title),
            subtitle = uiState.packageLabel ?: "Honkai: Star Rail",
            onBack = onBack
        ) {
            content()
        }
    }
}

@Composable
private fun StatusCard(uiState: HsrGraphicsUiState) {
    SectionCard {
        Text(stringResource(R.string.gf_detected_install), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Text(uiState.packageName ?: "", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(
            uiState.prefsPath ?: "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        uiState.lastApply?.let { report ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(stringResource(R.string.gf_last_apply, report), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun HsrLoadFailureCard(failure: HsrReadResult.Failure, onRefresh: () -> Unit) {
    // Host-styled failure surface: the File Engineering screen's GAME NOT FOUND card is a
    // SectionCard with a labelSmall title (error red for a missing game, primary otherwise),
    // so a load failure here reads as the same kind of thing, not a different component.
    SectionCard {
        Column {
            when (failure.stage) {
                HsrReadResult.Stage.NO_ROOT -> {
                    Text(stringResource(R.string.gf_hsr_root_title), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.gf_hsr_root_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                }
                HsrReadResult.Stage.GAME_NOT_INSTALLED -> {
                    Text(stringResource(R.string.gf_game_not_found), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, letterSpacing = 1.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.gf_hsr_missing_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                }
                HsrReadResult.Stage.PREFS_NOT_FOUND -> {
                    Text(stringResource(R.string.gf_hsr_settings_title), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.gf_hsr_settings_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                }
                HsrReadResult.Stage.READ_FAILED -> {
                    Text(stringResource(R.string.gf_could_not_read_title), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.gf_read_failed_detail, failure.detail),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                }
                HsrReadResult.Stage.PARSE_FAILED -> {
                    Text(stringResource(R.string.gf_hsr_format_title), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.gf_hsr_format_body, failure.detail),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            CatsmokerOutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.gf_retry))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageDropdown(
    label: String,
    options: Map<Int, String>,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = options[selected] ?: stringResource(R.string.gf_hsr_unknown_lang),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth()
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { (code, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        onSelect(code)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
        }
    }
}

@Composable
private fun QualitySlider(
    label: String,
    value: Int,
    valueText: String,
    onValueChange: (Int) -> Unit
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
            Text(valueText, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { raw -> onValueChange(raw.roundToInt()) },
            valueRange = 0f..5f,
            steps = 4
        )
    }
}

/** Localized names for the 0–5 quality steps. Mirrors [HsrGraphicsSettings.qualityName]. */
@Composable
private fun qualityNameFor(quality: Int): String = when (quality) {
    0 -> stringResource(R.string.gf_hsr_q_very_low)
    1 -> stringResource(R.string.gf_hsr_q_low)
    2 -> stringResource(R.string.gf_hsr_q_medium)
    3 -> stringResource(R.string.gf_hsr_q_high)
    4 -> stringResource(R.string.gf_hsr_q_very_high)
    5 -> stringResource(R.string.gf_hsr_q_ultra)
    else -> stringResource(R.string.gf_hsr_q_unknown)
}

/** Localized names for SFX's shifted scale. Mirrors [HsrGraphicsSettings.sfxQualityName]. */
@Composable
private fun sfxQualityNameFor(quality: Int): String = when (quality) {
    0 -> stringResource(R.string.gf_hsr_sfx_invalid)
    1 -> stringResource(R.string.gf_hsr_q_very_low)
    2 -> stringResource(R.string.gf_hsr_q_low)
    3 -> stringResource(R.string.gf_hsr_q_medium)
    4 -> stringResource(R.string.gf_hsr_q_high)
    5 -> stringResource(R.string.gf_hsr_q_very_high)
    else -> stringResource(R.string.gf_hsr_q_unknown)
}

/** Localized DLSS tier names. Mirrors [HsrGraphicsSettings.dlssName]. */
@Composable
private fun dlssNameFor(quality: Int): String = when (quality) {
    0 -> stringResource(R.string.gf_off)
    1 -> stringResource(R.string.gf_hsr_dlss_quality)
    2 -> stringResource(R.string.gf_hsr_dlss_balanced)
    3 -> stringResource(R.string.gf_hsr_dlss_performance)
    4 -> stringResource(R.string.gf_hsr_dlss_ultra)
    else -> stringResource(R.string.gf_off)
}

/** Localized Self Shadow level names. Mirrors [HsrGraphicsSettings.selfShadowName]. */
@Composable
private fun selfShadowNameFor(level: Int): String = when (level) {
    0 -> stringResource(R.string.gf_off)
    1 -> stringResource(R.string.gf_hsr_q_low)
    2 -> stringResource(R.string.gf_hsr_q_high)
    else -> stringResource(R.string.gf_off)
}

/** Localized particle-trail level names. Mirrors [HsrGraphicsSettings.particleTrailName]. */
@Composable
private fun particleTrailNameFor(level: Int): String = when (level) {
    0 -> stringResource(R.string.gf_off)
    1 -> stringResource(R.string.gf_hsr_q_low)
    2 -> stringResource(R.string.gf_hsr_q_medium)
    3 -> stringResource(R.string.gf_hsr_q_high)
    else -> stringResource(R.string.gf_off)
}

@Preview(showBackground = true)
@Composable
private fun HsrGraphicsScreenPreview() {
    CatsmokerTheme {
        HsrGraphicsScreen(
            uiState = HsrGraphicsUiState(
                settings = HsrGraphicsSettings(),
                gamePrefs = HsrGamePreferences(),
                packageName = "com.HoYoverse.hkrpgoversea",
                packageLabel = "Global/SEA",
                prefsPath = "/data_mirror/data_ce/null/0/com.HoYoverse.hkrpgoversea/shared_prefs/com.HoYoverse.hkrpgoversea.v2.playerprefs.xml",
                hasBackup = true
            ),
            onUpdate = {},
            onUpdatePrefs = {},
            onApply = {},
            onApplyPrefs = {},
            onRestoreBackup = {},
            onRefresh = {},
            onBack = {}
        )
    }
}
