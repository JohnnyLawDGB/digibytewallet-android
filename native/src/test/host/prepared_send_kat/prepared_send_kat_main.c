// Host KAT: the core's BRPreparedSend.h at its edges -- the quote and the pre-sign check of a DGB
// send, as the Android bridge (quoteTransaction, signPreparedTransaction) and the iOS send path
// call them.
//
// THE INVARIANT. The signer signs only the approved payment, at most one change output back to
// the wallet and the approved fee, from coins that are still the wallet's spendable plain coins,
// and a confirmation shows the fee of the transaction that is signed or none at all.
//
// fee_quote_kat runs the core rules against wallets the core builds and signs (the quote is the
// built fee and size, an unknown parent has no quote, each changed fee, spent or held coin, other
// recipient, other amount, extra output and foreign change is refused, the approved send and a
// send to the wallet's own address pass). This suite covers what a second caller meets and that
// suite does not, on the same vectors (its mnemonic, its 3 x 2,000,000 sat wallet, its foreign
// scripts):
//   - no answer is ever invented: no wallet, no transaction, no inputs, nowhere to write the quote;
//   - no approved payment (no script, an empty or truncated one, a zero amount) approves nothing;
//   - the output shapes the rule takes (payment only; change before payment; the payment twice to
//     the wallet itself) and the ones it refuses (two change outputs, the payment twice to someone
//     else, a zero-value change output, change and no payment);
//   - a DigiDollar coin is not a plain coin, so bytes that spend one are refused;
//   - the check after signing refuses a signed transaction whose coin was spent meanwhile;
//   - the verdict values are fixed (the bridge logs them; Swift sees their raw values).
//
// DIFFERENTIAL (opt-in, run.sh: PREPARED_SEND_REFERENCE=<file>): with -DPREPARED_SEND_DIFFERENTIAL=1
// and that file force-included -- the Android bridge's former prepared_send.h, e.g.
// `git show v4.1.0:native/src/main/jni/bridge/prepared_send.h` -- case "differential" runs both
// headers on the same seeded scenarios and requires the same answer from each, every time.
//
// MACRO CONVENTION: the header tests the VALUE of PREPARED_SEND_UNFIXED (#if): run.sh builds the
// comparison arm with -DPREPARED_SEND_UNFIXED=1 (the typical figure, nothing checked) and the
// shipped arm with =0.
//
// Every case runs; each prints RESULT <case> pass|fail. "list" names them; a case name runs one.
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
#include "BRPreparedSend.h"
#include "BRPreparedSend.h"   // twice: the include guard holds

static int g_caseFailed = 0, g_anyFailed = 0;

static void ck(int cond, const char *fmt, ...)
{
    va_list ap;
    printf(cond ? "PASS: " : "FAIL: ");
    va_start(ap, fmt);
    vprintf(fmt, ap);
    va_end(ap);
    printf("\n");
    if (!cond) g_caseFailed = 1;
}

// ---- fee_quote_kat's vectors ----------------------------------------------------------------
static const char *kMnemonic =
    "abandon abandon abandon abandon abandon abandon abandon abandon "
    "abandon abandon abandon about";
static uint8_t g_seed[64];
static BRMasterPubKey g_mpk84, g_mpk86;
static const uint8_t kPlaceholder[1] = {0};

