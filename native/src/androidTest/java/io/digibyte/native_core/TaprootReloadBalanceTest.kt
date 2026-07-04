package io.digibyte.native_core

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.digibyte.core.bridge.NativeBridge
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*

/**
 * Plan-2 fix-1 regression: a received P2TR (Taproot) output must survive a cold
 * restart in the wallet's balance.
 *
 * The bug: on restart, `WalletManager.restoreFromDisk()` calls
 * `recoverWalletFromBytes()` → `BRWalletNewDual(savedTxs, …)`. Inside
 * `BRWalletNewDual`, the saved transactions are bulk-loaded and
 * `_BRWalletUpdateBalance` runs while `hasTaprootKey == 0` and the taproot chains
 * are still empty — so a saved P2TR output fails
 * `BRSetContains(wallet->allAddrs, output.address)` and is dropped from
 * utxos/balance. `BRWalletSetTaprootKey` is only called AFTER `BRWalletNewDual`
 * returns; before the fix it installed the m/86' addresses but never re-ran
 * `_BRWalletUpdateBalance`, so the P2TR credit stayed missing until an unrelated
 * balance event. A resync does not recover it (the relayed-back tx is already in
 * `allTx`, so `BRWalletRegisterTransaction` early-returns without recompute).
 *
 * The fix (Option B): `BRWalletSetTaprootKey` re-runs `_BRWalletUpdateBalance`
 * after adding the taproot addresses to `allAddrs`, so the saved P2TR output is
 * credited on the recover path.
 *
 * This test drives the real reload cycle end-to-end:
 *   createWallet → register a confirmed tx paying getReceiveAddress(0,3) →
 *   getSerializedTransactions (the saved-tx blob) →
 *   loadSerializedTransactions + recoverWalletFromBytes (the restart) →
 *   assert the balance still includes the P2TR output.
 *
 * The `getBalance()` assertion after reload is the load-bearing one: RED before
 * the fix (balance omits the P2TR output), GREEN after.
 *
 * Fixed vector: the canonical all-zeros-entropy BIP39 mnemonic; the P2TR receive
 * address is the KAT-pinned m/86'/20'/0'/0/0 (see TaprootReceiveAddressTest).
 */
@RunWith(AndroidJUnit4::class)
class TaprootReloadBalanceTest {

    private val mnemonic =
        "abandon abandon abandon abandon abandon abandon abandon abandon " +
        "abandon abandon abandon about"

    private val expectedTaproot =
        "dgb1pcevt23hht82rkdrjdpwzstmqyj4ngyy42r9cu73rl4n9h5vu6hgsx5tm5q"

    // Distinctive, well above dust so the pending/dust path never applies.
    private val paidSat = 250_000_000L
    private val confirmedHeight = 20_000_000L
    private val blockTs = 1_700_000_000L

    // NOTE (teardown): we intentionally do NOT free the global saved-transaction set
    // after this test. recoverWalletFromBytes() → BRWalletNewDual() takes OWNERSHIP of
    // the g_savedTransactions BRTransaction pointers (it stores them, it does not copy),
    // so those objects are now co-owned by the recovered g_wallet. Calling
    // loadSerializedTransactions() again here would BRTransactionFree() them while
    // g_wallet still references them, and the next test's createWallet() →
    // BRWalletFree(g_wallet) would double-free → SIGSEGV. The next createWallet()'s
    // BRWalletFree reclaims the tx objects exactly once, which is the correct teardown.
    // (No other test in the suite calls loadSerializedTransactions, so the stale global
    // pointer is never freed a second time.) In production this shared ownership is benign
    // because each restore runs in a fresh process.

