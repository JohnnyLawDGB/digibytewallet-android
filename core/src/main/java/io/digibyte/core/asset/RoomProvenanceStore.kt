package io.digibyte.core.asset

import io.digibyte.core.db.dao.AssetProvenanceDao
import io.digibyte.core.db.entity.AssetProvenanceEntity
import io.digibyte.core.db.entity.AssetWalkFrontierEntity

/**
 * [ProvenanceStore] on Room, so what a parent-walk learns survives the process.
 *
 * The predecessor was a session-local set of walked txids — which meant a restart threw away
 * everything and a deep chain re-walked from zero, forever. Persistence is the point.
 */
class RoomProvenanceStore(
    private val dao: AssetProvenanceDao,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : ProvenanceStore {

    override suspend fun assetFor(txid: String): ResolvedAssetFacts? =
        dao.provenanceFor(txid)?.toFacts()

    override suspend fun putAssets(txids: List<String>, facts: ResolvedAssetFacts) {
        if (txids.isEmpty()) return
        dao.putProvenance(
            txids.distinct().map {
                AssetProvenanceEntity(
                    txid = it,
                    assetId = facts.assetId,
                    totalSupply = facts.totalSupply,
                    divisibility = facts.divisibility,
                    metadataCid = facts.metadataCid,
                    issuanceOpcode = facts.issuanceOpcode,
                    issuanceLocked = facts.issuanceLocked,
                )
            }
        )
    }

    override suspend fun issuanceFactsFor(assetId: String): ResolvedAssetFacts? =
        dao.issuanceFactsFor(assetId)?.toFacts()

    private fun AssetProvenanceEntity.toFacts() = ResolvedAssetFacts(
        assetId, totalSupply, divisibility, metadataCid, issuanceOpcode, issuanceLocked,
    )

    override suspend fun frontierFor(startTxid: String): WalkFrontier? =
        dao.frontierFor(startTxid)?.let { WalkFrontier(it.startTxid, it.resumeTxid, it.hopsWalked) }

    override suspend fun putFrontier(frontier: WalkFrontier) {
        dao.putFrontier(
            AssetWalkFrontierEntity(
                startTxid = frontier.startTxid,
                resumeTxid = frontier.resumeTxid,
                hopsWalked = frontier.hopsWalked,
                updatedAt = nowMillis(),
            )
        )
    }

    override suspend fun clearFrontier(startTxid: String) = dao.clearFrontier(startTxid)

    /** Rescan / wipe: provenance is a cache and must never outlive the chain state it describes. */
    suspend fun clearAll() {
        dao.clearAllProvenance()
        dao.clearAllFrontiers()
    }
}