static void finalizeTxHash(BRTransaction *tx)
{
    size_t len = BRTransactionSerialize(tx, NULL, 0);
    uint8_t *data = malloc(len ? len : 1);
    if (!data) return;
    len = BRTransactionSerialize(tx, data, len);
    BRTransaction *t = BRTransactionParse(data, len);
    if (t) { tx->txHash = t->txHash; tx->wtxHash = t->wtxHash; BRTransactionFree(t); }
    free(data);
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

// A wallet holding confirmed plain coins of sats[0..n), paying its native-segwit address (*coinAddr).
static BRWallet *walletWithCoins(const uint64_t *sats, size_t n, BRAddress *coinAddr)
{
    BRWallet *w = BRWalletNew(NULL, 0, g_mpk84);
    BRAddress a = BRWalletReceiveAddress(w, 1);
    for (size_t i = 0; i < n; i++) BRWalletRegisterTransaction(w, coinTx(a.s, sats[i], (uint8_t)(0xA0 + i)));
    if (coinAddr) *coinAddr = a;
    return w;
}

// A foreign native-segwit script, for bytes that pay someone the user did not approve.
static size_t foreignScript(uint8_t out[22], uint8_t fill)
{
    out[0] = 0x00; out[1] = 0x14;
    memset(out + 2, fill, 20);
    return 22;
}

static uint64_t paid(const BRTransaction *tx)
{
    uint64_t in = 0, out = 0;
    for (size_t i = 0; i < tx->inCount; i++) in += tx->inputs[i].amount;
    for (size_t i = 0; i < tx->outCount; i++) out += tx->outputs[i].amount;
    return in - out;
}

// The bytes the bridge hands its platform, parsed again as the bridge parses them.
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

// src's inputs with these outputs, as the bridge would hold them (serialized, parsed).
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

// Review: fee_quote_kat's wallet of three 2,000,000 sat coins, a send of payAmount to a foreign
// address built at the default rate, its bytes, and what was approved (the payment and the fee).
typedef struct {
    BRWallet *w; BRTransaction *built, *bytes; BRAddress coinAddr; char dest[75];
    uint8_t payScript[64]; size_t payScriptLen; uint64_t payAmount, approved;
} Prepared;

static int prepare(Prepared *p, uint64_t payAmount)
{
    const uint64_t coins[3] = { 2000000, 2000000, 2000000 };
    uint8_t spk[22];
    memset(p, 0, sizeof(*p));
    p->w = walletWithCoins(coins, 3, &p->coinAddr);
    foreignScript(spk, 0x5a);
    BRAddressFromScriptPubKey(p->dest, sizeof(p->dest), spk, sizeof(spk));
    p->payAmount = payAmount;
    p->payScriptLen = BRAddressScriptPubKey(p->payScript, sizeof(p->payScript), p->dest);
    p->built = BRWalletCreateTransactionAtFeePerKb(p->w, p->payAmount, p->dest, DEFAULT_FEE_PER_KB);
    if (!p->built) return 0;
    p->bytes = roundTrip(p->built);
    p->approved = paid(p->built);
    return p->bytes != NULL;
}

static void release(Prepared *p)
{
    if (p->bytes) BRTransactionFree(p->bytes);
    if (p->built) BRTransactionFree(p->built);
    if (p->w) BRWalletFree(p->w);
}

static const BRTxOutput *changeOf(const Prepared *p)
{
    for (size_t i = 0; i < p->built->outCount; i++) {
        if (strcmp(p->built->outputs[i].address, p->dest) != 0) return &p->built->outputs[i];
    }
    return NULL;
}

static const char *verdictName(BRPreparedSendVerdict v)
{
    switch (v) {
    case BRPreparedSendOK: return "OK";
    case BRPreparedSendFeeUnknown: return "FEE_UNKNOWN";
    case BRPreparedSendFeeChanged: return "FEE_CHANGED";
    case BRPreparedSendCoinUnavailable: return "COIN_UNAVAILABLE";
    case BRPreparedSendNotApproved: return "NOT_APPROVED";
    }
    return "?";
}

// The verdict for the prepared send's inputs carrying these outputs, approved as payAmount to the
// prepared recipient at the outputs' own fee: only the output rule can refuse it.
static BRPreparedSendVerdict checkShape(const Prepared *p, const BRTxOutput *outs, size_t n, uint64_t payAmount,
                                        const char *what)
{
    BRTransaction *t = withOutputs(p->built, outs, n);
    uint64_t fee = t ? BRWalletFeeForTx(p->w, t) : UINT64_MAX;
    ck(t && fee != UINT64_MAX && fee > 0, "%s: built, paying %llu sat", what, (unsigned long long)fee);
    BRPreparedSendVerdict v = t ? BRPreparedSendCheck(p->w, t, p->payScript, p->payScriptLen, payAmount, fee)
                                : BRPreparedSendOK;
    if (t) BRTransactionFree(t);
    return v;
}

// ---------------------------------------------------------------- cases ----

static void quote_never_invents(void)
{
    Prepared p;
    ck(prepare(&p, 3000000), "review prepares a send");
    if (!p.bytes) { release(&p); return; }
    uint64_t fee = 7, vsize = 7;
    ck(!BRPreparedSendQuote(NULL, p.bytes, &fee, &vsize), "no wallet: no quote");
    ck(!BRPreparedSendQuote(p.w, NULL, &fee, &vsize), "no transaction: no quote");
    BRTransaction *bare = BRTransactionNew();
    ck(!BRPreparedSendQuote(p.w, bare, &fee, &vsize), "a transaction with no inputs and no outputs: no quote");
    BRTransactionAddOutput(bare, 1000, p.payScript, p.payScriptLen);
    ck(!BRPreparedSendQuote(p.w, bare, &fee, &vsize), "a transaction with no inputs: no quote");
    BRTransactionFree(bare);
    ck(fee == 7 && vsize == 7, "a refusal leaves both outputs as they were (%llu, %llu)",
       (unsigned long long)fee, (unsigned long long)vsize);
    if (!g_caseFailed) {   // the comparison arm writes through these pointers: reached only by an arm that refuses
        ck(!BRPreparedSendQuote(p.w, p.bytes, NULL, &vsize), "nowhere to write the fee: no quote");
        ck(!BRPreparedSendQuote(p.w, p.bytes, &fee, NULL), "nowhere to write the size: no quote");
    }
    ck(BRPreparedSendQuote(p.w, p.bytes, &fee, &vsize) && fee == p.approved,
       "the same bytes with everything given are quoted at what they pay (%llu sat)", (unsigned long long)fee);
    release(&p);
}

static void check_needs_wallet_and_tx(void)
{
    Prepared p;
    ck(prepare(&p, 3000000), "review prepares a send");
    if (!p.bytes) { release(&p); return; }
    BRPreparedSendVerdict v = BRPreparedSendCheck(NULL, p.bytes, p.payScript, p.payScriptLen, p.payAmount, p.approved);
    ck(v == BRPreparedSendFeeUnknown, "no wallet: refused, the fee cannot be told (%s)", verdictName(v));
    v = BRPreparedSendCheck(p.w, NULL, p.payScript, p.payScriptLen, p.payAmount, p.approved);
    ck(v == BRPreparedSendFeeUnknown, "no transaction (bytes that did not parse): refused (%s)", verdictName(v));
    v = BRPreparedSendCheck(p.w, p.bytes, p.payScript, p.payScriptLen, p.payAmount, p.approved);
    ck(v == BRPreparedSendOK, "the same bytes with both given pass (%s)", verdictName(v));
    release(&p);
}

static void check_needs_an_approved_payment(void)
{
    Prepared p;
    ck(prepare(&p, 3000000), "review prepares a send");
    if (!p.bytes) { release(&p); return; }
    BRPreparedSendVerdict v = BRPreparedSendCheck(p.w, p.bytes, NULL, p.payScriptLen, p.payAmount, p.approved);
    ck(v == BRPreparedSendNotApproved, "no payment script: refused (%s)", verdictName(v));
    v = BRPreparedSendCheck(p.w, p.bytes, p.payScript, 0, p.payAmount, p.approved);
    ck(v == BRPreparedSendNotApproved, "an empty payment script: refused (%s)", verdictName(v));
    v = BRPreparedSendCheck(p.w, p.bytes, p.payScript, p.payScriptLen - 1, p.payAmount, p.approved);
    ck(v == BRPreparedSendNotApproved, "the payment script one byte short: refused (%s)", verdictName(v));
    v = BRPreparedSendCheck(p.w, p.bytes, p.payScript, p.payScriptLen, 0, p.approved);
    ck(v == BRPreparedSendNotApproved, "a payment of 0 sat: refused (%s)", verdictName(v));
    const BRTxOutput *change = changeOf(&p);
    if (change) {   // even when the bytes do pay 0 sat to the recipient
        BRTxOutput outs[2] = { { "", 0, p.payScript, p.payScriptLen }, { "", change->amount, change->script, change->scriptLen } };
        v = checkShape(&p, outs, 2, 0, "0 sat to the recipient, and the change");
        ck(v == BRPreparedSendNotApproved, "bytes paying the recipient 0 sat, approved as a payment of 0 sat: refused (%s)",
           verdictName(v));
    }
    release(&p);
}

static void payment_only_passes(void)
{
    Prepared p;
    ck(prepare(&p, 3000000), "review prepares a send");
    if (!p.bytes) { release(&p); return; }
    BRTxOutput outs[1] = { { "", p.payAmount, p.payScript, p.payScriptLen } };
    BRPreparedSendVerdict v = checkShape(&p, outs, 1, p.payAmount, "the payment alone, no change");
    ck(v == BRPreparedSendOK, "the approved payment with no change output passes (%s)", verdictName(v));
    release(&p);
}

static void change_before_payment_passes(void)
{
    Prepared p;
    ck(prepare(&p, 3000000), "review prepares a send");
    const BRTxOutput *change = p.bytes ? changeOf(&p) : NULL;
    ck(change != NULL, "the built send has a change output");
    if (!change) { release(&p); return; }
    BRTxOutput outs[2] = { { "", change->amount, change->script, change->scriptLen },
                           { "", p.payAmount, p.payScript, p.payScriptLen } };
    BRPreparedSendVerdict v = checkShape(&p, outs, 2, p.payAmount, "change first, then the payment");
    ck(v == BRPreparedSendOK, "order does not matter: change before the payment passes (%s)", verdictName(v));
    release(&p);
}

static void payment_twice_to_self_passes(void)
{
    const uint64_t coins[3] = { 2000000, 2000000, 2000000 };
    BRAddress own;
    BRWallet *w = walletWithCoins(coins, 3, &own);
    uint8_t script[64];
    size_t scriptLen = BRAddressScriptPubKey(script, sizeof(script), own.s);
    BRTransaction *built = BRWalletCreateTransactionAtFeePerKb(w, 3000000, own.s, DEFAULT_FEE_PER_KB);
    ck(built && built->inCount == 2, "a send to the wallet's own address is built from two coins");
    if (!built) { BRWalletFree(w); return; }
    // Its two coins (4,000,000 sat) carrying 1,990,000 sat twice to that address.
    BRTxOutput outs[2] = { { "", 1990000, script, scriptLen }, { "", 1990000, script, scriptLen } };
    BRTransaction *t = withOutputs(built, outs, 2);
    uint64_t fee = t ? BRWalletFeeForTx(w, t) : UINT64_MAX;
    ck(fee == 20000, "the outputs leave a 20,000 sat fee (%llu)", (unsigned long long)fee);
    BRPreparedSendVerdict v = t ? BRPreparedSendCheck(w, t, script, scriptLen, 1990000, fee) : BRPreparedSendNotApproved;
    ck(v == BRPreparedSendOK, "the payment twice to the wallet's own address: the second is change to the wallet (%s)",
       verdictName(v));
    if (t) BRTransactionFree(t);
    BRTransactionFree(built);
    BRWalletFree(w);
}

static void two_change_outputs_refused(void)
{
    Prepared p;
    ck(prepare(&p, 3000000), "review prepares a send");
    const BRTxOutput *change = p.bytes ? changeOf(&p) : NULL;
    ck(change != NULL, "the built send has a change output");
    if (!change) { release(&p); return; }
    uint8_t own[64];
    size_t ownLen = BRAddressScriptPubKey(own, sizeof(own), p.coinAddr.s);
    BRTxOutput outs[3] = { { "", p.payAmount, p.payScript, p.payScriptLen },
                           { "", change->amount - 100000, change->script, change->scriptLen },
                           { "", 100000, own, ownLen } };
    BRPreparedSendVerdict v = checkShape(&p, outs, 3, p.payAmount, "the payment and two outputs to the wallet");
    ck(v == BRPreparedSendNotApproved, "a second change output, even to the wallet, is refused (%s)", verdictName(v));
    release(&p);
}

static void payment_twice_refused(void)
{
    Prepared p;
    ck(prepare(&p, 3000000), "review prepares a send");
    if (!p.bytes) { release(&p); return; }
    // Its two coins (4,000,000 sat) carrying 1,990,000 sat twice to the approved recipient.
    BRTxOutput outs[2] = { { "", 1990000, p.payScript, p.payScriptLen }, { "", 1990000, p.payScript, p.payScriptLen } };
    BRPreparedSendVerdict v = checkShape(&p, outs, 2, 1990000, "1,990,000 sat twice to the approved recipient");
    ck(v == BRPreparedSendNotApproved, "paying the approved recipient twice is refused (%s)", verdictName(v));
    release(&p);
}

static void zero_value_change_refused(void)
{
    Prepared p;
    ck(prepare(&p, 3000000), "review prepares a send");
    const BRTxOutput *change = p.bytes ? changeOf(&p) : NULL;
    ck(change != NULL, "the built send has a change output");
    if (!change) { release(&p); return; }
    BRTxOutput outs[2] = { { "", p.payAmount, p.payScript, p.payScriptLen }, { "", 0, change->script, change->scriptLen } };
    BRPreparedSendVerdict v = checkShape(&p, outs, 2, p.payAmount, "the payment and a 0 sat output to the change address");
    ck(v == BRPreparedSendNotApproved, "a zero-value change output is refused (%s)", verdictName(v));
    release(&p);
}

static void change_without_payment_refused(void)
{
    Prepared p;
    ck(prepare(&p, 3000000), "review prepares a send");
    const BRTxOutput *change = p.bytes ? changeOf(&p) : NULL;
    ck(change != NULL, "the built send has a change output");
    if (!change) { release(&p); return; }
    BRTxOutput outs[1] = { { "", change->amount, change->script, change->scriptLen } };
    BRPreparedSendVerdict v = checkShape(&p, outs, 1, p.payAmount, "the change alone");
    ck(v == BRPreparedSendNotApproved, "bytes paying only the wallet, not the approved payment, are refused (%s)",
       verdictName(v));
    release(&p);
}

// The payment output pays MORE than approved and the change is lowered by the same amount: the fee
// is unchanged, and the approved recipient is still paid, but not the approved amount. Refused: the
// amount must match exactly (BRPreparedSendPaysAsApproved's `o->amount == payAmount`, never `>=`;
// the former bridge prepared_send.h and TransactionBuilder compare the amount for equality too).
static void payment_raised_change_lowered_refused(void)
{
    Prepared p;
    ck(prepare(&p, 3000000), "review prepares a send");
    const BRTxOutput *change = p.bytes ? changeOf(&p) : NULL;
    ck(change != NULL && change->amount > 100000, "the built send has a change output over 100,000 sat");
    if (!change || change->amount <= 100000) { release(&p); return; }
    BRTxOutput outs[2] = { { "", p.payAmount + 100000, p.payScript, p.payScriptLen },
                           { "", change->amount - 100000, change->script, change->scriptLen } };
    BRPreparedSendVerdict v = checkShape(&p, outs, 2, p.payAmount,
                                         "100,000 sat more to the approved recipient, 100,000 sat less change");
    ck(v == BRPreparedSendNotApproved, "paying the recipient more than approved is refused (%s)", verdictName(v));
    release(&p);
}

// A confirmed parent pays the wallet two outputs. Output 1 is then spent by a registered send;
// output 0 is still unspent. Bytes spending output 1 again are refused: the coin is looked up by
// hash AND output index (BRPreparedSendHasCoin's `utxos[i].n == n`), not by its parent alone.
static void sibling_of_unspent_coin_refused(void)
{
    BRWallet *w = BRWalletNew(NULL, 0, g_mpk84);
    BRAddress a = BRWalletReceiveAddress(w, 1);
    uint8_t spk[64], pay[22], changeS[64];
    size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), a.s), payLen = foreignScript(pay, 0x7c), changeLen;
    BRTransaction *parent = BRTransactionNew(), *spend, *t;
    UInt256 ph, parentHash;
    memset(ph.u8, 0xB7, sizeof(ph.u8));
    parent->version = 1;
    BRTransactionAddInput(parent, ph, 0, 0, spk, spkLen, kPlaceholder, 0, kPlaceholder, 0, 0xffffffff);
    BRTransactionAddOutput(parent, 2000000, spk, spkLen);
    BRTransactionAddOutput(parent, 3000000, spk, spkLen);
    parent->blockHeight = 23700000;
    parent->timestamp = 1700000000;
    finalizeTxHash(parent);
    parentHash = parent->txHash;
    ck(BRWalletRegisterTransaction(w, parent), "the parent is registered");
    BRAddress ca = BRWalletReceiveAddress(w, 1);
    changeLen = BRAddressScriptPubKey(changeS, sizeof(changeS), ca.s);

    // Output 1 is spent by a signed, registered send to someone else.
    spend = BRTransactionNew();
    spend->version = 1;
    BRTransactionAddInput(spend, parentHash, 1, 3000000, spk, spkLen, NULL, 0, NULL, 0, 0xffffffff);
    BRTransactionAddOutput(spend, 2980000, pay, payLen);
    int registered = BRWalletSignTransaction(w, spend, 0, g_seed, sizeof(g_seed)) && BRWalletRegisterTransaction(w, spend);
    ck(registered, "a send of output 1 is signed and registered");
    if (!registered) BRTransactionFree(spend);

    size_t n = BRWalletUTXOs(w, NULL, 0);
    BRUTXO u[8];
    n = BRWalletUTXOs(w, u, n < 8 ? n : 8);
    ck(n == 1 && UInt256Eq(u[0].hash, parentHash) && u[0].n == 0, "the wallet's one coin is output 0 of the parent");
    ck(! BRPreparedSendHasCoin(u, n, parentHash, 1) && BRPreparedSendHasCoin(u, n, parentHash, 0),
       "output 1 of the parent is not a coin of the wallet; output 0 is");

    // Bytes spending output 1 again, paying 1,000,000 sat with change to the wallet.
    t = BRTransactionNew();
    t->version = 1;
    BRTransactionAddInput(t, parentHash, 1, 3000000, spk, spkLen, NULL, 0, NULL, 0, 0xffffffff);
    BRTransactionAddOutput(t, 1000000, pay, payLen);
    BRTransactionAddOutput(t, 1980000, changeS, changeLen);
    uint64_t fee = BRWalletFeeForTx(w, t);
    ck(fee == 20000, "the bytes' fee is told from the parent: %llu sat", (unsigned long long)fee);
    BRPreparedSendVerdict v = BRPreparedSendCheck(w, t, pay, payLen, 1000000, fee);
    ck(v == BRPreparedSendCoinUnavailable,
       "spending an output already spent, beside its unspent sibling, is refused (%s)", verdictName(v));
    // The same bytes from output 0, the coin still held, pass.
    t->inputs[0].index = 0;
    t->inputs[0].amount = 2000000;
    t->outputs[1].amount = 980000;
    fee = BRWalletFeeForTx(w, t);
    v = BRPreparedSendCheck(w, t, pay, payLen, 1000000, fee);
    ck(v == BRPreparedSendOK, "the same send from output 0 passes (%s, fee %llu sat)", verdictName(v),
       (unsigned long long)fee);
    BRTransactionFree(t);
    BRWalletFree(w);
}

