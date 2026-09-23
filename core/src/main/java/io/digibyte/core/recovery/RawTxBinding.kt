package io.digibyte.core.recovery

import io.digibyte.core.bridge.NativeBridge
import io.digibyte.core.reconcile.RawTxEntry
import io.digibyte.core.reconcile.UtxoEntry
import java.security.MessageDigest

/**
 * The amount and script recovery signs for an input are the ones its parent transaction states.
 *
 * A legacy (P2PKH) signature does not commit to the amount it spends, so the amount handed to the
 * signer has to come from somewhere the signature cannot vouch for itself: the transaction that
 * created the output. This reads it there, and only from bytes whose own id is the outpoint's
 * txid. The id is computed from the bytes, never taken from the key they arrived under.
 *
 * Each input gets one of three answers:
 *  - [Verdict.Checked.proven]: the bytes are the named transaction, it has the output, and the
 *    reported amount and script are the ones it states. Only these reach a signer.
 *  - unproven: no parent was supplied, or the bytes do not parse. That input stays where it is and
 *    is reported; the rest of the profile is unaffected.
 *  - [Verdict.Contradicted]: the bytes are a different transaction, it has no such output, or it
 *    states a different amount or script. Nothing reported for that profile is signed.
 *
 * Every input is checked, whatever its script: a single rule is simpler to hold than a list of
 * exemptions, and a segwit input with a readable parent loses nothing by being checked.
 *
 * Parsing stays native (the parser every other raw-transaction path uses). The three touchpoints
 * are injected so the rule runs on a JVM; [native] is the production wiring.
 */
