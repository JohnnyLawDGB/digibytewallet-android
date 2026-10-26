package app.aroundtheblock.wallet

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import app.aroundtheblock.wallet.core.WalletManager
import app.aroundtheblock.wallet.core.security.KeyStoreManager
import app.aroundtheblock.wallet.ui.KotlinSourceGate
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A restore that refuses is not a crashed restore.
 *
 * The restore-crash bracket ([BootGuard.beginRestore] … `markRestoreHealthy`) exists to catch a native
 * crash inside the post-unlock restore; the next launch reads a bracket left armed as such a crash and
 * recovers by wiping the sync state, and on the second one by setting the database aside. A restore
 * that refuses before its native work — no readable seed, a record that cannot say whether it has a
 * passphrase — did not crash, so it must never leave the bracket armed. The arming is handed to
 * [WalletManager.restoreFromDisk] (`beforeNative`), which runs it only where the risky work starts.
 */
class RestoreRefusalBracketTest {

    @get:Rule val tmp = TemporaryFolder()

    @Before fun silenceLog() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
    }

    @After fun restoreLog() = unmockkStatic(Log::class)

    /** SharedPreferences over a map; every write lands. */
    private class MapPrefs : SharedPreferences {
        val map = LinkedHashMap<String, Any?>()
        override fun getAll(): MutableMap<String, *> = LinkedHashMap(map)
        override fun getString(key: String?, defValue: String?) = map[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?) = map[key] as? MutableSet<String> ?: defValues
        override fun getInt(key: String?, defValue: Int) = map[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long) = map[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float) = map[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean) = map[key] as? Boolean ?: defValue
        override fun contains(key: String?) = map.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val ops = mutableListOf<(MutableMap<String, Any?>) -> Unit>()
            override fun putString(key: String, value: String?) = apply { ops += { it[key] = value } }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { ops += { it[key] = values } }
            override fun putInt(key: String, value: Int) = apply { ops += { it[key] = value } }
            override fun putLong(key: String, value: Long) = apply { ops += { it[key] = value } }
            override fun putFloat(key: String, value: Float) = apply { ops += { it[key] = value } }
            override fun putBoolean(key: String, value: Boolean) = apply { ops += { it[key] = value } }
            override fun remove(key: String) = apply { ops += { it.remove(key) } }
            override fun clear() = apply { ops.add(0) { it.clear() } }
            override fun commit(): Boolean { ops.forEach { it(map) }; return true }
            override fun apply() { commit() }
        }
    }

    private class ReachedNative : RuntimeException("the restore reached its native work")

    private val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        .toByteArray(Charsets.UTF_8)

    private inner class Device {
        val stores = HashMap<String, MapPrefs>()
        val dbDir: File = tmp.newFolder("databases")
        val ctx: Context = mockk<Context>(relaxed = true).also { ctx ->
            every { ctx.getSharedPreferences(any(), any()) } answers { stores.getOrPut(firstArg()) { MapPrefs() } }
            every { ctx.filesDir } returns tmp.newFolder("files")
            every { ctx.getDatabasePath(any()) } answers { File(dbDir, firstArg<String>()) }
            every { ctx.applicationContext } returns ctx
        }
        val ksm = mockk<KeyStoreManager>(relaxed = true)
        val seedStore get() = stores.getOrPut("dgb_wallet_seed") { MapPrefs() }

        /** A stored wallet record: the seed under the legacy key, which unwraps it. */
        fun storedSeed(readable: Boolean = true) {
            seedStore.map["encrypted_seed"] = "c1c2c3"
            seedStore.map["encrypted_seed_iv"] = "a1a2a3"
            if (readable) every { ksm.decrypt(any()) } answers { mnemonic.copyOf() }
            else every { ksm.decrypt(any()) } throws java.security.ProviderException("keystore busy")
        }

        fun walletManager() = WalletManager(
            context = ctx,
            keyStoreManager = ksm,
            utxoManager = mockk(relaxed = true),
            dataEraser = mockk(relaxed = true),
            quiesceNative = { },
            seedNative = { m, p -> java.security.MessageDigest.getInstance("SHA-512").apply { update(m); p?.let { update(it) } }.digest() },
            releaseDatabaseKey = { true },
        )

        /** What the next launch does about a restore it finds still bracketed. */
        fun nextLaunch() = BootGuard.recoverFromCrashedRestoreIfNeeded(ctx)
    }

    private fun Device.restoreAsTheCallSitesDo(): Boolean =
        walletManager().restoreFromDisk(beforeNative = { BootGuard.beginRestore(ctx) })

    @Test fun `a restore refused for a record that cannot say whether it has a passphrase is not counted as a crash`() {
        val d = Device()
        d.storedSeed() // an earlier release's record with nothing to show its passphrase write landed
        File(d.dbDir, "wallet.db").writeText("rows")

        for (attempt in 1..2) {
            assertFalse("precondition: the restore refuses", d.restoreAsTheCallSitesDo())
            assertEquals(
                "a restore that refused before its native work was counted as a crashed restore (attempt $attempt): " +
                    "the next launch wipes the sync state, and on the second the database is set aside",
                BootGuard.RestoreRecovery.None, d.nextLaunch(),
            )
        }
        assertEquals("the database was moved after refused restores", "rows", File(d.dbDir, "wallet.db").readText())
    }

    @Test fun `a restore refused because the seed could not be read is not counted as a crash`() {
        val d = Device()
        d.storedSeed(readable = false)

        assertFalse("precondition: the restore refuses", d.restoreAsTheCallSitesDo())
        assertEquals("a restore that could not read its seed was counted as a crashed restore",
            BootGuard.RestoreRecovery.None, d.nextLaunch())
    }

    // GUARD: a restore that goes on to its native work is bracketed before that work starts.
    @Test fun `a restore that reaches its native work arms the bracket first`() {
        val d = Device()
        d.storedSeed()
        d.seedStore.map["wallet_creation_time"] = 1_767_225_600L // a complete record from an earlier release

        try {
            d.walletManager().restoreFromDisk(beforeNative = { BootGuard.beginRestore(d.ctx); throw ReachedNative() })
            fail("the restore did not reach its native work")
        } catch (e: ReachedNative) {
            // the bracket was armed at the start of the risky work, as the call sites ask
        }
        assertEquals(BootGuard.RestoreRecovery.Sync, d.nextLaunch())
    }

    // GUARD: the call sites arm the bracket only through restoreFromDisk's hook.
    @Test fun `the unlock and lost-PIN paths arm the bracket only through restoreFromDisk`() {
        for (path in listOf(
            "src/main/java/app/aroundtheblock/wallet/ui/onboarding/UnlockScreen.kt",
            "src/main/java/app/aroundtheblock/wallet/ui/navigation/AppNavigation.kt",
        )) {
            val gate = KotlinSourceGate.of(File(path).readText())
            val restores = gate.calls("walletManager.restoreFromDisk")
            assertTrue("scanner is blind: no restore in $path", restores.isNotEmpty())
            for (restore in restores) {
                assertTrue("$path restores without handing over the bracket: ${restore.text}", restore.named.containsKey("beforeNative"))
            }
            val arms = gate.calls("BootGuard.beginRestore")
            assertTrue("scanner is blind: no arming in $path", arms.isNotEmpty())
            for (arm in arms) {
                val inside = gate.enclosingBlock(arm.range.first)
                assertTrue("$path arms the bracket outside a hook handed to restoreFromDisk",
                    inside != null && (gate.code.substring(0, inside.first).trimEnd().endsWith("=") ||
                        gate.code.substring(0, inside.first).trimEnd().endsWith("beforeNative =")))
            }
        }
    }
}
