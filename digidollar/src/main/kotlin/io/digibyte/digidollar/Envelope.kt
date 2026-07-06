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
 */
object DigiDollarVersion {

    private const val MARKER = 0x0770

    /** The DigiDollar tx type of [version], or null if not DigiDollar-marked. */
    fun parse(version: Int): DigiDollarTxType? {
        if (version and 0xffff != MARKER) return null
        val code = (version ushr 24) and 0xff
        return DigiDollarTxType.entries.firstOrNull { it.code == code }
    }

    /** The nVersion for a DigiDollar transaction of the given [type]. */
    fun build(type: DigiDollarTxType): Int = (type.code shl 24) or MARKER
}
