package app.aroundtheblock.wallet.core.recovery

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import app.aroundtheblock.wallet.core.reconcile.DgbNodeClient
import app.aroundtheblock.wallet.core.reconcile.DigiDollarHoldingResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * DD-LOCATOR-BIND: every raw transaction the DigiDollar locator fetches is bound to the
 * transaction id it asked for. The fetched bytes' own id equals the requested txid, or the answer
 * is "could not be read" (null), which the scan never reads as a zero or as "no such output".
 *
 * The real [DgbNodeClient] runs here over a scripted HTTP answer. The id function is the JVM
 * model of the production one ([RawTxBinding.witnessFreeTransactionId]); the native output and
 * input readers are replaced by tables keyed on the bytes, so each test states what a
 * transaction's bytes say independently of the id it was requested under.
 */
class LocatorRawFetchBindingTest {

    // The Bitcoin genesis coinbase: a real serialization without witness data, id known.
    private val txA =
        "01000000010000000000000000000000000000000000000000000000000000000000000000ffffffff4d04ffff001d" +
            "0104455468652054696d65732030332f4a616e2f32303039204368616e63656c6c6f72206f6e206272696e6b206f" +
            "66207365636f6e64206261696c6f757420666f722062616e6b73ffffffff0100f2052a01000000434104678afdb0" +
            "fe5548271967f1a67130b7105cd6a828e03909a67962e0ea1f61deb649f6bc3f4cef38c4f35504e51ec112de5c38" +
            "4df7ba0b8d578a4c702b6bf11d5fac00000000"
    private val idA = "4a5e1e4baab89f3a32518a88c31bc87f618f76673e2cc77ab2127b7afdeda33b"

    // Another transaction: the same bytes under a different nVersion, so a different id.
    private val txB = "70070000" + txA.substring(8)
    private val idB = RawTxBinding.witnessFreeTransactionId(bytes(txB))!!

    // Two more transactions, again the same bytes under other nVersions.
    private val txC = "02000000" + txA.substring(8)
    private val idC = RawTxBinding.witnessFreeTransactionId(bytes(txC))!!
    private val txD = "03000000" + txA.substring(8)
    private val idD = RawTxBinding.witnessFreeTransactionId(bytes(txD))!!

    private val xq = "076cc826d55b011a868ca89317d79db554ab248c9736b6c34a89f4e6ba1159e9"
    private val address = DigiDollarAddress("DD1JAMfBqU9mVnwqZE5SJfJCYT7TKCBnpGeMQQgdDThvbcKbDaTN", xq, 0, 0)

    /** What each transaction's bytes state, whatever id they were served under. */
    private val outputsByTx = mapOf(
        txA to arrayOf("0|5000000000|76a914${"11".repeat(20)}88ac"),
        txB to arrayOf("0|0|5120$xq", "1|0|6a02444401020164"),
        txC to arrayOf("0|4999990000|76a914${"22".repeat(20)}88ac"),
        // Pays the address's key beside a DigiAsset carrier, with no DigiDollar OP_RETURN.
        txD to arrayOf("0|0|5120$xq", "1|0|6a0644410115000a"),
    )
    private val inputsByTx = mapOf(
        txA to arrayOf("${"00".repeat(32)}|-1"),
        txB to arrayOf("${"ee".repeat(32)}|3"),
        // Spends output 0 of the second transaction.
        txC to arrayOf("$idB|0"),
        txD to arrayOf("${"dd".repeat(32)}|1"),
    )

    private val outputsA = listOf(DigiDollarHolding.Output(0, 5_000_000_000L, "76a914${"11".repeat(20)}88ac"))
    private val inputsA = listOf(DigiDollarHolding.PrevOut("00".repeat(32), -1))

