package io.digibyte.digidollar

/**
 * Redemption transaction assembly (unsigned), mirroring Core v9.26.4's
 * RedeemTxBuilder::BuildRedemptionTransaction (proven byte-exact against
 * the Core-built redeem fixture):
 *
 *   vin 0   Collateral P2TR — SCRIPT-PATH spend of the normal Redemption
 *           leaf, sequence 0xfffffffe (CLTV-enabled)
 *   vin 1+  DD-token burns (zero-satoshi P2TR, key-path), sequence 0xfffffffe
 *   vin N   DGB fee input, sequence 0xffffffff
 *   vout 0  full Collateral back to the Owner's key-path P2TR
 *   vout …  DD-change P2TR + OP_RETURN Redemption metadata — only when the
 *           burn exceeds the Mint's amount (exact burns carry no OP_RETURN)
 *   vout …  DGB change — last
 *   nLockTime = the Mint's unlock height (consensus: height >= nLockTime)
 *
 * Full redemption only: the burned DD inputs must cover the Mint's whole
 * amount. Signing is a separate step — the collateral digest is script-path
 * (untweaked Owner key, per ADR-0001 via the native signer's tapTweak=false).
 */
object RedemptionBuilder {

    /** The Mint's Collateral outpoint (always the Mint's vout 0) plus the
     *  Mint parameters that shape the redemption. */
    data class CollateralUtxo(
        val txidHex: String,
        val vout: Int,
        val valueSats: Long,
        val lockHeight: Int,
        val ddCents: Long,
    )

    /** A DD-token UTXO to burn (a zero-satoshi P2TR). [scriptPubKeyHex] is
     *  its prevout script — DD tokens the wallet owns may sit at different
     *  output keys (e.g. received vs change), so each carries its own. */
    data class DdTokenUtxo(
        val txidHex: String,
        val vout: Int,
        val ddCents: Long,
        val scriptPubKeyHex: String,
    )

    /** The DGB input paying the fee. [scriptPubKeyHex] is its prevout script
     *  (needed for the sighash — typically `0014…` P2WPKH). */
    data class FeeUtxo(
        val txidHex: String,
        val vout: Int,
        val valueSats: Long,
        val scriptPubKeyHex: String,
    )

    /** An assembled, unsigned Redemption plus everything signing needs. */
    data class UnsignedRedemption(
        val tx: Transaction,
        val prevouts: List<TxOutput>,
        val leafScriptHex: String,
        val controlBlockHex: String,
        val ddChangeCents: Long,
        val dgbChangeSats: Long,
    )

    /**
     * [ownerKeyHex] is the Mint's untweaked Owner key — it shapes the leaf,
     * the control block, and the DD-change destination. The Collateral
     * return destination is the redeemer's own choice of address
     * ([collateralReturnScriptPubKeyHex]) — Core's builder pays a fresh
     * wallet address, not a key derived from the Mint.
     */
    @Suppress("LongParameterList")
    fun buildUnsigned(
        collateralUtxo: CollateralUtxo,
        ddUtxos: List<DdTokenUtxo>,
        feeUtxo: FeeUtxo,
        feeSats: Long,
        ownerKeyHex: String,
        collateralReturnScriptPubKeyHex: String,
        dgbChangeScriptPubKeyHex: String,
        ecOps: EcOps,
    ): UnsignedRedemption {
        require(feeSats >= DigiDollarLimits.MIN_DD_TX_FEE_SATS) {
            "fee below the 0.1 DGB DigiDollar floor"
        }
        val totalDdIn = ddUtxos.sumOf { it.ddCents }
        val ddChangeCents = totalDdIn - collateralUtxo.ddCents
        require(ddChangeCents >= 0) {
            "DD inputs ($totalDdIn cents) must cover the full minted amount " +
                "(${collateralUtxo.ddCents} cents) — full redemption only"
        }
        val rawDgbChangeSats = feeUtxo.valueSats - feeSats
        require(rawDgbChangeSats >= 0) {
            "fee input (${feeUtxo.valueSats} sats) cannot cover the fee ($feeSats)"
        }
        val dgbChangeSats =
            if (rawDgbChangeSats < MintBuilder.CHANGE_FOLD_SATS) 0 else rawDgbChangeSats

        val collateralScript = "5120" + Taproot.collateralOutputKey(
            lockHeight = collateralUtxo.lockHeight,
            ddCents = collateralUtxo.ddCents,
            ownerKeyHex = ownerKeyHex,
            ecOps = ecOps,
        )

        val inputs = buildList {
            add(TxInput(collateralUtxo.txidHex, collateralUtxo.vout, sequence = 0xfffffffeL))
            for (dd in ddUtxos) add(TxInput(dd.txidHex, dd.vout, sequence = 0xfffffffeL))
            add(TxInput(feeUtxo.txidHex, feeUtxo.vout, sequence = 0xffffffffL))
        }
        val prevouts = buildList {
            add(TxOutput(collateralUtxo.valueSats, collateralScript))
            for (dd in ddUtxos) add(TxOutput(0, dd.scriptPubKeyHex))
            add(TxOutput(feeUtxo.valueSats, feeUtxo.scriptPubKeyHex))
        }
        val outputs = buildList {
            add(TxOutput(collateralUtxo.valueSats, collateralReturnScriptPubKeyHex))
            if (ddChangeCents > 0) {
                add(TxOutput(0, "5120" + Taproot.ddTokenOutputKey(ownerKeyHex, ecOps)))
                add(TxOutput(0, RedemptionMetadata(ddChangeCents).build()))
            }
            if (dgbChangeSats > 0) add(TxOutput(dgbChangeSats, dgbChangeScriptPubKeyHex))
        }

        val tx = Transaction(
            version = DigiDollarVersion.build(DigiDollarTxType.REDEMPTION),
            locktime = collateralUtxo.lockHeight.toLong(),
            inputs = inputs,
            outputs = outputs,
        )
        return UnsignedRedemption(
            tx = tx,
            prevouts = prevouts,
            leafScriptHex = Taproot.normalRedemptionLeafScript(
                collateralUtxo.lockHeight,
                collateralUtxo.ddCents,
                ownerKeyHex,
            ),
            controlBlockHex = Taproot.collateralControlBlock(
                collateralUtxo.lockHeight,
                collateralUtxo.ddCents,
                ownerKeyHex,
                ecOps,
            ),
            ddChangeCents = ddChangeCents,
            dgbChangeSats = dgbChangeSats,
        )
    }
}
