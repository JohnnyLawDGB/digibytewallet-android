package app.aroundtheblock.wallet.core.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.aroundtheblock.wallet.core.bridge.NativeBridge
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * Binds the stranded-send sweep's removal rule to the refusal hold in `BRStrandedSend.h`:
 * [PublishOutcome.actionFor], [PublishOutcome.isPlainDgb] and [RefusalHold].
 *
 * The header is the source of truth: iOS imports it, and the Kotlin is kept as a mirror only because
 * `PublishOutcomeTest`, `RefusalHoldTest` and `InvalidSendRemovalTest` run on the host JVM, where
 * NativeBridge cannot load. A drift means one platform removes a send, and gives its coins back,
 * where the other keeps it: a send removed while the network still accepts it can be paid twice.
 * So every action is compared over every errno class and both flags; the gate over every reading; and
 * the hold over the RefusalHoldTest scenarios and seeded random scripts of clocks (including ones
 * that step back), rebuilt and unknown managers, answers read across a rebuild, and forgets.
 *
 * Run: ./gradlew :core:connectedMainnetDebugAndroidTest
 *      -Pandroid.testInstrumentationRunnerArguments.class=app.aroundtheblock.wallet.core.sync.StrandedRefusalParityTest
 */
@RunWith(AndroidJUnit4::class)
class StrandedRefusalParityTest {

    private companion object {
        const val REFUSED = 1
        const val BEFORE_KNOWN = 2
        const val AFTER_KNOWN = 4
        const val FORGET = 8
    }

    /** One observation: the clock, the manager read before and after the answer (null: unknown), the answer. */
    private data class Step(val now: Long, val before: Long?, val after: Long?, val refused: Boolean, val forget: Boolean = false)

    private fun kotlinRun(holdMs: Long, steps: List<Step>): IntArray {
        var now = 0L
        val reads = ArrayDeque<Long?>()
        val hold = RefusalHold(clock = { now }, manager = { reads.removeFirst() }, holdMs = holdMs)
        return IntArray(steps.size) { i ->
            val s = steps[i]
            if (s.forget) hold.forget("t")
            now = s.now
            reads.clear(); reads.addLast(s.before); reads.addLast(s.after)
            if (hold.observe("t") { s.refused }) 1 else 0
        }
    }

    private fun cRun(holdMs: Long, steps: List<Step>): IntArray? = NativeBridge.strandedRefusalHoldRun(
        holdMs,
        LongArray(steps.size) { steps[it].now },
        LongArray(steps.size) { steps[it].before ?: 0L },
        LongArray(steps.size) { steps[it].after ?: 0L },
        IntArray(steps.size) {
            val s = steps[it]
            (if (s.refused) REFUSED else 0) or (if (s.before != null) BEFORE_KNOWN else 0) or
                (if (s.after != null) AFTER_KNOWN else 0) or (if (s.forget) FORGET else 0)
        },
    )

    private fun agree(name: String, steps: List<Step>, holdMs: Long = RefusalHold.HOLD_MS) {
        assertArrayEquals("$name: the Kotlin hold is not the header's", cRun(holdMs, steps), kotlinRun(holdMs, steps))
    }

    @Test
    fun holdIsTheHeaders() {
        assertEquals("RefusalHold.HOLD_MS drifted from BR_STRANDED_REFUSAL_HOLD_MS",
            NativeBridge.strandedRefusalHoldMs(), RefusalHold.HOLD_MS)
    }

    @Test
    fun actionsAgree() {
        val codes = listOf(0, PublishOutcome.EINVAL, PublishOutcome.ENOTCONN, PublishOutcome.ETIMEDOUT,
            PublishOutcome.ECANCELED, PublishOutcome.PENDING, 1, 57, 60, 9999, Int.MIN_VALUE, Int.MAX_VALUE)
        for (e in codes) for (plain in listOf(false, true)) for (refused in listOf(false, true)) {
            val kotlin = PublishOutcome.of(e).actionFor(plain, refused)
            val c = NativeBridge.strandedRefusalActionFor(e, plain, refused)
            assertEquals("errno $e, plain DGB $plain, refused by peers $refused", c, kotlin.ordinal)
            assertEquals("Action ordinals moved", if (kotlin == PublishOutcome.Action.KEEP) 0 else 1, kotlin.ordinal)
        }
    }

