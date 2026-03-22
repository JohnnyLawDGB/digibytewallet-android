package io.digibyte.core.asset

import io.digibyte.core.model.AssetData
import io.digibyte.core.model.AssetOperation

/**
 * Result of validating an asset transfer against its rules.
 *
 * @param allowed  Whether the transfer is permitted.
 * @param reason   Human-readable explanation when [allowed] is false.
 */
data class RuleValidationResult(
    val allowed: Boolean,
    val reason: String? = null
)

/**
 * DigiAsset rules engine (Phase 2 stub).
 *
 * In the full DigiAsset v3 protocol, issuance transactions can embed rules
 * that govern how an asset may be transferred:
 *   - Royalties: mandatory payments to specified addresses on every transfer
 *   - KYC: only verified addresses may hold the asset
 *   - Expiry / vote cutoff: asset becomes non-transferable after a block height
 *   - Deflation: a fixed amount is burned on every transfer
 *   - Signers: multi-party approval required
 *
 * This Phase 2 implementation provides basic validation only. The full rules
 * engine (royalties, KYC, expiry, vote rights, deflation) will be implemented
 * in Phase 3 when the wallet needs to construct asset transactions, not just
 * display them.
 */
class DigiAssetRules {

    /**
     * Validate whether a transfer of this asset is allowed.
     *
     * Phase 2 checks:
     *   - Quantity must be positive
     *   - Dispersed (NFT-like) assets cannot be split (quantity must be 1)
     *
     * @param asset       The asset being transferred.
     * @param quantity    Number of tokens to transfer.
     * @param toAddress   Destination DigiByte address.
     * @param aggregation Aggregation type (needed for dispersed check).
     * @return [RuleValidationResult] indicating whether the transfer is valid.
     */
    fun validateTransfer(
        asset: AssetData,
        quantity: Long,
        toAddress: String,
        aggregation: Aggregation = Aggregation.AGGREGATABLE
    ): RuleValidationResult {

        if (quantity <= 0) {
            return RuleValidationResult(false, "Quantity must be positive")
        }

        // Dispersed (NFT) assets are indivisible and unique
        if (aggregation == Aggregation.DISPERSED && quantity != 1L) {
            return RuleValidationResult(
                false,
                "Dispersed (NFT) assets cannot be split; quantity must be 1"
            )
        }

        // Locked issuance means no additional supply can ever be created.
        // This does not prevent transfers, but we note it for callers.
        // (No restriction on transfer of already-issued locked tokens.)

        // TODO Phase 3: royalties validation
        //   - Check that the transaction includes required royalty outputs
        //   - Validate royalty amounts against exchange rates if currency != DGB

        // TODO Phase 3: KYC validation
        //   - Check toAddress is in the verified address set for this asset's issuer

        // TODO Phase 3: expiry / vote cutoff
        //   - Check current block height against the asset's cutoff height

        // TODO Phase 3: deflation
        //   - Ensure the correct deflation amount is burned in the transaction

        // TODO Phase 3: signers
        //   - Verify multi-party signatures if required by the asset rules

        return RuleValidationResult(true)
    }

    /**
     * Check whether this asset can be re-issued (minted again).
     *
     * @param asset The asset to check.
     * @return true if additional supply can be created.
     */
    fun canReissue(asset: AssetData): Boolean {
        // Locked assets can never have additional supply created
        if (asset.locked) return false
        // Only issuance operations create new supply
        return true
    }
}
