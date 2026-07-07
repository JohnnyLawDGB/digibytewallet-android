package io.digibyte.digidollar

/**
 * Witness assembly for a Redemption. Computes each taproot input's BIP341
 * digest from the UnsignedRedemption's own transaction and prevouts, obtains
 * BIP340 signatures through [schnorrSign] (production: the native seed-bound
 * signer; tests: Core's fixture signatures), and attaches the stacks:
 *
 *   vin 0    [signature, leaf script, control block] — script-path spend of
 *            the normal Redemption leaf, signed with the UNTWEAKED Owner key
 *            (tapTweak = false)
 *   vin 1…   [signature] — key-path DD-token burns, signed with the
 *            tap-tweaked Owner key (tapTweak = true)
 *   vin N    the fee input's stack, supplied by the wallet signer (empty
 *            until then — see [WalletSignerFormat])
 *
 * SIGHASH_DEFAULT everywhere: 64-byte signatures, no trailing hash-type byte.
 */
object RedemptionSigning {

    /** [schnorrSign] receives (inputIndex, digest, tapTweak) — the index lets
     *  the production signer route each DD burn to its own Owner-key
     *  derivation (received vs change tokens sit at different keys). */
    fun witnessedTx(
        unsigned: RedemptionBuilder.UnsignedRedemption,
        schnorrSign: (inputIndex: Int, digest: ByteArray, tapTweak: Boolean) -> ByteArray,
        feeWitness: List<ByteArray> = emptyList(),
    ): Transaction {
        val tx = unsigned.tx
        val feeIndex = tx.inputs.size - 1
        val witnesses = tx.inputs.indices.map { i ->
            when {
                i == 0 -> {
                    val digest = Sighash.taproot(
                        tx,
                        unsigned.prevouts,
                        inputIndex = 0,
                        leafHash = Taproot.tapLeafHash(unsigned.leafScriptHex),
                    )
                    listOf(
                        signature(schnorrSign(0, digest, false)),
                        unsigned.leafScriptHex.hexToByteArray(),
                        unsigned.controlBlockHex.hexToByteArray(),
                    )
                }
                i < feeIndex -> {
                    val digest = Sighash.taproot(tx, unsigned.prevouts, i, leafHash = null)
                    listOf(signature(schnorrSign(i, digest, true)))
                }
                else -> feeWitness
            }
        }
        return tx.copy(witnesses = witnesses)
    }

    private fun signature(sig: ByteArray): ByteArray {
        require(sig.size == 64) { "BIP340 signature must be 64 bytes, got ${sig.size}" }
        return sig
    }
}
