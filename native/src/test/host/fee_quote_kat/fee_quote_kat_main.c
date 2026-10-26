// Host KAT: the fee a DGB send's confirmation shows is the fee of the transaction that is signed.
//
// THE INVARIANT. A send is built and quoted at review, before the credential prompt (the bridge's
// createTransaction + quoteTransaction), and after the prompt the SAME bytes are signed
// (signPreparedTransaction). The bridge does both through prepared_send.h, which this KAT compiles
// against the live core:
//   (1) the quote is the built transaction's own fee -- its inputs minus its outputs, looked up in
//       the wallet's own transactions -- and its own size; not a typical 141-vbyte transaction at
//       the default rate (14,100 sat), which is what every confirmation used to show;
//   (2) before signing, the prepared bytes must pay exactly the approved fee and spend only coins
//       that are still among the wallet's spendable plain coins; after signing, the signed
//       transaction must pay exactly the approved fee. Bytes that fail are not signed.
//
// GUARDS (no comparison-arm red is possible: they pin the core behaviour the quote relies on):
//   - the builder's 100 sat/vB floor, its rounding of a rate's fee up to 100 sat, and the round-off
//     of the wallet's remaining balance to whole hundreds (all of it is in the fee the quote reads);
//   - a prepared send at the approved fee is signed and still pays exactly that fee;
//   - a DigiDollar transfer still builds, and every one of its outputs is relayable under DigiByte
//     Core's dust rule with Core's DigiDollar exemption (the send path's dust check refuses
//     nothing a DigiDollar send emits).
//
// MACRO CONVENTION: the header tests the VALUE of PREPARED_SEND_UNFIXED (#if, never #ifdef):
// run.sh builds the comparison arm with -DPREPARED_SEND_UNFIXED=1 (today's behaviour: the
// typical-size figure, nothing checked before signing) and the shipped arm with =0.
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
#include "prepared_send.h"

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

#define TYPICAL_FIGURE (141ULL * DEFAULT_FEE_PER_KB / 1000ULL)   // 14,100: the figure every confirmation showed

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

