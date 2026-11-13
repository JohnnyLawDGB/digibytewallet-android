package app.aroundtheblock.wallet.core.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * PIN-STORE: the production PIN store is the sealed file, reached through the migration; the alpha
 * EncryptedSharedPreferences is touched only by the legacy reader the migration drains.
 */
class PinStoreSourceGateTest {
    private val dir = File("src/main/java/app/aroundtheblock/wallet/core/security")

    @Test fun `PIN-STORE the production constructor opens the store through the migration`() {
        val src = File(dir, "PinManager.kt").readText()
        assertTrue(
            "PinManager(context) must open PinStoreMigration.open(context)",
            Regex("""constructor\(context: Context\)\s*:\s*this\(openStore \{ PinStoreMigration\.open\(context\) \}""").containsMatchIn(src)
        )
    }

    @Test fun `PIN-STORE the new store does not use the alpha library`() {
        val src = File(dir, "FilePinStore.kt").readText()
        assertFalse(src.contains("androidx.security.crypto"))
        assertTrue("the file lives in no-backup storage", src.contains("context.noBackupFilesDir"))
    }

    @Test fun `PIN-STORE EncryptedSharedPreferences is opened only by the legacy reader`() {
        val src = File(dir, "PinManager.kt").readText()
        val legacy = src.substringAfter("internal class LegacyEncryptedPrefsPinStore").substringBefore("\nclass PinManager")
        val outside = src.replace(legacy, "")
        assertFalse("EncryptedSharedPreferences.create outside the legacy reader", outside.contains("EncryptedSharedPreferences.create("))
    }
}
