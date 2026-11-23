package app.aroundtheblock.wallet.ui.asset

import android.net.Uri
import app.aroundtheblock.wallet.core.LogSafe
import app.aroundtheblock.wallet.core.ipfs.IpfsLocation

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
 *  - `ipfs://<cid>[/path]` → returned as Android Uri with scheme `ipfs`, when `<cid>` is a CID.
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

    /**
     * Which IPFS object [raw] names is decided by [IpfsLocation], the one copy of that rule, which
     * the stored media entries read through as well; this adds only the Coil model and the log lines.
     */
    fun resolve(raw: String?): Any? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        IpfsLocation.parse(trimmed)?.let { return Uri.parse(it.uri) }
        when {
            // A web URL that is no IPFS gateway URL, and an inline data: URI, are not loaded (see the class doc).
            trimmed.startsWith("https://", ignoreCase = true) || trimmed.startsWith("http://", ignoreCase = true) -> Unit
            trimmed.startsWith("data:", ignoreCase = true) -> Unit
            // Only a real CID becomes a model (IPFS-NO-NULL): `ipfs://null`, or any other word after
            // the scheme, used to reach the loader and be asked of every gateway on every draw.
            trimmed.startsWith("ipfs://") || trimmed.startsWith("/ipfs/") -> {
                val rest = if (trimmed.startsWith("ipfs://")) trimmed.removePrefix("ipfs://") else trimmed.removePrefix("/ipfs/")
                android.util.Log.w("AssetImageResolver",
                    "imageUrl names no IPFS CID (${rest.length} chars): ${LogSafe.text(rest, 48)}")
            }
            // ASSET-LOG-CRLF: the value is the issuer's; it reaches the log only escaped and cut short.
            else -> android.util.Log.w("AssetImageResolver",
                "Unrecognised imageUrl format (${trimmed.length} chars): ${LogSafe.text(trimmed, 48)}")
        }
        return null
    }
}
