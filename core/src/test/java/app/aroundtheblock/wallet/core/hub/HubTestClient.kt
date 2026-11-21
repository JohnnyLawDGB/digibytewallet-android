package app.aroundtheblock.wallet.core.hub

import io.mockk.every
import io.mockk.mockk
import okhttp3.OkHttpClient

/**
 * For a mocked shared client handed to [HubWebSocket], which opens its socket on a client derived
 * from the one it is given (HUB-WS-NO-REDIRECT): the derivation answers this mock itself, so the
 * test's own `newWebSocket` answer is the one reached. Only a derivation that turns both redirect
 * switches off is answered; any other fails the test that made it.
 */
fun OkHttpClient.derivesItselfWithoutRedirects() {
    val builder = mockk<OkHttpClient.Builder>()
    every { builder.followRedirects(false) } returns builder
    every { builder.followSslRedirects(false) } returns builder
    every { builder.build() } returns this
    every { newBuilder() } returns builder
}
