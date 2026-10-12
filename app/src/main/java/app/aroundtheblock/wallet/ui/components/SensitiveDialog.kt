package app.aroundtheblock.wallet.ui.components

import android.os.Build
import android.util.Log
import android.view.View
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

private const val TAG = "SensitiveDialog"

/**
 * A dialog that protects its own window: every send confirmation and the PIN dialog use it.
 *
 * A Compose [Dialog] is a window of its own, so nothing set on the screen behind it reaches it.
 * While this dialog is shown its window:
 *  - refuses touches that arrive through another window drawn over it (the obscured-touch filter
 *    on the window's decor view, which sees every touch before the dialog's content does);
 *  - holds FLAG_SECURE through the counted [SecureWindow] helper, so it is left out of
 *    screenshots, screen recordings and casting, and the task-switcher snapshot of the app is
 *    blank while it is up — the keypad and the amounts never reach one;
 *  - asks the system to hide other apps' overlay windows (Android 12 and later; the
 *    HIDE_OVERLAY_WINDOWS permission is declared in the manifest).
 *
 * If the dialog's window cannot be found the content is not shown and the dialog is dismissed:
 * a confirmation or a PIN entry is never offered on an unprotected window.
 */
@Composable
fun SensitiveDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        val window = dialogWindowOf(LocalView.current)
        if (window == null) {
            val dismiss = rememberUpdatedState(onDismissRequest)
            LaunchedEffect(Unit) {
                Log.e(TAG, "no dialog window found; the dialog is dismissed unshown")
                dismiss.value()
            }
            return@Dialog
        }
        SecureWindow(window)
        DisposableEffect(window) {
            window.decorView.filterTouchesWhenObscured = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) window.setHideOverlayWindows(true)
            onDispose {}
        }
        content()
    }
}

/** The window of the dialog [view] is drawn in: the one its DialogWindowProvider owns. */
private fun dialogWindowOf(view: View): Window? {
    var v: Any? = view
    while (v != null) {
        if (v is DialogWindowProvider) return v.window
        v = (v as? View)?.parent
    }
    return null
}
