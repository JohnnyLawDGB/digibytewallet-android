package io.digibyte.service

import io.digibyte.ui.KotlinSourceGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source gate (B234): the saved Dandelion setting reaches the broadcaster in a NEW process, not only
 * when the Settings toggle is flipped. `Broadcaster.dandelionEnabled` starts false in every process;
 * before this, sync start set only the native gate, so after any restart every send flooded while
 * the switch still read "on". Sync start and the toggle must both apply the setting through
 * `Broadcaster.applySetting`, which sets the mirror and the native gate together.
 */
class DandelionSettingAtStartGateTest {

    private val appRoot = File("src/main/java/io/digibyte")
    private fun gate(rel: String) = KotlinSourceGate.of(File(appRoot, rel).readText())

    @Test fun `sync start applies the saved setting to the broadcaster`() {
        val sync = gate("service/SyncService.kt")
        val applies = sync.calls("Broadcaster.applySetting")
        assertEquals("SyncService must apply the saved Dandelion setting exactly once", 1, applies.size)
        assertEquals(listOf("enabled"), applies.single().arguments)
        assertTrue(
            "SyncService still sets the native gate on its own, leaving the Kotlin mirror false",
            sync.calls("NativeBridge.setDandelionEnabled").isEmpty(),
        )
    }

    @Test fun `the settings toggle applies the setting the same way`() {
        val settings = gate("ui/settings/SettingsViewModel.kt")
        assertEquals(1, settings.calls("Broadcaster.applySetting").size)
        assertTrue(settings.calls("NativeBridge.setDandelionEnabled").isEmpty())
    }
}
