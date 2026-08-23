package io.digibyte.core.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * What a parent-walk proved about a transaction, kept so the next walk doesn't repeat it.
 *
 * One row per txid known to carry a given asset. A successful walk writes a row for EVERY
 * transaction on its path, not just the one it was asked about — so a later transfer that
 * touches any of them resolves in a single hop.
 */
@Entity(tableName = "asset_provenance")
data class AssetProvenanceEntity(
    @PrimaryKey val txid: String,
    val assetId: String,
    val totalSupply: Long,
    val divisibility: Int,
    val metadataCid: String?,
)

/**
 * Where a walk stopped, so a later attempt continues instead of starting over.
 *
 * Without this a chain longer than one attempt's hop budget is unreachable however many times
 * the walk runs — which is exactly how a transferred asset ended up permanently nameless.
 */
@Entity(tableName = "asset_walk_frontier")
data class AssetWalkFrontierEntity(
    @PrimaryKey val startTxid: String,
    val resumeTxid: String,
    val hopsWalked: Int,
    val updatedAt: Long,
)
