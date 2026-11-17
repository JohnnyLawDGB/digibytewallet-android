package app.aroundtheblock.wallet.ui.wallets

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.aroundtheblock.wallet.R

/** The running wallet's name at the top of the Wallet tab. Tapping it opens [WalletSwitcherSheet]. */
@Composable
fun WalletChip(name: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.wallet_chip_cd, name)
    AssistChip(
        onClick = onClick,
        label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = {
            Icon(Icons.Default.AccountBalanceWallet, contentDescription = null, modifier = Modifier.size(18.dp))
        },
        trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = Color.White.copy(alpha = 0.12f),
            labelColor = Color.White,
            leadingIconContentColor = Color.White,
            trailingIconContentColor = Color.White,
        ),
        border = null,
        modifier = modifier.semantics { contentDescription = description },
    )
}
