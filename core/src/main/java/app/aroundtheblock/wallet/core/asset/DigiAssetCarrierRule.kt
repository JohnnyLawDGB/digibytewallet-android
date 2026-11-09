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
 * One more reference behaviour decides the shape of the rule: DigiAsset Core's transaction reader
 * (DigiByteTransaction.cpp, the constructor's dataIndex) takes the FIRST nulldata output and offers
 * it to the KYC, exchange-rate and encrypted-key readers BEFORE the asset reader runs, and a match
 * there ends the reading: such a transaction is no asset transaction, whatever its last nulldata
 * output says. The wallet refuses to credit any transaction whose first nulldata output is not
 * the carrier itself. Two of those readers can also take a LONE carrier:
 *  - the encrypted-key reader (decodeEncryptedKeyTx) reads no content at all: one input, two or
 *    three outputs, output 0 worth exactly [ENCRYPTED_KEY_SATS], the nulldata output at index 1.
 *    A carrier in that shape is [Verdict.Undecided]. The shape needs the input count and output
 *    0's amount ([TxShape]); where a caller cannot supply them they are unknown, and an unknown
 *    counts as matching (fail closed).
 *  - the exchange-rate reader also needs the indexer's own watch list, which the wallet does not
 *    have. Not reproduced here.
 * KYC cannot take a carrier: its tag check reads the "DA" magic as "a#x", never "kyc".
 *
 * THE INVARIANT (DA-014-REF), failing closed:
 *  - [Verdict.None]: no output is tagged "DA". The only answer under which a transaction is known
 *    to carry no asset.
 *  - [Verdict.Carrier]: exactly one output is nulldata, exactly one output is tagged "DA", they
 *    are the same output, this reader can classify it, and the transaction is not in the
 *    encrypted-key shape above. That carrier decides the transaction. It is both the reference's
 *    last nulldata output and its first, so no script-only reader can take the transaction before
 *    the asset reader sees it.
 *  - [Verdict.Undecided]: anything else with a tagged output -- a second nulldata output of any
 *    kind, a second tagged output, a tagged output that is not nulldata, a carrier that cannot be
 *    classified (framing or header). Nothing is credited, every output is held (the hold rule
 *    reads it as [AssetOperation.UNCLASSIFIABLE]), and as a parent it is "may carry units", never
 *    "no asset": read as no asset, a plain DGB send could spend a remainder and burn it.
 *
 * Where this holds more than the reference would credit (two OP_RETURN outputs, which DigiByte
 * Core does not relay as standard anyway), it is the hold direction only: a held output can be
 * released later, an asset spent as plain DGB is gone.
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

    /** What DigiAsset Core's encrypted-key reader requires of output 0, in satoshis. */
    const val ENCRYPTED_KEY_SATS = 650L

    /** The parts of a transaction beyond its scripts that the rule needs: its input count and
     *  every output's amount, each null when the caller could not read it. The input count is
     *  read lazily, only for a transaction whose scripts and amounts already match the
     *  encrypted-key shape, so the rule costs no extra bridge read anywhere else. */
    class TxShape(inputs: () -> Int?, val outputAmounts: List<Long>?) {
        val inputCount: Int? by lazy(inputs)
        companion object {
            val UNKNOWN = TxShape({ null }, null)
            fun of(inputCount: Int?, outputAmounts: List<Long>?) = TxShape({ inputCount }, outputAmounts)
        }
    }

    private const val OP_RETURN = 0x6a
    private const val OP_PUSHDATA1 = 0x4c
    private const val OP_PUSHDATA2 = 0x4d
    private const val OP_PUSHDATA4 = 0x4e
    private const val OP_16 = 0x60

    /** [scripts] are ALL of a transaction's output scripts, in output order (the encrypted-key
     *  shape is positional). [shape] carries its input count and amounts; unknown parts fail
     *  closed. */
    fun verdict(scripts: List<ByteArray>, decoder: DigiAssetDecoder, shape: TxShape = TxShape.UNKNOWN): Verdict {
        var tagged = 0
        var nullData = 0
        var carrier = -1
        var carrierHeader: DecodedAssetHeader? = null
        for ((i, script) in scripts.withIndex()) {
            val header = decoder.decode(script)    // null: not tagged "DA"
            val isNull = isNullData(script)
            if (isNull) nullData++
            if (header != null) {
                tagged++
                if (isNull) { carrier = i; carrierHeader = header }
            }
        }
        if (tagged == 0) return Verdict.None
        val header = carrierHeader
        if (tagged != 1 || nullData != 1 || header == null ||
            header.operation == AssetOperation.UNCLASSIFIABLE) return Verdict.Undecided
        if (encryptedKeyShape(carrier, scripts.size, shape)) return Verdict.Undecided
        return Verdict.Carrier(carrier, scripts[carrier], header)
    }

    /** DigiAsset Core decodeEncryptedKeyTx's test, with unknown parts read as matching. */
    private fun encryptedKeyShape(carrier: Int, outputCount: Int, shape: TxShape): Boolean {
        if (carrier != 1 || outputCount < 2 || outputCount > 3) return false
        val first = shape.outputAmounts?.takeIf { it.size == outputCount }?.get(0)
        if (first != null && first != ENCRYPTED_KEY_SATS) return false
        val inputs = shape.inputCount
        return inputs == null || inputs == 1
    }

    /** The amounts of `"<vout>|<satoshis>|<scriptHex>"` lines, or null when any is unreadable. */
    fun amountsOfLines(lines: Array<out String?>?): List<Long>? {
        if (lines == null || lines.isEmpty()) return null
        return lines.map { it?.split("|", limit = 3)?.getOrNull(1)?.toLongOrNull() ?: return null }
    }

    /** The rule's [TxShape] from output lines and an input count read only if needed. */
    fun shapeOf(lines: Array<out String?>?, inputCount: () -> Int?): TxShape = TxShape(inputCount, amountsOfLines(lines))

    /** The input count of a serialized transaction (segwit marker aware), or null. */
    fun inputCountOfRawTx(rawTx: ByteArray): Int? {
        var p = 4
        if (rawTx.size < 5) return null
        if (rawTx.size > 6 && rawTx[4] == 0x00.toByte() && rawTx[5] == 0x01.toByte()) p = 6
        if (p >= rawTx.size) return null
        val b = rawTx[p].toInt() and 0xFF
        val width = when (b) { 0xFD -> 2; 0xFE -> 4; 0xFF -> 8; else -> 0 }
        if (width == 0) return b
        if (p + width >= rawTx.size) return null
        var v = 0L
        for (k in width downTo 1) v = (v shl 8) or (rawTx[p + k].toLong() and 0xFF)
        return if (v in 0..Int.MAX_VALUE.toLong()) v.toInt() else null
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
