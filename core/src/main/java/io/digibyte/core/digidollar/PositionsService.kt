package io.digibyte.core.digidollar

import io.digibyte.digidollar.MintMetadata

/**
 * Collateral positions (issue #10), recovered from the wallet's own Mint
 * transactions — no local persistence, so positions survive a seed restore.
 *
 * [io.digibyte.core.bridge.NativeBridge.listDigiDollarMints] emits one line
 * per wallet Mint transaction (`txid:vout:valueSats:blockHeight:spent:opReturnHex`,
 * where `vout` is the collateral output and `spent` says whether any wallet
 * transaction consumed it). The Mint OP_RETURN — our own build format —
 * carries the position's ddCents, unlock height, and Owner key; a position is
 * ours only if that key is the one key the wallet mints with,
 * m/86'/20'/0'/0/0 ([MintService.OWNER_KEY_COIN_TYPE], chain 0, index 0).
 */
class PositionsService(private val wallet: WalletPort) {

    /** The wallet surfaces the scan needs, seamed over NativeBridge. */
    interface WalletPort {
        /** Raw [io.digibyte.core.bridge.NativeBridge.listDigiDollarMints] lines. */
        fun listDigiDollarMints(): String

        /** BIP86 Owner key m/86'/coinType'/0'/chain/index, x-only 32 bytes. */
        fun deriveOwnerKey(coinType: Int, chain: Int, index: Int): ByteArray?
    }

    /** The wallet's open (unspent) collateral positions. */
    fun listOpenPositions(): List<RedemptionService.CollateralPosition> {
        val ownerKeyHex = wallet.deriveOwnerKey(MintService.OWNER_KEY_COIN_TYPE, 0, 0)
            ?.joinToString("") { "%02x".format(it) }
            ?: return emptyList()

        return wallet.listDigiDollarMints().lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { parseLine(it, ownerKeyHex) }
            .sortedBy { it.lockHeight } // soonest-unlocking first
            .toList()
    }

    /** One line → one open position; null skips it (spent, foreign Owner key,
     *  or malformed — a bad entry must never take down the whole scan). */
    @Suppress("SwallowedException", "ReturnCount")
    private fun parseLine(
        line: String,
        ownerKeyHex: String,
    ): RedemptionService.CollateralPosition? {
        val parts = line.split(':')
        if (parts.size != LINE_FIELDS) return null
        if (parts[4] != "0") return null // collateral already spent — position closed
        return try {
            val metadata = MintMetadata.parse(parts[5])
            if (metadata.ownerKeyHex != ownerKeyHex) return null
            RedemptionService.CollateralPosition(
                txidHex = parts[0],
                vout = parts[1].toInt(),
                valueSats = parts[2].toLong(),
                lockHeight = metadata.unlockHeight,
                ddCents = metadata.ddCents,
                ownerChain = 0,
                ownerIndex = 0,
            )
        } catch (e: IllegalArgumentException) {
            null // not a well-formed Mint line — skip
        }
    }

    private companion object {
        const val LINE_FIELDS = 6
    }
}