// A wallet holding one confirmed DigiDollar coin and the DGB coin `sats` (fee_quote_kat's shape);
// *ddHash is the DigiDollar coin's transaction, *dgbHash the DGB coin's.
static BRWallet *ddWallet(uint64_t sats, UInt256 *ddHash, UInt256 *dgbHash)
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
    *ddHash = dd->txHash;
    BRWalletRegisterTransaction(w, dd);
    BRAddress da = BRWalletReceiveAddress(w, 1);
    BRTransaction *coin = coinTx(da.s, sats, 0xC1);
    *dgbHash = coin->txHash;
    BRWalletRegisterTransaction(w, coin);
    return w;
}

static void digidollar_coin_refused(void)
{
    UInt256 ddHash, dgbHash;
    BRWallet *w = ddWallet(50000000, &ddHash, &dgbHash);
    const BRTransaction *dgbTx = BRWalletTransactionForHash(w, dgbHash);
    const BRTransaction *ddTx = BRWalletTransactionForHash(w, ddHash);
    ck(dgbTx && ddTx, "the wallet holds its DGB coin and its DigiDollar coin");
    if (!dgbTx || !ddTx) { BRWalletFree(w); return; }
    uint8_t pay[22], change[64];
    size_t payLen = foreignScript(pay, 0x6e);
    BRAddress ca = BRWalletReceiveAddress(w, 1);
    size_t changeLen = BRAddressScriptPubKey(change, sizeof(change), ca.s);
    for (int withDD = 0; withDD <= 1; withDD++) {
        BRTransaction *t = BRTransactionNew();
        t->version = 1;
        BRTransactionAddInput(t, dgbHash, 0, 50000000, dgbTx->outputs[0].script, dgbTx->outputs[0].scriptLen,
                              NULL, 0, NULL, 0, 0xffffffff);
        if (withDD) BRTransactionAddInput(t, ddHash, 0, 0, ddTx->outputs[0].script, ddTx->outputs[0].scriptLen,
                                          NULL, 0, NULL, 0, 0xffffffff);
        BRTransactionAddOutput(t, 10000000, pay, payLen);
        BRTransactionAddOutput(t, 39980000, change, changeLen);
        // Checked as constructed, not round-tripped: the unsigned serialization carries each input's
        // amount, and this one (a 0 sat input) does not read back intact. The check reads only its
        // inputs and outputs.
        uint64_t fee = BRWalletFeeForTx(w, t);
        BRPreparedSendVerdict v = BRPreparedSendCheck(w, t, pay, payLen, 10000000, fee);
        if (withDD) ck(v == BRPreparedSendCoinUnavailable,
                       "bytes that also spend the wallet's DigiDollar coin are refused: it is not a plain coin (%s)",
                       verdictName(v));
        else ck(v == BRPreparedSendOK, "the same send from the DGB coin alone passes (%s, fee %llu sat)",
                verdictName(v), (unsigned long long)fee);
        BRTransactionFree(t);
    }
    BRWalletFree(w);
}

