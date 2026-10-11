package app.aroundtheblock.wallet.core

import app.aroundtheblock.wallet.core.bridge.NativeBridge
import app.aroundtheblock.wallet.core.dandelion.Broadcaster
import app.aroundtheblock.wallet.core.send.DustPolicy
import app.aroundtheblock.wallet.core.send.FeeQuote
import app.aroundtheblock.wallet.core.send.FeeRequest
import app.aroundtheblock.wallet.core.sync.PublishOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Why a spend-class action was refused before anything was built or signed. */
enum class SendRefusal {
    /** The asset carries transfer rules this wallet cannot satisfy; sending would destroy it. */
    RULE_BOUND_ASSET,
    /** The asset's rules could not be established; refused rather than guessed. */
    RULES_UNKNOWN,
    /** What the asset's coins hold could not be confirmed right now; nothing is sent on a guess. */
    ASSET_HOLDING_UNVERIFIED,
    /** No coin confirmed to hold only this asset covers the amount. */
    ASSET_HOLDING_UNCONFIRMED,
    /** The amount needs coins held at more than one address; one send moves up to [TxResult.Refused.limit]. */
    ASSET_SPANS_ADDRESSES,
}

/** What is known of a broadcast send: whether a peer has taken it. */
enum class Delivery {
    /** A peer took the transaction (it requested or relayed it), or a block confirmed it. */
    RELAYED,

    /** It went out and no peer has taken it yet. The wallet keeps it and keeps trying. */
    NOT_YET_RELAYED,

    /** The caller did not wait for a peer's answer. */
    NOT_AWAITED,
}

sealed class TxResult {
    data class Success(val txid: String, val delivery: Delivery = Delivery.NOT_AWAITED) : TxResult()
    data class Error(val message: String) : TxResult()
    /** Refused by policy, not by failure: nothing was selected, built, signed or broadcast.
     *  Typed so the screen can say why in the user's language. */
    data class Refused(val reason: SendRefusal, val limit: Long? = null) : TxResult()
}

/**
 * The step that comes before a spend is built from the wallet's plain-coin set.
 *
 * Asset detection is what holds an asset-carrying output out of that set, so the set is right to
 * build from once detection has looked at every transaction the wallet holds. The pass that does
 * the looking belongs to the asset layer, which installs it here when it is constructed
 * ([app.aroundtheblock.wallet.core.asset.AssetManager]); whatever builds a spend from the plain-coin set runs it
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
    /** The script an address is paid with; null when it is not an address. */
    fun scriptPubKey(address: String): ByteArray?
    fun createTransaction(toAddress: String, amountSatoshis: Long, feePerKb: Long): ByteArray?
    /** `[fee, vsize]` of a transaction the wallet built, read from its bytes; null when the fee is unknown. */
    fun quoteTransaction(unsignedTx: ByteArray): LongArray?
    /** The outputs of raw transaction bytes, one `"vout|satoshis|scriptHex"` each. */
    fun transactionOutputs(tx: ByteArray): Array<String>?
    /**
     * Signs prepared bytes, or null: only the approved send. Native code refuses, before signing, bytes
     * whose outputs are not [amountSats] to [toAddress] plus change to a wallet address, that do not
     * pay exactly [approvedFeeSats], or that spend a coin which is no longer one of the wallet's
     * spendable plain coins; and refuses to return a signed transaction that fails the same check.
     * The only signer a DGB send has.
     */
    fun signPrepared(unsignedTx: ByteArray, toAddress: String, amountSats: Long, approvedFeeSats: Long): ByteArray?
    fun broadcast(signedTx: ByteArray): String?
    /** The publish result ring's answer for [txid]: 0 a peer took it, an errno, or -1 while pending. */
    fun publishResult(txid: String): Int
    fun sendDigiDollar(tdAddress: String, cents: Long): String?

    /** Touches [NativeBridge] only when a method is called, so constructing a builder loads nothing. */
    object Bridge : SpendNative {
        override fun isValidAddress(address: String): Boolean = NativeBridge.isValidAddress(address)
        override fun scriptPubKey(address: String): ByteArray? = NativeBridge.addressToScriptPubKey(address)
        override fun createTransaction(toAddress: String, amountSatoshis: Long, feePerKb: Long): ByteArray? =
            NativeBridge.createTransaction(toAddress, amountSatoshis, feePerKb)
        override fun quoteTransaction(unsignedTx: ByteArray): LongArray? = NativeBridge.quoteTransaction(unsignedTx)
        override fun transactionOutputs(tx: ByteArray): Array<String>? = NativeBridge.getRawTransactionOutputs(tx)
        override fun signPrepared(unsignedTx: ByteArray, toAddress: String, amountSats: Long, approvedFeeSats: Long): ByteArray? =
            NativeBridge.signPreparedTransaction(unsignedTx, toAddress, amountSats, approvedFeeSats)
        override fun broadcast(signedTx: ByteArray): String? = Broadcaster.broadcast(signedTx)
        override fun publishResult(txid: String): Int = NativeBridge.getPublishResult(txid)
        override fun sendDigiDollar(tdAddress: String, cents: Long): String? =
            NativeBridge.sendDigiDollar(tdAddress, cents)
    }
}

