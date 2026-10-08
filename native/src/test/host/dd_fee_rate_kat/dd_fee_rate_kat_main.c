// Host KAT: a DigiDollar transfer pays the fee its confirmation shows, whatever fee rate the wallet
// was left at.
//
// WHAT IT PROVES.
//   The DigiDollar confirmation shows the 0.1 DGB floor (DD_MIN_FEE). The wallet-wide fee rate
//   (BRWalletSetFeePerKb) is set by every DGB build that names one and raised by peers' feefilter,
//   so a transfer must not read it. For every funding shape below and every rate the wallet may have
//   been left at -- a custom rate as the send screen derives it from a DGB total, the feefilter cap,
//   a rate under the default -- after a DGB build at that rate:
//     (1) BOUND. The transfer pays at least DD_MIN_FEE and at most DD_MIN_FEE + TX_MIN_OUTPUT_AMOUNT - 1
//         (0.1 DGB + 54,599 sat): the floor, plus at most a DGB change too small to be an output.
//     (2) INDEPENDENCE. It is byte-for-byte the transfer built with the wallet at the default rate.
//     (3) The builder leaves the wallet's rate as it found it.
//   The sub-dust edge is pinned exactly: 54,599 sat of change goes to the fee (paid == the bound),
//   54,600 sat is an output (paid == the floor).
//
// RED. The reference builder (dd_coin_selection_kat/base_builder.c, the transfer function as it
//   was at core 5b45756, which read wallet->feePerKb for its size estimate and for the change
//   threshold) is run over the same cases. It MUST break the bound for the large custom rates --
//   e.g. a 5 DGB custom total leaves a rate at which a one-input transfer pays 9.54 DGB -- or this
//   KAT has lost the ability to see the defect and fails.
//
// The bridge half (NativeBridge_createTransaction puts the wallet's rate back after each build) is
// checked against the source by run.sh: jni_transaction.c includes Android headers and cannot be
// compiled on the host.
//
// BRWallet.c and base_builder.c are #included (the reference builder needs the wallet struct);
// BRWallet.c is therefore NOT on the compiler line in run.sh. Exit code 0 = all checks passed.
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>

#include "BRWallet.c"       // the CURRENT builder + struct/statics/macros (NOT on the clang line)
#include "base_builder.c"   // BRWalletCreateDigiDollarTransfer_base: reads wallet->feePerKb

#include "BRDigiDollar.h"
#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"
#include "BRAddress.h"

static int g_fail = 0;
static void ck(int cond, const char *what) {
    printf(cond ? "  ok   %s\n" : "  FAIL %s\n", what);
    if (!cond) g_fail++;
}

#define BOUND (DD_MIN_FEE + TX_MIN_OUTPUT_AMOUNT - 1)   // 10,054,599 sat
#define SEND_VSIZE 141ULL                                // SendViewModel.TYPICAL_TX_VSIZE

static const char *kMnemonic =
    "abandon abandon abandon abandon abandon abandon abandon abandon "
    "abandon abandon abandon about";
static const uint8_t kPlaceholder[1] = {0};
static const uint8_t kRecip[32] = {
    0xdc,0xea,0x60,0x96,0x99,0x3f,0x47,0x81,0x40,0x2e,0x76,0x3c,0x9d,0x36,0x09,0x79,
    0xc3,0xcf,0x66,0xa4,0x38,0x18,0xc9,0x5b,0x90,0x87,0xf0,0x88,0xcf,0x62,0x63,0x1b };

static BRMasterPubKey g_mpk84, g_mpk86;

static void finalizeTxHash(BRTransaction *tx) {
    uint8_t data[BRTransactionSerialize(tx, NULL, 0)];
    size_t len = BRTransactionSerialize(tx, data, sizeof(data));
    BRTransaction *t = BRTransactionParse(data, len);
    if (t) { tx->txHash = t->txHash; tx->wtxHash = t->wtxHash; BRTransactionFree(t); }
}

