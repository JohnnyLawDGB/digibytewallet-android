package io.digibyte.native_core

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.digibyte.core.bridge.NativeBridge
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*

/**
 * Plan-2 Task-4: taproot addresses are in the wallet's watch set (allAddrs /
 * bloom + BIP158 filter source) and survive the address-chain realloc.
 *
 * The watch set that actually leaves the device is enumerated by
 * `BRWalletAllAddrs` (surfaced here through `dumpAllAddresses()`), NOT the
 * internal `allAddrs` BRSet. Before the Task-4 fix, `BRWalletAllAddrs` omits the
 * two taproot chains, so a received P2TR output is not matched by the filter
 * even though `BRWalletContainsAddress` (internal set, populated by Task-3)
 * reports it. These tests therefore gate on `dumpAllAddresses()` — that is the
 * assertion that is RED before the enumeration fix and GREEN after.
 *
 * Fixed vector: canonical all-zeros-entropy BIP39 mnemonic. The P2TR receive
 * address is the KAT-pinned m/86'/20'/0'/0/0 dgb1p… (see TaprootReceiveAddressTest).
 */
@RunWith(AndroidJUnit4::class)
class TaprootWatchSetTest {

    private val mnemonic =
        "abandon abandon abandon abandon abandon abandon abandon abandon " +
        "abandon abandon abandon about"

    private val expectedTaproot =
        "dgb1pcevt23hht82rkdrjdpwzstmqyj4ngyy42r9cu73rl4n9h5vu6hgsx5tm5q"

    /** Taproot receive address is emitted into BRWalletAllAddrs (the filter source). */
    @Test
    fun taprootReceiveAddress_isInWatchSet() {
        assertTrue("wallet should be created", NativeBridge.createWalletFromBytes(mnemonic.toByteArray(), null))

        val taproot = NativeBridge.getReceiveAddress(0, 3)
        assertNotNull("format 3 receive address should not be null", taproot)
        assertEquals("sanity: receive addr is the KAT P2TR", expectedTaproot, taproot)

        // The load-bearing assertion: the taproot receive address is enumerated by
        // BRWalletAllAddrs, so bloom/BIP158 will watch it. RED before the fix.
        val dumped = NativeBridge.dumpAllAddresses().split("\n").filter { it.isNotBlank() }
        assertTrue(
            "taproot receive address must be in BRWalletAllAddrs (the watch/filter set)",
            dumped.contains(taproot),
        )

        // Secondary: internal allAddrs set membership (populated by Task-3).
        assertTrue(
            "walletContainsAddress should report the taproot receive address",
            NativeBridge.walletContainsAddress(taproot!!),
        )
    }

    /**
     * Realloc regression: BRWalletSetTaprootKey pre-generates the gap+100 taproot
     * window (110 external + 105 internal = 215 addrs), which forces the taproot
     * chain arrays (initial capacity 50) to grow/relocate several times. Re-request
     * the receive address at rising indices (exercises the receive path repeatedly)
     * and assert the taproot window survived the reallocs intact in the watch set —
     * guards the BRWalletAllAddrs partition math and the Task-3 rebuild-loop retention.
     */
    @Test
    fun taprootWindow_survivesChainRealloc() {
        assertTrue("wallet should be created", NativeBridge.createWalletFromBytes(mnemonic.toByteArray(), null))

        val addr0 = NativeBridge.getReceiveAddress(0, 3)
        assertNotNull(addr0)

        // Re-request across rising indices; the receive path re-runs the taproot
        // gap generation each call. (The chain already grew past its cap-50 initial
        // capacity during BRWalletSetTaprootKey's gap+100 pre-gen.)
        for (i in 0..60) {
            NativeBridge.getReceiveAddress(i, 3)
        }

        val dumped = NativeBridge.dumpAllAddresses().split("\n").filter { it.isNotBlank() }
        val taprootAddrs = dumped.filter { it.startsWith("dgb1p") }

        // > 50 taproot addresses were generated -> the array reallocated; all survive.
        assertTrue(
            "taproot chain should have grown well past initial capacity (was ${taprootAddrs.size})",
            taprootAddrs.size > 50,
        )
        // The earlier (index-0) taproot address is STILL contained after the growth.
        assertTrue(
            "index-0 taproot address must survive the chain realloc in the watch set",
            dumped.contains(addr0),
        )
        assertTrue(
            "walletContainsAddress should still report the index-0 taproot address",
            NativeBridge.walletContainsAddress(addr0!!),
        )
    }
}
