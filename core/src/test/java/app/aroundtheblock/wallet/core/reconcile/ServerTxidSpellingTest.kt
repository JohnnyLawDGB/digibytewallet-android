package app.aroundtheblock.wallet.core.reconcile

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import app.aroundtheblock.wallet.core.recovery.UtxoDedup
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * UTXO-DEDUP-CASE: a transaction id has one spelling inside the wallet, whatever the server wrote.
 *
 * A txid is 32 bytes; its hex has two spellings per letter. The wallet keeps txids as text and
 * compares them as text (map keys, set members, `==`), so the same coin listed under two spellings
 * was two coins to the recovery scan: counted twice in the balance it quotes, and handed to the
 * sweep as two inputs naming one outpoint, which refuses the whole profile. The core's rule
 * (BRRecoveryUtxo.h) compares the bytes; this is the Kotlin side agreeing with it.
 *
 * The rule is kept at the boundary: every txid [DgbNodeClient] reads from a response is put in the
 * wallet's spelling there ([canonicalTxid]), so nothing downstream has to remember to fold case.
 */
class ServerTxidSpellingTest {

    @Before fun quietLog() {
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
    }

    @After fun restoreLog() = unmockkStatic(Log::class)

    /** A client whose every request is answered with [body]. */
    private fun client(body: String): DgbNodeClient {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getString(any(), any()) } returns null
        every { prefs.getBoolean(any(), any()) } returns false
        val context = mockk<Context>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { context.applicationContext } returns context
        return DgbNodeClient(context, http)
    }

    private val lower = "6bd35c442896c27f0a1b2c3d4e5f60718293a4b5c6d7e8f9aabbccddeeff0011"
    private val upper = lower.uppercase()
    private val mixed = lower.take(32) + lower.drop(32).uppercase()
    private val otherTx = "ac3cbb77c252e78d" + "0".repeat(48)

    private fun row(txid: String, vout: Int, sats: Long, height: Long) =
        """{"txid":"$txid","vout":$vout,"amountSatoshi":$sats,"address":"D7Vxdup","height":$height,"scriptPubKey":"76a91488ac"}"""

    /** One coin listed three times, each in another spelling, beside one other coin. */
    private val reconcileBody = """{"height":24106700,"utxos":[
        ${row(lower, 0, 6_000L, 0L)},
        ${row(upper, 0, 6_000L, 24_106_618L)},
        ${row(mixed, 0, 6_000L, 24_106_618L)},
        ${row(otherTx, 1, 500_000_000L, 24_106_000L)}
      ],"rawTxs":{
        "$upper":{"hex":"0100","height":24106618,"time":1784416468},
        "$otherTx":{"hex":"0200","height":24106000,"time":1784410000}
      }}"""

    private fun reconcile(): ReconcileResult =
        runBlocking { client(reconcileBody).reconcileAddresses(listOf("D7Vxdup")) }!!

    // ── the recovery scan's de-duplication ────────────────────────────────────────────────────

    @Test fun `one coin a server lists in three spellings is one coin to the recovery scan`() {
        val coins = UtxoDedup.byOutpoint(reconcile().utxos)

        assertEquals("two coins: the repeated one once, and the other", 2, coins.size)
        assertEquals("the balance counts the repeated coin once", 500_006_000L, coins.sumOf { it.amountSatoshi })
        // The confirmed copy is the one kept, as for a repeat in one spelling.
        assertEquals(24_106_618L, coins.first { it.vout == 0 }.blockHeight)
    }

    @Test fun `the de-duplication itself reads two spellings as one outpoint`() {
        // Rows that did not come through DgbNodeClient (another UtxoSource): the rule holds on its own.
        fun utxo(txid: String, height: Long) = UtxoEntry(txid, 0, 6_000L, "D7Vxdup", height, "76a91488ac")
        val coins = UtxoDedup.byOutpoint(listOf(utxo(lower, 0L), utxo(upper, 24_106_618L), utxo(mixed, 0L)))

        assertEquals(1, coins.size)
        assertEquals(24_106_618L, coins.single().blockHeight)
        // The same txid at another index is another coin, in any spelling.
        assertEquals(2, UtxoDedup.byOutpoint(listOf(utxo(lower, 1L), utxo(upper, 1L).copy(vout = 1))).size)
    }

    @Test fun `the reconcile's distinct outpoints agree with the recovery scan's`() {
        val rows = reconcile().utxos
        assertEquals(UtxoDedup.byOutpoint(rows).size, distinctOutpoints(rows).size)
    }

    // ── the boundary: one spelling out of DgbNodeClient ───────────────────────────────────────

    @Test fun `every txid in a reconcile answer leaves the client in lower case`() {
        val r = reconcile()

        assertEquals(listOf(lower, lower, lower, otherTx), r.utxos.map { it.txid })
        assertEquals(setOf(lower, otherTx), r.rawTxs.keys)
    }

    @Test fun `a coin finds its parent transaction by its txid, whatever spelling either had`() {
        val r = reconcile()
        // Read as the callers read it: a plain map lookup by the row's txid.
        for (u in r.utxos) assertNotNull("${u.txid.take(8)}: no parent", r.rawTxs[u.txid])
        assertEquals("0100", r.rawTxs[r.utxos.first().txid]?.hex)
    }

    @Test fun `a parent listed under two spellings is one entry, the first kept`() {
        // org.json keeps no key order on the JVM, so the two entries carry the same bytes: what is
        // asked is that there is one entry, under the lower-case key.
        val body = """{"height":1,"utxos":[${row(upper, 0, 6_000L, 5L)}],"rawTxs":{
            "$upper":{"hex":"0100","height":5,"time":7},
            "$lower":{"hex":"0100","height":5,"time":7}}}"""
        val r = runBlocking { client(body).reconcileAddresses(listOf("D7Vxdup")) }!!

        assertEquals(setOf(lower), r.rawTxs.keys)
        assertEquals("0100", r.rawTxs[lower]?.hex)
    }

    @Test fun `address history txids are in lower case, so a known transaction is not planned again`() {
        val history = parseAddressHistory(JSONObject(
            """{"transactions":[{"txid":"$upper","height":10},{"txid":"$mixed","height":12},{"txid":"$otherTx","height":3}]}"""
        ))

        assertEquals(listOf(lower, lower, otherTx), history.map { it.txid })
        // The wallet's own list is lower case (the core writes it).
        assertEquals(listOf(otherTx), planHistoryImport(listOf(history), knownTxids = setOf(lower)).map { it.txid })
        // Unknown to the wallet: planned once, at the highest height either spelling gave.
        assertEquals(listOf(AddressTx(lower, 12L)), planHistoryImport(listOf(history), knownTxids = setOf(otherTx)))
    }

    @Test fun `a DigiDollar holding's txids are in lower case`() {
        val body = """{"type":"dd_address","result":{"dd_balance_cents":200,"balance_derivable":true,"unspent_count":2,
            "transactions":[{"txid":"$upper","height":1},{"txid":"$mixed","height":2}]}}"""
        val h = runBlocking { client(body).digiDollarHolding("DDexample") }

        assertEquals(listOf(lower, lower), h?.txids)
    }

    @Test fun `the normaliser folds the ASCII letters and nothing else`() {
        assertEquals(lower, canonicalTxid(upper))
        assertEquals(lower, canonicalTxid(mixed))
        assertEquals(lower, canonicalTxid(lower))
        assertEquals("", canonicalTxid(""))
        // Not hex: left to be refused where a txid is decoded, and never folded onto a hex digit.
        // U+212A (the Kelvin sign) lower-cases to an ASCII k under the Unicode rules.
        assertEquals("\u212A0 z", canonicalTxid("\u212A0 Z"))
        assertEquals("ı\u0130", canonicalTxid("ı\u0130"))
    }

    // ── the gate: no response txid is read past the boundary ──────────────────────────────────

    private fun repoDir(rel: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val f = File(dir, rel)
            if (f.isDirectory) return f
            dir = dir.parentFile
        }
        error("$rel not found above ${System.getProperty("user.dir")}")
    }

    @Test fun `every txid read from a JSON answer goes through the one normaliser`() {
        // A line that reads a "txid" member of a JSON answer must hand it to canonicalTxid on that
        // line. The one exception compares the member with the txid that was asked for, ignoring
        // case, and keeps nothing of it.
        val allowed = setOf(
            """if (!json.optString("txid").equals(txid, ignoreCase = true)) return StackLookup.Unavailable""",
        )
        val read = Regex("""(getString|optString)\(\s*"(txid|txId|tx_hash|txhash|tx_id|prev_txid)"""")
        val roots = listOf("core/src/main/java", "app/src/main/java").map(::repoDir)
        val offenders = roots.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
                f.readLines().mapIndexedNotNull { i, line ->
                    val code = line.trim()
                    if (!read.containsMatchIn(code) || code in allowed || "canonicalTxid(" in code) null
                    else "${f.relativeTo(root)}:${i + 1}: $code"
                }
            }
        }
        // AppNavigation reads a navigation argument named txid from a Bundle, not from a server.
        val fromServer = offenders.filterNot { "backStackEntry.arguments" in it }

        assertTrue("txids read from a server answer without canonicalTxid:\n" + fromServer.joinToString("\n"), fromServer.isEmpty())
        // The gate reads something: the client's own reads are found by the same pattern.
        val clientSource = File(roots[0], "app/aroundtheblock/wallet/core/reconcile/DgbNodeClient.kt").readText()
        assertTrue("the gate's pattern no longer finds DgbNodeClient's txid reads", read.findAll(clientSource).count() >= 3)
    }
}
