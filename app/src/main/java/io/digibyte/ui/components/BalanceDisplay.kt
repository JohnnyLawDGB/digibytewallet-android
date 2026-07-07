package io.digibyte.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.digibyte.ui.theme.DigiByteAccent

/**
 * Large fiat amount + DGB amount below. Styled per the wallet mockup.
 *
 * @param fiatAmount  Pre-formatted fiat string, e.g. "$12.34"
 * @param dgbAmount   Pre-formatted DGB string, e.g. "1,234.56789012 DGB"
 * @param ddAmount    Pre-formatted DigiDollar string e.g. "$50.00", or null to hide the line
 * @param isSynced    When false, balance is greyed out (last-known snapshot)
 */
@Composable
fun BalanceDisplay(
    fiatAmount: String,
    dgbAmount: String,
    ddAmount: String? = null,
    isSynced: Boolean = true,
    onFiatTap: (() -> Unit)? = null,
    onDdTap: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val fiatColor = if (isSynced)
        MaterialTheme.colorScheme.onBackground
    else
        MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)

    val dgbColor = if (isSynced)
        DigiByteAccent
    else
        DigiByteAccent.copy(alpha = 0.4f)

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = fiatAmount,
            style = MaterialTheme.typography.displaySmall.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 42.sp
            ),
            color = fiatColor,
            textAlign = TextAlign.Center,
            modifier = if (onFiatTap != null) Modifier.clickable { onFiatTap() } else Modifier
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = dgbAmount,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Medium,
                fontSize = 18.sp
            ),
            color = dgbColor,
            textAlign = TextAlign.Center
        )
        if (ddAmount != null) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = ddAmount,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp
                ),
                color = dgbColor,   // reuse the accent (dimmed when unsynced)
                textAlign = TextAlign.Center,
                modifier = if (onDdTap != null) Modifier.clickable { onDdTap() } else Modifier
            )
        }
    }
}
