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
 *     else the first entry NAMED LIKE A PICTURE that is not audio or video.
 *
 * "Named like a picture" is what 4.1.5 picked by, and a little more: the name is "icon" or
 * "image", or the name (or the URL's path) ends in an image file extension (`logo.png`).
 *
 * Only audio and video rule a file out without its bytes being looked at
 * ([AssetMediaEntry.mayBePicture]): an entry that declares `audio/…` or `video/…` is never the
 * picture, whatever it is called. Any other declared type that is not an image
 * (`application/octet-stream`, `binary/octet-stream`, `application/png`, `application/pdf`,
 * `text/html`, a typo) decides nothing, and an entry named like a picture is then judged like one
 * that declares no type: its bytes have the last word, and a file that does not decode as an
 * image is remembered as that by [IpfsClient.rememberNotAPicture].
 *
 * The WHOLE `urls` array is looked at for the picture, as 4.1.5 did: the bound on what is kept
 * ([MAX_ENTRIES]) is not a bound on where the picture may stand.
 *
 * ## What is kept
 *
 * The first [MAX_ENTRIES] `urls` entries with a usable URL, in the document's order: URL, declared
 * type and name. The picture's entry is always among them: when it stands later in the array it
 * takes the last place. A URL may hold a plain space (a gateway URL written
 * `…/ipfs/<cid>?filename=my logo.png` is kept as written, and names its CID); any other
 * whitespace or undisplayable character drops the entry. An entry is kept WITH ITS KIND whether or not the
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

    private val MIME = Regex("^[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*$")
    private val PICTURE_KEYS = listOf("image", "imageUrl", "image_url", "icon")
    private val PICTURE_NAMES = setOf("icon", "image")

    class Parsed internal constructor(val imageUrl: String?, val media: List<AssetMediaEntry>)

    /** [json] is the asset-level object (already unwrapped from `data`). */
    fun parse(json: JSONObject): Parsed {
        // Every entry, for choosing the picture; the document is bounded by the fetch (5 MiB), and
        // so is this walk. What is KEPT is bounded below.
        val media = entries(json.optJSONArray("urls"), limit = Int.MAX_VALUE)
        var pictureEntry: AssetMediaEntry? = null

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
            // Named like a picture: "icon"/"image", or an image file extension on the name or the
            // URL's path, whatever type was declared beside it.
            fun namedLikeAPicture(e: AssetMediaEntry) =
                named(e) || AssetMediaKind.of(null, e.name, e.url) == AssetMediaKind.IMAGE
            pictureEntry = media.firstOrNull { it.kind == AssetMediaKind.IMAGE && named(it) }
                ?: media.firstOrNull { it.kind == AssetMediaKind.IMAGE }
                // Not audio or video: no type, or a type that decides nothing. Its bytes will say.
                ?: media.firstOrNull { namedLikeAPicture(it) && it.mayBePicture }
            pictureEntry?.url
        }
        // Kept: the first MAX_ENTRIES, and the picture's own entry if it stands after them.
        val chosen = pictureEntry
        val kept = when {
            media.size <= MAX_ENTRIES -> media
            chosen != null && media.indexOfFirst { it === chosen } >= MAX_ENTRIES -> media.take(MAX_ENTRIES - 1) + chosen
            else -> media.take(MAX_ENTRIES)
        }
        return Parsed(picture?.takeIf { it.length <= AssetMetadataService.MAX_IMAGE_URL_LEN }, kept)
    }

    private fun entries(urls: JSONArray?, limit: Int = MAX_ENTRIES): List<AssetMediaEntry> {
        if (urls == null) return emptyList()
        val out = ArrayList<AssetMediaEntry>()
        for (i in 0 until urls.length()) {
            if (out.size >= limit) break
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
        // one line of visible characters, so that nothing downstream has to wonder. A plain space is
        // allowed inside it: gateway URLs are written `…?filename=my logo.png`, 4.1.5 loaded such a
        // picture (the loader takes the CID and never reads the query), and the URL is never
        // fetched as written.
        if (cleanUrl.any { !AssetMetadataService.isDisplaySafe(it) || (it.isWhitespace() && it != ' ') }) return null
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
