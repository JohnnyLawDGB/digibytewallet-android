package io.digibyte.core.dandelion

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Broadcaster.applySetting] is the one way the user's Dandelion setting reaches the broadcaster:
 * it sets the Kotlin mirror [Broadcaster.broadcast] checks, whatever the native gate does. The
 * native library is absent in a JVM test, so this also pins that a native failure cannot stop the
 * mirror being set.
 */
class BroadcasterSettingTest {
    @After fun reset() { Broadcaster.dandelionEnabled = false }

    @Test fun `applySetting sets the mirror both ways even when the native gate is unavailable`() {
        Broadcaster.applySetting(true)
        assertTrue(Broadcaster.dandelionEnabled)
        Broadcaster.applySetting(false)
        assertFalse(Broadcaster.dandelionEnabled)
    }
}
