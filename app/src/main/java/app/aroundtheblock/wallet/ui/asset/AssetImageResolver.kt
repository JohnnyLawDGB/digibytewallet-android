package app.aroundtheblock.wallet.ui.asset

import android.net.Uri

/**
 * Normalises the `AssetMetadata.imageUrl` field — which IPFS-hosted asset
 * metadata may express in any of four shapes — into a single value Coil can
 * dispatch on:
 *
 *  - a web URL is never fetched from the host it names (4.1.2 row 28): metadata is the issuer's, and
 *    an image URL on a host the issuer runs told that host the IP of every wallet that showed the
 *    asset -- send an asset to an address, learn who opens it. An IPFS gateway URL
 *    (`https://<any host>/ipfs/<cid>[/path]`, or the subdomain form `https://<cid>.ipfs.<host>/path`)
 *    is read for its CID and fetched as `ipfs://<cid>[/path]` through the wallet's own gateways; any
 *    other `https://` or `http://` URL is not loaded (the placeholder is shown).
 *  - `ipfs://<cid>[/path]` → returned as Android Uri with scheme `ipfs`.
 *    [IpfsFetcher] recognises the scheme and routes through our
 *    hash-verifying `IpfsClient`.
 *  - `/ipfs/<cid>` → stripped and wrapped to `ipfs://<cid>`.
 *  - bare CID (CIDv0 `Qm…` or CIDv1 `bafy…`, `z…`, `f…`) → wrapped to
 *    `ipfs://<cid>`.
 *
 * Returns null for blank / unrecognised inputs so the caller can render a
 * placeholder and skip the network entirely.
 *
 * `data:` URIs are rejected, whatever media type they declare. Coil 2.x ships
 * no data-URI fetcher and the app registers none, so such a value never
 * rendered anyway; passing it on only made the detail screen offer an empty
 * full-screen viewer. Inline artwork, if it is ever wanted, needs a bounded
 * fetcher that accepts specific image types, not a pass-through here.
 */
object AssetImageResolver {

    private val CID_V0_REGEX = Regex("^Qm[1-9A-HJ-NP-Za-km-z]{44}$")

    // CIDv1: one of several multibase prefixes followed by at least ~48 chars.
    // Loose bound is intentional — strict decoding lives in CidVerifier.
    private val CID_V1_REGEX = Regex("^(b[a-z2-7]{58,}|z[1-9A-HJ-NP-Za-km-z]{48,}|f[0-9a-fA-F]{60,})$")

    fun resolve(raw: String?): Any? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        return when {
            trimmed.startsWith("https://", ignoreCase = true) || trimmed.startsWith("http://", ignoreCase = true) ->
                gatewayCid(trimmed)?.let { Uri.parse("ipfs://$it") }
            // Inline data: URIs are not loaded (see the class doc): no fetcher reads them.
            trimmed.startsWith("data:", ignoreCase = true) -> null
            trimmed.startsWith("ipfs://") -> Uri.parse(trimmed)
            trimmed.startsWith("/ipfs/") -> Uri.parse("ipfs://${trimmed.removePrefix("/ipfs/")}")
            isCid(trimmed) -> Uri.parse("ipfs://$trimmed")
            else -> {
                android.util.Log.w("AssetImageResolver", "Unrecognised imageUrl format: $trimmed")
                null
            }
        }
    }

    /** The `<cid>[/path]` an IPFS gateway URL names, or null when the URL is not one. */
    private fun gatewayCid(url: String): String? {
        val afterScheme = url.substringAfter("://")
        val host = afterScheme.substringBefore('/').substringBefore('?').lowercase()
        val path = afterScheme.substringAfter('/', missingDelimiterValue = "").substringBefore('?').substringBefore('#')
        // https://<cid>.ipfs.<gateway>/<path>
        val sub = host.substringBefore(".ipfs.", missingDelimiterValue = "")
        if (sub.isNotEmpty() && isCid(sub)) return if (path.isEmpty()) sub else "$sub/$path"
        // https://<gateway>/ipfs/<cid>/<path>
        if (!path.startsWith("ipfs/")) return null
        val rest = path.removePrefix("ipfs/")
        val cid = rest.substringBefore('/')
        return if (isCid(cid)) rest else null
    }

    private fun isCid(s: String): Boolean =
        CID_V0_REGEX.matches(s) || CID_V1_REGEX.matches(s)
}
