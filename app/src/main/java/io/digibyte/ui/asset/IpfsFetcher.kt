package io.digibyte.ui.asset

import android.net.Uri
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.key.Keyer
import coil.request.Options
import io.digibyte.core.ipfs.IpfsClient
import okio.Buffer

/**
 * Coil Fetcher that intercepts `ipfs://<cid>` URIs and routes the fetch
 * through [IpfsClient.fetchVerified]. The IpfsClient validates the returned
 * bytes against the CID's declared multihash before handing them back — any
 * gateway tampering surfaces as a null return here.
 *
 * We don't double-verify inside Coil's pipeline; the bytes are already
 * authenticated. Logging a CID verification failure at ERROR is the
 * exception because it's a potential security signal, not cosmetic.
 */
class IpfsFetcher(
    private val uri: Uri,
    private val options: Options,
    private val ipfsClient: IpfsClient
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val cid = extractCid(uri) ?: return null
        val bytes = ipfsClient.fetchVerified(cid)
        if (bytes == null) {
            android.util.Log.e(
                "IpfsFetcher",
                "CID verification or fetch failed for $cid — possible gateway tampering or unreachable"
            )
            return null
        }
        val buffer = Buffer().write(bytes)
        return SourceResult(
            source = ImageSource(buffer, options.context),
            mimeType = null,           // let Coil sniff from magic bytes
            dataSource = DataSource.NETWORK
        )
    }

    companion object {
        /** Extract the bare CID from an `ipfs://<cid>[/path]` URI. */
        fun extractCid(uri: Uri): String? {
            if (uri.scheme != "ipfs") return null
            return uri.host?.takeIf { it.isNotBlank() }
        }
    }

    class Factory(private val ipfsClient: IpfsClient) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.scheme != "ipfs") return null
            return IpfsFetcher(data, options, ipfsClient)
        }
    }
}

/** Caches Coil entries by bare CID rather than full URI — content-addressed. */
class IpfsCacheKeyer : Keyer<Uri> {
    override fun key(data: Uri, options: Options): String? {
        if (data.scheme != "ipfs") return null
        return "ipfs:${data.host}"
    }
}
