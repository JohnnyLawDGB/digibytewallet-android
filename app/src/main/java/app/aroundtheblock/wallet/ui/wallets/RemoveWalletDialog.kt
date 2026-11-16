package app.aroundtheblock.wallet.ui.wallets

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import app.aroundtheblock.wallet.R

/**
 * Removing one wallet, in two steps: the back-up-your-phrase warning, then typing the wallet's
 * name. Confirm stays disabled until the text is the wallet's shown name. The credential check
 * and the removal itself are the caller's ([onConfirm]); while [busy] the dialog cannot be left.
 */
@Composable
fun RemoveWalletDialog(
    row: WalletRow,
    /** The last wallet on the device: removing it erases the app. */
    isOnlyWallet: Boolean,
    busy: Boolean,
    onConfirm: (typedName: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var warned by remember(row.id) { mutableStateOf(false) }
    var typed by remember(row.id) { mutableStateOf("") }
    val matches = typed.trim().isNotEmpty() && typed.trim() == row.shownName.trim()

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !busy, dismissOnClickOutside = !busy),
        title = { Text(stringResource(R.string.wallet_remove_title, row.shownName)) },
        text = {
            Column {
                if (!warned) {
                    Text(stringResource(R.string.wallet_remove_backup_warning))
                    if (row.alsoTestnet) {
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.wallet_remove_both_networks))
                    }
                    if (isOnlyWallet) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.wallet_remove_last),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                } else if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.wallet_remove_removing))
                    }
                } else {
                    Text(stringResource(R.string.wallet_remove_type_prompt, row.shownName))
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.wallet_remove_name_label)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            if (!warned) {
                TextButton(onClick = { warned = true }) { Text(stringResource(R.string.wallet_remove_continue)) }
            } else {
                TextButton(
                    onClick = { onConfirm(typed) },
                    enabled = matches && !busy,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.wallet_remove_confirm)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