static void refused_after_signing(void)
{
    Prepared p;
    ck(prepare(&p, 3000000), "review prepares a send");
    if (!p.bytes) { release(&p); return; }
    BRPreparedSendVerdict v = BRPreparedSendCheck(p.w, p.bytes, p.payScript, p.payScriptLen, p.payAmount, p.approved);
    ck(v == BRPreparedSendOK, "before signing: the approved send passes (%s)", verdictName(v));
    int s = BRWalletSignTransaction(p.w, p.bytes, 0, g_seed, sizeof(g_seed));
    ck(s && BRTransactionIsSigned(p.bytes), "the prepared bytes are signed");
    // Between the signature and the check after it, another send of the same coins is registered.
    BRTransaction *other = BRWalletCreateTransactionAtFeePerKb(p.w, 3000000, p.dest, DEFAULT_FEE_PER_KB);
    int signedOther = other && BRWalletSignTransaction(p.w, other, 0, g_seed, sizeof(g_seed));
    int registered = signedOther && BRWalletRegisterTransaction(p.w, other);
    ck(registered, "a second send of the same coins is registered");
    if (!registered && other) BRTransactionFree(other);
    v = BRPreparedSendCheck(p.w, p.bytes, p.payScript, p.payScriptLen, p.payAmount, p.approved);
    ck(v == BRPreparedSendCoinUnavailable, "after signing: the signed transaction is refused, not handed back (%s)",
       verdictName(v));
    release(&p);
}