// A wallet holding confirmed plain coins of sats[0..n), paying its native-segwit address.
static BRWallet *walletWithCoins(const uint64_t *sats, size_t n)
{
    BRWallet *w = BRWalletNew(NULL, 0, g_mpk84);
    BRAddress a = BRWalletReceiveAddress(w, 1);
    for (size_t i = 0; i < n; i++) BRWalletRegisterTransaction(w, coinTx(a.s, sats[i], (uint8_t)(0xA0 + i)));
    return w;
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

// The bytes the bridge hands Kotlin, parsed again as the bridge parses them before quoting or signing.
static BRTransaction *roundTrip(const BRTransaction *tx)
{
    size_t len = BRTransactionSerialize(tx, NULL, 0);
    uint8_t *buf = malloc(len ? len : 1);
    if (!buf) return NULL;
    len = BRTransactionSerialize(tx, buf, len);
    BRTransaction *t = BRTransactionParse(buf, len);
    free(buf);
    return t;
}

// The size the builder priced: the transaction without its change output, plus one output's allowance.
static size_t pricedSize(const BRTransaction *tx, const char *dest)
{
    BRTransaction *t = BRTransactionNew();
    for (size_t i = 0; i < tx->inCount; i++) {
        const BRTxInput *in = &tx->inputs[i];
        BRTransactionAddInput(t, in->txHash, in->index, in->amount, in->script, in->scriptLen, NULL, 0, NULL, 0, in->sequence);
    }
    for (size_t i = 0; i < tx->outCount; i++) {
        if (strcmp(tx->outputs[i].address, dest) == 0)
            BRTransactionAddOutput(t, tx->outputs[i].amount, tx->outputs[i].script, tx->outputs[i].scriptLen);
    }
    size_t s = BRTransactionVSize(t) + TX_OUTPUT_SIZE;
    BRTransactionFree(t);
    return s;
}

static uint64_t buildFee(BRWallet *w, uint64_t amount, const char *dest, uint64_t rate, size_t *priced)
{
    BRTransaction *tx = BRWalletCreateTransactionAtFeePerKb(w, amount, dest, rate);
    if (!tx) return UINT64_MAX;
    uint64_t f = paid(tx);
    if (priced) *priced = pricedSize(tx, dest);
    BRTransactionFree(tx);
    return f;
}

// ---- DigiByte Core 9.26's dust rule (src/policy/policy.cpp), mirrored for the DigiDollar guard ----
static uint64_t coreDustThreshold(const uint8_t *s, size_t len)
{
    if ((len > 0 && s[0] == 0x6a) || len > 10000) return 0;                       // IsUnspendable
    size_t size = 8 + (len < 0xfd ? 1 : 3) + len;                                 // serialized txout
    int witness = len >= 4 && len <= 42 && (s[0] == 0x00 || (s[0] >= 0x51 && s[0] <= 0x60)) &&
                  (size_t)s[1] + 2 == len;                                        // IsWitnessProgram
    size += witness ? (32 + 4 + 1 + 107 / 4 + 4) : (32 + 4 + 1 + 107 + 4);
    return (uint64_t)size * 30000 / 1000;                                         // DUST_RELAY_TX_FEE
}

static int coreIsDigiDollarTx(uint32_t version)
{
    uint32_t type = version >> 24;
    return (version & 0xFFFF) == 0x0770 && type >= 1 && type <= 3;
}

static int coreIsDust(uint64_t value, const uint8_t *s, size_t len, int ddTx)
{
    int exempt = ddTx && value == 0 && len == 34 && s[0] == 0x51;                 // IsDigiDollarDustExemptOutput
    return value < coreDustThreshold(s, len) && !exempt;
}

// ---------------------------------------------------------------- cases ----

static void quote_is_fee_built(void)
{
    const uint64_t coins[6] = { 2000000, 2000000, 2000000, 2000000, 2000000, 2000000 };
    BRWallet *w = walletWithCoins(coins, 6);
    char dest[75]; externalAddress(dest, sizeof(dest));
    BRTransaction *tx = BRWalletCreateTransactionAtFeePerKb(w, 11000000, dest, DEFAULT_FEE_PER_KB);
    ck(tx && tx->inCount == 6, "the send gathers all six coins");
    if (!tx) { BRWalletFree(w); return; }
    BRTransaction *bytes = roundTrip(tx);
    uint64_t fee = 0, vsize = 0;
    int ok = bytes && prepared_send_quote(w, bytes, &fee, &vsize);
    printf("NOTE: six inputs, %zu outputs, %zu vB as built; the transaction pays %llu sat\n",
           tx->outCount, BRTransactionVSize(tx), (unsigned long long)paid(tx));
    ck(ok, "the built bytes have a quote");
    ck(fee == paid(tx), "the quote is the transaction's inputs minus its outputs: quoted %llu sat, it pays %llu sat",
       (unsigned long long)fee, (unsigned long long)paid(tx));
    ck(vsize == BRTransactionVSize(tx), "the quote's size is the transaction's own: quoted %llu vB, built %zu vB",
       (unsigned long long)vsize, BRTransactionVSize(tx));
    ck(fee != TYPICAL_FIGURE, "the quote is not the typical 141-byte figure (%llu sat)", (unsigned long long)TYPICAL_FIGURE);
    ck(fee >= vsize * 100, "the fee is at least 100 sat/vB on that size");
    if (bytes) BRTransactionFree(bytes);
    BRTransactionFree(tx);
    BRWalletFree(w);
}

static void quote_unknown_parent(void)
{
    const uint64_t coins[2] = { 3000000, 3000000 };
    BRWallet *w = walletWithCoins(coins, 2);
    char dest[75]; externalAddress(dest, sizeof(dest));
    BRTransaction *tx = BRWalletCreateTransactionAtFeePerKb(w, 1000000, dest, DEFAULT_FEE_PER_KB);
    ck(tx != NULL, "a send is built");
    if (!tx) { BRWalletFree(w); return; }
    BRTransaction *bytes = roundTrip(tx);
    BRWalletRemoveTransaction(w, tx->inputs[0].txHash);   // the wallet no longer holds the coin's transaction
    uint64_t fee = 0, vsize = 0;
    int ok = bytes && prepared_send_quote(w, bytes, &fee, &vsize);
    ck(!ok, "bytes spending a coin of a transaction the wallet does not hold have no quote (got %llu sat)",
       (unsigned long long)fee);
    if (bytes) BRTransactionFree(bytes);
    BRTransactionFree(tx);
    BRWalletFree(w);
}

static void floor_and_round(void)
{
    const uint64_t coins[3] = { 2000000, 2000000, 2000000 };
    BRWallet *w = walletWithCoins(coins, 3);
    char dest[75]; externalAddress(dest, sizeof(dest));
    size_t priced = 0;
    uint64_t atDefault = buildFee(w, 3000000, dest, DEFAULT_FEE_PER_KB, &priced);
    printf("NOTE: priced at %zu vB; %llu sat at the default rate\n", priced, (unsigned long long)atDefault);
    ck(atDefault == priced * 100, "the default rate pays 100 sat/vB on the priced size");
    const uint64_t under[] = { 1, 1000, 50000, 99999 };
    for (size_t i = 0; i < sizeof(under) / sizeof(under[0]); i++) {
        uint64_t f = buildFee(w, 3000000, dest, under[i], NULL);
        ck(f == atDefault, "a rate of %llu sat/kB is raised to the floor: pays %llu sat", (unsigned long long)under[i], (unsigned long long)f);
    }
    uint64_t at150 = buildFee(w, 3000000, dest, 150000, &priced);
    uint64_t want = ((priced * 150000 / 1000 + 99) / 100) * 100;
    ck(at150 == want, "150 sat/vB pays the priced size at that rate rounded up to 100 sat: %llu (want %llu)",
       (unsigned long long)at150, (unsigned long long)want);
    uint64_t at123 = buildFee(w, 3000000, dest, 123457, &priced);
    ck(at123 % 100 == 0 && at123 > atDefault, "an odd rate's fee is whole hundreds too: %llu", (unsigned long long)at123);
    BRWalletFree(w);
}

static void balance_round_off(void)
{
    const uint64_t coins[2] = { 2000037, 2000000 };   // a balance that is not whole hundreds
    BRWallet *w = walletWithCoins(coins, 2);
    char dest[75]; externalAddress(dest, sizeof(dest));
    BRTransaction *tx = BRWalletCreateTransactionAtFeePerKb(w, 3000000, dest, DEFAULT_FEE_PER_KB);
    ck(tx != NULL, "a send is built");
    if (!tx) { BRWalletFree(w); return; }
    uint64_t fee = paid(tx);
    ck(fee % 100 == 37, "the fee carries the balance's odd 37 sat: %llu", (unsigned long long)fee);
    ck((4000037 - 3000000 - fee) % 100 == 0, "the wallet keeps a balance in whole hundreds");
    BRTransactionFree(tx);
    BRWalletFree(w);
}

static void quote_includes_round_off(void)
{
    const uint64_t coins[2] = { 2000037, 2000000 };
    BRWallet *w = walletWithCoins(coins, 2);
    char dest[75]; externalAddress(dest, sizeof(dest));
    BRTransaction *tx = BRWalletCreateTransactionAtFeePerKb(w, 3000000, dest, DEFAULT_FEE_PER_KB);
    ck(tx != NULL, "a send is built");
    if (!tx) { BRWalletFree(w); return; }
    BRTransaction *bytes = roundTrip(tx);
    uint64_t q = 0, v = 0;
    int ok = bytes && prepared_send_quote(w, bytes, &q, &v);
    ck(ok && q == paid(tx), "the quote includes the round-off the transaction pays: quoted %llu, it pays %llu",
       (unsigned long long)q, (unsigned long long)paid(tx));
    if (bytes) BRTransactionFree(bytes);
    BRTransactionFree(tx);
    BRWalletFree(w);
}

// Review: a wallet of three coins, a send built, its bytes as the bridge holds them, and what was
// approved for it -- the payment (destination script and amount) and the transaction's own fee (the
// quote cases above pin that the quote is this).
typedef struct {
    BRWallet *w; BRTransaction *built, *bytes; uint64_t approved; char dest[75];
    uint8_t payScript[64]; size_t payScriptLen; uint64_t payAmount;
} Prepared;

static int prepare(Prepared *p)
{
    const uint64_t coins[3] = { 2000000, 2000000, 2000000 };
    memset(p, 0, sizeof(*p));
    p->w = walletWithCoins(coins, 3);
    externalAddress(p->dest, sizeof(p->dest));
    p->payAmount = 3000000;
    p->payScriptLen = BRAddressScriptPubKey(p->payScript, sizeof(p->payScript), p->dest);
    p->built = BRWalletCreateTransactionAtFeePerKb(p->w, p->payAmount, p->dest, DEFAULT_FEE_PER_KB);
    if (!p->built) return 0;
    p->bytes = roundTrip(p->built);
    p->approved = paid(p->built);
    return p->bytes != NULL;
}

static PreparedSendVerdict checkAsApproved(const Prepared *p, const BRTransaction *tx, uint64_t fee)
{
    return prepared_send_check(p->w, tx, p->payScript, p->payScriptLen, p->payAmount, fee);
}

// The prepared send with its outputs replaced: the same inputs, so the same coins -- and, when the
// outputs add up the same, the same fee. Returned as the bridge would hold it (serialized, parsed).
static BRTransaction *withOutputs(const BRTransaction *src, const BRTxOutput *outs, size_t n)
{
    BRTransaction *t = BRTransactionNew();
    t->version = src->version;
    t->lockTime = src->lockTime;
    for (size_t i = 0; i < src->inCount; i++) {
        const BRTxInput *in = &src->inputs[i];
        BRTransactionAddInput(t, in->txHash, in->index, in->amount, in->script, in->scriptLen, NULL, 0, NULL, 0, in->sequence);
    }
    for (size_t i = 0; i < n; i++) BRTransactionAddOutput(t, outs[i].amount, outs[i].script, outs[i].scriptLen);
    BRTransaction *bytes = roundTrip(t);
    BRTransactionFree(t);
    return bytes;
}

// The built send's change output (the one that is not the payment), or NULL.
static const BRTxOutput *changeOf(const Prepared *p)
{
    for (size_t i = 0; i < p->built->outCount; i++) {
        if (strcmp(p->built->outputs[i].address, p->dest) != 0) return &p->built->outputs[i];
    }
    return NULL;
}

static void release(Prepared *p)
{
    if (p->bytes) BRTransactionFree(p->bytes);
    if (p->built) BRTransactionFree(p->built);
    if (p->w) BRWalletFree(p->w);
}

static const char *verdictName(PreparedSendVerdict v)
{
    switch (v) {
    case PREPARED_SEND_OK: return "OK";
    case PREPARED_SEND_FEE_UNKNOWN: return "FEE_UNKNOWN";
    case PREPARED_SEND_FEE_CHANGED: return "FEE_CHANGED";
    case PREPARED_SEND_COIN_UNAVAILABLE: return "COIN_UNAVAILABLE";
    case PREPARED_SEND_NOT_APPROVED: return "NOT_APPROVED";
    }
    return "?";
}

static void check_approved_signs(void)
{
    Prepared p;
    ck(prepare(&p), "review prepares a send");
    if (!p.bytes) { release(&p); return; }
    PreparedSendVerdict before = checkAsApproved(&p, p.bytes, p.approved);
    ck(before == PREPARED_SEND_OK, "bytes paying the approved fee pass before signing (%s)", verdictName(before));
    int s = BRWalletSignTransaction(p.w, p.bytes, 0, g_seed, sizeof(g_seed));
    ck(s && BRTransactionIsSigned(p.bytes), "the prepared bytes are signed");
    PreparedSendVerdict after = checkAsApproved(&p, p.bytes, p.approved);
    ck(after == PREPARED_SEND_OK, "the signed transaction pays the approved fee (%s)", verdictName(after));
    BRTransaction *again = roundTrip(p.bytes);
    ck(again && BRTransactionIsSigned(again) && BRWalletFeeForTx(p.w, again) == p.approved,
       "the signed bytes, read back, pay exactly %llu sat", (unsigned long long)p.approved);
    if (again) BRTransactionFree(again);
    release(&p);
}

static void check_fee_lower(void)
{
    Prepared p;
    ck(prepare(&p), "review prepares a send");
    if (!p.bytes) { release(&p); return; }
    PreparedSendVerdict v = checkAsApproved(&p, p.bytes, p.approved - 1);
    ck(v == PREPARED_SEND_FEE_CHANGED, "bytes paying 1 sat more than approved are refused before signing (%s)", verdictName(v));
    release(&p);
}

static void check_fee_higher(void)
{
    Prepared p;
    ck(prepare(&p), "review prepares a send");
    if (!p.bytes) { release(&p); return; }
    PreparedSendVerdict v = checkAsApproved(&p, p.bytes, p.approved + 100);
    ck(v == PREPARED_SEND_FEE_CHANGED, "bytes paying less than approved are refused before signing (%s)", verdictName(v));
    release(&p);
}

static void check_coin_spent(void)
{
    Prepared p;
    ck(prepare(&p), "review prepares a send");
    if (!p.bytes) { release(&p); return; }
    // While the prompt is up, another send spends the same coins and the wallet registers it.
    BRTransaction *other = BRWalletCreateTransactionAtFeePerKb(p.w, 3000000, p.dest, DEFAULT_FEE_PER_KB);
    int signedOther = other && BRWalletSignTransaction(p.w, other, 0, g_seed, sizeof(g_seed));
    int registered = signedOther && BRWalletRegisterTransaction(p.w, other);
    ck(registered, "a second send of the same coins is registered");
    if (!registered && other) BRTransactionFree(other);
    PreparedSendVerdict v = checkAsApproved(&p, p.bytes, p.approved);
    ck(v == PREPARED_SEND_COIN_UNAVAILABLE, "bytes spending a coin another send has spent are refused before signing (%s)", verdictName(v));
    release(&p);
}

static void check_coin_held(void)
{
    Prepared p;
    ck(prepare(&p), "review prepares a send");
    if (!p.bytes) { release(&p); return; }
    // While the prompt is up, asset detection holds one of the coins out of the plain set.
    ck(BRWalletRegisterAssetOutpoint(p.w, p.bytes->inputs[0].txHash, p.bytes->inputs[0].index),
       "the coin is held as an asset carrier");
    PreparedSendVerdict v = checkAsApproved(&p, p.bytes, p.approved);
    ck(v == PREPARED_SEND_COIN_UNAVAILABLE, "bytes spending a coin now held as an asset carrier are refused before signing (%s)", verdictName(v));
    release(&p);
}

static void check_parent_gone(void)
{
    Prepared p;
    ck(prepare(&p), "review prepares a send");
    if (!p.bytes) { release(&p); return; }
    BRWalletRemoveTransaction(p.w, p.bytes->inputs[0].txHash);
    PreparedSendVerdict v = checkAsApproved(&p, p.bytes, p.approved);
    ck(v == PREPARED_SEND_FEE_UNKNOWN, "bytes spending a coin of a transaction the wallet no longer holds are refused (%s)", verdictName(v));
    release(&p);
}

// A foreign native-segwit script, for bytes that pay someone the user did not approve.
static void foreignScript(uint8_t out[22], uint8_t fill)
{
    out[0] = 0x00; out[1] = 0x14;
    memset(out + 2, fill, 20);
}

static void check_recipient_changed(void)
{
    Prepared p;
    ck(prepare(&p), "review prepares a send");
    const BRTxOutput *change = p.bytes ? changeOf(&p) : NULL;
    ck(change != NULL, "the built send has a change output");
    if (!change) { release(&p); return; }
    uint8_t other[22]; foreignScript(other, 0x6b);
    BRTxOutput outs[2] = { { "", p.payAmount, other, sizeof(other) }, { "", change->amount, change->script, change->scriptLen } };
    BRTransaction *altered = withOutputs(p.built, outs, 2);
    ck(altered && BRWalletFeeForTx(p.w, altered) == p.approved, "the altered bytes pay the same fee");
    PreparedSendVerdict v = altered ? checkAsApproved(&p, altered, p.approved) : PREPARED_SEND_OK;
    ck(v == PREPARED_SEND_NOT_APPROVED, "bytes paying the approved amount to another recipient are refused before signing (%s)", verdictName(v));
    if (altered) BRTransactionFree(altered);
    release(&p);
}

static void check_amount_changed(void)
{
    Prepared p;
    ck(prepare(&p), "review prepares a send");
    const BRTxOutput *change = p.bytes ? changeOf(&p) : NULL;
    ck(change != NULL, "the built send has a change output");
    if (!change) { release(&p); return; }
    BRTxOutput outs[2] = { { "", p.payAmount - 100000, p.payScript, p.payScriptLen },
                           { "", change->amount + 100000, change->script, change->scriptLen } };
    BRTransaction *altered = withOutputs(p.built, outs, 2);
    ck(altered && BRWalletFeeForTx(p.w, altered) == p.approved, "the altered bytes pay the same fee");
    PreparedSendVerdict v = altered ? checkAsApproved(&p, altered, p.approved) : PREPARED_SEND_OK;
    ck(v == PREPARED_SEND_NOT_APPROVED, "bytes paying the approved recipient another amount are refused before signing (%s)", verdictName(v));
    if (altered) BRTransactionFree(altered);
    release(&p);
}

static void check_extra_output(void)
{
    Prepared p;
    ck(prepare(&p), "review prepares a send");
    const BRTxOutput *change = p.bytes ? changeOf(&p) : NULL;
    ck(change != NULL, "the built send has a change output");
    if (!change) { release(&p); return; }
    uint8_t other[22]; foreignScript(other, 0x6c);
    BRTxOutput outs[3] = { { "", p.payAmount, p.payScript, p.payScriptLen },
                           { "", change->amount - 100000, change->script, change->scriptLen },
                           { "", 100000, other, sizeof(other) } };
    BRTransaction *altered = withOutputs(p.built, outs, 3);
    ck(altered && BRWalletFeeForTx(p.w, altered) == p.approved, "the altered bytes pay the same fee");
    PreparedSendVerdict v = altered ? checkAsApproved(&p, altered, p.approved) : PREPARED_SEND_OK;
    ck(v == PREPARED_SEND_NOT_APPROVED, "bytes adding a payment nobody approved are refused before signing (%s)", verdictName(v));
    if (altered) BRTransactionFree(altered);
    release(&p);
}

static void check_change_foreign(void)
{
    Prepared p;
    ck(prepare(&p), "review prepares a send");
    const BRTxOutput *change = p.bytes ? changeOf(&p) : NULL;
    ck(change != NULL, "the built send has a change output");
    if (!change) { release(&p); return; }
    uint8_t other[22]; foreignScript(other, 0x6d);
    BRTxOutput outs[2] = { { "", p.payAmount, p.payScript, p.payScriptLen }, { "", change->amount, other, sizeof(other) } };
    BRTransaction *altered = withOutputs(p.built, outs, 2);
    ck(altered && BRWalletFeeForTx(p.w, altered) == p.approved, "the altered bytes pay the same fee");
    PreparedSendVerdict v = altered ? checkAsApproved(&p, altered, p.approved) : PREPARED_SEND_OK;
    ck(v == PREPARED_SEND_NOT_APPROVED, "bytes sending the change to an address not the wallet's are refused before signing (%s)", verdictName(v));
    if (altered) BRTransactionFree(altered);
    release(&p);
}

static void check_self_send_signs(void)
{
    const uint64_t coins[3] = { 2000000, 2000000, 2000000 };
    BRWallet *w = walletWithCoins(coins, 3);
    BRAddress own = BRWalletReceiveAddress(w, 1);
    uint8_t script[64]; size_t scriptLen = BRAddressScriptPubKey(script, sizeof(script), own.s);
    BRTransaction *tx = BRWalletCreateTransactionAtFeePerKb(w, 3000000, own.s, DEFAULT_FEE_PER_KB);
    ck(tx != NULL, "a send to one of the wallet's own addresses is built");
    if (!tx) { BRWalletFree(w); return; }
    BRTransaction *bytes = roundTrip(tx);
    PreparedSendVerdict v = bytes ? prepared_send_check(w, bytes, script, scriptLen, 3000000, paid(tx)) : PREPARED_SEND_NOT_APPROVED;
    ck(v == PREPARED_SEND_OK, "the approved payment to the wallet's own address, plus change, passes (%s)", verdictName(v));
    if (bytes) BRTransactionFree(bytes);
    BRTransactionFree(tx);
    BRWalletFree(w);
}

// A wallet holding one confirmed $100.00 DigiDollar coin and the DGB coin `sats` (dd_fee_rate_kat's shape).
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
    BRAddress da = BRWalletReceiveAddress(w, 1);
    BRWalletRegisterTransaction(w, coinTx(da.s, sats, 0xC1));
    return w;
}

