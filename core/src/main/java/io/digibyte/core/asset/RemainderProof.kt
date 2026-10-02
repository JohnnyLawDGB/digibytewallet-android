package io.digibyte.core.asset

import android.content.Context
import io.digibyte.core.model.AssetOperation
import io.digibyte.core.networkSuffix
import kotlinx.coroutines.sync.withLock

/**
 * A durable record of asset transactions whose LAST output is proven to carry no DigiAsset units.
 *
 * The hold rule ([AssetTxQuantity.targetsOutput]) keeps an asset transaction's last output out of
 * the plain-DGB spendable set while what its inputs carried is unknown — the implicit change could
 * be any amount. A proof recorded here answers that question for one transaction: its inputs
 * carried exactly what its instructions consume, so the remainder is zero. The hold paths read it
 * where their own rows give no answer ([AssetManager]); nothing else does.
 *
 * Only [RemainderProofWalk] writes a proof. A proof is a fact about transaction bytes that are
 * fixed by their ids, so it never expires and does not depend on which wallet asked.
 */
interface RemainderProofStore {
    fun isProven(txid: String): Boolean
    fun markProven(txid: String)
}

/** Process-lifetime store: the default for callers that do not persist (tests, previews). */
class InMemoryRemainderProofStore : RemainderProofStore {
    private val proven = java.util.Collections.synchronizedSet(HashSet<String>())
    override fun isProven(txid: String): Boolean = txid.lowercase() in proven
    override fun markProven(txid: String) { proven.add(txid.lowercase()) }
}

/** Store kept in the app's private preferences, per network, so a proof survives a restart and
 *  the startup replay does not hold the output out again. It lives in the scan's own preferences
 *  file, which a wallet wipe already clears on every network. */
class PrefsRemainderProofStore(context: Context) : RemainderProofStore {
    private val prefs = context.getSharedPreferences(
        PREFS_NAME + networkSuffix(context), Context.MODE_PRIVATE,
    )

    override fun isProven(txid: String): Boolean =
        txid.lowercase() in (prefs.getStringSet(KEY_PROVEN, emptySet()) ?: emptySet())

    @Synchronized
    override fun markProven(txid: String) {
        val current = prefs.getStringSet(KEY_PROVEN, emptySet()) ?: emptySet()
        if (txid.lowercase() in current) return
        prefs.edit().putStringSet(KEY_PROVEN, HashSet(current) + txid.lowercase()).commit()
    }

    private companion object {
        const val PREFS_NAME = "dgb_reconcile"
        const val KEY_PROVEN = "remainder_proven_txids"
    }
}

/** A transaction as the walk reads it: what it spends and its output scripts, in order. */
class WalkTx(
    /** Spent outpoints (txid display hex, vout); a coin-generation input has an all-zero txid. */
    val inputs: List<Pair<String, Int>>,
    val outputScripts: List<ByteArray>,
)

/**
 * Proves, from chain data, that the last output of a DigiAsset transfer carries no units, so the
 * DGB on it can leave the hold. Anything it cannot prove stays held: every answer other than
 * [Verdict.Proven] means "keep holding".
 *
 * WHAT IT READS. Transactions by id, from [fetch] (the wallet's own copy, else the node the scan
 * already talks to). Every transaction is accepted only when [txidOf] — the id computed from the
 * bytes — is the id it was asked for; any other bytes end the walk for that question. Nothing is
 * taken from a server's description of a transaction.
 *
 * THE MODEL. What an output holds is one of: provably nothing; "nothing, or exactly k units in one
 * holding"; or unknown. The rules, all from the DigiAsset transfer rules the wallet already applies:
 *  - A transaction with no DigiAsset payload passes every input unit to its last output, so its
 *    other outputs hold nothing, and its last output holds what its inputs held.
 *  - A transfer credits each instruction to the outputs it names; units it does not assign go to
 *    the last output ([AssetTxQuantity.implicitChange]). If the transfer is not valid, its outputs
 *    hold either nothing or (by one reading of the reference) everything at the last output; an
 *    output named by exactly ONE fixed instruction therefore holds nothing or exactly that amount,
 *    in one holding, and an output nothing names, other than the last, holds nothing.
 *  - The last output of a transfer that names it in no instruction holds nothing when either its
 *    inputs held nothing, or exactly one input held "nothing or exactly k" in one holding, every
 *    other input held nothing, and the instructions (none of them a skip) consume exactly k:
 *    valid, the remainder is zero; invalid, there was nothing to pass on. One holding consumed
 *    whole by the transfer's own instructions cannot fail on how holdings combine. An input that
 *    holds nothing is passed over by the instructions, as the reference does.
 *  Everything else — an issuance, a burn, a percent or range instruction where it matters, a
 *  payload that does not re-encode to exactly its own bytes, more than one payload, an index past
 *  the outputs — is unknown.
 *
 * BOUNDS. At most [maxTxs] transactions are read per proof and the walk goes at most [maxDepth]
 * transactions deep; reaching either is unknown. Results are cached for the walk's lifetime, so
 * outputs of one scan that share ancestry are read once.
 */