    @Test
    fun plainDgbGateAgrees() {
        for (held in listOf(false, true)) for (asset in listOf(false, true))
            for (dd in listOf(Int.MIN_VALUE, -1, 0, 1, 2, 3, 4, Int.MAX_VALUE)) {
                assertEquals("held $held, asset $asset, DigiDollar type $dd",
                    NativeBridge.strandedSendIsPlainDgb(held, asset, dd), PublishOutcome.isPlainDgb(held, asset, dd))
            }
    }

    /** RefusalHoldTest's scenarios, step for step. */
    @Test
    fun scenariosAgree() {
        val t0 = 1_000_000L
        val min = 60_000L
        agree("held ten minutes", listOf(Step(t0, 1, 1, true), Step(t0 + 10 * min - 1, 1, 1, true), Step(t0 + 10 * min, 1, 1, true)))
        agree("sweep cadence", (0..7).map { Step(t0 + it * 90_000L, 1, 1, true) })
        agree("a false answer forgets", listOf(Step(t0, 1, 1, true), Step(t0 + 5 * min, 1, 1, false),
            Step(t0 + 10 * min, 1, 1, true), Step(t0 + 20 * min - 1, 1, 1, true), Step(t0 + 20 * min, 1, 1, true)))
        agree("clock steps back", listOf(Step(t0, 1, 1, true), Step(t0 - 30 * min, 1, 1, true),
            Step(t0 + 10 * min - 1, 1, 1, true), Step(t0 + 10 * min, 1, 1, true)))
        agree("forgotten", listOf(Step(t0, 1, 1, true), Step(t0 + 10 * min, 1, 1, true, forget = true)))
        agree("rebuilt manager", listOf(Step(t0, 1, 1, true), Step(t0 + 10 * min, 2, 2, true),
            Step(t0 + 20 * min - 1, 2, 2, true), Step(t0 + 20 * min, 2, 2, true)))
        agree("answer across a rebuild", listOf(Step(t0, 1, 1, true), Step(t0 + 10 * min, 1, 2, true),
            Step(t0 + 10 * min, 2, 2, true), Step(t0 + 20 * min, 2, 2, true)))
        agree("unknown manager", listOf(Step(t0, null, null, true), Step(t0 + 30 * min, null, null, true)))
        agree("InvalidSendRemovalTest", listOf(Step(0, 1, 1, true), Step(300_000, 1, 1, false), Step(600_000, 1, 1, true),
            Step(1_200_000, 1, 1, true)), holdMs = 600_000L)
        agree("Long wrap", listOf(Step(Long.MAX_VALUE - 100_000, 7, 7, true), Step(Long.MIN_VALUE + 499_999, 7, 7, true)))
    }

    /** Seeded random scripts: every input the hold reads, in every combination the generator reaches. */
    @Test
    fun randomScriptsAgree() {
        val rnd = Random(0x5EED_4110)
        val managers = listOf<Long?>(1L, 1L, 1L, 2L, null)
        repeat(500) { n ->
            var now = rnd.nextLong(-1_000_000L, 1_000_000_000L)
            val steps = List(rnd.nextInt(1, 30)) {
                now += listOf(0L, 1L, 90_000L, 300_000L, 599_999L, 600_000L, -60_000L, -1L).random(rnd)
                val before = managers.random(rnd)
                val after = if (rnd.nextInt(5) == 0) managers.random(rnd) else before
                Step(now, before, after, refused = rnd.nextInt(6) != 0, forget = rnd.nextInt(12) == 0)
            }
            val hold = listOf(RefusalHold.HOLD_MS, 1L, 90_000L, 600_000L).random(rnd)
            agree("script $n (hold $hold ms)", steps, hold)
        }
    }

    @Test
    fun unequalArraysAnswerNull() {
        assertNull(NativeBridge.strandedRefusalHoldRun(600_000L, LongArray(2), LongArray(1), LongArray(2), IntArray(2)))
    }
}