static void verdict_values_fixed(void)
{
    ck(BRPreparedSendOK == 0 && BRPreparedSendFeeUnknown == 1 && BRPreparedSendFeeChanged == 2 &&
       BRPreparedSendCoinUnavailable == 3 && BRPreparedSendNotApproved == 4,
       "OK 0, FEE_UNKNOWN 1, FEE_CHANGED 2, COIN_UNAVAILABLE 3, NOT_APPROVED 4");
}

#if PREPARED_SEND_DIFFERENTIAL
// ---- differential: BRPreparedSend.h against the force-included reference (prepared_send.h) ----
static uint64_t g_rng = 0x9e3779b97f4a7c15ULL;
static uint64_t rnd(void) { g_rng ^= g_rng << 13; g_rng ^= g_rng >> 7; g_rng ^= g_rng << 17; return g_rng; }
static uint64_t below(uint64_t n) { return n ? rnd() % n : 0; }

static size_t g_compared = 0, g_mismatched = 0, g_quotes = 0, g_seen[5];

static void compareQuote(BRWallet *w, const BRTransaction *tx, int nullOut, const char *what)
{
    uint64_t f1 = 0xA5A5, v1 = 0x5A5A, f2 = 0xA5A5, v2 = 0x5A5A;
    int r1, r2;
    if (nullOut && PREPARED_SEND_UNFIXED) return;   // both comparison arms write through the pointers
    r1 = BRPreparedSendQuote(w, tx, nullOut == 1 ? NULL : &f1, nullOut == 2 ? NULL : &v1);
    r2 = prepared_send_quote(w, tx, nullOut == 1 ? NULL : &f2, nullOut == 2 ? NULL : &v2);
    g_quotes++;
    if (r1 != r2 || f1 != f2 || v1 != v2) {
        g_mismatched++;
        printf("FAIL: quote differs (%s): core %d %llu %llu, reference %d %llu %llu\n", what, r1,
               (unsigned long long)f1, (unsigned long long)v1, r2, (unsigned long long)f2, (unsigned long long)v2);
    }
}