class RawTxBinding(
    /**
     * The id of raw transaction bytes, display-order hex, computed over the serialization without
     * witness data; null when the bytes do not parse. Production: [transactionId].
     */
    private val txidOf: (ByteArray) -> String?,
    /** Outputs of raw transaction bytes; null when the bytes do not parse. */
    private val outputsOf: (ByteArray) -> List<Output>?,
    /** Outpoints ("txid:vout", display order) a raw transaction spends; null when it does not parse. */
    private val inputsOf: (ByteArray) -> List<String>?,
) {

    /** One output as the parent transaction states it. */
    data class Output(val vout: Int, val amountSat: Long, val scriptPubKeyHex: String)

    sealed class Verdict {
        /** A parent disagrees with what was reported for [outpoint]. The whole profile is refused. */
        data class Contradicted(val outpoint: String, val detail: String) : Verdict()

        /** Nothing disagreed. [unproven] lists the outpoints ("txid:vout") with no readable parent. */
        class Checked internal constructor(
            private val stated: Map<String, Output>,
            val unproven: List<String>,
        ) : Verdict() {
            /**
             * [utxo] carrying its parent's amount and script, or null when it is not proven. The
             * copy, not the reported row, is what a signer is given.
             */
            fun proven(utxo: UtxoEntry): UtxoEntry? {
                val out = stated[outpoint(utxo)] ?: return null
                return utxo.copy(amountSatoshi = out.amountSat, scriptPubKeyHex = out.scriptPubKeyHex)
            }
        }
    }

    /** Checks every UTXO of [result] against the parents it carries. Each parent is read once. */
    fun check(result: RecoveryScanService.ProfileResult): Verdict {
        val parsed = mutableMapOf<String, Parent>()
        val stated = mutableMapOf<String, Output>()
        val unproven = mutableListOf<String>()

        for (utxo in result.utxos) {
            val op = outpoint(utxo)
            val parent = parsed.getOrPut(utxo.txid.lowercase()) { read(utxo.txid, result.rawTxs) }
            when (parent) {
                is Parent.Unreadable -> if (op !in unproven) unproven += op
                is Parent.Other ->
                    return Verdict.Contradicted(op, "the parent supplied for $op is ${parent.txid}")
                is Parent.Read -> {
                    val out = parent.outputs.firstOrNull { it.vout == utxo.vout }
                        ?: return Verdict.Contradicted(op, "its parent has no output ${utxo.vout}")
                    if (out.amountSat != utxo.amountSatoshi) {
                        return Verdict.Contradicted(
                            op, "reported ${utxo.amountSatoshi} sat, its parent states ${out.amountSat}",
                        )
                    }
                    val reportedScript = utxo.scriptPubKeyHex
                    if (reportedScript != null && !reportedScript.equals(out.scriptPubKeyHex, ignoreCase = true)) {
                        return Verdict.Contradicted(op, "the reported script is not the one its parent states")
                    }
                    stated[op] = out.copy(scriptPubKeyHex = out.scriptPubKeyHex.lowercase())
                }
            }
        }
        return Verdict.Checked(stated, unproven)
    }

    /**
     * The fee a signed transaction pays: what it spends, at the amounts [spent] gives for each
     * outpoint, less what it pays out. Null unless the transaction reads back and spends exactly
     * the outpoints in [spent], each once.
     */
    fun feePaid(signed: ByteArray, spent: Map<String, Long>): Long? {
        val ins = runCatching { inputsOf(signed) }.getOrNull() ?: return null
        val outs = runCatching { outputsOf(signed) }.getOrNull() ?: return null
        val normalized = ins.map { it.lowercase() }
        val expected = spent.keys.map { it.lowercase() }
        if (normalized.size != expected.size || normalized.toSet() != expected.toSet()) return null
        if (outs.isEmpty() || outs.any { it.amountSat < 0L } || spent.values.any { it <= 0L }) return null
        return try {
            val totalIn = spent.values.fold(0L) { acc, v -> Math.addExact(acc, v) }
            val totalOut = outs.fold(0L) { acc, o -> Math.addExact(acc, o.amountSat) }
            (totalIn - totalOut).takeIf { it >= 0L }
        } catch (_: ArithmeticException) {
            null
        }
    }

    private sealed class Parent {
        data class Read(val outputs: List<Output>) : Parent()
        data class Other(val txid: String) : Parent()
        data object Unreadable : Parent()
    }

    private fun read(txid: String, rawTxs: Map<String, RawTxEntry>): Parent {
        val entry = rawTxs[txid]
            ?: rawTxs.entries.firstOrNull { it.key.equals(txid, ignoreCase = true) }?.value
            ?: return Parent.Unreadable
        val bytes = hexToBytesOrNull(entry.hex) ?: return Parent.Unreadable
        val id = runCatching { txidOf(bytes) }.getOrNull() ?: return Parent.Unreadable
        if (!id.equals(txid, ignoreCase = true)) return Parent.Other(id)
        val outputs = runCatching { outputsOf(bytes) }.getOrNull() ?: return Parent.Unreadable
        return Parent.Read(outputs)
    }

    companion object {
        /** Production wiring: every read goes through the native parser. */
        fun native(): RawTxBinding = RawTxBinding(
            txidOf = transactionId,
            outputsOf = ::nativeOutputs,
            inputsOf = ::nativeInputs,
        )

        /**
         * The production id function: the id the wallet's own parser computes, which excludes
         * witness data. It answers only for bytes that are exactly one complete signed
         * transaction; anything else is null, which leaves that input unproven (held and
         * reported), never read as a different transaction.
         */
        val transactionId: (ByteArray) -> String? = { NativeBridge.rawTransactionId(it) }

        internal fun witnessFreeTransactionId(raw: ByteArray): String? {
            if (raw.size < 10) return null
            // version (4 bytes), then 0x00 0x01 is the segwit marker and flag.
            if (raw[4] == 0.toByte() && raw[5] == 1.toByte()) return null
            val sha = MessageDigest.getInstance("SHA-256")
            val hash = sha.digest(sha.digest(raw))
            hash.reverse()
            return hash.joinToString("") { "%02x".format(it) }
        }

        internal fun outpoint(utxo: UtxoEntry): String = "${utxo.txid}:${utxo.vout}"

        private fun nativeOutputs(raw: ByteArray): List<Output>? {
            val lines = NativeBridge.getRawTransactionOutputs(raw) ?: return null
            return lines.map { line ->
                val parts = line.split("|", limit = 3)
                require(parts.size == 3)
                Output(parts[0].toInt(), parts[1].toLong(), parts[2])
            }
        }

        private fun nativeInputs(raw: ByteArray): List<String>? {
            val lines = NativeBridge.getRawTransactionInputs(raw) ?: return null
            return lines.map { line ->
                val parts = line.split("|", limit = 2)
                require(parts.size == 2)
                "${parts[0]}:${parts[1].toInt()}"
            }
        }

        private fun hexToBytesOrNull(hex: String): ByteArray? {
            if (hex.isEmpty() || hex.length % 2 != 0) return null
            val out = ByteArray(hex.length / 2)
            for (i in out.indices) {
                val hi = Character.digit(hex[i * 2], 16)
                val lo = Character.digit(hex[i * 2 + 1], 16)
                if (hi < 0 || lo < 0) return null
                out[i] = ((hi shl 4) or lo).toByte()
            }
            return out
        }
    }
}
