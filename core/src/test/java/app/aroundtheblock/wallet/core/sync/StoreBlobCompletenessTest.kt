package app.aroundtheblock.wallet.core.sync

import app.aroundtheblock.wallet.core.sync.StoreDurabilityTest.Companion.filterChain
import app.aroundtheblock.wallet.core.sync.StoreDurabilityTest.Companion.ledger
import app.aroundtheblock.wallet.core.sync.StoreDurabilityTest.Companion.savedBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    /** The C side: what the completeness checks mirror. A format change there must fail here. */
    private val core = File("../native/src/main/jni/digibytewallet-core")

    private fun define(file: String, name: String): Long {
        val src = File(core, file).also { assertTrue("scanner is blind: no ${it.path}", it.isFile) }.readText()
        val m = Regex("""#define\s+$name\s+(0x[0-9A-Fa-f]+|\d+)u?\b""").find(src)
            ?: throw AssertionError("scanner is blind: no #define $name in $file")
        val v = m.groupValues[1]
        return if (v.startsWith("0x")) v.substring(2).toLong(16) else v.toLong()
    }

    @Test fun `the ledger constants are the C header's`() {
        assertEquals("a ledger version bump must update CfScanLedgerStore.isComplete (its entry sizes too)",
            define("BRCFRestorePreflight.h", "BR_CF_RESTORE_LEDGER_VERSION_MAX"), CfScanLedgerStore.LEDGER_VERSION_MAX)
        assertEquals(define("BRCFRestorePreflight.h", "BR_CF_RESTORE_LEDGER_MAGIC"), CfScanLedgerStore.LEDGER_MAGIC)
        assertEquals(define("BRCFScanLedger.c", "CF_LEDGER_VERSION_3"), CfScanLedgerStore.LEDGER_VERSION_MAX)
    }

    @Test fun `the filter-chain constants are the C serializer's`() {
        assertEquals(define("BRCompactFilterChain.c", "BR_COMPACT_FILTER_CHAIN_MAGIC"), FilterHeaderStore.CHAIN_MAGIC)
        assertEquals(define("BRCompactFilterChain.c", "BR_COMPACT_FILTER_CHAIN_VERSION"), FilterHeaderStore.CHAIN_VERSION.toLong())
    }
}
