package io.digibyte.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Battery-optimization / background-sync helpers. The single biggest PREVENTION lever for
 * the "wallet drops to 0 peers when the screen is off, only a force-stop fixes it" wedge:
 * on Doze-heavy OEMs (Samsung One UI, Pixel) the OS suspends the WHOLE process — freezing
 * the sync loop and dropping every peer socket — unless the app is on the battery
 * allow-list. Doze even ignores wakelocks inside a running foreground service, so the
 * allow-list (or frequent foregrounding) is the only real lever we have short of FCM
 * (ruled out for sovereignty).
 *
 * We open the SYSTEM battery-optimization LIST (Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_
 * SETTINGS), which is always allowed and needs NO permission — deliberately NOT the direct
 * ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS dialog, which requires the permission and is
 * Play-policy-restricted. isIgnoringBatteryOptimizations() is a permission-free read.
 */
object BatteryOptimization {

    /** True if the wallet is already exempt from battery optimization (or the check is
     *  unavailable — fail OPEN so we never nag on a device that can't report it). */
    fun isExempt(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(true)
    }

    /** Open the system battery-optimization list so the user can set this app to Unrestricted.
     *  Play-policy-safe; no special permission. Falls back to the app-details screen. */
    fun openBatterySettings(context: Context) {
        val opened = runCatching {
            context.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
        if (!opened) {
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    /** Samsung One UI layers its own "sleeping apps" / Device Care restrictions ON TOP of the
     *  generic allow-list, so Samsung users need an extra "Never sleeping apps" hint. */
    val isSamsung: Boolean get() = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
}
