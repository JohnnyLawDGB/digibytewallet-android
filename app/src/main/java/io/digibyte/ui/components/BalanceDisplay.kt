package io.digibyte.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.digibyte.ui.theme.DigiByteAccent
import io.digibyte.ui.theme.DigiDollarGreen
import io.digibyte.ui.theme.DigiDollarGreenBright

/**
 * Hero balance: the native DGB amount is the large primary figure, with the
 * chosen fiat/crypto equivalent (USD / BTC / PHP — tap to cycle) below it.
 * DigiDollar held, if any, is shown as a distinct pill beneath — it is a
 * separate dollar-denominated asset, not part of the DGB total. Both currencies
 * are named by their mark rather than in words; see the contentDescription note
 * on each, because a logo that replaces a word has to keep saying it aloud.
 *
 * @param fiatAmount  Pre-formatted equivalent string in the chosen currency, e.g. "$12.34"
 * @param dgbAmount   Pre-formatted DGB amount WITHOUT the ticker, e.g. "1,234.56789012" —
 *                    the DigiByte mark is rendered after it in place of the word "DGB"
 * @param ddAmount    Pre-formatted DigiDollar string e.g. "$50.00", or null to hide the pill
 * @param isSynced    When false, everything is dimmed (last-known snapshot)
 * @param onFiatTap   Tap handler on the equivalent line (cycles the display currency)
 */
@Composable
fun BalanceDisplay(
    fiatAmount: String,
    dgbAmount: String,
    ddAmount: String? = null,
    isSynced: Boolean = true,
    onFiatTap: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val alpha = if (isSynced) 1f else 0.4f
    val primaryColor = MaterialTheme.colorScheme.onBackground.copy(alpha = alpha)
    val equivColor = MaterialTheme.colorScheme.onBackground.copy(alpha = alpha * 0.7f)
    val accent = DigiByteAccent.copy(alpha = alpha)

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Primary: native DGB balance, with the DigiByte mark standing in for the "DGB"
        // ticker that used to be part of this string.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = dgbAmount,
                style = MaterialTheme.typography.displaySmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 40.sp
                ),
                color = primaryColor,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.width(10.dp))
            Image(
                painter = painterResource(id = io.digibyte.R.drawable.dgb_symbol),
                // The mark REPLACES a word. Without this a screen reader would read the
                // figure and drop the unit entirely, which is the one thing the ticker
                // was there to say.
                contentDescription = "DigiByte",
                // Dimmed with everything else when showing a last-known snapshot; an
                // undimmed logo beside a faded balance reads as though it were live.
                modifier = Modifier
                    .size(44.dp)
                    .alpha(alpha)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Secondary: chosen equivalent (tap to cycle USD / BTC / PHP)
        Text(
            text = "≈ $fiatAmount",
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Medium,
                fontSize = 18.sp
            ),
            color = equivColor,
            textAlign = TextAlign.Center,
            modifier = if (onFiatTap != null) Modifier.clickable { onFiatTap() } else Modifier
        )

        // DigiDollar held — a distinct pill, separate from the DGB total
        if (ddAmount != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                shape = RoundedCornerShape(percent = 50),
                // Tinted to DigiDollar's own green, not the DGB accent. The pill's job is to
                // say "separate asset"; a blue container holding a green mark said the reverse.
                color = DigiDollarGreen.copy(alpha = alpha * 0.22f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        painter = painterResource(id = io.digibyte.R.drawable.dd_symbol),
                        // Replaces the word "DigiDollar". Without this a screen reader would
                        // announce a bare dollar figure with nothing to say which currency it
                        // is — and this pill sits directly beneath a DGB balance, so "which
                        // currency" is the entire point of the label.
                        contentDescription = "DigiDollar",
                        // Sized to the 13sp label it replaced, not to the hero's figure: this
                        // is a name for the amount beside it, and a mark that outweighed the
                        // number would invert that.
                        modifier = Modifier
                            .size(29.dp)
                            .alpha(alpha)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = ddAmount,
                        // Two-thirds of the hero's 40sp. The mark beside it is two-thirds of the
                        // hero's 30dp, so the pill is a true scaled copy of the DGB section
                        // rather than a differently-proportioned one: both keep the same 0.75
                        // mark-to-text ratio.
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 27.sp
                        ),
                        color = DigiDollarGreenBright.copy(alpha = alpha)
                    )
                }
            }
        }
    }
}
