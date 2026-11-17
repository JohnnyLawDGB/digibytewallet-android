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
    /** True when no wallet other than legacy exists in any form (entries or records on disk). */
    private val onlyLegacyExists: () -> Boolean = { false },
) {
    /** True when the web data is known to belong to [activeId]. */
    fun run(): Boolean {
        val owner = readOwner()
        if (owner == activeId) return true
        // Upgrade: before multi-wallet only legacy could have made this session, so adopt it as
        // owner without a logout. Any other wallet present keeps the fail-closed clear below.
        if (owner == null && activeId == LEGACY && onlyLegacyExists()) return writeOwner(activeId)
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
        private const val LEGACY = app.aroundtheblock.wallet.core.wallets.WalletIds.LEGACY
        const val PREFS = "dgb_web_session_owner"
        const val KEY_OWNER = "owner_wallet_id"
        /**
         * The owner a full wipe leaves ([markWiped]). No wallet id is ever this, so the next launch
         * always clears the web data, even for a new seed made as `legacy` again. Not a removal: an
         * absent owner with only legacy is adopted without clearing (the upgrade rule above).
         */
        const val OWNER_WIPED = "wiped"

        /** The full wipe's part: the web data now belongs to no wallet. True only when the write landed. */
        fun markWiped(context: Context): Boolean {
            val app = context.applicationContext ?: context
            return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_OWNER, OWNER_WIPED).commit()
        }

        @Volatile
        var verified: Boolean = false
            internal set

        /**
         * THE list of where the platform keeps an app's WebView data: every profile directory,
         * including per-process suffixed ones, its caches and the HTTP auth database. The launch
         * isolation and the wipe's fallback file removal (IdentitySessionEraser) both use it. Named,
         * not asked for through getDir(), which would create the directory it is about to remove.
         */
        fun webDataDirs(dataDir: File, cacheDir: File): List<File> =
            (dataDir.listFiles { f -> f.name.startsWith("app_webview") }?.toList() ?: emptyList()) +
                listOf(
                    File(cacheDir, "WebView"),
                    File(cacheDir, "org.chromium.android_webview"),
                    File(dataDir, "databases/http_auth.db"),
                )

        fun isolateAtLaunch(context: Context) {
            val app = context.applicationContext ?: context
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            verified = WebSessionIsolation(
                readOwner = { prefs.getString(KEY_OWNER, null) },
                writeOwner = { prefs.edit().putString(KEY_OWNER, it).commit() },
                webDataDirs = { webDataDirs(app.dataDir, app.cacheDir) },
                activeId = ActiveWallet.scope(app).walletId,
                onlyLegacyExists = { !ActiveWallet.registry(app).hasNonLegacyWallet() },
            ).run()
        }
    }
}
