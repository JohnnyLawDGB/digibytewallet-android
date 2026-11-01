// Host KAT: a transaction a recovery signer has signed leaves only through signed_spend_gate (4.1.3, review R3).
//   A  RED   two sweeps signing one foreign coin (no wallet loaded): the first passes, the second is refused
//   B  RED   a sweep of a coin the wallet holds as spent is refused
//   C  GUARD once the first claim is released, the second transaction passes
//   D  GUARD the same transaction asked again is granted (its own claim is renewed)
//   E  GUARD a transaction with no inputs is refused
// RED -DRECOVERY_SPEND_GATE_UNFIXED=1: the gate lets everything through; A and B must fail there.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "BRWallet.h"
#include "BRTransaction.h"
#include "BRAddress.h"
#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"
#include "signed_spend_gate.h"

static int g_fail = 0;
static void check(int c, const char *d) { printf(c ? "PASS: %s\n" : "FAIL: %s\n", d); if (!c) g_fail++; }
static const uint8_t kSig[2] = {0x01, 0x51};
static const char *kMn = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";

static void fin(BRTransaction *tx) {
    uint8_t d[BR_VLA_LEN(BRTransactionSerialize(tx, NULL, 0))];
    size_t n = BRTransactionSerialize(tx, d, sizeof(d));
    BRTransaction *t = BRTransactionParse(d, n);
    if (t) { tx->txHash = t->txHash; tx->wtxHash = t->wtxHash; BRTransactionFree(t); }
}

// A transaction spending (prev, n) to a script derived from `tag`.
static BRTransaction *spend(UInt256 prev, uint32_t n, uint8_t tag) {
    BRTransaction *tx = BRTransactionNew();
    uint8_t spk[22] = {0x00, 20}; memset(spk + 2, tag, 20);
    BRTransactionAddInput(tx, prev, n, 100000, spk, sizeof(spk), kSig, sizeof(kSig), kSig, 0, 0xffffffff);
    BRTransactionAddOutput(tx, 90000, spk, sizeof(spk));
    fin(tx);
    return tx;
}

int main(void)
{
    setvbuf(stdout, NULL, _IONBF, 0);
    double now = 1000.0;

    printf("\n=== A, C, D: two sweeps of one foreign coin, no wallet ===\n");
    coin_reservation_clear();
    UInt256 foreign; memset(foreign.u8, 0x61, 32);
    BRTransaction *s1 = spend(foreign, 0, 0x11), *s2 = spend(foreign, 0, 0x22);
    check(signed_spend_gate(NULL, s1, now) == 1, "A: the first sweep of the coin may leave");
    check(signed_spend_gate(NULL, s2, now) == 0, "A: a second, different sweep of the same coin is refused");
    check(signed_spend_gate(NULL, s1, now + 1) == 1, "D: the first sweep asked again is granted (its own claim)");
    coin_reservation_release(s1->txHash);
    check(signed_spend_gate(NULL, s2, now + 2) == 1, "C: once the first claim is released the second may leave");
    BRTransactionFree(s1); BRTransactionFree(s2);

    printf("\n=== B: a sweep of a coin the wallet holds as spent ===\n");
    coin_reservation_clear();
    uint8_t seed[64];
    BRBIP39DeriveKey(seed, kMn, NULL);
    BRMasterPubKey mpk = BRBIP32MasterPubKeyBIP84(seed, sizeof(seed));
    BRWallet *w0 = BRWalletNew(NULL, 0, mpk);
    BRAddress a; BRWalletUnusedAddrs(w0, &a, 1, 0, 1);
    BRWalletFree(w0);
    uint8_t spk[64]; size_t l = BRAddressScriptPubKey(spk, sizeof(spk), a.s);
    BRTransaction *recv = BRTransactionNew();
    UInt256 outside; memset(outside.u8, 0x71, 32);
    BRTransactionAddInput(recv, outside, 0, 0, spk, l, kSig, sizeof(kSig), kSig, 0, 0xffffffff);
    BRTransactionAddOutput(recv, 200000, spk, l);
    fin(recv); recv->blockHeight = 700000; recv->timestamp = 1700000000;
    BRTransaction *walletSpend = BRTransactionNew();
    BRTransactionAddInput(walletSpend, recv->txHash, 0, 200000, spk, l, kSig, sizeof(kSig), kSig, 0, 0xffffffff);
    uint8_t other[22] = {0x00, 20}; memset(other + 2, 0x33, 20);
    BRTransactionAddOutput(walletSpend, 150000, other, sizeof(other));
    fin(walletSpend); walletSpend->blockHeight = 700001; walletSpend->timestamp = 1700000100;
    UInt256 recvHash = recv->txHash;
    BRTransaction *txs[2] = { recv, walletSpend };
    BRWallet *w = BRWalletNew(txs, 2, mpk);
    check(BRWalletOutpointSpent(w, recvHash, 0) == 1, "setup: the wallet holds the coin as spent");
    BRTransaction *sweep = spend(recvHash, 0, 0x44);
    check(signed_spend_gate(w, sweep, now) == 0, "B: a sweep of a coin the wallet holds as spent is refused");
    BRTransactionFree(sweep);
    BRWalletFree(w);

    printf("\n=== E: no inputs ===\n");
    BRTransaction *empty = BRTransactionNew();
    check(signed_spend_gate(NULL, empty, now) == 0, "E: a transaction with no inputs is refused");
    BRTransactionFree(empty);

    printf("\n%s: recovery_spend_gate_kat (%d failure%s)\n", g_fail ? "FAIL" : "PASS", g_fail, g_fail == 1 ? "" : "s");
    return g_fail ? 1 : 0;
}
