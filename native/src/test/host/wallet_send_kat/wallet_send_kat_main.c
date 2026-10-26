// Host KAT for BRWalletSend.h: the own-send registration rule and the publish verdict ring.
//
// Built twice by run.sh:
//   -DWALLET_SEND_SAME_OBJECT_UNFIXED -DPUBLISH_RING_RECORDS_EALREADY_UNFIXED
//       the wallet registers the manager's own object, and EALREADY overwrites a verdict:
//       every RED case fails
//   (default) the shipped rules: every case passes

#include "BRTransaction.h"
#include "BRWallet.h"

// Free-count seam: every BRTransactionFree the header itself makes is counted, so "the copy
// nobody took is released" is checked deterministically (the KAT runs with detect_leaks=0, and
// macOS has no LeakSanitizer). Only BRWalletSend.h sees the macro; it is undefined below.
static int g_headerFrees = 0;
static void kat_counted_free(BRTransaction *tx) { g_headerFrees++; BRTransactionFree(tx); }
#define BRTransactionFree kat_counted_free
#include "BRWalletSend.h"
#undef BRTransactionFree

#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"
#include "BRAddress.h"
#include <stdio.h>
#include <string.h>

static int g_failed = 0;
static void result(const char *name, int pass)
{
    if (! pass) { printf("FAIL: %s\n", name); g_failed = 1; }
    printf("RESULT %s %s\n", name, pass ? "pass" : "fail");
}

// A "signed" transaction spending (prev, index) to script, parsed back in the network's signed
// format so it carries its txHash. The signature bytes are not a valid signature.
static BRTransaction *signed_tx(UInt256 prev, uint32_t index, const uint8_t *script, size_t scriptLen,
                                uint64_t amount)
{
    BRTransaction *tx = BRTransactionNew(), *parsed;
    uint8_t sig[2] = { 0x01, 0x51 }, witness[1] = { 0x00 }, buf[512];
    size_t len;

    BRTransactionAddInput(tx, prev, index, 0, NULL, 0, sig, sizeof(sig), witness, sizeof(witness), 0xffffffffu);
    BRTransactionAddOutput(tx, amount, script, scriptLen);
    len = BRTransactionSerialize(tx, buf, sizeof(buf));
    BRTransactionFree(tx);
    parsed = BRTransactionParseSigned(buf, len);
    parsed->timestamp = 0;
    return parsed;
}

// A "signed" receive to addr (a non-empty script and witness on its one foreign input),
// parsed back so it carries its txHash, as a transaction from the network does.
static BRTransaction *receive_to(const char *addr, uint64_t amount, uint8_t salt)
{
    BRTransaction *tx = BRTransactionNew(), *parsed;
    UInt256 prev = UINT256_ZERO;
    uint8_t sig[2] = { 0x01, 0x51 }, witness[1] = { 0x00 }, script[64], buf[512];
    size_t scriptLen = BRAddressScriptPubKey(script, sizeof(script), addr), len;

    prev.u8[0] = salt;
    BRTransactionAddInput(tx, prev, 0, 0, NULL, 0, sig, sizeof(sig), witness, sizeof(witness), 0xffffffffu);
    BRTransactionAddOutput(tx, amount, script, scriptLen);
    len = BRTransactionSerialize(tx, buf, sizeof(buf));
    BRTransactionFree(tx);
    parsed = BRTransactionParse(buf, len);
    parsed->timestamp = 0;
    return parsed;
}

