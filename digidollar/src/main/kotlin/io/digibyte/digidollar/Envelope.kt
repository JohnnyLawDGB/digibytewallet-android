package io.digibyte.digidollar

/**
 * The three DigiDollar transaction types, as encoded in the nVersion top byte.
 * Mirrors DigiByte Core v9.26.4 consensus (1=Mint, 2=Transfer, 3=Redemption).
 */
enum class DigiDollarTxType(val code: Int) {
    MINT(1),
    TRANSFER(2),
    REDEMPTION(3),
}

/**
 * DigiDollar nVersion envelope: marker 0x0770 in the low 16 bits, tx type in
 * the top byte.
 *
 * Build-only: detection/parsing of incoming transactions is the C core's job
 * (upstream ships a golden-vector-proven decoder). The test source set keeps
 * a parse cross-check ([CrossCheckParse]) against the Core-built fixtures.
 */
object DigiDollarVersion {

    internal const val MARKER = 0x0770

    /** The nVersion for a DigiDollar transaction of the given [type]. */
    fun build(type: DigiDollarTxType): Int = (type.code shl 24) or MARKER
}