static void compareCheck(BRWallet *w, const BRTransaction *tx, const uint8_t *s, size_t sl, uint64_t amt,
                         uint64_t fee, const char *what)
{
    int a = (int)BRPreparedSendCheck(w, tx, s, sl, amt, fee);
    int b = (int)prepared_send_check(w, tx, s, sl, amt, fee);
    g_compared++;
    if (a >= 0 && a < 5) g_seen[a]++;
    if (a != b) {
        g_mismatched++;
        printf("FAIL: verdict differs (%s): core %d, reference %d\n", what, a, b);
    }
}

// One send, then every variant of its bytes and of its approval, then the wallet changing under it.
static void diffScenario(int self)
{
    uint64_t coins[6];
    size_t n = 1 + (size_t)below(6);
    BRAddress own;
    uint64_t total = 0;
    for (size_t i = 0; i < n; i++) { coins[i] = 100000 + below(4900000); total += coins[i]; }
    BRWallet *w = walletWithCoins(coins, n, &own);
    uint8_t fs[22];
    char dest[75];
    foreignScript(fs, (uint8_t)(0x40 + below(64)));
    if (self) strcpy(dest, own.s); else BRAddressFromScriptPubKey(dest, sizeof(dest), fs, sizeof(fs));
    uint8_t pay[64];
    size_t payLen = BRAddressScriptPubKey(pay, sizeof(pay), dest);
    uint64_t amount = 10000 + below(total > 200000 ? total - 200000 : 1);
    uint64_t rate = below(3) == 0 ? DEFAULT_FEE_PER_KB : 1 + below(300000);
    BRTransaction *built = BRWalletCreateTransactionAtFeePerKb(w, amount, dest, rate);
    if (!built) { BRWalletFree(w); return; }
    BRTransaction *bytes = roundTrip(built);
    uint64_t approved = paid(built);

    compareQuote(w, bytes, 0, "as built");
    compareQuote(w, bytes, 1, "no fee pointer");
    compareQuote(w, bytes, 2, "no size pointer");
    compareQuote(NULL, bytes, 0, "no wallet");
    compareQuote(w, NULL, 0, "no tx");
    BRTransaction *empty = BRTransactionNew();
    compareQuote(w, empty, 0, "no inputs, no outputs");
    compareCheck(w, empty, pay, payLen, amount, 0, "no inputs, no outputs");
    compareCheck(w, empty, pay, payLen, 0, 0, "no inputs, no outputs, nothing approved");
    BRTransactionFree(empty);
    compareCheck(w, bytes, pay, payLen, amount, approved, "as approved");
    compareCheck(NULL, bytes, pay, payLen, amount, approved, "no wallet");
    compareCheck(w, NULL, pay, payLen, amount, approved, "no tx");
    compareCheck(w, bytes, NULL, payLen, amount, approved, "no script");
    compareCheck(w, bytes, pay, 0, amount, approved, "empty script");
    compareCheck(w, bytes, pay, payLen - 1, amount, approved, "short script");
    compareCheck(w, bytes, pay, payLen, 0, approved, "zero amount");
    compareCheck(w, bytes, pay, payLen, amount + 1, approved, "other amount approved");
    compareCheck(w, bytes, pay, payLen, amount, approved + 1 + below(1000), "fee approved higher");
    compareCheck(w, bytes, pay, payLen, amount, approved - 1 - below(approved - 1), "fee approved lower");
    compareCheck(w, bytes, fs, sizeof(fs), amount, approved, "other recipient approved");

    // Output variants: every output drawn from {payment, change, own, foreign, zero, other amount}.
    uint8_t ownS[64], foreignS[22];
    size_t ownLen = BRAddressScriptPubKey(ownS, sizeof(ownS), own.s);
    foreignScript(foreignS, (uint8_t)(0x80 + below(64)));
    const BRTxOutput *change = NULL;
    for (size_t i = 0; i < built->outCount; i++) {
        if (built->outputs[i].scriptLen != payLen || memcmp(built->outputs[i].script, pay, payLen) != 0) change = &built->outputs[i];
    }
    for (int m = 0; m < 24; m++) {
        BRTxOutput outs[4];
        size_t k = 1 + (size_t)below(4);
        for (size_t i = 0; i < k; i++) {
            uint64_t a = 1 + below(amount);
            switch (below(7)) {
            case 0: case 1: outs[i] = (BRTxOutput){ "", amount, pay, payLen }; break;
            case 2: outs[i] = change ? (BRTxOutput){ "", change->amount, change->script, change->scriptLen }
                                     : (BRTxOutput){ "", a, ownS, ownLen }; break;
            case 3: outs[i] = (BRTxOutput){ "", a, ownS, ownLen }; break;
            case 4: outs[i] = (BRTxOutput){ "", a, foreignS, sizeof(foreignS) }; break;
            case 5: outs[i] = below(2) ? (BRTxOutput){ "", 0, ownS, ownLen } : (BRTxOutput){ "", 0, pay, payLen }; break;
            default: outs[i] = (BRTxOutput){ "", a, pay, payLen }; break;
            }
        }
        BRTransaction *t = withOutputs(built, outs, k);
        if (!t) continue;
        uint64_t fee = BRWalletFeeForTx(w, t);
        compareQuote(w, t, 0, "variant");
        compareCheck(w, t, pay, payLen, amount, fee, "variant at its own fee");
        compareCheck(w, t, pay, payLen, amount, approved, "variant at the approved fee");
        BRTransactionFree(t);
    }

    // The wallet changes: sign, then (one of) hold a coin, drop a parent, or spend the coins elsewhere.
    BRTransaction *signedTx = roundTrip(built);
    if (signedTx && BRWalletSignTransaction(w, signedTx, 0, g_seed, sizeof(g_seed))) {
        compareQuote(w, signedTx, 0, "signed");
        compareCheck(w, signedTx, pay, payLen, amount, approved, "signed");
    }
    const BRTxInput *in = &bytes->inputs[below(bytes->inCount)];
    switch (below(3)) {
    case 0: BRWalletRegisterAssetOutpoint(w, in->txHash, in->index); break;
    case 1: BRWalletRemoveTransaction(w, in->txHash); break;
    default: {
        BRTransaction *other = BRWalletCreateTransactionAtFeePerKb(w, amount, dest, DEFAULT_FEE_PER_KB);
        if (other && BRWalletSignTransaction(w, other, 0, g_seed, sizeof(g_seed)) && BRWalletRegisterTransaction(w, other)) other = NULL;
        if (other) BRTransactionFree(other);
    } }
    compareQuote(w, bytes, 0, "after the wallet changed");
    compareCheck(w, bytes, pay, payLen, amount, approved, "after the wallet changed");
    if (signedTx) {
        compareCheck(w, signedTx, pay, payLen, amount, approved, "signed, after the wallet changed");
        BRTransactionFree(signedTx);
    }
    BRTransactionFree(bytes);
    BRTransactionFree(built);
    BRWalletFree(w);
}

