package app.aroundtheblock.wallet.core.model

import app.aroundtheblock.wallet.core.ipfs.IpfsLocation

/** What a file an asset's metadata declares is, as far as the wallet can tell without fetching it. */
enum class AssetMediaKind {
    IMAGE, AUDIO, VIDEO,
    /** A type was declared and it is none of the three (a document, a web page, a 3D model). */
    OTHER,
    /** Nothing was declared and the name says nothing: only the bytes can tell. */
    UNKNOWN;

    companion object {
        private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "avif", "bmp", "heic", "heif")
        private val AUDIO_EXT = setOf("mp3", "wav", "ogg", "oga", "flac", "m4a", "aac", "opus", "weba", "mid", "midi")
        private val VIDEO_EXT = setOf("mp4", "m4v", "webm", "mov", "mkv", "ogv", "avi", "mpg", "mpeg", "3gp")

        /**
         * The declared MIME type decides (ASSET-MEDIA-CLASSIFY). The entry's NAME never outranks it:
         * DigiAsset tools name an audio-only asset's song "icon", and a file named `cover.png` that
         * declares `video/mp4` is a video. Only when no type is declared (or the declared one is
         * the catch-all `application/octet-stream`) does a file extension, of the name and then of
         * the URL, give a hint.
         */
        fun of(mimeType: String?, name: String?, url: String?): AssetMediaKind {
            val type = mimeType?.trim()?.lowercase()?.substringBefore(';')?.trim().orEmpty()
            when {
                type.startsWith("image/") -> return IMAGE
                type.startsWith("audio/") -> return AUDIO
                type.startsWith("video/") -> return VIDEO
                type.isNotEmpty() && type != "application/octet-stream" -> return OTHER
            }
            for (text in listOf(name, url)) {
                val ext = text?.substringBefore('?')?.substringBefore('#')?.substringAfterLast('/')
                    ?.substringAfterLast('.', missingDelimiterValue = "")?.lowercase().orEmpty()
                when (ext) {
                    in IMAGE_EXT -> return IMAGE
                    in AUDIO_EXT -> return AUDIO
                    in VIDEO_EXT -> return VIDEO
                }
            }
            return UNKNOWN
        }
    }
}

/**
 * One file an asset's metadata declares (an entry of its `urls` array): where it is, what the
 * issuer says it is, and what the issuer called it. All three are the issuer's words. Nothing here
 * has been fetched, and keeping an entry makes no request.
 *
 * ## What an entry is, and what may be fetched for it
 *
 * [kind] and [cid] are separate facts. The kind comes from the declared type (failing that, a file
 * extension) and survives whatever the URL is, so a screen can say "this asset carries a video"
 * about a file the wallet will never fetch. [cid] is nullable:
 *
 * | the entry's URL                                   | [cid]   | [fetchable] |
 * |---------------------------------------------------|---------|-------------|
 * | a CID: bare, `ipfs://<cid>`, `/ipfs/<cid>`        | the CID | yes         |
 * | an IPFS gateway URL for a CID                     | the CID | yes, through the wallet's own gateways; the host the URL names is never contacted |
 * | a CID with a path, `ipfs://<cid>/song.mp3`        | the CID | **no**      |
 * | any other `http(s)://` URL, or anything else      | null    | **no**      |
 *
 * A web URL is kept with its kind and never fetched: it names a host the issuer chose, and asking
 * it would tell that host who holds the asset (4.1.2 row 28).
 *
 * A CID with a path names a file INSIDE a directory. [cid] is then the directory, a different
 * object from the file, and the wallet's client fetches whole CIDs only, so the entry is kept and
 * marked not fetchable. **Fetch by [cid] only when [fetchable] is true**: the bare CID of a path
 * entry is not the file, and fetching it instead would be a silent substitution.
 *
 * A bare CID that happens to BE a directory cannot be told from a file's by reading metadata (both
 * are dag-pb CIDs); it reads as fetchable, and only its bytes show that it is not the declared
 * media. Whoever fetches it has to treat that answer as "not this kind of file", not as the file.
 *
 * @param url      as written: normally `ipfs://<cid>`. Never fetched as written; see above.
 * @param mimeType the declared type, lower-cased, or null when none was declared.
 * @param name     the entry's name ("icon", "file", a file name), cleaned for display, or null.
 */
data class AssetMediaEntry(
    val url: String,
    val mimeType: String?,
    val name: String?,
) {
    val kind: AssetMediaKind get() = AssetMediaKind.of(mimeType, name, url)

    /** The IPFS object [url] names ([IpfsLocation], the rule the picture loader uses too), or null. */
    val location: IpfsLocation? get() = IpfsLocation.parse(url)

    /** The CID [url] names, or null when it names none (a web URL, anything unrecognised). With a
     *  path it is the DIRECTORY's CID: see [fetchable]. */
    val cid: String? get() = location?.cid

    /** True when the file is exactly the object [cid] names, so the wallet's gateways can be asked
     *  for it by that CID. False with no CID, and false for a CID with a path. */
    val fetchable: Boolean get() = location?.let { !it.hasPath } ?: false

    /**
     * False only when the entry is audio or video: by its declared `audio/…` or `video/…` type, or,
     * with no usable type, by a file extension. Those are the only things that rule a file out as a
     * picture without its bytes being looked at. A declared type that is none of image, audio and
     * video (`application/octet-stream`, `application/png`, `binary/octet-stream`, a typo) decides
     * nothing: such an entry is judged like one that declares no type.
     */
    val mayBePicture: Boolean get() = when (kind) {
        AssetMediaKind.IMAGE, AssetMediaKind.UNKNOWN -> true
        AssetMediaKind.AUDIO, AssetMediaKind.VIDEO -> false
        AssetMediaKind.OTHER -> AssetMediaKind.of(null, name, url).let { it != AssetMediaKind.AUDIO && it != AssetMediaKind.VIDEO }
    }
}
