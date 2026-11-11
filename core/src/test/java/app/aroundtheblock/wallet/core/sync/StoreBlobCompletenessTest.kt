package app.aroundtheblock.wallet.core.sync

import app.aroundtheblock.wallet.core.sync.StoreDurabilityTest.Companion.filterChain
import app.aroundtheblock.wallet.core.sync.StoreDurabilityTest.Companion.ledger
import app.aroundtheblock.wallet.core.sync.StoreDurabilityTest.Companion.savedBlocks
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** STORE-DURABILITY: which tmp files a load may recover, per format (the layouts are the C core's serializers). */
class StoreBlobCompletenessTest {

    @Test fun `filter-chain completeness follows its header`() {
        assertTrue(FilterHeaderStore.isComplete(filterChain(0)))
        assertTrue(FilterHeaderStore.isComplete(filterChain(7)))
        val wrongMagic = filterChain(1).also { it[0] = 0 }
        val wrongVersion = filterChain(1).also { it[4] = 2 }
        assertFalse(FilterHeaderStore.isComplete(wrongMagic))
        assertFalse(FilterHeaderStore.isComplete(wrongVersion))
    }

    @Test fun `ledger completeness accepts each version it parses`() {
        for (v in 1..3) assertTrue("v$v", CfScanLedgerStore.isComplete(ledger(2, version = v)))
        assertFalse(CfScanLedgerStore.isComplete(ledger(2, version = 4)))
        assertTrue(CfScanLedgerStore.isComplete(ledger(0)))
    }

    @Test fun `a block window with no blocks is not complete`() {
        assertFalse(SavedBlockStore.isComplete(byteArrayOf(0, 0, 0, 0)))
        assertTrue(SavedBlockStore.isComplete(savedBlocks(1)))
    }

}
