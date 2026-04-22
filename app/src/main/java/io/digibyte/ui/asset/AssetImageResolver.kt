package io.digibyte.ui.asset

import android.net.Uri

/**
 * Normalises the `AssetMetadata.imageUrl` field — which IPFS-hosted asset
 * metadata may express in any of four shapes — into a single value Coil can
 * dispatch on:
 *
 *  - `https://foo.com/bar.png` → returned as String; Coil's default OkHttp
 *    fetcher handles it through the injected (Tor-aware) OkHttpClient.
 *  - `ipfs://<cid>[/path]` → returned as Android Uri with scheme `ipfs`.
 *    [IpfsFetcher] recognises the scheme and routes through our
 *    hash-verifying `IpfsClient`.
 *  - `/ipfs/<cid>` → stripped and wrapped to `ipfs://<cid>`.
 *  - bare CID (CIDv0 `Qm…` or CIDv1 `bafy…`, `z…`, `f…`) → wrapped to
 *    `ipfs://<cid>`.
 *
 * Returns null for blank / unrecognised inputs so the caller can render a
 * placeholder and skip the network entirely.
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
            trimmed.startsWith("https://") || trimmed.startsWith("http://") -> trimmed
            // data: URIs carry the image inline (base64 or raw), used by
            // issuance tools that skip IPFS for small thumbnails. Coil
            // handles data:image/... natively.
            trimmed.startsWith("data:") -> trimmed
            trimmed.startsWith("ipfs://") -> Uri.parse(trimmed)
            trimmed.startsWith("/ipfs/") -> Uri.parse("ipfs://${trimmed.removePrefix("/ipfs/")}")
            isCid(trimmed) -> Uri.parse("ipfs://$trimmed")
            else -> {
                android.util.Log.w("AssetImageResolver", "Unrecognised imageUrl format: $trimmed")
                null
            }
        }
    }

    private fun isCid(s: String): Boolean =
        CID_V0_REGEX.matches(s) || CID_V1_REGEX.matches(s)
}
