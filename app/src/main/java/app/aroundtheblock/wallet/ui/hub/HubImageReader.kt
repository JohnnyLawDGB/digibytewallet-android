package app.aroundtheblock.wallet.ui.hub

import android.content.Context
import android.net.Uri
import app.aroundtheblock.wallet.core.digiscope.DigiScopeClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
            val bytes = resolver.openInputStream(uri)?.use { input ->
                val limit = DigiScopeClient.MAX_IMAGE_BYTES.toInt()
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                while (buffer.size() <= limit) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    buffer.write(chunk, 0, n)
                }
                buffer.toByteArray()
            } ?: return@withContext Result.Refused(PickedImageProblem.UNREADABLE)
            when {
                bytes.isEmpty() -> Result.Refused(PickedImageProblem.UNREADABLE)
                bytes.size > DigiScopeClient.MAX_IMAGE_BYTES -> Result.Refused(PickedImageProblem.TOO_LARGE)
                else -> Result.Read(PickedImage(bytes, type))
            }
        } catch (e: Exception) {
            Result.Refused(PickedImageProblem.UNREADABLE)
        }
    }

    sealed class Result {
        class Read(val image: PickedImage) : Result()
        class Refused(val problem: PickedImageProblem) : Result()
    }
}
