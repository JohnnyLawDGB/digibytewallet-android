package app.aroundtheblock.wallet.core.security

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.GeneralSecurityException
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * PIN-STORE: the PIN hash, salt and rate-limit counters live in a file the app controls, sealed with
 * AES-256-GCM under a dedicated key, and no longer depend on the alpha EncryptedSharedPreferences.
 * The old store is read once, the new one written and read back, and only then is the old one
 * deleted; a migration that fails keeps the old store and is tried again. A new store that is there
 * and cannot be read is the unavailable store, never "no PIN".
 *
 * The Keystore cannot run off-device, so these seal with a software AES-256 key through the same
 * [KeystorePinStoreCipher.sealWith] / [KeystorePinStoreCipher.openWith] the Keystore key goes through.
 */
class PinStoreMigrationTest {

    @get:Rule val tmp = TemporaryFolder()

    @Before fun log() {
        mockkStatic(Log::class)
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
    }

    @After fun unlog() = unmockkStatic(Log::class)

    private val pin = "246810"

    private class SoftwareCipher(var key: SecretKey = newKey()) : PinStoreCipher {
        var sealFails = false
        var openTampers = false
        override fun seal(plain: ByteArray): ByteArray {
            if (sealFails) throw GeneralSecurityException("stand-in: the key could not be used")
            return KeystorePinStoreCipher.sealWith(key, plain)
        }
        override fun open(sealed: ByteArray): ByteArray {
            val plain = KeystorePinStoreCipher.openWith(key, sealed)
            if (openTampers) plain[plain.size - 1] = (plain[plain.size - 1].toInt() xor 1).toByte()
            return plain
        }
        companion object {
            fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        }
    }

    /** The old store, in memory. [open] hands out a view of [map], as the real prefs would. */
    private class FakeLegacy(val map: MutableMap<String, Any?> = LinkedHashMap()) : LegacyPinStore {
        var present = true
        var openFails = false
        var deleteLands = true
        var deletes = 0
        var opens = 0

        override fun exists() = present
        override fun open(): LegacyPinStore.Opened {
            opens++
            if (openFails) throw GeneralSecurityException("stand-in: keyset")
            return View()
        }
        override fun delete(): Boolean {
            deletes++
            if (deleteLands) { present = false; map.clear() }
            return deleteLands
        }

        inner class View : LegacyPinStore.Opened {
            override fun all(): Map<String, Any?> = LinkedHashMap(map)
            override fun getInt(key: String, def: Int) = map[key] as Int? ?: def
            override fun getLong(key: String, def: Long) = map[key] as Long? ?: def
            override fun getString(key: String) = map[key] as String?
            override fun getBoolean(key: String, def: Boolean) = map[key] as Boolean? ?: def
            override fun contains(key: String) = map.containsKey(key)
            override fun putInt(key: String, value: Int) { map[key] = value }
            override fun putLong(key: String, value: Long) { map[key] = value }
            override fun putString(key: String, value: String) { map[key] = value }
            override fun putBoolean(key: String, value: Boolean) { map[key] = value }
            override fun remove(key: String) { map.remove(key) }
            override fun clear() { map.clear() }
        }
    }

    private class Clock(var sinceBoot: Long = 5_000_000L) : PinClock {
        override fun sinceBootMs() = sinceBoot
        override fun bootCount() = 7
        override fun wallMs() = 1_800_000_000_000L
    }

    private class Hash : Argon2idHash {
        override fun hash(pin: ByteArray, salt: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").run { update(salt); update(pin); digest() }
    }

    private val clock = Clock()
    private fun manager(store: PinStore) = PinManager(store, Hash(), clock)
    private val target get() = File(tmp.root, "nobackup/${PinStoreMigration.FILE_NAME}")
    private fun open(cipher: PinStoreCipher, legacy: LegacyPinStore) = PinManager.openStore { PinStoreMigration.open(target, cipher, legacy) }

    /** An old store holding a PIN, two wrong attempts and wipe-after-N on. */
    private fun legacyWithPin(): FakeLegacy {
        val legacy = FakeLegacy()
        manager(legacy.open()).apply {
            setPin(pin)
            setWipeAfterN(true)
            verifyPin("000000")
            verifyPin("000001")
        }
        legacy.opens = 0
        return legacy
    }

    // ── migration success ─────────────────────────────────────────────────────

