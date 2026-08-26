package io.digibyte.core.reconcile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `DgbNodeClient` logs the request URL on every failure path — seven of them. One of the routes
 * it builds is `/rpc/address-history/<address>`, so a network blip used to write one of the
 * user's addresses into logcat. On a wallet whose roadmap is sovereignty-first, and which
 * already redacts the wallet address from Digi-ID logs for exactly this reason, that was the
 * wrong default. Found in the v4.0.58 security cycle (finding 7).
 *
 * Reading logcat needs `READ_LOGS` or ADB, so this is low severity — but the fix costs one
 * function and the leak is of the single most linkable thing the app holds.
 *
 * The redaction has to stay *useful*: a log line that says only "a request failed" makes the
 * next outage harder to diagnose than the leak was worth. So the tests below pin both halves —
 * identifiers must go, and host plus route shape must stay.
 */
class DgbNodeClientRedactionTest {

    private val addr = "DQTjL9vfXVbMfCgbmoZDdMBxNs2Dqmy7yD"
    private val txid = "9f1c2e5a7b3d4e6f8a0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e2f"

    // ---- what must disappear ---------------------------------------------------------------

    @Test fun `an address never survives redaction`() {
        val out = DgbNodeClient.redactUrl("https://api.digiscope.me/api/rpc/address-history/$addr")
        assertFalse("the address is still in the log line: $out", out.contains(addr))
    }

    @Test fun `a txid never survives redaction`() {
        val out = DgbNodeClient.redactUrl("https://api.digiscope.me/api/explorer/tx/$txid")
        assertFalse("the txid is still in the log line: $out", out.contains(txid))
    }

    @Test fun `a query string is dropped whole`() {
        val out = DgbNodeClient.redactUrl("https://api.digiscope.me/api/explorer/tx?id=$txid&x=1")
        assertFalse(out.contains(txid))
        assertFalse("query values must not be enumerated either", out.contains("x=1"))
    }

    // ---- what must survive, or the log line is useless --------------------------------------

    @Test fun `the host and route shape are kept`() {
        val out = DgbNodeClient.redactUrl("https://api.digiscope.me/api/rpc/address-history/$addr")
        assertTrue("host lost: $out", out.contains("api.digiscope.me"))
        assertTrue("route lost: $out", out.contains("rpc/address-history"))
    }

    /**
     * A redactor that blanks everything would pass every assertion above. This is the check
     * that it is discriminating rather than merely destructive.
     */
    @Test fun `a url carrying no identifier is unchanged`() {
        val plain = "https://api.digiscope.me/api/wallet/reconcile"
        assertEquals(plain, DgbNodeClient.redactUrl(plain))
    }

    @Test fun `a custom node endpoint stays readable`() {
        // Own-node users are the ones most likely to be debugging their own setup, so the
        // host and port must survive — that is the whole diagnostic value for them.
        val out = DgbNodeClient.redactUrl("http://192.168.1.50:12024/api/wallet/reconcile")
        assertTrue("host lost: $out", out.contains("192.168.1.50"))
        assertTrue("port lost: $out", out.contains("12024"))
        assertTrue("route lost: $out", out.contains("wallet/reconcile"))
    }

    // ---- robustness: this runs on a failure path, so it must never add a failure ------------

    @Test fun `garbage in does not throw`() {
        DgbNodeClient.redactUrl("")
        DgbNodeClient.redactUrl("not a url")
        DgbNodeClient.redactUrl("://///")
        DgbNodeClient.redactUrl("dgbnode://host/path")
    }

    @Test fun `an unparseable url still reveals nothing`() {
        val out = DgbNodeClient.redactUrl("not a url but here is $addr")
        assertFalse("unparseable input leaked its contents: $out", out.contains(addr))
    }
}
