package io.digibyte.core.asset

object DeadSendPredicate {
    const val DUST_FLOOR = 5460L               // conservative legacy dust floor
    /** A parsed output value; OP_RETURN outputs are value 0 and ignored. */
    data class OutSats(val sats: Long)
    /** Dead iff conflicted OR carries a sub-dust (non-relayable) output. Caller
     *  passes only UNCONFIRMED txs; confirmed txs are filtered upstream. */
    fun isDead(isValid: Boolean, outputs: List<OutSats>): Boolean =
        !isValid || outputs.any { it.sats in 1 until DUST_FLOOR }
}
