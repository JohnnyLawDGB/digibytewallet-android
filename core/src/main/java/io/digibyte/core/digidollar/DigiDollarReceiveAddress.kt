package io.digibyte.core.digidollar

import io.digibyte.digidollar.DdAddress
import io.digibyte.digidollar.EcOps
import io.digibyte.digidollar.Taproot

/**
 * The wallet's DigiDollar receive address (issue #18 receive-screen half).
 *
 * DigiDollar is sent to the tap-tweaked DD-token output key of the receiver's
 * BIP86 Owner key — the same key a Mint pays its token output to. This encodes
 * that key as a `TD…`/`DD…` address others paste into `senddigidollar`.
 *
 * The Owner key is always on the wallet's watched chain m/86'/20'/0'/0/0
 * ([MintService.OWNER_KEY_COIN_TYPE]); the tweak must run through the real
 * [EcOps] (native secp256k1) so the address matches what the node derives.
 * [DdAddress.encode] takes the FINAL tweaked key — never the raw Owner key.
 */
object DigiDollarReceiveAddress {

    /** @throws IllegalArgumentException if [ownerKeyHex] is not a 32-byte
     *  x-only key (mapped by callers to a null/unavailable address). */
    fun forOwnerKey(ownerKeyHex: String, ecOps: EcOps, testnet: Boolean): String {
        val outputKeyHex = Taproot.ddTokenOutputKey(ownerKeyHex, ecOps)
        val outputKey = outputKeyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val network = if (testnet) DdAddress.Network.TESTNET else DdAddress.Network.MAINNET
        return DdAddress.encode(outputKey, network)
    }
}
