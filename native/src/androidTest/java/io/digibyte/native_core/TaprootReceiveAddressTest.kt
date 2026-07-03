package io.digibyte.native_core

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.digibyte.core.bridge.NativeBridge
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*

/**
 * Plan-2 Task-3 KAT: `getReceiveAddress(index, 3)` returns the real BIP86 Taproot
 * (P2TR, `dgb1p…`) receive address, and format 2 (BIP84 P2WPKH) is unchanged.
 *
 * Fixed vector: the canonical all-zeros-entropy BIP39 mnemonic
 * ("abandon…about"). The wallet JNI is mnemonic-only (BRBIP39DeriveKey → 64-byte
 * seed), so the Task-1 host-KAT seed (raw 16 bytes) is unreachable through
 * createWallet; a canonical mnemonic is substituted. Both expected addresses were
 * computed by an INDEPENDENT Python oracle (bip_utils BIP32 + from-scratch BIP341
 * key-path taptweak / bech32m) that was first cross-checked to reproduce Task-1's
 * three pinned m/86' addresses for the raw 16-byte seed before being trusted — so
 * these pins are an independent reference, not a tautological echo of the C code.
 *
 *   m/86'/20'/0'/0/0  P2TR  (dgb1p): pins that getReceiveAddress(0,3) derives over
 *                                    the BIP86 taprootPubKey, NOT the m/84' key.
 *   m/84'/20'/0'/0/0  P2WPKH(dgb1q): regression — BIP84 receive path intact.
 */
@RunWith(AndroidJUnit4::class)
class TaprootReceiveAddressTest {

    private val mnemonic =
        "abandon abandon abandon abandon abandon abandon abandon abandon " +
        "abandon abandon abandon about"

    // Independently-derived reference values (see KDoc). DigiByte: purpose 86'/84',
    // coin type 20', account 0', HMAC key "Bitcoin seed", HRP "dgb".
    private val expectedTaproot =
        "dgb1pcevt23hht82rkdrjdpwzstmqyj4ngyy42r9cu73rl4n9h5vu6hgsx5tm5q"
    private val expectedSegwit =
        "dgb1q9gmf0pv8jdymcly6lz6fl7lf6mhslsd72e2jq8"

    @Test
    fun getReceiveAddress_format3_returnsBip86TaprootAddress() {
        assertTrue("wallet should be created", NativeBridge.createWallet(mnemonic))

        val taproot = NativeBridge.getReceiveAddress(0, 3)
        assertNotNull("format 3 receive address should not be null", taproot)
        assertTrue(
            "taproot receive address should be bech32m P2TR (dgb1p…), was $taproot",
            taproot!!.startsWith("dgb1p"),
        )
        assertEquals(
            "getReceiveAddress(0,3) must match the independently-derived " +
                "m/86'/20'/0'/0/0 P2TR address",
            expectedTaproot,
            taproot,
        )
    }

    @Test
    fun getReceiveAddress_format2_bip84Unchanged() {
        assertTrue("wallet should be created", NativeBridge.createWallet(mnemonic))

        val segwit = NativeBridge.getReceiveAddress(0, 2)
        assertNotNull("format 2 receive address should not be null", segwit)
        assertEquals(
            "getReceiveAddress(0,2) must still be the m/84'/20'/0'/0/0 P2WPKH address",
            expectedSegwit,
            segwit,
        )
    }
}
