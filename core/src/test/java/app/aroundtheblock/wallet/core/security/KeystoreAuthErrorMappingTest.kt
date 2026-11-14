package app.aroundtheblock.wallet.core.security

import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.security.KeyStoreException
import javax.crypto.IllegalBlockSizeException

/**
 * KS-BIND. Invariant: a Keystore "user not authenticated" or "permanently invalidated" failure is
 * recognised wherever it sits in the cause chain, so the seed-key probe answers
 * NEEDS_DEVICE_CREDENTIAL (one prompt, then a bound record) rather than UNAVAILABLE (a silent fallback
 * to the legacy key) on Keystores that raise it wrapped at doFinal.
 */
class KeystoreAuthErrorMappingTest {

    // The android.jar stubs cannot be constructed on the JVM; a mock is a real instance of the type.
    private val notAuthed: Throwable = mockk<UserNotAuthenticatedException>(relaxed = true)
    private val invalidated: Throwable = mockk<KeyPermanentlyInvalidatedException>(relaxed = true)

    private fun mapped(thrown: Throwable): Throwable =
        runCatching { mapKeystoreAuthErrors<Unit> { throw thrown } }.exceptionOrNull()
            ?: throw AssertionError("nothing thrown")

    private fun wrapped(cause: Throwable): Exception =
        IllegalBlockSizeException("doFinal failed").apply { initCause(cause) }

    @Test fun `KS-BIND top-level not-authenticated maps to auth required`() {
        assertEquals(KeystoreUserAuthRequiredException::class.java, mapped(notAuthed)::class.java)
    }

    @Test fun `KS-BIND top-level invalidated maps to invalidated`() {
        assertEquals(KeystoreKeyInvalidatedException::class.java, mapped(invalidated)::class.java)
    }

    @Test fun `KS-BIND not-authenticated wrapped at doFinal maps to auth required`() {
        assertEquals(KeystoreUserAuthRequiredException::class.java, mapped(wrapped(notAuthed))::class.java)
    }

    @Test fun `KS-BIND a vendor KeyStoreException naming key-user-not-authenticated maps to auth required`() {
        val vendor = wrapped(KeyStoreException("Key user not authenticated"))
        assertEquals(KeystoreUserAuthRequiredException::class.java, mapped(vendor)::class.java)
    }

    @Test fun `KS-BIND invalidated two levels down maps to invalidated`() {
        val deep = RuntimeException("outer", wrapped(invalidated))
        assertEquals(KeystoreKeyInvalidatedException::class.java, mapped(deep)::class.java)
    }

    @Test fun `KS-BIND an unrelated failure is rethrown unchanged`() {
        val other = wrapped(KeyStoreException("Invalid key blob"))
        assertSame(other, mapped(other))
        assertNull(keystoreAuthFailureOf(IllegalStateException("x")))
    }

    @Test fun `KS-BIND a cause cycle terminates`() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        assertNull(keystoreAuthFailureOf(a))
    }

    // KS-BIND: the one rule shared by the status line and the unlock path (boundKeyStateOnFailure).

    @Test fun `KS-BIND alias missing reads ABSENT whatever the error says`() {
        // API 31+: getKey answers null for the deleted alias; the cipher's error names nothing.
        val nullKey = java.security.InvalidKeyException("No installed provider supports this key: (null)")
        assertEquals(BoundKeyState.ABSENT, boundKeyStateOnFailure(nullKey, aliasExists = false))
        assertEquals(BoundKeyState.ABSENT, boundKeyStateOnFailure(NullPointerException(), aliasExists = false))
        assertEquals(true, BoundKeyState.ABSENT.neverOpens)
    }

    @Test fun `KS-BIND KeyPermanentlyInvalidatedException reads INVALIDATED, raw or wrapped`() {
        assertEquals(BoundKeyState.INVALIDATED, boundKeyStateOnFailure(invalidated, aliasExists = true))
        assertEquals(BoundKeyState.INVALIDATED, boundKeyStateOnFailure(wrapped(invalidated), aliasExists = null))
        assertEquals(true, BoundKeyState.INVALIDATED.neverOpens)
    }

    @Test fun `KS-BIND InvalidKeyException with the alias present is UNKNOWN - retryable, not lock removed`() {
        // A permanently invalidated key raises the KeyPermanentlyInvalidatedException subclass; a plain
        // InvalidKeyException on a key that is still there is no evidence of loss.
        val r = boundKeyStateOnFailure(java.security.InvalidKeyException("vendor"), aliasExists = true)
        assertEquals(BoundKeyState.UNKNOWN, r)
        assertEquals(false, r.neverOpens)
    }

    @Test fun `KS-BIND a transient error is UNKNOWN and a lapsed window USABLE - both retryable`() {
        assertEquals(BoundKeyState.UNKNOWN, boundKeyStateOnFailure(java.security.ProviderException("busy"), aliasExists = null))
        assertEquals(BoundKeyState.UNKNOWN, boundKeyStateOnFailure(java.security.ProviderException("busy"), aliasExists = true))
        assertEquals(BoundKeyState.USABLE, boundKeyStateOnFailure(wrapped(notAuthed), aliasExists = true))
        assertEquals(false, BoundKeyState.UNKNOWN.neverOpens)
        assertEquals(false, BoundKeyState.USABLE.neverOpens)
    }
}
