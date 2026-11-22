package app.aroundtheblock.wallet.core.model

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
 * @param url      as written: normally `ipfs://<cid>`. Resolved, and refused, exactly as a picture's is.
 * @param mimeType the declared type, lower-cased, or null when none was declared.
 * @param name     the entry's name ("icon", "file", a file name), cleaned for display, or null.
 */
data class AssetMediaEntry(
    val url: String,
    val mimeType: String?,
    val name: String?,
) {
    val kind: AssetMediaKind get() = AssetMediaKind.of(mimeType, name, url)
}
