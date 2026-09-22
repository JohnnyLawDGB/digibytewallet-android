package io.digibyte.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reader the source gates stand on. A gate is only as good as what it can tell apart, so each
 * distinction the gates rely on is shown here on a few lines of text: code from comment and from
 * literal, a whole argument from part of one, a whole condition from part of one.
 */
class KotlinSourceGateTest {

    private fun gate(text: String) = KotlinSourceGate.of(text.trimIndent())

    @Test fun `comments are not code, and every line stays where it was`() {
        val text = """
            val a = f(1) // g(2)
            /* h(3)
               /* nested i(4) */ still a comment j(5)
            */ val b = k(6)
        """.trimIndent()
        val gate = KotlinSourceGate.of(text)
        assertEquals(text.length, gate.code.length)
        assertEquals(text.lines().size, gate.code.lines().size)
        for (gone in listOf("g(", "h(", "i(", "j(", "nested", "comment")) {
            assertFalse("`$gone` is still read as code", gate.code.contains(gone))
        }
        assertEquals(1, gate.calls("f").size)
        assertEquals(1, gate.calls("k").size)
        assertEquals("val b = k(6)", gate.code.lines().last().trim())
    }

    @Test fun `the text of a literal is not code, and what a template runs is`() {
        val dollar = "$"
        val gate = gate(
            """
            val s = "send(1) // not a comment" + run(2)
            val t = "0.$dollar{"0".repeat(decimals)} and ${dollar}name and \" send(3) \\"
            val raw = ${"\"\"\""}send(4) " "" $dollar{inRaw(5)}${"\"\"\""} + after(6)
            val c = '"'; val d = '\''; val e = '\u0041'; tail(7)
            """
        )
        assertTrue("a literal's text is read as code", gate.calls("send").isEmpty())
        for (kept in listOf("run", "repeat", "inRaw", "after", "tail")) {
            assertEquals("`$kept(` is code and was not read", 1, gate.calls(kept).size)
        }
        assertTrue("a simple template names what it reads", gate.code.contains("${dollar}name"))
        assertEquals(
            "a quote mark inside a character literal is that character, not the start of a string",
            "val c = ' '; val d = '  '; val e = '      '; tail(7)",
            gate.code.lines().last(),
        )
    }

    @Test fun `a call is read whole - its arguments are cut at its own commas only`() {
        val gate = gate(
            """
            fun send(a: Int) = Unit
            val r = manager . send (
                assetId = approved.assetId,
                quantity   =
                    approved.units /* whole units */ ,
                note = listOf(1, 2).joinToString(", ") { it.toString() },
                same == other,
            )
            """
        )
        val call = gate.calls("manager.send").single()
        assertEquals(4, call.arguments.size)
        assertEquals(
            mapOf(
                "assetId" to "approved.assetId",
                "quantity" to "approved.units",
                "note" to "listOf(1, 2).joinToString(\", \") { it.toString() }",
            ),
            call.named,
        )
        assertEquals("same == other", call.arguments.last())
        assertEquals("a declaration is not a call", 1, gate.calls("send").size)
        assertEquals("approved.units", gate.code.substring(call.valueRange("quantity")!!).trim())
        assertNull(call.valueRange("same"))
    }

    @Test fun `part of an argument is not the argument`() {
        val call = gate("val r = m.send(quantity = approved.units * 10, to = approved.address.reversed())").calls("m.send").single()
        assertEquals("approved.units * 10", call.named["quantity"])
        assertEquals("approved.address.reversed()", call.named["to"])
    }

    @Test fun `a longer receiver is still a call, a longer name is not`() {
        val gate = gate("this.viewModel.send(a); viewModel.sendAll(b); myviewModel.send(c)")
        assertEquals(listOf(listOf("a")), gate.calls("viewModel.send").map { it.arguments })
    }

    @Test fun `what stands under an answer - the branch of a bare condition`() {
        val gate = gate(
            """
            launch {
                if (auth.authorize(activity, title("x"))) {
                    doIt()
                } else {
                    cancel()
                }
                after()
            }
            """
        )
        val under = gate.guardedBy(gate.calls("auth.authorize").single())
        assertNotNull(under)
        val guarded = gate.code.substring(under!!)
        assertTrue(guarded.contains("doIt()"))
        assertFalse(guarded.contains("cancel()") || guarded.contains("after()"))
    }

