package com.catsmoker.app.features.gamingtools.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One line of the gaming-mode result list.
 *
 * @param applied whether the change this row describes actually landed on the device. A row that
 *   did not land keeps the check mark off and greys the value out, so an optimization the ROM
 *   refused can never read as a success.
 */
@Composable
fun GamingModeResultRow(label: String, value: String, applied: Boolean = true) {
    val accent = if (applied) MaterialTheme.colorScheme.primary else Color(0xFF9E9E9E)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (applied) Icons.Default.Check else Icons.Default.Remove,
                    null,
                    tint = accent,
                    modifier = Modifier.size(10.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(text = label, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f), fontSize = 13.sp)
        }
        Text(
            text = value,
            color = accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
