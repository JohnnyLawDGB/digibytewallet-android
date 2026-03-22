package io.digibyte.ui.components

import java.io.File
import java.net.Socket

object RootDetector {
    fun isRooted(): Boolean {
        val paths = listOf(
            "/system/app/Superuser.apk", "/system/xbin/su", "/sbin/su",
            "/system/bin/su", "/data/local/xbin/su", "/data/local/bin/su",
            "/system/sd/xbin/su", "/system/bin/failsafe/su",
            "/su/bin/su", "/data/adb/magisk"
        )
        return paths.any { File(it).exists() }
    }

    fun isFridaDetected(): Boolean {
        return try { Socket("127.0.0.1", 27042).close(); true } catch (_: Exception) { false }
    }
}
