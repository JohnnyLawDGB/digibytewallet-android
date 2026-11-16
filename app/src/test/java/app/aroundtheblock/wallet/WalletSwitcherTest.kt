package app.aroundtheblock.wallet

import app.aroundtheblock.wallet.core.wallets.EntryState
import app.aroundtheblock.wallet.core.wallets.WalletEntry
import app.aroundtheblock.wallet.core.wallets.WalletOrigin
import app.aroundtheblock.wallet.core.wallets.WalletRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class WalletSwitcherTest {
    private val b = "bbbbbbbbbbbb"
    private val calls = mutableListOf<String>()
    private val registry = mockk<WalletRegistry>()

    private fun entry(id: String, state: EntryState = EntryState.READY, testnet: Boolean? = false) =
        WalletEntry(id, null, 2, testnet, 0L, WalletOrigin.CREATED, 1, state)

    private fun switcher(current: String = "legacy", web: Boolean = true) = WalletSwitcher(
        registry = registry,
        testnet = { false },
        currentId = { current },
        endWebSession = { calls += "endWebSession"; web },
        restart = { calls += "restart" },
    ).also {
        every { registry.find(b) } returns entry(b)
        every { registry.find("legacy") } returns entry("legacy", testnet = null)
        every { registry.setActive(false, any()) } answers { calls += "setActive:${secondArg<String>()}"; true }
    }

    @Test fun `a switch persists the id, ends the web session, then restarts`() {
        assertEquals(WalletSwitcher.Result.RESTARTING, switcher().switchTo(b))
        assertEquals(listOf("setActive:$b", "endWebSession", "restart"), calls)
    }

    @Test fun `a switch ends the web session before it restarts`() {
        assertEquals(WalletSwitcher.Result.RESTARTING, switcher(web = false).switchTo(b))
        assertEquals(listOf("setActive:$b", "endWebSession", "restart"), calls)
    }

    @Test fun `an unsaved id never restarts`() {
        val s = switcher()
        every { registry.setActive(false, b) } returns false
        assertEquals(WalletSwitcher.Result.NOT_SAVED, s.switchTo(b))
        assertEquals(emptyList<String>(), calls)
    }

    @Test fun `the wallet this process runs is not restarted into`() {
        assertEquals(WalletSwitcher.Result.ALREADY_ACTIVE, switcher().switchTo("legacy"))
        assertEquals(emptyList<String>(), calls)
    }

    @Test fun `a wallet the registry already made active is still restarted into`() {
        every { registry.activeId(false) } returns b
        assertEquals(WalletSwitcher.Result.RESTARTING, switcher().switchTo(b))
        assertEquals(1, calls.count { it == "restart" })
    }

    @Test fun `an unknown, pending or other-network wallet is refused`() {
        val s = switcher()
        every { registry.find("nope") } returns null
        every { registry.find("pend") } returns entry("pend", EntryState.PENDING)
        every { registry.find("tn") } returns entry("tn", testnet = true)
        listOf("nope", "pend", "tn").forEach { assertEquals(WalletSwitcher.Result.UNKNOWN_WALLET, s.switchTo(it)) }
        assertEquals(emptyList<String>(), calls)
    }
}
