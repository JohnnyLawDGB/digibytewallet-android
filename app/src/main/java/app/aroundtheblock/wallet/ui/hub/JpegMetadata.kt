package app.aroundtheblock.wallet.ui.hub

import java.io.ByteArrayOutputStream

/**
 * Takes the metadata out of a JPEG without decoding it: every application segment but JFIF
 * (APP1 to APP15: EXIF with its GPS position, camera and time, XMP, ICC, maker data) and every
 * comment (COM) is dropped. The segments that make the picture (tables, frame, scan and the
 * entropy-coded data after it) are kept byte for byte. Pure: no Android, so it is tested on the JVM.
 */
object JpegMetadata {

    private const val MARKER = 0xFF
    private const val SOI = 0xD8
    private const val EOI = 0xD9
    private const val SOS = 0xDA
    private const val APP0 = 0xE0
    private const val APP15 = 0xEF
    private const val COM = 0xFE
    private const val FILL = -2

    /** [jpeg] without its metadata segments, or null when it is not a JPEG this can walk. */
    fun strip(jpeg: ByteArray): ByteArray? {
        val segments = segments(jpeg) ?: return null
        val out = ByteArrayOutputStream(jpeg.size)
        out.write(jpeg, 0, 2)
        segments.filterNot { it.marker in (APP0 + 1)..APP15 || it.marker == COM }
            .forEach { out.write(jpeg, it.start, it.end - it.start) }
        return out.toByteArray()
    }

    /** One stretch of the file: a marker and its bytes, [start] inclusive, [end] exclusive. */
    private class Segment(val marker: Int, val start: Int, val end: Int)

    /**
     * The file after its start marker, segment by segment, up to and including the end marker or
     * the start of scan (which runs to the end of the file: the picture data). Null when the file
     * does not start as a JPEG, a segment is malformed, or it ends before either.
     */
    private fun segments(jpeg: ByteArray): List<Segment>? {
        if (jpeg.size < 4 || u(jpeg, 0) != MARKER || u(jpeg, 1) != SOI) return null
        val found = mutableListOf<Segment>()
        var at = 2
        var next: Segment? = segmentAt(jpeg, at)
        while (next != null) {
            found += next
            at = next.end
            next = if (next.marker == EOI || next.marker == SOS || at + 1 >= jpeg.size) null else segmentAt(jpeg, at)
        }
        val last = found.lastOrNull()?.marker
        return found.takeIf { last == EOI || last == SOS }
    }

    /** The segment starting at [at], or null when there is none there. */
    private fun segmentAt(jpeg: ByteArray, at: Int): Segment? {
        val marker = if (at + 1 < jpeg.size) u(jpeg, at + 1) else -1
        val length = if (at + 3 < jpeg.size) (u(jpeg, at + 2) shl 8) or u(jpeg, at + 3) else -1
        return when {
            marker < 0 || u(jpeg, at) != MARKER -> null
            // A fill byte before a marker.
            marker == MARKER -> Segment(FILL, at, at + 1)
            // Markers with no length (EOI, RSTn, TEM).
            marker == EOI || marker in 0xD0..0xD7 || marker == 0x01 -> Segment(marker, at, at + 2)
            length < 2 || at + 2 + length > jpeg.size -> null
            // From the start of scan on, the rest is the picture.
            marker == SOS -> Segment(SOS, at, jpeg.size)
            else -> Segment(marker, at, at + 2 + length)
        }
    }

    /** True when [jpeg] still carries any segment [strip] removes. */
    fun hasMetadata(jpeg: ByteArray): Boolean = strip(jpeg)?.let { it.size != jpeg.size } ?: true

    private fun u(bytes: ByteArray, i: Int): Int = bytes[i].toInt() and 0xFF
}
