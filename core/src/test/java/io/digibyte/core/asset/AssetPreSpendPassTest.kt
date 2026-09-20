package io.digibyte.core.asset

import android.util.Log
import io.digibyte.core.SpendPreflight
import io.digibyte.core.TxResult
import io.digibyte.core.db.dao.AssetMetadataDao
import io.digibyte.core.db.dao.UtxoDao
import io.digibyte.core.db.entity.UtxoEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The pass that runs before a spend is built from the plain-coin set: every owned output the
 * targeting rule names, in every transaction the wallet holds, is held out of that set by the
 * time the pass returns — and the pass returns normally only if it looked at all of them.
 *
 * It is the holding-out half of detection and nothing else: the same header decode, the same
 * input-unit resolution and the same [AssetTxQuantity.targetsOutput] rule, with no row written and
 * no network touched, so it can sit in front of a send. A transaction shown to carry no asset
 * payload is never looked at again (that cannot change); every asset transaction is looked at on
 * every pass, because its answer depends on rows and on the owned-address set, which do change.
 */
class AssetPreSpendPassTest {

    private val utxoDao = mockk<UtxoDao>(relaxed = true)
    private val held = mutableListOf<Pair<String, Int>>()
    private var registerThrows = false

    private val ours = "0014" + "11".repeat(20)
    private val theirs = "0014" + "22".repeat(20)

    /** One instruction: 10 units to output 0. Whatever else the inputs carried rides to the last output. */
    private val transferTenToOutputZero = "6a0644410115000a"

    private fun id(tag: String) = tag.padEnd(64, '0')
    private val plain1 = id("a1")
    private val plain2 = id("a2")
    private val plain3 = id("a3")
    private val transfer = id("b1")
    private val funding = id("f1")

    /** What the wallet holds, as the bridge would list it. Mutable so a test can let one arrive. */
    private val wallet = LinkedHashMap<String, Array<String>>()
    private val inputs = HashMap<String, Array<String>>()
    private val reads = mutableListOf<String>()

    private fun plainOutputs() = arrayOf("0|150000000|$ours", "1|4200000|$theirs")

    /** Marker to someone else, the payload, someone else's DGB, and OUR output last. */
    private fun transferOutputs() = arrayOf(
        "0|700|$theirs", "1|0|$transferTenToOutputZero", "2|9000|$theirs", "3|50000|$ours",
    )

    private fun manager(beforeSpend: (suspend () -> Unit)? = null) = AssetManager(
        utxoDao = utxoDao,
        transactionDao = mockk(relaxed = true),
        metadataDao = mockk<AssetMetadataDao>(relaxed = true),
        metadataService = mockk(relaxed = true),
        registerAssetOutpoint = { txid, vout ->
            if (registerThrows) throw IllegalStateException("the bridge did not answer")
            held += txid to vout
            true
        },
        beforeSpend = beforeSpend,
    )

