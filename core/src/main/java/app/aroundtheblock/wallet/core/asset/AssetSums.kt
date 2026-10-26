package app.aroundtheblock.wallet.core.asset

/**
 * Sums of asset quantities that never wrap (4.1.2 row 27). A single quantity can reach Long.MAX_VALUE
 * (an issuer chooses it), so two such rows summed with `sumOf` went negative: every send of that asset
 * was refused ("not enough asset") -- an issuer could block sends of its asset.
 *
 *  - [saturating]: for decisions (is there enough? which group is largest?): an overflowing total is
 *    as large as a Long can say, which is "enough" for any quantity a send can ask for.
 *  - [exactOrNull]: for the transfer itself, where the total becomes an instruction's amount: no
 *    approximation may reach a transaction, so an overflowing total refuses the plan.
 */
object AssetSums {
    fun add(a: Long, b: Long): Long {
        val r = a + b
        // The sum left the Long range iff both operands have the sign the result lacks.
        return if (((a xor r) and (b xor r)) < 0) (if (a < 0) Long.MIN_VALUE else Long.MAX_VALUE) else r
    }

    fun saturating(values: Iterable<Long>): Long = values.fold(0L) { acc, v -> add(acc, v) }

    fun exactOrNull(values: Iterable<Long>): Long? = try {
        values.fold(0L) { acc, v -> Math.addExact(acc, v) }
    } catch (_: ArithmeticException) {
        null
    }
}
