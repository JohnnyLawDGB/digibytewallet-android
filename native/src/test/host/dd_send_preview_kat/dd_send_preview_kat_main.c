// Host KAT: a DigiDollar send's confirmation shows the fee of the transfer that is signed, and only the
// approved transfer is signed.
//
// THE INVARIANT. The bridge builds a DigiDollar transfer at review, reads what it pays and frees it
// (previewDigiDollar), and after the credential prompt builds it again and signs it only if it is the
// approved send at the approved fee (sendApprovedDigiDollar). Both go through digidollar_send.h, which
// this KAT compiles against the live core:
//   (1) the preview's fee is the built transfer's own fee -- its inputs minus its outputs -- not the
//       0.1 DGB floor the confirmation used to show whatever the transfer paid (a DGB change below the
//       dust line is folded into the fee, so a transfer can pay more than the floor);
//   (2) the preview reports what comes back to the wallet (DigiDollar change, DGB change) and how many
//       inputs the transfer spends;
//   (3) a rebuilt transfer is signed only when output 0 pays exactly the approved cents to the approved
//       key, every other output is the OP_RETURN or change to this wallet, and the fee is exactly the
//       approved fee -- a wallet that changed while the prompt was up builds something else, which is
//       refused rather than signed.
//
// GUARDS (no comparison-arm red is possible: they hold in both arms):
//   - a transfer that pays exactly the floor is previewed at the floor (the old figure was right there);
//   - the approved transfer, rebuilt from an unchanged wallet, passes; so does one to the wallet's own
//     DigiDollar address; and a signed transfer still passes (signing changes nothing the check reads);
//   - a DigiDollar transfer still builds and the preview answers for it.
//
// MACRO CONVENTION: the header tests the VALUE of DIGIDOLLAR_SEND_UNFIXED (#if, never #ifdef): run.sh
// builds the comparison arm with -DDIGIDOLLAR_SEND_UNFIXED=1 (the floor shown, nothing checked) and the
// shipped arm with =0.
//
// One case per process (argv[1]); "list" names them. Each case prints RESULT <case> pass|fail.
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>
#include <stdarg.h>

#include "BRWallet.h"
#include "BRTransaction.h"
#include "BRAddress.h"
#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"
#include "BRDigiDollar.h"
#include "digidollar_send.h"

static int g_failed = 0;

static void ck(int cond, const char *fmt, ...)
{
    va_list ap;
    printf(cond ? "PASS: " : "FAIL: ");
    va_start(ap, fmt);
    vprintf(fmt, ap);
    va_end(ap);
    printf("\n");
    if (!cond) g_failed = 1;
}

static const char *kMnemonic =
    "abandon abandon abandon abandon abandon abandon abandon abandon "
    "abandon abandon abandon about";
static uint8_t g_seed[64];
static BRMasterPubKey g_mpk84, g_mpk86;
static const uint8_t kPlaceholder[1] = {0};
static const uint8_t kRecip[32] = {
    0xdc,0xea,0x60,0x96,0x99,0x3f,0x47,0x81,0x40,0x2e,0x76,0x3c,0x9d,0x36,0x09,0x79,
    0xc3,0xcf,0x66,0xa4,0x38,0x18,0xc9,0x5b,0x90,0x87,0xf0,0x88,0xcf,0x62,0x63,0x1b };
static const uint8_t kOther[32] = {
    0x5b,0x13,0x33,0x7c,0x91,0x0d,0x2a,0x44,0x6e,0x8f,0x01,0x77,0xa2,0x3c,0x55,0x19,
    0x0e,0x6d,0x42,0xb8,0x24,0x9a,0xc7,0x31,0x60,0x0b,0xe3,0x5f,0x2d,0x88,0x14,0x4a };

#define FLOOR DD_SEND_FEE_FLOOR_SATS   // 10,000,000 sat: what the confirmation showed

static void finalizeTxHash(BRTransaction *tx)
{
    uint8_t data[BRTransactionSerialize(tx, NULL, 0)];
    size_t len = BRTransactionSerialize(tx, data, sizeof(data));
    BRTransaction *t = BRTransactionParse(data, len);
    if (t) { tx->txHash = t->txHash; tx->wtxHash = t->wtxHash; BRTransactionFree(t); }
}

