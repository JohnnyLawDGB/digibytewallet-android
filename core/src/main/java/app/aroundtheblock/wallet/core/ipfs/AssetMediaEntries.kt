package app.aroundtheblock.wallet.core.ipfs

import app.aroundtheblock.wallet.core.model.AssetMediaEntry
import app.aroundtheblock.wallet.core.model.AssetMediaKind
import app.aroundtheblock.wallet.core.network.textOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads the files an asset's metadata declares, and chooses which of them is its picture
 * (ASSET-MEDIA-CLASSIFY). One copy of both rules; [AssetMetadataService.storeFromJson] is the caller.
 *
 * The document is written by whoever minted the asset and is authenticated by nothing, so what is
 * kept from it is bounded and cleaned here, and nothing in it is fetched here.
 *
 * ## The picture
 *
 * Chosen by media type, never by an entry's name alone. DigiAsset tools name an audio-only asset's
 * song "icon"; until 4.1.5 the first entry NAMED "icon" was stored as the image, so a 4 MB MP3 was
 * downloaded as a picture and thrown away. In order:
 *
 *  1. the top-level `image`, `imageUrl`, `image_url`, `icon` text, unless a `urls` entry for the
 *     same FILE is audio or video, or its own file extension says audio or video. "The same file"
 *     is decided as the picture loader decides it ([IpfsLocation]): a bare CID, `ipfs://<cid>`,
 *     `/ipfs/<cid>` and a gateway URL for that CID are one file, however each is spelled;
 *  2. from `urls`: an entry of kind IMAGE named "icon" or "image"; else any entry of kind IMAGE;
 *     else an entry named "icon" or "image" that is not audio or video.
 *
 * Only audio and video rule a file out without its bytes being looked at
 * ([AssetMediaEntry.mayBePicture]): an entry that declares `audio/…` or `video/…` is never the
 * picture, whatever it is called. Any other declared type that is not an image
 * (`application/octet-stream`, `application/png`, a typo) decides nothing, and an entry named as
 * the picture is then judged like one that declares no type: its bytes have the last word, and a
 * file that does not decode as an image is remembered as that by [IpfsClient.rememberNotAPicture].
 *
 * ## What is kept
 *
 * Every `urls` entry with a usable URL, in the document's order, up to [MAX_ENTRIES]: URL, declared
 * type and name. The picture is among them. An entry is kept WITH ITS KIND whether or not the
 * wallet could ever fetch it: a web URL, or a CID with a path, is kept and is not fetchable
 * ([AssetMediaEntry.cid], [AssetMediaEntry.fetchable]). Stored as JSON text in
 * `asset_metadata.mediaJson`; the CID and "fetchable" are derived from the URL on reading, so the
 * stored shape carries one copy of where the file is.
 */
object AssetMediaEntries {

    /** The most entries kept from one document. Real documents carry one or two. */
    const val MAX_ENTRIES = 16

    /** A MIME type longer than this is not one. */
    const val MAX_MIME_LEN = 100

    /** The most entries of `urls` looked at, kept or not, so a huge array costs a bounded walk. */
    private const val MAX_SCANNED = 256

    private val MIME = Regex("^[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*$")
    private val PICTURE_KEYS = listOf("image", "imageUrl", "image_url", "icon")
    private val PICTURE_NAMES = setOf("icon", "image")

    class Parsed internal constructor(val imageUrl: String?, val media: List<AssetMediaEntry>)

    /** [json] is the asset-level object (already unwrapped from `data`). */
    fun parse(json: JSONObject): Parsed {
        val media = entries(json.optJSONArray("urls"))

        val topLevel = PICTURE_KEYS.firstNotNullOfOrNull { json.textOrNull(it) }
        val picture = if (topLevel != null) {
            // A top-level key carries no type. The same file's `urls` entry may; failing that, its
            // extension. The same FILE, not the same text: the loader fetches a bare CID,
            // `ipfs://<cid>`, `/ipfs/<cid>` and a gateway URL for it as one file, so an audio
            // entry in one spelling must rule out a top-level picture in another.
            val where = IpfsLocation.parse(topLevel)
            val sameFile = media.filter { e -> e.url == topLevel || (where != null && e.location?.sameFileAs(where) == true) }
            val mayBePicture = when {
                sameFile.isEmpty() -> AssetMediaEntry(topLevel, null, null).mayBePicture
                // Declared twice with different types: an image declaration keeps it; else every one must allow it.
                else -> sameFile.any { it.kind == AssetMediaKind.IMAGE } || sameFile.all { it.mayBePicture }
            }
            topLevel.takeIf { mayBePicture }
        } else {
            fun named(e: AssetMediaEntry) = e.name?.lowercase() in PICTURE_NAMES
            (media.firstOrNull { it.kind == AssetMediaKind.IMAGE && named(it) }
                ?: media.firstOrNull { it.kind == AssetMediaKind.IMAGE }
                // Named as the picture and not audio or video: no type, or a type that decides nothing.
                ?: media.firstOrNull { named(it) && it.mayBePicture })?.url
        }
        return Parsed(picture?.takeIf { it.length <= AssetMetadataService.MAX_IMAGE_URL_LEN }, media)
    }

    private fun entries(urls: JSONArray?): List<AssetMediaEntry> {
        if (urls == null) return emptyList()
        val out = ArrayList<AssetMediaEntry>()
        for (i in 0 until minOf(urls.length(), MAX_SCANNED)) {
            if (out.size >= MAX_ENTRIES) break
            val u = urls.optJSONObject(i) ?: continue
            out += entry(u.textOrNull("url"), u.textOrNull("mimeType"), u.textOrNull("name")) ?: continue
        }
        return out
    }

    /** One entry, bounded and cleaned, or null when it has no usable URL. Used on the way in and on the way out. */
    private fun entry(url: String?, mimeType: String?, name: String?): AssetMediaEntry? {
        val cleanUrl = url?.trim()?.takeIf { it.isNotEmpty() && it.length <= AssetMetadataService.MAX_IMAGE_URL_LEN }
            ?: return null
        // The URL is never displayed; it is resolved, and refused, like a picture's. It still must be
        // one line of visible characters, so that nothing downstream has to wonder.
        if (cleanUrl.any { !AssetMetadataService.isDisplaySafe(it) || it.isWhitespace() }) return null
        val type = mimeType?.substringBefore(';')?.trim()?.lowercase()
            ?.takeIf { it.length <= MAX_MIME_LEN && MIME.matches(it) }
        val cleanName = AssetMetadataService.sanitize(name, AssetMetadataService.MAX_SHORT_TEXT_LEN, allowNewlines = false)
        return AssetMediaEntry(cleanUrl, type, cleanName)
    }

    fun encode(media: List<AssetMediaEntry>): String {
        val out = JSONArray()
        for (m in media.take(MAX_ENTRIES)) {
            val o = JSONObject().put("url", m.url)
            if (m.mimeType != null) o.put("mimeType", m.mimeType)
            if (m.name != null) o.put("name", m.name)
            out.put(o)
        }
        return out.toString()
    }

    /** What [encode] wrote. Anything else (NULL, a row never read for media, damaged text) is no entries. */
    fun decode(text: String?): List<AssetMediaEntry> {
        if (text.isNullOrBlank()) return emptyList()
        val arr = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        // Through the same bounds as on the way in: a stored row is trusted no further than a document.
        return entries(arr)
    }
}
