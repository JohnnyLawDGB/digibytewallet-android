package io.digibyte.ui.settings

import android.content.Context
import io.digibyte.BuildConfig
import io.digibyte.core.isTestnet
import java.net.URLEncoder

/** Base URL of the digiscope.me wallet bug/feedback portal (see the Wallet Dev
 *  portal brief). Update here if the portal path changes. */
const val BUG_REPORT_BASE_URL = "https://digiscope.me/report"

/**
 * Pure URL assembly: base + URL-encoded query params in the given order,
 * omitting empty values. No Android dependencies, so it's unit-testable.
 */
fun assembleReportUrl(base: String, params: List<Pair<String, String>>): String {
    val query = params
        .filter { it.second.isNotEmpty() }
        .joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
    return if (query.isEmpty()) base else "$base?$query"
}

/**
 * Build the pre-filled bug-report deep link. Captures the device / version /
 * sync facts the portal's intake needs — so users don't mistype them — including
 * the memory **page size** (4096 vs 16384), the exact datum that short-circuits
 * new-device crash triage. The portal reads these query params to pre-fill its
 * device/version block; the user still writes the description.
 */
fun buildBugReportUrl(context: Context, syncStage: String): String {
    val pageSize = runCatching {
        android.system.Os.sysconf(android.system.OsConstants._SC_PAGE_SIZE)
    }.getOrDefault(0L)
    return assembleReportUrl(
        BUG_REPORT_BASE_URL,
        listOf(
            "type" to "bug",
            "model" to android.os.Build.MODEL,
            "mfr" to android.os.Build.MANUFACTURER,
            "android" to android.os.Build.VERSION.RELEASE,
            "sdk" to android.os.Build.VERSION.SDK_INT.toString(),
            "appver" to BuildConfig.VERSION_NAME,
            "build" to BuildConfig.VERSION_CODE.toString(),
            "pagesize" to (if (pageSize > 0) pageSize.toString() else ""),
            "network" to if (isTestnet(context)) "testnet" else "mainnet",
            "sync" to syncStage,
        )
    )
}
