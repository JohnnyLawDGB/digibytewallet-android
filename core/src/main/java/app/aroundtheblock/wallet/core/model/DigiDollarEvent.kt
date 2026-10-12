package app.aroundtheblock.wallet.core.model

/**
 * What a DigiDollar transaction did for this wallet, as native code reads it from the transaction itself
 * ([app.aroundtheblock.wallet.core.bridge.NativeBridge.digiDollarEvent]): its DigiDollar type, and whose
 * outputs it pays and spends — never the sign of its DGB movement, which says the opposite of what a mint
 * (DGB locked as collateral while dollars arrive) or a redemption (collateral back while dollars are
 * burned) does, and only the fee of a transfer to the wallet's own address.
 *
 * The activity list labels a DigiDollar row from [kind] and shows [cents].
 */
data class DigiDollarEvent(
    val kind: Kind,
    /** What was minted, sent, received, moved to the wallet itself, or burned. */
    val cents: Long,
    /** DigiDollar paid back to the wallet as change (sent transfers and redemptions). */
    val changeCents: Long,
) {
    enum class Kind(val code: Long) {
        /** Not a DigiDollar transaction, or one that moved none of this wallet's dollars. */
        NONE(0),
        /** A mint: dollars created. */
        MINTED(1),
        /** A transfer this wallet paid for, to someone else. */
        SENT(2),
        /** A transfer this wallet did not pay for, paying it dollars. */
        RECEIVED(3),
        /** A transfer this wallet paid for in which every dollar came back to it. */
        SELF_TRANSFER(4),
        /** A redemption: dollars burned. */
        REDEEMED(5),
    }

    companion object {
        /** The bridge's `[kind, cents, changeCents]`, or null when there is none or it does not read as one. */
        fun of(raw: LongArray?): DigiDollarEvent? {
            if (raw == null || raw.size != 3 || raw[1] < 0L || raw[2] < 0L) return null
            val kind = Kind.entries.firstOrNull { it.code == raw[0] } ?: return null
            return DigiDollarEvent(kind, raw[1], raw[2])
        }
    }
}
