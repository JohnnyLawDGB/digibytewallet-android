package io.digibyte.core.recovery

import io.digibyte.core.reconcile.RawTxEntry
import io.digibyte.core.reconcile.UtxoEntry

/**
 * Transactions a test can hand to [RawTxBinding] without a native parser.
 *
 * A booked transaction's bytes are its id as UTF-8 text, and the fake parser reads the id back
 * from them, so "these bytes are transaction X" is literal. A parent filed under the wrong key in
 * a lookup response is then simply the bytes of another booked transaction.
 */
internal class TxBook {
    private data class Tx(val inputs: List<String>, val outputs: List<RawTxBinding.Output>)

    private val txs = mutableMapOf<String, Tx>()

    /** Books transaction [id] with these outputs (and, for a signed transaction, inputs). */
    fun add(id: String, outputs: List<RawTxBinding.Output>, inputs: List<String> = emptyList()) {
        txs[id] = Tx(inputs, outputs)
    }

    /** Books parents that state exactly what the lookup reported for each of [utxos]. */
    fun honest(vararg utxos: UtxoEntry) {
        for ((txid, group) in utxos.groupBy { it.txid }) {
            val existing = txs[txid]?.outputs.orEmpty()
            val added = group.map { RawTxBinding.Output(it.vout, it.amountSatoshi, it.scriptPubKeyHex ?: "") }
            add(txid, (existing + added).distinctBy { it.vout }.sortedBy { it.vout })
        }
    }

    fun bytes(id: String): ByteArray = id.toByteArray(Charsets.UTF_8)

    fun hex(id: String): String = bytes(id).joinToString("") { "%02x".format(it) }

    /** A lookup-response parent entry carrying transaction [id]'s bytes. */
    fun entry(id: String) = RawTxEntry(hex = hex(id), blockHeight = 1L, blockTime = 1L)

    /** The parents a lookup response would carry for [utxos]: each txid mapped to its own bytes. */
    fun rawTxs(utxos: List<UtxoEntry>): Map<String, RawTxEntry> =
        utxos.map { it.txid }.distinct().associateWith { entry(it) }

    val binding = RawTxBinding(
        txidOf = { raw -> String(raw, Charsets.UTF_8).takeIf { it in txs } },
        outputsOf = { raw -> txs[String(raw, Charsets.UTF_8)]?.outputs },
        inputsOf = { raw -> txs[String(raw, Charsets.UTF_8)]?.inputs },
    )
}
