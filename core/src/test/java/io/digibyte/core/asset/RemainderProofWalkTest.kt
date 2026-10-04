package io.digibyte.core.asset

import io.digibyte.core.asset.RemainderProofWalk.Reason
import io.digibyte.core.asset.RemainderProofWalk.Verdict
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The last output of an asset transfer leaves the hold only on a proof that it carries no units,
 * read from transactions whose bytes hash to the ids asked for. Anything short of a proof keeps it
 * held.
 *
 * The chain here is a map of transactions; a transaction's "bytes" are its id with a prefix, so
 * [txidOf] can be told to disagree for one of them.
 *
 * The held transfer `t` has the shape of an asset received from someone else: input 0 is the
 * sender's carrier (output 0 of their transfer `p`, named by one instruction for 1 unit), input 1
 * is plain DGB (output 0 of a two-output payment `q`); `t` pays 1 unit to output 0 and its last
 * output, output 2, is the DGB the wallet holds.
 */
class RemainderProofWalkTest {

    private val ours = byteArrayOf(0x00, 0x14) + ByteArray(20) { 0x11 }
    private val theirs = byteArrayOf(0x00, 0x14) + ByteArray(20) { 0x22 }

    private fun fixed(out: Int, amount: Long, skip: Boolean = false) =
        DigiAssetEncoder.TransferInstruction(skip = skip, range = false, percent = false, outputIndex = out, amount = amount)

    private fun transfer(vararg inst: DigiAssetEncoder.TransferInstruction) =
        DigiAssetEncoder.encodeTransferScript(3, inst.toList())

    private fun id(tag: String) = tag.padEnd(64, '0')

    private val chain = HashMap<String, WalkTx>()
    private val fetched = mutableListOf<String>()
    private val unavailable = HashSet<String>()
    private val substitute = HashMap<String, String>()   // asked id -> id of the bytes actually returned

    private fun put(txid: String, inputs: List<Pair<String, Int>>, vararg outputs: ByteArray) {
        chain[txid] = WalkTx(inputs, outputs.toList())
    }

    private fun walk(maxTxs: Int = RemainderProofWalk.DEFAULT_MAX_TXS, maxDepth: Int = RemainderProofWalk.DEFAULT_MAX_DEPTH) =
        RemainderProofWalk(
            fetch = { txid ->
                fetched += txid
                if (txid in unavailable) null else "tx:${substitute[txid] ?: txid}".toByteArray()
            },
            txidOf = { bytes -> String(bytes).removePrefix("tx:") },
            parse = { bytes -> chain[String(bytes).removePrefix("tx:")] },
            maxTxs = maxTxs,
            maxDepth = maxDepth,
        )

    private val t = id("71")
    private val p = id("70")
    private val q = id("50")

    init {
        put(p, listOf(id("60") to 1), theirs, transfer(fixed(0, 1)), theirs)          // the sender's transfer
        put(q, listOf(id("40") to 0), theirs, theirs)                                // a plain payment
        put(t, listOf(p to 0, q to 0), ours, transfer(fixed(0, 1)), ours)
    }

    // ---- proven ----------------------------------------------------------------------------

    @Test fun `one carrier consumed whole by the transfer leaves nothing at the last output`() = runTest {
        assertEquals(Verdict.Proven, walk().proveLastOutputCarriesNoUnits(t, 2))
        assertFalse("a non-last output of a plain payment needs no further reads", id("40") in fetched)
    }

    @Test fun `a plain input that is itself a last output is followed back`() = runTest {
        val r = id("51")
        put(r, listOf(id("41") to 0), theirs, theirs)
        put(q, listOf(r to 0), theirs, theirs)                                       // r:0 is not r's last output
        put(t, listOf(p to 0, q to 1), ours, transfer(fixed(0, 1)), ours)            // spends q's LAST output
        assertEquals(Verdict.Proven, walk().proveLastOutputCarriesNoUnits(t, 2))
        assertTrue(r in fetched)
    }

    @Test fun `results are kept for the walk, so shared ancestry is read once`() = runTest {
        val t2 = id("72")
        put(t2, listOf(p to 0, q to 1), ours, transfer(fixed(0, 1)), ours)
        put(q, listOf(id("40") to 0), theirs, theirs, theirs)
        val w = walk()
        assertEquals(Verdict.Proven, w.proveLastOutputCarriesNoUnits(t, 2))
        val before = fetched.size
        assertEquals(Verdict.Proven, w.proveLastOutputCarriesNoUnits(t2, 2))
        assertEquals(listOf(t2), fetched.drop(before))
    }

    // ---- held --------------------------------------------------------------------------------

    @Test fun `a parent that cannot be fetched keeps the output held`() = runTest {
        unavailable += p
        assertEquals(Verdict.Held(Reason.UNAVAILABLE), walk().proveLastOutputCarriesNoUnits(t, 2))
    }