// A confirmed transaction paying `sats` to `addr`, spending a coin that is not the wallet's.
static BRTransaction *coinTx(const char *addr, uint64_t sats, uint8_t tag)
{
    uint8_t spk[64];
    size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), addr);
    BRTransaction *c = BRTransactionNew();
    UInt256 h;
    memset(h.u8, tag, sizeof(h.u8));
    c->version = 1;
    BRTransactionAddInput(c, h, 0, 0, spk, spkLen, kPlaceholder, 0, kPlaceholder, 0, 0xffffffff);
    BRTransactionAddOutput(c, sats, spk, spkLen);
    c->blockHeight = 23700000;
    c->timestamp = 1700000000;
    finalizeTxHash(c);
    return c;
}

// A wallet holding one confirmed $100.00 DigiDollar coin and, when `sats` is not 0, the DGB coin `sats`.
static BRWallet *ddWallet(uint64_t sats)
{
    BRWallet *w = BRWalletNew(NULL, 0, g_mpk84);
    BRWalletSetTaprootKey(w, g_mpk86);
    BRAddress ta = BRWalletReceiveAddress(w, 2);
    uint8_t spk[64];
    size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), ta.s);
    BRTransaction *dd = BRTransactionNew();
    UInt256 ph;
    memset(ph.u8, 0x11, sizeof(ph.u8));
    dd->version = 0x02000770;
    BRTransactionAddInput(dd, ph, 0, 0, spk, spkLen, kPlaceholder, 0, kPlaceholder, 0, 0xffffffff);
    BRTransactionAddOutput(dd, 0, spk, spkLen);
    const uint8_t orr[9] = { 0x6a, 0x02, 0x44, 0x44, 0x01, 0x02, 0x02, 0x10, 0x27 };   // "DD" type 2 [10000 c]
    BRTransactionAddOutput(dd, 0, orr, sizeof(orr));
    dd->blockHeight = 23700000;
    finalizeTxHash(dd);
    BRWalletRegisterTransaction(w, dd);
    if (sats) {
        BRAddress da = BRWalletReceiveAddress(w, 1);
        BRWalletRegisterTransaction(w, coinTx(da.s, sats, 0xC1));
    }
    return w;
}

// The wallet's own DigiDollar key: the taproot output key of its first taproot receive address.
static int ownKey(BRWallet *w, uint8_t key[32])
{
    BRAddress ta = BRWalletReceiveAddress(w, 2);
    uint8_t spk[64];
    size_t n = BRAddressScriptPubKey(spk, sizeof(spk), ta.s);
    if (n != 34 || spk[0] != 0x51 || spk[1] != 0x20) return 0;
    memcpy(key, spk + 2, 32);
    return 1;
}

// A native-segwit address no wallet here owns.
static void externalAddress(char *out, size_t cap)
{
    uint8_t spk[22] = { 0x00, 0x14 };
    memset(spk + 2, 0x5a, 20);
    BRAddressFromScriptPubKey(out, cap, spk, sizeof(spk));
}

// What a built transaction pays, from its own inputs and outputs (the builder records each input's amount).
static uint64_t paid(const BRTransaction *tx)
{
    uint64_t in = 0, out = 0;
    for (size_t i = 0; i < tx->inCount; i++) in += tx->inputs[i].amount;
    for (size_t i = 0; i < tx->outCount; i++) out += tx->outputs[i].amount;
    return in - out;
}

// `src` with output `at` replaced by one paying `script` the same amount: the same inputs and the same
// fee, another payee.
static BRTransaction *withOutputScript(const BRTransaction *src, size_t at, const uint8_t *script, size_t len)
{
    BRTransaction *t = BRTransactionNew();
    t->version = src->version;
    t->lockTime = src->lockTime;
    for (size_t i = 0; i < src->inCount; i++) {
        const BRTxInput *in = &src->inputs[i];
        BRTransactionAddInput(t, in->txHash, in->index, in->amount, in->script, in->scriptLen, NULL, 0, NULL, 0, in->sequence);
    }
    for (size_t i = 0; i < src->outCount; i++) {
        const BRTxOutput *o = &src->outputs[i];
        if (i == at) BRTransactionAddOutput(t, o->amount, script, len);
        else BRTransactionAddOutput(t, o->amount, o->script, o->scriptLen);
    }
    return t;
}

