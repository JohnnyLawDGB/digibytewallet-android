package app.aroundtheblock.wallet.ui.hub

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import app.aroundtheblock.wallet.core.digiscope.DigiScopeClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import javax.inject.Inject

/** An image the user picked, ready to upload: its bytes and its type, as found in its content. */
class PickedImage(val bytes: ByteArray, val mimeType: String)

/** Why a picked image cannot be sent. */
enum class PickedImageProblem { UNREADABLE, WRONG_TYPE, TOO_LARGE, TOO_MANY_PIXELS, NOT_PREPARED }

/** Reads a JPEG's size from its header, and decodes and encodes it again. */
interface JpegCodec {
    /** Width and height from the header alone (nothing decoded), or null when it cannot be read. */
    fun bounds(jpeg: ByteArray): Pair<Int, Int>?

    /** Decoded at [sampleSize] (a power of two), oriented, and encoded again; null when either step fails. */
    fun reencode(jpeg: ByteArray, sampleSize: Int): ByteArray?
}

/**
 * Reads an image the system photo picker handed back ([Uri] with a read grant for this app, no
 * storage permission) for a Hub chat post, and makes the bytes that are uploaded ([prepare]).
 *
 * The type is decided by the bytes, never by what the picker or the file's name claims
 * ([sniff]): PNG, JPEG, GIF and WebP, the types the server takes; anything else is refused before
 * an upload, and the upload is labelled with the type found.
 *
 * A JPEG is always decoded and encoded again ([BitmapJpegCodec]): the new file holds the
 * picture and nothing else, so a camera photo's EXIF (the place it was taken, the time, the
 * phone), XMP, comments and any bytes after its end never leave the phone. If it cannot be decoded
 * or encoded, it is not sent: the original bytes are never the fallback.
 *
 * PNG, WebP and GIF go as they are: re-encoding a GIF here would drop its animation, and the
 * server re-encodes every image with sharp, which keeps no metadata (hub-media.js
 * `processImage`), before anyone else can see it.
 */
