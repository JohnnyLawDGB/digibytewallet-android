package io.digibyte.core.asset

/**
 * How an owned asset should be named in the UI.
 *
 * There are THREE nameless states and the list previously ran two of them together, so the most
 * honest case looked like the most broken one:
 *
 *  - **Unresolved** — no asset id derived yet (a transfer whose parent-walk hasn't finished).
 *  - **Pending** — asset id known, no metadata row: we haven't successfully asked yet.
 *  - **Artifact** — asset id known, metadata row exists, but it carries no name. We DID ask; the
 *    issuance simply published no metadata document. Confirmed live for
 *    La4WAqZfAwtxb…, which every provider answers with `"cid": null`.
 *
 * An artifact is not a failure and must not read like one. It is a real on-chain asset whose
 * issuer published no name — a digital artifact — and the wallet should say so and count it,
 * rather than showing a truncated base58 id that looks like something went wrong.
 */
data class AssetDisplayLabel(
    val title: String,
    val subtitle: String?,
    val kind: Kind,
) {
    enum class Kind { NAMED, ARTIFACT, PENDING, UNRESOLVED }

    companion object {
        /**
         * @param assetId      the asset id, or an `unresolved:<txid>` placeholder
         * @param hasMetadataRow whether any metadata row exists for it
         * @param name         the published name, if the row carries one
         * @param symbol       the published symbol, if any
         */
        fun of(
            assetId: String,
            hasMetadataRow: Boolean,
            name: String?,
            symbol: String? = null,
        ): AssetDisplayLabel {
            if (assetId.startsWith("unresolved:")) {
                return AssetDisplayLabel(
                    title = "DigiAsset " + assetId.substringAfter("unresolved:").take(6),
                    subtitle = "identifying…",
                    kind = Kind.UNRESOLVED,
                )
            }

            if (!name.isNullOrBlank()) {
                return AssetDisplayLabel(name, symbol, Kind.NAMED)
            }

            // Row present but no name: we asked and there is nothing to show. Say that, rather
            // than implying a fetch is still outstanding.
            if (hasMetadataRow) {
                return AssetDisplayLabel(
                    title = "Artifact " + assetId.take(6),
                    subtitle = "no name published",
                    kind = Kind.ARTIFACT,
                )
            }

            // No row yet — genuinely still pending, and worth distinguishing because it may
            // resolve on its own.
            return AssetDisplayLabel(
                title = assetId.take(8) + "…",
                subtitle = "metadata offline",
                kind = Kind.PENDING,
            )
        }
    }
}
