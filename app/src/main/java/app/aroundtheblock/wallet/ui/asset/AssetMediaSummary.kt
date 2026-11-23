package app.aroundtheblock.wallet.ui.asset

import app.aroundtheblock.wallet.core.model.AssetMediaEntry
import app.aroundtheblock.wallet.core.model.AssetMediaKind

/**
 * What the detail screen says about the audio and video an asset declares (ASSET-MEDIA-FIXES):
 * one plain line per file, naming the kind, with the size when it is already known.
 *
 * It is disclosure only. Nothing here plays, opens or fetches a file, and the size comes from
 * [sizeOf], which the screen answers from an answer the wallet already has; a file whose size was
 * never learned gets the kind alone. Pictures get no line (the picture is on screen), and neither
 * does a document, a page or a file of unknown type: the wallet shows none of them and makes no
 * claim about them.
 */
object AssetMediaSummary {

    /** The most lines shown. Real assets declare one such file. */
    const val MAX_LINES = 4

    /** Between two lines of the one row. */
    const val LINE_BREAK = "\n"

    data class Line(val kind: AssetMediaKind, val sizeBytes: Long?)

    fun lines(media: List<AssetMediaEntry>, sizeOf: (String) -> Long?): List<Line> =
        media.asSequence()
            .filter { it.kind == AssetMediaKind.AUDIO || it.kind == AssetMediaKind.VIDEO }
            .distinctBy { it.url }
            .take(MAX_LINES)
            .map { Line(it.kind, sizeOf(it.url)?.takeIf { size -> size > 0 }) }
            .toList()
}
