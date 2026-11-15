package app.aroundtheblock.wallet.core.recovery

/**
 * A foreign wallet's DigiDollar: locating the outpoint that holds it, and describing it.
 *
 * ## Why the ordinary lookup cannot find this
 *
 * A DigiDollar token output carries ZERO satoshis. The cents live in the transaction's OP_RETURN,
 * not in the output amount, and the reconcile endpoint filters zero-value outputs out of the UTXO
 * set entirely.
 *
 * Measured on mainnet: a taproot address holding $1.00 answers `balance 0, utxo_count 0` through
 * the ordinary lookup, while the backend's DigiDollar endpoint reports `dd_balance_cents: 100`
 * for the same key. So a scan that derived the right addresses would still conclude the wallet
 * held nothing — the derivation was never the blocker, the lookup is.
 *
 * ## Balance is enough to report; an outpoint is needed to move
 *
 * The DigiDollar endpoint gives cents and a txid. Spending needs txid AND vout AND the
 * scriptPubKey, so the transaction's outputs are fetched and the one paying our taproot output
 * key is located here.
 *
 * Pure — no network, no JNI. The caller supplies the outputs and the derived key.
 */
object DigiDollarHolding {

    /** One parsed output of a transaction, as `getRawTransactionOutputs` returns them. */
    data class Output(val vout: Int, val amountSat: Long, val scriptPubKeyHex: String)

    /** A spendable DigiDollar outpoint. */
    data class Outpoint(val vout: Int, val scriptPubKeyHex: String)

    /**
     * A reference to a previous output, i.e. one transaction input.
     *
     * Distinct from [Outpoint], which identifies an output WITHIN the transaction being read.
     * This one points OUT of it, and is what proves a located output has already been spent.
     */
    data class PrevOut(val txid: String, val vout: Int)

    /** A P2TR scriptPubKey is `OP_1 <push32> X(Q)` — 0x51 0x20 then the 32-byte output key. */
    private const val P2TR_PREFIX = "5120"

    /**
     * The output that holds this wallet's DigiDollar, or null.
     *
     * Two conditions, and both matter:
     *
     *  - the script is P2TR paying [taprootOutputKeyHex]. Matching on value alone would select the
     *    OP_RETURN, which is also zero-value and sits in every DD transaction.
     *  - the value is zero. A P2TR output at the same key carrying satoshis is ordinary DGB, not
     *    dollars, and spending it as a DD input would misstate the transaction's value.
     */
    /** Every output of [outputs] paying this key (a transaction can pay one key twice; 4.1.2 row 20). */
    fun locateAll(outputs: List<Output>, taprootOutputKeyHex: String): List<Outpoint> {
        val want = (P2TR_PREFIX + taprootOutputKeyHex).lowercase()
        return outputs.filter { it.amountSat == 0L && it.scriptPubKeyHex.lowercase() == want }
            .map { Outpoint(it.vout, it.scriptPubKeyHex) }
    }

    fun locate(outputs: List<Output>, taprootOutputKeyHex: String): Outpoint? {
        val want = (P2TR_PREFIX + taprootOutputKeyHex).lowercase()
        return outputs
            .firstOrNull { it.amountSat == 0L && it.scriptPubKeyHex.lowercase() == want }
            ?.let { Outpoint(it.vout, it.scriptPubKeyHex) }
    }

