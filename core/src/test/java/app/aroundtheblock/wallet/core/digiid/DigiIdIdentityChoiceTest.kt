package app.aroundtheblock.wallet.core.digiid

import android.util.Log
import app.aroundtheblock.wallet.core.db.dao.DigiIdHistoryDao
import app.aroundtheblock.wallet.core.db.entity.DigiIdHistoryEntity
import app.aroundtheblock.wallet.core.model.DigiIdRequest
import app.aroundtheblock.wallet.core.model.DigiIdResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Which identity a login signs with, through DigiIdManager: Q4 A (Mark, 2026-11-10). The shared
 * m/0'/0/0 key exactly for the hosts on IdentityKeyPolicy's list, the per-site key for every other
 * host, whatever this phone's Digi-ID history holds. The history is written, never read.
 *
 * Through 4.1.4 there was a choice here: a restored wallet picked the identity for a site with no
 * successful login on record (4.1.2 row 16), and a site with a shared-key login on record kept the
 * shared key. Both are gone, so the same seed signs a site the same way on every device.
 */
class DigiIdIdentityChoiceTest {

    // The request digiid_core_kat holds the two identities of (sign_site_request).
    private val request = DigiIdRequest.parse("digiid://example.com/callback?x=abc123")!!
    private val dao = mockk<DigiIdHistoryDao>(relaxed = true)
    private val signedWith = mutableListOf<IdentityKeyKind>()
    private val recorded = mutableListOf<DigiIdHistoryEntity>()

    // Every request is answered here, without a network: the tests look at which key signed and what was written.
    private val answering = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body("".toResponseBody(null)).build()
    }.build()

    private fun manager() = DigiIdManager(
        answering, dao, mockk(relaxed = true),
        sign = { _, kind -> signedWith += kind; "Daddr|c2ln" },
    )

    @Before fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
        coEvery { dao.insert(capture(recorded)) } just runs
    }

    @After fun tearDown() = unmockkStatic(Log::class)

    @Test fun `a site that is not on the list signs with the per-site identity`() = runBlocking {
        assertTrue(manager().authenticate(request) is DigiIdResult.Success)
        assertEquals(listOf(IdentityKeyKind.PER_SITE), signedWith)
    }

    /**
     * The user impact Q4 A intends: a site that io.digibyte <= 4.0.76 signed in with the shared key
     * (every site, then) gets its per-site identity from this release on, so the site sees a new user.
     * The history saying so changes nothing, because nothing reads it: the login only writes its row.
     */
    @Test fun `a site with a shared-key login on record signs with its per-site identity`() = runBlocking {
        every { dao.getHistory() } returns flowOf(
            listOf(DigiIdHistoryEntity(domain = "example.com", callbackUrl = "https://example.com/callback",
                address = "Dshared", timestamp = 1L, success = true, derivation = "legacy")),
        )

        manager().authenticate(request)

        assertEquals(listOf(IdentityKeyKind.PER_SITE), signedWith)
        coVerify(exactly = 1) { dao.insert(any()) }
        confirmVerified(dao)
    }

    @Test fun `the listed hosts sign with the shared identity`() = runBlocking {
        for (uri in listOf(
            "digiid://digiscope.me/api/digiid/callback?x=n",
            "digiid://api.digiscope.me/api/auth/digiid/callback?x=n",
            // The parse lower-cases the host, as iOS's does; the list is read against that.
            "digiid://API.DigiScope.me/api/digiid/callback?x=n",
        )) {
            manager().authenticate(DigiIdRequest.parse(uri)!!)
        }
        assertEquals(List(3) { IdentityKeyKind.LEGACY }, signedWith)
    }

    @Test fun `a name under a listed host signs with its per-site identity`() = runBlocking {
        for (uri in listOf("digiid://hub.digiscope.me/callback?x=n", "digiid://x.api.digiscope.me/callback?x=n")) {
            manager().authenticate(DigiIdRequest.parse(uri)!!)
        }
        assertEquals(List(2) { IdentityKeyKind.PER_SITE }, signedWith)
    }

    /** The history's words for the two identities, which iOS records too (`DigiIdIdentity` raw values). */
    @Test fun `each login records which identity signed it`() = runBlocking {
        manager().authenticate(request)
        manager().authenticate(DigiIdRequest.parse("digiid://digiscope.me/api/digiid/callback?x=n")!!)
        assertEquals(listOf("site", "legacy"), recorded.map { it.derivation })
        assertEquals(listOf("example.com", "digiscope.me"), recorded.map { it.domain })
    }

    @Test fun `a locked wallet signs nothing and records nothing`() = runBlocking {
        val locked = DigiIdManager(answering, dao, mockk(relaxed = true), sign = { _, _ -> null })
        assertTrue(locked.authenticate(request) is DigiIdResult.Error)
        coVerify(exactly = 0) { dao.insert(any()) }
    }
}
