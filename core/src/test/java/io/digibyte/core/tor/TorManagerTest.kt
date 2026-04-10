package io.digibyte.core.tor

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for TorState sealed class and TorManager state helpers.
 * These test the state model without needing a real Tor daemon.
 */
class TorManagerTest {

    @Test
    fun `TorState Disabled is the initial default`() {
        val state: TorState = TorState.Disabled
        assertFalse(state is TorState.Connected)
        assertFalse(state is TorState.Starting)
        assertFalse(state is TorState.Failed)
    }

    @Test
    fun `TorState Connected carries socksPort`() {
        val state = TorState.Connected(socksPort = 9150)
        assertEquals(9150, state.socksPort)
    }

    @Test
    fun `TorState Failed carries reason string`() {
        val state = TorState.Failed("Bootstrap timed out")
        assertEquals("Bootstrap timed out", state.reason)
    }

    @Test
    fun `TorState sealed class exhaustive matching`() {
        // Ensures all subtypes are handled — compile-time check.
        val states = listOf(
            TorState.Disabled,
            TorState.Starting,
            TorState.Connecting,
            TorState.Connected(9050),
            TorState.Failed("test")
        )
        for (state in states) {
            val label = when (state) {
                is TorState.Disabled -> "disabled"
                is TorState.Starting -> "starting"
                is TorState.Connecting -> "connecting"
                is TorState.Connected -> "connected:${state.socksPort}"
                is TorState.Failed -> "failed:${state.reason}"
            }
            assertNotNull(label)
        }
    }

    @Test
    fun `Connected socksPort is accessible via safe cast`() {
        val state: TorState = TorState.Connected(35607)
        val port = (state as? TorState.Connected)?.socksPort
        assertEquals(35607, port)
    }

    @Test
    fun `Non-Connected state returns null port via safe cast`() {
        val state: TorState = TorState.Starting
        val port = (state as? TorState.Connected)?.socksPort
        assertNull(port)
    }
}
