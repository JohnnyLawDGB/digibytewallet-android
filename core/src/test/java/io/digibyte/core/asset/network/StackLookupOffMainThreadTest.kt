package io.digibyte.core.asset.network

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The indexer lookup runs a blocking HTTP call. Its callers include the asset confirmation, which is
 * launched on the main thread, where Android refuses network access outright — and the lookup turns
 * every exception into "no answer", so a main-thread call would refuse every asset send without a
 * visible error. The lookup therefore moves itself onto the IO dispatcher.
 */
class StackLookupOffMainThreadTest {

    private val src = File("src/main/java/io/digibyte/core/asset/network/DigiScopeAssetClient.kt").readText()

    @Test fun the_indexer_lookup_runs_on_the_io_dispatcher() {
        val body = src.substringAfter("override suspend fun stackOf(").substringBefore("override suspend fun getAssetData(")
        assertTrue("stackOf must switch to Dispatchers.IO before the call", Regex("""withContext\(\s*(kotlinx\.coroutines\.)?Dispatchers\.IO\s*\)""").containsMatchIn(body))
        assertTrue(body.indexOf("withContext") < body.indexOf(".execute()"))
    }
}
