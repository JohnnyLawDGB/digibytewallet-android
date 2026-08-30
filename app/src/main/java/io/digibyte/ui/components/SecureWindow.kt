package io.digibyte.ui.components

import android.app.Activity
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Reference-counted owner of FLAG_SECURE. One instance per process; the count is the number of
 * on-screen composables that need the window secure.
 *
 * A plain set-on-compose / clear-on-dispose per screen is wrong under NavHost: the destination
 * composes (set) while the source is still on screen, and the source disposes (clear) at the end
 * of the transition, so a secure->secure navigation ends with the flag OFF for the destination.
 * seed_verify -> seed_passphrase is the only route into passphrase entry, which means the
 * passphrase was typed into a capturable window. Counting holders and clearing only when the last
 * one leaves is what makes that sequence end secure.
 *
 * [apply] receives the desired state so the arithmetic is testable without a Window.
 */
class SecureWindowFlag(private val apply: (secure: Boolean) -> Unit) {
    var holders: Int = 0
        private set

    val isSecure: Boolean get() = holders > 0

    @Synchronized
    fun acquire() {
        if (holders++ == 0) apply(true)
    }

    @Synchronized
    fun release() {
        if (holders == 0) return
        if (--holders == 0) apply(false)
    }
}

private var currentWindow: Window? = null

private val processFlag = SecureWindowFlag { secure ->
    val window = currentWindow ?: return@SecureWindowFlag
    if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
}

/**
 * Holds FLAG_SECURE on the Activity window for as long as the calling composable is on screen.
 *
 * FLAG_SECURE blocks screenshots, screen recording and casting of the window. Every screen that
 * shows OR takes a recovery phrase or passphrase must call this: the words are exactly as
 * capturable while being typed as while being displayed, and until this helper existed only the
 * display screens set the flag. Holders are counted (see [SecureWindowFlag]) so the flag survives
 * a secure->secure navigation and is cleared only when the last secure screen leaves.
 */
@Composable
fun SecureWindow() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        (context as? Activity)?.window?.let { currentWindow = it }
        processFlag.acquire()
        onDispose { processFlag.release() }
    }
}
