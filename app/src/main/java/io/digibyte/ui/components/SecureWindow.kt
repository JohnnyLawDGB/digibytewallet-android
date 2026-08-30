package io.digibyte.ui.components

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Holds FLAG_SECURE on the Activity window for as long as the calling composable is on screen.
 *
 * FLAG_SECURE blocks screenshots, screen recording and casting of the window. Every screen that
 * shows OR takes a recovery phrase or passphrase must call this: the words are exactly as
 * capturable while being typed as while being displayed, and until this helper existed only the
 * display screens set the flag. Cleared on dispose so the rest of the app stays screenshot-able.
 */
@Composable
fun SecureWindow() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
