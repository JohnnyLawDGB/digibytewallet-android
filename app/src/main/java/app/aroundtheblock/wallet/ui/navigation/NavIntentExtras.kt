package app.aroundtheblock.wallet.ui.navigation

import android.content.Intent
import android.os.Bundle

/**
 * No launching intent chooses a screen (NAV-DEEPLINK).
 *
 * The app opens its screens only through its own navigation calls. AndroidX Navigation's own
 * extras (prefix `android-support-nav:`) are therefore removed from the launching intent before
 * the NavHost exists (onCreate) and from every later intent (onNewIntent).
 */
object NavIntentExtras {

    /** The prefix of every extra NavController's deep-link handling reads. */
    const val NAV_PREFIX = "android-support-nav:"

    /** The keys in [keys] that must not reach the NavController. */
    fun keysToStrip(keys: Set<String>): Set<String> = keys.filterTo(mutableSetOf()) { it.startsWith(NAV_PREFIX) }

    /**
     * Removes the navigation extras from [intent], in place. If its extras cannot be read at all, none
     * of them is trusted and all are dropped. The app's own launch markers then read false, which is
     * already what they read when unreadable (FreshStartAfterWipe).
     */
    fun strip(intent: Intent?) {
        if (intent == null) return
        try {
            val keys = intent.extras?.keySet() ?: return
            for (key in keysToStrip(keys)) intent.removeExtra(key)
        } catch (_: RuntimeException) {
            intent.replaceExtras(Bundle())
        }
    }
}