static void dd_transfer_builds(void)
{
    BRWallet *w = ddWallet(50000000);
    BRTransaction *tx = BRWalletCreateDigiDollarTransfer(w, kRecip, 5000);   // $50.00
    ck(tx != NULL, "a DigiDollar transfer of $50.00 builds");
    if (!tx) { BRWalletFree(w); return; }
    int ddTx = coreIsDigiDollarTx(tx->version);
    ck(ddTx && BRDigiDollarTxType(tx) == DD_TYPE_TRANSFER, "it carries the DigiDollar transfer marker (version 0x%08x)", tx->version);
    int tokens = 0;
    for (size_t i = 0; i < tx->outCount; i++) {
        const BRTxOutput *o = &tx->outputs[i];
        ck(!coreIsDust(o->amount, o->script, o->scriptLen, ddTx),
           "output %zu (%llu sat, %zu-byte script) is relayable under Core's dust rule", i,
           (unsigned long long)o->amount, o->scriptLen);
        if (o->amount == 0 && o->scriptLen == 34 && o->script[0] == 0x51) {
            tokens++;
            ck(coreIsDust(o->amount, o->script, o->scriptLen, 0),
               "output %zu is a token output Core lets through only by its DigiDollar exemption", i);
        }
    }
    ck(tokens >= 1, "the transfer has %d zero-value token output(s)", tokens);
    BRTransactionFree(tx);
    BRWalletFree(w);
}

