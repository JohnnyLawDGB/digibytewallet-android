package io.digibyte

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.digibyte.core.sync.FilterHeaderStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Covers the coverage gap in [StaleDataWiper.wipeSyncBlobs]: it cleared
 * `dgb_sync_data`/`dgb_bloom_peers` but never touched the FILE-BACKED compact-
 * filter-header chain ([FilterHeaderStore]'s `saved_filter_headers<net>.bin`).
 * A crashed-restore recovery (the [BootGuard] restore bracket) wipes sync blobs
 * on the theory that the corrupt state is gone — but a corrupt `.bin` would
 * survive that wipe and could re-crash the very next restore. This test drives
 * the REAL [StaleDataWiper] (not mocked) against a real temp `filesDir` so the
 * assertion is behavioral, not a stub check.
 */
class StaleDataWiperFilterHeaderTest {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `wipeSyncBlobs deletes the file-backed filter-header chain`() {
        val harness = Harness()

        // Simulate a previously-persisted CF chain, as a crashed restore would
        // leave behind.
        val binFile = FilterHeaderStore.file(harness.context)
        binFile.writeBytes(byteArrayOf(1, 2, 3, 4))
        assertTrue("test setup: dummy .bin must exist before the wipe", binFile.exists())

        StaleDataWiper.wipeSyncBlobs(harness.context)

        assertFalse(
            "saved_filter_headers<net>.bin must be deleted by wipeSyncBlobs",
            binFile.exists()
        )
    }

    @Test
    fun `wipeSyncBlobs is a no-op-safe when no bin file exists yet`() {
        val harness = Harness()
        val binFile = FilterHeaderStore.file(harness.context)
        assertFalse(binFile.exists())

        // Must not throw even though there's nothing to delete.
        StaleDataWiper.wipeSyncBlobs(harness.context)

        assertFalse(binFile.exists())
    }

    // ==================== test harness ====================

    private class InMemoryPrefsFactory {
        private val stores = mutableMapOf<String, MutableMap<String, Any?>>()

        fun prefsFor(name: String): SharedPreferences {
            val backing = stores.getOrPut(name) { mutableMapOf() }
            return fakeSharedPreferences(backing)
        }

        private fun fakeSharedPreferences(backing: MutableMap<String, Any?>): SharedPreferences {
            val prefs: SharedPreferences = mockk(relaxed = true)
            val editor: SharedPreferences.Editor = mockk(relaxed = true)

            every { prefs.getBoolean(any(), any()) } answers {
                (backing[firstArg<String>()] as? Boolean) ?: secondArg()
            }
            every { prefs.getString(any(), any()) } answers {
                (backing[firstArg<String>()] as? String) ?: secondArg()
            }
            every { prefs.contains(any()) } answers { backing.containsKey(firstArg<String>()) }
            every { prefs.edit() } returns editor

            every { editor.putBoolean(any(), any()) } answers {
                backing[firstArg<String>()] = secondArg<Boolean>(); editor
            }
            every { editor.putString(any(), any()) } answers {
                backing[firstArg<String>()] = secondArg<String?>(); editor
            }
            every { editor.remove(any()) } answers { backing.remove(firstArg<String>()); editor }
            every { editor.clear() } answers { backing.clear(); editor }
            every { editor.commit() } returns true
            every { editor.apply() } returns Unit

            return prefs
        }
    }

    private class Harness {
        val factory = InMemoryPrefsFactory()
        private val tempDir = createTempDirForTest()
        val context: Context = mockk(relaxed = true)

        init {
            every { context.getSharedPreferences(any(), any()) } answers {
                factory.prefsFor(firstArg())
            }
            // FilterHeaderStore.file(ctx) resolves off context.filesDir — point it
            // at a scratch temp dir so real file I/O is exercised harmlessly.
            every { context.filesDir } returns tempDir
        }

        private fun createTempDirForTest(): File =
            File.createTempFile("stale-wiper-filterheader-test", "").apply {
                delete(); mkdirs()
            }
    }
}
