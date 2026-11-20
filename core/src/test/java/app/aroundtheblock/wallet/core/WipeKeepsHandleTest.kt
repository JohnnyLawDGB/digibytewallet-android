package app.aroundtheblock.wallet.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import app.aroundtheblock.wallet.core.digiscope.HubTokenStore
import app.aroundtheblock.wallet.core.security.PinManager
import app.aroundtheblock.wallet.core.sync.ChainTipStore
import app.aroundtheblock.wallet.core.wallets.ActiveWallet
import app.aroundtheblock.wallet.core.wallets.WalletHousekeeping
import app.aroundtheblock.wallet.core.wallets.WalletOrigin
import app.aroundtheblock.wallet.core.wallets.WalletRegistry
import app.aroundtheblock.wallet.core.wallets.WalletScope
import app.aroundtheblock.wallet.core.wallets.WalletStoreEraser
import app.aroundtheblock.wallet.core.wallets.WalletStores
import app.aroundtheblock.wallet.core.wallets.WipeOwedStores
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
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
 * INT-2026-11-19-A. A wipe reports "verified" only when no store of any wallet is left on the
 * device, and a store a wipe could not clear is still found by the next launch's wipe.
 *
 * A wallet is found by its registry entry, its seed record or its seed key alias, and a wipe removes
 * all three before its stores. So a wipe that could not clear one wallet's store keeps that wallet's
 * id on record ([WipeOwedStores]) until its stores read back empty, and a verified wipe leaves
 * neither the record nor the id.
 *
 * Preferences behave like the platform's ([FilePrefsFactory]) and each launch is a new process: a
 * new context and new preference instances over the same directory. The id set is the production
 * one ([ActiveWallet.everyWalletId]), not injected.
 */
class WipeKeepsHandleTest {

    @get:Rule val tmp = TemporaryFolder()

    @Before fun setUp() {
        ActiveWallet.resetForTest()
        ChainTipStore.invalidateCache()
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
    }

    @After fun tearDown() {
        unmockkStatic(Log::class)
        ActiveWallet.resetForTest()
        ChainTipStore.invalidateCache()
    }