// Spend every plain coin of `w` in one DGB send to an external address, signed and registered: another
// send the wallet made while the DigiDollar prompt was up.
static int spendPlainCoins(BRWallet *w, uint64_t amount)
{
    char dest[75];
    externalAddress(dest, sizeof(dest));
    BRTransaction *other = BRWalletCreateTransactionAtFeePerKb(w, amount, dest, DEFAULT_FEE_PER_KB);
    int ok = other && BRWalletSignTransaction(w, other, 0, g_seed, sizeof(g_seed)) && BRWalletRegisterTransaction(w, other);
    if (!ok && other) BRTransactionFree(other);
    return ok;
}

static const char *verdictName(DdSendVerdict v)
{
    switch (v) {
    case DD_SEND_OK: return "OK";
    case DD_SEND_FEE_UNKNOWN: return "FEE_UNKNOWN";
    case DD_SEND_FEE_CHANGED: return "FEE_CHANGED";
    case DD_SEND_NOT_APPROVED: return "NOT_APPROVED";
    }
    return "?";
}

// ---- the preview -------------------------------------------------------------------------------

static void preview_fee_is_built_fee(void)
{
    // 0.1003 DGB pays the 0.1 DGB floor and leaves 30,000 sat: below the dust line, so it goes to the fee.
    BRWallet *w = ddWallet(10030000);
    BRTransaction *tx = BRWalletCreateDigiDollarTransfer(w, kRecip, 5000);
    ck(tx != NULL, "a $50.00 transfer builds");
    if (!tx) { BRWalletFree(w); return; }
    uint64_t built = paid(tx);
    ck(built == 10030000, "the transfer pays 10,030,000 sat (the floor plus the sub-dust change): %llu", (unsigned long long)built);
    DdSendPreview p;
    int ok = dd_send_preview(w, tx, &p);
    ck(ok, "the preview answers");
    ck(ok && p.feeSats == built, "the preview's fee is the transfer's own fee: %llu, built %llu",
       (unsigned long long)(ok ? p.feeSats : 0), (unsigned long long)built);
    BRTransactionFree(tx);
    BRWalletFree(w);
}

static void preview_reports_what_comes_back(void)
{
    BRWallet *w = ddWallet(50000000);
    BRTransaction *tx = BRWalletCreateDigiDollarTransfer(w, kRecip, 4000);   // $40.00 of $100.00
    ck(tx != NULL, "a $40.00 transfer builds");
    if (!tx) { BRWalletFree(w); return; }
    DdSendPreview p;
    int ok = dd_send_preview(w, tx, &p);
    ck(ok, "the preview answers");
    ck(ok && p.feeSats == paid(tx) && p.feeSats == FLOOR, "fee %llu", (unsigned long long)(ok ? p.feeSats : 0));
    ck(ok && p.ddChangeCents == 6000, "$60.00 of DigiDollar comes back: %llu cents", (unsigned long long)(ok ? p.ddChangeCents : 0));
    ck(ok && p.dgbChangeSats == 50000000 - FLOOR, "0.4 DGB comes back: %llu sat", (unsigned long long)(ok ? p.dgbChangeSats : 0));
    ck(ok && p.inputCount == tx->inCount && tx->inCount == 2, "two inputs (one DigiDollar, one DGB): %llu",
       (unsigned long long)(ok ? p.inputCount : 0));
    BRTransactionFree(tx);
    BRWalletFree(w);
}

static void preview_at_the_floor(void)
{
    BRWallet *w = ddWallet(50000000);
    BRTransaction *tx = BRWalletCreateDigiDollarTransfer(w, kRecip, 5000);
    ck(tx != NULL, "a $50.00 transfer builds");
    if (!tx) { BRWalletFree(w); return; }
    DdSendPreview p;
    int ok = dd_send_preview(w, tx, &p);
    ck(ok && p.feeSats == FLOOR && paid(tx) == FLOOR, "a transfer paying the floor is previewed at the floor: %llu",
       (unsigned long long)(ok ? p.feeSats : 0));
    BRTransactionFree(tx);
    BRWalletFree(w);
}