class HubImageReader(
    private val context: Context,
    private val codec: JpegCodec,
) {
    @Inject constructor(@ApplicationContext context: Context) : this(context, BitmapJpegCodec)

    /** The image, or why it cannot be sent. */
    suspend fun read(uri: Uri): Result = withContext(Dispatchers.IO) {
        try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { readCapped(it) }
            when {
                bytes == null || bytes.isEmpty() -> Result.Refused(PickedImageProblem.UNREADABLE)
                bytes.size > DigiScopeClient.MAX_IMAGE_BYTES -> Result.Refused(PickedImageProblem.TOO_LARGE)
                else -> prepare(bytes, codec)
            }
        } catch (ignored: Exception) {
            Result.Refused(PickedImageProblem.UNREADABLE)
        }
    }

    sealed class Result {
        class Read(val image: PickedImage) : Result()
        class Refused(val problem: PickedImageProblem) : Result()
    }

    companion object {
        const val JPEG = "image/jpeg"
        const val PNG = "image/png"
        const val GIF = "image/gif"
        const val WEBP = "image/webp"

        /** The long side an uploaded JPEG is capped at, as the server caps every image (hub-media.js `MAX_SIDE`). */
        const val MAX_SIDE = 2048

        /** The most pixels a JPEG may claim in its header before it is decoded on the phone: 50 megapixels. */
        const val MAX_PIXELS = 50_000_000L

        /**
         * The bytes uploaded for [bytes]: a JPEG re-encoded by [codec], any other allowed type
         * unchanged, and anything else refused. The type is [sniff]ed from the bytes. A JPEG whose
         * header cannot be read, that claims more than [MAX_PIXELS], whose re-encode fails, or whose
         * re-encode is not a JPEG, is refused: only the re-encoder's output is ever sent for a JPEG.
         * It is decoded at the sample size [sampleSizeFor] picks, so the decoded picture's long side
         * is at most [MAX_SIDE] before anything else is done with it.
         */
        fun prepare(bytes: ByteArray, codec: JpegCodec): Result = when (val type = sniff(bytes)) {
            null -> Result.Refused(PickedImageProblem.WRONG_TYPE)
            JPEG -> prepareJpeg(bytes, codec)
            else -> Result.Read(PickedImage(bytes, type))
        }

        private fun prepareJpeg(bytes: ByteArray, codec: JpegCodec): Result {
            val bounds = runCatching { codec.bounds(bytes) }.getOrNull()?.takeIf { (w, h) -> w > 0 && h > 0 }
            val tooManyPixels = bounds != null && bounds.first.toLong() * bounds.second > MAX_PIXELS
            // Only the re-encoder's output is ever sent for a JPEG: there is no path to the original.
            val reencoded = bounds?.takeIf { !tooManyPixels }
                ?.let { (w, h) -> runCatching { codec.reencode(bytes, sampleSizeFor(w, h)) }.getOrNull() }
                ?.takeIf { sniff(it) == JPEG && it.size <= DigiScopeClient.MAX_IMAGE_BYTES }
            return when {
                tooManyPixels -> Result.Refused(PickedImageProblem.TOO_MANY_PIXELS)
                reencoded == null -> Result.Refused(PickedImageProblem.NOT_PREPARED)
                else -> Result.Read(PickedImage(reencoded, JPEG))
            }
        }

        /** The image type [bytes] start as (by their magic bytes), or null when they are none of the four. */
        fun sniff(bytes: ByteArray): String? {
            fun at(offset: Int, vararg expected: Int) =
                bytes.size >= offset + expected.size &&
                    expected.indices.all { (bytes[offset + it].toInt() and 0xFF) == expected[it] }
            fun ascii(offset: Int, text: String) =
                bytes.size >= offset + text.length && text.indices.all { bytes[offset + it].toInt() == text[it].code }
            return when {
                at(0, 0xFF, 0xD8, 0xFF) -> JPEG
                at(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> PNG
                ascii(0, "GIF87a") || ascii(0, "GIF89a") -> GIF
                ascii(0, "RIFF") && ascii(8, "WEBP") -> WEBP
                else -> null
            }
        }

        /** The smallest power of two that brings the long side of [width] x [height] to at most [MAX_SIDE] (rounding up). */
        fun sampleSizeFor(width: Int, height: Int): Int {
            val longSide = maxOf(width, height).toLong()
            var sample = 1
            while ((longSide + sample - 1) / sample > MAX_SIDE) sample *= 2
            return sample
        }

        /** [input] read until its end or until it is past the 8 MB limit (one 64 KiB chunk at most), whichever is first. */
        private fun readCapped(input: InputStream): ByteArray {
            val limit = DigiScopeClient.MAX_IMAGE_BYTES.toInt()
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            var n = 0
            while (buffer.size() <= limit && n >= 0) {
                n = input.read(chunk)
                if (n > 0) buffer.write(chunk, 0, n)
            }
            return buffer.toByteArray()
        }
    }
}

/**
 * The app's [JpegCodec]: reads the size from the header with [BitmapFactory] (`inJustDecodeBounds`,
 * nothing decoded), decodes at the sample size [HubImageReader.prepare] chose (memory stays
 * bounded), applies the EXIF orientation, caps the long side at [HubImageReader.MAX_SIDE], and
 * writes a new JPEG with [Bitmap.compress], which writes the picture and no EXIF, XMP or comment.
 */
object BitmapJpegCodec : JpegCodec {
    private const val QUALITY = 90

    override fun bounds(jpeg: ByteArray): Pair<Int, Int>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        return if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else bounds.outWidth to bounds.outHeight
    }

    override fun reencode(jpeg: ByteArray, sampleSize: Int): ByteArray? {
        var decoded: Bitmap? = null
        var oriented: Bitmap? = null
        return try {
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options) ?: return null
            decoded = bitmap
            val orientation = ExifInterface(ByteArrayInputStream(jpeg))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val matrix = orientationMatrix(orientation)
            val longSide = maxOf(bitmap.width, bitmap.height)
            if (longSide > HubImageReader.MAX_SIDE) {
                val scale = HubImageReader.MAX_SIDE.toFloat() / longSide
                matrix.postScale(scale, scale)
            }
            val out = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            oriented = out
            ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }.toByteArray()
        } catch (ignored: Exception) {
            null
        } finally {
            if (oriented !== decoded) oriented?.recycle()
            decoded?.recycle()
        }
    }

    /** The turn and flip EXIF [orientation] (1 to 8) asks for. */
    private fun orientationMatrix(orientation: Int): Matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(-90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
            else -> Unit
        }
    }
}
