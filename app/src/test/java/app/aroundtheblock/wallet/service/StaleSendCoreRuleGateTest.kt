package app.aroundtheblock.wallet.service

import app.aroundtheblock.wallet.ui.KotlinSourceGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source gate: the stale-send clear's decision (4.1.3, review R8) -- whether a valid, unconfirmed own
 * send is a day old and one the network never took -- is the core's (BRStaleSend.h, through
 * NativeBridge.staleSendOldEnough / staleSendIsStale / staleSendNextStep), which iOS calls too, and
 * not Kotlin's. Until this moved, StaleSendPredicate (WalletManager.kt) and the early returns of
 * DgbNodeClient.sendNeverRelayed were the rule. Kotlin keeps the network calls and the parse of their
 * answers. The rule's values are checked by the host KAT stale_send_kat, which has a red arm for the
 * age (STALE_SEND_AGE_RED) and one for the node's evidence (STALE_SEND_RELAY_RED).
 */
class StaleSendCoreRuleGateTest {

    private val coreRoot = File("../core/src/main/java/app/aroundtheblock/wallet/core")
    private val nodeFile = File(coreRoot, "reconcile/DgbNodeClient.kt")
    private val walletFile = File(coreRoot, "WalletManager.kt")
    private val node = KotlinSourceGate.of(nodeFile.readText())
    private val wallet = KotlinSourceGate.of(walletFile.readText())
    private val header = File("../native/src/main/jni/digibytewallet-core/BRStaleSend.h")

    private fun body(source: KotlinSourceGate, name: String): IntRange {
        val declared = Regex("""fun\s+$name\s*\(""").find(source.code) ?: error("no fun $name: update the gate")
        // The body's brace follows the parameter list (which may hold a lambda type's default `{ _, _ -> false }`).
        val params = source.code.indexOf('(', declared.range.first)
        var depth = 0
        var at = params
        while (true) {
            when (source.code[at]) { '(' -> depth++; ')' -> depth-- }
            if (depth == 0) break
            at++
        }
        val open = source.code.indexOf('{', at)
        return source.blockAt(open) ?: error("unbalanced body of $name")
    }

