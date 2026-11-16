package app.aroundtheblock.wallet.ui.wallets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.aroundtheblock.wallet.R
import app.aroundtheblock.wallet.WalletSwitcher
import app.aroundtheblock.wallet.core.settings.maskedAmount
import app.aroundtheblock.wallet.core.wallets.WalletRegistry
import app.aroundtheblock.wallet.ui.theme.DigiByteAccent
import app.aroundtheblock.wallet.ui.wallet.WalletViewModel

/**
 * Every wallet on this network with its last-known DGB balance (and DigiDollar balance when it
 * holds any), then Add wallet and Manage. Tapping another wallet restarts the app into it.
 * [hidden] masks the balances as the hero card does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletSwitcherSheet(
    rows: List<WalletRow>,
    canAdd: Boolean,
    hidden: Boolean,
    onSwitch: (String) -> WalletSwitcher.Result,
    onAdd: () -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var switchFailed by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Text(
            text = stringResource(R.string.wallet_switcher_title),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
        )
        HorizontalDivider()

        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
            items(rows, key = { it.id }) { row ->
                WalletRowItem(row, hidden) {
                    switchFailed = false
                    when (onSwitch(row.id)) {
                        WalletSwitcher.Result.ALREADY_ACTIVE -> onDismiss()
                        WalletSwitcher.Result.RESTARTING -> Unit
                        else -> switchFailed = true
                    }
                }
            }
        }

        // DigiStamp signs in through the external browser, whose session the app cannot end: a
        // switch leaves it signed in as the wallet being left.
        Text(
            text = stringResource(R.string.wallet_switcher_digistamp_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )

        if (switchFailed) {
            Text(
                text = stringResource(R.string.wallet_switcher_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
        HorizontalDivider()

        if (canAdd) {
            SheetAction(Icons.Default.Add, stringResource(R.string.wallet_switcher_add), onAdd)
        } else {
            Text(
                text = stringResource(R.string.wallet_add_cap, WalletRegistry.MAX_PER_NETWORK),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
        }
        SheetAction(Icons.Default.Settings, stringResource(R.string.wallet_switcher_manage), onManage)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun WalletRowItem(row: WalletRow, hidden: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.shownName,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = if (row.active) FontWeight.Bold else FontWeight.Normal,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            WalletBalanceLine(row, hidden)
            if (row.alsoTestnet) {
                Text(
                    text = stringResource(R.string.wallet_switcher_both_networks),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
        }
        if (row.active) {
            Icon(
                Icons.Default.Check,
                contentDescription = stringResource(R.string.wallet_switcher_active),
                tint = DigiByteAccent,
            )
        }
    }
}

/** The row's last-known balance: DGB, plus DigiDollar when it holds any; "Not synced yet" before its first sync. */
@Composable
internal fun WalletBalanceLine(row: WalletRow, hidden: Boolean) {
    val sat = row.lastBalanceSat
    val text = if (sat == null) {
        stringResource(R.string.wallet_switcher_not_synced)
    } else {
        val dgb = stringResource(R.string.wallet_switcher_balance_dgb, maskedAmount(hidden, WalletViewModel.formatSatoshisBare(sat)))
        val cents = row.lastDdCents
        if (cents != null && cents != 0L) {
            stringResource(
                R.string.wallet_switcher_balance_with_dd,
                dgb,
                maskedAmount(hidden, WalletViewModel.formatDigiDollar(cents)),
            )
        } else {
            dgb
        }
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
    )
}

@Composable
private fun SheetAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = DigiByteAccent)
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}