int main(void)
{
    UInt512 seed = UINT512_ZERO;
    BRBIP39DeriveKey(seed.u8, "abandon abandon abandon abandon abandon abandon abandon abandon "
                              "abandon abandon abandon about", NULL);
    BRWallet *w = BRWalletNew(NULL, 0, BRBIP32MasterPubKeyBIP84(seed.u8, sizeof(seed)));
    BRAddress addr = BRWalletReceiveAddress(w, 1);

    // ---- 1. own-send registration ---------------------------------------------------------
    BRTransaction *send = receive_to(addr.s, 1000000, 0x11);
    UInt256 hash = send->txHash;
    BRWalletRegisterOwnSend(w, send);
    const BRTransaction *record = BRWalletTransactionForHash(w, hash);

    result("copy_not_same_object", record != NULL && record != send);                 // RED
    result("copy_registered", record != NULL);                                          // GUARD
    result("timestamp_set_before_register", send->timestamp != 0);                      // GUARD
    if (record != send) BRTransactionFree(send);   // the manager releasing ITS object: must be safe
    result("original_free_is_safe", BRWalletTransactionForHash(w, hash) == record);     // GUARD

    // A re-publish: a fresh object with the same hash. The wallet keeps its record and takes
    // nothing; the fresh object stays the caller's.
    BRTransaction *again = receive_to(addr.s, 1000000, 0x11);
    BRWalletRegisterOwnSend(w, again);
    result("republish_keeps_the_record", BRWalletTransactionForHash(w, hash) == record &&
                                         BRWalletTransactionForHash(w, hash) != again); // GUARD
    BRTransactionFree(again);

    // The wallet's RECORD carries the timestamp, not only the caller's object: the copy is
    // taken after the timestamp is set (the wallet orders its records by it).
    result("record_timestamp_set", record != NULL && record->timestamp != 0);             // GUARD

    // A send spending a wallet coin. The signature is not valid, so only a TRUSTED registration
    // (this wallet signed it; Android's _registerWalletCopy uses BRWalletRegisterTransactionTrusted)
    // takes it: a plain BRWalletRegisterTransaction checks the signature on the wallet coin and
    // refuses it, and the send would be missing from BRWalletTransactions().
    {
        uint8_t foreign[22] = { 0x00, 0x14 };   // OP_0 PUSH20: a P2WPKH script no wallet key owns
        memset(foreign + 2, 0x77, 20);
        BRTransaction *spend = signed_tx(hash, 0, foreign, sizeof(foreign), 400000);
        UInt256 spendHash = spend->txHash;
        int frees = g_headerFrees;

        BRWalletRegisterOwnSend(w, spend);
        const BRTransaction *spendRecord = BRWalletTransactionForHash(w, spendHash);
        result("wallet_spend_registers_trusted", spendRecord != NULL && g_headerFrees == frees); // GUARD
        if (spendRecord != spend) BRTransactionFree(spend);

        // A confirmed transaction that touches no wallet address: the wallet keeps no record of
        // it, so the copy registered for it has no owner and the header releases it (exactly one
        // free). The caller's object is untouched and still the caller's.
        UInt256 elsewhere = UINT256_ZERO;
        elsewhere.u8[0] = 0x33;
        BRTransaction *other = signed_tx(elsewhere, 1, foreign, sizeof(foreign), 5000);
        other->blockHeight = 100;   // confirmed: not kept as a foreign unconfirmed tx either
        frees = g_headerFrees;
        BRWalletRegisterOwnSend(w, other);
        result("nonwallet_copy_released", BRWalletTransactionForHash(w, other->txHash) == NULL &&
                                          g_headerFrees == frees + 1);                   // RED
        BRTransactionFree(other);
    }

    // ---- 2. the verdict ring --------------------------------------------------------------
    BRPublishRing ring;
    memset(&ring, 0, sizeof(ring));
    UInt256 a = UINT256_ZERO, b = UINT256_ZERO;
    a.u8[0] = 0xaa; b.u8[0] = 0xbb;

    result("unknown_reads_pending", BRPublishRingLookup(&ring, a) == BR_PUBLISH_PENDING);
    BRPublishRingRecord(&ring, a, ETIMEDOUT);
    result("verdict_recorded", BRPublishRingLookup(&ring, a) == ETIMEDOUT);
    BRPublishRingRecord(&ring, a, 0);
    result("republish_replaces_its_verdict", BRPublishRingLookup(&ring, a) == 0);
    BRPublishRingRecord(&ring, a, EALREADY);
    result("ealready_never_overwrites", BRPublishRingLookup(&ring, a) == 0);           // RED
    result("other_hash_still_pending", BRPublishRingLookup(&ring, b) == BR_PUBLISH_PENDING);

    // ECANCELED (the wallet removed the transaction while its publish was pending) is a verdict:
    // Android's _publishResult records every error except EALREADY.
    BRPublishRingRecord(&ring, b, ECANCELED);
    result("ecanceled_recorded", BRPublishRingLookup(&ring, b) == ECANCELED);              // GUARD

    for (int i = 1; i <= BR_PUBLISH_RING_SIZE; i++) {   // 32 more distinct hashes push `a` out
        UInt256 h = UINT256_ZERO;
        h.u8[1] = (uint8_t)i; h.u8[2] = 0x5a;
        BRPublishRingRecord(&ring, h, 0);
    }
    result("oldest_ages_out_to_pending", BRPublishRingLookup(&ring, a) == BR_PUBLISH_PENDING);

    // Retention, against Android's literal PUBLISH_RESULT_MAX = 32 (jni_transaction.c), not the
    // macro: an entry survives 31 later publishes of other hashes and is gone after the 32nd.
    {
        BRPublishRing fresh;
        UInt256 first = UINT256_ZERO;
        int survived = 1;

        memset(&fresh, 0, sizeof(fresh));
        first.u8[0] = 0xcc;
        BRPublishRingRecord(&fresh, first, ENOTCONN);
        for (int i = 1; i <= 32; i++) {
            UInt256 h = UINT256_ZERO;
            h.u8[1] = (uint8_t)i; h.u8[2] = 0xc3;
            BRPublishRingRecord(&fresh, h, 0);
            if (i <= 31 && BRPublishRingLookup(&fresh, first) != ENOTCONN) survived = 0;
        }
        result("ring_keeps_32", survived);                                                  // GUARD
        result("ring_drops_after_32", BRPublishRingLookup(&fresh, first) == BR_PUBLISH_PENDING); // GUARD
    }

    BRWalletFree(w);
    memset(&seed, 0, sizeof(seed));
    if (! g_failed) printf("ALL PASS\n");
    return g_failed ? 1 : 0;
}
