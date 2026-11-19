package app.aroundtheblock.wallet.core.wallets

import app.aroundtheblock.wallet.core.InMemoryPrefsFactory
import app.aroundtheblock.wallet.core.wipeTestContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A quick start is display-only and belongs to the wallet it was taken for: kept until that
 * wallet's own sync has finished once, then cleared, and never seen by another wallet.
 */
class QuickStartStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private val prefs = InMemoryPrefsFactory()
    private val ctx by lazy { wipeTestContext(prefs, tmp.root) }
    private val b = WalletScope("0123456789abcdef", testnet = false)
    private val legacy = WalletScope.legacy(testnet = false)
    private val q = QuickStart(unverifiedSat = 150_000_000L, unverifiedDdCents = 125L, sourceLabel = "digiscope.me", atSecs = 1_763_000_000L)

    @Test fun `a quick start is kept until the wallet has synced`() {
        assertTrue(QuickStartStore.write(ctx, b, q))
        assertEquals(q, QuickStartStore.read(ctx, b))
        assertFalse("cleared before the wallet synced", QuickStartStore.clearIfSynced(ctx, b))
        assertNotNull(QuickStartStore.read(ctx, b))

        prefs.prefsFor(WalletStores.prefsName("dgb_sync_data", b)).edit().putBoolean("has_synced", true).commit()

        assertTrue(QuickStartStore.clearIfSynced(ctx, b))
        assertNull(QuickStartStore.read(ctx, b))
        assertFalse("nothing left to clear", QuickStartStore.clearIfSynced(ctx, b))
    }

    @Test fun `a quick start is the wallet's own`() {
        assertTrue(QuickStartStore.write(ctx, b, q))
        assertNull(QuickStartStore.read(ctx, legacy))
        // Another wallet having synced does not clear this one's.
        prefs.prefsFor(WalletStores.prefsName("dgb_sync_data", legacy)).edit().putBoolean("has_synced", true).commit()
        assertFalse(QuickStartStore.clearIfSynced(ctx, b))
        assertEquals(q, QuickStartStore.read(ctx, b))
    }

    @Test fun `no DigiDollar figure is stored as none`() {
        val noDd = q.copy(unverifiedDdCents = null)
        assertTrue(QuickStartStore.write(ctx, b, noDd))
        assertEquals(noDd, QuickStartStore.read(ctx, b))
    }

    /** A stored zero is "checked, holds none"; an absent key is "could not be checked". The two
     *  must not meet on the way through the store. A pin: it passes on the store as it was. */
    @Test fun `a checked zero of DigiDollar is stored as zero, not as none`() {
        val zero = q.copy(unverifiedDdCents = 0L)
        assertTrue(QuickStartStore.write(ctx, b, zero))
        assertEquals(0L, QuickStartStore.read(ctx, b)?.unverifiedDdCents)
        assertTrue("the zero was not written", prefs.prefsFor(WalletStores.prefsName("dgb_quickstart", b)).contains("unverified_dd_cents"))

        // And a later quick start with none drops the earlier figure rather than keeping it.
        assertTrue(QuickStartStore.write(ctx, b, q.copy(unverifiedDdCents = null)))
        assertNull(QuickStartStore.read(ctx, b)?.unverifiedDdCents)
        assertFalse(prefs.prefsFor(WalletStores.prefsName("dgb_quickstart", b)).contains("unverified_dd_cents"))
    }

    @Test fun `a write that does not land says so`() {
        prefs.refuseWritesTo(WalletStores.prefsName("dgb_quickstart", b))
        assertFalse(QuickStartStore.write(ctx, b, q))
    }
}
