package io.digibyte.core.recovery

import io.digibyte.core.reconcile.ReconcileResult
import io.digibyte.core.reconcile.UtxoEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UtxoSourceTest {
    @Test
    fun fakeSource_returnsConfiguredUtxos() = runBlocking {
        val utxo = UtxoEntry("aa".repeat(32), 0, 100_000L, "Daddr", 100L, "76a914...88ac")
        val source: UtxoSource = FakeUtxoSource(
            mapOf("Daddr" to ReconcileResult(listOf(utxo), emptyMap(), 200L))
        )
        val result = source.fetchUtxos(listOf("Daddr"))
        assertEquals(1, result!!.utxos.size)
        assertEquals(100_000L, result.utxos[0].amountSatoshi)
    }

    @Test
    fun fakeSource_unreachableReturnsNull() = runBlocking {
        val source: UtxoSource = FakeUtxoSource(emptyMap(), reachable = false)
        assertNull(source.fetchUtxos(listOf("Daddr")))
    }
}
