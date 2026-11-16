package app.aroundtheblock.wallet.core.wallets

import app.aroundtheblock.wallet.core.InMemoryPrefs
import app.aroundtheblock.wallet.core.InMemoryPrefsFactory
import android.content.SharedPreferences
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletRegistryTest {
    private val factory = InMemoryPrefsFactory()
    private val prefs: InMemoryPrefs = factory.prefsFor("dgb_wallets")
    private var recordsOnDisk: List<Pair<String, Boolean?>> = emptyList()
    private val missingRecords = mutableSetOf<String>()
    private var legacyRecord = true

    private fun registry() = WalletRegistry(
        prefs = prefs,
        recordExists = { scope ->
            if (scope.isLegacy) legacyRecord else scope.walletId !in missingRecords
        },
        recordsOnDisk = { recordsOnDisk },
    )

    private fun reserved(r: WalletRegistry, testnet: Boolean = false, active: Boolean = false): WalletEntry {
        val e = r.reserve(testnet, WalletOrigin.CREATED, 1_000L)!!
        assertTrue(r.complete(e.id, makeActive = active))
        return e
    }

    @Test
    fun `an install from 4-1-4 lists exactly the legacy wallet and writes nothing`() {
        val r = registry()
        assertEquals(listOf("legacy"), r.list(false).map { it.id })
        assertEquals(1, r.list(false)[0].number)
        assertNull(r.list(false)[0].name)
        assertTrue(prefs.all.isEmpty())
    }

    @Test
    fun `no record and no registry lists nothing`() {
        legacyRecord = false
        assertEquals(emptyList<WalletEntry>(), registry().list(false))
    }

    @Test
    fun `a reserved wallet is not listed until complete`() {
        val r = registry()
        val e = r.reserve(false, WalletOrigin.CREATED, 1_000L)!!
        assertTrue(Regex("[0-9a-f]{16}").matches(e.id))
        assertFalse(r.list(false).any { it.id == e.id })
        assertTrue(r.incomplete().any { it.id == e.id })
        assertTrue(r.complete(e.id, makeActive = true))
        val listed = r.list(false).first { it.id == e.id }
        assertEquals(2, listed.number)
        assertEquals(e.id, r.activeId(false))
    }

    @Test
    fun `the eighth wallet is the last`() {
        val r = registry()
        repeat(7) { reserved(r) }
        assertNull(r.reserve(false, WalletOrigin.CREATED, 1L))
        assertNotNull(r.reserve(true, WalletOrigin.CREATED, 1L))
    }

    @Test
    fun `a pending entry counts toward the cap`() {
        val r = registry()
        repeat(6) { reserved(r) }
        assertNotNull(r.reserve(false, WalletOrigin.CREATED, 1L))
        assertFalse(r.canReserve(false))
        assertNull(r.reserve(false, WalletOrigin.CREATED, 1L))
    }

    @Test
    fun `an active id whose record is gone resolves to the first wallet that has one`() {
        val r = registry()
        val b = reserved(r, active = true)
        assertEquals(b.id, r.activeId(false))
        missingRecords += b.id
        assertEquals("legacy", r.activeId(false))
    }

    @Test
    fun `an unreadable registry is rebuilt from the seed records on disk`() {
        prefs.seed("registry", "{not json")
        recordsOnDisk = listOf("0123456789abcdef" to false, "fedcba9876543210" to null)
        val r = registry()
        assertEquals(listOf("legacy", "0123456789abcdef", "fedcba9876543210"), r.list(false).map { it.id })
        r.list(false).drop(1).forEach {
            assertNull(it.name)
            assertEquals(WalletOrigin.RESTORED, it.origin)
        }
        assertEquals("legacy", r.activeId(false))
    }

    @Test
    fun `names are trimmed, at most 32 chars and unique ignoring case`() {
        val r = registry()
        val b = reserved(r)
        val c = reserved(r)
        assertTrue(r.rename(b.id, " Savings "))
        assertEquals("Savings", r.find(b.id)!!.name)
        assertFalse(r.rename(c.id, "savings"))
        assertFalse(r.rename(c.id, ""))
        assertFalse(r.rename(c.id, "x".repeat(33)))
    }

    @Test
    fun `reorder keeps every id`() {
        val r = registry()
        val b = reserved(r)
        assertTrue(r.reorder(false, listOf(b.id, "legacy")))
        assertEquals(listOf(b.id, "legacy"), r.list(false).map { it.id })
        assertFalse(r.reorder(false, listOf(b.id)))
    }

    @Test
    fun `a refused commit changes nothing`() {
        val r = registry()
        val b = reserved(r)
        factory.refuseWritesTo("dgb_wallets")
        assertFalse(r.setActive(false, b.id))
        assertEquals("legacy", r.activeId(false))
    }

    @Test
    fun `removing a wallet moves the active id on every network that names it`() {
        val r = registry()
        val b = reserved(r, testnet = false)
        val t = reserved(r, testnet = true)
        assertTrue(r.markRemoving("legacy"))
        assertEquals(b.id, r.activeId(false))
        assertEquals(t.id, r.activeId(true))
        assertEquals(EntryState.REMOVING, r.find("legacy")!!.state)
        assertTrue(r.list(false).none { it.id == "legacy" })
        assertEquals("REMOVING", JSONObject(prefs.getString("registry", null)!!).getJSONObject("legacy").getString("state"))
    }

    @Test
    fun `legacy reads as READY when its state is absent`() {
        prefs.seed("registry", """{"v":1,"entries":[],"legacy":{"name":null,"order":0},"active":{}}""")
        assertEquals(listOf("legacy"), registry().list(false).map { it.id })
    }

    @Test
    fun `any record means a wallet exists`() {
        legacyRecord = false
        assertFalse(registry().anyRecordExists())
        recordsOnDisk = listOf("0123456789abcdef" to true)
        assertTrue(registry().anyRecordExists())
    }

    @Test
    fun `a rebuilt registry lists only ids whose record exists`() {
        // An empty seed prefs file left on disk (a removal that died after clearing it) is not a wallet.
        prefs.seed("registry", "{not json")
        recordsOnDisk = listOf("0123456789abcdef" to false, "fedcba9876543210" to false)
        missingRecords += "fedcba9876543210"
        assertEquals(listOf("legacy", "0123456789abcdef"), registry().list(false).map { it.id })
    }

    @Test
    fun `an empty seed prefs file alone does not mean a wallet exists`() {
        legacyRecord = false
        recordsOnDisk = listOf("0123456789abcdef" to false)
        missingRecords += "0123456789abcdef"
        assertFalse(registry().anyRecordExists())
    }

    /**
     * Two registry instances over one prefs file (ActiveWallet.registry builds one per call). The
     * first reader is held until a second reader arrives (or 500 ms pass): unserialised, both
     * read the same registry and the second commit drops the first one's entry.
     */
    @Test
    fun `concurrent mutations from two registry instances lose nothing`() {
        val paused = AtomicBoolean(false)
        val secondReader = CountDownLatch(1)
        val gated = object : SharedPreferences by prefs {
            override fun getString(key: String?, defValue: String?): String? {
                if (paused.compareAndSet(false, true)) secondReader.await(500, TimeUnit.MILLISECONDS) else secondReader.countDown()
                return prefs.getString(key, defValue)
            }
        }
        fun instance() = WalletRegistry(gated, { true }, { emptyList() })
        val reserved = java.util.Collections.synchronizedList(mutableListOf<WalletEntry?>())
        val threads = List(2) { Thread { reserved += instance().reserve(false, WalletOrigin.CREATED, 1L) } }
        threads.forEach { it.start() }
        threads.forEach { it.join(5_000) }

        assertEquals(2, reserved.filterNotNull().size)
        assertEquals(reserved.filterNotNull().map { it.id }.toSet(), registry().incomplete().map { it.id }.toSet())
    }
}
