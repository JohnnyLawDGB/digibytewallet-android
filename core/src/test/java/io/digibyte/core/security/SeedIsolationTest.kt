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

    /**
     * The String-typed `createWallet(String)` / `recoverWallet(String, Long)` entry points were
     * the legacy pre-CRITICAL-3 path: the mnemonic crossed JNI as an immutable JVM String, and
     * their C bodies were the only place the wallet's whole address pool was written to logcat.
     * They had no production caller after `WalletManager` moved to the `FromBytes` variants and
     * were deleted in the 2026-08-30 audit follow-up. Their absence is the invariant.
     */
    @Test
    fun `NativeBridge has no String-typed createWallet or recoverWallet entry point`() {
        val stringy = NativeBridge::class.java.declaredMethods.filter { m ->
            m.name in setOf("createWallet", "recoverWallet")
        }
        assertTrue("legacy String seed entry points must stay deleted: $stringy", stringy.isEmpty())
    }

    @Test
    fun `NativeBridge createWalletFromBytes accepts ByteArray and returns Boolean`() {
        val method = NativeBridge::class.java.getDeclaredMethod("createWalletFromBytes", ByteArray::class.java, ByteArray::class.java)
        assertEquals("createWalletFromBytes must return Boolean, not the seed",
            Boolean::class.javaPrimitiveType, method.returnType)
    }

    @Test
    fun `NativeBridge recoverWalletFromBytes accepts ByteArray and returns Boolean`() {
        val method = NativeBridge::class.java.getDeclaredMethod(
            "recoverWalletFromBytes", ByteArray::class.java, Long::class.javaPrimitiveType, ByteArray::class.java
        )
        assertEquals("recoverWalletFromBytes must return Boolean, not the seed",
            Boolean::class.javaPrimitiveType, method.returnType)
    }

    /**
     * The passphrase is seed material and must cross JNI as bytes, never as a String.
     *
     * CLAUDE.md:51 records the CRITICAL-3 remediation making the mnemonic a ByteArray so it never
     * becomes an immutable JVM String — one that cannot be zeroed and lives on the heap until GC
     * chooses otherwise. The passphrase is the other half of the same secret; the two together
     * ARE the wallet. It originally shipped as a String at every hop, which quietly did not
     * extend that guarantee to it.
     *
     * This is the gate on that decision. A String parameter here means the invariant has silently
     * regressed, and a document nobody re-reads would not have caught it.
     */
    @Test
    fun `NativeBridge passphrase parameters are ByteArray, never String`() {
        val create = NativeBridge::class.java.getDeclaredMethod(
            "createWalletFromBytes", ByteArray::class.java, ByteArray::class.java
        )
        assertEquals(Boolean::class.javaPrimitiveType, create.returnType)

        NativeBridge::class.java.getDeclaredMethod(
            "recoverWalletFromBytes", ByteArray::class.java, Long::class.javaPrimitiveType, ByteArray::class.java
        )
        NativeBridge::class.java.getDeclaredMethod(
            "mnemonicToSeed", ByteArray::class.java, ByteArray::class.java
        )

        // And no String-typed passphrase overload may creep back in alongside them.
        val stringy = NativeBridge::class.java.declaredMethods.filter { m ->
            m.name in setOf("createWalletFromBytes", "recoverWalletFromBytes", "mnemonicToSeed") &&
                m.parameterTypes.any { it == String::class.java }
        }
        assertTrue("passphrase must not cross JNI as a String: $stringy", stringy.isEmpty())
    }

    @Test
    fun `NativeBridge getReceiveAddress does not accept seed parameter`() {
        val method = NativeBridge::class.java.getDeclaredMethod("getReceiveAddress", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        // Should only take index and format — not seed material
        assertEquals("getReceiveAddress should take 2 int params, no seed", 2, method.parameterCount)
    }

    /**
     * `unlockSession(ByteArray)` was documented as taking an "opaque token" but its C body
     * memcpy'd any 64-byte argument straight into `g_seed` — a raw-seed injection door with no
     * production caller (`WalletManager.unlock(authToken)` was itself dead). The earlier test
     * here asserted the method's return type, which is not what made it safe. Deleted 2026-08-30;
     * this asserts it stays gone.
     */
    @Test
    fun `NativeBridge has no unlockSession entry point`() {
        val present = NativeBridge::class.java.declaredMethods.filter { it.name == "unlockSession" }
        assertTrue("unlockSession must stay deleted (raw-seed injection door): $present", present.isEmpty())
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
