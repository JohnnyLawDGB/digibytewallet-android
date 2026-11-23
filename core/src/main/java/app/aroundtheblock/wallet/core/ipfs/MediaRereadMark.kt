package app.aroundtheblock.wallet.core.ipfs

import org.json.JSONObject

/**
 * What `asset_metadata.mediaJson` holds for a named row whose re-read for media was tried and
 * answered "not here": how many times, and when last. It is the third thing that column can hold:
 *
 *  - NULL: the document has not been read for the files it declares (every row from before schema 13);
 *  - a JSON ARRAY: it has been read, and these are the files ([AssetMediaEntries]; `[]` for none);
 *  - this, a JSON OBJECT `{"notHere":2,"at":1795000000000}`: not read yet, and the trusted gateway
 *    has said on two separate days that the document is not there, last at that time.
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

        /**
         * True while a mark's last answer is younger than [minGapMs] at [now]: the document is not
         * asked for again inside that time. False when the clock has gone backwards past it, so a
         * mark dated in the future cannot keep a row waiting for good.
         */
        fun isResting(mark: MediaRereadMark, now: Long, minGapMs: Long): Boolean = now - mark.at in 0 until minGapMs

        /** The mark [text] holds, or null when it holds something else (NULL, the entries, damage). */
        fun read(text: String?): MediaRereadMark? {
            if (text == null || !text.trimStart().startsWith("{")) return null
            val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
            val count = o.optInt(KEY_COUNT, -1)
            if (count < 1 || !o.has(KEY_AT)) return null
            return MediaRereadMark(count, o.optLong(KEY_AT))
        }

        /**
         * The mark after one more "not here" at [now]. Counted only when the last counted one is at
         * least [minGapMs] old: answers closer together than that are one outage seen twice, for
         * instance by an app started several times in a row. So `n` counted answers span at least
         * `(n - 1) * minGapMs` of the device's clock.
         *
         * A clock that went BACKWARDS past the last answer counts nothing either: the mark is
         * re-dated to [now], so the gap is measured from here and setting the clock back cannot
         * shorten the time the count takes.
         */
        fun next(before: MediaRereadMark?, now: Long, minGapMs: Long): MediaRereadMark = when {
            before == null -> MediaRereadMark(1, now)
            now < before.at -> before.copy(at = now)
            now - before.at < minGapMs -> before
            else -> MediaRereadMark(before.notHere + 1, now)
        }
    }
}
