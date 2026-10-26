package app.aroundtheblock.wallet.ui.hub

import app.aroundtheblock.wallet.core.db.dao.CachedMessageDao
import app.aroundtheblock.wallet.core.digiscope.DigiScopeClient
import app.aroundtheblock.wallet.core.hub.HubWebSocket
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verifyOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * 4.1.2 row 8 (N4): Log out ends the Hub session on the phone -- the chat socket is closed before the
 * session is forgotten (nothing more goes out or comes in on it), and the cached chat messages are deleted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HubLogoutTest {

    @Before fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun reset() = Dispatchers.resetMain()

    @Test fun `log out closes the socket, ends the session and clears the chat cache`() {
        val client = mockk<DigiScopeClient>(relaxed = true)
        val socket = mockk<HubWebSocket>(relaxed = true)
        val cache = mockk<CachedMessageDao>(relaxed = true)
        ProfileViewModel(client, socket, cache).logout()
        verifyOrder {
            socket.disconnect()
            client.logout()
        }
        coVerify(timeout = 2000) { cache.deleteAll() }
    }
}
