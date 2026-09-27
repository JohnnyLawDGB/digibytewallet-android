package io.digibyte.service

import android.content.Context
import androidx.work.WorkerParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * [SyncWorker] is constructed by WorkManager's default factory, which resolves the worker class by
 * name and calls `getDeclaredConstructor(Context, WorkerParameters)` — so that constructor, and
 * only that constructor, must exist and be public. Anything the worker needs beyond its context
 * and parameters is obtained inside it (a Hilt entry point), never through the constructor.
 *
 * Exactly the call the default factory makes; a worker this cannot resolve is never run.
 */
class SyncWorkerConstructionTest {

    @Test fun `the default factory can resolve the worker's constructor`() {
        val constructor = SyncWorker::class.java.getDeclaredConstructor(Context::class.java, WorkerParameters::class.java)
        assertNotNull(constructor)
        assertEquals("the constructor is not public", Modifier.PUBLIC, constructor.modifiers and Modifier.PUBLIC)
    }

    @Test fun `the worker has no other constructor`() {
        val signatures = SyncWorker::class.java.declaredConstructors.map { c -> c.parameterTypes.map { it.name } }
        assertEquals(listOf(listOf(Context::class.java.name, WorkerParameters::class.java.name)), signatures)
    }
}
