package io.digibyte.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

data class AppUpdate(
    val versionName: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val htmlUrl: String,
    /** True when this came from a PRERELEASE. Surface it in the dialog — a beta
     *  carries knowingly-unverified surface and the user opted in to be offered it. */
    val isPrerelease: Boolean = false
)

class UpdateChecker(private val client: OkHttpClient) {

    companion object {
        private const val REPO = "JohnnyLawDGB/digibytewallet-android"

        /** Stable channel. GitHub's /releases/latest EXCLUDES prereleases by definition —
         *  which is exactly why tagging `-beta` stops it notifying anyone, and is the
         *  point of the tag rather than a defect in it. */
        private const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"

        /** Beta channel: the full list, newest first, INCLUDING prereleases. */
        private const val ALL_URL = "https://api.github.com/repos/$REPO/releases?per_page=10"
    }

    /**
     * Check whether a newer version is available.
     *
     * @param includePrereleases opt-in beta channel. Default false, so a prerelease can
     *   never be pushed to someone who did not ask for it.
     */
    suspend fun checkForUpdate(
        currentVersion: String,
        includePrereleases: Boolean = false
    ): AppUpdate? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(if (includePrereleases) ALL_URL else LATEST_URL)
                .header("Accept", "application/vnd.github.v3+json")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null

                // /releases returns an ARRAY (newest first); /releases/latest one object.
                val release: JSONObject = if (includePrereleases) {
                    val arr = JSONArray(body)
                    // Skip drafts: visible to maintainers, not installable by anyone.
                    (0 until arr.length())
                        .map { arr.getJSONObject(it) }
                        .firstOrNull { !it.optBoolean("draft", false) }
                        ?: return@withContext null
                } else {
                    JSONObject(body)
                }

                val tagName = release.getString("tag_name").removePrefix("v")
                // Flatten markdown BEFORE truncating: the dialog renders these with a plain
                // Text() that has no markdown support, so the raw body showed asterisks and
                // backticks to the user. Stripping first also means the 500-char budget is
                // spent on words rather than on markup. Truncating first would risk cutting
                // through a marker pair and leaving an unmatched one behind.
                val releaseNotes = io.digibyte.core.update.ReleaseNotesPlainText
                    .render(release.optString("body", ""))
                    .take(500)
                val htmlUrl = release.getString("html_url")
                val isPre = release.optBoolean("prerelease", false)

                var downloadUrl = htmlUrl // fallback: the release page
                release.optJSONArray("assets")?.let { assets ->
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        if (asset.getString("name").endsWith(".apk")) {
                            downloadUrl = asset.getString("browser_download_url")
                            break
                        }
                    }
                }

                if (isNewer(tagName, currentVersion)) {
                    AppUpdate(
                        versionName = tagName,
                        releaseNotes = releaseNotes,
                        downloadUrl = downloadUrl,
                        htmlUrl = htmlUrl,
                        isPrerelease = isPre
                    )
                } else null
            }
        } catch (e: Exception) {
            android.util.Log.w("UpdateChecker", "Update check failed: ${e.message}")
            null
        }
    }

    /**
     * Numeric compare, with one refinement: at EQUAL numbers a build carrying a
     * pre-release suffix is OLDER than one without.
     *
     * Without it, someone on 4.0.31-beta would never be offered the stable 4.0.31 —
     * both reduce to [4,0,31] once the suffix is stripped, stranding the tester on the
     * prerelease at exactly the moment the tested build ships.
     */
    internal fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split(".").mapNotNull { it.replace(Regex("[^0-9]"), "").toIntOrNull() }
        val l = local.split(".").mapNotNull { it.replace(Regex("[^0-9]"), "").toIntOrNull() }
        for (i in 0 until maxOf(r.size, l.size)) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv > lv) return true
            if (rv < lv) return false
        }
        // Numerically equal: stable (unsuffixed) supersedes a prerelease.
        return local.contains('-') && !remote.contains('-')
    }
}
