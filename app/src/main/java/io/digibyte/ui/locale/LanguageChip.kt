package io.digibyte.ui.locale

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.digibyte.core.locale.LanguageChipLabel
import java.util.Locale

/**
 * A compact language control, for screens that have no settings menu to send someone to.
 *
 * ## Why onboarding needs its own
 *
 * The language picker used to live only in Settings, which is behind onboarding: create a wallet,
 * write down a recovery phrase, set a PIN. Someone who reads no English had to complete the most
 * consequential sequence in the app — in English — before reaching the control that would have
 * let them read it. The screens where a misunderstanding costs money were exactly the screens
 * with no way to change the language.
 *
 * So the chip sits on the first screen, before any decision is made.
 *
 * The label is the current language's own name rather than the word "Language"; see
 * [LanguageChipLabel] for why that distinction is the whole point.
 */
@Composable
fun LanguageChip(
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
) {
    val context = LocalContext.current
    var showPicker by remember { mutableStateOf(false) }
    val current = remember { LocaleController.current(context) }

    // Read from the composition rather than Locale.getDefault(): the base context is already
    // wrapped by LocaleController, so this reflects what the user is actually looking at.
    val configuration = LocalConfiguration.current
    val deviceLocale: Locale = remember(configuration) {
        @Suppress("DEPRECATION")
        runCatching { configuration.locales[0] }.getOrNull() ?: Locale.getDefault()
    }

    if (showPicker) {
        LanguagePickerSheet(
            selected = current,
            onSelect = { entry ->
                showPicker = false
                LocaleController.apply(context, entry)
            },
            onDismiss = { showPicker = false },
        )
    }

    Surface(
        modifier = modifier.clickable { showPicker = true },
        shape = RoundedCornerShape(20.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, tint.copy(alpha = 0.25f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Language,
                // The globe carries the meaning for anyone who cannot read the label — which is
                // the person this control exists for.
                contentDescription = "Change language",
                tint = tint.copy(alpha = 0.75f),
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = LanguageChipLabel.forChoice(current, deviceLocale),
                style = MaterialTheme.typography.labelLarge,
                color = tint.copy(alpha = 0.85f),
            )
        }
    }
}
