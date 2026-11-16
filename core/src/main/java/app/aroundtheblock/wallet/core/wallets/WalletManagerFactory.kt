package app.aroundtheblock.wallet.core.wallets

import app.aroundtheblock.wallet.core.WalletManager

/** Builds a [WalletManager] over one wallet's scope and Keystore aliases (not the process's active one). */
fun interface WalletManagerFactory {
    fun forScope(scope: WalletScope): WalletManager
}
