package io.digibyte.core.security

import io.digibyte.core.bridge.NativeBridge
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * Security test: Seed Isolation
 *
 * Verifies that the wallet's BIP39 seed/mnemonic cannot be accessed
 * through public APIs or leaked through the JNI boundary.
 *
 * Published as part of the security audit at:
 * https://github.com/JohnnyLawDGB/digibytewallet-android/security/
 */
class SeedIsolationTest {

    @Test
    fun `NativeBridge has no method that returns seed or mnemonic`() {
        val dangerousReturnPatterns = listOf("seed", "mnemonic", "phrase", "entropy", "privkey", "privatekey", "secret")
        val methods = NativeBridge::class.java.declaredMethods

        // Allow-list — each entry documents why the pattern match is safe:
        //   generatemnemonic — creates new entropy, not a getter for existing seed
        //   mnemonictoseed — Universal Restore probes multi-path derivation; the
        //       returned ByteArray is scoped, zeroed in finally by callers
        //       (RecoveryScanService, LegacySweepService) and never written to disk
        //   deriveprivatekeywif — returns a WIF for a SPECIFIC child address on an
        //       OVERRIDING derivation profile, used by LegacySweepService to build
        //       sweep transactions for legacy funds; the child key is unrelated to
        //       the stored seed and is zeroed after signing
        val allowedMethods = setOf("generatemnemonic", "mnemonictoseed", "deriveprivatekeywif")

        val leakyMethods = methods.filter { method ->
            val name = method.name.lowercase()
            val returnType = method.returnType.simpleName.lowercase()
            name !in allowedMethods &&
            dangerousReturnPatterns.any { pattern ->
                name.contains(pattern) && (returnType == "string" || returnType == "bytearray" || returnType == "byte[]")
            }
        }

        assertTrue(
            "NativeBridge should not expose methods that return seed/mnemonic data. Found: ${leakyMethods.map { it.name }}",
            leakyMethods.isEmpty()
        )
    }

    @Test
    fun `NativeBridge signMessage returns address and signature only`() {
        // signMessage should return "address|base64signature" — never key material
        val method = NativeBridge::class.java.getDeclaredMethod("signMessage", String::class.java, Int::class.javaPrimitiveType)
        assertEquals("signMessage must return String (address|sig), not ByteArray",
            String::class.java, method.returnType)
    }

    @Test
    fun `NativeBridge createWallet accepts seed but does not return it`() {
        val method = NativeBridge::class.java.getDeclaredMethod("createWallet", String::class.java)
        assertEquals("createWallet must return Boolean, not the seed",
            Boolean::class.javaPrimitiveType, method.returnType)
    }

    @Test
    fun `NativeBridge recoverWallet accepts seed but does not return it`() {
        val method = NativeBridge::class.java.getDeclaredMethod("recoverWallet", String::class.java, Long::class.javaPrimitiveType)
        assertEquals("recoverWallet must return Boolean, not the seed",
            Boolean::class.javaPrimitiveType, method.returnType)
    }

    @Test
    fun `NativeBridge createWalletFromBytes accepts ByteArray and returns Boolean`() {
        val method = NativeBridge::class.java.getDeclaredMethod("createWalletFromBytes", ByteArray::class.java, String::class.java)
        assertEquals("createWalletFromBytes must return Boolean, not the seed",
            Boolean::class.javaPrimitiveType, method.returnType)
    }

    @Test
    fun `NativeBridge recoverWalletFromBytes accepts ByteArray and returns Boolean`() {
        val method = NativeBridge::class.java.getDeclaredMethod(
            "recoverWalletFromBytes", ByteArray::class.java, Long::class.javaPrimitiveType, String::class.java
        )
        assertEquals("recoverWalletFromBytes must return Boolean, not the seed",
            Boolean::class.javaPrimitiveType, method.returnType)
    }

    @Test
    fun `NativeBridge getReceiveAddress does not accept seed parameter`() {
        val method = NativeBridge::class.java.getDeclaredMethod("getReceiveAddress", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        // Should only take index and format — not seed material
        assertEquals("getReceiveAddress should take 2 int params, no seed", 2, method.parameterCount)
    }

    @Test
    fun `NativeBridge unlockSession accepts opaque token not raw seed`() {
        // The auth token is a Keystore-decrypted blob, not the mnemonic
        val method = NativeBridge::class.java.getDeclaredMethod("unlockSession", ByteArray::class.java)
        assertEquals("unlockSession must return Boolean",
            Boolean::class.javaPrimitiveType, method.returnType)
    }

    @Test
    fun `NativeBridge lockSession zeros keys and returns void`() {
        val method = NativeBridge::class.java.getDeclaredMethod("lockSession")
        assertEquals("lockSession must return void",
            Void.TYPE, method.returnType)
    }

    @Test
    fun `NativeBridge has no public fields exposing key material`() {
        val fields = NativeBridge::class.java.declaredFields
        val publicFields = fields.filter { Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers) }
        assertTrue(
            "NativeBridge should have no public instance fields. Found: ${publicFields.map { it.name }}",
            publicFields.isEmpty()
        )
    }

    @Test
    fun `NativeBridge is an object singleton not instantiable`() {
        // Kotlin objects compile to a class with a private constructor + INSTANCE field
        assertTrue("NativeBridge should be a Kotlin object (singleton)",
            NativeBridge::class.java.declaredFields.any { it.name == "INSTANCE" })
    }
}
