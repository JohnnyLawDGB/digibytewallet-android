package io.digibyte.core.model

/**
 * Represents a DigiAsset embedded in a transaction output.
 *
 * @param assetId       DigiAsset asset identifier (hash-derived).
 * @param quantity      Raw token quantity (before divisibility scaling).
 * @param operation     Whether this output issues, transfers, or burns tokens.
 * @param metadataCid   IPFS CID pointing to the asset's JSON metadata (may be null for
 *                      simple transfers that don't re-embed the CID).
 * @param locked        When true the tokens are bound to a specific address and cannot
 *                      be transferred without re-issuance rules being met.
 * @param divisibility  Number of decimal places (0–8); display quantity = quantity / 10^divisibility.
 */
data class AssetData(
    val assetId: String,
    val quantity: Long,
    val operation: AssetOperation,
    val metadataCid: String?,
    val locked: Boolean,
    val divisibility: Int
)

/** The type of DigiAsset operation encoded in a transaction output. */
enum class AssetOperation {
    /** First issuance — creates new tokens. */
    ISSUANCE,
    /** Transfer of existing tokens between addresses. */
    TRANSFER,
    /** Permanent destruction of tokens. */
    BURN
}

/**
 * An asset balance owned by the local wallet, optionally enriched with metadata.
 *
 * @param assetId   DigiAsset asset identifier.
 * @param quantity  Total quantity held (sum across all UTXOs).
 * @param metadata  Decoded metadata from IPFS cache, or null if not yet fetched.
 * @param utxoCount Number of UTXOs holding this asset.
 */
data class OwnedAsset(
    val assetId: String,
    val quantity: Long,
    val metadata: AssetMetadata?,
    val utxoCount: Int
)

/**
 * Human-readable metadata for a DigiAsset, sourced from an IPFS-hosted JSON document
 * and cached in the local Room database.
 *
 * @param assetId       DigiAsset identifier this metadata belongs to.
 * @param name          Display name of the asset (e.g. "DigiByte Shield").
 * @param symbol        Short ticker symbol (e.g. "DGBS").
 * @param description   Free-text description.
 * @param decimals      Decimal places for display (mirrors [AssetData.divisibility]).
 * @param totalSupply   Total tokens ever issued.
 * @param issuerAddress DigiByte address of the original issuer.
 * @param imageUrl      URL or IPFS URI for an asset logo / artwork.
 */
data class AssetMetadata(
    val assetId: String,
    val name: String?,
    val symbol: String?,
    val description: String?,
    val decimals: Int = 0,
    val totalSupply: Long = 0,
    val issuerAddress: String?,
    val imageUrl: String?,
    /** IPFS CID of the metadata document. Carried into the display model so the wallet can spot
     *  the same document published under more than one assetId — see [AssetDuplicateDetector]. */
    val metadataCid: String? = null,
)