static void preview_unknown_parent(void)
{
    BRWallet *w = ddWallet(50000000);
    BRTransaction *tx = BRWalletCreateDigiDollarTransfer(w, kRecip, 5000);
    ck(tx != NULL, "a $50.00 transfer builds");
    if (!tx) { BRWalletFree(w); return; }
    // An input the wallet holds no transaction for: the fee cannot be told, and no figure is shown.
    UInt256 unknown;
    memset(unknown.u8, 0x77, sizeof(unknown.u8));
    BRTransactionAddInput(tx, unknown, 0, 1000000, NULL, 0, NULL, 0, NULL, 0, TXIN_SEQUENCE);
    DdSendPreview p;
    int ok = dd_send_preview(w, tx, &p);
    ck(!ok, "a transfer spending an input the wallet does not hold has no preview (got fee %llu)",
       (unsigned long long)(ok ? p.feeSats : 0));
    BRTransactionFree(tx);
    BRWalletFree(w);
}

// ---- the check after the prompt ----------------------------------------------------------------

static void check_approved_signs(void)
{
    BRWallet *w = ddWallet(10030000);
    BRTransaction *reviewed = BRWalletCreateDigiDollarTransfer(w, kRecip, 5000);
    DdSendPreview p;
    int ok = reviewed && dd_send_preview(w, reviewed, &p);
    ck(ok, "review builds and previews the transfer");
    if (reviewed) BRTransactionFree(reviewed);   // freed at review: nothing outlives the prompt
    BRTransaction *rebuilt = ok ? BRWalletCreateDigiDollarTransfer(w, kRecip, 5000) : NULL;
    ck(rebuilt != NULL, "the transfer is built again after the prompt");
    if (rebuilt) {
        DdSendVerdict v = dd_send_check(w, rebuilt, kRecip, 5000, p.feeSats);
        ck(v == DD_SEND_OK, "the rebuilt transfer is the approved send at the approved fee (%s)", verdictName(v));
        BRTransactionFree(rebuilt);
    }
    BRWalletFree(w);
}

static void check_fee_changed(void)
{
    // Review: a 0.5 DGB coin pays the floor, with change.
    BRWallet *w = ddWallet(50000000);
    BRTransaction *reviewed = BRWalletCreateDigiDollarTransfer(w, kRecip, 5000);
    DdSendPreview p;
    int ok = reviewed && dd_send_preview(w, reviewed, &p);
    ck(ok && p.feeSats == FLOOR, "review previews the floor");
    if (reviewed) BRTransactionFree(reviewed);
    // While the prompt is up, a DGB send spends that coin, and a 0.1003 DGB coin arrives: the transfer
    // built after the prompt pays the floor plus a sub-dust change.
    ck(spendPlainCoins(w, 30000000), "another send spends the 0.5 DGB coin");
    BRAddress da = BRWalletReceiveAddress(w, 1);
    BRWalletRegisterTransaction(w, coinTx(da.s, 10030000, 0xC2));
    BRTransaction *rebuilt = BRWalletCreateDigiDollarTransfer(w, kRecip, 5000);
    ck(rebuilt != NULL && paid(rebuilt) == 10030000, "the transfer built after the prompt pays %llu sat",
       (unsigned long long)(rebuilt ? paid(rebuilt) : 0));
    if (rebuilt) {
        DdSendVerdict v = dd_send_check(w, rebuilt, kRecip, 5000, ok ? p.feeSats : FLOOR);
        ck(v == DD_SEND_FEE_CHANGED, "a transfer paying more than the approved fee is refused before signing (%s)", verdictName(v));
        BRTransactionFree(rebuilt);
    }
    BRWalletFree(w);
}

