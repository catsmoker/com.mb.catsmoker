package com.catsmoker.app.features.spoofdevice

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.catsmoker.app.R
import com.catsmoker.app.shared.ui.components.ScreenScaffold
import com.catsmoker.app.shared.ui.components.SectionCard

@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    ScreenScaffold(
        title = stringResource(R.string.spoof_menu_diag),
        subtitle = stringResource(R.string.spoof_diag_subtitle),
        onBack = onBack
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            DiagGroup(title = stringResource(R.string.spoof_diag_hardware)) {
                DiagField(stringResource(R.string.spoof_field_brand), Build.BRAND)
                DiagField(stringResource(R.string.spoof_field_manufacturer), Build.MANUFACTURER)
                DiagField(stringResource(R.string.spoof_field_model), Build.MODEL)
                DiagField(stringResource(R.string.spoof_diag_product), Build.PRODUCT)
                DiagField(stringResource(R.string.spoof_diag_device), Build.DEVICE)
                DiagField(stringResource(R.string.spoof_field_board), Build.BOARD)
                DiagField(stringResource(R.string.spoof_field_hardware), Build.HARDWARE)
            }

            DiagGroup(title = stringResource(R.string.spoof_diag_software)) {
                DiagField(stringResource(R.string.spoof_diag_release), Build.VERSION.RELEASE)
                DiagField(stringResource(R.string.spoof_field_sdk), Build.VERSION.SDK_INT.toString())
                DiagField(stringResource(R.string.spoof_field_build_id), Build.ID)
                DiagField(stringResource(R.string.spoof_field_incremental), Build.VERSION.INCREMENTAL)
                DiagField(stringResource(R.string.spoof_field_fingerprint), Build.FINGERPRINT)
            }
            
            DiagGroup(title = stringResource(R.string.spoof_diag_system)) {
                DiagField(stringResource(R.string.spoof_field_bootloader), Build.BOOTLOADER)
                DiagField(stringResource(R.string.spoof_diag_radio), Build.getRadioVersion() ?: stringResource(R.string.spoof_diag_unknown))
                DiagField(stringResource(R.string.spoof_diag_tags), Build.TAGS)
                DiagField(stringResource(R.string.spoof_diag_type), Build.TYPE)
            }
        }
    }
}

@Composable
fun DiagGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
        SectionCard(content = content)
    }
}

@Composable
fun DiagField(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        Text(value, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}
