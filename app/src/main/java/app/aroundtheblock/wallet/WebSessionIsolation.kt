package app.aroundtheblock.wallet

import android.content.Context
import android.util.Log
import app.aroundtheblock.wallet.core.wallets.ActiveWallet
import java.io.File

/**
 * Keeps one wallet's DigiStamp/WebView session from greeting another. The switch's own clear of the
 * cookie jar is asynchronous and the process ends right after it, so isolation does not rest on it:
 * the owner wallet id of the web data is stored (shared, not per wallet, commit()ed) and every launch,
 * before any WebView or CookieManager exists, deletes the web data synchronously when the owner is
 * not the wallet this process runs. If the delete cannot be verified the owner is left as it was and
 * [verified] stays false, so the web entry refuses to start (fail closed).
 */
internal class WebSessionIsolation(
    private val readOwner: () -> String?,
    private val writeOwner: (String) -> Boolean,
    private val webDataDirs: () -> List<File>,
    private val activeId: String,
) {
    /** True when the web data is known to belong to [activeId]. */
    fun run(): Boolean {
        val owner = readOwner()
        if (owner == activeId) return true
        val dirs = webDataDirs()
        val gone = dirs.all { !it.exists() || (it.deleteRecursively() && !it.exists()) }
        if (!gone) {
            runCatching { Log.w(TAG, "web data of another wallet could not be removed; web entry refused") }
            return false
        }
        return writeOwner(activeId)
    }

    companion object {
        private const val TAG = "WebSessionIsolation"
        const val PREFS = "dgb_web_session_owner"
        const val KEY_OWNER = "owner_wallet_id"

        @Volatile
        var verified: Boolean = false
            internal set

        /** Every WebView profile directory the platform may use, including per-process suffixed ones. */
        fun webDataDirs(dataDir: File, cacheDir: File): List<File> =
            (dataDir.listFiles { f -> f.name.startsWith("app_webview") }?.toList() ?: emptyList()) +
                File(cacheDir, "WebView")

        fun isolateAtLaunch(context: Context) {
            val app = context.applicationContext ?: context
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            verified = WebSessionIsolation(
                readOwner = { prefs.getString(KEY_OWNER, null) },
                writeOwner = { prefs.edit().putString(KEY_OWNER, it).commit() },
                webDataDirs = { webDataDirs(app.dataDir, app.cacheDir) },
                activeId = ActiveWallet.scope(app).walletId,
            ).run()
        }
    }
}
