package com.catsmoker.app.features.spoofdevice

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.catsmoker.app.R
import com.catsmoker.app.shared.data.model.DevicePreset
import com.catsmoker.app.shared.data.model.DeviceProfile
import com.catsmoker.app.shared.ui.components.ScreenScaffold
import com.catsmoker.app.shared.ui.components.SectionCard
import com.catsmoker.app.shared.util.RandomGenerator
import com.catsmoker.app.shared.ui.components.CatsmokerButton

/**
 * The MCC+MNC this profile actually publishes, using the same precedence as
 * [com.catsmoker.app.shared.data.repository.SpoofRepository.renderConfig]: the SIM operator wins,
 * falling back to the network operator. Deriving the SIM identifiers from anything else lets the
 * IMSI contradict `gsm.sim.operator.numeric` in the rendered profile.
 */
private fun DeviceProfile.publishedOperatorNumeric(): String =
    simOperatorNumeric.ifBlank { operatorNumeric }

/** The known [RandomGenerator.Operator] behind [publishedOperatorNumeric], or null if hand-entered. */
private fun DeviceProfile.resolvedOperator(): RandomGenerator.Operator? =
    RandomGenerator.operatorForNumeric(publishedOperatorNumeric())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditorScreen(
    profileId: String,
    uiState: SpoofDeviceViewModel.UiState,
    presets: List<DevicePreset>,
    onSave: (String, String, DeviceProfile) -> Unit,
    onBack: () -> Unit
) {
    val entry = uiState.profiles.find { it.id == profileId }
    if (entry == null) {
        onBack()
        return
    }

    var profileName by remember { mutableStateOf(entry.name) }
    var profile by remember { mutableStateOf(entry.profile.copy()) }
    var expandedPresets by remember { mutableStateOf(false) }
    var selectedPresetLabel by remember { mutableStateOf("") }
    val presetHint = stringResource(R.string.spoof_editor_preset_hint)

    val isDefaultProfile = uiState.profiles.firstOrNull()?.id == profileId

    ScreenScaffold(
        title = stringResource(R.string.spoof_editor_title),
        subtitle = stringResource(R.string.spoof_editor_subtitle),
        onBack = onBack,
        trailingContent = {
            IconButton(onClick = { onSave(profileId, profileName, profile) }) {
                Icon(Icons.Default.Save, null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SectionCard {
                OutlinedTextField(
                    value = profileName,
                    onValueChange = { if (!isDefaultProfile) profileName = it },
                    label = { Text(stringResource(R.string.spoof_field_profile_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    readOnly = isDefaultProfile,
                    supportingText = {
                        if (isDefaultProfile) {
                            Text(stringResource(R.string.spoof_editor_default_locked))
                        }
                    }
                )
            }

            Text(stringResource(R.string.spoof_editor_presets), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SectionCard {
                ExposedDropdownMenuBox(
                    expanded = expandedPresets,
                    onExpandedChange = { expandedPresets = it }
                ) {
                    OutlinedTextField(
                        value = selectedPresetLabel.ifBlank { presetHint },
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expandedPresets) },
                        modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = expandedPresets,
                        onDismissRequest = { expandedPresets = false }
                    ) {
                        presets.forEach { preset ->
                            DropdownMenuItem(
                                text = { Text(preset.displayName) },
                                onClick = {
                                    profile = preset.profile.copy()
                                    selectedPresetLabel = preset.displayName
                                    expandedPresets = false
                                }
                            )
                        }
                    }
                }
            }

            EditorGroup(title = stringResource(R.string.spoof_group_hardware)) {
                EditorField(stringResource(R.string.spoof_field_brand), profile.brand) { profile = profile.copy(brand = it) }
                EditorField(stringResource(R.string.spoof_field_manufacturer), profile.manufacturer) { profile = profile.copy(manufacturer = it) }
                EditorField(stringResource(R.string.spoof_field_model), profile.model) { profile = profile.copy(model = it) }
                EditorField(stringResource(R.string.spoof_field_product_name), profile.productName) { profile = profile.copy(productName = it) }
                EditorField(stringResource(R.string.spoof_field_device_code), profile.deviceCode) { profile = profile.copy(deviceCode = it) }
                EditorField(stringResource(R.string.spoof_field_board), profile.board) { profile = profile.copy(board = it) }
                EditorField(stringResource(R.string.spoof_field_hardware), profile.hardware) { profile = profile.copy(hardware = it) }
                EditorField(stringResource(R.string.spoof_field_platform), profile.boardPlatform) { profile = profile.copy(boardPlatform = it) }
            }

            EditorGroup(title = stringResource(R.string.spoof_group_gpu)) {
                // Blank means "leave the real GL strings alone" — the presets carry no verified
                // GPU identifiers, so inventing one would be the fabricated-codename mistake.
                // Both fields render as a pair or not at all (see renderConfig), so filling
                // only one of them silently does nothing.
                EditorField(stringResource(R.string.spoof_field_gl_vendor), profile.gpuVendor) { profile = profile.copy(gpuVendor = it) }
                EditorField(stringResource(R.string.spoof_field_gl_renderer), profile.gpuRenderer) { profile = profile.copy(gpuRenderer = it) }
            }

            EditorGroup(title = stringResource(R.string.spoof_group_build)) {
                CatsmokerButton(
                    onClick = {
                        val buildId = RandomGenerator.generateBuildId()
                        val incremental = RandomGenerator.generateIncremental()
                        profile = profile.copy(
                            buildId = buildId,
                            buildDisplayId = buildId,
                            buildIncremental = incremental,
                            securityPatch = RandomGenerator.generateSecurityPatch(),
                            buildFingerprint = RandomGenerator.generateFingerprint(
                                profile.brand, profile.productName, profile.deviceCode, profile.buildRelease, buildId, incremental
                            ),
                            bootloader = RandomGenerator.generateBootloader(profile.deviceCode)
                        )
                    },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    colors = ButtonDefaults.filledTonalButtonColors()
                ) {
                    Icon(Icons.Default.Casino, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.spoof_action_roll_build))
                }
                EditorField(stringResource(R.string.spoof_field_android_version), profile.buildRelease) { profile = profile.copy(buildRelease = it) }
                EditorField(stringResource(R.string.spoof_field_sdk), profile.buildSdk.toString(), numeric = true) { profile = profile.copy(buildSdk = it.toIntOrNull() ?: 0) }
                AdvancedField(stringResource(R.string.spoof_field_build_id), profile.buildId, { profile = profile.copy(buildId = it, buildDisplayId = it) }) { RandomGenerator.generateBuildId() }
                AdvancedField(stringResource(R.string.spoof_field_incremental), profile.buildIncremental, { profile = profile.copy(buildIncremental = it) }) { RandomGenerator.generateIncremental() }
                AdvancedField(stringResource(R.string.spoof_field_security_patch), profile.securityPatch, { profile = profile.copy(securityPatch = it) }) { RandomGenerator.generateSecurityPatch() }
                AdvancedField(stringResource(R.string.spoof_field_fingerprint), profile.buildFingerprint, { profile = profile.copy(buildFingerprint = it) }) {
                    RandomGenerator.generateFingerprint(profile.brand, profile.productName, profile.deviceCode, profile.buildRelease, profile.buildId, profile.buildIncremental)
                }
                AdvancedField(stringResource(R.string.spoof_field_bootloader), profile.bootloader, { profile = profile.copy(bootloader = it) }) { RandomGenerator.generateBootloader(profile.deviceCode) }
            }

            EditorGroup(title = stringResource(R.string.spoof_group_display)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { EditorField(stringResource(R.string.spoof_field_width), profile.screenWidth.toString(), numeric = true) { profile = profile.copy(screenWidth = it.toIntOrNull() ?: 0) } }
                    Box(Modifier.weight(1f)) { EditorField(stringResource(R.string.spoof_field_height), profile.screenHeight.toString(), numeric = true) { profile = profile.copy(screenHeight = it.toIntOrNull() ?: 0) } }
                    Box(Modifier.weight(1f)) { EditorField(stringResource(R.string.spoof_field_density), profile.screenDensity.toString(), numeric = true) { profile = profile.copy(screenDensity = it.toIntOrNull() ?: 0) } }
                }
                // Blank/0 means "leave the panel's real rate alone" — the presets carry no verified
                // panel tier, so an invented 120 would be the fabricated-codename mistake. 0 is
                // what renders as no `screen.refresh_rate` key at all (see renderConfig), and 0 is
                // also what the old profiles Gson fills deserialize to, so nothing needs migrating.
                EditorField(
                    stringResource(R.string.spoof_field_refresh_rate),
                    if (profile.screenRefreshRate > 0) profile.screenRefreshRate.toString() else "",
                    numeric = true
                ) { profile = profile.copy(screenRefreshRate = it.toIntOrNull() ?: 0) }
            }

            EditorGroup(title = stringResource(R.string.spoof_group_network)) {
                CatsmokerButton(
                    onClick = {
                        val op = RandomGenerator.randomOperator()
                        profile = profile.copy(
                            operatorAlpha = op.alpha,
                            operatorNumeric = op.numeric,
                            simOperatorAlpha = op.alpha,
                            simOperatorNumeric = op.numeric,
                            simCountryIso = op.countryIso,
                            timezone = RandomGenerator.randomTimezone(),
                            locale = RandomGenerator.randomLocale(),
                            // The SIM identity has to follow the operator. Leaving the old values
                            // behind would publish an IMSI still claiming the previous network.
                            subscriberId = RandomGenerator.generateIMSI(op.numeric),
                            simSerialNumber = RandomGenerator.generateICCID(op),
                            phoneNumber = RandomGenerator.generatePhoneNumber(op)
                        )
                    },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    colors = ButtonDefaults.filledTonalButtonColors()
                ) {
                    Icon(Icons.Default.Casino, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.spoof_action_roll_network))
                }
                AdvancedField(stringResource(R.string.spoof_field_operator_name), profile.operatorAlpha, { profile = profile.copy(operatorAlpha = it) }) {
                    val op = RandomGenerator.randomOperator()
                    profile = profile.copy(
                        operatorNumeric = op.numeric,
                        simOperatorAlpha = op.alpha,
                        simOperatorNumeric = op.numeric,
                        simCountryIso = op.countryIso,
                        subscriberId = RandomGenerator.generateIMSI(op.numeric),
                        simSerialNumber = RandomGenerator.generateICCID(op),
                        phoneNumber = RandomGenerator.generatePhoneNumber(op)
                    )
                    op.alpha
                }
                EditorField(stringResource(R.string.spoof_field_operator_numeric), profile.operatorNumeric, numeric = true) { profile = profile.copy(operatorNumeric = it) }
                EditorField(stringResource(R.string.spoof_field_sim_operator_name), profile.simOperatorAlpha) { profile = profile.copy(simOperatorAlpha = it) }
                EditorField(stringResource(R.string.spoof_field_sim_operator_numeric), profile.simOperatorNumeric, numeric = true) { profile = profile.copy(simOperatorNumeric = it) }
                EditorField(stringResource(R.string.spoof_field_sim_country), profile.simCountryIso) { profile = profile.copy(simCountryIso = it) }
                AdvancedField(stringResource(R.string.spoof_field_timezone), profile.timezone, { profile = profile.copy(timezone = it) }) { RandomGenerator.randomTimezone() }
                AdvancedField(stringResource(R.string.spoof_field_locale), profile.locale, { profile = profile.copy(locale = it) }) { RandomGenerator.randomLocale() }
            }

            EditorGroup(title = stringResource(R.string.spoof_group_ids)) {
                CatsmokerButton(
                    onClick = {
                        // Mirror renderConfig's precedence so the SIM identity matches whichever
                        // operator the rendered profile actually publishes.
                        val simNumeric = profile.publishedOperatorNumeric()
                        val op = RandomGenerator.operatorForNumeric(simNumeric)
                        profile = profile.copy(
                            androidId = RandomGenerator.generateAndroidId(),
                            imei = RandomGenerator.generateIMEI(),
                            meid = RandomGenerator.generateMEID(),
                            subscriberId = RandomGenerator.generateIMSI(simNumeric),
                            simSerialNumber = RandomGenerator.generateICCID(op),
                            phoneNumber = RandomGenerator.generatePhoneNumber(op),
                            gaid = RandomGenerator.generateGAID(),
                            gsfId = RandomGenerator.generateGSFId(),
                            mediaDrmId = RandomGenerator.generateMediaDrmId(),
                            appSetId = RandomGenerator.generateAppSetId(),
                            serialNumber = RandomGenerator.generateAndroidId().take(12).uppercase()
                        )
                    },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    colors = ButtonDefaults.filledTonalButtonColors()
                ) {
                    Icon(Icons.Default.Casino, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.spoof_action_roll_ids))
                }
                AdvancedField(stringResource(R.string.spoof_field_android_id), profile.androidId, { profile = profile.copy(androidId = it) }) { RandomGenerator.generateAndroidId() }
                AdvancedField(stringResource(R.string.spoof_field_imei), profile.imei, { profile = profile.copy(imei = it) }) { RandomGenerator.generateIMEI() }
                AdvancedField(stringResource(R.string.spoof_field_meid), profile.meid, { profile = profile.copy(meid = it) }) { RandomGenerator.generateMEID() }
                AdvancedField(stringResource(R.string.spoof_field_imsi), profile.subscriberId, { profile = profile.copy(subscriberId = it) }) {
                    RandomGenerator.generateIMSI(profile.publishedOperatorNumeric())
                }
                AdvancedField(stringResource(R.string.spoof_field_iccid), profile.simSerialNumber, { profile = profile.copy(simSerialNumber = it) }) {
                    RandomGenerator.generateICCID(profile.resolvedOperator())
                }
                AdvancedField(stringResource(R.string.spoof_field_phone), profile.phoneNumber, { profile = profile.copy(phoneNumber = it) }) {
                    RandomGenerator.generatePhoneNumber(profile.resolvedOperator())
                }
                AdvancedField(stringResource(R.string.spoof_field_gaid), profile.gaid, { profile = profile.copy(gaid = it) }) { RandomGenerator.generateGAID() }
                AdvancedField(stringResource(R.string.spoof_field_gsf), profile.gsfId, { profile = profile.copy(gsfId = it) }) { RandomGenerator.generateGSFId() }
                AdvancedField(stringResource(R.string.spoof_field_drm), profile.mediaDrmId, { profile = profile.copy(mediaDrmId = it) }) { RandomGenerator.generateMediaDrmId() }
                AdvancedField(stringResource(R.string.spoof_field_appset), profile.appSetId, { profile = profile.copy(appSetId = it) }) { RandomGenerator.generateAppSetId() }
                AdvancedField(stringResource(R.string.spoof_field_serial), profile.serialNumber, { profile = profile.copy(serialNumber = it) }) { RandomGenerator.generateAndroidId().take(12).uppercase() }
            }
        }
    }
}

@Composable
fun EditorGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
        SectionCard(content = content)
    }
}

@Composable
fun EditorField(label: String, value: String, numeric: Boolean = false, multiline: Boolean = false, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        singleLine = !multiline,
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text),
        placeholder = { Text(stringResource(R.string.spoof_hint_blank), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f), fontSize = 12.sp) }
    )
}

@Composable
fun AdvancedField(label: String, value: String, onValueChange: (String) -> Unit, onRandomize: () -> String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.spoof_hint_blank), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f), fontSize = 12.sp) }
        )
        IconButton(onClick = { onValueChange(onRandomize()) }) {
            Icon(Icons.Default.Casino, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}
