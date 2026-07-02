package io.digibyte.core

import io.digibyte.core.bridge.NativeBridge
import io.digibyte.core.dandelion.Broadcaster
import io.digibyte.core.db.entity.UtxoEntity

sealed class TxResult {
    data class Success(val txid: String) : TxResult()
    data class Error(val message: String) : TxResult()
}

class TransactionBuilder(
    private val coinSelector: CoinSelector,
    private val utxoManager: UtxoManager,
    private val outgoingTxStore: OutgoingTxStore,
    private val walletTxPersister: WalletTxPersister,
) {
    /**
     * Build, sign, and broadcast a transaction.
     * Returns txid on success, error message on failure.
     */
    suspend fun sendTransaction(
        toAddress: String,
        amountSatoshis: Long,
        feePerKb: Long,
        spendableUtxos: List<UtxoEntity>
    ): TxResult {
        // Validate address
        if (!NativeBridge.isValidAddress(toAddress)) {
            return TxResult.Error("Invalid DigiByte address")
        }

        // Validate amount
        if (amountSatoshis <= 0) {
            return TxResult.Error("Amount must be positive")
        }

        // Create unsigned transaction via C core — the C core's BRWallet handles
        // UTXO selection internally using its own transaction set. The Room-based
        // CoinSelector was redundant and failed because Room UTXOs weren't populated
        // from the SPV sync. Let the C core do what it's designed to do.
        val unsignedTx = NativeBridge.createTransaction(toAddress, amountSatoshis, feePerKb)
            ?: return TxResult.Error("Insufficient balance")

        // Sign via C core (uses RFC 6979 deterministic nonces)
        val signedTx = NativeBridge.signTransaction(unsignedTx)
            ?: return TxResult.Error("Failed to sign transaction")

        // Broadcast via C core
        val txid = Broadcaster.broadcast(signedTx)
            ?: return TxResult.Error("Failed to broadcast transaction")

        // Record the outgoing tx so the activity list can categorize it as
        // "Sent" even if BRWalletAmountSentByTx later returns 0 because the
        // parent UTXO txs aren't in BRWallet->allTx. Best-effort: a stored
        // tx hash is only useful for the activity-list override and never
        // affects on-chain state, so failures here are silent.
        val feeSats = estimateFee(signedTx.size, feePerKb)
        outgoingTxStore.record(
            txid = txid,
            sentSats = amountSatoshis,
            feeSats = feeSats,
            toAddress = toAddress,
        )

        // Snapshot the wallet's tx set to disk synchronously so a
        // force-stop or crash immediately after broadcast doesn't drop
        // the just-sent tx from the persisted state. The companion
        // C-side change (publishTransaction → BRWalletRegisterTransaction)
        // guarantees getSerializedTransactions sees this tx by the time
        // persist() runs.
        walletTxPersister.persist()

        return TxResult.Success(txid)
    }

    private fun estimateFee(signedSize: Int, feePerKb: Long): Long =
        (signedSize.toLong() * feePerKb + 999L) / 1000L
}
