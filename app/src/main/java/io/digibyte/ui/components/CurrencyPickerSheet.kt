package io.digibyte.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.digibyte.ui.theme.DigiByteAccent
import io.digibyte.ui.wallet.WalletViewModel.DisplayCurrency

/**
 * Picks the currency the hero balance is quoted in.
 *
 * Replaces a tap that cycled USD → BTC → PHP. Cycling is a fine gesture for three options and a
 * bad one for nineteen: it took up to eighteen taps to return to where you started, and the
 * available choices were invisible until you had tapped through them all.
 *
 * The list is deliberately ordered by how many DigiByte users are likely to want each rather
 * than alphabetically — someone looking for rupees should not have to scroll past the rand to
 * find them. [DisplayCurrency]'s declaration order is that order, so there is one place to
 * change it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CurrencyPickerSheet(
    selected: DisplayCurrency,
    onSelect: (DisplayCurrency) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Text(
            text = "Display currency",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 4.dp),
        )
        Text(
            text = "Your DigiByte balance is always the real one — this only changes what it is compared against.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
        )
        HorizontalDivider()

        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(DisplayCurrency.entries) { currency ->
                val isSelected = currency == selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(currency) }
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = currency.label,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = currency.name,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                    // Ticked rather than only bolded: weight alone is easy to miss, and this is
                    // the one row the reader is looking for.
                    Spacer(modifier = Modifier.width(12.dp))
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Selected",
                            tint = DigiByteAccent,
                            modifier = Modifier.size(20.dp),
                        )
                    } else {
                        Spacer(modifier = Modifier.size(20.dp))
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}
