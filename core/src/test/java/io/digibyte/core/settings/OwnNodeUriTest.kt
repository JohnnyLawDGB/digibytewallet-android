package io.digibyte.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OwnNodeUriTest {
    @Test fun parsesSchemeHostPort() {
        val u = OwnNodeUri.parse("dgbnode://10.0.0.5:12024", 12024)!!
        assertEquals(CustomNode("10.0.0.5", 12024), u.node); assertNull(u.label); assertNull(u.net)
    }
    @Test fun defaultsPortWhenAbsent() {
        assertEquals(CustomNode("node.example.com", 12024), OwnNodeUri.parse("dgbnode://node.example.com", 12024)!!.node)
    }
    @Test fun capturesNetAndLabel() {
        val u = OwnNodeUri.parse("dgbnode://10.0.0.5:12024?net=mainnet&label=My%20Node", 12024)!!
        assertEquals("mainnet", u.net); assertEquals("My Node", u.label)
    }
    @Test fun rawHostPortFallsThroughToCustomNode() {   // non-scheme input still works (manual field)
        assertEquals(CustomNode("10.0.0.5", 12024), OwnNodeUri.parse("10.0.0.5:12024", 12024)!!.node)
    }
    @Test fun rejectsOnionForNow() { assertNull(OwnNodeUri.parse("dgbnode://abcd.onion:12024", 12024)) }
    @Test fun rejectsBadPort() { assertNull(OwnNodeUri.parse("dgbnode://host:70000", 12024)) }
    @Test fun labelSanitizedAndCapped() {
        val u = OwnNodeUri.parse("dgbnode://h:1?label=" + "x".repeat(80), 12024)!!
        assertEquals(32, u.label!!.length)
    }
    @Test fun garbageIsNull() { assertNull(OwnNodeUri.parse("￿ not a uri  ", 12024)) }
    @Test fun blankIsNull() { assertNull(OwnNodeUri.parse("   ", 12024)) }
}