    @Test
    fun p2trReceive_survivesReload_inBalance() {
        assertTrue("wallet should be created", NativeBridge.createWallet(mnemonic))

        val taproot = NativeBridge.getReceiveAddress(0, 3)
        assertNotNull("format 3 receive address should not be null", taproot)
        assertEquals("sanity: receive addr is the KAT P2TR", expectedTaproot, taproot)

        val scriptPubKey = NativeBridge.addressToScriptPubKey(taproot!!)
        assertNotNull("P2TR scriptPubKey should resolve", scriptPubKey)

        // Build a confirmed transaction whose single output pays our own P2TR
        // receive address. A synthetic (but structurally valid & signed) tx is
        // sufficient — the reload path only cares about the output address.
        val rawTx = buildLegacyTxPaying(scriptPubKey!!, paidSat)

        assertTrue(
            "registerRawTransaction should accept the P2TR-paying tx",
            NativeBridge.registerRawTransaction(rawTx, confirmedHeight, blockTs),
        )

        // Sanity: the LIVE wallet credits the P2TR output (allAddrs already holds
        // the taproot chain from createWallet's BRWalletSetTaprootKey). This isolates
        // the bug to the reload path, not the live registration path.
        assertEquals(
            "live wallet balance should include the P2TR output",
            paidSat,
            NativeBridge.getBalance(),
        )

        // --- Simulate the cold restart ---
        val blob = NativeBridge.getSerializedTransactions()
        assertNotNull("serialized transactions blob should not be null", blob)

        val loaded = NativeBridge.loadSerializedTransactions(blob!!)
        assertTrue("at least one saved tx should load, was $loaded", loaded >= 1)

        val phraseBytes = mnemonic.toByteArray(Charsets.UTF_8)
        assertTrue(
            "recoverWalletFromBytes (the reload) should succeed",
            NativeBridge.recoverWalletFromBytes(phraseBytes, blockTs),
        )

        // LOAD-BEARING: after reload, the P2TR output must still be in the balance.
        // RED before the fix (BRWalletNewDual credited balance while hasTaprootKey==0
        // and never recomputed after BRWalletSetTaprootKey); GREEN after.
        assertEquals(
            "reloaded wallet balance must still include the received P2TR output",
            paidSat,
            NativeBridge.getBalance(),
        )

        // Secondary: the taproot address is (re)installed in the watch set.
        assertTrue(
            "reloaded wallet should still contain the P2TR receive address",
            NativeBridge.walletContainsAddress(taproot),
        )
    }

    /**
     * Build a minimal legacy (non-witness) DigiByte transaction with one input and
     * one output paying [scriptPubKey] [amountSat] satoshis. The input carries a
     * P2PKH-style scriptSig (`<push72><72><push33><33>`) which is NOT a valid
     * scriptPubKey, so BRTransactionParse stores it as the input signature (and sets
     * an empty witness), making BRTransactionIsSigned() return true — the gate that
     * both registerRawTransaction and BRWalletNewDual's bulk-load require.
     */
    private fun buildLegacyTxPaying(scriptPubKey: ByteArray, amountSat: Long): ByteArray {
        val out = ArrayList<Byte>()
        fun u32le(v: Long) { for (i in 0..3) out.add(((v shr (8 * i)) and 0xff).toByte()) }
        fun u64le(v: Long) { for (i in 0..7) out.add(((v shr (8 * i)) and 0xff).toByte()) }

        u32le(1L)                                   // version
        out.add(0x01)                               // vin count = 1
        for (i in 0 until 32) out.add(0x11.toByte()) // prevout hash (non-zero)
        u32le(0L)                                    // prevout index

        val scriptSig = ArrayList<Byte>()
        scriptSig.add(0x48.toByte())                 // push 72 bytes (dummy DER sig)
        for (i in 0 until 72) scriptSig.add(0x30.toByte())
        scriptSig.add(0x21.toByte())                 // push 33 bytes (dummy pubkey)
        scriptSig.add(0x02.toByte())
        for (i in 0 until 32) scriptSig.add(0xAB.toByte())
        require(scriptSig.size < 0xfd) { "scriptSig too long for 1-byte varint" }
        out.add(scriptSig.size.toByte())             // scriptSig len (varint)
        out.addAll(scriptSig)
        u32le(0xffffffffL)                           // sequence

        out.add(0x01)                                // vout count = 1
        u64le(amountSat)                             // value
        require(scriptPubKey.size < 0xfd) { "scriptPubKey too long for 1-byte varint" }
        out.add(scriptPubKey.size.toByte())          // scriptPubKey len (varint)
        for (b in scriptPubKey) out.add(b)

        u32le(0L)                                    // locktime
        return out.toByteArray()
    }
}
