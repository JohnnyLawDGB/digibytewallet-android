package app.aroundtheblock.wallet.core

import java.util.Collections

/**
 * The native side of a send, for a JVM test: it records every step in the order it is reached and
 * answers as the test sets it up. The bridge cannot run here (its static initialiser loads the native
 * library), so this is the only way to see which native call a send makes and with what.
 *
 * Built bytes are named by the rate they were built at — `tx@100000` — so a test can tell the bytes
 * the review built from bytes built again later, and see which bytes reach the signer.
 */
open class RecordingSpendNative : SpendNative {
    val events: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** The script each address is paid with; a native-segwit script unless a test says otherwise. */
    var scriptFor: (String) -> ByteArray? = { P2WPKH }

    /** `[fee, vsize]` per built bytes, keyed by their name (`tx@<rate>`). */
    val quotes: MutableMap<String, LongArray> = mutableMapOf()

    /** The outputs of built bytes, as the bridge lists them. */
    var outputsOf: (String) -> Array<String>? = { arrayOf("0|150000000|$P2WPKH_HEX") }

    /** Whether the checked signer signs (it refuses when the bytes no longer match the approval). */
    var signerSigns = true

    var broadcastTxid: String? = "txid"

    /** The publish result ring's answer on each successive read; the last one repeats. */
    var verdicts: List<Int> = listOf(0)
        set(value) { field = value; verdictReads = 0 }
    private var verdictReads = 0

    override fun isValidAddress(address: String): Boolean { events += "address"; return true }

    override fun scriptPubKey(address: String): ByteArray? { events += "script"; return scriptFor(address) }

    override fun createTransaction(toAddress: String, amountSatoshis: Long, feePerKb: Long): ByteArray? {
        events += "create@$feePerKb"
        return "tx@$feePerKb".toByteArray()
    }

    override fun quoteTransaction(unsignedTx: ByteArray): LongArray? {
        val name = String(unsignedTx)
        events += "quote $name"
        return quotes[name]?.copyOf()
    }

    override fun transactionOutputs(tx: ByteArray): Array<String>? {
        events += "outputs"
        return outputsOf(String(tx))
    }

    override fun signPrepared(unsignedTx: ByteArray, toAddress: String, amountSats: Long, approvedFeeSats: Long): ByteArray? {
        events += "sign ${String(unsignedTx)} to=$toAddress amount=$amountSats fee=$approvedFeeSats"
        return if (signerSigns) "signed:${String(unsignedTx)}".toByteArray() else null
    }

    override fun broadcast(signedTx: ByteArray): String? { events += "broadcast ${String(signedTx)}"; return broadcastTxid }

    override fun publishResult(txid: String): Int {
        val v = verdicts[minOf(verdictReads, verdicts.size - 1)]
        verdictReads++
        events += "verdict $v"
        return v
    }

    /** What the DigiDollar preview reads from the transfer it builds: `[fee, ddChange, dgbChange, inputs]`, or null. */
    var ddPreview: LongArray? = longArrayOf(10_030_000L, 0L, 0L, 2L)

    /** What the approved DigiDollar send returns: its txid, or null when native code refused to sign. */
    var ddTxid: String? = "ddtxid"

    override fun previewDigiDollar(tdAddress: String, cents: Long): LongArray? {
        events += "previewDigiDollar to=$tdAddress cents=$cents"
        return ddPreview?.copyOf()
    }

    override fun sendApprovedDigiDollar(tdAddress: String, cents: Long, approvedFeeSats: Long): String? {
        events += "sendApprovedDigiDollar to=$tdAddress cents=$cents fee=$approvedFeeSats"
        return ddTxid
    }

    companion object {
        /** A native-segwit (P2WPKH) output script: OP_0, a 20-byte push. */
        val P2WPKH_HEX: String = "0014" + "11".repeat(19) + "aa"
        val P2WPKH: ByteArray = hex(P2WPKH_HEX)

        fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }
}
