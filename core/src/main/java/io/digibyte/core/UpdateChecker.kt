package io.digibyte.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class AppUpdate(
    val versionName: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val htmlUrl: String
)

class UpdateChecker(private val client: OkHttpClient) {

    companion object {
        private const val RELEASES_URL =
            "https://api.github.com/repos/JohnnyLawDGB/digibytewallet-android/releases/latest"
    }

    /**
     * Check if a newer version is available on GitHub.
     * Returns [AppUpdate] if a newer version exists, null otherwise.
     */
    suspend fun checkForUpdate(currentVersion: String): AppUpdate? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(RELEASES_URL)
                .header("Accept", "application/vnd.github.v3+json")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)

                val tagName = json.getString("tag_name").removePrefix("v")
                val releaseNotes = json.optString("body", "").take(500)
                val htmlUrl = json.getString("html_url")

                // Find APK download URL from assets
                val assets = json.optJSONArray("assets")
                var downloadUrl = htmlUrl // fallback to release page
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        if (asset.getString("name").endsWith(".apk")) {
                            downloadUrl = asset.getString("browser_download_url")
                            break
                        }
                    }
                }

                // Compare versions
                if (isNewer(tagName, currentVersion)) {
                    AppUpdate(
                        versionName = tagName,
                        releaseNotes = releaseNotes,
                        downloadUrl = downloadUrl,
                        htmlUrl = htmlUrl
                    )
                } else null
            }
        } catch (e: Exception) {
            android.util.Log.w("UpdateChecker", "Update check failed: ${e.message}")
            null
        }
    }

    private fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split(".").mapNotNull { it.replace(Regex("[^0-9]"), "").toIntOrNull() }
        val l = local.split(".").mapNotNull { it.replace(Regex("[^0-9]"), "").toIntOrNull() }
        for (i in 0 until maxOf(r.size, l.size)) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv > lv) return true
            if (rv < lv) return false
        }
        return false
    }
}
