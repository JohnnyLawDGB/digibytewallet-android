package io.digibyte.digidollar

/**
 * Mint transaction assembly (unsigned), mirroring Core v9.26.4's
 * MintTxBuilder output layout exactly:
 *
 *   vout 0  Collateral P2TR (NUMS + two-leaf MAST), the required Collateral
 *   vout 1  DD-token P2TR (Owner key, key-path only), value 0
 *   vout 2  OP_RETURN Mint metadata
 *   vout 3  P2WPKH change (consensus forbids a second value-bearing P2TR)
 *
 * The unlock height commits to tip + 1 + a 100-block confirmation buffer +
 * the Lock tier's blocks. Signing is a separate step (the digest goes to
 * the native signer per ADR-0001).
 */
object MintBuilder {

    /** Consensus confirmation buffer added to the unlock height. */
    const val LOCK_CONFIRMATION_BUFFER_BLOCKS = 100

    /** Default Mint fee: 0.119 DGB (above the 0.1 DGB DigiDollar floor). */
    const val DEFAULT_FEE_SATS = 11_900_000L

    /** Change below 0.001 DGB is folded into the fee instead of creating a
     *  near-dust output — mirrors the digidollar-js reference (txbuild.js
     *  CHANGE_FOLD_SATS): negligible value, guaranteed dust under any DGB
     *  dust policy. */
    const val CHANGE_FOLD_SATS = 100_000L

    /** The coin funding a Mint. */
    data class FundingUtxo(val txidHex: String, val vout: Int, val valueSats: Long)

    /** An assembled, unsigned Mint. */
    data class UnsignedMint(
        val tx: Transaction,
        val unlockHeight: Int,
        val collateralSats: Long,
        val changeSats: Long,
    )

    @Suppress("LongParameterList")
    fun buildUnsigned(
        fundingUtxo: FundingUtxo,
        ddCents: Long,
        tier: LockTier,
        oraclePriceMicroUsd: Long,
        tipHeight: Int,
        feeSats: Long,
        ownerKeyHex: String,
        changePubKeyHash160Hex: String,
        ecOps: EcOps,
        dcaMultiplierBps: Long = Collateral.DCA_BPS_SCALE,
    ): UnsignedMint {
        require(ddCents >= DigiDollarLimits.MIN_MINT_CENTS) {
            "Mint below the consensus minimum of ${DigiDollarLimits.MIN_MINT_CENTS} cents"
        }
        require(ddCents <= DigiDollarLimits.MAX_MINT_CENTS) {
            "Mint above the consensus maximum of ${DigiDollarLimits.MAX_MINT_CENTS} cents"
        }
        require(feeSats >= DigiDollarLimits.MIN_DD_TX_FEE_SATS) {
            "fee below the 0.1 DGB DigiDollar floor"
        }
        val changeHash = changePubKeyHash160Hex.hexToByteArray()
        require(changeHash.size == 20) { "change hash160 must be 20 bytes" }

        val collateralSats = Collateral.requiredSats(
            ddCents = ddCents,
            tier = tier,
            oraclePriceMicroUsd = oraclePriceMicroUsd,
            dcaMultiplierBps = dcaMultiplierBps,
        )
        val rawChangeSats = fundingUtxo.valueSats - collateralSats - feeSats
        require(rawChangeSats >= 0) {
            "funding (${fundingUtxo.valueSats} sats) cannot cover Collateral " +
                "($collateralSats) + fee ($feeSats)"
        }
        val changeSats = if (rawChangeSats < CHANGE_FOLD_SATS) 0 else rawChangeSats

        val unlockHeight = tipHeight + 1 + LOCK_CONFIRMATION_BUFFER_BLOCKS + tier.lockBlocks

        val collateralKey = Taproot.collateralOutputKey(
            lockHeight = unlockHeight,
            ddCents = ddCents,
            ownerKeyHex = ownerKeyHex,
            ecOps = ecOps,
        )
        val ddTokenKey = Taproot.ddTokenOutputKey(ownerKeyHex, ecOps)
        val metadata = MintMetadata(
            ddCents = ddCents,
            unlockHeight = unlockHeight,
            lockTier = tier.index,
            ownerKeyHex = ownerKeyHex,
        ).build()

        val outputs = buildList {
            add(TxOutput(collateralSats, "5120$collateralKey"))
            add(TxOutput(0, "5120$ddTokenKey"))
            add(TxOutput(0, metadata))
            if (changeSats > 0) add(TxOutput(changeSats, "0014$changePubKeyHash160Hex"))
        }

        val tx = Transaction(
            version = DigiDollarVersion.build(DigiDollarTxType.MINT),
            locktime = 0,
            inputs = listOf(
                TxInput(fundingUtxo.txidHex, fundingUtxo.vout, sequence = 0xffffffffL),
            ),
            outputs = outputs,
        )
        return UnsignedMint(tx, unlockHeight, collateralSats, changeSats)
    }
}
