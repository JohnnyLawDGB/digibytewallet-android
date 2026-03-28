package io.digibyte.ui.wallet

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.digibyte.core.PriceData
import io.digibyte.core.PriceProvider
import io.digibyte.core.bridge.NativeBridge
import io.digibyte.core.UtxoManager
import io.digibyte.core.WalletManager
import io.digibyte.core.db.dao.TransactionDao
import io.digibyte.core.db.entity.TransactionEntity
import io.digibyte.core.model.SyncState
import io.digibyte.core.tor.TorManager
import io.digibyte.core.tor.TorState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class WalletViewModel @Inject constructor(
    private val application: Application,
    private val utxoManager: UtxoManager,
    private val transactionDao: TransactionDao,
    private val walletManager: WalletManager,
    private val priceProvider: PriceProvider,
    private val torManager: TorManager
) : ViewModel() {

    private val prefs = application.getSharedPreferences("dgb_sync_data", 0)

    /** Live balance in satoshis — polls C core every 5 seconds.
     *  Initialized from last-known snapshot so the UI isn't blank on restart. */
    private val _balance = MutableStateFlow(prefs.getLong("last_balance", 0L))
    val balance: StateFlow<Long> = _balance.asStateFlow()

    /** Live transaction list from C core, most-recent first. */
    private val _transactions = MutableStateFlow<List<TransactionEntity>>(emptyList())
    val transactions: StateFlow<List<TransactionEntity>> = _transactions.asStateFlow()

    /** SPV sync state propagated from WalletManager. */
    val syncState: StateFlow<SyncState> = walletManager.syncState

    /** Live Tor state — used by WalletScreen to show the Tor indicator badge. */
    val torState: StateFlow<TorState> = torManager.state

    /** Latest price data from PriceProvider (null until first fetch). */
    private val _price = MutableStateFlow<PriceData?>(null)
    val price: StateFlow<PriceData?> = _price.asStateFlow()

    /** Fiat balance string, e.g. "$12.34" — derived from balance + price. */
    val fiatBalance: StateFlow<String> = combine(balance, price) { sats, priceData ->
        if (priceData == null || priceData.priceUsd <= 0.0) return@combine "$ --"
        val dgb = sats / 100_000_000.0
        val fiat = dgb * priceData.priceUsd
        val fmt = NumberFormat.getCurrencyInstance(Locale.US)
        fmt.format(fiat)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "$ --")

    init {
        fetchPricePeriodically()
        pollNativeBalance()
    }

    /** Poll the C core for balance and transactions every 5 seconds.
     *  The C core tracks these from SPV sync — Room DB is secondary. */
    private fun pollNativeBalance() {
        viewModelScope.launch {
            while (true) {
                // Poll balance
                val nativeBalance = NativeBridge.getBalance()
                if (nativeBalance != _balance.value) {
                    _balance.value = nativeBalance
                    // Snapshot for next restart
                    prefs.edit().putLong("last_balance", nativeBalance).apply()
                }

                // Poll transactions
                val currentHeight = NativeBridge.getLastBlockHeight()
                val txDetails = NativeBridge.getTransactionDetails()
                if (txDetails.isNotEmpty()) {
                    val txList = txDetails.trim().lines().mapNotNull { line ->
                        val parts = line.split("|")
                        if (parts.size >= 5) {
                            val txHeight = parts[3].toLongOrNull() ?: 0L
                            val confs = if (txHeight > 0 && currentHeight >= txHeight)
                                (currentHeight - txHeight + 1).toInt() else 0
                            TransactionEntity(
                                txid = parts[0],
                                amount = parts[1].toLongOrNull() ?: 0L,
                                fee = parts[2].toLongOrNull() ?: 0L,
                                blockHeight = txHeight,
                                timestamp = parts[4].toLongOrNull() ?: 0L,
                                toAddress = "",
                                fromAddress = "",
                                confirmations = confs,
                                isAssetTx = false
                            )
                        } else null
                    }
                    if (txList != _transactions.value) {
                        _transactions.value = txList
                    }
                }

                delay(5_000L)
            }
        }
    }

    /** Fetch price now and then every 5 minutes. */
    private fun fetchPricePeriodically() {
        viewModelScope.launch {
            while (true) {
                runCatching { _price.value = priceProvider.fetchPrice() }
                delay(5 * 60 * 1000L)
            }
        }
    }

    /** Get a receive address for [index]. Delegates to WalletManager (bech32 by default). */
    fun getReceiveAddress(index: Int = 0): String? =
        walletManager.getReceiveAddress(index, format = 2)

    companion object {
        /**
         * Format satoshis to a human-readable DGB string with up to 8 decimal places.
         * Example: 123456789012 → "1,234.56789012 DGB"
         */
        fun formatSatoshis(satoshis: Long): String {
            val dgb = satoshis / 100_000_000.0
            val fmt = NumberFormat.getNumberInstance(Locale.US).apply {
                minimumFractionDigits = 2
                maximumFractionDigits = 8
            }
            return "${fmt.format(dgb)} DGB"
        }
    }
}