static void check_fee_lower(void)
{
    BRWallet *w = ddWallet(50000000);
    BRTransaction *tx = BRWalletCreateDigiDollarTransfer(w, kRecip, 5000);
    ck(tx != NULL, "a $50.00 transfer builds");
    if (!tx) { BRWalletFree(w); return; }
    DdSendVerdict v = dd_send_check(w, tx, kRecip, 5000, FLOOR + 30000);
    ck(v == DD_SEND_FEE_CHANGED, "a transfer paying less than the approved fee is refused too (%s)", verdictName(v));
    BRTransactionFree(tx);
    BRWalletFree(w);
}

static void check_recipient_changed(void)
{
    BRWallet *w = ddWallet(50000000);
    BRTransaction *tx = BRWalletCreateDigiDollarTransfer(w, kOther, 5000);
    ck(tx != NULL, "a $50.00 transfer to another key builds");
    if (!tx) { BRWalletFree(w); return; }
    DdSendVerdict v = dd_send_check(w, tx, kRecip, 5000, paid(tx));
    ck(v == DD_SEND_NOT_APPROVED, "a transfer paying a key that was not approved is refused (%s)", verdictName(v));
    BRTransactionFree(tx);
    BRWalletFree(w);
}

static void check_amount_changed(void)
{
    BRWallet *w = ddWallet(50000000);
    BRTransaction *tx = BRWalletCreateDigiDollarTransfer(w, kRecip, 5000);
    ck(tx != NULL, "a $50.00 transfer builds");
    if (!tx) { BRWalletFree(w); return; }
    DdSendVerdict v = dd_send_check(w, tx, kRecip, 4000, paid(tx));
    ck(v == DD_SEND_NOT_APPROVED, "a transfer of $50.00 is refused when $40.00 was approved (%s)", verdictName(v));
    BRTransactionFree(tx);
    BRWalletFree(w);
}

static void check_dd_change_foreign(void)
{
    BRWallet *w = ddWallet(50000000);
    BRTransaction *tx = BRWalletCreateDigiDollarTransfer(w, kRecip, 4000);   // DigiDollar change at output 1
    ck(tx != NULL && tx->outCount >= 3 && BRDigiDollarOutputAmount(tx, 1) == 6000, "a $40.00 transfer builds with $60.00 change");
    if (!tx || tx->outCount < 3) { if (tx) BRTransactionFree(tx); BRWalletFree(w); return; }
    uint8_t foreign[34] = { 0x51, 0x20 };
    memcpy(foreign + 2, kOther, 32);
    BRTransaction *moved = withOutputScript(tx, 1, foreign, sizeof(foreign));
    DdSendVerdict v = dd_send_check(w, moved, kRecip, 4000, paid(tx));
    ck(v == DD_SEND_NOT_APPROVED, "a transfer whose DigiDollar change pays a key not the wallet's is refused (%s)", verdictName(v));
    BRTransactionFree(moved);
    BRTransactionFree(tx);
    BRWalletFree(w);
}

static void check_dgb_change_foreign(void)
{
    BRWallet *w = ddWallet(50000000);
    BRTransaction *tx = BRWalletCreateDigiDollarTransfer(w, kRecip, 10000);  // all $100.00: no DigiDollar change; DGB change at 1
    ck(tx != NULL && tx->outCount == 3 && tx->outputs[1].amount > 0, "a $100.00 transfer builds with DGB change and no DigiDollar change");
    if (!tx || tx->outCount != 3) { if (tx) BRTransactionFree(tx); BRWalletFree(w); return; }
    uint8_t foreign[22] = { 0x00, 0x14 };
    memset(foreign + 2, 0x5a, 20);
    BRTransaction *moved = withOutputScript(tx, 1, foreign, sizeof(foreign));
    DdSendVerdict v = dd_send_check(w, moved, kRecip, 10000, paid(tx));
    ck(v == DD_SEND_NOT_APPROVED, "a transfer whose DGB change pays an address not the wallet's is refused (%s)", verdictName(v));
    BRTransactionFree(moved);
    BRTransactionFree(tx);
    BRWalletFree(w);
}