    /** The bytes of a hex string, or null when it is not hex. */
    fun hexBytes(hex: String): ByteArray? {
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

    /**
     * The cents the transaction's own "DD" OP_RETURN binds to output [vout], or null when that cannot be
     * read. The reading of native BRDigiDollarOutputAmount: the first OP_RETURN whose first push is "DD",
     * then the type push (1 MINT, 2 TRANSFER, 3 REDEEM), then minimal positive script numbers -- all of
     * them for a TRANSFER, the first for the others -- bound in order to the zero-value `OP_1 <32>`
     * outputs. Anything it cannot read is null, never a guess; it is used only to say how much is held.
     */
    fun outputCents(outputs: List<Output>, vout: Int): Long? {
        val ordered = outputs.sortedBy { it.vout }
        // Ordinal binding is positional: a missing or repeated vout would shift which amount binds to
        // which output, so anything but vouts 0..n-1 is unreadable (the caller then holds more).
        if (ordered.withIndex().any { (i, o) -> o.vout != i }) return null
        val scripts = ordered.map { hexBytes(it.scriptPubKeyHex) ?: return null }
        val dd = scripts.firstOrNull { isDdOpReturn(it) } ?: return null
        val pushes = pushesOf(dd) ?: return null
        if (pushes.size < 2) return null
        val type = scriptNum(pushes[1])?.takeIf { pushes[1].size <= 4 }?.toInt() ?: return null
        if (type !in 1..3) return null
        val amounts = ArrayList<Long>()
        for (p in pushes.drop(2)) {
            if (p.isEmpty()) continue
            val v = scriptNum(p) ?: return null
            if (v <= 0L) return null
            amounts += v
            if (type != 2) break
        }
        if (amounts.isEmpty()) return null
        var k = 0
        for ((i, o) in ordered.withIndex()) {
            val s = scripts[i]
            if (s.isNotEmpty() && (s[0].toInt() and 0xFF) == 0x6a) continue
            if (o.amountSat != 0L) continue
            val token = s.size == 34 && s[0] == 0x51.toByte() && s[1] == 0x20.toByte()
            if (o.vout == vout) return if (token) amounts.getOrNull(k) else null
            if (token) k++
        }
        return null
    }

    private fun isDdOpReturn(s: ByteArray): Boolean {
        if (s.size < 4 || (s[0].toInt() and 0xFF) != 0x6a) return false
        val first = pushesOf(s, limit = 1)?.firstOrNull() ?: return false
        return first.size == 2 && first[0] == 0x44.toByte() && first[1] == 0x44.toByte()
    }

    /** The pushes after OP_RETURN, as native _ddNextPush reads them (a non-push opcode is an empty push). */
    private fun pushesOf(s: ByteArray, limit: Int = Int.MAX_VALUE): List<ByteArray>? {
        val out = ArrayList<ByteArray>()
        var pos = 1
        while (pos < s.size && out.size < limit) {
            val op = s[pos].toInt() and 0xFF
            val (hdr, len) = when {
                op == 0x00 -> 1 to 0L
                op in 0x01..0x4b -> 1 to op.toLong()
                op == 0x4c -> { if (pos + 2 > s.size) return null; 2 to (s[pos + 1].toLong() and 0xFF) }
                op == 0x4d -> { if (pos + 3 > s.size) return null; 3 to ((s[pos + 1].toLong() and 0xFF) or ((s[pos + 2].toLong() and 0xFF) shl 8)) }
                op == 0x4e -> { if (pos + 5 > s.size) return null; 5 to (0..3).fold(0L) { a, j -> a or ((s[pos + 1 + j].toLong() and 0xFF) shl (8 * j)) } }
                else -> 1 to 0L
            }
            val start = pos + hdr
            if (len > s.size - start) return null
            out += s.copyOfRange(start, start + len.toInt())
            pos = start + len.toInt()
        }
        return out
    }

    /** Minimal signed little-endian script number of at most 8 bytes, or null. */
    private fun scriptNum(d: ByteArray): Long? {
        if (d.size > 8) return null
        if (d.isEmpty()) return 0L
        val last = d[d.size - 1].toInt() and 0xFF
        if ((last and 0x7f) == 0 && (d.size == 1 || (d[d.size - 2].toInt() and 0x80) == 0)) return null
        var acc = 0L
        for (i in d.indices) acc = acc or ((d[i].toLong() and 0xFF) shl (8 * i))
        if (last and 0x80 != 0) {
            acc = acc and (1L shl (8 * d.size - 1)).inv()
            return -acc
        }
        return acc
    }

    /** Whether there is anything to report. Zero cents is no dollars, not unseen dollars. */
    fun isHolding(cents: Long): Boolean = cents > 0

    /** Cents as dollars, for telling someone what their wallet holds. */
    fun formatCents(cents: Long): String {
        val whole = cents / 100
        val part = (cents % 100).toInt()
        return "$" + String.format("%,d.%02d", whole, part)
    }
}
