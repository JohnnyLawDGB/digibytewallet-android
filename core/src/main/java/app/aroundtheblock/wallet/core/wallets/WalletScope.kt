package app.aroundtheblock.wallet.core.wallets

import java.security.SecureRandom

/** Wallet identifiers. `legacy` is the wallet every pre-multi-wallet install already has. */
object WalletIds {
    const val LEGACY = "legacy"
    private val HEX16 = Regex("^[0-9a-f]{16}$")

    fun isValid(id: String): Boolean = id == LEGACY || HEX16.matches(id)

    /** 8 random bytes as lowercase hex. */
    fun newId(random: SecureRandom): String {
        val bytes = ByteArray(8).also { random.nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

/** One wallet on one network. Only a valid id is accepted, so a name built from it cannot escape. */
data class WalletScope(val walletId: String, val testnet: Boolean) {
    init {
        require(WalletIds.isValid(walletId)) { "invalid wallet id" }
    }

    val isLegacy: Boolean get() = walletId == WalletIds.LEGACY

    companion object {
        fun legacy(testnet: Boolean): WalletScope = WalletScope(WalletIds.LEGACY, testnet)
    }
}

/** How a store's name varies. */
enum class StoreScope {
    /** Shared by every wallet: "" | "_testnet". */
    NETWORK,

    /** Legacy: "" | "_testnet"; other: "_<id>" | "_<id>_testnet". */
    WALLET_AND_NETWORK,

    /** Legacy: ""; other: "_<id>" (stores that 4.1.4 never network-suffixed). */
    WALLET,
}

/** The ONLY place a store suffix is built. */
fun storageSuffix(scope: WalletScope, store: StoreScope): String {
    val net = if (scope.testnet) "_testnet" else ""
    val wallet = if (scope.isLegacy) "" else "_${scope.walletId}"
    return when (store) {
        StoreScope.NETWORK -> net
        StoreScope.WALLET_AND_NETWORK -> wallet + net
        StoreScope.WALLET -> wallet
    }
}