    @Test fun `what stands under an answer - one statement, and no further`() {
        val gate = gate("fun gated(action: () -> Unit) { launch { if (auth.authorize(a)) action(); other() } }")
        val guarded = gate.code.substring(gate.guardedBy(gate.calls("auth.authorize").single())!!)
        assertEquals("action()", guarded.trim())
        val withElse = gate("if (auth.authorize(a)) doIt() else cancel()")
        assertEquals("doIt()", withElse.code.substring(withElse.guardedBy(withElse.calls("auth.authorize").single())!!).trim())
    }

    @Test fun `what stands under an answer - the rest of the block after leaving on false`() {
        val gate = gate(
            """
            outer {
                launch {
                    if (!auth.authorize(a)) return@launch   // denied
                    doIt()
                }
                outside()
            }
            """
        )
        val guarded = gate.code.substring(gate.guardedBy(gate.calls("auth.authorize").single())!!)
        assertTrue(guarded.contains("doIt()"))
        assertFalse(guarded.contains("outside()"))

        val tidiesUpFirst = gate("launch { if (!auth.authorize(a)) { cancel(); return@launch }\n doIt() }")
        val after = tidiesUpFirst.code.substring(tidiesUpFirst.guardedBy(tidiesUpFirst.calls("auth.authorize").single())!!)
        assertTrue(after.contains("doIt()"))
        assertFalse("what the branch does on false does not stand under a true answer", after.contains("cancel()"))
    }

    @Test fun `what stands under an answer - held in a value that is named once more`() {
        val gate = gate(
            """
            launch {
                val authed = auth.authorize(a)
                if (!authed) return@launch
                doIt()
            }
            """
        )
        assertTrue(gate.code.substring(gate.guardedBy(gate.calls("auth.authorize").single())!!).contains("doIt()"))
    }

    @Test fun `an answer that is only part of the condition guards nothing`() {
        val notTheWholeCondition = listOf(
            "if (auth.authorize(a) || true) { doIt() }",
            "if (true || auth.authorize(a)) { doIt() }",
            "if ((auth.authorize(a)) || true) { doIt() }",
            "if (auth.authorize(a).not()) { doIt() }",
            "if (!!auth.authorize(a)) { doIt() }",
            "if (!auth.authorize(a)) log() \n doIt()",
            "if (!auth.authorize(a)) { if (busy) return } \n doIt()",
            "if (!auth.authorize(a)) { return; log() } \n doIt()",
            "if (!auth.authorize(a) && false) return \n doIt()",
            "val ok = auth.authorize(a) || true \n if (ok) { doIt() }",
            "val ok = auth.authorize(a) \n if (ok || true) { doIt() }",
            "val ok = auth.authorize(a) \n if (ok) { doIt() } \n if (!ok) { doIt() }",
            "auth.authorize(a) \n doIt()",
            "when (auth.authorize(a)) { else -> doIt() }",
        )
        for (text in notTheWholeCondition) {
            val gate = gate(text)
            assertNull(text, gate.guardedBy(gate.calls("auth.authorize").single()))
        }
    }

    @Test fun `a block is found from what stands in it, and from the call it follows`() {
        val gate = gate(
            """
            Field(onChange = { text = it; touched() }, label = { Text("{") })
            Effect(key, other) {
                value?.let { text = it }
            }
            """
        )
        val write = gate.code.indexOf("text = it")
        assertEquals("{ text = it; touched() }", gate.code.substring(gate.enclosingBlock(write)!!))
        val effect = gate.calls("Effect").single()
        assertEquals(listOf("key", "other"), effect.arguments)
        val body = gate.trailingBlock(effect)!!
        assertTrue(gate.code.substring(body).contains("value?.let"))
        assertEquals("{ text = it }", gate.code.substring(gate.enclosingBlock(gate.code.lastIndexOf("text = it"))!!))
        assertNull(gate.trailingBlock(gate.calls("touched").single()))
        assertNull(gate.enclosingBlock(0))
        assertTrue("a literal is kept as it is written, for the gates that pin one", gate.written.contains("Text(\"{\")"))
    }

    @Test fun `a condition is matched whole, however it is spaced`() {
        val gate = gate(
            """
            if (a(x) != b.c) { return }
            if ( a(x)   !=   b.c ) return
            if (a(x) != b.c && false) { return }
            if (!(a(x) != b.c)) { return }
            val s = "if (a(x) != b.c) { return }"
            """
        )
        assertEquals(2, gate.branchesOn("a(x) != b.c").size)
    }
}
