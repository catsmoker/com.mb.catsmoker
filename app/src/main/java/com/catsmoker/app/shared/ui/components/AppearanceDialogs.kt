package com.catsmoker.app.shared.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.catsmoker.app.R
import com.catsmoker.app.system.config.AppearanceStore

/**
 * Shared appearance pickers: the first-run welcome dialog and the Settings rows use the
 * same option rows, so a language/theme choice looks identical in both places.
 *
 * Language endonyms live here (not in resources): a picker must name each language in
 * that language itself, whatever the current UI language is.
 */
@Composable
fun SelectableOptionRow(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@Composable
fun ThemeModeOptions(
    selected: AppearanceStore.ThemeMode,
    onSelect: (AppearanceStore.ThemeMode) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SelectableOptionRow(
            selected = selected == AppearanceStore.ThemeMode.SYSTEM,
            label = stringResource(R.string.sys_theme_system),
            onClick = { onSelect(AppearanceStore.ThemeMode.SYSTEM) }
        )
        SelectableOptionRow(
            selected = selected == AppearanceStore.ThemeMode.DARK,
            label = stringResource(R.string.sys_theme_dark),
            onClick = { onSelect(AppearanceStore.ThemeMode.DARK) }
        )
        SelectableOptionRow(
            selected = selected == AppearanceStore.ThemeMode.LIGHT,
            label = stringResource(R.string.sys_theme_light),
            onClick = { onSelect(AppearanceStore.ThemeMode.LIGHT) }
        )
    }
}

/** Native name per language tag — never translated, by definition. */
fun languageEndonym(tag: String): String = when (tag) {
    AppearanceStore.LANGUAGE_ARABIC -> "العربية"
    AppearanceStore.LANGUAGE_SPANISH -> "Español"
    AppearanceStore.LANGUAGE_CHINESE -> "中文 (简体)"
    AppearanceStore.LANGUAGE_ENGLISH -> "English"
    else -> ""
}

@Composable
fun languageDisplayName(tag: String): String {
    val endonym = languageEndonym(tag)
    return endonym.ifEmpty { stringResource(R.string.sys_lang_system) }
}

@Composable
fun LanguageOptions(selectedTag: String, onSelect: (String) -> Unit) {
    val options = listOf(
        AppearanceStore.LANGUAGE_SYSTEM,
        AppearanceStore.LANGUAGE_ENGLISH,
        AppearanceStore.LANGUAGE_ARABIC,
        AppearanceStore.LANGUAGE_SPANISH,
        AppearanceStore.LANGUAGE_CHINESE
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { tag ->
            SelectableOptionRow(
                selected = selectedTag == tag,
                label = languageDisplayName(tag),
                onClick = { onSelect(tag) }
            )
        }
    }
}