    @Test fun `PIN-STORE a migration carries the PIN and every counter, then deletes the old store`() {
        val legacy = legacyWithPin()
        val before = LinkedHashMap(legacy.map)
        val cipher = SoftwareCipher()

        val store = open(cipher, legacy)

        assertTrue("the new store serves", store is FilePinStore)
        assertTrue("the file is in place", target.exists())
        assertEquals("the old store was deleted once", 1, legacy.deletes)
        assertFalse(legacy.present)
        assertEquals("the file holds exactly what the old store held", before, FilePinStore.read(target, cipher))
        val mgr = manager(store)
        assertTrue(mgr.storeAvailable)
        assertTrue(mgr.hasPin())
        assertTrue(mgr.isWipeAfterNEnabled())
        // The two failures carried over: the next is the third free one, the one after starts a cooldown.
        assertEquals(PinVerifyResult.Wrong(3, null), mgr.verifyPin("000002"))
        assertTrue(mgr.verifyPin("000003") is PinVerifyResult.Wrong)
        // Reopened from disk with no old store: the cooldown is still there (a force-stop changes nothing).
        val reopened = manager(open(cipher, FakeLegacy().apply { present = false }))
        assertTrue(reopened.verifyPin(pin) is PinVerifyResult.LockedOut)
        clock.sinceBoot += PinManager.MAX_COOLDOWN_MS
        assertSame(PinVerifyResult.Success, reopened.verifyPin(pin))
    }

    @Test fun `PIN-STORE a cooldown running in the old store is still running after the migration`() {
        val legacy = legacyWithPin()
        manager(legacy.open()).apply { verifyPin("1"); verifyPin("2") } // 4 failures: a cooldown
        val mgr = manager(open(SoftwareCipher(), legacy))
        assertTrue(mgr.verifyPin(pin) is PinVerifyResult.LockedOut)
    }

    @Test fun `PIN-STORE an old store with no PIN migrates to an empty store`() {
        val legacy = FakeLegacy()
        val store = open(SoftwareCipher(), legacy)
        assertTrue(store is FilePinStore)
        assertFalse(manager(store).hasPin())
        assertFalse(legacy.present)
    }

    @Test fun `PIN-STORE a fresh install has no PIN and keeps the one it sets`() {
        val cipher = SoftwareCipher()
        val none = FakeLegacy().apply { present = false }
        val mgr = manager(open(cipher, none))
        assertTrue(mgr.storeAvailable)
        assertFalse(mgr.hasPin())
        mgr.setPin(pin)
        assertFalse("a write leaves no temporary file", File(target.parentFile, target.name + ".tmp").exists())
        val reopened = manager(open(cipher, none))
        assertTrue(reopened.hasPin())
        assertSame(PinVerifyResult.Success, reopened.verifyPin(pin))
        assertEquals("the old store was never opened", 0, none.opens)
    }

    @Test fun `PIN-STORE clearPin empties the new store on disk`() {
        val cipher = SoftwareCipher()
        val none = FakeLegacy().apply { present = false }
        manager(open(cipher, none)).apply { setPin(pin); clearPin() }
        assertFalse(manager(open(cipher, none)).hasPin())
        assertTrue(FilePinStore.read(target, cipher).isEmpty())
    }

    // ── migration failure and retry ───────────────────────────────────────────

    @Test fun `PIN-STORE a migration that cannot write keeps the old store, serves from it, and retries`() {
        val legacy = legacyWithPin()
        val cipher = SoftwareCipher().apply { sealFails = true }

        val first = open(cipher, legacy)
        assertFalse("not the new store", first is FilePinStore)
        assertNotSame(UnavailablePinStore, first)
        assertFalse("nothing in place", target.exists())
        assertTrue("the old store is kept", legacy.present)
        assertEquals(0, legacy.deletes)
        // The session runs on the old store: the PIN works, the counters count, and land in it.
        val session = manager(first)
        assertEquals(PinVerifyResult.Wrong(3, null), session.verifyPin("000002"))
        assertEquals(3, legacy.map["pin_fail_count"])

        cipher.sealFails = false
        val retried = open(cipher, legacy)
        assertTrue("the retry migrated", retried is FilePinStore)
        assertFalse(legacy.present)
        val mgr = manager(retried)
        assertTrue("the failure counted during the failed session carried over", mgr.verifyPin("000003") is PinVerifyResult.Wrong)
        assertTrue(mgr.verifyPin(pin) is PinVerifyResult.LockedOut)
    }

    @Test fun `PIN-STORE a migration whose read-back differs keeps the old store and leaves nothing in place`() {
        val legacy = legacyWithPin()
        val before = LinkedHashMap(legacy.map)
        val cipher = SoftwareCipher().apply { openTampers = true }

        val store = open(cipher, legacy)

        assertFalse(store is FilePinStore)
        assertFalse("nothing in place", target.exists())
        assertFalse("no staging file left", File(target.parentFile, target.name + ".migrating").exists())
        assertTrue(legacy.present)
        assertEquals("the old store is untouched", before, legacy.map)
        assertTrue(manager(store).hasPin())
    }

