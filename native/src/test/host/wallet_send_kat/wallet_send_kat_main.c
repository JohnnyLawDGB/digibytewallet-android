// Host KAT for BRWalletSend.h: the own-send registration rule and the publish verdict ring.
//
// Built twice by run.sh:
//   -DWALLET_SEND_SAME_OBJECT_UNFIXED -DPUBLISH_RING_RECORDS_EALREADY_UNFIXED
//       the wallet registers the manager's own object, and EALREADY overwrites a verdict:
//       every RED case fails
//   (default) the shipped rules: every case passes

#include "BRWalletSend.h"
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

    for (int i = 1; i <= BR_PUBLISH_RING_SIZE; i++) {   // 32 more distinct hashes push `a` out
        UInt256 h = UINT256_ZERO;
        h.u8[1] = (uint8_t)i; h.u8[2] = 0x5a;
        BRPublishRingRecord(&ring, h, 0);
    }
    result("oldest_ages_out_to_pending", BRPublishRingLookup(&ring, a) == BR_PUBLISH_PENDING);

    BRWalletFree(w);
    memset(&seed, 0, sizeof(seed));
    if (! g_failed) printf("ALL PASS\n");
    return g_failed ? 1 : 0;
}