// A wallet holding one confirmed $100.00 DigiDollar coin and the DGB coins `sats[0..n)`.
static BRWallet *mkWallet(const uint64_t *sats, size_t n) {
    BRWallet *w = BRWalletNew(NULL, 0, g_mpk84);
    BRWalletSetTaprootKey(w, g_mpk86);
    BRAddress ta = BRWalletReceiveAddress(w, 2);
    uint8_t spk[64]; size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), ta.s);
    BRTransaction *dd = BRTransactionNew();
    dd->version = 0x02000770;
    UInt256 ph; memset(ph.u8, 0x11, 32);
    BRTransactionAddInput(dd, ph, 0, 0, spk, spkLen, kPlaceholder, 0, kPlaceholder, 0, 0xffffffff);
    BRTransactionAddOutput(dd, 0, spk, spkLen);
    uint8_t orr[9] = {0x6a,0x02,0x44,0x44,0x01,0x02,0x02,0x10,0x27};      // "DD" type 2 [10000c]
    BRTransactionAddOutput(dd, 0, orr, sizeof(orr));
    dd->blockHeight = 23700000;
    finalizeTxHash(dd);
    BRWalletRegisterTransaction(w, dd);

    BRAddress da = BRWalletReceiveAddress(w, 1);
    uint8_t dspk[64]; size_t dspkLen = BRAddressScriptPubKey(dspk, sizeof(dspk), da.s);
    for (size_t i = 0; i < n; i++) {
        BRTransaction *c = BRTransactionNew();
        c->version = 1;
        UInt256 h; memset(h.u8, (uint8_t)(0xA0 + i), 32);
        BRTransactionAddInput(c, h, 0, 0, dspk, dspkLen, kPlaceholder, 0, kPlaceholder, 0, 0xffffffff);
        BRTransactionAddOutput(c, sats[i], dspk, dspkLen);
        c->blockHeight = 23700000;
        finalizeTxHash(c);
        BRWalletRegisterTransaction(w, c);
    }
    return w;
}

// What a transfer pays: its inputs' values less its outputs' (DD inputs and outputs carry 0).
static uint64_t paid(const BRTransaction *tx) {
    uint64_t in = 0, out = 0;
    for (size_t i = 0; i < tx->inCount; i++) in += tx->inputs[i].amount;
    for (size_t i = 0; i < tx->outCount; i++) out += tx->outputs[i].amount;
    return in - out;
}

static size_t ser(const BRTransaction *tx, uint8_t *buf, size_t cap) {
    size_t n = BRTransactionSerialize(tx, NULL, 0);
    return (n <= cap) ? BRTransactionSerialize(tx, buf, cap) : 0;
}

// A DGB build at `rate`, left in place the way the bridge left it before the restore: the rate is
// set on the wallet and the build runs (and may fail -- a failed build used to leave it too).
static void dgbBuildAt(BRWallet *w, uint64_t rate) {
    BRWalletSetFeePerKb(w, rate);
    BRAddress to = BRWalletReceiveAddress(w, 1);
    BRTransaction *t = BRWalletCreateTransaction(w, 1000000, to.s);
    if (t) BRTransactionFree(t);
}

