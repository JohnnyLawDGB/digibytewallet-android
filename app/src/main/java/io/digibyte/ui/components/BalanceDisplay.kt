package io.digibyte.ui.components

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
 */
@Composable
fun BalanceDisplay(
    fiatAmount: String,
    dgbAmount: String,
    modifier: Modifier = Modifier
) {
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
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = dgbAmount,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Medium,
                fontSize = 18.sp
            ),
            color = DigiByteAccent,
            textAlign = TextAlign.Center
        )
    }
}
