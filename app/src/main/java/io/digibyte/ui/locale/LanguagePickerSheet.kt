package io.digibyte.ui.locale

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.digibyte.core.locale.AppLocale
import io.digibyte.ui.theme.DigiByteAccent

/**
 * Chooses the wallet's language.
 *
 * Every language names ITSELF, in its own script — हिन्दी, 日本語, Tiếng Việt. A list written in
 * English is unusable by exactly the person who needs it, because someone who reads no English
 * cannot find their language in a column of English words. The English name sits underneath as a
 * secondary line, for anyone who does read it and is looking for a familiar label.
 *
 * "Follow device language" is first and is the default, so the common case needs no decision.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguagePickerSheet(
    selected: AppLocale.Entry?,
    onSelect: (AppLocale.Entry?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Text(
            text = "Language",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 4.dp),
        )
        Text(
            text = "Changing this restarts the wallet screen. Your coins, phrase and settings are untouched.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
        )
        // Said plainly rather than discovered. These translations have not been checked by native
        // speakers yet, and the strings that matter most — the ones about losing your coins — are
        // exactly where a machine translation goes wrong. Telling people gives a wrong word a
        // route back to us instead of quietly costing trust, and Settings already has the
        // reporting link to send them to.
        Text(
            text = "Translations are new and not yet checked by native speakers. If something " +
                "reads wrong — especially a warning about your recovery phrase or your coins — " +
                "please tell us via Settings → Report a bug.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
        )
        HorizontalDivider()

        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            item {
                LanguageRow(
                    primary = "Follow device language",
                    secondary = null,
                    isSelected = selected == null,
                    onClick = { onSelect(null) },
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp))
            }
            items(AppLocale.SUPPORTED) { entry ->
                LanguageRow(
                    primary = entry.endonym,
                    // Not repeated when they are the same word, which would read as a stutter.
                    secondary = entry.englishName.takeIf { it != entry.endonym },
                    isSelected = entry.tag == selected?.tag,
                    onClick = { onSelect(entry) },
                )
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun LanguageRow(
    primary: String,
    secondary: String?,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = primary,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (secondary != null) {
                Text(
                    text = secondary,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
        }
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Selected",
                tint = DigiByteAccent,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
