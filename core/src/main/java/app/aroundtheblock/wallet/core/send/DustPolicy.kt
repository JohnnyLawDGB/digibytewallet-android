package app.aroundtheblock.wallet.core.send

/**
 * The smallest output the network relays, per script type, exactly as DigiByte Core 9.26 decides it.
 *
 * A payment below its line is built, signed and broadcast, and then refused by every node: the send
 * path refuses it before anything is built. The rule is Core's `GetDustThreshold` / `IsDust`
 * (`src/policy/policy.cpp`) at Core's dust relay fee, `DUST_RELAY_TX_FEE` = 30,000 sat/kvB
 * (`src/policy/policy.h`), ten times Bitcoin's:
 *
 * | output                | serialized | + spend | line (sat) |
 * |-----------------------|-----------:|--------:|-----------:|
 * | legacy P2PKH (D…)     |         34 |     148 |      5,460 |
 * | script hash P2SH (S…) |         32 |     148 |      5,400 |
 * | native segwit P2WPKH  |         31 |      67 |      2,940 |
 * | P2WSH, Taproot P2TR   |         43 |      67 |      3,300 |
 * | unspendable (data)    |          — |       — |          0 |
 *
 * DigiDollar token outputs carry no DGB by protocol. Core lets them through below the line — only a
 * zero-value, 34-byte, OP_1 output, and only inside a transaction carrying the DigiDollar version
 * marker with a type Core knows (`IsDigiDollarDustExemptOutput`, and `IsStandardTx`). The same
 * exemption is here: without it this rule would refuse every DigiDollar send. Every other output of
 * a DigiDollar transaction keeps its line.
 */
object DustPolicy {
    /** DigiByte Core's dust relay fee, sat/kvB. */
    const val DUST_RELAY_FEE_PER_KB: Long = 30_000L

    private const val MAX_SCRIPT_SIZE = 10_000
    private const val OP_0 = 0x00
    private const val OP_1 = 0x51
    private const val OP_16 = 0x60
    private const val OP_RETURN = 0x6a

    /** The low 16 bits of a DigiDollar transaction's version. */
    private const val DD_VERSION_MARKER = 0x0770

    /** The dust line for an output paying [script], in satoshis (Core's `GetDustThreshold`). */
    fun threshold(script: ByteArray): Long {
        if ((script.isNotEmpty() && (script[0].toInt() and 0xff) == OP_RETURN) || script.size > MAX_SCRIPT_SIZE) return 0L
        var size = 8L + compactSizeLength(script.size) + script.size
        // Spending it: a P2WPKH input (witness discounted 75%) for a witness program, else the 148 bytes of a P2PKH input.
        size += if (isWitnessProgram(script)) 32 + 4 + 1 + 107 / 4 + 4 else 32 + 4 + 1 + 107 + 4
        // CFeeRate::GetFee: the fee for `size` bytes at the rate, rounded up.
        return (DUST_RELAY_FEE_PER_KB * size + 999) / 1000
    }

    /** True for a transaction version Core reads as a DigiDollar transaction: the marker, and a type of 1-3. */
    fun isDigiDollarTransaction(version: Int): Boolean =
        (version and 0xFFFF) == DD_VERSION_MARKER && ((version ushr 24) and 0xFF) in 1..3

    /** True for a DigiDollar token output, which Core relays below the line inside a DigiDollar transaction. */
    fun isDigiDollarExempt(valueSats: Long, script: ByteArray, txIsDigiDollar: Boolean): Boolean =
        txIsDigiDollar && valueSats == 0L && script.size == 34 && (script[0].toInt() and 0xff) == OP_1

    /** True when the output is below its line and not exempt: every node would refuse the transaction. */
    fun isDust(valueSats: Long, script: ByteArray, txIsDigiDollar: Boolean): Boolean =
        valueSats < threshold(script) && !isDigiDollarExempt(valueSats, script, txIsDigiDollar)

    /** Core's `CScript::IsWitnessProgram`: a version opcode, then one push of 2-40 bytes that is the whole rest. */
    private fun isWitnessProgram(script: ByteArray): Boolean {
        if (script.size < 4 || script.size > 42) return false
        val version = script[0].toInt() and 0xff
        if (version != OP_0 && (version < OP_1 || version > OP_16)) return false
        return (script[1].toInt() and 0xff) + 2 == script.size
    }

    private fun compactSizeLength(n: Int): Int = when {
        n < 253 -> 1
        n <= 0xFFFF -> 3
        else -> 5
    }
}
