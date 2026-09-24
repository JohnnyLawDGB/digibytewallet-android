package io.digibyte

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebStorage
import io.digibyte.core.IdentitySessionWipe
import io.digibyte.core.digiscope.DigiScopeClient
import io.digibyte.core.hub.HubWebSocket
import io.digibyte.ui.digistamp.DigistampWebViewHost
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The app-side half of a wallet wipe: the sessions the wallet's identity opened OUTSIDE the
 * wallet's own stores. `core` cannot see them, so `WalletManager.wipeWallet` reaches this
 * through [IdentitySessionWipe] (wired in `AppModule`) — one wipe, whichever screen started it.
 *
 *  - **Hub.** The persisted token is removed by `core`'s eraser; what is left is the copy the
 *    running client holds in memory and the socket that was opened with it.
 *  - **DigiStamp.** The site signs in with Digi-ID and keeps its session in the WebView's
 *    cookie jar and web storage, which belong to the app, not to any one view. The retained
 *    view is released, then the jar and the storage are emptied through [CookieManager] and
 *    [WebStorage] — see [WebSessionEnd] for what the result of that rests on. No WebView is ever
 *    created for this: on a device whose WebView implementation is unavailable that would throw,
 *    and there the files are removed instead.
 *
 * Both parts are always attempted; the result is true only when both were confirmed. The
 * WebView calls run on the main thread — inline when the wipe already runs there (the launch
 * backstop), otherwise posted and awaited for a bounded time.
 */
class IdentitySessionEraser internal constructor(
    private val endHubSession: () -> Boolean,
    private val endWebSession: () -> Boolean,
    private val onMainThread: (() -> Boolean) -> Boolean,
) : IdentitySessionWipe {

    constructor(
        context: Context,
        digiScopeClient: dagger.Lazy<DigiScopeClient>,
        hubWebSocket: dagger.Lazy<HubWebSocket>,
    ) : this(
        endHubSession = {
            hubWebSocket.get().disconnect()
            digiScopeClient.get().logout()
            !digiScopeClient.get().isLoggedIn()
        },
        endWebSession = { clearWebSession(context.applicationContext) },
        onMainThread = ::runOnMainThread,
    )

    override fun eraseIdentitySessions(): Boolean {
        val hub = attempt("hub session", endHubSession)
        val web = attempt("web session") { onMainThread(endWebSession) }
        return hub && web
    }

    private fun attempt(what: String, block: () -> Boolean): Boolean = try {
        block().also { ok -> if (!ok) Log.w(TAG, "$what: not confirmed") }
    } catch (t: Throwable) {
        Log.w(TAG, "$what: ${t.javaClass.simpleName}")
        false
    }

    private companion object {
        const val TAG = "IdentitySessionEraser"

        /** Long enough for a first WebView-implementation load on an old phone, short enough
         *  that a busy main thread turns into "not confirmed" rather than a stalled wipe. */
        const val MAIN_THREAD_WAIT_SECONDS = 5L

        /** Must be called on the main thread. */
        fun clearWebSession(context: Context): Boolean = WebSessionEnd(
            releaseRetainedView = { DigistampWebViewHost.destroy() },
            removeCookies = {
                // The callback is the cookie store's own answer, and it arrives on this thread
                // once this pass over the looper is done — so it is carried to the log rather
                // than thrown away, and nothing waits on it here.
                CookieManager.getInstance().removeAllCookies { removed ->
                    Log.i(TAG, "web session: cookie store reported removal=$removed")
                }
            },
            flushCookies = { CookieManager.getInstance().flush() },
            clearWebStorage = { WebStorage.getInstance().deleteAllData() },
            removeSessionFiles = {
                webViewDirs(context).map { !it.exists() || (it.deleteRecursively() && !it.exists()) }.all { it }
            },
        ).end()

        /** Where the platform keeps an app's WebView profile and its cache. Named, not asked
         *  for through getDir(), which would create the directory it is about to remove. */
        fun webViewDirs(context: Context): List<File> = listOf(
            File(context.dataDir, "app_webview"),
            File(context.cacheDir, "WebView"),
        )

        fun runOnMainThread(block: () -> Boolean): Boolean {
            if (Looper.myLooper() == Looper.getMainLooper()) return block()
            val done = CountDownLatch(1)
            val result = AtomicBoolean(false)
            Handler(Looper.getMainLooper()).post {
                try {
                    result.set(block())
                } catch (t: Throwable) {
                    Log.w(TAG, "web session: ${t.javaClass.simpleName}")
                } finally {
                    done.countDown()
                }
            }
            return done.await(MAIN_THREAD_WAIT_SECONDS, TimeUnit.SECONDS) && result.get()
        }
    }
}

/**
 * Ending the DigiStamp web session, with each platform call behind a plain-Kotlin seam so the
 * routine itself runs on a JVM.
 *
 * What the result rests on, and what it deliberately does not: the cookie store takes the removal
 * on its own thread and answers through the callback it is handed, so a question put to it in the
 * next statement is a question about the state BEFORE the removal — a session that really was ended
 * would report itself unfinished. Web storage offers no answer at all. So the evidence is the
 * removal the store accepted and the flush that forced its file to disk, and no read-back decides
 * this. Every part is attempted, whatever happened to the one before it.
 *
 * A platform with no WebView implementation throws on the first of these calls: this process never
 * had a view, nothing holds the session's files open, and removing them is then both possible and
 * the whole job — that is what [removeSessionFiles] is for, and its result is read back.
 */
internal class WebSessionEnd(
    private val releaseRetainedView: () -> Unit,
    private val removeCookies: () -> Unit,
    private val flushCookies: () -> Unit,
    private val clearWebStorage: () -> Unit,
    private val removeSessionFiles: () -> Boolean,
) {
    fun end(): Boolean {
        // A retained view holds its session in memory. Its failure must not skip the jar.
        val viewReleased = runCatching { releaseRetainedView() }
            .onFailure { Log.w(TAG, "web session: retained view not released (${it.javaClass.simpleName})") }
            .isSuccess
        val storeEnded = try {
            removeCookies()
            flushCookies()
            clearWebStorage()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "web session: no WebView implementation (${t.javaClass.simpleName}); removing its files")
            removeSessionFiles()
        }
        return viewReleased && storeEnded
    }

    private companion object {
        const val TAG = "IdentitySessionEraser"
    }
}
