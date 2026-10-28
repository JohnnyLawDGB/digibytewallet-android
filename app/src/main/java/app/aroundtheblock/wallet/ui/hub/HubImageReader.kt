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
import javax.inject.Inject

/** An image the user picked, read into memory for upload: its bytes and its type. */
class PickedImage(val bytes: ByteArray, val mimeType: String)

/** Why a picked image cannot be sent. */
enum class PickedImageProblem { UNREADABLE, WRONG_TYPE, TOO_LARGE }

/**
 * Reads an image the system photo picker handed back ([Uri] with a read grant for this app, no
 * storage permission) for a Hub chat post. It stops reading once past the server's 8 MB limit
 * (one 64 KiB chunk at most), so a larger file is refused without being held in memory whole, and takes only the types the
 * server takes. The server checks the image again by its content.
 *
 * A JPEG leaves the phone without its metadata ([prepare]): a camera photo's EXIF carries the
 * place it was taken (GPS), the time and the phone. It is decoded with its EXIF orientation applied
 * and its long side capped at [MAX_SIDE] (as the server caps it), re-encoded, and then put through
 * [JpegMetadata.strip] whatever the encoder wrote. PNG, WebP and GIF go as they are: re-encoding a
 * GIF here would drop its animation, and the server re-encodes every image with sharp, which keeps
 * no metadata (hub-media.js `processImage`), before anyone can see it.
 */
class HubImageReader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** The image, or why it cannot be sent. */
    suspend fun read(uri: Uri): Result = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val type = resolver.getType(uri)
            if (type == null || type !in DigiScopeClient.IMAGE_TYPES) return@withContext Result.Refused(PickedImageProblem.WRONG_TYPE)
            val bytes = resolver.openInputStream(uri)?.use { readCapped(it) }
            val prepared = bytes?.takeIf { it.isNotEmpty() && it.size <= DigiScopeClient.MAX_IMAGE_BYTES }
                ?.let { prepare(it, type, ::reencodeJpeg) }
            when {
                bytes == null || bytes.isEmpty() -> Result.Refused(PickedImageProblem.UNREADABLE)
                bytes.size > DigiScopeClient.MAX_IMAGE_BYTES -> Result.Refused(PickedImageProblem.TOO_LARGE)
                prepared == null -> Result.Refused(PickedImageProblem.UNREADABLE)
                else -> Result.Read(PickedImage(prepared, type))
            }
        } catch (e: Exception) {
            Result.Refused(PickedImageProblem.UNREADABLE)
        }
    }

    /** [input] read until its end or until it is past the 8 MB limit, whichever is first. */
    private fun readCapped(input: java.io.InputStream): ByteArray {
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

    /** [jpeg] decoded at a power-of-two sample size that keeps its long side at least [MAX_SIDE], or null. */
    private fun decodeBounded(jpeg: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        return if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else BitmapFactory.decodeByteArray(
            jpeg, 0, jpeg.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight) },
        )
    }

    /**
     * [jpeg] decoded with its EXIF orientation applied, its long side at most [MAX_SIDE], and
     * encoded again as a JPEG, or null when it does not decode. Memory is bounded by decoding at a
     * power-of-two sample size first ([sampleSizeFor]).
     */
    private fun reencodeJpeg(jpeg: ByteArray): ByteArray? {
        var decoded: Bitmap? = null
        var oriented: Bitmap? = null
        return try {
            val bitmap = decodeBounded(jpeg) ?: return null
            decoded = bitmap
            val orientation = ExifInterface(ByteArrayInputStream(jpeg))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val matrix = orientationMatrix(orientation)
            val longSide = maxOf(bitmap.width, bitmap.height)
            if (longSide > MAX_SIDE) {
                val scale = MAX_SIDE.toFloat() / longSide
                matrix.postScale(scale, scale)
            }
            val out = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            oriented = out
            ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()
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

    companion object {
        /** The long side an uploaded JPEG is capped at, as the server caps every image (hub-media.js `MAX_SIDE`). */
        const val MAX_SIDE = 2048
        private const val JPEG_QUALITY = 90

        /**
         * The bytes that are uploaded for an image of [mimeType]. A JPEG is [reencode]d and then
         * stripped ([JpegMetadata.strip]) whatever the encoder wrote; one that does not re-encode
         * is stripped as it is (its orientation is then lost, never its metadata kept); one that
         * cannot be stripped is not sent (null). Every other type is sent unchanged: see the class.
         */
        fun prepare(bytes: ByteArray, mimeType: String, reencode: (ByteArray) -> ByteArray?): ByteArray? =
            if (mimeType == "image/jpeg") JpegMetadata.strip(reencode(bytes) ?: bytes) else bytes

        /** The largest power of two that leaves the long side of [width] x [height] at least [MAX_SIDE]. */
        fun sampleSizeFor(width: Int, height: Int): Int {
            var sample = 1
            while (maxOf(width, height) / (sample * 2) >= MAX_SIDE) sample *= 2
            return sample
        }
    }

    sealed class Result {
        class Read(val image: PickedImage) : Result()
        class Refused(val problem: PickedImageProblem) : Result()
    }
}
