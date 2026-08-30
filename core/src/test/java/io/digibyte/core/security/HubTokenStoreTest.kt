package io.digibyte.core.security

import android.content.SharedPreferences
import android.util.Log
import io.digibyte.core.digiscope.HubTokenStore
import io.digibyte.core.sync.FakeSharedPreferences
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
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
    private val store = HubTokenStore(legacy, { secure })

    @Before
    fun silenceLog() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
    }

    @After
    fun restoreLog() = unmockkStatic(Log::class)

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

    // ── the encrypted store must never be able to block wallet access ────────
    //
    // `load()` runs from DigiScopeClient's init, which Hilt executes while injecting
    // MainActivity. EncryptedSharedPreferences throws (SecurityException /
    // GeneralSecurityException) when its Tink keyset or a stored value fails AEAD — a
    // documented failure class after OS upgrades and Keystore hiccups — and a throw there is
    // a crash before onCreate that no wiper clears: a permanent crash loop for a wallet holder,
    // over the least valuable secret in the app.

    @Test
    fun `a secure store that cannot be opened reads as logged-out instead of throwing`() {
        var resets = 0
        val broken = HubTokenStore(legacy, { throw SecurityException("keyset unreadable") }, { resets++ })

        assertNull(broken.load())
        assertEquals("the unreadable store must be discarded so the next launch recovers", 1, resets)
    }

    @Test
    fun `a secure value that fails AEAD reads as logged-out and discards the store`() {
        var resets = 0
        val broken = HubTokenStore(legacy, { ThrowingPrefs }, { resets++ })

        assertNull(broken.load())
        assertEquals(1, resets)
    }

    @Test
    fun `save and clear on a broken secure store do not throw`() {
        val broken = HubTokenStore(legacy, { ThrowingPrefs }, {})
        legacy.edit().putString(HubTokenStore.KEY_JWT, "stale").commit()

        broken.save("token")
        broken.clear()

        assertFalse("clear must still drop the plaintext copy", legacy.contains(HubTokenStore.KEY_JWT))
    }

    @Test
    fun `plaintext token is kept when the encrypted write does not land`() {
        val rejecting = RejectingWritePrefs()
        val store = HubTokenStore(legacy, { rejecting })
        legacy.edit().putString(HubTokenStore.KEY_JWT, "only-copy").commit()

        assertNull(store.load())
        assertEquals("the session must not be lost from both stores", "only-copy",
            legacy.getString(HubTokenStore.KEY_JWT, null))
    }

    /** Every read and write throws, the way a corrupt AEAD keyset does. */
    private object ThrowingPrefs : SharedPreferences by FakeSharedPreferences() {
        override fun getString(key: String?, defValue: String?): String? = throw SecurityException("AEAD failed")
        override fun contains(key: String?): Boolean = throw SecurityException("AEAD failed")
        override fun edit(): SharedPreferences.Editor = throw SecurityException("AEAD failed")
    }

    /** Accepts edits but reports every commit as failed and stores nothing. */
    private class RejectingWritePrefs : SharedPreferences by FakeSharedPreferences() {
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            override fun putString(key: String, value: String?) = this
            override fun putStringSet(key: String, values: MutableSet<String>?) = this
            override fun putInt(key: String, value: Int) = this
            override fun putLong(key: String, value: Long) = this
            override fun putFloat(key: String, value: Float) = this
            override fun putBoolean(key: String, value: Boolean) = this
            override fun remove(key: String) = this
            override fun clear() = this
            override fun commit(): Boolean = false
            override fun apply() = Unit
        }
    }
}
