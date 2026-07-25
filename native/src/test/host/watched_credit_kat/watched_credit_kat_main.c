// Host KAT: a watched address must be CREDITABLE, not merely matchable.
//
// THE BUG: BRWalletAllAddrs appends wallet->watchedAddrs, so a pinned Receive address
// becomes a BIP158 filter element and a payment to it DOES get its block downloaded. But
// the credit side never consults watchedAddrs — _BRWalletContainsTx (BRWallet.c:155/168)
// and _BRWalletUpdateBalance (:260) both gate on the allAddrs BRSet, which by explicit
// design (BRWallet.c:66) never contains watched entries. So the block is fetched and the
// transaction is then thrown away. Silently.
//
// WHY IT ISN'T FIXED BY WIDENING THE CREDIT GATE: BRWalletSignTransaction resolves an
// address to a key INDEX by scanning the derived chain arrays and never looks at
// watchedAddrs. Crediting a watch-only address would create balance the wallet can see,
// select for spending, and then fail to sign. The invariant is: credit iff derived.
//
// THE FIX: BRWalletAddWatchedAddress now tries to bring the address into the DERIVED set by
// extending whichever chain it belongs to (bounded by WATCH_RESOLVE_MAX_SPAN). Within that
// span a watched address becomes fully creditable and signable; beyond it, it stays
// watch-only and a payment to it is still not credited — which this KAT also pins, so the
// remaining limitation is documented rather than assumed away.
//
// Exit code 0 = all checks passed, 1 = check failed / ASan fault.
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <stdint.h>
#include "BRWallet.h"
#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"
#include "BRAddress.h"
#include "BRKey.h"

static int g_fail = 0;
static void check(int c, const char *d){ printf(c?"PASS: %s\n":"FAIL: %s\n", d); if(!c) g_fail++; }

static const char *kMnemonic =
    "abandon abandon abandon abandon abandon abandon abandon abandon "
    "abandon abandon abandon about";

static const uint8_t kPlaceholder[1] = {0};

static void finalizeTxHash(BRTransaction *tx) {
    uint8_t data[BRTransactionSerialize(tx, NULL, 0)];
    size_t len = BRTransactionSerialize(tx, data, sizeof(data));
    BRTransaction *t = BRTransactionParse(data, len);
    if (t) { tx->txHash = t->txHash; tx->wtxHash = t->wtxHash; BRTransactionFree(t); }
}

// the P2WPKH external address at `idx`, derived independently of the wallet's chains
static BRAddress segwitAddrAt(BRMasterPubKey mpk, uint32_t idx) {
    uint8_t pub[BRBIP32PubKey(NULL, 0, mpk, SEQUENCE_EXTERNAL_CHAIN, idx)];
    size_t len = BRBIP32PubKey(pub, sizeof(pub), mpk, SEQUENCE_EXTERNAL_CHAIN, idx);
    BRKey key; BRAddress a = BR_ADDRESS_NONE;
    if (BRKeySetPubKey(&key, pub, len)) BRKeySegwitAddress(&key, a.s, sizeof(a), OP_0);
    return a;
}

// a confirmed tx paying `amount` to `addr`
static BRTransaction *payTo(const char *addr, uint64_t amount, uint8_t seedByte) {
    uint8_t spk[64];
    size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), addr);
    if (spkLen == 0) return NULL;
    UInt256 prev; memset(prev.u8, seedByte, 32);
    BRTransaction *tx = BRTransactionNew();
    BRTransactionAddInput(tx, prev, 0, 0, spk, spkLen, kPlaceholder, 0, kPlaceholder, 0, 0xffffffff);
    BRTransactionAddOutput(tx, amount, spk, spkLen);
    finalizeTxHash(tx);
    tx->blockHeight = 700000;   // confirmed: keep the dust/pending gate out of this test
    tx->timestamp = 1784980000;
    return tx;
}

int main(void) {
    uint8_t seed[64]; BRBIP39DeriveKey(seed, kMnemonic, NULL);
    BRMasterPubKey mpk84 = BRBIP32MasterPubKeyBIP84(seed, sizeof(seed));
    BRWallet *w = BRWalletNew(NULL, 0, mpk84);
    check(w != NULL, "wallet created"); if (!w) { printf("\nFATAL\n"); return 1; }

    const uint64_t AMT = 500000000ULL; // 5 DGB, well above dust

    // ---- an address INSIDE the resolvable span --------------------------------------
    // index 120 is past the gap+100 window pre-generated at wallet creation, so it is not
    // derived yet — the case watchedAddrs exists for.
    BRAddress near = segwitAddrAt(mpk84, 120);
    check(! BRAddressEq(&near, &BR_ADDRESS_NONE), "derived a near (idx 120) address");
    check(BRWalletContainsAddress(w, near.s) == 0, "idx 120 not known before watching");

    BRWalletAddWatchedAddress(w, near.s);
    check(BRWalletContainsAddress(w, near.s) == 1, "idx 120 known after watching");

    {
        BRTransaction *tx = payTo(near.s, AMT, 0x11);
        check(tx != NULL, "built a payment to the watched address");
        uint64_t before = BRWalletBalance(w);
        check(BRWalletRegisterTransaction(w, tx) != 0,
              "payment to a watched address is RECOGNISED as ours");
        check(BRWalletBalance(w) == before + AMT,
              "payment to a watched address is CREDITED (not just matched)");
        check(BRWalletContainsTransaction(w, tx) == 1, "tx is contained by the wallet");
    }

    // ---- and it is SIGNABLE, which is why crediting it is safe -----------------------
    // A coin the wallet credits but cannot sign for would be unspendable balance. The fix
    // brings the address into the derived chains precisely so this holds.
    {
        BRAddress dest = segwitAddrAt(mpk84, 3);
        BRTransaction *spend = BRWalletCreateTransaction(w, AMT / 2, dest.s);
        check(spend != NULL, "wallet can build a spend using the credited coin");
        if (spend) {
            check(BRWalletSignTransaction(w, spend, 0, seed, sizeof(seed)) != 0,
                  "wallet can SIGN a spend of a formerly-watched address (no unspendable balance)");
            BRTransactionFree(spend);
        }
    }

    // ---- an address BEYOND the resolvable span ---------------------------------------
    // Documents the remaining limitation honestly: still matched, still not credited.
    {
        BRAddress far = segwitAddrAt(mpk84, 9000);
        BRWalletAddWatchedAddress(w, far.s);
        check(BRWalletContainsAddress(w, far.s) == 1,
              "a far address is still WATCHED (stays in the compact-filter match set)");

        BRTransaction *tx = payTo(far.s, AMT, 0x22);
        uint64_t before = BRWalletBalance(w);
        BRWalletRegisterTransaction(w, tx);
        check(BRWalletBalance(w) == before,
              "a far watch-only address is NOT credited (known limitation, pinned)");
        if (! BRWalletContainsTransaction(w, tx)) BRTransactionFree(tx);
    }

    BRWalletFree(w);
    printf(g_fail ? "\n%d CHECK(S) FAILED\n" : "\nALL PASS\n", g_fail);
    return g_fail ? 1 : 0;
}
