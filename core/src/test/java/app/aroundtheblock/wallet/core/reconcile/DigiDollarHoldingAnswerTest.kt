package app.aroundtheblock.wallet.core.reconcile

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * QUICKSTART-DD: what [DgbNodeClient.digiDollarHolding] makes of the DigiDollar endpoint's answer.
 *
 * Since 4.1.5 the restore's quick start shows and stores this balance, so the rule the scan
 * follows has to hold at the parser too: an answer that does not carry a balance is a lookup that
 * was not made (null), never a zero. Until then a missing `dd_balance_cents` defaulted to 0 and
 * `balance_derivable` was not read at all, and the zero only fed Recover funds.
 *
 * The live answer for an address never used, measured 2026-11-19 on api.digiscope.me, is
 * `"dd_balance_cents":0, ..., "balance_derivable":true, "tx_count":0, "unspent_count":0,
 * "transactions":[]`: the fields are present on an empty address, so requiring them does not turn
 * an ordinary empty wallet into "could not be checked". What the server sends when it cannot
 * derive a balance has not been observed; the client's reading of it is what is fixed here.
 */
class DigiDollarHoldingAnswerTest {

    @Before fun quietLog() {
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
    }

    @After fun restoreLog() = unmockkStatic(Log::class)

    /** The client's answer to [result] served as the endpoint's `result` object. */
    private fun holding(result: String, type: String = "dd_address"): DigiDollarHoldingResult? {
        val body = """{"type":"$type","result":$result}"""
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
        return runBlocking { DgbNodeClient(context, http).digiDollarHolding("DDexample") }
    }

    private val tx = """"transactions":[{"txid":"aa","height":1},{"txid":"bb","height":2}]"""

    @Test fun `the live answer's shape is read`() {
        val h = holding("""{"address":"DDexample","network":"mainnet","dd_balance_cents":200,"dd_balance_usd":2,"balance_derivable":true,"tx_count":2,"unspent_count":2,$tx}""")
        assertEquals(DigiDollarHoldingResult(cents = 200L, unspentCount = 2, txids = listOf("aa", "bb")), h)
    }

    @Test fun `an address never used is a checked zero`() {
        val h = holding("""{"dd_balance_cents":0,"dd_balance_usd":0,"balance_derivable":true,"tx_count":0,"unspent_count":0,"transactions":[]}""")
        assertEquals(DigiDollarHoldingResult(cents = 0L, unspentCount = 0, txids = emptyList()), h)
    }

    @Test fun `an answer without the derivable flag is taken at its balance`() {
        assertEquals(100L, holding("""{"dd_balance_cents":100,"unspent_count":1,$tx}""")?.cents)
    }

    @Test fun `a balance the server could not derive is not a zero`() {
        assertNull(holding("""{"dd_balance_cents":0,"balance_derivable":false,"unspent_count":0,"transactions":[]}"""))
        assertNull(holding("""{"balance_derivable":false,"transactions":[]}"""))
        // Not derivable is not derivable, whatever number sits beside it.
        assertNull(holding("""{"dd_balance_cents":100,"balance_derivable":false,$tx}"""))
        // A flag that is not the boolean true does not vouch for the balance.
        assertNull(holding("""{"dd_balance_cents":0,"balance_derivable":null,"transactions":[]}"""))
        assertNull(holding("""{"dd_balance_cents":0,"balance_derivable":"false","transactions":[]}"""))
    }

    @Test fun `an answer with no balance, or one that is not a number, is not a zero`() {
        assertNull(holding("""{"balance_derivable":true,"unspent_count":0,"transactions":[]}"""))
        assertNull(holding("""{"dd_balance_cents":null,"balance_derivable":true,"transactions":[]}"""))
        assertNull(holding("""{"dd_balance_cents":"unknown","balance_derivable":true,"transactions":[]}"""))
        assertNull(holding("""{"unspent_count":0,"transactions":[]}"""))
    }

    @Test fun `an answer of another type is not a holding`() {
        assertNull(holding("""{"dd_balance_cents":0,"balance_derivable":true,"transactions":[]}""", type = "address"))
    }
}