typedef struct { const char *name; void (*run)(void); } Case;
static const Case kCases[] = {
    { "quote_is_fee_built", quote_is_fee_built },
    { "quote_unknown_parent", quote_unknown_parent },
    { "floor_and_round", floor_and_round },
    { "balance_round_off", balance_round_off },
    { "quote_includes_round_off", quote_includes_round_off },
    { "check_approved_signs", check_approved_signs },
    { "check_fee_lower", check_fee_lower },
    { "check_fee_higher", check_fee_higher },
    { "check_coin_spent", check_coin_spent },
    { "check_coin_held", check_coin_held },
    { "check_parent_gone", check_parent_gone },
    { "check_recipient_changed", check_recipient_changed },
    { "check_amount_changed", check_amount_changed },
    { "check_extra_output", check_extra_output },
    { "check_change_foreign", check_change_foreign },
    { "check_self_send_signs", check_self_send_signs },
    { "dd_transfer_builds", dd_transfer_builds },
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
        printf("ARM: %s\n", PREPARED_SEND_UNFIXED ? "comparison (PREPARED_SEND_UNFIXED=1)" : "shipped");
        kCases[i].run();
        printf("RESULT %s %s\n", kCases[i].name, g_failed ? "fail" : "pass");
        return g_failed;
    }
    fprintf(stderr, "unknown case %s\n", argv[1]);
    return 2;
}
