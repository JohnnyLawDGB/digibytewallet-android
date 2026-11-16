package app.aroundtheblock.wallet.core.wallets

import android.content.Context
import androidx.annotation.VisibleForTesting
import app.aroundtheblock.wallet.core.isTestnet
import java.io.File

/** Which wallet this process is using. Resolved once, then fixed until the process ends. */
object ActiveWallet {
    private val lock = Any()
    private var cached: Pair<Context, WalletScope>? = null

    private const val NETWORK_KEY = "wallet_network"

    /** Resolved once per application context (by identity), then cached. */
    fun scope(context: Context): WalletScope {
        val app = context.applicationContext ?: context
        synchronized(lock) {
            cached?.let { (ctx, scope) -> if (ctx === app) return scope }
            val testnet = isTestnet(app)
            val scope = WalletScope(registry(app).activeId(testnet), testnet)
            cached = app to scope
            return scope
        }
    }

    fun registry(context: Context): WalletRegistry {
        val app = context.applicationContext ?: context
        return WalletRegistry(
            prefs = app.getSharedPreferences(WalletRegistry.PREFS, Context.MODE_PRIVATE),
            recordExists = { scope ->
                val p = app.getSharedPreferences(WalletStores.seedRecordPrefs(scope), Context.MODE_PRIVATE)
                p.contains("encrypted_seed") || p.contains("encrypted_seed_v2")
            },
            recordsOnDisk = { recordsOnDisk(app) },
        )
    }

    private fun recordsOnDisk(app: Context): List<Pair<String, Boolean?>> {
        val files = runCatching { File(app.dataDir, "shared_prefs").listFiles() }.getOrNull() ?: return emptyList()
        return files.mapNotNull { f ->
            val id = WalletStores.walletIdOfSeedPrefsFile(f.name) ?: return@mapNotNull null
            val prefs = app.getSharedPreferences(WalletStores.seedRecordPrefs(WalletScope(id, false)), Context.MODE_PRIVATE)
            id to when (val v = prefs.all[NETWORK_KEY]) {
                is Boolean -> v
                is String -> when (v.lowercase()) { "testnet" -> true; "mainnet" -> false; else -> null }
                else -> null
            }
        }.sortedBy { it.first }
    }

    @VisibleForTesting
    fun resetForTest() {
        synchronized(lock) { cached = null }
    }
}
