package io.digibyte.service

import io.digibyte.ui.KotlinSourceGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source gate: the stranded-send sweep asks `shouldSweepRepublish` before it floods a recorded
 * send. The sweep runs on a 90 s timer and republished EVERY unconfirmed recorded send, so a
 * fresh stem was flooded from this wallet within seconds (measured on the Note 8, 2026-09-24:
 * stemmed 21:08:45, relayed back 21:08:49, flooded by the sweep 21:08:51), undoing the stem.
 * The decision itself is covered by `DandelionBroadcastPolicyTest`; this pins that the sweep
 * consults it, with the embargo state of the same tx, before its publish.
 */
class StrandedSweepSparesStemsGateTest {

    private val sync = KotlinSourceGate.of(File("src/main/java/io/digibyte/service/SyncService.kt").readText())

    @Test fun `the sweep consults the stem policy before it republishes`() {
        val sweep = sync.code.indexOf("private suspend fun rebroadcastStrandedSends()")
        assertTrue("rebroadcastStrandedSends not found", sweep >= 0)
        val body = sweep until sync.code.indexOf("\n    }\n", sweep)
        val asks = sync.calls("shouldSweepRepublish", body)
        assertEquals("the sweep must ask shouldSweepRepublish exactly once", 1, asks.size)
        assertEquals(
            listOf("Broadcaster.isEmbargoPending(txid)"),
            asks.single().arguments,
        )
        val publishes = sync.calls("NativeBridge.publishTransaction", body)
        assertEquals(1, publishes.size)
        assertTrue("the sweep publishes before asking", asks.single().range.first < publishes.single().range.first)
    }
}
