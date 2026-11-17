package app.aroundtheblock.wallet.ui.wallets

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aroundtheblock.wallet.R
import app.aroundtheblock.wallet.core.settings.BalancePrivacyPrefs
import app.aroundtheblock.wallet.core.wallets.RemoveResult
import app.aroundtheblock.wallet.core.wallets.WalletRegistry
import app.aroundtheblock.wallet.ui.components.rememberSpendAuth
import app.aroundtheblock.wallet.ui.theme.DigiByteNavy
import kotlinx.coroutines.launch

/**
 * Rename, reorder and remove this network's wallets. Removing asks for the backup warning, the
 * typed name and the app PIN or biometric, in that order (RemoveWalletDialog, SpendAuth).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageWalletsScreen(
    onBack: () -> Unit,
    viewModel: WalletsViewModel = hiltViewModel(),
) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val removing by viewModel.removing.collectAsStateWithLifecycle()
    val onlyWalletId by viewModel.onlyWalletId.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val hidden = remember { BalancePrivacyPrefs.isHidden(context) }
    val scope = rememberCoroutineScope()
    val spendAuth = rememberSpendAuth()
    spendAuth.Dialogs()
    val authTitle = stringResource(R.string.wallet_remove_confirm)
    val authSubtitle = stringResource(R.string.wallet_remove_auth_subtitle)

    var renameTarget by remember { mutableStateOf<WalletRow?>(null) }
    var removeTarget by remember { mutableStateOf<WalletRow?>(null) }
    var message by remember { mutableStateOf<Int?>(null) }

    // A removal runs to its end; leaving the screen mid-way is held until it has.
    BackHandler(enabled = removing) { }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.wallet_manage_title)) },
                navigationIcon = {
                    IconButton(onClick = { if (!removing) onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DigiByteNavy,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
        ) {
            message?.let { res ->
                Text(
                    text = stringResource(res),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(16.dp),
                )
                HorizontalDivider()
            }
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                itemsIndexed(rows, key = { _, r -> r.id }) { index, row ->
                    ManageRow(
                        row = row,
                        hidden = hidden,
                        canMoveUp = index > 0,
                        canMoveDown = index < rows.lastIndex,
                        enabled = !removing,
                        onRename = { renameTarget = row },
                        onMove = { delta -> viewModel.move(row.id, delta) },
                        onRemove = { message = null; removeTarget = row },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    renameTarget?.let { row ->
        RenameDialog(
            row = row,
            onSave = { name -> viewModel.rename(row.id, name).also { if (it) renameTarget = null } },
            onDismiss = { renameTarget = null },
        )
    }

    removeTarget?.let { row ->
        RemoveWalletDialog(
            row = row,
            isOnlyWallet = onlyWalletId == row.id,
            busy = removing,
            onConfirm = { typed ->
                scope.launch {
                    if (!spendAuth.authorize(context as? FragmentActivity, authTitle, authSubtitle)) return@launch
                    // RemovedRestartNeeded and WipedEverything end this process from inside remove().
                    message = when (viewModel.remove(row.id, typed)) {
                        RemoveResult.Removed -> R.string.wallet_remove_done
                        RemoveResult.RemovedRestartNeeded, is RemoveResult.WipedEverything -> null
                        RemoveResult.DatabaseKeyStillOnLegacy -> R.string.wallet_remove_db_key_kept
                        RemoveResult.Incomplete -> R.string.wallet_remove_failed
                        RemoveResult.NotRemoved, RemoveResult.NameMismatch -> R.string.wallet_remove_not_removed
                    }
                    removeTarget = null
                }
            },
            onDismiss = { removeTarget = null },
        )
    }
}

@Composable
private fun ManageRow(
    row: WalletRow,
    hidden: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    enabled: Boolean,
    onRename: () -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.shownName,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (row.active) FontWeight.Bold else FontWeight.Normal),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            WalletBalanceLine(row, hidden)
            if (row.active) {
                Text(
                    text = stringResource(R.string.wallet_switcher_active),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        IconButton(onClick = onRename, enabled = enabled) {
            Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.wallet_manage_rename))
        }
        IconButton(onClick = { onMove(-1) }, enabled = enabled && canMoveUp) {
            Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.wallet_manage_move_up))
        }
        IconButton(onClick = { onMove(+1) }, enabled = enabled && canMoveDown) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.wallet_manage_move_down))
        }
        IconButton(onClick = onRemove, enabled = enabled) {
            Icon(
                Icons.Default.Delete,
                contentDescription = stringResource(R.string.wallet_manage_remove),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun RenameDialog(row: WalletRow, onSave: (String) -> Boolean, onDismiss: () -> Unit) {
    var name by remember(row.id) { mutableStateOf(row.shownName) }
    var invalid by remember(row.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.wallet_manage_rename_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(WalletRegistry.MAX_NAME_CHARS + 8); invalid = false },
                    singleLine = true,
                    isError = invalid,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        if (invalid) R.string.wallet_manage_rename_invalid else R.string.wallet_manage_rename_hint,
                        WalletRegistry.MAX_NAME_CHARS,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (invalid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (!onSave(name)) invalid = true }) { Text(stringResource(R.string.wallet_manage_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