    @Test fun `no Kotlin copy of the stale-send rule is left`() {
        val mains = listOf(File("src/main/java"), File("../core/src/main/java")).flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }
        assertTrue("no sources found: update the gate", mains.size > 10)
        for (f in mains) {
            val code = KotlinSourceGate.of(f.readText()).code
            assertTrue("${f.name} names StaleSendPredicate", !Regex("""\bStaleSendPredicate\b""").containsMatchIn(code))
        }
        // The old age rule was `timestampS > 0L && nowS - timestampS >= MIN_AGE_S`, MIN_AGE_S = 24L * 60 * 60.
        for ((name, src) in listOf("WalletManager" to wallet, "DgbNodeClient" to node)) {
            assertTrue("$name keeps a day constant of its own",
                !Regex("""\bMIN_AGE_S\b|24L?\s*\*\s*60\s*\*\s*60|86_?400""").containsMatchIn(src.code))
        }
        val clear = body(wallet, "clearStuckSends")
        assertTrue("clearStuckSends compares a send's age itself",
            !Regex("""nowS\s*-\s*ts\b|\bts\s*>\s*0""").containsMatchIn(wallet.code.substring(clear)))
    }

    @Test fun `the clear decides a send's staleness with the core`() {
        val clear = body(wallet, "clearStuckSends")
        val old = wallet.calls("NativeBridge.staleSendOldEnough", clear)
        val stale = wallet.calls("NativeBridge.staleSendIsStale", clear)
        assertEquals(1, old.size)
        assertEquals(1, stale.size)
        assertEquals(listOf("ts", "nowS"), old.single().arguments)
        assertEquals(listOf("ts", "nowS"), stale.single().arguments.take(2))
        // The node is asked (neverRelayed) only as staleSendIsStale's third argument, after the age and
        // the dead and orphan checks, so it is never asked about a young, dead or orphaned send.
        assertTrue("staleSendIsStale's evidence is not the node's answer",
            stale.single().arguments[2].startsWith("neverRelayed(txid,"))
        assertEquals("neverRelayed is asked elsewhere", 1, wallet.calls("neverRelayed", clear).size)
        val decl = Regex("""val\s+stale\s*=\s*!dead\s*&&\s*!orphan\s*&&\s*""").find(wallet.code, clear.first)
        assertTrue("no `val stale = !dead && !orphan && ...` in clearStuckSends", decl != null && decl.range.first in clear)
        assertEquals("the age is not asked first", decl!!.range.last + 1, old.single().range.first)
        assertEquals("the age and the evidence are not joined by &&", "&&",
            wallet.code.substring(old.single().range.last + 1, stale.single().range.first).trim())
    }

    @Test fun `sendNeverRelayed asks what the core says and decides nothing`() {
        val b = body(node, "sendNeverRelayed")
        val text = node.code.substring(b)
        val step = node.calls("NativeBridge.staleSendNextStep", b)
        assertEquals(1, step.size)
        assertEquals(listOf("pinned", "inputs.size", "txStatus", "asked", "lastAnswer"), step.single().arguments)
        assertTrue("pinned is not the default-endpoint test",
            Regex("""val\s+pinned\s*=\s*endpoint\(\)\s*==\s*DEFAULT_BASE_URL\s""").containsMatchIn(text))
        // The old copy: `inputs.isEmpty()`, `!= TxNetworkStatus.UNKNOWN`, `outputUnspent(t, v) != true`.
        assertTrue("sendNeverRelayed reads a node answer itself",
            !Regex("""TxNetworkStatus\s*\.|!=\s*true|==\s*true|==\s*false|isEmpty\(\)|isNotEmpty\(\)""").containsMatchIn(text))
        val tx = node.calls("txNetworkStatus", b)
        val input = node.calls("outputUnspent", b)
        assertEquals(1, tx.size)
        assertEquals(1, input.size)
        assertEquals("the txid's answer does not reach the core whole", listOf("txNetworkStatus(txid)"),
            node.calls("staleTxCode", b).single().arguments)
        assertEquals("an input's answer does not reach the core whole", listOf("outputUnspent(t, v)"),
            node.calls("staleInputCode", b).single().arguments)
        assertTrue("the loop is not bounded to one question for the txid and one per input",
            Regex("""repeat\(\s*inputs\.size\s*\+\s*2\s*\)""").containsMatchIn(text))
        assertTrue("only the core's NEVER_RELAYED may answer true",
            Regex("""STALE_STEP_NEVER_RELAYED\s*->\s*return\s+true""").containsMatchIn(text) &&
                Regex("""return\s+true""").findAll(text).count() == 1)
    }

    @Test fun `the Kotlin codes are the header's`() {
        assertTrue("BRStaleSend.h not found at ${header.path}: is the core submodule checked out?", header.isFile)
        val h = header.readText()
        val kotlin = Regex("""internal\s+const\s+val\s+(STALE_\w+)\s*=\s*(\d+)""").findAll(node.code)
            .associate { it.groupValues[1] to it.groupValues[2].toInt() }
        val names = mapOf(
            "STALE_TX_NOT_ASKED" to "BRStaleSendTxNotAsked", "STALE_TX_CONFIRMED" to "BRStaleSendTxConfirmed",
            "STALE_TX_IN_MEMPOOL" to "BRStaleSendTxInMempool", "STALE_TX_UNKNOWN" to "BRStaleSendTxUnknown",
            "STALE_TX_UNAVAILABLE" to "BRStaleSendTxUnavailable",
            "STALE_INPUT_NO_ANSWER" to "BRStaleSendInputNoAnswer", "STALE_INPUT_UNSPENT" to "BRStaleSendInputUnspent",
            "STALE_INPUT_SPENT" to "BRStaleSendInputSpent",
            "STALE_STEP_NOT_SHOWN" to "BRStaleSendNotShown", "STALE_STEP_NEVER_RELAYED" to "BRStaleSendNeverRelayed",
            "STALE_STEP_ASK_TX" to "BRStaleSendAskTx", "STALE_STEP_ASK_INPUT" to "BRStaleSendAskInput",
        )
        assertEquals("DgbNodeClient's STALE_ codes changed: update the gate", names.keys, kotlin.keys)
        for ((k, c) in names) {
            val v = Regex("""\b$c\s*=\s*(\d+)""").find(h)?.groupValues?.get(1)?.toInt()
            assertEquals("$k is not $c", v, kotlin[k])
        }
    }
}