int main(void) {
    uint8_t seed[64]; BRBIP39DeriveKey(seed, kMnemonic, NULL);
    g_mpk84 = BRBIP32MasterPubKeyBIP84(seed, sizeof(seed));
    g_mpk86 = BRBIP32MasterPubKeyBIP86(seed, sizeof(seed));

    ck(TX_MIN_OUTPUT_AMOUNT == 54600, "the dust amount at the default rate is 54,600 sat");
    ck(BOUND == 10054599, "the bound is 0.1 DGB + 54,599 sat");

    // Rates the wallet may be left at. Custom ones as SendViewModel derives them from a DGB total
    // (feeSat * 1000 / 141); the feefilter cap; one under the default.
    const uint64_t rates[] = {
        DEFAULT_FEE_PER_KB,
        5240000ULL * 1000 / SEND_VSIZE,        // 0.0524 DGB custom total: where the old fee crossed the floor
        7000000ULL * 1000 / SEND_VSIZE,        // 0.07 DGB
        100000000ULL * 1000 / SEND_VSIZE,      // 1 DGB
        500000000ULL * 1000 / SEND_VSIZE,      // 5 DGB
        MAX_FEE_PER_KB,                        // the most a peer's feefilter can set
        30000000ULL,                           // a rate under the floor estimate, over the dust scale
        1000ULL,                               // under the default
    };
    const size_t nRates = sizeof(rates) / sizeof(rates[0]);

    // DGB holdings that fund the fee.
    struct { const char *name; uint64_t sats[3]; size_t n; uint64_t expectPaid; } shapes[] = {
        { "one 30 DGB coin",                      { 3000000000ULL },               1, DD_MIN_FEE },
        { "one 1 DGB coin",                       { 100000000ULL },                1, DD_MIN_FEE },
        { "floor + 1,000,000 sat",                { DD_MIN_FEE + 1000000 },        1, DD_MIN_FEE },
        { "floor + 54,600 sat (change is an output)", { DD_MIN_FEE + 54600 },     1, DD_MIN_FEE },
        { "floor + 54,599 sat (change goes to fee)",  { DD_MIN_FEE + 54599 },     1, BOUND },
        { "0.04 + 0.04 + 30 DGB",                 { 4000000, 4000000, 3000000000ULL }, 3, DD_MIN_FEE },
    };
    const size_t nShapes = sizeof(shapes) / sizeof(shapes[0]);

    uint64_t refWorst = 0;
    char what[256];
    for (size_t s = 0; s < nShapes; s++) {
        printf("\n%s\n", shapes[s].name);
        // the transfer as built with the wallet at the default rate
        BRWallet *w0 = mkWallet(shapes[s].sats, shapes[s].n);
        BRTransaction *t0 = BRWalletCreateDigiDollarTransfer(w0, kRecip, 4000);
        snprintf(what, sizeof(what), "builds at the default rate, paying %llu sat",
                 (unsigned long long)shapes[s].expectPaid);
        ck(t0 != NULL && paid(t0) == shapes[s].expectPaid, what);
        uint8_t b0[4096]; size_t l0 = t0 ? ser(t0, b0, sizeof(b0)) : 0;

        for (size_t r = 0; r < nRates; r++) {
            BRWallet *w = mkWallet(shapes[s].sats, shapes[s].n);
            dgbBuildAt(w, rates[r]);
            uint64_t left = BRWalletFeePerKb(w);
            BRTransaction *t = BRWalletCreateDigiDollarTransfer(w, kRecip, 4000);
            uint64_t p = t ? paid(t) : 0;
            snprintf(what, sizeof(what), "after a DGB build at %llu sat/kB: pays %llu sat, within [floor, bound]",
                     (unsigned long long)rates[r], (unsigned long long)p);
            ck(t != NULL && p >= DD_MIN_FEE && p <= BOUND, what);
            uint8_t b[4096]; size_t l = t ? ser(t, b, sizeof(b)) : 0;
            ck(t != NULL && t0 != NULL && l == l0 && l > 0 && memcmp(b, b0, l) == 0,
               "  ... and is byte-for-byte the transfer built at the default rate");
            ck(BRWalletFeePerKb(w) == left, "  ... and leaves the wallet's rate as it found it");
            if (t) BRTransactionFree(t);

            // the reference builder, which read the wallet's rate
            BRTransaction *ref = BRWalletCreateDigiDollarTransfer_base(w, kRecip, 4000);
            if (ref) {
                uint64_t rp = paid(ref);
                if (rp > refWorst) refWorst = rp;
                if (rp > BOUND) printf("         reference builder pays %llu sat here\n", (unsigned long long)rp);
                BRTransactionFree(ref);
            }
            BRWalletFree(w);
        }
        if (t0) BRTransactionFree(t0);
        BRWalletFree(w0);
    }

    printf("\nRED (the reference builder, which read the wallet's rate)\n");
    snprintf(what, sizeof(what), "it breaks the bound after a custom-rate DGB build (worst: %llu sat)",
             (unsigned long long)refWorst);
    ck(refWorst > BOUND, what);

    printf(g_fail == 0 ? "\nALL PASS\n" : "\n%d FAIL\n", g_fail);
    return g_fail ? 1 : 0;
}
