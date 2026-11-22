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
 * through [IpfsClient.fetch]. The IpfsClient validates the returned
 * bytes against the CID's declared multihash before handing them back — any
 * gateway tampering surfaces as a null return here.
 *
 * We don't double-verify inside Coil's pipeline; the bytes are already
 * authenticated. Logging a CID verification failure at ERROR is the
 * exception because it's a potential security signal, not cosmetic.
 * That line is for a gateway that was asked and gave nothing usable. A
 * draw for which no gateway was asked (the value is not a CID, or each
 * gateway said recently that it does not have it) is routine, happens on
 * every draw of such an icon, and is logged at INFO in other words. So is
 * a file over the size limit: one the trusted gateway (which serves the
 * file itself) refused for size, whatever the public gateways then said,
 * or one every gateway asked refused for size. That is the file's size,
 * not a gateway failing, and it is the reason reported.
 *
 * **A file that is not a picture is downloaded once (ASSET-MEDIA-FIXES).** Coil keeps no record of
 * a failed load and this loader has no disk cache, so a file that was fetched and did not decode
 * (an asset's "icon" can be an MP3) used to be fetched again by every image instance on every
 * draw. Whether the bytes are an image is asked here, of the same platform decoder Coil will use
 * ([decodesAsImage], bounds only, nothing allocated for pixels), and a "no" is remembered in
 * [IpfsClient], which the list, the detail screen and the full-screen viewer all load through.
 *
 * **Only the whole file is judged.** The "no" is remembered only when the bytes are known to be the
 * file itself ([IpfsClient.Fetched.wholeFile]): the trusted gateway's complete answer, or bytes
 * that hash to a raw-codec CID. A fetch that failed is no evidence, and neither is a public
 * gateway's root block of a dag-pb CID, which is what comes back when the trusted gateway is down
 * or has not pinned the file yet. Neither decodes as an image, and neither says the file is not one.
 */
class IpfsFetcher(
    private val uri: Uri,
    private val options: Options,
    private val ipfsClient: IpfsClient,
    /** Whether these bytes are an image the platform can decode. A test passes its own. */
    private val decodesAsImage: (ByteArray) -> Boolean = ::platformDecodesAsImage,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val cid = extractCid(uri) ?: return null
        if (ipfsClient.isKnownNotAPicture(cid)) {
            // Every draw of such an icon comes through here: said quietly, and nothing is requested.
            android.util.Log.d("IpfsFetcher", "Not fetched: ${LogSafe.text(cid, 80)} was fetched before and is no image")
            return null
        }
        val fetched = ipfsClient.fetch(cid)
        val bytes = fetched.bytes
        if (bytes == null) {
            if (fetched.trustedRefusedForSize && fetched.gatewaysFailed == 0) {
                // Refused for size before and still remembered: nothing was asked. Every draw of
                // such an icon comes through here, so it is said quietly.
                android.util.Log.d(
                    "IpfsFetcher",
                    "Not fetched: ${LogSafe.text(cid, 80)} is over the size limit for a picture, as the trusted gateway said before"
                )
            } else if (fetched.trustedRefusedForSize ||
                (fetched.gatewaysFailed > 0 && fetched.refusedForSize == fetched.gatewaysFailed)
            ) {
                // The size is the reason: the gateway that serves the file itself refused it for
                // size (whatever the public gateways then answered: unreachable, 504, 404, or a
                // block that is not the file), or every gateway asked did. Not a line that should
                // read as a security signal.
                android.util.Log.i(
                    "IpfsFetcher",
                    "Not shown: ${LogSafe.text(cid, 80)} is over the size limit for a picture (${IpfsClient.MAX_RESPONSE_SIZE} bytes)"
                )
            } else if (fetched.gatewaysFailed > 0) {
                android.util.Log.e(
                    "IpfsFetcher",
                    "CID verification or fetch failed for ${LogSafe.text(cid, 80)} — possible gateway tampering or unreachable"
                )
            } else {
                // The value is the issuer's and may be anything; it reaches the log escaped and cut short.
                android.util.Log.i(
                    "IpfsFetcher",
                    "Not fetched, no gateway asked for ${LogSafe.text(cid, 80)}: not a CID, or not there (or too large) when last asked"
                )
            }
            return null
        }
        if (!decodesAsImage(bytes)) {
            if (!fetched.wholeFile) {
                // A block, not the file: what a public gateway gives for a dag-pb CID when the
                // trusted gateway failed or does not have it yet. It says nothing about the file,
                // so nothing is remembered and the next draw asks again, as before this memory existed.
                android.util.Log.i(
                    "IpfsFetcher",
                    "Not shown: only a block of ${LogSafe.text(cid, 80)} was fetched (${bytes.size} bytes), not the file; asked again later"
                )
                return null
            }
            ipfsClient.rememberNotAPicture(cid)
            android.util.Log.w(
                "IpfsFetcher",
                "${LogSafe.text(cid, 80)} is not a picture (${bytes.size} bytes, no image the device decodes); not downloaded as one again"
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

/**
 * True when [bytes] are an image `BitmapFactory` can decode on this device: the decoder Coil then
 * uses, asked for the bounds only, so no pixel is allocated and a huge image costs nothing here.
 * A decoder that throws on hostile input reads as "not an image".
 */
internal fun platformDecodesAsImage(bytes: ByteArray): Boolean = try {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    bounds.outWidth > 0 && bounds.outHeight > 0
} catch (e: Exception) {
    false
}