static void check_self_send_signs(void)
{
    BRWallet *w = ddWallet(50000000);
    uint8_t own[32];
    ck(ownKey(w, own), "the wallet has a DigiDollar key");
    BRTransaction *tx = BRWalletCreateDigiDollarTransfer(w, own, 5000);
    ck(tx != NULL, "a $50.00 transfer to the wallet's own DigiDollar address builds");
    if (!tx) { BRWalletFree(w); return; }
    DdSendVerdict v = dd_send_check(w, tx, own, 5000, paid(tx));
    ck(v == DD_SEND_OK, "the approved transfer to the wallet itself passes (%s)", verdictName(v));
    BRTransactionFree(tx);
    BRWalletFree(w);
}

static void check_after_signing(void)
{
    BRWallet *w = ddWallet(10030000);
    BRTransaction *tx = BRWalletCreateDigiDollarTransfer(w, kRecip, 4000);
    ck(tx != NULL, "a $40.00 transfer builds");
    if (!tx) { BRWalletFree(w); return; }
    uint64_t fee = paid(tx);
    DdSendVerdict before = dd_send_check(w, tx, kRecip, 4000, fee);
    int signedOk = BRWalletSignTransaction(w, tx, 0, g_seed, sizeof(g_seed)) && BRTransactionIsSigned(tx);
    ck(signedOk, "the wallet signs every input (the DigiDollar taproot input and the DGB fee input)");
    DdSendVerdict after = dd_send_check(w, tx, kRecip, 4000, fee);
    ck(before == DD_SEND_OK && after == DD_SEND_OK, "the check passes before and after signing (%s, %s)",
       verdictName(before), verdictName(after));
    BRTransactionFree(tx);
    BRWalletFree(w);
}

static void dd_send_builds(void)
{
    BRWallet *w = ddWallet(50000000);
    BRTransaction *tx = BRWalletCreateDigiDollarTransfer(w, kRecip, 5000);
    ck(tx != NULL && BRDigiDollarTxType(tx) == DD_TYPE_TRANSFER, "a DigiDollar transfer of $50.00 builds");
    DdSendPreview p;
    ck(tx && dd_send_preview(w, tx, &p), "and the preview answers for it");
    if (tx) BRTransactionFree(tx);
    BRWalletFree(w);
}

typedef struct { const char *name; void (*run)(void); } Case;
static const Case kCases[] = {
    { "preview_fee_is_built_fee", preview_fee_is_built_fee },
    { "preview_reports_what_comes_back", preview_reports_what_comes_back },
    { "preview_at_the_floor", preview_at_the_floor },
    { "preview_unknown_parent", preview_unknown_parent },
    { "check_approved_signs", check_approved_signs },
    { "check_fee_changed", check_fee_changed },
    { "check_fee_lower", check_fee_lower },
    { "check_recipient_changed", check_recipient_changed },
    { "check_amount_changed", check_amount_changed },
    { "check_dd_change_foreign", check_dd_change_foreign },
    { "check_dgb_change_foreign", check_dgb_change_foreign },
    { "check_self_send_signs", check_self_send_signs },
    { "check_after_signing", check_after_signing },
    { "dd_send_builds", dd_send_builds },
};

int main(int argc, char **argv)
{
    size_t n = sizeof(kCases) / sizeof(kCases[0]);
    if (argc < 2) { fprintf(stderr, "usage: %s <case>|list\n", argv[0]); return 2; }
    if (strcmp(argv[1], "list") == 0) {
        for (size_t i = 0; i < n; i++) printf("%s\n", kCases[i].name);
        return 0;
    }
    BRBIP39DeriveKey(g_seed, kMnemonic, NULL);
    g_mpk84 = BRBIP32MasterPubKeyBIP84(g_seed, sizeof(g_seed));
    g_mpk86 = BRBIP32MasterPubKeyBIP86(g_seed, sizeof(g_seed));
    for (size_t i = 0; i < n; i++) {
        if (strcmp(argv[1], kCases[i].name) != 0) continue;
        printf("ARM: %s\n", DIGIDOLLAR_SEND_UNFIXED ? "comparison (DIGIDOLLAR_SEND_UNFIXED=1)" : "shipped");
        kCases[i].run();
        printf("RESULT %s %s\n", kCases[i].name, g_failed ? "fail" : "pass");
        return g_failed;
    }
    fprintf(stderr, "unknown case %s\n", argv[1]);
    return 2;
}