    @Before fun quietLog() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
    }

    @After fun restoreLog() = unmockkStatic(Log::class)

    private fun bytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /**
     * A source over a server that answers a raw-transaction request for txid K with the body
     * `served[K]` (K in the case the request used); anything else is a 404.
     */
    private fun source(
        served: Map<String, String>,
        txidOf: (ByteArray) -> String? = RawTxBinding::witnessFreeTransactionId,
    ): ReconcileBackendUtxoSource {
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val asked = chain.request().url.pathSegments.last()
            val body = served.entries.firstOrNull { it.key.equals(asked, ignoreCase = true) }?.value
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (body == null) 404 else 200).message("scripted")
                .body((body ?: "{}").toResponseBody("application/json".toMediaType()))
                .build()
        }).build()
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getString(any(), any()) } returns null
        every { prefs.getBoolean(any(), any()) } returns false
        val context = mockk<Context>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { context.applicationContext } returns context
        return ReconcileBackendUtxoSource(
            nodeClient = DgbNodeClient(context, http),
            txidOf = txidOf,
            outputLines = { raw -> outputsByTx[hex(raw)] },
            inputLines = { raw -> inputsByTx[hex(raw)] },
        )
    }

    private fun answer(txHex: String) = """{"hex":"$txHex"}"""

    @Test fun `the id model is the one the network names the transaction by`() {
        assertEquals(idA, RawTxBinding.witnessFreeTransactionId(bytes(txA)))
        assertTrue("the second transaction is a different one", idB != idA)
    }

    // ---- a matching answer is accepted -----------------------------------------------------

    @Test fun `outputs are read from an answer that is the transaction asked for`() = runBlocking {
        assertEquals(outputsA, source(mapOf(idA to answer(txA))).fetchOutputs(idA))
    }

    @Test fun `inputs are read from an answer that is the transaction asked for`() = runBlocking {
        assertEquals(inputsA, source(mapOf(idA to answer(txA))).fetchInputs(idA))
    }

    @Test fun `the version is read from an answer that is the transaction asked for`() = runBlocking {
        assertEquals(1, source(mapOf(idA to answer(txA))).fetchVersion(idA))
        assertEquals(0x0770, source(mapOf(idB to answer(txB))).fetchVersion(idB))
    }

    @Test fun `a txid asked for in upper case still names its transaction`() = runBlocking {
        val s = source(mapOf(idA to answer(txA)))
        assertEquals(outputsA, s.fetchOutputs(idA.uppercase()))
        assertEquals(inputsA, s.fetchInputs(idA.uppercase()))
        assertEquals(1, s.fetchVersion(idA.uppercase()))
    }

    // ---- an answer for a different transaction is refused -----------------------------------

    @Test fun `outputs of a different transaction are not read as the one asked for`() = runBlocking {
        assertNull(source(mapOf(idA to answer(txB))).fetchOutputs(idA))
    }

    @Test fun `inputs of a different transaction are not read as the one asked for`() = runBlocking {
        assertNull(source(mapOf(idA to answer(txB))).fetchInputs(idA))
    }

    @Test fun `the version of a different transaction is not read as the one asked for`() = runBlocking {
        assertNull(source(mapOf(idA to answer(txB))).fetchVersion(idA))
    }

    @Test fun `bytes with no id are unreadable for every fetch`() = runBlocking {
        val s = source(mapOf(idA to answer(txA)), txidOf = { null })
        assertNull(s.fetchOutputs(idA))
        assertNull(s.fetchInputs(idA))
        assertNull(s.fetchVersion(idA))
    }

    @Test fun `an id function that throws leaves every fetch unreadable`() = runBlocking {
        val s = source(mapOf(idA to answer(txA)), txidOf = { error("parser failure") })
        assertNull(s.fetchOutputs(idA))
        assertNull(s.fetchInputs(idA))
        assertNull(s.fetchVersion(idA))
    }

    // ---- through the scan: unreadable, never located and never a zero ------------------------

    @Test fun `a listed transaction answered with another one locates nothing and keeps the balance`() = runBlocking {
        // The other transaction's bytes pay this address's key; the listed one's do not.
        val s = source(mapOf(idA to answer(txB)))
        val r = DigiDollarScan.assemble(
            addresses = listOf(address),
            holdingFor = { DigiDollarHoldingResult(100, 1, listOf(idA)) },
            outputsFor = { s.fetchOutputs(it) },
            inputsFor = { s.fetchInputs(it) },
            versionFor = { s.fetchVersion(it) },
        )
        assertEquals("no outpoint is located in bytes that are not the listed transaction", emptyList<Any>(), r.holdings)
        assertEquals("the balance is still reported, not zero", 100L, r.cents)
        assertEquals("and reported as not movable", 100L, r.unlocatableCents)
    }

    @Test fun `the same transaction answered as itself locates its outpoint`() = runBlocking {
        val s = source(mapOf(idB to answer(txB)))
        val r = DigiDollarScan.assemble(
            addresses = listOf(address),
            holdingFor = { DigiDollarHoldingResult(100, 1, listOf(idB)) },
            outputsFor = { s.fetchOutputs(it) },
            inputsFor = { s.fetchInputs(it) },
            versionFor = { s.fetchVersion(it) },
        )
        assertEquals(listOf(DigiDollarScan.Holding(address, idB, 0, "5120$xq")), r.holdings)
        assertEquals(0L, r.unlocatableCents)
    }

    @Test fun `inputs answered with another transaction prove nothing spent`() = runBlocking {
        // Outputs come from a server that answers each request with its own transaction. The
        // inputs request for the first listed transaction is answered with a transaction that
        // spends the outpoint located in the second: not the one asked for, so it is unreadable.
        val outputs = source(mapOf(idA to answer(txA), idB to answer(txB)))
        val inputs = source(mapOf(idA to answer(txC), idB to answer(txB)))
        val r = DigiDollarScan.assemble(
            addresses = listOf(address),
            holdingFor = { DigiDollarHoldingResult(100, 1, listOf(idA, idB)) },
            outputsFor = { outputs.fetchOutputs(it) },
            inputsFor = { inputs.fetchInputs(it) },
            versionFor = { outputs.fetchVersion(it) },
        )
        assertEquals(listOf(DigiDollarScan.Holding(address, idB, 0, "5120$xq")), r.holdings)
        assertEquals(0L, r.unlocatableCents)
    }

    @Test fun `a version answered with another transaction does not change the family decision`() = runBlocking {
        // The listed transaction is not marked DigiDollar by its own nVersion. Its version request
        // is answered with a transaction whose nVersion carries the marker: unreadable, so unknown.
        val outputs = source(mapOf(idD to answer(txD)))
        val versions = source(mapOf(idD to answer(txB)))
        assertEquals("the swapped answer would carry the marker", 0x0770, source(mapOf(idB to answer(txB))).fetchVersion(idB))
        val r = DigiDollarScan.assemble(
            addresses = listOf(address),
            holdingFor = { DigiDollarHoldingResult(100, 1, listOf(idD)) },
            outputsFor = { outputs.fetchOutputs(it) },
            inputsFor = { outputs.fetchInputs(it) },
            versionFor = { versions.fetchVersion(it) },
        )
        assertEquals(listOf(DigiDollarScan.Holding(address, idD, 0, "5120$xq")), r.holdings)
        assertEquals(0L, r.unlocatableCents)
    }

    // ---- one spelling of a listed txid ------------------------------------------------------

    @Test fun `a receive listed in upper case whose output a listed transaction spends locates nothing`() = runBlocking {
        val s = source(mapOf(idB to answer(txB), idC to answer(txC)))
        val r = DigiDollarScan.assemble(
            addresses = listOf(address),
            holdingFor = { DigiDollarHoldingResult(100, 0, listOf(idB.uppercase(), idC)) },
            outputsFor = { s.fetchOutputs(it) },
            inputsFor = { s.fetchInputs(it) },
            versionFor = { s.fetchVersion(it) },
        )
        assertEquals("the spent outpoint is not offered", emptyList<Any>(), r.holdings)
        assertEquals(100L, r.cents)
        assertEquals(100L, r.unlocatableCents)
    }

    @Test fun `a transaction listed in both letter cases is one transaction`() = runBlocking {
        val s = source(mapOf(idB to answer(txB)))
        val asked = mutableListOf<String>()
        val r = DigiDollarScan.assemble(
            addresses = listOf(address),
            holdingFor = { DigiDollarHoldingResult(100, 0, listOf(idB, idB.uppercase())) },
            outputsFor = { asked += it; s.fetchOutputs(it) },
            inputsFor = { s.fetchInputs(it) },
            versionFor = { s.fetchVersion(it) },
        )
        assertEquals("its outpoint is located once", listOf(DigiDollarScan.Holding(address, idB, 0, "5120$xq")), r.holdings)
        assertEquals("and read once, by its lower-case id", listOf(idB), asked)
    }

    @Test fun `a transaction listed in both letter cases does not make up the unspent count`() = runBlocking {
        val s = source(mapOf(idB to answer(txB)))
        val r = DigiDollarScan.assemble(
            addresses = listOf(address),
            holdingFor = { DigiDollarHoldingResult(200, 2, listOf(idB, idB.uppercase())) },
            outputsFor = { s.fetchOutputs(it) },
            inputsFor = { s.fetchInputs(it) },
            versionFor = { s.fetchVersion(it) },
        )
        assertEquals(emptyList<Any>(), r.holdings)
        assertEquals(200L, r.unlocatableCents)
    }

    @Test fun `an input reported in upper case still marks its outpoint spent`() = runBlocking {
        val r = DigiDollarScan.assemble(
            addresses = listOf(address),
            holdingFor = { DigiDollarHoldingResult(100, 0, listOf(idB, idC)) },
            outputsFor = { if (it == idB) listOf(DigiDollarHolding.Output(0, 0, "5120$xq")) else emptyList() },
            inputsFor = { if (it == idC) listOf(DigiDollarHolding.PrevOut(idB.uppercase(), 0)) else emptyList() },
        )
        assertEquals(emptyList<Any>(), r.holdings)
        assertEquals(100L, r.unlocatableCents)
    }
}
