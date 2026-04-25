package io.digibyte.ui.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Open an external URL in a Chrome Custom Tab so the user can return to
 * the wallet via the tab's close button. Falls back to a plain ACTION_VIEW
 * intent (with NEW_TASK) on devices without any browser that supports
 * Custom Tabs — the user lands in a stand-alone browser task and Android's
 * recents/back gesture still surfaces the wallet again.
 *
 * Use this for anything outside our own app: marketplace, block explorer
 * links, release download URLs, etc. Don't use it for in-wallet navigation.
 */
fun openExternalUrl(context: Context, url: String) {
    val uri = Uri.parse(url)
    try {
        CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
            .launchUrl(context, uri)
    } catch (_: ActivityNotFoundException) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
