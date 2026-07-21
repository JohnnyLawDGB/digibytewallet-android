package io.digibyte.core.security

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Security test: Manifest & Configuration
 *
 * Static analysis of AndroidManifest.xml and build configuration
 * to verify security-critical settings.
 *
 * Published as part of the security audit.
 */
class ManifestSecurityTest {

    private val manifestFile = findManifest()

    private fun findManifest(): File {
        val candidates = listOf(
            "app/src/main/AndroidManifest.xml",
            "../app/src/main/AndroidManifest.xml",
            "../../app/src/main/AndroidManifest.xml"
        )
        return candidates.map { File(it) }.firstOrNull { it.exists() }
            ?: candidates.map { File(System.getProperty("user.dir"), it) }.firstOrNull { it.exists() }
            ?: throw IllegalStateException("AndroidManifest.xml not found — run tests from project root")
    }

    private val manifest: String by lazy { manifestFile.readText() }

    @Test
    fun `allowBackup is false`() {
        assertTrue("android:allowBackup must be false — backup could extract encrypted seed",
            manifest.contains("android:allowBackup=\"false\""))
    }

    @Test
    fun `fullBackupContent is false`() {
        assertTrue("android:fullBackupContent must be false",
            manifest.contains("android:fullBackupContent=\"false\""))
    }

    @Test
    fun `SyncService is not exported`() {
        assertTrue("SyncService must have exported=false",
            manifest.contains("android:exported=\"false\""))
    }

    @Test
    fun `no content providers declared`() {
        assertFalse("Wallet should not export content providers — attack surface for data theft",
            manifest.contains("<provider"))
    }

    @Test
    fun `no broadcast receivers exported`() {
        // Custom receivers could be triggered by malicious apps
        assertFalse("No exported broadcast receivers should exist",
            manifest.contains("<receiver") && manifest.contains("android:exported=\"true\""))
    }

    @Test
    fun `networkSecurityConfig is defined`() {
        assertTrue("Network security config must be set for certificate pinning / cleartext control",
            manifest.contains("android:networkSecurityConfig"))
    }

    @Test
    fun `only expected permissions declared`() {
        val allowedPermissions = setOf(
            "android.permission.INTERNET",
            "android.permission.CAMERA",
            "android.permission.USE_BIOMETRIC",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
            "android.permission.POST_NOTIFICATIONS",
            // Normal (install-time) permission — required for the ConnectivityManager
            // default-network callback that drives the network-regained reconnect
            // (SyncService.registerNetworkRegainedCallback, v4.0.19). Not dangerous.
            "android.permission.ACCESS_NETWORK_STATE"
        )
        val permPattern = Regex("android:name=\"(android\\.permission\\.[A-Z_]+)\"")
        val declared = permPattern.findAll(manifest).map { it.groupValues[1] }.toSet()
        val unexpected = declared - allowedPermissions

        assertTrue(
            "Unexpected permissions found: $unexpected. " +
                "Wallets should request minimal permissions. " +
                "READ_EXTERNAL_STORAGE, WRITE_EXTERNAL_STORAGE, ACCESS_FINE_LOCATION are dangerous.",
            unexpected.isEmpty()
        )
    }

    @Test
    fun `no cleartext traffic allowed`() {
        assertFalse("usesCleartextTraffic must not be true — all traffic should be HTTPS/TLS",
            manifest.contains("android:usesCleartextTraffic=\"true\""))
    }
}
