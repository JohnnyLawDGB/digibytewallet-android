package app.aroundtheblock.wallet.core.tor

import android.content.Context
import app.aroundtheblock.wallet.core.InMemoryPrefsFactory
import app.aroundtheblock.wallet.core.db.dao.WalletConfigDao
import app.aroundtheblock.wallet.core.db.entity.WalletConfigEntity
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A new install's Tor defaults are decided from the install's own state (TorManager's `dgb_tor`),
 * never from a wallet's database, so adding or switching wallets can never change Tor.
 *
 * Multi-wallet step 1 gave every wallet its own database. The launch used to read "this database has
 * no `wallet_config` row" as a new install and write the Tor setting off. A wallet just added has no
 * row, and adding one restarts the app into it, so that first launch turned a Tor user's setting off:
 * the core was told it may dial peers directly, and every connection after it went direct while the
 * Network Info switch quietly read "off". A testnet database, one set aside by recovery or by the
 * crash-loop breaker, a wiped one and the temporary database a Keystore fault gives a session read
 * the same way. MainActivity now runs [TorManager.applyNewInstallDefaults] and nothing else
 * (`AppWideSettingsSourceGateTest` holds it to that).
 */
class TorNewInstallDefaultsTest {

    /** The install's preferences files: every launch of the app reads the same ones. */
    private val device = InMemoryPrefsFactory()
    private val ctx: Context = mockk<Context>(relaxed = true).also { ctx ->
        every { ctx.getSharedPreferences(any(), any()) } answers { device.prefsFor(firstArg()) }
        every { ctx.applicationContext } returns ctx
    }
    private val torFile get() = device.prefsFor(TorManager.PREFS)

    /** One launch: a new process with its own TorManager, the core bound to it as DigiByteApp binds it. */
    private inner class Launch {
        val tor = TorManager(ctx)
        /** Every value handed to the core's "peers must go through Tor" switch, in order. */
        val toldCore = mutableListOf<Boolean>()

        init { tor.bindPeersRequireTor { toldCore += it } }

        /** The launch step MainActivity.onCreate runs. */
        fun applyLaunchDefaults(): Boolean = tor.applyNewInstallDefaults()
    }

    /** One wallet's database as a launch can see it: its `wallet_config` row, or none. */
    private class WalletDb(private val row: WalletConfigEntity?) : WalletConfigDao {
        override fun observe(): Flow<WalletConfigEntity?> = flowOf(row)
        override suspend fun get(): WalletConfigEntity? = row
        override suspend fun upsert(config: WalletConfigEntity) = throw AssertionError("a launch writes no wallet database")
    }

    @Test fun `a fresh install is written Tor off, with the old prompt marked shown`() {
        val first = Launch()
        assertTrue("a fresh install did not get its Tor defaults", first.applyLaunchDefaults())
        assertFalse(first.tor.isEnabled)
        assertEquals(false, torFile.durable[TorManager.KEY_ENABLED])
        assertEquals(true, torFile.durable[TorManager.KEY_UPGRADE_PROMPT_SHOWN])
        assertFalse("the defaults were written twice", Launch().applyLaunchDefaults())
    }

    @Test fun `adding a wallet leaves Tor on exactly as the user set it`() {
        // The legacy wallet: installed, then its owner turns Tor on in Settings → Network Info.
        Launch().apply {
            applyLaunchDefaults()
            tor.isEnabled = true
        }
        val before = torFile.durable.toMap()

        // Adding a wallet restarts the app into it. Its database holds no wallet_config row, which the
        // replaced step took for a new install (see the control below); this step never looks there.
        val added = Launch()
        val wrote = added.applyLaunchDefaults()
        assertTrue("adding a wallet turned Tor off", added.tor.isEnabled)
        assertTrue("the added wallet's launch told the core it may go direct", added.toldCore.last())
        assertFalse("the added wallet's launch rewrote the Tor setting", wrote)
        assertEquals("the added wallet's launch changed the install's Tor file", before, torFile.durable.toMap())

        // Switching back (another restart) changes nothing either.
        val back = Launch()
        assertFalse(back.applyLaunchDefaults())
        assertTrue("switching wallets turned Tor off", back.tor.isEnabled)
        assertEquals(before, torFile.durable.toMap())
    }

    @Test fun `a Tor setting turned off stays off in an added wallet`() {
        Launch().apply {
            applyLaunchDefaults()
            tor.isEnabled = true
            tor.isEnabled = false
        }
        val added = Launch()
        assertFalse(added.applyLaunchDefaults())
        assertFalse("adding a wallet turned Tor on", added.tor.isEnabled)
    }

    /** A Phase-1 upgrade marked the old prompt shown without ever writing the setting. */
    @Test fun `an install that only marked the old prompt shown reads Tor off before and after`() {
        torFile.seed(TorManager.KEY_UPGRADE_PROMPT_SHOWN, true)
        val launch = Launch()
        assertFalse(launch.tor.isEnabled)
        assertTrue("the setting was never written, so the defaults are owed", launch.applyLaunchDefaults())
        assertFalse(launch.tor.isEnabled)
        assertEquals(false, torFile.durable[TorManager.KEY_ENABLED])
    }

    /**
     * The control: the launch step this replaced (MainActivity.applyTorDefaults at develop 52aa40000,
     * its decision verbatim), run in the same scenario. It has to turn Tor off in the added wallet, or
     * the scenario above could not have seen the bug.
     */
    @Test fun `the scenario catches the replaced step - an added wallet's empty database read as a new install`() = runTest {
        suspend fun replacedStep(tor: TorManager, db: WalletConfigDao) {
            val cfg = db.get()
            if (cfg == null) {
                tor.isEnabled = false
                tor.upgradePromptShown = true
            }
        }
        // The legacy wallet: the Settings switch turned Tor on and (then) also wrote this row.
        Launch().tor.isEnabled = true
        val legacyDb = WalletDb(WalletConfigEntity(torEnabled = true, torPromptShown = true))
        Launch().apply {
            replacedStep(tor, legacyDb)
            assertTrue("control: the legacy wallet's own launch kept Tor on", tor.isEnabled)
        }

        val added = Launch()
        replacedStep(added.tor, WalletDb(row = null))
        assertFalse("control: the replaced step left Tor on, so the scenario cannot see the bug", added.tor.isEnabled)
        assertFalse("control: the replaced step did not tell the core to go direct", added.toldCore.last())
    }
}