    @Test fun `PIN-STORE an entry type the new store cannot hold keeps the old store`() {
        val legacy = legacyWithPin().apply { map["stray"] = 1.5f }
        val store = open(SoftwareCipher(), legacy)
        assertFalse(store is FilePinStore)
        assertTrue(legacy.present)
        assertFalse(target.exists())
    }

    @Test fun `PIN-STORE an old store that cannot be opened is the unavailable store and is kept`() {
        val legacy = legacyWithPin().apply { openFails = true }
        assertSame(UnavailablePinStore, open(SoftwareCipher(), legacy))
        assertTrue(legacy.present)
        assertEquals(0, legacy.deletes)
        assertFalse(target.exists())
    }

    @Test fun `PIN-STORE a migration stopped before its rename is done again from the old store`() {
        val legacy = legacyWithPin()
        val cipher = SoftwareCipher()
        target.parentFile!!.mkdirs()
        File(target.parentFile, target.name + ".migrating").writeBytes(byteArrayOf(1, 2, 3))
        val store = open(cipher, legacy)
        assertTrue(store is FilePinStore)
        assertTrue(manager(store).hasPin())
        assertFalse(File(target.parentFile, target.name + ".migrating").exists())
    }

    @Test fun `PIN-STORE once the new store is in place a stale old store is deleted again and never read`() {
        val legacy = legacyWithPin().apply { deleteLands = false }
        val cipher = SoftwareCipher()
        manager(open(cipher, legacy)).verifyPin("000002") // 3 failures, in the new store
        assertTrue("the delete did not land", legacy.present)
        // What the old store says is not the PIN state any more: a fresh PIN and no failures there.
        manager(legacy.open()).setPin("999999")
        legacy.opens = 0
        legacy.deleteLands = true

        val mgr = manager(open(cipher, legacy))

        assertEquals("the old store was not read", 0, legacy.opens)
        assertFalse("its delete was tried again", legacy.present)
        assertTrue(mgr.verifyPin("999999") is PinVerifyResult.Wrong)
        assertTrue("the new store's count stands", mgr.verifyPin(pin) is PinVerifyResult.LockedOut)
    }

    // ── a corrupt new store is unavailable, not "no PIN" ──────────────────────

    private fun inPlace(): Pair<SoftwareCipher, FakeLegacy> {
        val cipher = SoftwareCipher()
        val none = FakeLegacy().apply { present = false }
        manager(open(cipher, none)).setPin(pin)
        return cipher to none
    }

    private fun assertUnavailableAndUntouched(cipher: PinStoreCipher, legacy: LegacyPinStore) {
        val onDisk = target.readBytes()
        val store = open(cipher, legacy)
        assertSame(UnavailablePinStore, store)
        val mgr = PinManager(store, Hash(), clock)
        assertFalse(mgr.storeAvailable)
        assertTrue("the lock treats it as PIN-protected", mgr.pinAssumedForLock())
        assertFalse(mgr.wipeOwedAtLaunch())
        assertArrayEquals("the file is left as it is", onDisk, target.readBytes())
    }

    @Test fun `PIN-STORE a new store with a flipped byte is unavailable`() {
        val (cipher, none) = inPlace()
        val bytes = target.readBytes()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x40).toByte()
        target.writeBytes(bytes)
        assertUnavailableAndUntouched(cipher, none)
    }

    @Test fun `PIN-STORE a truncated or empty new store is unavailable`() {
        val (cipher, none) = inPlace()
        target.writeBytes(target.readBytes().copyOf(20))
        assertUnavailableAndUntouched(cipher, none)
        target.writeBytes(ByteArray(0))
        assertUnavailableAndUntouched(cipher, none)
    }

    @Test fun `PIN-STORE a new store whose key has gone is unavailable`() {
        val (cipher, none) = inPlace()
        cipher.key = SoftwareCipher.newKey()
        assertUnavailableAndUntouched(cipher, none)
    }

    @Test fun `PIN-STORE a corrupt new store beside an old one is unavailable and the old one is not used`() {
        val (cipher, _) = inPlace()
        target.writeBytes(ByteArray(64) { 7 })
        val legacy = legacyWithPin()
        assertUnavailableAndUntouched(cipher, legacy)
        assertEquals("the old store was not read", 0, legacy.opens)
        assertTrue("nor deleted", legacy.present)
    }

    // ── the codec ─────────────────────────────────────────────────────────────

    @Test fun `PIN-STORE the codec round-trips the four types and refuses trailing bytes`() {
        val values = mapOf("i" to 3, "l" to 4L, "b" to true, "s" to "x")
        val encoded = PinStoreCodec.encode(values)
        assertEquals(values, PinStoreCodec.decode(encoded))
        try {
            PinStoreCodec.decode(encoded + 0)
            throw AssertionError("trailing bytes were accepted")
        } catch (_: java.io.IOException) {
        }
    }
}