static void differential(void)
{
    for (int s = 0; s < 120; s++) diffScenario(s % 5 == 4);
    // The DigiDollar coin, through both headers.
    UInt256 ddHash, dgbHash;
    BRWallet *w = ddWallet(50000000, &ddHash, &dgbHash);
    const BRTransaction *dgbTx = BRWalletTransactionForHash(w, dgbHash), *ddTx = BRWalletTransactionForHash(w, ddHash);
    if (dgbTx && ddTx) {
        uint8_t pay[22];
        size_t payLen = foreignScript(pay, 0x6e);
        BRTransaction *t = BRTransactionNew();
        BRTransactionAddInput(t, dgbHash, 0, 50000000, dgbTx->outputs[0].script, dgbTx->outputs[0].scriptLen, NULL, 0, NULL, 0, 0xffffffff);
        BRTransactionAddInput(t, ddHash, 0, 0, ddTx->outputs[0].script, ddTx->outputs[0].scriptLen, NULL, 0, NULL, 0, 0xffffffff);
        BRTransactionAddOutput(t, 10000000, pay, payLen);
        compareQuote(w, t, 0, "DigiDollar coin");
        compareCheck(w, t, pay, payLen, 10000000, BRWalletFeeForTx(w, t), "DigiDollar coin");
        BRTransactionFree(t);
    }
    BRWalletFree(w);
    printf("NOTE: %zu verdicts and %zu quotes compared; core verdicts OK %zu, FEE_UNKNOWN %zu, FEE_CHANGED %zu, "
           "COIN_UNAVAILABLE %zu, NOT_APPROVED %zu\n", g_compared, g_quotes, g_seen[0], g_seen[1], g_seen[2],
           g_seen[3], g_seen[4]);
    ck(g_mismatched == 0, "BRPreparedSend.h and the reference answered every input alike (%zu differed)", g_mismatched);
    if (!PREPARED_SEND_UNFIXED) {
        ck(g_seen[0] && g_seen[1] && g_seen[2] && g_seen[3] && g_seen[4], "every verdict was reached (not blind)");
    }
}
#endif

