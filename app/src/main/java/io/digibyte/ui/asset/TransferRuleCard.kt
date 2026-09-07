package io.digibyte.ui.asset

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.digibyte.R
import io.digibyte.core.asset.rules.RuleCheckState

/**
 * Tells the user why an asset cannot be sent. Rendered on the detail and send screens whenever
 * the transfer-rule check is anything but NONE, which is the only state that enables Send.
 *
 * A rule-bearing asset moved by a plain transfer is cleared by every DigiAsset Core indexer:
 * the whole input holding is destroyed while the wallet reports success. So the card says three
 * things in this order: what is true, what is blocked, and that the asset is safe.
 */
@Composable
fun TransferRuleCard(
    state: RuleCheckState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state == RuleCheckState.NONE) return
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            when (state) {
                RuleCheckState.CHECKING -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.as_rules_checking),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                RuleCheckState.RULE_BOUND -> {
                    Text(
                        text = stringResource(R.string.as_rules_bound_title),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.as_rules_bound_body),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                RuleCheckState.UNVERIFIED -> {
                    Text(
                        text = stringResource(R.string.as_rules_unverified_body),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = onRetry) {
                        Text(stringResource(R.string.as_rules_retry))
                    }
                }
                RuleCheckState.NONE -> Unit
            }
        }
    }
}
