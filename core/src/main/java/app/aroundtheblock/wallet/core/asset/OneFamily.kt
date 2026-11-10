package app.aroundtheblock.wallet.core.asset

/**
 * ONE-FAMILY: an output belongs to at most one token family. The Kotlin half of the rule the native
 * wallet applies in `_BRWalletUpdateBalance`, `BRWalletOutpointAssetState` (OTHER_FAMILY, -3), coin
 * selection and the asset-send signer (BRWallet.c `_BRTxIsDualFamily` / `_BRTxOutputOneFamilyHeld`).
 *
 * Precedence: DigiDollar is decided by DigiByte Core's consensus (a transaction with the DD nVersion
 * marker is validated by `ValidateDigiDollarTransaction`, and its zero-value P2TR outputs bound to amount
 * slots ARE DigiDollar). The DigiAsset layer is an indexer reading of the same chain: DigiAsset Core reads
 * no nVersion and credits whatever output its instructions name. So a DigiDollar output is never an
 * asset holding, and a transaction marked as both is decided by neither: it is held, credited to no
 * balance and offered to no send at all, the way [DigiAssetCarrierRule.Verdict.Undecided] is held.
 * Consensus lets a transaction without the DD marker spend a DD output, so spending one through the
 * asset, plain or sweep path destroys the dollars on it.
 *
 * Every test here only ever HOLDS more; none credits anything.
 */
internal object OneFamily {

    /** `(nVersion & 0xFFFF)` of every DigiDollar transaction (DigiByte Core `DD_TX_VERSION`). */
    const val DD_VERSION_MARKER = 0x0770

    private const val OP_RETURN = 0x6a
    private const val OP_DIGIDOLLAR = 0xbb
    private const val OP_PUSHDATA1 = 0x4c
    private const val OP_PUSHDATA2 = 0x4d
    private const val OP_PUSHDATA4 = 0x4e

    fun versionMarksDigiDollar(version: Int): Boolean = (version and 0xFFFF) == DD_VERSION_MARKER

    /** The nVersion of a serialized transaction (its first four bytes, little-endian), or null. */
    fun rawTxVersion(rawTx: ByteArray): Int? {
        if (rawTx.size < 4) return null
        return (rawTx[0].toInt() and 0xFF) or ((rawTx[1].toInt() and 0xFF) shl 8) or
            ((rawTx[2].toInt() and 0xFF) shl 16) or ((rawTx[3].toInt() and 0xFF) shl 24)
    }

    /** True when the serialized transaction carries the DigiDollar nVersion marker; null if unreadable. */
    fun rawTxMarksDigiDollar(rawTx: ByteArray): Boolean? = rawTxVersion(rawTx)?.let(::versionMarksDigiDollar)

    /**
     * An OP_RETURN in either form DigiByte Core's `FindDDOpReturn` accepts: `OP_RETURN OP_DIGIDOLLAR …`,
     * or `OP_RETURN` whose first push is exactly the two bytes "DD" (any push encoding, as native
     * `_ddFindDDOpReturn` reads it). Script-only: where the nVersion is not to hand, a "DD" OP_RETURN
     * stands in for the marker, which only ever holds more.
     */
    fun scriptsMarkDigiDollar(scripts: List<ByteArray>): Boolean = scripts.any(::isDigiDollarOpReturn)

    private fun isDigiDollarOpReturn(s: ByteArray): Boolean {
        if (s.size < 2 || (s[0].toInt() and 0xFF) != OP_RETURN) return false
        val op = s[1].toInt() and 0xFF
        if (op == OP_DIGIDOLLAR) return true
        val (off, len) = when {
            op in 0x01..0x4b -> 2 to op.toLong()
            op == OP_PUSHDATA1 && s.size >= 3 -> 3 to (s[2].toLong() and 0xFF)
            op == OP_PUSHDATA2 && s.size >= 4 -> 4 to ((s[2].toLong() and 0xFF) or ((s[3].toLong() and 0xFF) shl 8))
            op == OP_PUSHDATA4 && s.size >= 6 -> 6 to ((0..3).fold(0L) { a, k -> a or ((s[2 + k].toLong() and 0xFF) shl (8 * k)) })
            else -> return false
        }
        return len == 2L && s.size >= off + 2 && s[off] == 0x44.toByte() && s[off + 1] == 0x44.toByte()
    }

    /** A DigiDollar token output's shape: zero value, `OP_1 <32-byte push>` (BRDigiDollarOutputAmount). */
    fun isDigiDollarTokenShape(amountSats: Long?, script: ByteArray): Boolean =
        amountSats == 0L && script.size == 34 && script[0] == 0x51.toByte() && script[1] == 0x20.toByte()

    /**
     * The transaction is held under ONE-FAMILY as a whole: it carries a DigiDollar marking (the nVersion
     * marker when [ddMarker] is true, or a "DD" OP_RETURN) AND a DigiAsset tag (any carrier verdict but
     * [DigiAssetCarrierRule.Verdict.None]).
     */
    fun isDualFamily(scripts: List<ByteArray>, decoder: DigiAssetDecoder, ddMarker: Boolean? = null): Boolean =
        (ddMarker == true || scriptsMarkDigiDollar(scripts)) &&
            DigiAssetCarrierRule.verdict(scripts, decoder) != DigiAssetCarrierRule.Verdict.None

    /**
     * Output [vout] of the transaction listed by `"<vout>|<sats>|<scriptHex>"` [lines] may be spent by no
     * asset, plain or sweep send: the transaction is dual-family, or it is DigiDollar-marked and the output
     * has the token shape. Null when the lines cannot be read.
     */
    fun outputHeld(lines: Array<out String?>?, vout: Int, decoder: DigiAssetDecoder, ddMarker: Boolean? = null): Boolean? {
        val scripts = DigiAssetCarrierRule.scriptsOfLines(lines) ?: return null
        if (isDualFamily(scripts, decoder, ddMarker)) return true
        val marked = ddMarker == true || scriptsMarkDigiDollar(scripts)
        if (!marked || vout < 0 || vout >= scripts.size) return false
        val amount = lines?.getOrNull(vout)?.split("|", limit = 3)?.getOrNull(1)?.toLongOrNull()
        return isDigiDollarTokenShape(amount, scripts[vout])
    }
}
