package io.digibyte.core.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.digibyte.core.bridge.NativeBridge
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Binds Kotlin's [RecreateSequence] executor to the order declared in `BRRecreateSequence.h`.
 *
 * ## Why this one is shaped differently
 *
 * [CfRecoveryPolicyParityTest] and [PublishOutcomeParityTest] compare two pure tables. This
 * cannot: `RecreateSequence.run` takes five `suspend` lambdas, and a coroutine step cannot be
 * driven from a C callback without blocking the calling thread inside JNI — the exact hazard
 * `KeepaliveHealth.GIVE_UP_WEDGED` describes, where `Job.cancel()` cannot interrupt a thread
 * inside a JNI call and the shared dispatcher pool starves.
 *
 * So C owns the *specification* — the order, the names, the every-step-runs rule — and each
 * platform keeps its own executor. This test is what makes that split safe: it runs the real
 * Kotlin executor with recording lambdas and asserts the observed call order equals what the
 * C header declares. It is an assertion about behaviour, not about a table.
 *
 * ## What is at stake
 *
 * A recovery that rebuilds the manager before refreshing the near-tip window floors
 * `lastBlock` to the birth checkpoint. Measured on a Note 8: a scan at 24,052,509 dropped to
 * 22,650,000 and spent ~6 hours climbing back. The order IS the fix.
 */
@RunWith(AndroidJUnit4::class)
class RecreateSequenceParityTest {

    private companion object {
        /** C step value -> the label the Kotlin executor uses in its failure list. */
        val LABEL_BY_STEP = mapOf(
            0 to "flush",
            1 to "reload",
            2 to "forceReconnect",
            3 to "startSync",
            4 to "restoreLedger",
        )
    }

    /** Run the real executor with recording lambdas; returns the labels in call order. */
    private fun observedOrder(
        failAt: String? = null,
    ): Pair<List<String>, RecreateSequence.Result> {
        val seen = mutableListOf<String>()
        fun step(name: String): suspend () -> Unit = {
            seen += name
            if (name == failAt) throw IllegalStateException("injected failure in $name")
        }
        val result = runBlocking {
            RecreateSequence.run(
                flushPersistedState = step("flush"),
                reloadBlocksNearTip = {
                    seen += "reload"
                    if (failAt == "reload") throw IllegalStateException("injected failure in reload")
                    true
                },
                forceReconnect = step("forceReconnect"),
                startSync = step("startSync"),
                restoreLedgerAndSnap = step("restoreLedger"),
            )
        }
        return seen to result
    }

    /** The C header's order, as labels. */
    private fun cOrder(): List<String> =
        (0 until NativeBridge.recreateStepCount()).map { i ->
            val step = NativeBridge.recreateStepAt(i)
            NativeBridge.recreateStepName(step)
                ?: error("C step $step at position $i has no name")
        }

    /** Sanity: C agrees with itself about how many steps there are. */
    @Test
    fun stepCountMatches() {
        assertEquals("C declares a different number of steps", 5, NativeBridge.recreateStepCount())
        assertEquals("out-of-range index must be -1", -1, NativeBridge.recreateStepAt(5))
        assertEquals("negative index must be -1", -1, NativeBridge.recreateStepAt(-1))
    }

    /** C's names match the labels the Kotlin executor writes into its failure list. */
    @Test
    fun namesMatchKotlinFailureLabels() {
        LABEL_BY_STEP.forEach { (step, label) ->
            assertEquals(
                "C name for step $step drifted from the Kotlin failure label",
                label,
                NativeBridge.recreateStepName(step),
            )
        }
        org.junit.Assert.assertNull(
            "an unknown step must have no name",
            NativeBridge.recreateStepName(99),
        )
    }

    /**
     * THE test. The Kotlin executor must visit the steps in the order C declares.
     *
     * If someone reorders `RecreateSequence.run`'s body — moving the reload after
     * `forceReconnect`, say — this fails, instead of a user losing six hours.
     */
    @Test
    fun kotlinExecutorFollowsTheCOrder() {
        val (observed, _) = observedOrder()
        assertEquals(
            "the Kotlin executor's call order diverged from BRRecreateSequence.h",
            cOrder(),
            observed,
        )
    }

    /** Spelled out separately, because this pairing is the actual defect. */
    @Test
    fun reloadPrecedesTheRebuildThatConsumesIt() {
        val (observed, _) = observedOrder()
        assertTrue(
            "the near-tip reload must run before forceReconnect — the rebuild consumes it",
            observed.indexOf("reload") < observed.indexOf("forceReconnect"),
        )
        assertTrue(
            "the persisted-state flush must run first — both restores read the PERSISTED snapshot",
            observed.first() == "flush",
        )
        assertTrue(
            "the ledger restore must run after the new manager exists",
            observed.indexOf("startSync") < observed.indexOf("restoreLedger"),
        )
    }

    /**
     * Every step runs even when an earlier one throws, and C says so too.
     *
     * Aborting halfway leaves a manager marked for rebuild and never rebuilt — worse than the
     * state being recovered from.
     */
    @Test
    fun everyStepRunsAfterAFailure() {
        (0 until NativeBridge.recreateStepCount()).forEach { i ->
            val step = NativeBridge.recreateStepAt(i)
            assertTrue(
                "C says step ${NativeBridge.recreateStepName(step)} aborts the sequence",
                NativeBridge.recreateContinuesAfterFailure(step),
            )
        }

        LABEL_BY_STEP.values.forEach { failing ->
            val (observed, result) = observedOrder(failAt = failing)
            assertEquals(
                "a failure in '$failing' stopped the sequence; every step must still run",
                cOrder(),
                observed,
            )
            assertTrue(
                "the failure in '$failing' was swallowed instead of reported",
                result.failures.any { it.startsWith("$failing:") },
            )
        }
    }
}
