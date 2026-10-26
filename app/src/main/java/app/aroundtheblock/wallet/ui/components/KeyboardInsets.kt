package app.aroundtheblock.wallet.ui.components

import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView

/**
 * The soft keyboard's height, for every screen that makes room for it. Use these, never
 * `imePadding()` or `WindowInsets.ime` directly — `KeyboardInsetsGateTest` holds that.
 *
 * Below Android 11 Compose's IME inset is driven by a compat animation, and an animation that
 * never ends leaves the last keyboard height in place: Compose then ignores every later inset
 * until another keyboard comes and goes. On the Note 8 (Android 9, 2026-10-25, 4.1.2 Wave 10) a
 * keyboard closed while another window was over the wallet, and every screen with imePadding
 * kept 874 px of it — Receive lost its lower half, format chips included, with no keyboard on
 * screen. The window's own insets were right the whole time (system bottom 126 = stable bottom
 * 126), so below 30 the height is read from them on every layout pass instead. 30+ keeps
 * Compose's inset: there it comes from the platform WindowInsets.Type.ime(), not the compat path.
 */
fun Modifier.keyboardPadding(): Modifier =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) imePadding()
    else composed {
        val px = legacyKeyboardBottomPx()
        val bottom = with(LocalDensity.current) { px.toDp() }
        padding(bottom = bottom)
    }

/** The keyboard's bottom inset in px, for code that reacts to the keyboard rather than padding for it. */
@Composable
fun keyboardBottomPx(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) WindowInsets.ime.getBottom(LocalDensity.current)
    else legacyKeyboardBottomPx()

/**
 * Below Android 11 the platform reports the keyboard only inside the system-window inset:
 * with adjustResize its bottom grows by the keyboard and the stable inset (the navigation bar)
 * does not, so the difference is the keyboard. Negative means a stale or partial dispatch.
 */
internal fun legacyKeyboardPx(systemWindowInsetBottom: Int, stableInsetBottom: Int): Int =
    (systemWindowInsetBottom - stableInsetBottom).coerceAtLeast(0)

@Composable
private fun legacyKeyboardBottomPx(): Int {
    val view = LocalView.current
    var px by remember { mutableIntStateOf(currentLegacyKeyboardPx(view)) }
    DisposableEffect(view) {
        val listener = ViewTreeObserver.OnGlobalLayoutListener { px = currentLegacyKeyboardPx(view) }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose { view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }
    return px
}

@Suppress("DEPRECATION") // systemWindowInsetBottom/stableInsetBottom are the API < 30 surface by design
private fun currentLegacyKeyboardPx(view: View): Int {
    val insets = view.rootWindowInsets ?: return 0
    return legacyKeyboardPx(insets.systemWindowInsetBottom, insets.stableInsetBottom)
}