    @Test fun `a parent whose bytes are another transaction keeps the output held`() = runTest {
        substitute[p] = q
        assertEquals(Verdict.Held(Reason.ID_MISMATCH), walk().proveLastOutputCarriesNoUnits(t, 2))
    }

    @Test fun `the transaction itself must be the one asked for`() = runTest {
        substitute[t] = p
        assertEquals(Verdict.Held(Reason.ID_MISMATCH), walk().proveLastOutputCarriesNoUnits(t, 2))
    }

    /** A plain input reached through [n] payments, each spending the last output of the one before. */
    private fun longPlainRun(n: Int): String {
        var prev = id("30") to 0
        put(id("30"), listOf(id("20") to 0), theirs, theirs)
        for (i in 0 until n) {
            val tx = id("31" + i.toString().padStart(3, '0'))
            put(tx, listOf(prev), theirs, theirs)
            prev = tx to 1
        }
        put(t, listOf(p to 0, prev), ours, transfer(fixed(0, 1)), ours)
        return prev.first
    }

    @Test fun `reaching the transaction count bound keeps the output held`() = runTest {
        longPlainRun(10)
        assertEquals(Verdict.Held(Reason.BOUND), walk(maxTxs = 6).proveLastOutputCarriesNoUnits(t, 2))
        assertEquals(Verdict.Proven, walk(maxTxs = 20).proveLastOutputCarriesNoUnits(t, 2))
    }

    @Test fun `reaching the depth bound keeps the output held`() = runTest {
        longPlainRun(10)
        assertEquals(Verdict.Held(Reason.BOUND), walk(maxDepth = 4).proveLastOutputCarriesNoUnits(t, 2))
        assertEquals(Verdict.Proven, walk(maxDepth = 20).proveLastOutputCarriesNoUnits(t, 2))
    }

    @Test fun `a carrier holding more than the transfer consumes may leave a remainder`() = runTest {
        put(p, listOf(id("60") to 1), theirs, transfer(fixed(0, 3)), theirs)
        assertEquals(Verdict.Held(Reason.NOT_PROVABLE), walk().proveLastOutputCarriesNoUnits(t, 2))
    }

    @Test fun `two inputs that may hold units are not proven`() = runTest {
        val p2 = id("73")
        put(p2, listOf(id("61") to 0), theirs, transfer(fixed(0, 1)), theirs)
        put(t, listOf(p to 0, p2 to 0), ours, transfer(fixed(0, 2)), ours)
        assertEquals(Verdict.Held(Reason.NOT_PROVABLE), walk().proveLastOutputCarriesNoUnits(t, 2))
    }

    @Test fun `an input from the last output of a transfer with an unknown remainder is not proven`() = runTest {
        unavailable += id("60")
        put(t, listOf(p to 2, q to 0), ours, transfer(fixed(0, 1)), ours)   // p:2 is p's last output
        assertEquals(Verdict.Held(Reason.UNAVAILABLE), walk().proveLastOutputCarriesNoUnits(t, 2))
    }

    @Test fun `a skip instruction is outside the rule`() = runTest {
        put(t, listOf(p to 0, q to 0), ours, transfer(fixed(0, 1, skip = true)), ours)
        assertEquals(Verdict.Held(Reason.NOT_PROVABLE), walk().proveLastOutputCarriesNoUnits(t, 2))
    }

    @Test fun `an instruction naming the last output is not a remainder question`() = runTest {
        put(t, listOf(p to 0, q to 0), ours, transfer(fixed(2, 1)), ours)
        assertEquals(Verdict.Held(Reason.NOT_PROVABLE), walk().proveLastOutputCarriesNoUnits(t, 2))
    }

    @Test fun `only the last output can be proven`() = runTest {
        assertEquals(Verdict.Held(Reason.NOT_PROVABLE), walk().proveLastOutputCarriesNoUnits(t, 0))
    }

    @Test fun `a payload that does not re-encode to its own bytes is not trusted`() = runTest {
        // A trailing byte the lenient parser drops (it reads the instruction list as empty).
        val lenient = transfer(fixed(0, 1)).let { s ->
            val payload = s.copyOfRange(2, s.size) + byteArrayOf(0xFF.toByte())
            byteArrayOf(0x6A, payload.size.toByte()) + payload
        }
        put(p, listOf(id("60") to 1), theirs, lenient, theirs)
        assertEquals(Verdict.Held(Reason.NOT_PROVABLE), walk().proveLastOutputCarriesNoUnits(t, 2))
    }

    @Test fun `an issuance in the ancestry is outside the rule`() = runTest {
        // DA magic, version 3, issuance opcode 1 with a metadata hash: not a transfer.
        val issuance = byteArrayOf(0x6A, 0x26, 0x44, 0x41, 0x03, 0x01) + ByteArray(32) + byteArrayOf(0x01, 0x10)
        put(p, listOf(id("60") to 1), theirs, issuance, theirs)
        assertEquals(Verdict.Held(Reason.NOT_PROVABLE), walk().proveLastOutputCarriesNoUnits(t, 2))
    }
}
