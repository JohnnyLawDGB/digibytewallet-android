package io.digibyte.core.asset

import io.digibyte.core.model.AssetMetadata

/**
 * Finds assets that publish the *same metadata document* under a *different assetId*.
 *
 * ## Why this is the signature worth looking for
 *
 * A copied asset cannot reuse an assetId — the id is derived from `input[0]` of its own issuance,
 * so a new issuance necessarily gets a new one. What it can reuse, verbatim, is the original's
 * IPFS CID. DORRO #5 (`assets.digistamp.co/listing/cmrxzrkhd02avdo7s7ymb93ue`, confirmed a
 * forgery) did exactly that.
 *
 * The consequence is that nothing *inside* the metadata distinguishes the copy: same name, same
 * image, same description, byte for byte. [io.digibyte.core.ipfs.CidVerifier] passes as well,
 * because the content genuinely does hash to that CID — **content verification proves integrity,
 * never authorship**. Two facts from outside the document can tell them apart: the chain-proven
 * issuer, and this — the reuse itself.
 *
 * ## What this deliberately does not do
 *
 * It does not decide which asset is genuine, and its wording must never imply that it has.
 * Ranking by issuance height would be a guess: the earliest issuance *this wallet happens to
 * hold* need not be the earliest that exists. It reports a fact — these assetIds publish
 * identical metadata — and leaves the judgement to the person, next to the verified issuer that
 * lets them make it.
 *
 * ## Scope
 *
 * The wallet sees only assets it holds, so someone holding just the copy learns nothing here.
 * Whole-index detection has to come from a provider that indexes every issuance; as of
 * 2026-08-26 `assets.digistamp.co` exposes no CID-collision lookup. This is a real but partial
 * check, and is worth having because the case it does catch — receiving a "copy" of something you
 * already own — is exactly when a holder is being asked to accept one.
 */
object AssetDuplicateDetector {

    /**
     * Other assetIds in [held] whose metadata CID matches [assetId]'s.
     *
     * Returns empty when the asset has no CID, when no other asset shares it, or when [assetId]
     * is not in [held]. Assets **without** a CID are never duplicates of one another: an absent
     * CID is the ordinary state of any asset whose metadata has not resolved, and matching on it
     * would herd every unresolved asset into one false cluster — the quickest way to train
     * someone to ignore the warning entirely.
     */
    fun assetsSharingMetadata(assetId: String, held: List<AssetMetadata>): List<String> {
        val cid = held.firstOrNull { it.assetId == assetId }
            ?.metadataCid
            ?.takeIf { it.isNotBlank() }
            ?: return emptyList()

        return held.asSequence()
            .filter { it.assetId != assetId && it.metadataCid?.trim() == cid.trim() }
            .map { it.assetId }
            .distinct()
            .toList()
    }
}
