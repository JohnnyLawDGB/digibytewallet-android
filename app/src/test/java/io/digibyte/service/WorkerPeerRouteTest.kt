package io.digibyte.service

import org.junit.Assert.assertEquals
import org.junit.Test

/** [WorkerPeerRoute]: where the background worker's peer connections may go. */
class WorkerPeerRouteTest {

    @Test fun `Tor off dials peers directly`() {
        assertEquals(WorkerPeerRoute.Direct, WorkerPeerRoute.of(torEnabled = false, socksPort = null))
        assertEquals(WorkerPeerRoute.Direct, WorkerPeerRoute.of(torEnabled = false, socksPort = 9050))
    }

    @Test fun `Tor on and connected goes through its SOCKS port`() {
        assertEquals(WorkerPeerRoute.ViaSocks(9050), WorkerPeerRoute.of(torEnabled = true, socksPort = 9050))
    }

    @Test fun `Tor on without a SOCKS port waits`() {
        assertEquals(WorkerPeerRoute.Wait, WorkerPeerRoute.of(torEnabled = true, socksPort = null))
    }

    @Test fun `a port that is not a port waits`() {
        assertEquals(WorkerPeerRoute.Wait, WorkerPeerRoute.of(torEnabled = true, socksPort = 0))
        assertEquals(WorkerPeerRoute.Wait, WorkerPeerRoute.of(torEnabled = true, socksPort = -1))
        assertEquals(WorkerPeerRoute.Wait, WorkerPeerRoute.of(torEnabled = true, socksPort = 65_536))
    }
}
