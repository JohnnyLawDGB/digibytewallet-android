package io.digibyte.core

import io.digibyte.core.bridge.NativeBridge
import io.digibyte.core.dandelion.Broadcaster
import io.digibyte.core.db.entity.UtxoEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Why a spend-class action was refused before anything was built or signed. */
enum class SendRefusal {
    /** The asset carries transfer rules this wallet cannot satisfy; sending would destroy it. */
    RULE_BOUND_ASSET,
    /** The asset's rules could not be established; refused rather than guessed. */
    RULES_UNKNOWN,
}

sealed class TxResult {
    data class Success(val txid: String) : TxResult()
    data class Error(val message: String) : TxResult()
    /** Refused by policy, not by failure: nothing was selected, built, signed or broadcast.
     *  Typed so the screen can say why in the user's language. */
    data class Refused(val reason: SendRefusal) : TxResult()
}

/**
 * The step that comes before a spend is built from the wallet's plain-coin set.
 *
 * Asset detection is what holds an asset-carrying output out of that set, so the set is right to
 * build from once detection has looked at every transaction the wallet holds. The pass that does
 * the looking belongs to the asset layer, which installs it here when it is constructed
 * ([io.digibyte.core.asset.AssetManager]); whatever builds a spend from the plain-coin set runs it
 * first and builds nothing unless it finished.
 *
 * No pass installed is the same as a pass that did not finish: no spend is built.
 */
object SpendPreflight {
    /** What a refused spend reports: the wording the send screens already show as their general
     *  failure, so a refusal needs no text of its own. */
    const val NOT_SENT = "The transaction could not be sent."

    @Volatile private var pass: (suspend () -> Unit)? = null

    internal val isInstalled: Boolean get() = pass != null

    /** Internal: the pass is the asset layer's, and only `core` can put one here. */
    internal fun install(pass: suspend () -> Unit) { this.pass = pass }

    internal fun clear() { pass = null }

    /** Run the installed pass to completion. Throws when none is installed or it cannot finish. */
    suspend fun run() {
        val installed = pass ?: throw IllegalStateException("no pre-spend pass is installed")
        installed()
    }

    /**
     * True once [pass] has run to completion; false when it could not, in which case the caller
     * builds nothing. A cancellation is not a verdict on the pass and is rethrown; so is an error
     * of the virtual machine, which is not caught here at all. Either way nothing is built.
     */
    suspend fun completed(pass: suspend () -> Unit = { run() }): Boolean =
        try {
            pass()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            notFinished(e)
        } catch (e: LinkageError) {   // a native library that is missing or did not initialise
            notFinished(e)
        }

    private fun notFinished(cause: Throwable): Boolean {
        android.util.Log.w("SpendPreflight", "the pre-spend pass did not finish; nothing is built", cause)
        return false
    }
}

/**
 * The native calls a spend is made of. Production goes to the bridge; a test supplies a recording
 * fake, because the bridge's static initialiser loads the native library and cannot run on a JVM.
 */
interface SpendNative {
    fun isValidAddress(address: String): Boolean
    fun createTransaction(toAddress: String, amountSatoshis: Long, feePerKb: Long): ByteArray?
    fun signTransaction(unsignedTx: ByteArray): ByteArray?
    fun broadcast(signedTx: ByteArray): String?
    fun sendDigiDollar(tdAddress: String, cents: Long): String?

    /** Touches [NativeBridge] only when a method is called, so constructing a builder loads nothing. */
    object Bridge : SpendNative {
        override fun isValidAddress(address: String): Boolean = NativeBridge.isValidAddress(address)
        override fun createTransaction(toAddress: String, amountSatoshis: Long, feePerKb: Long): ByteArray? =
            NativeBridge.createTransaction(toAddress, amountSatoshis, feePerKb)
        override fun signTransaction(unsignedTx: ByteArray): ByteArray? = NativeBridge.signTransaction(unsignedTx)
        override fun broadcast(signedTx: ByteArray): String? = Broadcaster.broadcast(signedTx)
        override fun sendDigiDollar(tdAddress: String, cents: Long): String? =
            NativeBridge.sendDigiDollar(tdAddress, cents)
    }
}

class TransactionBuilder(
    private val coinSelector: CoinSelector,
    private val utxoManager: UtxoManager,
    private val outgoingTxStore: OutgoingTxStore,
    private val walletTxPersister: WalletTxPersister,
    /** Runs before anything is built from the plain-coin set; see [SpendPreflight]. */
    private val beforeSpend: suspend () -> Unit = { SpendPreflight.run() },
    private val native: SpendNative = SpendNative.Bridge,
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
    ): TxResult = withContext(Dispatchers.IO) {
        // Off the caller's thread (SendViewModel launches on Main): build, sign,
        // and BROADCAST are blocking native calls that take the C-core peer/wallet
        // locks. Running them on the UI thread froze the app at "broadcasting",
        // especially once the compact-filter confirmation path started holding the
        // peer-manager lock across full-block processing.
        // Validate address
        if (!native.isValidAddress(toAddress)) {
            return@withContext TxResult.Error("Invalid DigiByte address")
        }

        // Validate amount
        if (amountSatoshis <= 0) {
            return@withContext TxResult.Error("Amount must be positive")
        }

        // Detection first, to completion: the C core selects coins from its plain-coin set on the
        // next line, and that set is right to select from once every transaction the wallet
        // holds has been looked at. A pass that did not finish means nothing is built.
        if (!SpendPreflight.completed(beforeSpend)) {
            return@withContext TxResult.Error(SpendPreflight.NOT_SENT)
        }

        // Create unsigned transaction via C core — the C core's BRWallet handles
        // UTXO selection internally using its own transaction set. The Room-based
        // CoinSelector was redundant and failed because Room UTXOs weren't populated
        // from the SPV sync. Let the C core do what it's designed to do.
        val unsignedTx = native.createTransaction(toAddress, amountSatoshis, feePerKb)
            ?: return@withContext TxResult.Error("Insufficient balance")

        // Sign via C core (uses RFC 6979 deterministic nonces)
        val signedTx = native.signTransaction(unsignedTx)
            ?: return@withContext TxResult.Error("Failed to sign transaction")

        // Broadcast via C core
        val txid = native.broadcast(signedTx)
            ?: return@withContext TxResult.Error("Failed to broadcast transaction")

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

        TxResult.Success(txid)
    }

    /**
     * Send DigiDollar. The transfer is built, signed and published natively in one step, and its
     * network fee is paid from the plain-coin set — so detection runs to completion first, exactly
     * as for a DGB send. Returns the txid, or null when nothing was sent.
     */
    suspend fun sendDigiDollar(tdAddress: String, cents: Long): String? = withContext(Dispatchers.IO) {
        if (!SpendPreflight.completed(beforeSpend)) return@withContext null
        native.sendDigiDollar(tdAddress, cents)
    }

    private fun estimateFee(signedSize: Int, feePerKb: Long): Long =
        (signedSize.toLong() * feePerKb + 999L) / 1000L
}
