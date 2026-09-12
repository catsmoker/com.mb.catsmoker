package com.catsmoker.app.features.gamingtools.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.catsmoker.app.R
import com.catsmoker.app.features.gamingtools.engine.GamingModeReport
import com.catsmoker.app.features.gamingtools.engine.GamingModeState
import com.catsmoker.app.shared.ui.components.SectionCard

/**
 * The Gaming Mode card.
 *
 * @param canActivate whether root or Shizuku is available. Every optimization Gaming Mode applies is a
 *   privileged command, so without one of those channels the whole feature can do nothing at all. The
 *   power button is disabled and the reason is stated on the card rather than letting the user press a
 *   live button and watch it fail. It goes live on its own as soon as either channel appears, because
 *   the state comes from the privilege flow the screen re-reads on every sync.
 */
@Composable
fun GamingModeCard(
    gamingState: GamingModeState,
    report: GamingModeReport,
    animatedProgress: Float,
    canActivate: Boolean,
    isActive: Boolean,
    isBusy: Boolean,
    onActivate: () -> Unit,
    onDeactivate: () -> Unit
) {
    SectionCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.gt_gm_title),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 1.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = when (gamingState) {
                            is GamingModeState.Active -> stringResource(R.string.gt_gm_active)
                            // The engine already reports which step it is on, so show that rather
                            // than a generic word.
                            is GamingModeState.Enabling -> gamingState.statusText
                            is GamingModeState.Disabling -> stringResource(R.string.gt_gm_reverting)
                            is GamingModeState.Error -> stringResource(R.string.gt_gm_failed)
                            is GamingModeState.Idle ->
                                if (canActivate) stringResource(R.string.gt_gm_ready) else stringResource(R.string.gt_gm_locked)
                        },
                        style = MaterialTheme.typography.titleLarge,
                        color = when {
                            gamingState is GamingModeState.Error -> MaterialTheme.colorScheme.error
                            // Item 8: a feature that cannot run must look like it cannot run.
                            !canActivate -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                    )
                }

                IconButton(
                    onClick = { if (isActive) onDeactivate() else onActivate() },
                    enabled = !isBusy && canActivate,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(
                            if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .border(
                            1.dp,
                            if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                            else MaterialTheme.colorScheme.outlineVariant,
                            CircleShape
                        )
                ) {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = null,
                        tint = when {
                            isActive -> MaterialTheme.colorScheme.primary
                            !canActivate -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            // Item 2: the requirement is stated on the card, above everything else, so the greyed-out
            // button is never a mystery. It disappears by itself the moment either channel appears.
            if (!canActivate) {
                Spacer(modifier = Modifier.height(16.dp))
                NoticeBlock(
                    text = stringResource(R.string.gt_gm_need),
                    tint = Color(0xFFFFB300)
                )
                Spacer(modifier = Modifier.height(8.dp))
                CollapsibleExplainer(
                    title = stringResource(R.string.gt_gm_what_title),
                    lines = listOf(
                        stringResource(R.string.gt_gm_what_1),
                        stringResource(R.string.gt_gm_what_2),
                        stringResource(R.string.gt_gm_what_3)
                    )
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Animated Status Bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animatedProgress)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                    MaterialTheme.colorScheme.primary
                                )
                            )
                        )
                )
            }

            if (gamingState is GamingModeState.Error) {
                Spacer(modifier = Modifier.height(16.dp))
                NoticeBlock(text = gamingState.message, tint = MaterialTheme.colorScheme.error)
            }

            if (isActive) {
                Spacer(modifier = Modifier.height(24.dp))
                // Every row below is a value the engine read back from the device after writing it,
                // so a change the ROM refused shows as refused instead of as a success.
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    GamingModeResultRow(
                        label = "PowerHAL",
                        value = if (report.fixedPerformance) stringResource(R.string.gt_gm_fixed) else stringResource(R.string.gt_gm_not_applied),
                        applied = report.fixedPerformance
                    )
                    // Null means the device carries no vendor GPU mode property at all — every
                    // non-Qualcomm SoC — so the row is omitted rather than shown as refused, the
                    // same rule as the game frame cap row below.
                    report.gpuPerformanceMode?.let { applied ->
                        GamingModeResultRow(
                            label = stringResource(R.string.gt_gm_label_gpu),
                            value = if (applied) stringResource(R.string.gt_gm_qc_perf) else stringResource(R.string.gt_gm_not_applied),
                            applied = applied
                        )
                    }
                    // Same null rule — omitted on non-Qualcomm silicon. The value shown is the
                    // measured panel peak the hint carries, read back from the property.
                    report.qtiGameFps?.let { applied ->
                        GamingModeResultRow(
                            label = stringResource(R.string.gt_gm_label_fps_hint),
                            value = if (applied) stringResource(R.string.gt_gm_qc_peak) else stringResource(R.string.gt_gm_not_applied),
                            applied = applied
                        )
                    }
                    GamingModeResultRow(
                        label = stringResource(R.string.gt_gm_label_display),
                        value = report.lockedRefreshHz?.let { stringResource(R.string.gt_gm_locked_hz, it) } ?: stringResource(R.string.gt_gm_rate_unlocked),
                        applied = report.lockedRefreshHz != null
                    )
                    GamingModeResultRow(
                        label = stringResource(R.string.gt_gm_label_touch),
                        value = if (report.touchResponseBoost) stringResource(R.string.gt_gm_touch_boost) else stringResource(R.string.gt_gm_touch_na),
                        applied = report.touchResponseBoost
                    )
                    GamingModeResultRow(
                        label = stringResource(R.string.gt_gm_label_bg_apps),
                        value = when {
                            report.suspendedPackages > 0 && report.suspendFailures > 0 ->
                                stringResource(R.string.gt_gm_susp_both, report.suspendedPackages, report.suspendFailures)
                            report.suspendedPackages > 0 -> stringResource(R.string.gt_gm_susp_some, report.suspendedPackages)
                            report.suspendFailures > 0 -> stringResource(R.string.gt_gm_susp_refused, report.suspendFailures)
                            else -> stringResource(R.string.gt_gm_susp_none)
                        },
                        applied = report.suspendedPackages > 0
                    )
                    GamingModeResultRow(
                        label = stringResource(R.string.gt_gm_label_dnd),
                        value = if (report.dndEngaged) stringResource(R.string.gt_gm_dnd_on) else stringResource(R.string.gt_gm_dnd_off),
                        applied = report.dndEngaged
                    )
                    // Null means notification access was never granted, so the second layer was
                    // never switchable on this run — omitted rather than shown as refused, the
                    // same rule as the background-data row below.
                    report.notificationSuppression?.let {
                        GamingModeResultRow(
                            label = stringResource(R.string.gt_gm_label_notif),
                            value = if (it) stringResource(R.string.gt_gm_notif_on) else stringResource(R.string.gt_gm_notif_off),
                            applied = it
                        )
                    }
                    report.networkWhitelisted?.let { whitelisted ->
                        GamingModeResultRow(
                            label = stringResource(R.string.gt_gm_label_net),
                            value = if (whitelisted) stringResource(R.string.gt_gm_net_ok) else stringResource(R.string.gt_gm_net_no),
                            applied = whitelisted
                        )
                    }
                    // Null means no game was targeted or the device predates game interventions
                    // (Android 12) — not applicable, so the row is omitted rather than shown as
                    // refused, exactly like the background-data row above.
                    report.gameInterventionApplied?.let { applied ->
                        GamingModeResultRow(
                            label = stringResource(R.string.gt_gm_label_cap),
                            value = if (applied) stringResource(R.string.gt_gm_cap_raised) else stringResource(R.string.gt_gm_cap_no),
                            applied = applied
                        )
                    }
                    // Reported from the read-back of always_finish_activities, so "Applied" means the
                    // setting holds 1 right now rather than that the command was sent.
                    GamingModeResultRow(
                        label = stringResource(R.string.gt_gm_label_discard),
                        value = if (report.discardActivities) stringResource(R.string.gt_gm_discard_on) else stringResource(R.string.gt_gm_not_applied),
                        applied = report.discardActivities
                    )
                    GamingModeResultRow(
                        label = stringResource(R.string.gt_gm_label_limit),
                        value = if (report.processLimit) stringResource(R.string.gt_gm_limit) else stringResource(R.string.gt_gm_not_applied),
                        applied = report.processLimit
                    )
                    GamingModeResultRow(
                        label = stringResource(R.string.gt_gm_label_other_data),
                        value = when (report.backgroundDataRestricted) {
                            // null means the user's own switch was already on, so this run left it
                            // alone — saying "not applied" would misreport a deliberate decision.
                            null -> stringResource(R.string.gt_gm_left_as_set)
                            true -> stringResource(R.string.gt_gm_blocked_metered)
                            false -> stringResource(R.string.gt_gm_not_blocked)
                        },
                        applied = report.backgroundDataRestricted != false
                    )
                }

                if (report.unavailable.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    NoticeBlock(
                        text = stringResource(R.string.gt_gm_unavailable_title) + "\n" +
                            report.unavailable.joinToString("\n") { "• $it" },
                        tint = Color(0xFFFFB300)
                    )
                }
            }

            // Revert problems live in this same list, so they must not hide behind isActive:
            // deactivation reports refused wake-ups and leftover blocks here, and a half-reverted
            // device that looks clean is exactly how apps stay stopped with no explanation.
            if (!isActive && report.unavailable.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                NoticeBlock(
                    text = stringResource(R.string.gt_gm_revert_leftovers) + "\n" +
                        report.unavailable.joinToString("\n") { "• $it" },
                    tint = Color(0xFFFFB300)
                )
            }
        }
    }
}

/** Small tinted panel used for an activation error or the list of refused optimizations. */
@Composable
private fun NoticeBlock(text: String, tint: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(tint.copy(alpha = 0.10f))
            .border(1.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(text = text, color = tint, fontSize = 12.sp, lineHeight = 18.sp)
    }
}
