package io.digibyte.core.security

import io.digibyte.core.digiscope.HubTokenStore
import io.digibyte.core.sync.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Hub session JWT lived as plaintext in `dgb_digiscope` SharedPreferences (audit F5/F7):
 * it is the one migratable-by-backup secret in the app, and unlike the seed/PIN blobs it is
 * not Keystore-wrapped. [HubTokenStore] moves it into EncryptedSharedPreferences the way
 * `PinManager` keeps the PIN hash, and migrates an existing plaintext token exactly once.
 *
 * The fakes stand in for the plaintext and encrypted stores; the migration contract is what
 * these tests pin, not the AndroidX crypto.
 */
class HubTokenStoreTest {

    private val legacy = FakeSharedPreferences()
    private val secure = FakeSharedPreferences()
    private val store = HubTokenStore(legacy, secure)

    @Test
    fun `existing plaintext token is moved into the encrypted store and removed from plaintext`() {
        legacy.edit().putString(HubTokenStore.KEY_JWT, "old-plaintext-jwt").commit()

        assertEquals("old-plaintext-jwt", store.load())

        assertEquals("old-plaintext-jwt", secure.getString(HubTokenStore.KEY_JWT, null))
        assertFalse("plaintext copy must be gone after migration", legacy.contains(HubTokenStore.KEY_JWT))
    }

    @Test
    fun `save writes only the encrypted store`() {
        store.save("fresh-jwt")

        assertEquals("fresh-jwt", secure.getString(HubTokenStore.KEY_JWT, null))
        assertFalse("a new token must never land in plaintext", legacy.contains(HubTokenStore.KEY_JWT))
        assertEquals("fresh-jwt", store.load())
    }

    @Test
    fun `clear empties both stores so a stale plaintext token cannot resurrect a session`() {
        legacy.edit().putString(HubTokenStore.KEY_JWT, "stale").commit()
        store.save("live")

        store.clear()

        assertNull(store.load())
        assertFalse(legacy.contains(HubTokenStore.KEY_JWT))
        assertFalse(secure.contains(HubTokenStore.KEY_JWT))
    }

    @Test
    fun `an already-migrated token wins over a leftover plaintext one`() {
        secure.edit().putString(HubTokenStore.KEY_JWT, "encrypted-current").commit()
        legacy.edit().putString(HubTokenStore.KEY_JWT, "plaintext-leftover").commit()

        assertEquals("encrypted-current", store.load())
        assertFalse(legacy.contains(HubTokenStore.KEY_JWT))
    }

    @Test
    fun `load with nothing stored is null and leaves both stores empty`() {
        assertNull(store.load())
        assertTrue(legacy.all.isEmpty())
        assertTrue(secure.all.isEmpty())
    }
}