/**
 * A DGB send built and quoted before the credential prompt: the exact bytes that are signed after it,
 * and their fee. Plain bytes and numbers, never a native object, so nothing native outlives the prompt;
 * an abandoned prompt drops it and the next review builds afresh.
 */
class PreparedSend internal constructor(
    val toAddress: String,
    val amountSats: Long,
    /** The rate the bytes were built at, sat/kB. */
    val feePerKb: Long,
    /** The fee of these bytes: what the confirmation shows and the approval carries. */
    val quote: FeeQuote,
    unsignedTx: ByteArray,
) {
    private val bytes: ByteArray = unsignedTx.copyOf()

    /** A copy of the unsigned bytes. */
    fun unsignedTx(): ByteArray = bytes.copyOf()
}

/** What review produced: a send ready to confirm, or why there is none. Nothing is signed either way. */
sealed class PrepareResult {
    data class Ready(val send: PreparedSend) : PrepareResult()

    /** The payment is below the smallest output the network relays to that address type. */
    data class BelowDust(val thresholdSats: Long) : PrepareResult()

    data class Error(val message: String) : PrepareResult()
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
     * Review: build the send and quote it, before the credential prompt. Nothing is signed.
     *
     * The quote is the built transaction's own fee — its inputs minus its outputs — and that is what
     * the confirmation shows and the approval carries; [sendPrepared] then signs these same bytes,
     * through the signer that checks them against that fee. A custom fee is a total: it becomes a
     * rate over this transaction's size, measured by building it at the default rate first, and the
     * quote says when the transaction pays more than the total ([FeeQuote.raised]).
     *
     * A payment below the dust line of its address type is refused before anything is built, and
     * every output of the built bytes is held to the same line — the bytes that are signed are the
     * bytes checked.
     */
    suspend fun prepareTransaction(toAddress: String, amountSatoshis: Long, fee: FeeRequest): PrepareResult =
        withContext(Dispatchers.IO) {
            if (!native.isValidAddress(toAddress)) return@withContext PrepareResult.Error("Invalid DigiByte address")
            if (amountSatoshis <= 0) return@withContext PrepareResult.Error("Amount must be positive")

            // The payment itself, before anything is built: below its line every node refuses the transaction.
            val destination = native.scriptPubKey(toAddress)
                ?: return@withContext PrepareResult.Error("Invalid DigiByte address")
            if (DustPolicy.isDust(amountSatoshis, destination, txIsDigiDollar = false)) {
                return@withContext PrepareResult.BelowDust(DustPolicy.threshold(destination))
            }

            // Detection first, to completion: the C core selects coins from its plain-coin set on the
            // next step, and that set is right to select from once every transaction the wallet
            // holds has been looked at. A pass that did not finish means nothing is built.
            if (!SpendPreflight.completed(beforeSpend)) {
                return@withContext PrepareResult.Error(SpendPreflight.NOT_SENT)
            }

            // At the default rate first: a default-fee send's bytes, and the size a custom total is taken over.
            val atDefault = buildAndQuote(toAddress, amountSatoshis, FeeQuote.DEFAULT_FEE_PER_KB, requestedSats = null)
            if (atDefault !is Built.Ok) return@withContext atDefault.asError()
            val built = when (fee) {
                is FeeRequest.Default -> atDefault
                is FeeRequest.CustomTotal -> {
                    // A rate of 0 would fall back to the wallet's own rate; 1 sat/kB builds at the floor.
                    val rate = FeeQuote.rateForTotal(fee.totalSats, atDefault.quote.vsize).coerceAtLeast(1L)
                    buildAndQuote(toAddress, amountSatoshis, rate, requestedSats = fee.totalSats)
                }
            }
            if (built !is Built.Ok) return@withContext built.asError()

            // Every output of what will be signed, not only the payment. The builder folds change
            // below the line into the fee, so today this only ever confirms it.
            belowDustLine(built.bytes)?.let { return@withContext it }

            PrepareResult.Ready(PreparedSend(toAddress, amountSatoshis, built.rate, built.quote, built.bytes))
        }