class RemainderProofWalk(
    private val fetch: suspend (String) -> ByteArray?,
    private val txidOf: (ByteArray) -> String?,
    private val parse: (ByteArray) -> WalkTx?,
    private val decoder: DigiAssetDecoder = DigiAssetDecoder(),
    private val maxTxs: Int = DEFAULT_MAX_TXS,
    private val maxDepth: Int = DEFAULT_MAX_DEPTH,
) {
    // One proof at a time: the per-proof read budget and the caches are shared state.
    private val lock = kotlinx.coroutines.sync.Mutex()
    /** Why a proof did not complete. Every one of them means the output stays held. */
    enum class Reason {
        /** A transaction could not be fetched or parsed. */
        UNAVAILABLE,
        /** Bytes came back whose id is not the id asked for. */
        ID_MISMATCH,
        /** The transaction count or depth bound was reached. */
        BOUND,
        /** The chain data is outside what the model can prove. */
        NOT_PROVABLE,
    }

    sealed class Verdict {
        object Proven : Verdict() { override fun toString() = "Proven" }
        data class Held(val reason: Reason) : Verdict()
    }

    /** What an output holds, as far as the walk can say. */
    private sealed class Units {
        object Zero : Units()
        /** Nothing, or exactly [amount] units in one holding. */
        data class Chunk(val amount: Long) : Units()
        data class Unknown(val reason: Reason) : Units()
    }

    private sealed class Read {
        class Ok(val tx: WalkTx) : Read()
        data class Failed(val reason: Reason) : Read()
    }

    private val txs = HashMap<String, Read>()
    private val units = HashMap<Pair<String, Int>, Units>()
    private var readsThisProof = 0

    /** Does output [vout] of transaction [txid] — which must be the transaction's last output —
     *  provably carry no DigiAsset units? */
    suspend fun proveLastOutputCarriesNoUnits(txid: String, vout: Int): Verdict = lock.withLock {
        prove(txid, vout)
    }

    private suspend fun prove(txid: String, vout: Int): Verdict {
        readsThisProof = 0
        val id = txid.lowercase()
        val tx = when (val r = read(id)) {
            is Read.Failed -> return Verdict.Held(r.reason)
            is Read.Ok -> r.tx
        }
        if (vout != tx.outputScripts.size - 1) return Verdict.Held(Reason.NOT_PROVABLE)
        // The question is about a transfer's remainder: a transaction with no payload has nothing
        // to prove here and is not this walk's to release.
        if (payloadOf(tx) !is Payload.Transfer) return Verdict.Held(Reason.NOT_PROVABLE)
        return when (val u = unitsOf(id, vout, depth = 0)) {
            Units.Zero -> Verdict.Proven
            is Units.Chunk -> Verdict.Held(Reason.NOT_PROVABLE)
            is Units.Unknown -> Verdict.Held(u.reason)
        }
    }

    private suspend fun read(txid: String): Read {
        txs[txid]?.let { return it }
        if (readsThisProof >= maxTxs) return Read.Failed(Reason.BOUND)   // not cached: a later proof may read it
        readsThisProof++
        val result = run {
            val bytes = try {
                fetch(txid)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: return@run Read.Failed(Reason.UNAVAILABLE)
            val id = runCatching { txidOf(bytes) }.getOrNull()?.lowercase()
            if (id != txid) return@run Read.Failed(Reason.ID_MISMATCH)
            val tx = runCatching { parse(bytes) }.getOrNull() ?: return@run Read.Failed(Reason.UNAVAILABLE)
            Read.Ok(tx)
        }
        // A fetch that failed may succeed on the next scan; only what the bytes settle is kept.
        if (result is Read.Ok || (result as Read.Failed).reason == Reason.ID_MISMATCH) txs[txid] = result
        return result
    }

    private sealed class Payload {
        object None : Payload()
        class Transfer(val header: DecodedAssetHeader) : Payload()
        object Other : Payload()
    }

    /** The transaction's DigiAsset payload, read strictly: exactly one OP_RETURN carrying the
     *  DigiAsset marker, decoded as a transfer whose instructions re-encode to exactly its bytes
     *  (so no instruction was dropped by a lenient parse). */
    private fun payloadOf(tx: WalkTx): Payload {
        val marked = tx.outputScripts.filter { it.isNotEmpty() && it[0] == OP_RETURN && decoder.containsAsset(it) }
        if (marked.isEmpty()) return Payload.None
        if (marked.size > 1) return Payload.Other
        val script = marked[0]
        val header = decoder.decode(script) ?: return Payload.Other
        if (header.operation != AssetOperation.TRANSFER) return Payload.Other
        if (header.version !in 1..3 || header.transferInstructions.isEmpty()) return Payload.Other
        val reencoded = runCatching {
            DigiAssetEncoder.encodeTransferScript(
                2,
                header.transferInstructions.map {
                    DigiAssetEncoder.TransferInstruction(it.skip, it.range, it.percent, it.outputIndex, it.amount)
                },
            )
        }.getOrNull() ?: return Payload.Other
        // The encoder writes version 2; the version byte is the only field that may differ.
        val versionAt = reencoded.size - payloadLength(reencoded) + 2
        reencoded[versionAt] = header.version.toByte()
        return if (reencoded.contentEquals(script)) Payload.Transfer(header) else Payload.Other
    }

    private suspend fun unitsOf(txid: String, vout: Int, depth: Int): Units {
        val key = txid to vout
        units[key]?.let { return it }
        if (depth > maxDepth) return Units.Unknown(Reason.BOUND)
        val result = computeUnits(txid, vout, depth)
        // A bound is about this proof's budget, not about the output; leave it unsettled.
        if (!(result is Units.Unknown && result.reason == Reason.BOUND) &&
            !(result is Units.Unknown && result.reason == Reason.UNAVAILABLE)
        ) units[key] = result
        return result
    }

    private suspend fun computeUnits(txid: String, vout: Int, depth: Int): Units {
        if (txid == COINBASE_TXID) return Units.Zero
        val tx = when (val r = read(txid)) {
            is Read.Failed -> return Units.Unknown(r.reason)
            is Read.Ok -> r.tx
        }
        val outputCount = tx.outputScripts.size
        if (vout < 0 || vout >= outputCount) return Units.Unknown(Reason.NOT_PROVABLE)
        val last = outputCount - 1

        when (val payload = payloadOf(tx)) {
            Payload.None -> {
                if (vout != last) return Units.Zero
                return when (val pool = poolOf(tx, depth)) {
                    is Pool.Unknown -> Units.Unknown(pool.reason)
                    Pool.Empty -> Units.Zero
                    is Pool.One -> Units.Chunk(pool.amount)
                }
            }
            Payload.Other -> return Units.Unknown(Reason.NOT_PROVABLE)
            is Payload.Transfer -> {
                val instructions = payload.header.transferInstructions
                if (instructions.any { it.outputIndex >= outputCount }) return Units.Unknown(Reason.NOT_PROVABLE)
                val naming = instructions.filter { if (it.range) vout <= it.outputIndex else it.outputIndex == vout }
                if (vout != last) {
                    if (naming.isEmpty()) return Units.Zero
                    val only = naming.singleOrNull() ?: return Units.Unknown(Reason.NOT_PROVABLE)
                    if (only.range || only.percent) return Units.Unknown(Reason.NOT_PROVABLE)
                    return if (only.amount == 0L) Units.Zero else Units.Chunk(only.amount)
                }
                if (naming.isNotEmpty()) return Units.Unknown(Reason.NOT_PROVABLE)
                // A skip moves the instructions on to the next input, so they need not draw the
                // one holding whole; that is outside what this rule proves.
                if (instructions.any { it.skip }) return Units.Unknown(Reason.NOT_PROVABLE)
                val consumed = AssetTxQuantity.assignedUnits(payload.header)
                    ?: return Units.Unknown(Reason.NOT_PROVABLE)
                return when (val pool = poolOf(tx, depth)) {
                    is Pool.Unknown -> Units.Unknown(pool.reason)
                    Pool.Empty -> Units.Zero
                    is Pool.One -> if (pool.amount == consumed) Units.Zero else Units.Unknown(Reason.NOT_PROVABLE)
                }
            }
        }
    }

    private sealed class Pool {
        object Empty : Pool()
        data class One(val amount: Long) : Pool()
        data class Unknown(val reason: Reason) : Pool()
    }

    /** What a transaction's inputs held together: nothing, one holding of "nothing or k", or
     *  unknown. Inputs are read in order and the first unknown ends it. */
    private suspend fun poolOf(tx: WalkTx, depth: Int): Pool {
        var one: Long? = null
        for ((prevTxid, prevVout) in tx.inputs) {
            when (val u = unitsOf(prevTxid.lowercase(), prevVout, depth + 1)) {
                Units.Zero -> Unit
                is Units.Chunk -> {
                    if (one != null) return Pool.Unknown(Reason.NOT_PROVABLE)
                    one = u.amount
                }
                is Units.Unknown -> return Pool.Unknown(u.reason)
            }
        }
        return one?.let { Pool.One(it) } ?: Pool.Empty
    }

    companion object {
        const val DEFAULT_MAX_TXS = 200
        const val DEFAULT_MAX_DEPTH = 64
        private const val OP_RETURN: Byte = 0x6A
        private const val COINBASE_TXID = "0000000000000000000000000000000000000000000000000000000000000000"

        /** Length of the pushed payload of an OP_RETURN script the encoder built. */
        private fun payloadLength(script: ByteArray): Int =
            if ((script[1].toInt() and 0xFF) == 0x4C) script[2].toInt() and 0xFF else script[1].toInt() and 0xFF
    }
}