typedef struct { const char *name; void (*run)(void); } Case;
static const Case kCases[] = {
    { "quote_never_invents", quote_never_invents },
    { "check_needs_wallet_and_tx", check_needs_wallet_and_tx },
    { "check_needs_an_approved_payment", check_needs_an_approved_payment },
    { "payment_only_passes", payment_only_passes },
    { "change_before_payment_passes", change_before_payment_passes },
    { "payment_twice_to_self_passes", payment_twice_to_self_passes },
    { "two_change_outputs_refused", two_change_outputs_refused },
    { "payment_twice_refused", payment_twice_refused },
    { "zero_value_change_refused", zero_value_change_refused },
    { "change_without_payment_refused", change_without_payment_refused },
    { "payment_raised_change_lowered_refused", payment_raised_change_lowered_refused },
    { "sibling_of_unspent_coin_refused", sibling_of_unspent_coin_refused },
    { "digidollar_coin_refused", digidollar_coin_refused },
    { "refused_after_signing", refused_after_signing },
    { "verdict_values_fixed", verdict_values_fixed },
#if PREPARED_SEND_DIFFERENTIAL
    { "differential", differential },
#endif
};

int main(int argc, char **argv)
{
    size_t n = sizeof(kCases) / sizeof(kCases[0]);
    const char *only = argc > 1 ? argv[1] : NULL;
    int ran = 0;

    if (only && strcmp(only, "list") == 0) {
        for (size_t i = 0; i < n; i++) printf("%s\n", kCases[i].name);
        return 0;
    }
    BRBIP39DeriveKey(g_seed, kMnemonic, NULL);
    g_mpk84 = BRBIP32MasterPubKeyBIP84(g_seed, sizeof(g_seed));
    g_mpk86 = BRBIP32MasterPubKeyBIP86(g_seed, sizeof(g_seed));
    printf("ARM: %s\n", PREPARED_SEND_UNFIXED ? "comparison (PREPARED_SEND_UNFIXED=1)" : "shipped");
    for (size_t i = 0; i < n; i++) {
        if (only && strcmp(only, kCases[i].name) != 0) continue;
        g_caseFailed = 0;
        printf("--- %s\n", kCases[i].name);
        kCases[i].run();
        printf("RESULT %s %s\n", kCases[i].name, g_caseFailed ? "fail" : "pass");
        if (g_caseFailed) g_anyFailed = 1;
        ran++;
    }
    if (!ran) { fprintf(stderr, "unknown case %s\n", only); return 2; }
    if (!g_anyFailed) printf("ALL PASS\n");
    return g_anyFailed;
}
