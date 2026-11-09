package app.aroundtheblock.wallet.core.asset

import app.aroundtheblock.wallet.core.model.AssetOperation

/**
 * DA-014-REF: the ONE rule for "does this transaction carry a DigiAsset, and which output is the
 * carrier". Every such decision in the wallet goes through [verdict]: detection
 * ([AssetManager]'s carrier choice), the parent checks that decide whether a funding transaction
 * can hold units, the provenance walk's hop, the pre-spend pass and the startup replay. The native
 * reader (BRDigiAsset.c `BRTxAssetCarrier`) implements the same rule, and the two are held to one
 * vector set (`vectors/da-carrier/vectors.txt` in the core submodule).
 *
 * The rule is DigiAsset Core's, the reference indexer:
 *  - `DigiAsset::decodeAssetTxHeader` (DigiAsset.cpp) reads ONE output: the LAST output whose
 *    scriptPubKey type is `nulldata`, of any kind. If that output is not a DigiAsset carrier the
 *    transaction is not a DigiAsset transaction, whatever an earlier output says.
 *  - `nulldata` is DigiByte Core's `Solver` (script/standard.cpp): OP_RETURN followed only by
 *    push opcodes (`IsPushOnly`, every opcode <= OP_16 and every push complete). An OP_RETURN
 *    followed by anything else is `nonstandard` and the reference does not look at it.
 *
 * What the wallet does with it, failing closed:
 *  - [Verdict.None]: no output is tagged "DA" at all. The only answer under which a transaction
 *    is known to carry no asset.
 *  - [Verdict.Carrier]: the last nulldata output is a carrier this reader can classify, and no
 *    tagged output anywhere is unclassifiable. That carrier, and only it, decides the transaction.
 *  - [Verdict.Undecided]: some output is tagged "DA", but the reference reads the transaction as
 *    plain (its last nulldata output is not a carrier, or it has no nulldata output at all), or a
 *    tagged carrier cannot be classified. Nothing is credited, every output is held (the hold rule
 *    reads it as [AssetOperation.UNCLASSIFIABLE]), and as a parent it is "may carry units", never
 *    "no asset" — read as no asset, a plain DGB send could spend a remainder and burn it.
 *
 * Where this holds more than the reference would credit (an unclassifiable carrier beside the
 * reference's carrier), it is the hold direction only: a held output can be released later, an
 * asset spent as plain DGB is gone.
 */
internal object DigiAssetCarrierRule {

    sealed class Verdict {
        /** No output is tagged "DA": a plain transaction. */
        object None : Verdict()

        /** [script] at output [index] is the reference's carrier, decoded as [header]. */
        class Carrier(val index: Int, val script: ByteArray, val header: DecodedAssetHeader) : Verdict()

        /** Tagged somewhere, but no carrier the wallet may credit from: hold everything. */
        object Undecided : Verdict()
    }

    private const val OP_RETURN = 0x6a
    private const val OP_PUSHDATA1 = 0x4c
    private const val OP_PUSHDATA2 = 0x4d
    private const val OP_PUSHDATA4 = 0x4e
    private const val OP_16 = 0x60

    /** [scripts] are a transaction's output scripts in output order (non-OP_RETURN scripts may be
     *  left out: they are neither nulldata nor tagged, so they change nothing). */
    fun verdict(scripts: List<ByteArray>, decoder: DigiAssetDecoder): Verdict {
        var tagged = false
        var unclassifiable = false
        var lastNullData = -1
        var lastNullDataHeader: DecodedAssetHeader? = null
        for ((i, script) in scripts.withIndex()) {
            val header = decoder.decode(script)    // null: not tagged "DA"
            if (header != null) {
                tagged = true
                if (header.operation == AssetOperation.UNCLASSIFIABLE) unclassifiable = true
            }
            if (isNullData(script)) {
                lastNullData = i
                lastNullDataHeader = header
            }
        }
        if (!tagged) return Verdict.None
        val header = lastNullDataHeader
        if (unclassifiable || lastNullData < 0 || header == null) return Verdict.Undecided
        return Verdict.Carrier(lastNullData, scripts[lastNullData], header)
    }

    /** DigiByte Core's `nulldata`: OP_RETURN, then nothing but complete push opcodes. */
    fun isNullData(script: ByteArray): Boolean {
        if (script.isEmpty() || (script[0].toInt() and 0xFF) != OP_RETURN) return false
        var pc = 1
        while (pc < script.size) {
            val op = script[pc++].toInt() and 0xFF
            if (op > OP_16) return false
            val size: Long = when {
                op < OP_PUSHDATA1 -> op.toLong()
                op == OP_PUSHDATA1 -> {
                    if (script.size - pc < 1) return false
                    (script[pc].toLong() and 0xFF).also { pc += 1 }
                }
                op == OP_PUSHDATA2 -> {
                    if (script.size - pc < 2) return false
                    ((script[pc].toLong() and 0xFF) or ((script[pc + 1].toLong() and 0xFF) shl 8)).also { pc += 2 }
                }
                op == OP_PUSHDATA4 -> {
                    if (script.size - pc < 4) return false
                    var v = 0L
                    for (k in 3 downTo 0) v = (v shl 8) or (script[pc + k].toLong() and 0xFF)
                    pc += 4
                    v
                }
                else -> 0L   // OP_1NEGATE, OP_RESERVED, OP_1..OP_16: no data
            }
            if (script.size - pc < size) return false
            pc += size.toInt()
        }
        return true
    }

    /** The scripts of `"<vout>|<satoshis>|<scriptHex>"` bridge lines, in output order, or null
     *  when there is nothing to judge from or any line cannot be read: an output that was not
     *  read could be the carrier. */
    fun scriptsOfLines(lines: Array<out String?>?): List<ByteArray>? {
        if (lines == null || lines.isEmpty()) return null
        val scripts = ArrayList<ByteArray>(lines.size)
        for (line in lines) {
            val hex = line?.split("|", limit = 3)?.getOrNull(2) ?: return null
            scripts += hex.hexToBytesOrNull() ?: return null
        }
        return scripts
    }

    private fun String.hexToBytesOrNull(): ByteArray? {
        if (length % 2 != 0) return null
        val out = ByteArray(length / 2)
        for (i in out.indices) {
            val hi = Character.digit(this[2 * i], 16)
            val lo = Character.digit(this[2 * i + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}