    private suspend fun AssetManager.pass(
        txHashes: () -> Array<out String?>? = { wallet.keys.toTypedArray() },
        outputsOf: (String) -> Array<out String?>? = { reads += it; wallet[it] },
    ): Int = holdAssetOutputsBeforeSpendImpl(
        txHashes = txHashes,
        outputsOf = outputsOf,
        inputsOf = { inputs[it] },
        ownedScriptHexes = { setOf(ours) },
    )

    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>(), any()) } returns 0
        coEvery { utxoDao.getAssetUtxoAt(any(), any()) } returns null

        wallet[plain1] = plainOutputs()
        wallet[plain2] = plainOutputs()
        wallet[transfer] = transferOutputs()
        inputs[transfer] = arrayOf("$funding|1")
    }

    @After fun tearDown() = unmockkStatic(Log::class)

    @Test fun `the vector is a transfer of ten units to output zero`() {
        val script = ByteArray(transferTenToOutputZero.length / 2) {
            transferTenToOutputZero.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
        val header = DigiAssetDecoder().decode(script)
        assertNotNull("the payload no longer decodes — every test below would be blind", header)
        assertEquals(listOf(0 to 10L), header!!.transferInstructions.map { it.outputIndex to it.amount })
    }

    /** Input units unknown, so a remainder cannot be ruled out: our last output is held out. */
    @Test fun `an owned output the targeting rule names is held out by the time the pass returns`() = runTest {
        manager().pass()

        assertEquals(listOf(transfer to 3), held)
    }

    /** The inputs are known to carry exactly what the instruction hands out: nothing rides to the
     *  last output, so it is ordinary DGB change and stays spendable. */
    @Test fun `an owned output nothing targets stays in the plain-coin set`() = runTest {
        coEvery { utxoDao.getAssetUtxoAt(funding, 1) } returns UtxoEntity(
            txid = funding, vout = 1, scriptPubKey = ByteArray(0), satoshis = 700L, blockHeight = 1L,
            isAsset = true, assetId = "La", assetQuantity = 10L,
        )

        manager().pass()

        assertEquals(emptyList<Pair<String, Int>>(), held)
        assertTrue("the transfer was never looked at — the test is blind", transfer in reads)
    }

    /** Output 0 is targeted but is not ours; holding it out would be meaningless. */
    @Test fun `an output that is not ours is not registered`() = runTest {
        manager().pass()

        assertTrue(held.none { it.second == 0 })
        assertTrue("nothing was registered at all — the test is blind", held.isNotEmpty())
    }

    // ── It cannot report complete without having looked ───────────────────

    /** Each way the pass can stop says something different, so each test pins its own branch. */
    private fun assertStoppedWith(message: String, thrown: Throwable?) {
        assertTrue("expected the pass to fail, got $thrown", thrown is IllegalStateException)
        assertEquals(message, thrown!!.message)
    }

    @Test fun `a pass that cannot list the wallet's transactions does not complete`() = runTest {
        val thrown = runCatching { manager().pass(txHashes = { null }) }.exceptionOrNull()

        assertStoppedWith("the wallet's transactions could not be listed", thrown)
    }

    @Test fun `a listed transaction with no id fails the pass`() = runTest {
        val thrown = runCatching {
            manager().pass(txHashes = { arrayOf(plain1, null) })
        }.exceptionOrNull()

        assertStoppedWith("a listed transaction has no id", thrown)
    }

    @Test fun `a listed transaction that cannot be read fails the pass`() = runTest {
        val thrown = runCatching {
            manager().pass(outputsOf = { if (it == transfer) null else wallet[it] })
        }.exceptionOrNull()

        assertStoppedWith("a listed transaction could not be read", thrown)
    }

    @Test fun `a listed transaction whose inputs cannot be read fails the pass`() = runTest {
        inputs.remove(transfer)

        val thrown = runCatching { manager().pass() }.exceptionOrNull()

        assertStoppedWith("a listed transaction's inputs could not be read", thrown)
        assertEquals(emptyList<Pair<String, Int>>(), held)
    }

    @Test fun `an output line that cannot be parsed fails the pass`() = runTest {
        wallet[plain2] = arrayOf("0|150000000|$ours", "not an output line")

        val thrown = runCatching { manager().pass() }.exceptionOrNull()

        assertStoppedWith("an output line could not be parsed", thrown)
    }

    @Test fun `a payload script that is not hex fails the pass`() = runTest {
        wallet[plain2] = arrayOf("0|150000000|$ours", "1|0|6azz")

        val thrown = runCatching { manager().pass() }.exceptionOrNull()

        assertStoppedWith("an output script is not hex", thrown)
    }

    /** The index and the script decide what is held out; the amount plays no part, so the pass
     *  does not judge it. The bridge prints the amount unsigned, and a value past the signed
     *  range is still a line the pass can read. */
    @Test fun `an amount the pass does not use is not a reason to stop`() = runTest {
        wallet[transfer] = arrayOf(
            "0|700|$theirs", "1|0|$transferTenToOutputZero",
            "2|18446744073709551615|$theirs", "3|9223372036854775808|$ours",
        )

        val thrown = runCatching { manager().pass() }.exceptionOrNull()

        assertNull("the pass stopped on a field it does not use: $thrown", thrown)
        assertEquals(listOf(transfer to 3), held)
    }

    /** The row says the first input carried exactly what the instruction hands out. The second
     *  input line cannot be read, so what the inputs carried is unknown — not "nothing more" —
     *  and our last output is held out. */
    @Test fun `an input line that cannot be read makes the inputs unknown`() = runTest {
        coEvery { utxoDao.getAssetUtxoAt(funding, 1) } returns UtxoEntity(
            txid = funding, vout = 1, scriptPubKey = ByteArray(0), satoshis = 700L, blockHeight = 1L,
            isAsset = true, assetId = "La", assetQuantity = 10L,
        )
        inputs[transfer] = arrayOf("$funding|1", "$funding|4294967295")

        manager().pass()

        assertEquals(listOf(transfer to 3), held)
    }

    /** A set that shows something new at every listing never gives the pass a listing it can end
     *  on. It gives up — as a pass that did not finish — long before the set stops moving. */
    @Test fun `a transaction set that does not settle fails the pass`() = runTest {
        var listings = 0

        val thrown = runCatching {
            manager().pass(txHashes = {
                if (++listings <= 50) wallet[id("d%04d".format(listings))] = plainOutputs()
                wallet.keys.toTypedArray()
            })
        }.exceptionOrNull()

        assertStoppedWith("the wallet's transaction set did not settle", thrown)
        assertTrue("the pass went on listing: $listings", listings < 50)
    }

    @Test fun `a registration that throws fails the pass`() = runTest {
        registerThrows = true

        val thrown = runCatching { manager().pass() }.exceptionOrNull()

        assertStoppedWith("the bridge did not answer", thrown)
    }

    @Test fun `a row lookup that throws fails the pass`() = runTest {
        coEvery { utxoDao.getAssetUtxoAt(any(), any()) } throws IOException("the database did not answer")

        val thrown = runCatching { manager().pass() }.exceptionOrNull()

        assertTrue("expected the pass to fail, got $thrown", thrown is IOException)
        assertEquals("the database did not answer", thrown!!.message)
        assertEquals(emptyList<Pair<String, Int>>(), held)
    }

    @Test fun `a cancellation inside the pass reaches the caller`() = runTest {
        val thrown = runCatching {
            manager().pass(outputsOf = { throw CancellationException("the screen went away") })
        }.exceptionOrNull()

        assertTrue("a cancellation must reach the caller, got $thrown", thrown is CancellationException)
    }

    /** The pass checks for its caller between transactions: once the caller is gone it reads no more. */
    @Test fun `a pass whose caller is cancelled reads no further`() = runTest {
        repeat(200) { wallet[id("c%04d".format(it))] = plainOutputs() }
        val mgr = manager()
        var readsSeen = 0
        lateinit var job: Job

        job = launch {
            mgr.pass(outputsOf = {
                if (++readsSeen == 10) job.cancel()
                wallet[it]
            })
        }
        job.join()

        assertTrue("the pass was not cancelled", job.isCancelled)
        assertEquals("the pass kept reading after its caller was cancelled", 10, readsSeen)
    }

    /** One pass at a time. A second caller waits outside until the first has returned, and then
     *  reads only what any later pass reads. */
    @Test fun `a second pass waits for the first and finds the work done`() = runBlocking<Unit> {
        val mgr = manager()
        val firstIsInside = CountDownLatch(1)
        val letFirstFinish = CountDownLatch(1)
        val secondListings = AtomicInteger(0)
        val secondReads: MutableList<String> = Collections.synchronizedList(mutableListOf())

        val first = async(Dispatchers.Default) {
            mgr.pass(outputsOf = {
                firstIsInside.countDown()
                letFirstFinish.await(10, TimeUnit.SECONDS)
                wallet[it]
            })
        }
        assertTrue("the first pass never started", firstIsInside.await(10, TimeUnit.SECONDS))
        val second = async(Dispatchers.Default) {
            mgr.pass(
                txHashes = { secondListings.incrementAndGet(); wallet.keys.toTypedArray() },
                outputsOf = { secondReads += it; wallet[it] },
            )
        }
        delay(300)                              // every chance to get in while the first is inside
        val listedWhileFirstWasInside = secondListings.get()
        letFirstFinish.countDown()
        first.await()
        second.await()

        assertEquals("a second pass ran while the first was still inside", 0, listedWhileFirstWasInside)
        assertTrue("the second pass never ran — the test is blind", secondListings.get() > 0)
        assertFalse("the second pass read again what the first had settled", plain1 in secondReads || plain2 in secondReads)
        assertTrue("the second pass skipped the asset transaction", transfer in secondReads)
    }

    // ── What a later pass costs, and what it never skips ──────────────────

    @Test fun `a later pass reads only what is new and what carries an asset`() = runTest {
        val mgr = manager()
        mgr.pass()
        assertEquals(setOf(plain1, plain2, transfer, funding), reads.toSet())

        reads.clear(); held.clear()
        wallet[plain3] = plainOutputs()
        mgr.pass()

        // plain1 / plain2 were shown to carry no asset payload; that cannot change.
        assertEquals(setOf(transfer, funding, plain3), reads.toSet())
        // The asset transaction is looked at again and held out again: registering is idempotent,
        // and the answer depends on rows and owned addresses, which move.
        assertEquals(listOf(transfer to 3), held)
    }

    @Test fun `a transaction that arrived since the last pass is looked at by the next one`() = runTest {
        val mgr = manager()
        mgr.pass()
        held.clear()

        val arrived = id("b2")
        wallet[arrived] = transferOutputs()
        inputs[arrived] = arrayOf("${id("f2")}|0")
        mgr.pass()

        assertTrue("the arrival was not held out: $held", (arrived to 3) in held)
    }

    /** The pass returns only once a listing shows nothing it has not looked at. */
    @Test fun `a transaction that arrives while the pass runs is looked at before it returns`() = runTest {
        val arrived = id("b3")
        var listings = 0

        manager().pass(txHashes = {
            if (++listings == 2) {
                wallet[arrived] = transferOutputs()
                inputs[arrived] = arrayOf("${id("f3")}|0")
            }
            wallet.keys.toTypedArray()
        })

        assertTrue("the arrival was not held out: $held", (arrived to 3) in held)
        assertTrue("the pass stopped after one listing", listings >= 3)
    }

    // ── Wiring ────────────────────────────────────────────────────────────

    @Test fun `constructing the asset layer installs its pass for every spend`() {
        SpendPreflight.clear()
        assertTrue(!SpendPreflight.isInstalled)

        manager()

        assertTrue(SpendPreflight.isInstalled)
        SpendPreflight.clear()
    }

    /** The asset send pays its network fee from the plain-coin set, so it waits for the pass too. */
    @Test fun `an asset send reads no coin when the pass could not finish`() = runTest {
        val events = mutableListOf<String>()
        val store = InMemoryProvenanceStore()
        store.putAssets(
            listOf("iss"),
            ResolvedAssetFacts(
                assetId = "LaFree", totalSupply = 5, divisibility = 0, metadataCid = null,
                issuanceOpcode = 1, issuanceLocked = true,
            ),
        )
        val metaDao = mockk<AssetMetadataDao>(relaxed = true)
        coEvery { metaDao.rulesJsonFor("LaFree") } returns "{}"
        val mgr = AssetManager(
            utxoDao = utxoDao, transactionDao = mockk(relaxed = true), metadataDao = metaDao,
            metadataService = mockk(relaxed = true), provenanceStore = store,
            beforeSpend = { events += "detect"; throw IOException("the pass did not finish") },
        )

        // Anything past the pass goes into the native bridge, which has no library on the JVM;
        // the result is taken as a value so the first thing to fail here is an assertion.
        val result = runCatching { mgr.sendAsset("LaFree", 1L, "dgb1qanything", 100_000L) }

        assertEquals(listOf("detect"), events)
        assertEquals(TxResult.Error(SpendPreflight.NOT_SENT), result.getOrNull())
        coVerify(exactly = 0) { utxoDao.getAssetUtxosByIdNow(any()) }
    }

    @Test fun `a cancelled pass cancels the asset send`() = runTest {
        val store = InMemoryProvenanceStore()
        store.putAssets(
            listOf("iss"),
            ResolvedAssetFacts(
                assetId = "LaFree", totalSupply = 5, divisibility = 0, metadataCid = null,
                issuanceOpcode = 1, issuanceLocked = true,
            ),
        )
        val metaDao = mockk<AssetMetadataDao>(relaxed = true)
        coEvery { metaDao.rulesJsonFor("LaFree") } returns "{}"
        val mgr = AssetManager(
            utxoDao = utxoDao, transactionDao = mockk(relaxed = true), metadataDao = metaDao,
            metadataService = mockk(relaxed = true), provenanceStore = store,
            beforeSpend = { throw CancellationException("the screen went away") },
        )

        val thrown = runCatching { mgr.sendAsset("LaFree", 1L, "dgb1qanything", 100_000L) }.exceptionOrNull()

        assertTrue("a cancellation must reach the caller, got $thrown", thrown is CancellationException)
    }
}