    /**
     * After the credential prompt: sign the prepared bytes, broadcast them, and say whether a peer took them.
     *
     * Nothing is built here. The bytes go to the checked signer with the approved payment and fee;
     * native code signs only that send — refusing bytes whose outputs are not that payment plus change
     * to the wallet, that do not pay exactly that fee, or that spend a coin which is no longer one of
     * the wallet's spendable plain coins (another send spent it, or detection now holds it as an asset
     * carrier, while the prompt was up) — and nothing is broadcast. The record keeps the fee signed.
     *
     * The send is [Delivery.RELAYED] only when a peer has taken it; otherwise [Delivery.NOT_YET_RELAYED]
     * — the wallet holds it and keeps publishing it, and it is never reported as sent.
     */
    suspend fun sendPrepared(prepared: PreparedSend): TxResult {
        val sent = withContext(Dispatchers.IO) {
            // Detection to completion again: the prompt took time, and the signer checks the coins
            // against the plain-coin set that detection keeps.
            if (!SpendPreflight.completed(beforeSpend)) {
                return@withContext TxResult.Error(SpendPreflight.NOT_SENT)
            }

            val signedTx = native.signPrepared(
                prepared.unsignedTx(), prepared.toAddress, prepared.amountSats, prepared.quote.feeSats,
            )
            if (signedTx == null) {
                // Not the approved send any more (its payment, fee or coins), or the session is
                // locked: nothing is signed or sent, and reviewing again builds afresh.
                android.util.Log.w(TAG, "the prepared send no longer matches its approval; nothing was signed")
                return@withContext TxResult.Error(SpendPreflight.NOT_SENT)
            }

            val txid = native.broadcast(signedTx)
                ?: return@withContext TxResult.Error("Failed to broadcast transaction")

            // Record the outgoing tx so the activity list can categorize it as "Sent" even if
            // BRWalletAmountSentByTx later returns 0 because the parent UTXO txs aren't in
            // BRWallet->allTx. The fee recorded is the fee signed. Best-effort: the record only
            // feeds the activity list and never affects on-chain state.
            outgoingTxStore.record(
                txid = txid,
                sentSats = prepared.amountSats,
                feeSats = prepared.quote.feeSats,
                toAddress = prepared.toAddress,
            )

            // Snapshot the wallet's tx set to disk synchronously so a force-stop or crash right
            // after the broadcast doesn't drop the just-sent tx from the persisted state. The
            // C side registers the tx before publishing, so getSerializedTransactions sees it.
            walletTxPersister.persist()

            TxResult.Success(txid)
        }
        if (sent !is TxResult.Success) return sent
        // The send is recorded and persisted before the wait, so leaving the screen loses nothing.
        return sent.copy(delivery = awaitDelivery(sent.txid))
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

    /**
     * Whether a peer has taken the published [txid]: [Delivery.RELAYED] on a verdict of 0, otherwise
     * [Delivery.NOT_YET_RELAYED] — no verdict within the wait, or any other verdict. See
     * [PublishOutcome.await]. The wait itself stays on the caller's dispatcher; only the reads go to IO.
     */
    suspend fun awaitDelivery(txid: String): Delivery {
        val outcome = PublishOutcome.await(read = { withContext(Dispatchers.IO) { native.publishResult(txid) } })
        return if (outcome?.userVisiblySent == true) Delivery.RELAYED else Delivery.NOT_YET_RELAYED
    }

    /** One build and its quote, or why there is none. */
    private sealed class Built {
        class Ok(val bytes: ByteArray, val quote: FeeQuote, val rate: Long) : Built()
        /** The builder found no coins to cover the amount and the fee. */
        data object Short : Built()
        /** Built bytes whose fee the wallet cannot tell: an input it does not hold. */
        data object Unquoted : Built()

        fun asError(): PrepareResult = when (this) {
            is Ok -> throw IllegalStateException("not an error")
            Short -> PrepareResult.Error("Insufficient balance")
            Unquoted -> PrepareResult.Error(SpendPreflight.NOT_SENT)
        }
    }

    private fun buildAndQuote(toAddress: String, amountSatoshis: Long, rate: Long, requestedSats: Long?): Built {
        val bytes = native.createTransaction(toAddress, amountSatoshis, rate) ?: return Built.Short
        val quote = FeeQuote.of(native.quoteTransaction(bytes), requestedSats) ?: return Built.Unquoted
        return Built.Ok(bytes, quote, rate)
    }

    /** [PrepareResult.BelowDust] for the first output of [tx] below its line; an error when they cannot be read; else null. */
    private fun belowDustLine(tx: ByteArray): PrepareResult? {
        val outputs = native.transactionOutputs(tx) ?: return PrepareResult.Error(SpendPreflight.NOT_SENT)
        val digiDollar = DustPolicy.isDigiDollarTransaction(versionOf(tx))
        for (line in outputs) {
            val parts = line.split('|')
            val sats = parts.getOrNull(1)?.toLongOrNull()
            val script = parts.getOrNull(2)?.let(::hexToBytes)
            if (parts.size != 3 || sats == null || script == null) return PrepareResult.Error(SpendPreflight.NOT_SENT)
            if (DustPolicy.isDust(sats, script, digiDollar)) return PrepareResult.BelowDust(DustPolicy.threshold(script))
        }
        return null
    }

    private companion object {
        const val TAG = "DGB-Send"

        /** A serialized transaction's version: its first four bytes, little-endian. */
        fun versionOf(tx: ByteArray): Int =
            if (tx.size < 4) 0
            else (tx[0].toInt() and 0xff) or ((tx[1].toInt() and 0xff) shl 8) or
                ((tx[2].toInt() and 0xff) shl 16) or ((tx[3].toInt() and 0xff) shl 24)

        fun hexToBytes(hex: String): ByteArray? {
            if (hex.length % 2 != 0) return null
            val out = ByteArray(hex.length / 2)
            for (i in out.indices) {
                val hi = Character.digit(hex[2 * i], 16)
                val lo = Character.digit(hex[2 * i + 1], 16)
                if (hi < 0 || lo < 0) return null
                out[i] = ((hi shl 4) or lo).toByte()
            }
            return out
        }
    }
}
