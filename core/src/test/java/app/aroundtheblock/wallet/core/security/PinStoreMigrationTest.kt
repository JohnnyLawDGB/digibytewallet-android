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
        var sealFailsTimes = 0
        /** Seals that still land before every later one fails (storage filling up); -1 = no limit. */
        var sealsLeft = -1
        var openTampers = false
        override fun seal(plain: ByteArray): ByteArray {
            if (sealFails) throw GeneralSecurityException("stand-in: the key could not be used")
            if (sealsLeft == 0) throw GeneralSecurityException("stand-in: ENOSPC")
            if (sealsLeft > 0) sealsLeft--
            if (sealFailsTimes > 0) { sealFailsTimes--; throw GeneralSecurityException("stand-in: a passing Keystore fault") }
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
        var onDelete: () -> Unit = {}

        override fun exists() = present
        override fun open(): LegacyPinStore.Opened {
            opens++
            if (openFails) throw GeneralSecurityException("stand-in: keyset")
            return View()
        }
        override fun delete(): Boolean {
            onDelete()
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
        assertEquals("the file holds exactly what the old store held", before, FilePinStore.read(target, cipher) - "pin_store_migrated_from")
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

    @Test fun `PIN-STORE clearPin deletes the new store from disk`() {
        val cipher = SoftwareCipher()
        val none = FakeLegacy().apply { present = false }
        manager(open(cipher, none)).apply { setPin(pin); clearPin() }
        assertFalse("the file is gone, not emptied", target.exists())
        assertFalse(manager(open(cipher, none)).hasPin())
    }

    // ── a write or a clear that does not land (review item 2: fail closed) ─────

    /** The hash, counting how many entries were actually compared. */
    private class CountingHash : Argon2idHash {
        var calls = 0
        private val inner = Hash()
        override fun hash(pin: ByteArray, salt: ByteArray): ByteArray { calls++; return inner.hash(pin, salt) }
    }

    /** A PIN, wipe-after-N on, and nine wrong entries (each cooldown let run out). */
    private fun nineWrongWithWipeAfterN(cipher: SoftwareCipher, hash: CountingHash): PinManager {
        val mgr = PinManager(open(cipher, FakeLegacy().apply { present = false }), hash, clock)
        mgr.setPin(pin)
        mgr.setWipeAfterN(true)
        repeat(PinManager.WIPE_THRESHOLD - 1) {
            assertTrue(mgr.verifyPin("00000$it") is PinVerifyResult.Wrong)
            clock.sinceBoot += PinManager.MAX_COOLDOWN_MS
        }
        return mgr
    }

    @Test fun `PIN-STORE storage failing every write after nine wrong entries never allows a tenth compare`() {
        // Re-review item 1: wipe-after-N on, 9 wrong, then the storage fills (ENOSPC).
        val cipher = SoftwareCipher()
        val hash = CountingHash()
        val mgr = nineWrongWithWipeAfterN(cipher, hash)
        cipher.sealFails = true
        val compared = hash.calls
        repeat(20) {
            val r = mgr.verifyPin(if (it == 7) pin else "11111$it")
            assertSame("entry $it with nothing able to persist: $r", PinVerifyResult.Unavailable, r)
            clock.sinceBoot += PinManager.MAX_COOLDOWN_MS
        }
        assertEquals("an entry was compared while its failure could not be written", compared, hash.calls)
    }

    @Test fun `PIN-STORE a wipe whose owed flag could not be written stays owed in this process`() {
        val cipher = SoftwareCipher()
        val mgr = nineWrongWithWipeAfterN(cipher, CountingHash())
        assertEquals("the last cooldown has run out (and its clear has landed)", 0L, mgr.currentLockout())
        cipher.sealsLeft = 1 // the tenth entry's count lands, the owed-wipe flag after it does not
        assertSame(PinVerifyResult.ShouldWipe, mgr.verifyPin("999999"))
        assertEquals("precondition: the flag is not on disk", null, FilePinStore.read(target, cipher)["pin_wipe_pending"])
        assertTrue("the unlock screen would take another PIN", mgr.isWipePending())
        runCatching { mgr.clearWipePending() }
        assertTrue("a release that did not land released the owed wipe", mgr.isWipePending())
        cipher.sealFails = false; cipher.sealsLeft = -1
        mgr.clearWipePending()
        assertFalse(mgr.isWipePending())
    }

    @Test fun `PIN-STORE past the wipe threshold a seed that will not erase still leaves the rate limit as with wipe-after-N off`() {
        // The seed erase fails every time and the screen stands down, so the PIN is asked again; writes land.
        val on = nineWrongWithWipeAfterN(SoftwareCipher(), CountingHash())
        assertSame(PinVerifyResult.ShouldWipe, on.verifyPin("999999"))
        val afterWipeEntry = on.verifyPin("999998")
        assertTrue("the entry after a wipe that did not erase ran at full speed: $afterWipeEntry", afterWipeEntry is PinVerifyResult.LockedOut)

        // The same ten entries with wipe-after-N off.
        target.delete()
        val off = PinManager(open(SoftwareCipher(), FakeLegacy().apply { present = false }), Hash(), clock)
        off.setPin(pin)
        repeat(PinManager.WIPE_THRESHOLD - 1) { off.verifyPin("00000$it"); clock.sinceBoot += PinManager.MAX_COOLDOWN_MS }
        val tenth = off.verifyPin("999999") as PinVerifyResult.Wrong
        assertEquals("the same cooldown either way", tenth.lockedUntil, (afterWipeEntry as PinVerifyResult.LockedOut).until)
    }

    @Test fun `PIN-STORE a fresh file records a fresh digest, so a PIN set after a downgrade is not read as a failed delete`() {
        val cipher = SoftwareCipher()
        manager(open(cipher, FakeLegacy().apply { present = false })).setPin(pin) // fresh install: no migration
        val legacy = FakeLegacy() // the older build found no old store and the user set a PIN there
        manager(legacy.open()).setPin("999999")
        val mgr = manager(open(cipher, legacy))
        assertSame("the PIN set on the older build", PinVerifyResult.Success, mgr.verifyPin("999999"))
        assertFalse(legacy.present)
    }

    @Test fun `PIN-STORE an empty old store beside a fresh file is deleted`() {
        val cipher = SoftwareCipher()
        manager(open(cipher, FakeLegacy().apply { present = false })).setPin(pin)
        val legacy = FakeLegacy() // present, no entries
        val mgr = manager(open(cipher, legacy))
        assertSame(PinVerifyResult.Success, mgr.verifyPin(pin))
        assertFalse(legacy.present)
    }

    @Test fun `PIN-STORE a right PIN at the wipe entry is never wiped`() {
        val cipher = SoftwareCipher()
        val mgr = nineWrongWithWipeAfterN(cipher, CountingHash())
        assertEquals("the wipe entry's count is on disk before its compare", PinManager.WIPE_THRESHOLD - 1, FilePinStore.read(target, cipher)["pin_fail_count"])
        assertSame(PinVerifyResult.Success, mgr.verifyPin(pin))
        assertFalse(mgr.isWipePending())
    }

    @Test fun `PIN-STORE a failure count that cannot persist is never compared`() {
        val cipher = SoftwareCipher()
        val none = FakeLegacy().apply { present = false }
        val mgr = manager(open(cipher, none))
        mgr.setPin(pin)
        cipher.sealFails = true
        assertSame("a wrong PIN whose failure did not persist", PinVerifyResult.Unavailable, mgr.verifyPin("000000"))
        assertSame("and the right one gives no signal either", PinVerifyResult.Unavailable, mgr.verifyPin(pin))
        cipher.sealFails = false
        assertEquals("nothing counted on disk", null, FilePinStore.read(target, cipher)["pin_fail_count"].takeIf { it != 0 })
        assertSame(PinVerifyResult.Success, mgr.verifyPin(pin))
    }

    @Test fun `PIN-STORE a write that does not land changes nothing in memory either`() {
        val cipher = SoftwareCipher()
        val none = FakeLegacy().apply { present = false }
        val mgr = manager(open(cipher, none))
        mgr.setPin(pin)
        cipher.sealFails = true
        runCatching { mgr.setWipeAfterN(true) }
        assertFalse("the session went on with a setting that is not on disk", mgr.isWipeAfterNEnabled())
    }

    @Test fun `PIN-STORE clearPin that cannot delete the file says so and keeps the PIN`() {
        val cipher = SoftwareCipher()
        val none = FakeLegacy().apply { present = false }
        val mgr = manager(open(cipher, none))
        mgr.setPin(pin)
        val dir = target.parentFile!!
        dir.setWritable(false)
        try {
            assertTrue("a clear that did not land reported success", runCatching { mgr.clearPin() }.isFailure)
            assertTrue("the PIN is still what is on disk", mgr.hasPin())
            assertTrue(target.exists())
        } finally {
            dir.setWritable(true)
        }
    }

    @Test fun `PIN-STORE clearPin deletes an old store beside the file, and fails when it cannot`() {
        val legacy = legacyWithPin().apply { deleteLands = false }
        val cipher = SoftwareCipher()
        val mgr = manager(open(cipher, legacy))
        assertTrue("precondition: the old store is still there", legacy.present)
        assertTrue("an old store left behind would be migrated back", runCatching { mgr.clearPin() }.isFailure)
        assertTrue("the file stays while the old store does", target.exists())
        legacy.deleteLands = true
        mgr.clearPin()
        assertFalse(legacy.present)
        assertFalse(target.exists())
        assertFalse(manager(open(cipher, legacy)).hasPin())
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

    // Review item 1. An old store beside the file is either a delete that did not land (unchanged since
    // the migration: the file is newer) or a downgrade (changed: the old store is newer).

    @Test fun `PIN-STORE an old store unchanged since its migration is deleted again and the file's count stands`() {
        val legacy = legacyWithPin().apply { deleteLands = false }
        val cipher = SoftwareCipher()
        manager(open(cipher, legacy)).verifyPin("000002") // 3 failures, in the new store only
        assertTrue("the delete did not land", legacy.present)
        legacy.deleteLands = true

        val mgr = manager(open(cipher, legacy))

        assertFalse("its delete was tried again", legacy.present)
        assertTrue("the count is the file's, not the 2 the old store still held", mgr.verifyPin("000003") is PinVerifyResult.Wrong)
        assertTrue(mgr.verifyPin(pin) is PinVerifyResult.LockedOut)
    }

    @Test fun `PIN-STORE an unchanged old store whose delete keeps failing never hands back its counters`() {
        val legacy = legacyWithPin().apply { deleteLands = false }
        val cipher = SoftwareCipher()
        manager(open(cipher, legacy)).apply { verifyPin("000002"); verifyPin("000003") } // 4: a cooldown
        repeat(3) {
            assertTrue("launch $it reset the count from the old store", manager(open(cipher, legacy)).verifyPin(pin) is PinVerifyResult.LockedOut)
        }
    }

    @Test fun `PIN-STORE an old store changed after the migration (a downgrade) is the newer copy and is migrated again`() {
        val legacy = legacyWithPin().apply { deleteLands = false }
        val cipher = SoftwareCipher()
        manager(open(cipher, legacy)).verifyPin("000002") // 3 failures, in the new store
        // An older build ran: it reads only the old store, and the user set a new PIN there.
        manager(legacy.open()).setPin("999999")
        legacy.deleteLands = true

        val mgr = manager(open(cipher, legacy))

        assertSame("the PIN the user set last opens", PinVerifyResult.Success, mgr.verifyPin("999999"))
        assertFalse("the old store went once migrated", legacy.present)
        val reopened = manager(open(cipher, FakeLegacy().apply { present = false }))
        assertTrue("migrated to disk", reopened.verifyPin("999999") is PinVerifyResult.Success)
        assertTrue(reopened.verifyPin(pin) is PinVerifyResult.Wrong)
    }

    @Test fun `PIN-STORE a file with no recorded digest beside an old store wins, and the old store is deleted`() {
        // Re-review item 2: a file written before the digest existed records none; that absence is no
        // evidence the old store is newer.
        val cipher = SoftwareCipher()
        manager(open(cipher, FakeLegacy().apply { present = false })).setPin(pin)
        // As a build before the digest wrote it: the same entries, no digest.
        writeWhole(target, FilePinStore.seal(cipher, FilePinStore.read(target, cipher) - PinStoreMigration.KEY_MIGRATED_FROM))
        val legacy = FakeLegacy()
        manager(legacy.open()).setPin("999999")
        val mgr = manager(open(cipher, legacy))
        assertSame("the file's PIN", PinVerifyResult.Success, mgr.verifyPin(pin))
        assertFalse("the old store went", legacy.present)
    }

    @Test fun `PIN-STORE an old store beside the file that cannot be opened is kept and the file serves`() {
        val legacy = legacyWithPin().apply { deleteLands = false }
        val cipher = SoftwareCipher()
        manager(open(cipher, legacy)).verifyPin("000002")
        legacy.openFails = true
        val store = open(cipher, legacy)
        assertTrue(store is FilePinStore)
        assertTrue(legacy.present)
        assertTrue(manager(store).hasPin())
    }

    // ── durability: the rename is on disk before the old store goes ───────────

    /** Records each directory sync, with whether the PIN file was in place at that moment. */
    private class RecordingSync(val events: MutableList<String>, val target: File) : DirSync {
        override fun sync(dir: File) {
            events += "sync ${dir.name} inPlace=${target.exists()}"
        }
    }

    @Test fun `PIN-STORE the migration syncs the directory after the rename and before the old store is deleted`() {
        val events = mutableListOf<String>()
        val legacy = legacyWithPin().apply { onDelete = { events += "delete old" } }
        val store = PinManager.openStore { PinStoreMigration.open(target, SoftwareCipher(), legacy, RecordingSync(events, target)) }
        assertTrue(store is FilePinStore)
        val delete = events.indexOf("delete old")
        assertTrue("the old store was deleted: $events", delete >= 0)
        assertTrue(
            "a sync of ${target.parentFile!!.name} with the file in place comes before the delete: $events",
            events.subList(0, delete).contains("sync ${target.parentFile!!.name} inPlace=true")
        )
    }

    @Test fun `PIN-STORE a stale old store beside the new file is deleted only after a directory sync`() {
        val cipher = SoftwareCipher()
        manager(open(cipher, legacyWithPin().apply { deleteLands = false })).verifyPin("000002")
        val events = mutableListOf<String>()
        val legacy = legacyWithPin().apply { onDelete = { events += "delete old" } }
        PinStoreMigration.open(target, cipher, legacy, RecordingSync(events, target))
        val delete = events.indexOf("delete old")
        assertTrue("the stale old store was deleted: $events", delete >= 0)
        assertTrue("a sync comes before the delete: $events", events.subList(0, delete).any { it.startsWith("sync ") })
    }

    @Test fun `PIN-STORE every write syncs the directory once the rename has landed`() {
        val events = mutableListOf<String>()
        val none = FakeLegacy().apply { present = false }
        val mgr = manager(PinStoreMigration.open(target, SoftwareCipher(), none, RecordingSync(events, target)))
        mgr.setPin(pin)
        assertTrue("the write synced its directory after the rename: $events", events.contains("sync ${target.parentFile!!.name} inPlace=true"))
    }

    @Test fun `PIN-STORE a staging write stopped before its rename leaves nothing behind at the next launch`() {
        // The next launch need not migrate again (the store may already be in place), so the open
        // itself removes it, not the next staging write.
        val (cipher, none) = inPlace()
        val stray = File(target.parentFile, target.name + ".migrating.tmp").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertTrue(open(cipher, none) is FilePinStore)
        assertFalse("the stopped staging write is cleaned up", stray.exists())
    }

    @Test fun `PIN-STORE a write that fails once is tried again and lands`() {
        val cipher = SoftwareCipher()
        val none = FakeLegacy().apply { present = false }
        val mgr = manager(open(cipher, none))
        mgr.setPin(pin)
        cipher.sealFailsTimes = 1
        assertTrue(mgr.verifyPin("000000") is PinVerifyResult.Wrong)
        assertEquals("the failure counted on disk, not only in memory", 1, FilePinStore.read(target, cipher)["pin_fail_count"])
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
