package app.aroundtheblock.wallet.core

import android.util.Log
import app.aroundtheblock.wallet.core.db.AsideReason
import app.aroundtheblock.wallet.core.db.CipherFiles
import app.aroundtheblock.wallet.core.db.DatabaseFiles
import app.aroundtheblock.wallet.core.db.DatabaseKeyProvisioner
import app.aroundtheblock.wallet.core.db.DatabaseOpenPlan
import app.aroundtheblock.wallet.core.db.OpenAnswer
import app.aroundtheblock.wallet.core.db.RekeyAnswer
import app.aroundtheblock.wallet.core.db.WalletDatabaseOpener
import app.aroundtheblock.wallet.core.digiscope.HubTokenStore
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A wallet wipe and the databases set aside.
 *
 * Recovery never removes a database it set aside (`DatabaseFiles.setAside`): the copy may be the
 * only one of what the chain cannot give back. A full wallet wipe is the one place that removes
 * them, because after a wipe nothing of the wallet may remain — and a database set aside under the
 * legacy literal password stays readable by anyone with the file, its password being published.
 * Invariants:
 *  - after a wipe, no database file of any network remains: not the live one, not any aside, not
 *    any companion;
 *  - the eraser's read-back claims the database gone only if none of those files remains.
 *
 * Pure JVM, through the eraser's seams: real files in a scratch directory, preferences in memory,
 * the Keystore faked; the asides are made by the production code that makes them.
 */
class WipeRemovesSetAsideDatabasesTest {

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

    private class FakeKeystore(vararg present: String) : KeystoreAliases {
        val aliases = present.toMutableSet()
        override fun contains(alias: String) = alias in aliases
        override fun delete(alias: String) { aliases.remove(alias) }
    }

    /** A file the legacy literal password opens, but damaged: the opener sets it aside as corrupt. */
    private class LegacyKeyedButCorrupt : CipherFiles {
        override fun probe(password: ByteArray) =
            if (password.contentEquals("digibyte-wallet-db".toByteArray())) OpenAnswer.CORRUPT else OpenAnswer.NOT_A_DATABASE
        override fun rekey(from: ByteArray, to: ByteArray) = RekeyAnswer.UNCHANGED
    }

    private inner class Device {
        val prefs = InMemoryPrefsFactory()
        val ctx = wipeTestContext(prefs, tmp.newFolder())
        val dbDir: File = ctx.getDatabasePath("wallet.db").parentFile!!
        val keystore = FakeKeystore(DB_ALIAS)
        val eraser = AndroidWalletDataEraser(ctx, keystore) {
            HubTokenStore(prefs.prefsFor(HubTokenStore.LEGACY_PREFS_NAME), { prefs.prefsFor(HubTokenStore.SECURE_PREFS_NAME) })
        }
        private var tick = 1_000L
        fun files(name: String) = DatabaseFiles(dbDir, name, clock = { tick++ })

        /** A database at its live name, with every companion. */
        fun live(name: String, content: String) {
            File(dbDir, name).writeText(content)
            for (suffix in listOf("-wal", "-shm", "-journal")) File(dbDir, name + suffix).writeText(content + suffix)
        }

        fun databaseFiles(): List<String> = dbDir.listFiles()!!.map { it.name }.filter { it.startsWith("wallet") }.sorted()
    }

    /** On both networks: a set-aside database for every reason, a legacy-password one, and the live one — each with companions. */
    private fun Device.plantEverything() {
        for (net in listOf("", "_testnet")) {
            val name = "wallet$net.db"
            for (reason in AsideReason.values()) {
                live(name, "aside ${reason.tag}")
                assertTrue("precondition: a $name set aside (${reason.tag})", files(name).setAside(reason))
            }
            // A database keyed with the published legacy password, set aside by the opener itself.
            live(name, "legacy-keyed")
            val plan = WalletDatabaseOpener(DatabaseKeyProvisioner(prefs.prefsFor(DatabaseKeyProvisioner.RECORDS), FakeVault()),
                files(name), LegacyKeyedButCorrupt(), pause = { }).plan()
            assertTrue("precondition: the legacy-password $name was set aside ($plan)", plan is DatabaseOpenPlan.Open && !File(dbDir, name).exists())
            live(name, "live")
        }
        val asides = databaseFiles().filter { ".aside-" in it }
        assertTrue("precondition: asides with companions on both networks: $asides",
            asides.any { it.startsWith("wallet.db.aside-") && it.endsWith("-wal") } &&
                asides.any { it.startsWith("wallet_testnet.db.aside-corrupt-") })
    }

    @Test fun `after a wipe no database file of any network remains`() {
        val d = Device()
        d.plantEverything()

        val erased = d.eraser.eraseDatabase()

        assertEquals("database files a wipe left behind", emptyList<String>(), d.databaseFiles())
        assertTrue("a wipe that left no database file did not say so", erased)
    }

    @Test fun `the eraser's read-back claims the database gone only if none of its files remains`() {
        val d = Device()
        d.plantEverything()
        // An aside that cannot be removed: a non-empty directory under an aside's name.
        File(d.dbDir, "wallet_testnet.db.aside-corrupt-9").apply { mkdirs(); File(this, "held").writeText("x") }

        val erased = d.eraser.eraseDatabase()

        assertTrue("precondition: the aside that cannot be removed is still there",
            File(d.dbDir, "wallet_testnet.db.aside-corrupt-9").exists())
        assertFalse("the wipe reported the database gone while one of its files remains", erased)
    }

    @Test fun `a wallet wipe is not verified while a set-aside database remains`() = kotlinx.coroutines.test.runTest {
        val d = Device()
        d.plantEverything()
        File(d.dbDir, "wallet.db.aside-recovery-9").apply { mkdirs(); File(this, "held").writeText("x") }
        val wm = WalletManager(
            context = d.ctx,
            keyStoreManager = io.mockk.mockk(relaxed = true),
            utxoManager = io.mockk.mockk(relaxed = true),
            dataEraser = d.eraser,
            quiesceNative = { },
        )

        val report = wm.wipeWallet()

        assertFalse("a wipe that left a database file behind reported itself verified: $report", report.verified)
    }
}
