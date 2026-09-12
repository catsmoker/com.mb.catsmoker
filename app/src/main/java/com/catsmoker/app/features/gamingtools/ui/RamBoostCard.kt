package com.catsmoker.app.features.gamingtools.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.catsmoker.app.R
import com.catsmoker.app.shared.ui.components.SectionCard
import com.catsmoker.app.shared.ui.components.CatsmokerButton

/**
 * The RAM boost button and its result.
 *
 * This card used to carry a second "Reset OS Defaults" button beside it. That has been removed
 * entirely: turning Gaming Mode off already restores the snapshot taken before it started, which puts
 * the user's own values back rather than deleting them, so the reset button was the worse of two ways
 * to do the same job.
 */
@Composable
fun RamBoostCard(
    isBoostingRam: Boolean,
    ramResult: String?,
    onBoostRam: () -> Unit
) {
    SectionCard {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.gt_ram_title),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            CatsmokerButton(
                onClick = onBoostRam,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isBoostingRam,
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isBoostingRam) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.gt_ram_free))
                }
            }

            // The toast is transient; keeping the measured result on the card lets the user read it.
            if (ramResult != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = ramResult,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                    fontSize = 12.sp
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            CollapsibleExplainer(
                title = stringResource(R.string.gt_explainer_what),
                lines = listOf(
                    stringResource(R.string.gt_ram_what_1),
                    stringResource(R.string.gt_ram_what_2),
                    stringResource(R.string.gt_ram_what_3)
                )
            )
        }
    }
}
