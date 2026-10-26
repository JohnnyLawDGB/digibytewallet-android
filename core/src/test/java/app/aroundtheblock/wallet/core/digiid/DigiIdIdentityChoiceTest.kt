package app.aroundtheblock.wallet.core.digiid

import android.util.Log
import app.aroundtheblock.wallet.core.db.dao.DigiIdHistoryDao
import app.aroundtheblock.wallet.core.model.DigiIdRequest
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * 4.1.2 row 16: a restored wallet has no Digi-ID history, so for a site with no successful login
 * on record the user picks the identity — per-site (the original DigiByte app) or the shared
 * m/0'/0/0 key (io.digibyte 3.x–4.0.76) — and the login signs with exactly that one. Where the
 * history already decides, the pick changes nothing.
 */
class DigiIdIdentityChoiceTest {

    private val request = DigiIdRequest.parse("digiid://example.com/callback?x=abc123")!!
    private val dao = mockk<DigiIdHistoryDao>(relaxed = true)
    private val signedWith = mutableListOf<IdentityKeyKind>()

    // Every request fails before it leaves: the tests only look at which key was used.
    private val offline = OkHttpClient.Builder().addInterceptor { throw IOException("offline") }.build()

    private fun manager(restored: Boolean = true) = DigiIdManager(
        offline, dao, mockk(relaxed = true),
        sign = { _, kind -> signedWith += kind; "Daddr|c2ln" },
        address = { _, kind -> if (kind == IdentityKeyKind.PER_SITE) "DperSite" else "Dshared" },
        walletMayHaveHistoryElsewhere = { restored },
    )

    @Before fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
    }

    @After fun tearDown() = unmockkStatic(Log::class)

    private fun history(any: Int, legacy: Int) {
        coEvery { dao.countSuccessful(any()) } returns any
        coEvery { dao.countSuccessfulLegacy(any()) } returns legacy
    }

    @Test fun `no history offers both identities by address`() = runBlocking {
        history(any = 0, legacy = 0)
        assertEquals(DigiIdManager.IdentityChoice("DperSite", "Dshared"), manager().identityChoice(request))
    }

    @Test fun `the shared identity picked is the one signed with`() = runBlocking {
        history(any = 0, legacy = 0)
        manager().authenticate(request, IdentityKeyKind.LEGACY)
        assertEquals(listOf(IdentityKeyKind.LEGACY), signedWith)
    }

    @Test fun `no pick signs with the per-site identity`() = runBlocking {
        history(any = 0, legacy = 0)
        manager().authenticate(request)
        assertEquals(listOf(IdentityKeyKind.PER_SITE), signedWith)
    }

    @Test fun `a site with a per-site login on record offers no choice and ignores a pick`() = runBlocking {
        history(any = 1, legacy = 0)
        assertNull(manager().identityChoice(request))
        manager().authenticate(request, IdentityKeyKind.LEGACY)
        assertEquals(listOf(IdentityKeyKind.PER_SITE), signedWith)
    }

    @Test fun `a locked wallet offers no choice`() = runBlocking {
        history(any = 0, legacy = 0)
        val locked = DigiIdManager(offline, dao, mockk(relaxed = true), sign = { _, _ -> null }, address = { _, _ -> null })
        assertNull(locked.identityChoice(request))
    }

    @Test fun `a wallet created here is offered no choice and a pick of the shared key is ignored`() = runBlocking {
        history(any = 0, legacy = 0)
        assertNull(manager(restored = false).identityChoice(request))
        manager(restored = false).authenticate(request, IdentityKeyKind.LEGACY)
        assertEquals(listOf(IdentityKeyKind.PER_SITE), signedWith)
    }
}
