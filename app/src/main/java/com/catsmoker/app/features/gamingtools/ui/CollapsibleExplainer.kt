package com.catsmoker.app.features.gamingtools.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.catsmoker.app.R

/**
 * One collapsed-by-default explanation, used for every "what is this?" text in Gaming Tools.
 *
 * The whole header is the tap target, not just the arrow, so it works the way a phone user expects a
 * dropdown to. Nothing is shown until it is opened, which is what keeps a card with five explanations
 * on it readable.
 *
 * The [title] should be a plain question the user might actually ask — "What is this?", "Do I need
 * root?" — and [lines] should answer it in short, ordinary sentences. Technical names belong in the
 * body with a plain-words gloss next to them, never in the title.
 *
 * @param title the always-visible question.
 * @param lines the answer, one paragraph per entry, revealed on tap.
 * @param accent the colour of the title and the tint of the background. Amber and red are used for
 *   "this has a cost" and "this will not work here".
 * @param initiallyExpanded left false everywhere by design. It exists for the rare case where the
 *   text *is* the feature's only content, not decoration on it.
 */
@Composable
fun CollapsibleExplainer(
    title: String,
    lines: List<String>,
    accent: Color = Color(0xFF64B5F6),
    initiallyExpanded: Boolean = false
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(accent.copy(alpha = 0.08f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = accent,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) stringResource(R.string.gt_explainer_hide) else stringResource(R.string.gt_explainer_show),
                tint = accent,
                modifier = Modifier.size(18.dp)
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                lines.forEach { line ->
                    Text(line, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f), lineHeight = 16.sp)
                }
            }
        }
    }
}
