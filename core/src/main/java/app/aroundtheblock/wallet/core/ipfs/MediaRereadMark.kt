package app.aroundtheblock.wallet.core.ipfs

import org.json.JSONObject

/**
 * What `asset_metadata.mediaJson` holds for a named row whose re-read for media was tried and
 * answered "not here": how many times, and when last. It is the third thing that column can hold:
 *
 *  - NULL: the document has not been read for the files it declares (every row from before schema 13);
 *  - a JSON ARRAY: it has been read, and these are the files ([AssetMediaEntries]; `[]` for none);
 *  - this, a JSON OBJECT `{"notHere":2,"at":1795000000000}`: not read yet, and a gateway has said
 *    twice that the document is not there.
 *
 * An object is not an array, so [AssetMediaEntries.decode] reads it as no entries, which is what a
 * row not yet read has. Kept in the same column so that bounding the re-read
 * ([AssetMetadataService.MEDIA_REREAD_NOT_HERE_LIMIT]) adds no column and Room stays at schema 13.
 */
internal data class MediaRereadMark(val notHere: Int, val at: Long) {

    fun encode(): String = JSONObject().put(KEY_COUNT, notHere).put(KEY_AT, at).toString()

    companion object {
        private const val KEY_COUNT = "notHere"
        private const val KEY_AT = "at"

        /** The mark [text] holds, or null when it holds something else (NULL, the entries, damage). */
        fun read(text: String?): MediaRereadMark? {
            if (text == null || !text.trimStart().startsWith("{")) return null
            val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
            val count = o.optInt(KEY_COUNT, -1)
            if (count < 1 || !o.has(KEY_AT)) return null
            return MediaRereadMark(count, o.optLong(KEY_AT))
        }

        /**
         * The mark after one more "not here" at [now]. Counted only when the last one is at least
         * [minGapMs] old (or the clock went backwards): answers closer together than that are one
         * outage seen twice, for instance by an app started several times in a row.
         */
        fun next(before: MediaRereadMark?, now: Long, minGapMs: Long): MediaRereadMark = when {
            before == null -> MediaRereadMark(1, now)
            now - before.at in 0 until minGapMs -> before
            else -> MediaRereadMark(before.notHere + 1, now)
        }
    }
}