    /** A store whose writes do not land: `commit()` reports false and the file keeps what it held. */
    private class RefusingPrefs(inner: SharedPreferences) : SharedPreferences by inner {
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            override fun putString(key: String, value: String?) = this
            override fun putStringSet(key: String, values: MutableSet<String>?) = this
            override fun putInt(key: String, value: Int) = this
            override fun putLong(key: String, value: Long) = this
            override fun putFloat(key: String, value: Float) = this
            override fun putBoolean(key: String, value: Boolean) = this
            override fun remove(key: String) = this
            override fun clear() = this
            override fun commit() = false
            override fun apply() = Unit
        }
    }

    /**
     * A store as the platform's behaves when a commit fails: `commit()` reports false and the file
     * keeps what it held, but this process reads its own write back until it ends.
     */
    private class MemoryOnlyPrefs(private val inner: SharedPreferences) : SharedPreferences by inner {
        private val view = HashMap<String, Any?>(inner.all)

        /** From now on a commit writes this process's whole view through to the file, as a landed commit does. */
        var lands = false

        /** A commit changes this process's view and then throws. */
        var throws = false

        private fun writeThrough(): Boolean {
            val e = inner.edit().clear()
            view.forEach { (k, v) -> if (v is String) e.putString(k, v) }
            return e.commit()
        }
        override fun getAll(): MutableMap<String, *> = HashMap(view)
        override fun getString(key: String, defValue: String?) = view[key] as? String ?: defValue
        override fun contains(key: String) = view.containsKey(key)
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val ops = ArrayList<() -> Unit>()
            override fun putString(key: String, value: String?) = apply { ops += { view[key] = value } }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { ops += { view[key] = values } }
            override fun putInt(key: String, value: Int) = apply { ops += { view[key] = value } }
            override fun putLong(key: String, value: Long) = apply { ops += { view[key] = value } }
            override fun putFloat(key: String, value: Float) = apply { ops += { view[key] = value } }
            override fun putBoolean(key: String, value: Boolean) = apply { ops += { view[key] = value } }
            override fun remove(key: String) = apply { ops += { view.remove(key) } }
            override fun clear() = apply { ops += { view.clear() } }
            override fun commit(): Boolean {
                ops.forEach { it() }
                if (throws) throw IllegalStateException("commit did not return")
                return lands && writeThrough()
            }
            override fun apply() { ops.forEach { it() } }
        }
    }

    private inner class Device {
        val root: File = tmp.newFolder()
        val vault = FakeVault()
        val aliases = vault.asKeystoreAliases()

        /** Prefs files whose writes are refused, in every launch, until taken out again. */
        val refusing = mutableSetOf<String>()

        /** Prefs files whose writes this process reads back although they never reach the file. */
        val memoryOnly = mutableSetOf<String>()
        private val memoryOnlyOpen = HashMap<String, MemoryOnlyPrefs>()

        /** The open memory-only view of [name] in this process. */
        fun memoryOnlyView(name: String): MemoryOnlyPrefs = memoryOnlyOpen.getValue(name)

        lateinit var ctx: Context
        lateinit var prefs: FilePrefsFactory
        lateinit var b: WalletScope
        lateinit var stuck: String

        init { launch() }

        /** Prefs files that take their writes but whose file cannot be deleted. */
        val undeletable = mutableSetOf<String>()

        /** Prefs files that cannot be opened at all. */
        val unreadable = mutableSetOf<String>()

        /** A new process over the same device: nothing held from the one before. */
        fun launch() {
            ActiveWallet.resetForTest()
            filePrefsContext(root).also { (c, p) -> ctx = c; prefs = p }
            WipeOwedStores.resetForTest()
            memoryOnlyOpen.clear()
            every { ctx.getSharedPreferences(match { it in memoryOnly }, any()) } answers {
                memoryOnlyOpen.getOrPut(firstArg()) { MemoryOnlyPrefs(prefs.prefsFor(firstArg())) }
            }
            every { ctx.deleteSharedPreferences(match { it in memoryOnly }) } returns false
            every { ctx.getSharedPreferences(match { it in refusing }, any()) } answers {
                RefusingPrefs(prefs.prefsFor(firstArg()))
            }
            every { ctx.deleteSharedPreferences(match { it in refusing || it in undeletable }) } returns false
            every { ctx.getSharedPreferences(match { it in unreadable }, any()) } throws IllegalStateException("not opened")
        }

        /** Legacy and wallet B (mainnet), each with its record and key; B with two stores. */
        fun populate() {
            prefs.prefsFor("dgb_wallet_seed").seed("encrypted_seed_v2", "c1")
            vault.createUnboundKey(WalletStores.keyAliasV2(WalletScope.legacy(false)))
            val registry = ActiveWallet.registry(ctx)
            val entry = registry.reserve(false, WalletOrigin.CREATED, 1L)!!
            b = WalletScope(entry.id, false)
            prefs.prefsFor(WalletStores.seedRecordPrefs(b)).seed("encrypted_seed_v2", "c2")
            stuck = WalletStores.prefsName("dgb_sync_data", b)
            prefs.prefsFor(stuck).seed("last_balance", 7L)
            prefs.prefsFor(WalletStores.prefsName("dgb_watched_addrs", b)).seed("held", "x")
            vault.createUnboundKey(WalletStores.keyAliasV2(b))
            assertTrue(registry.complete(entry.id, makeActive = false))
        }

        fun eraser() = WalletStoreEraser(ctx, aliases, { true }) { name -> File(root, "shared_prefs/$name.xml").exists() }

        fun dataEraser(): WalletDataEraser = AndroidWalletDataEraser(ctx, aliases) {
            HubTokenStore(ctx, WalletScope.legacy(false)) { name -> ctx.getSharedPreferences(name, Context.MODE_PRIVATE) }
        }

        fun walletManager(
            dataEraser: WalletDataEraser = dataEraser(),
            allWalletIds: (() -> List<String>)? = null,
        ) = if (allWalletIds == null) walletManagerListing(dataEraser) { ActiveWallet.everyWalletId(ctx) { aliases.aliases() } }
        else walletManagerListing(dataEraser, allWalletIds)

        private fun walletManagerListing(dataEraser: WalletDataEraser, allWalletIds: () -> List<String>) = WalletManager(
            context = ctx,
            keyStoreManager = keyStoreManagerOver(vault, alias = WalletStores.keyAlias(WalletScope.legacy(false))),
            utxoManager = mockk(relaxed = true),
            dataEraser = dataEraser,
            quiesceNative = { },
            scopeEraser = eraser(),
            keystoreAliases = aliases,
            allWalletIds = allWalletIds,
        )

        fun pin() = PinManager(RecordingPinStore().apply { map["pin_hash"] = "00"; map["pin_salt"] = "00"; map["pin_method"] = "pbkdf2" })

        fun owedFile() = File(root, "shared_prefs/${WipeOwedStores.PREFS}.xml")

        fun stuckStoreOnDisk() = File(root, "shared_prefs/$stuck.xml").exists()

        /** Every file under the device's data directory that carries B's id, in its name or in what it holds. */
        fun namingB(): List<String> = root.walkTopDown().filter { it.isFile }
            .filter { b.walletId in it.name || b.walletId in it.readText() }
            .map { it.relativeTo(root).path }.sorted().toList()
    }

    @Test fun `a store a wipe could not clear is found and cleared by the next launch's wipe`() = runTest {
        val d = Device().apply { populate() }
        d.refusing += d.stuck

        val first = d.walletManager().wipeThenReleasePin(d.pin())

        assertTrue("precondition: every seed record and key went", first.seedGone)
        assertFalse("a store is left, yet the wipe verified", first.verified)
        assertTrue("precondition: the store is still on the device", d.stuckStoreOnDisk())

        // The next launch: the store still refuses, so that wipe finds it and cannot clear it either.
        d.launch()
        val second = d.walletManager().wipeThenReleasePin(d.pin())

        assertTrue("precondition: the store is still on the device", d.stuckStoreOnDisk())
        assertFalse("a wallet's store is on the device, yet the wipe verified", second.verified)

        // The launch after: the store takes its erase.
        d.refusing.clear()
        d.launch()
        val pin = d.pin()
        val third = d.walletManager().wipeThenReleasePin(pin)

        assertFalse("the store was never looked for again", d.stuckStoreOnDisk())
        assertTrue("$third", third.verified)
        assertFalse("the PIN store was not cleared after the verified wipe", pin.hasPin())
        assertEquals("a verified wipe left the wallet's id on the device", emptyList<String>(), d.namingB())
    }

    @Test fun `the PIN store is kept while a wallet's store is left`() = runTest {
        val d = Device().apply { populate() }
        d.refusing += d.stuck
        d.walletManager().wipeThenReleasePin(d.pin())

        d.launch()
        val pin = d.pin()
        d.walletManager().wipeThenReleasePin(pin)

        assertTrue("precondition: the store is still on the device", d.stuckStoreOnDisk())
        assertTrue("the PIN store was cleared by a wipe that left a wallet's store", pin.hasPin())
    }

    @Test fun `a launch erase kept for the next launch is still found after the wipe owed at that launch`() = runTest {
        val d = Device().apply { populate() }
        // A READY wallet with no record: what launch housekeeping erases, stores first.
        assertTrue(d.eraser().eraseRecord(d.b))
        d.refusing += d.stuck
        d.launch()

        assertEquals("the launch erase dropped a wallet whose store is left", 0, WalletHousekeeping(ActiveWallet.registry(d.ctx), d.eraser()).run())
        assertTrue("precondition: its entry is kept for the next launch", d.b.walletId in ActiveWallet.registry(d.ctx).allEntryIds())
        // The wipe owed at that same launch cannot clear the store either.
        assertFalse(d.walletManager().wipeThenReleasePin(d.pin()).verified)

        d.launch()
        WalletHousekeeping(ActiveWallet.registry(d.ctx), d.eraser()).run()
        val second = d.walletManager().wipeThenReleasePin(d.pin())

        assertTrue("precondition: the store is still on the device", d.stuckStoreOnDisk())
        assertFalse("a wallet's store is on the device, yet the wipe verified", second.verified)

        d.refusing.clear()
        d.launch()
        WalletHousekeeping(ActiveWallet.registry(d.ctx), d.eraser()).run()
        val third = d.walletManager().wipeThenReleasePin(d.pin())

        assertTrue("$third", third.verified)
        assertEquals("a verified wipe left the wallet's id on the device", emptyList<String>(), d.namingB())
    }

    @Test fun `the ids are on record before the first store is erased, and no seed step waits for them`() = runTest {
        val d = Device().apply { populate() }
        val real = d.dataEraser()
        var atRecordErase: List<String>? = null
        var atFirstStore: List<String>? = null
        var keysAtFirstStore: Set<String>? = null
        val watching = object : WalletDataEraser by real {
            override fun eraseSeedCiphertext(): Boolean {
                atRecordErase = WipeOwedStores.ids(d.ctx)
                return real.eraseSeedCiphertext()
            }
            override fun eraseSyncData(): Boolean {
                atFirstStore = WipeOwedStores.ids(d.ctx)
                keysAtFirstStore = d.vault.keys.keys.toSet()
                return real.eraseSyncData()
            }
        }

        val report = d.walletManager(watching).wipeThenReleasePin(d.pin())

        assertEquals("the record was written ahead of the seed records' erase", emptyList<String>(), atRecordErase)
        assertEquals("the wallet's id was not on record when its stores began to go", listOf(d.b.walletId), atFirstStore)
        assertEquals("a seed key was still there when the stores began to go", emptySet<String>(),
            keysAtFirstStore!!.filter { WalletStores.walletIdOfAlias(it) != null }.toSet())
        assertTrue("$report", report.verified)
        assertEquals(emptyList<String>(), d.namingB())
    }

    @Test fun `a wipe whose record of ids did not land keeps the registry, so the next wipe still lists the wallet`() = runTest {
        val d = Device().apply { populate() }
        // One run in which neither B's store nor the record of ids takes its write.
        d.refusing += d.stuck
        d.refusing += WipeOwedStores.PREFS

        val first = d.walletManager().wipeThenReleasePin(d.pin())

        assertTrue("precondition: every seed record and key went", first.seedGone)
        assertFalse(first.verified)
        assertEquals("precondition: the id is not on record", emptyList<String>(), WipeOwedStores.ids(d.ctx))

        // The next launch: the record takes writes again, the store still refuses.
        d.refusing -= WipeOwedStores.PREFS
        d.launch()
        val pin = d.pin()
        val second = d.walletManager().wipeThenReleasePin(pin)

        assertTrue("precondition: the store is still on the device", d.stuckStoreOnDisk())
        assertFalse("a wallet's store is on the device, yet the wipe verified", second.verified)
        assertTrue("the PIN store was cleared by a wipe that left a wallet's store", pin.hasPin())

        d.refusing.clear()
        d.launch()
        val third = d.walletManager().wipeThenReleasePin(d.pin())

        assertTrue("$third", third.verified)
        assertEquals("a verified wipe left the wallet's id on the device", emptyList<String>(), d.namingB())
        assertFalse("a verified wipe left the registry", File(d.root, "shared_prefs/${WalletRegistry.PREFS}.xml").exists())
    }

    @Test fun `a record write this process reads back but that never reached the file does not release the registry`() = runTest {
        val d = Device().apply { populate() }
        // One run in which B's store refuses and the record of ids takes its writes in memory only.
        d.refusing += d.stuck
        d.memoryOnly += WipeOwedStores.PREFS

        val first = d.walletManager().wipeThenReleasePin(d.pin())

        assertTrue("precondition: every seed record and key went", first.seedGone)
        assertFalse(first.verified)
        assertTrue("precondition: this process reads the id back", d.b.walletId in WipeOwedStores.ids(d.ctx))
        assertFalse("precondition: the id never reached the file", File(d.root, "shared_prefs/${WipeOwedStores.PREFS}.xml").exists())

        // The next launch: the record takes writes again, the store still refuses.
        d.memoryOnly.clear()
        d.launch()
        val pin = d.pin()
        val second = d.walletManager().wipeThenReleasePin(pin)

        assertTrue("precondition: the store is still on the device", d.stuckStoreOnDisk())
        assertFalse("a wallet's store is on the device, yet the wipe verified", second.verified)
        assertTrue("the PIN store was cleared by a wipe that left a wallet's store", pin.hasPin())

        d.refusing.clear()
        d.launch()
        val third = d.walletManager().wipeThenReleasePin(d.pin())

        assertTrue("$third", third.verified)
        assertEquals("a verified wipe left the wallet's id on the device", emptyList<String>(), d.namingB())
    }

    @Test fun `after a record write that did not land, the record is written in full by the next write of the same process`() = runTest {
        val d = Device().apply { populate() }
        d.refusing += d.stuck
        d.memoryOnly += WipeOwedStores.PREFS
        val recordFile = File(d.root, "shared_prefs/${WipeOwedStores.PREFS}.xml")

        val first = d.walletManager().wipeThenReleasePin(d.pin())
        assertFalse(first.verified)
        assertFalse("precondition: the id never reached the file", recordFile.exists())

        // The same process: the record's writes land again; the store still refuses.
        d.memoryOnlyView(WipeOwedStores.PREFS).lands = true
        val pin = d.pin()
        val second = d.walletManager().wipeThenReleasePin(pin)

        assertFalse("a wallet's store is on the device, yet the wipe verified", second.verified)
        assertTrue("the PIN store was cleared by a wipe that left a wallet's store", pin.hasPin())
        assertTrue("the id did not reach the file although this process read it back", recordFile.exists() && d.b.walletId in recordFile.readText())

        d.refusing.clear()
        d.memoryOnly.clear()
        d.launch()
        val third = d.walletManager().wipeThenReleasePin(d.pin())
        assertTrue("$third", third.verified)
        assertEquals("a verified wipe left the wallet's id on the device", emptyList<String>(), d.namingB())
    }

    @Test fun `a record write that changes what this process reads and then throws does not release the registry`() = runTest {
        val d = Device().apply { populate() }
        d.refusing += d.stuck
        d.memoryOnly += WipeOwedStores.PREFS
        d.ctx.getSharedPreferences(WipeOwedStores.PREFS, Context.MODE_PRIVATE)
        d.memoryOnlyView(WipeOwedStores.PREFS).throws = true

        val first = d.walletManager().wipeThenReleasePin(d.pin())
        assertFalse(first.verified)

        d.memoryOnly.clear()
        d.launch()
        val pin = d.pin()
        val second = d.walletManager().wipeThenReleasePin(pin)

        assertTrue("precondition: the store is still on the device", d.stuckStoreOnDisk())
        assertFalse("a wallet's store is on the device, yet the wipe verified", second.verified)
        assertTrue("the PIN store was cleared by a wipe that left a wallet's store", pin.hasPin())
    }

    @Test fun `a wipe does not verify while the record of ids cannot be read`() = runTest {
        val d = Device().apply { populate() }
        d.refusing += d.stuck
        d.walletManager().wipeThenReleasePin(d.pin())
        assertEquals("precondition: the id is on record", listOf(d.b.walletId), WipeOwedStores.ids(d.ctx))

        // The next launch: the record cannot be opened, and the store still refuses.
        d.unreadable += WipeOwedStores.PREFS
        d.launch()
        val pin = d.pin()
        val second = d.walletManager().wipeThenReleasePin(pin)

        assertTrue("precondition: the store is still on the device", d.stuckStoreOnDisk())
        assertFalse("the record of ids could not be read, yet the wipe verified", second.verified)
        assertTrue("the PIN store was cleared by a wipe that could not read the record of ids", pin.hasPin())

        d.unreadable.clear()
        d.refusing.clear()
        d.launch()
        val third = d.walletManager().wipeThenReleasePin(d.pin())

        assertTrue("$third", third.verified)
        assertEquals(emptyList<String>(), d.namingB())
    }

    @Test fun `a wipe does not verify while the emptied record's file is on the device`() = runTest {
        val d = Device().apply { populate() }
        d.undeletable += WipeOwedStores.PREFS
        d.launch()
        val pin = d.pin()

        val first = d.walletManager().wipeThenReleasePin(pin)

        assertTrue("precondition: the record's file is still on the device", d.owedFile().exists())
        assertFalse("a file only a device with more than one wallet has is left, yet the wipe verified", first.verified)
        assertTrue(pin.hasPin())

        // The next launch finds the emptied file and removes it.
        d.undeletable.clear()
        d.launch()
        val second = d.walletManager().wipeThenReleasePin(d.pin())

        assertTrue("$second", second.verified)
        assertFalse("a verified wipe left the record's file", d.owedFile().exists())
    }

    @Test fun `a wipe does not verify while an id is still on record`() = runTest {
        val d = Device().apply { populate() }
        // An earlier wipe left B's id on record; its stores go now, but the record will not take the write.
        assertTrue(WipeOwedStores.record(d.ctx, owed = listOf(d.b.walletId)))
        d.refusing += WipeOwedStores.PREFS
        d.launch()

        val report = d.walletManager().wipeThenReleasePin(d.pin())

        assertFalse("precondition: B's store went", d.stuckStoreOnDisk())
        assertEquals("precondition: the id is still on record", listOf(d.b.walletId), WipeOwedStores.ids(d.ctx))
        assertFalse("a wallet id is left on the device, yet the wipe verified", report.verified)
    }

    @Test fun `a wipe that did not list an id on record does not verify`() = runTest {
        val d = Device()
        d.prefs.prefsFor("dgb_wallet_seed").seed("encrypted_seed_v2", "c1")
        d.vault.createUnboundKey(WalletStores.keyAliasV2(WalletScope.legacy(false)))
        val unlisted = "0123456789abcdef"
        assertTrue(WipeOwedStores.record(d.ctx, owed = listOf(unlisted)))

        val report = d.walletManager(allWalletIds = { listOf("legacy") }).wipeThenReleasePin(d.pin())

        assertTrue("precondition: every seed record and key went", report.seedGone)
        assertEquals("an id this run never erased was taken off the record", listOf(unlisted), WipeOwedStores.ids(d.ctx))
        assertFalse("a wallet is still owed its erase, yet the wipe verified", report.verified)
    }

    @Test fun `a wipe of a device with one wallet writes no record of ids`() = runTest {
        val d = Device()
        d.prefs.prefsFor("dgb_wallet_seed").seed("encrypted_seed_v2", "c1")
        d.vault.createUnboundKey(WalletStores.keyAliasV2(WalletScope.legacy(false)))
        var filesAtFirstStore: List<String>? = null
        val real = d.dataEraser()
        val watching = object : WalletDataEraser by real {
            override fun eraseSyncData(): Boolean {
                filesAtFirstStore = d.prefs.files()
                return real.eraseSyncData()
            }
        }

        val report = d.walletManager(watching).wipeThenReleasePin(d.pin())

        assertTrue("$report", report.verified)
        assertFalse("a record of no ids was written", "${WipeOwedStores.PREFS}.xml" in filesAtFirstStore!!)
        assertFalse("${WipeOwedStores.PREFS}.xml" in d.prefs.files())
    }

    @Test fun `the record of ids holds each valid id once, never legacy, and its file goes with the last id`() {
        val d = Device()
        val idB = "0123456789abcdef"
        val idC = "fedcba9876543210"

        assertTrue(WipeOwedStores.record(d.ctx, owed = emptyList()))
        assertEquals("a record of nothing created a file", emptyList<String>(), d.prefs.files())

        assertTrue(WipeOwedStores.record(d.ctx, owed = listOf("legacy", "not-an-id")))
        assertEquals("an id that is never listed was written", emptyList<String>(), d.prefs.files())

        assertTrue(WipeOwedStores.record(d.ctx, owed = listOf(idB, "legacy", "not-an-id", idB)))
        assertEquals("what the file holds is not the one valid id", listOf(idB), d.owedFile().readText().let { text ->
            listOf(idB, "legacy", "not-an-id").filter { it in text }
        })
        assertTrue(WipeOwedStores.record(d.ctx, owed = listOf(idC)))
        assertEquals(listOf(idB, idC), WipeOwedStores.ids(d.ctx))

        // An id is taken off only when it is named as cleared; one still owed in the same call stays.
        assertTrue(WipeOwedStores.record(d.ctx, owed = listOf(idC), cleared = listOf(idB, idC)))
        assertEquals(listOf(idC), WipeOwedStores.ids(d.ctx))
        d.launch()
        assertEquals("the record did not reach the disk", listOf(idC), WipeOwedStores.ids(d.ctx))

        assertTrue(WipeOwedStores.record(d.ctx, cleared = listOf(idC)))
        assertEquals(emptyList<String>(), WipeOwedStores.ids(d.ctx))
        assertEquals("the emptied record's file is left", emptyList<String>(), d.prefs.files())
    }

    @Test fun `a record that does not take its write says so`() {
        val d = Device()
        d.refusing += WipeOwedStores.PREFS
        d.launch()

        assertTrue("nothing to write is not a write that failed", WipeOwedStores.record(d.ctx, cleared = listOf("0123456789abcdef")))
        assertFalse(WipeOwedStores.record(d.ctx, owed = listOf("0123456789abcdef")))
    }

    @Test fun `a record that cannot be read is neither written over nor settled`() {
        val d = Device()
        val id = "0123456789abcdef"
        assertTrue(WipeOwedStores.record(d.ctx, owed = listOf(id)))
        d.unreadable += WipeOwedStores.PREFS
        d.launch()

        assertEquals("what cannot be read was read", null, WipeOwedStores.read(d.ctx))
        assertEquals("listing wallets must not fail on it", emptyList<String>(), WipeOwedStores.ids(d.ctx))
        assertFalse("nothing to take off a record that cannot be read is not a record that holds nothing",
            WipeOwedStores.record(d.ctx, cleared = listOf("fedcba9876543210")))
        assertFalse("a record that cannot be read counted as empty", WipeOwedStores.settled(d.ctx))
        assertTrue("the record's file was removed unread", d.owedFile().exists())

        d.unreadable.clear()
        d.launch()
        assertEquals(listOf(id), WipeOwedStores.ids(d.ctx))
        assertFalse("a record that names a wallet is settled", WipeOwedStores.settled(d.ctx))
        assertTrue(WipeOwedStores.record(d.ctx, cleared = listOf(id)))
        assertTrue(WipeOwedStores.settled(d.ctx))
    }

    @Test fun `the production id set includes the ids on record`() {
        val d = Device()
        val id = "0123456789abcdef"
        assertTrue(WipeOwedStores.record(d.ctx, owed = listOf(id)))

        assertEquals(listOf("legacy", id), ActiveWallet.everyWalletId(d.ctx) { emptyList() })
    }
}
