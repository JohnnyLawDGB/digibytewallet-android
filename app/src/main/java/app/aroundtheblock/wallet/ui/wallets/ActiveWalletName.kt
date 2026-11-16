package app.aroundtheblock.wallet.ui.wallets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import app.aroundtheblock.wallet.R
import app.aroundtheblock.wallet.core.wallets.ActiveWallet
import app.aroundtheblock.wallet.ui.locale.LocaleController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The name the running wallet is shown by (its own, or the localised "Wallet <number>"), or null
 * when the registry has no entry for it. Reads the registry's prefs: call off the main thread.
 */
internal fun activeWalletShownName(context: Context): String? {
    val scope = ActiveWallet.scope(context)
    val entry = ActiveWallet.registry(context).find(scope.walletId) ?: return null
    return shownName(entry) { n -> LocaleController.wrap(context).getString(R.string.wallet_default_name, n) }
}

/** [activeWalletShownName] for a screen: null until read, and when there is nothing to show. */
@Composable
internal fun rememberActiveWalletName(): String? {
    val context = LocalContext.current
    val name by produceState<String?>(initialValue = null, context) {
        value = withContext(Dispatchers.IO) { runCatching { activeWalletShownName(context) }.getOrNull() }
    }
    return name
}
