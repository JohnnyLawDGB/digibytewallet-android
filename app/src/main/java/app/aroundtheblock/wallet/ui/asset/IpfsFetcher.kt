package app.aroundtheblock.wallet.ui.asset

import android.net.Uri
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.key.Keyer
import coil.request.Options
import app.aroundtheblock.wallet.core.LogSafe
import app.aroundtheblock.wallet.core.ipfs.IpfsClient
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
 * That line is for a gateway that was asked and gave nothing usable. A
 * draw for which no gateway was asked (the value is not a CID, or each
 * gateway said recently that it does not have it) is routine, happens on
 * every draw of such an icon, and is logged at INFO in other words.
 */
class IpfsFetcher(
    private val uri: Uri,
    private val options: Options,
    private val ipfsClient: IpfsClient
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val cid = extractCid(uri) ?: return null
        val fetched = ipfsClient.fetch(cid)
        val bytes = fetched.bytes
        if (bytes == null) {
            if (fetched.gatewaysFailed > 0) {
                android.util.Log.e(
                    "IpfsFetcher",
                    "CID verification or fetch failed for ${LogSafe.text(cid, 80)} — possible gateway tampering or unreachable"
                )
            } else {
                // The value is the issuer's and may be anything; it reaches the log escaped and cut short.
                android.util.Log.i(
                    "IpfsFetcher",
                    "Not fetched, no gateway asked for ${LogSafe.text(cid, 80)}: not a CID, or not there when last asked"
                )
            }
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
