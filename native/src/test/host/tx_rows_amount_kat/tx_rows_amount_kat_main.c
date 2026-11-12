// Host KAT: the amount and fee a transaction-list row shows (BRTxRows.h, the AMOUNT and FEE rules).
//
// THE RULE (owner decisions D1 option A and D2, 2026-11-08):
//   - a DigiDollar transaction: received - sent;
//   - a send (some input is the wallet's, and it pays others something): -toOthers, the payee amount
//     only -- the fee is the row's own field. A DigiAsset transfer reads as minus its marker output;
//   - anything else: received - sent;
//   - the fee is the wallet's (inputs minus outputs, every parent held), or BR_TX_ROW_FEE_UNKNOWN
//     (UINT64_MAX). Unknown is never a figure: not 0, not a partial sum.
// An input is the wallet's when the wallet holds its parent coin, or -- parent missing -- when its
// address or the P2WPKH address of its witness key is a wallet address. A restored wallet's send whose
// parents were never downloaded must still read as the payment, not as a receive of its change; that is
// what Android corrected in Kotlin (OutgoingTxStore's override) and iOS never did.
//
// Every expectation is fixed here, from the transaction the case builds (what it pays whom, what it
// pays in fee), never from the header's own arithmetic. The transactions are real: built and signed by
// the core's wallet for the abandon...about seed, registered in real wallets, and -- where a case is
// about a wallet that never saw a parent -- serialized and parsed again, as storage and peers deliver
// them (a parsed native-segwit input carries no address; the core does not derive one from a witness).
//
// The key test is applied only to confirmed transactions: an unconfirmed transaction may name the
// wallet's public key over a coin nobody can check (unconfirmed_witness_key_not_owned,
// unconfirmed_scriptsig_key_not_owned); both must read as the receive they are.
//
// KNOWN GAP, pinned (taproot_keypath_missing_parent): a Taproot key-path input's witness is a
// signature alone. With its parent missing, nothing says the coin was the wallet's, so the send reads
// as received - sent: its change, positive. The case asserts exactly that, so closing the gap fails it
// and it must be rewritten with the fix.
//
// MACRO CONVENTION: BRTxRows.h tests the VALUE of TX_ROWS_UNFIXED (#if). run.sh builds the comparison
// arm with -DTX_ROWS_UNFIXED=1 (every amount received - sent, an unknown fee read as 0) and the shipped
// arm with =0.
//
// One case per process (argv[1]); "list" names them. Each case prints RESULT <case> pass|fail.
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>
#include <stdarg.h>
#include <inttypes.h>

#include "BRTxRows.h"
#include "BRWallet.h"
#include "BRTransaction.h"
#include "BRAddress.h"
#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"
#include "BRDigiDollar.h"

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

#define NOW 1762560000u          // the caller's clock (2025-11-08); only pending rows read it
#define HEIGHT 23700000u
#define TS 1700000000u
#define DA_MARKER_SATS 6000ULL   // Android core/asset/send/AssetCoinSelector.kt: one marker per recipient
#define UNKNOWN UINT64_MAX       // written as the wallet writes it, not as the header's macro

static void finalizeTxHash(BRTransaction *tx)
{
    uint8_t data[BRTransactionSerialize(tx, NULL, 0)];
    size_t len = BRTransactionSerialize(tx, data, sizeof(data));
    BRTransaction *t = BRTransactionParse(data, len);
    if (t) { tx->txHash = t->txHash; tx->wtxHash = t->wtxHash; BRTransactionFree(t); }
}

// A confirmed transaction paying `sats` to `addr`, spending a coin that is not the wallet's (its input
// names a foreign native-segwit script, so the input's address is not the wallet's).
static BRTransaction *coinTx(const char *addr, uint64_t sats, uint8_t tag)
{
    uint8_t spk[64], foreign[22] = { 0x00, 0x14 };
    size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), addr);
    BRTransaction *c = BRTransactionNew();
    UInt256 h;
    memset(h.u8, tag, sizeof(h.u8));
    memset(foreign + 2, 0x33, 20);
    c->version = 1;
    BRTransactionAddInput(c, h, 0, 0, foreign, sizeof(foreign), kPlaceholder, 0, kPlaceholder, 0, 0xffffffff);
    BRTransactionAddOutput(c, sats, spk, spkLen);
    c->blockHeight = HEIGHT;
    c->timestamp = TS;
    finalizeTxHash(c);
    return c;
}

// A native-segwit address no wallet here owns.
static void externalAddress(char *out, size_t cap, uint8_t fill)
{
    uint8_t spk[22] = { 0x00, 0x14 };
    memset(spk + 2, fill, 20);
    BRAddressFromScriptPubKey(out, cap, spk, sizeof(spk));
}

static uint64_t paid(const BRTransaction *tx)
{
    uint64_t in = 0, out = 0;
    for (size_t i = 0; i < tx->inCount; i++) in += tx->inputs[i].amount;
    for (size_t i = 0; i < tx->outCount; i++) out += tx->outputs[i].amount;
    return in - out;
}

// What tx pays every address except `dest` (its change, when dest is the payee).
static uint64_t paidExcept(const BRTransaction *tx, const char *dest)
{
    uint64_t s = 0;
    for (size_t i = 0; i < tx->outCount; i++) if (strcmp(tx->outputs[i].address, dest) != 0) s += tx->outputs[i].amount;
    return s;
}

// The bytes as storage and peers deliver them: serialized and parsed again, unconfirmed.
static BRTransaction *parsed(const BRTransaction *tx)
{
    size_t len = BRTransactionSerialize(tx, NULL, 0);
    uint8_t *buf = malloc(len ? len : 1);
    if (!buf) return NULL;
    len = BRTransactionSerialize(tx, buf, len);
    BRTransaction *t = BRTransactionParse(buf, len);
    free(buf);
    return t;
}

// A signed send of `amount` to `dest` from w, at the default rate. Not registered.
static BRTransaction *signedSend(BRWallet *w, uint64_t amount, const char *dest)
{
    BRTransaction *tx = BRWalletCreateTransactionAtFeePerKb(w, amount, dest, DEFAULT_FEE_PER_KB);
    if (!tx) return NULL;
    if (!BRWalletSignTransaction(w, tx, 0, g_seed, sizeof(g_seed))) { BRTransactionFree(tx); return NULL; }
    return tx;
}

// The row BRWalletTxRows gives for hash h, through the full reader. 0 if absent.
static int rowFor(BRWallet *w, UInt256 h, BRTxRow *out)
{
    size_t n = BRWalletTxRows(w, NOW, NULL, 0), i, got;
    BRTxRow *rows = calloc(n ? n : 1, sizeof(*rows));
    int found = 0;
    if (!rows) return 0;
    got = BRWalletTxRows(w, NOW, rows, n);
    for (i = 0; i < got; i++) {
        if (UInt256Eq(rows[i].txHash, h)) { *out = rows[i]; found = 1; break; }
    }
    free(rows);
    return found;
}

static void showRow(const char *what, const BRTxRow *r)
{
    printf("NOTE: %s: amount %" PRId64 ", fee %s%" PRIu64 ", sent %" PRIu64 ", received %" PRIu64
           ", toOthers %" PRIu64 ", ownsInput %u, rule %u\n", what, r->amount,
           r->fee == UNKNOWN ? "UNKNOWN/" : "", r->fee, r->sent, r->received, r->toOthers, r->ownsInput, r->rule);
}

// ---------------------------------------------------------------- pure cases ----

static void rule_table(void)
{
    // { isDigiDollar, ownsInput, toOthers, expected rule }
    const struct { int dd, owns; uint64_t to; BRTxRowAmountRule want; } t[] = {
        { 0, 0, 0, BRTxRowAmountNet },           // a receive
        { 0, 0, 5, BRTxRowAmountNet },           // pays others, but no input is the wallet's: not a send
        { 0, 1, 0, BRTxRowAmountNet },           // a send to the wallet itself
        { 0, 1, 1, BRTxRowAmountPayee },         // a send
        { 0, 1, UINT64_MAX, BRTxRowAmountPayee },
        { 1, 0, 0, BRTxRowAmountDigiDollar },
        { 1, 1, 0, BRTxRowAmountDigiDollar },
        { 1, 1, 7, BRTxRowAmountDigiDollar },    // a DigiDollar mint's collateral is not a payee amount
        { 2, 1, 7, BRTxRowAmountDigiDollar },    // any non-zero isDigiDollar
    };
    for (size_t i = 0; i < sizeof(t) / sizeof(t[0]); i++) {
        BRTxRowAmountRule r = BRTxRowAmountRuleFor(t[i].dd, t[i].owns, t[i].to);
        ck(r == t[i].want, "isDigiDollar %d, ownsInput %d, toOthers %" PRIu64 ": rule %d, want %d",
           t[i].dd, t[i].owns, t[i].to, r, t[i].want);
    }
}

static void amount_arithmetic(void)
{
    ck(BRTxRowAmountFor(BRTxRowAmountNet, 100000000, 0, 0) == 100000000, "net: a receive of 1 DGB is +100000000");
    ck(BRTxRowAmountFor(BRTxRowAmountNet, 389990000, 500000000, 110000000) == -110010000, "net: received - sent");
    ck(BRTxRowAmountFor(BRTxRowAmountPayee, 389990000, 500000000, 110000000) == -110000000, "payee: -toOthers");
    ck(BRTxRowAmountFor(BRTxRowAmountDigiDollar, 10000000, 60000000, 49990000) == -50000000, "DigiDollar: received - sent");
    ck(BRTxRowAmountFor(BRTxRowAmountPayee, 0, 0, (uint64_t)INT64_MAX) == -INT64_MAX, "payee: -INT64_MAX exactly");
    ck(BRTxRowAmountFor(BRTxRowAmountPayee, 0, 0, (uint64_t)INT64_MAX + 1) == INT64_MIN, "payee: saturates at INT64_MIN");
    ck(BRTxRowAmountFor(BRTxRowAmountNet, UINT64_MAX, 0, 0) == INT64_MAX, "net: saturates at INT64_MAX");
    ck(BRTxRowAmountFor(BRTxRowAmountNet, 0, UINT64_MAX, 0) == INT64_MIN, "net: saturates at INT64_MIN");
    ck(BRTxRowAmountFor((BRTxRowAmountRule)99, 5, 2, 9) == 3, "an unknown rule value is net, never payee");
}

static void fee_unknown_pure(void)
{
    ck(BRTxRowFeeFor(UINT64_MAX) == UNKNOWN, "an unknown fee stays unknown (got %" PRIu64 ")", BRTxRowFeeFor(UINT64_MAX));
    ck(BR_TX_ROW_FEE_UNKNOWN == UINT64_MAX, "BR_TX_ROW_FEE_UNKNOWN is UINT64_MAX");
    ck(BRTxRowFeeFor(14100) == 14100 && BRTxRowFeeFor(0) == 0, "a known fee is its figure");
}

// ---------------------------------------------------------------- wallet cases ----

static void receive_is_net_fee_unknown(void)
{
    BRWallet *w = BRWalletNew(NULL, 0, g_mpk84);
    BRAddress a = BRWalletReceiveAddress(w, 1);
    BRTransaction *c = coinTx(a.s, 100000000, 0xA1);
    UInt256 h = c->txHash;
    BRTxRow r;
    ck(BRWalletRegisterTransaction(w, c), "the receive is registered");
    ck(rowFor(w, h, &r), "it has a row");
    showRow("receive", &r);
    ck(r.amount == 100000000, "a receive of 1 DGB reads +100000000 (got %" PRId64 ")", r.amount);
    ck(r.rule == BRTxRowAmountNet && !r.ownsInput, "a receive: rule net, no input of the wallet's");
    ck(r.fee == UNKNOWN, "its fee (the sender's; the wallet holds no parent) is unknown, not a figure (got %" PRIu64 ")", r.fee);
    BRWalletFree(w);
}

static void send_payee_only(void)
{
    BRWallet *w = BRWalletNew(NULL, 0, g_mpk84);
    BRAddress a = BRWalletReceiveAddress(w, 1);
    char dest[75]; externalAddress(dest, sizeof(dest), 0x5a);
    BRWalletRegisterTransaction(w, coinTx(a.s, 500000000, 0xA2));
    BRTransaction *tx = signedSend(w, 110000000, dest);
    ck(tx != NULL, "a signed send of 1.1 DGB builds");
    if (!tx) { BRWalletFree(w); return; }
    uint64_t fee = paid(tx);
    UInt256 h = tx->txHash;
    ck(BRWalletRegisterTransaction(w, tx), "it is registered");
    BRTxRow r;
    ck(rowFor(w, h, &r), "it has a row");
    showRow("send", &r);
    ck(r.amount == -110000000, "the row is the payee amount, -110000000, not -(payee + fee) = -%" PRIu64 " (got %" PRId64 ")",
       110000000 + fee, r.amount);
    ck(r.fee == fee, "the fee is the transaction's own: %" PRIu64 " (got %" PRIu64 ")", fee, r.fee);
    ck(r.rule == BRTxRowAmountPayee && r.ownsInput, "rule payee, an input of the wallet's");
    BRWalletFree(w);
}

static void send_everything(void)
{
    BRWallet *w = BRWalletNew(NULL, 0, g_mpk84);
    BRAddress a = BRWalletReceiveAddress(w, 1);
    char dest[75]; externalAddress(dest, sizeof(dest), 0x5b);
    BRWalletRegisterTransaction(w, coinTx(a.s, 500000000, 0xA3));
    uint64_t max = BRWalletMaxOutputAmount(w);
    BRTransaction *tx = signedSend(w, max, dest);
    ck(tx != NULL, "a send of the whole balance (%" PRIu64 ") builds", max);
    if (!tx) { BRWalletFree(w); return; }
    uint64_t fee = paid(tx), change = paidExcept(tx, dest);
    UInt256 h = tx->txHash;
    BRWalletRegisterTransaction(w, tx);
    BRTxRow r;
    ck(rowFor(w, h, &r), "it has a row");
    showRow("send everything", &r);
    ck(change == 0, "no change output");
    ck(r.amount == -(int64_t)max, "the row is what the payee got, -%" PRIu64 ", not the balance -500000000 (got %" PRId64 ")", max, r.amount);
    ck(r.fee == fee && max + fee == 500000000, "the fee is the rest: %" PRIu64, fee);
    BRWalletFree(w);
}

static void self_send_is_fee(void)
{
    BRWallet *w = BRWalletNew(NULL, 0, g_mpk84);
    BRAddress a = BRWalletReceiveAddress(w, 1);
    BRWalletRegisterTransaction(w, coinTx(a.s, 500000000, 0xA4));
    BRAddress own = BRWalletReceiveAddress(w, 1);
    BRTransaction *tx = signedSend(w, 100000000, own.s);
    ck(tx != NULL, "a send to the wallet's own address builds");
    if (!tx) { BRWalletFree(w); return; }
    uint64_t fee = paid(tx);
    UInt256 h = tx->txHash;
    BRWalletRegisterTransaction(w, tx);
    BRTxRow r;
    ck(rowFor(w, h, &r), "it has a row");
    showRow("self-send", &r);
    ck(r.toOthers == 0 && r.rule == BRTxRowAmountNet, "it pays no one else: rule net");
    ck(r.amount == -(int64_t)fee, "the row is minus the fee, -%" PRIu64 " (got %" PRId64 ")", fee, r.amount);
    ck(r.fee == fee, "and the fee is its own");
    BRWalletFree(w);
}

// A foreign key swept into the wallet (a paper wallet, a WIF key, a legacy key the wallet does not
// derive): the input's witness carries a key that is not the wallet's, every output is the wallet's.
static void self_sweep_foreign_key(void)
{
    static const uint8_t G[33] = { 0x02, 0x79,0xBE,0x66,0x7E,0xF9,0xDC,0xBB,0xAC,0x55,0xA0,0x62,0x95,0xCE,0x87,0x0B,
                                   0x07,0x02,0x9B,0xFC,0xDB,0x2D,0xCE,0x28,0xD9,0x59,0xF2,0x81,0x5B,0x16,0xF8,0x17,0x98 };
    uint8_t wit[1 + 71 + 1 + 33];
    BRWallet *w = BRWalletNew(NULL, 0, g_mpk84);
    BRAddress a = BRWalletReceiveAddress(w, 1);
    uint8_t spk[64];
    size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), a.s);
    UInt256 ph;
    memset(ph.u8, 0x77, sizeof(ph.u8));
    wit[0] = 71; memset(&wit[1], 0x30, 71); wit[72] = 33; memcpy(&wit[73], G, 33);
    BRTransaction *t = BRTransactionNew();
    t->version = 2;
    // kPlaceholder, 0 rather than NULL, 0: _BRTxInputData memcpy's the scriptSig even when it is empty,
    // and memcpy from NULL is undefined (UBSan stops on it under glibc)
    BRTransactionAddInput(t, ph, 0, 0, kPlaceholder, 0, kPlaceholder, 0, wit, sizeof(wit), 0xffffffff);
    BRTransactionAddOutput(t, 250000000, spk, spkLen);
    t->blockHeight = HEIGHT; t->timestamp = TS;
    finalizeTxHash(t);
    BRTransaction *p = parsed(t);
    BRTransactionFree(t);
    ck(p != NULL, "the sweep parses");
    if (!p) { BRWalletFree(w); return; }
    p->blockHeight = HEIGHT; p->timestamp = TS;
    UInt256 h = p->txHash;
    ck(BRWalletRegisterTransaction(w, p), "the sweep is registered");
    BRTxRow r;
    ck(rowFor(w, h, &r), "it has a row");
    showRow("sweep of a foreign key", &r);
    ck(!r.ownsInput, "a witness key that is not the wallet's is not the wallet's input");
    ck(r.amount == 250000000 && r.rule == BRTxRowAmountNet, "it reads as the +250000000 it brought in (got %" PRId64 ")", r.amount);
    BRWalletFree(w);
}

// Wallet A builds and signs the send; wallet B (same seed) holds only the send, parsed back from bytes,
// and the parents in keep[] (indexes into A's coins).
static BRWallet *walletWithout(const BRTransaction *send, BRTransaction *const *coins, const int *keep, size_t nKeep,
                               int taproot)
{
    BRTransaction *txs[4];
    size_t n = 0;
    for (size_t i = 0; i < nKeep; i++) txs[n++] = parsed(coins[keep[i]]);
    txs[n] = parsed(send);
    if (!txs[n]) return NULL;
    txs[n]->blockHeight = HEIGHT + 10; txs[n]->timestamp = TS + 100;
    for (size_t i = 0; i < n; i++) { txs[i]->blockHeight = HEIGHT; txs[i]->timestamp = TS; }
    n++;
    for (size_t i = 0; i < txs[n - 1]->inCount; i++)
        printf("NOTE: wallet B's copy of the send: input %zu address \"%s\", %zu witness bytes\n", i,
               txs[n - 1]->inputs[i].address, txs[n - 1]->inputs[i].witLen);
    BRWallet *b = BRWalletNew(txs, n, g_mpk84);
    if (b && taproot) BRWalletSetTaprootKey(b, g_mpk86);
    return b;
}

static void missing_parent_p2wpkh_send(void)
{
    BRWallet *w = BRWalletNew(NULL, 0, g_mpk84);
    BRAddress a = BRWalletReceiveAddress(w, 1);
    char dest[75]; externalAddress(dest, sizeof(dest), 0x5c);
    BRTransaction *coin = coinTx(a.s, 500000000, 0xA5);
    BRTransaction *keepCopy = BRTransactionCopy(coin);
    BRWalletRegisterTransaction(w, coin);
    BRTransaction *tx = signedSend(w, 110000000, dest);
    ck(tx != NULL, "wallet A signs a send of 1.1 DGB");
    if (!tx) { BRTransactionFree(keepCopy); BRWalletFree(w); return; }
    uint64_t change = paidExcept(tx, dest);
    BRWallet *b = walletWithout(tx, &keepCopy, NULL, 0, 0);
    ck(b != NULL, "wallet B (same seed) opens holding the send and not its parent");
    if (b) {
        BRTxRow r;
        BRTransaction *bt = parsed(tx);
        ck(bt && bt->inputs[0].address[0] == '\0', "the parsed P2WPKH input carries no address: only its witness says whose it is");
        ck(rowFor(b, tx->txHash, &r), "it has a row");
        showRow("send, parent missing", &r);
        ck(r.sent == 0 && r.received == change, "the wallet sees sent 0, received %" PRIu64 " (its change)", change);
        ck(r.ownsInput, "the witness key's P2WPKH address is the wallet's: the input is the wallet's");
        ck(r.amount == -110000000, "the row is the payment, -110000000, not a receive of the change +%" PRIu64 " (got %" PRId64 ")",
           change, r.amount);
        ck(r.fee == UNKNOWN, "the fee is unknown (the parent is missing), not a figure (got %" PRIu64 ")", r.fee);
        if (bt) BRTransactionFree(bt);
        BRWalletFree(b);
    }
    BRTransactionFree(tx);
    BRTransactionFree(keepCopy);
    BRWalletFree(w);
}

static void partial_parent_send(void)
{
    BRWallet *w = BRWalletNew(NULL, 0, g_mpk84);
    BRAddress a = BRWalletReceiveAddress(w, 1);
    char dest[75]; externalAddress(dest, sizeof(dest), 0x5d);
    BRTransaction *coins[2] = { coinTx(a.s, 300000000, 0xA6), coinTx(a.s, 300000000, 0xA7) };
    BRTransaction *copies[2] = { BRTransactionCopy(coins[0]), BRTransactionCopy(coins[1]) };
    BRWalletRegisterTransaction(w, coins[0]);
    BRWalletRegisterTransaction(w, coins[1]);
    BRTransaction *tx = signedSend(w, 521210000, dest);
    ck(tx != NULL && tx->inCount == 2, "wallet A signs a send of 5.2121 DGB spending both 3 DGB coins");
    if (!tx || tx->inCount != 2) { if (tx) BRTransactionFree(tx); BRTransactionFree(copies[0]); BRTransactionFree(copies[1]); BRWalletFree(w); return; }
    uint64_t change = paidExcept(tx, dest);
    const int keep[1] = { 0 };
    BRWallet *b = walletWithout(tx, copies, keep, 1, 0);
    ck(b != NULL, "wallet B holds the send and ONE of its two parents");
    if (b) {
        BRTxRow r;
        ck(rowFor(b, tx->txHash, &r), "it has a row");
        showRow("send, one parent of two", &r);
        ck(r.sent == 300000000, "the wallet sees only the parent it holds: sent 300000000 (got %" PRIu64 ")", r.sent);
        ck(r.amount == -521210000, "the row is the payment, -521210000, not change - partial inputs = %" PRId64 " (got %" PRId64 ")",
           (int64_t)change - 300000000, r.amount);
        ck(r.fee == UNKNOWN, "the fee is unknown, never a partial sum (got %" PRIu64 ")", r.fee);
        BRWalletFree(b);
    }
    BRTransactionFree(tx);
    BRTransactionFree(copies[0]); BRTransactionFree(copies[1]);
    BRWalletFree(w);
}

// A DigiAsset transfer: the asset rides a marker output of DA_MARKER_SATS to the recipient, the DA
// OP_RETURN carries the transfer instruction, the rest of the DGB comes back as change.
static void digiasset_send_marker(void)
{
    BRWallet *w = BRWalletNew(NULL, 0, g_mpk84);
    BRAddress a = BRWalletReceiveAddress(w, 1);
    char dest[75]; externalAddress(dest, sizeof(dest), 0x5e);
    BRTransaction *coin = coinTx(a.s, 50000000, 0xA8);
    UInt256 ch = coin->txHash;
    uint8_t coinSpk[64]; size_t coinSpkLen = BRAddressScriptPubKey(coinSpk, sizeof(coinSpk), a.s);
    BRWalletRegisterTransaction(w, coin);
    BRAddress chg = BRWalletInternalChangeAddress(w);
    uint8_t destSpk[64], chgSpk[64];
    size_t destLen = BRAddressScriptPubKey(destSpk, sizeof(destSpk), dest);
    size_t chgLen = BRAddressScriptPubKey(chgSpk, sizeof(chgSpk), chg.s);
    const uint8_t daRet[9] = { 0x6a, 0x07, 'D', 'A', 0x03, 0x15, 0x00, 0x00, 0x01 };   // DA v3 transfer: input 0 -> output 0, 1 unit
    const uint64_t fee = 22000;
    BRTransaction *tx = BRTransactionNew();
    tx->version = 2;
    BRTransactionAddInput(tx, ch, 0, 50000000, coinSpk, coinSpkLen, NULL, 0, NULL, 0, 0xffffffff);
    BRTransactionAddOutput(tx, DA_MARKER_SATS, destSpk, destLen);
    BRTransactionAddOutput(tx, 0, daRet, sizeof(daRet));
    BRTransactionAddOutput(tx, 50000000 - DA_MARKER_SATS - fee, chgSpk, chgLen);
    int s = BRWalletSignTransaction(w, tx, 0, g_seed, sizeof(g_seed));
    ck(s, "the DigiAsset transfer is signed");
    UInt256 h = tx->txHash;
    ck(s && BRWalletRegisterTransaction(w, tx), "and registered");
    if (!s) BRTransactionFree(tx);
    BRTxRow r;
    ck(rowFor(w, h, &r), "it has a row");
    showRow("DigiAsset transfer", &r);
    ck(r.amount == -(int64_t)DA_MARKER_SATS, "the row is minus the marker, -%llu, not -(marker + fee) = -%llu (got %" PRId64 ")",
       (unsigned long long)DA_MARKER_SATS, (unsigned long long)(DA_MARKER_SATS + fee), r.amount);
    ck(r.fee == fee, "the fee is the transaction's own, %" PRIu64 " (got %" PRIu64 ")", fee, r.fee);
    BRWalletFree(w);
}

// A DigiDollar mint shape: DGB leaves as collateral to a script outside the wallet, a DigiDollar marker
// version, the DD OP_RETURN. Its row is the DGB the balance moved by, collateral and fee together.
static void digidollar_keeps_net(void)
{
    BRWallet *w = BRWalletNew(NULL, 0, g_mpk84);
    BRWalletSetTaprootKey(w, g_mpk86);
    BRAddress a = BRWalletReceiveAddress(w, 1);
    char vault[75]; externalAddress(vault, sizeof(vault), 0x5f);
    BRTransaction *coin = coinTx(a.s, 60000000, 0xA9);
    UInt256 ch = coin->txHash;
    uint8_t coinSpk[64]; size_t coinSpkLen = BRAddressScriptPubKey(coinSpk, sizeof(coinSpk), a.s);
    BRWalletRegisterTransaction(w, coin);
    BRAddress ta = BRWalletReceiveAddress(w, 2);
    BRAddress chg = BRWalletInternalChangeAddress(w);
    uint8_t vSpk[64], tSpk[64], cSpk[64];
    size_t vLen = BRAddressScriptPubKey(vSpk, sizeof(vSpk), vault);
    size_t tLen = BRAddressScriptPubKey(tSpk, sizeof(tSpk), ta.s);
    size_t cLen = BRAddressScriptPubKey(cSpk, sizeof(cSpk), chg.s);
    const uint8_t ddRet[8] = { 0x6a, 0x02, 'D', 'D', 0x01, 0x01, 0x02, 0x10 };   // "DD", type 1 (mint)
    const uint64_t collateral = 49990000, fee = 10000;
    BRTransaction *tx = BRTransactionNew();
    tx->version = 0x01000770;   // DigiDollar marker, type 1: mint
    BRTransactionAddInput(tx, ch, 0, 60000000, coinSpk, coinSpkLen, NULL, 0, NULL, 0, 0xffffffff);
    BRTransactionAddOutput(tx, collateral, vSpk, vLen);
    BRTransactionAddOutput(tx, 0, tSpk, tLen);
    BRTransactionAddOutput(tx, 0, ddRet, sizeof(ddRet));
    BRTransactionAddOutput(tx, 60000000 - collateral - fee, cSpk, cLen);
    int s = BRWalletSignTransaction(w, tx, 0, g_seed, sizeof(g_seed));
    ck(s, "the DigiDollar-marked transaction is signed");
    ck(BRDigiDollarTxType(tx) == DD_TYPE_MINT, "the core classifies it as a DigiDollar mint");
    UInt256 h = tx->txHash;
    ck(s && BRWalletRegisterTransaction(w, tx), "and registered");
    if (!s) BRTransactionFree(tx);
    BRTxRow r;
    ck(rowFor(w, h, &r), "it has a row");
    showRow("DigiDollar mint", &r);
    ck(r.toOthers == collateral && r.ownsInput, "it pays the collateral outside the wallet from the wallet's coin");
    ck(r.rule == BRTxRowAmountDigiDollar && r.ddType == DD_TYPE_MINT, "rule DigiDollar (got rule %u, ddType %u)", r.rule, r.ddType);
    ck(r.amount == -(int64_t)(collateral + fee), "the row is received - sent, -%" PRIu64 ", not the payee rule's -%" PRIu64 " (got %" PRId64 ")",
       collateral + fee, collateral, r.amount);
    BRWalletFree(w);
}

// A Taproot (BIP86) coin spent by key path. Returns the signed send, the coin's copy in *coinOut.
static BRTransaction *taprootSend(BRWallet **wOut, BRTransaction **coinOut, const char *dest, uint64_t *changeOut)
{
    BRWallet *w = BRWalletNew(NULL, 0, g_mpk84);
    BRWalletSetTaprootKey(w, g_mpk86);
    BRAddress ta = BRWalletReceiveAddress(w, 2);
    BRTransaction *coin = coinTx(ta.s, 500000000, 0xAA);
    *coinOut = BRTransactionCopy(coin);
    BRWalletRegisterTransaction(w, coin);
    BRTransaction *tx = signedSend(w, 110000000, dest);
    *wOut = w;
    if (tx) {
        *changeOut = paidExcept(tx, dest);
        printf("NOTE: taproot coin %s; send input 0 witness %zu bytes\n", ta.s, tx->inputs[0].witLen);
    }
    return tx;
}

static void taproot_send_payee(void)
{
    BRWallet *w = NULL; BRTransaction *coin = NULL; uint64_t change = 0;
    char dest[75]; externalAddress(dest, sizeof(dest), 0x60);
    BRTransaction *tx = taprootSend(&w, &coin, dest, &change);
    ck(tx != NULL, "a Taproot key-path send builds and signs");
    if (tx) {
        UInt256 h = tx->txHash;
        BRWalletRegisterTransaction(w, tx);
        BRTxRow r;
        ck(rowFor(w, h, &r), "it has a row");
        showRow("taproot send, parent held", &r);
        ck(r.amount == -110000000 && r.ownsInput, "with its parent held, the row is the payment, -110000000 (got %" PRId64 ")", r.amount);
    }
    if (coin) BRTransactionFree(coin);
    BRWalletFree(w);
}

static void taproot_keypath_missing_parent(void)
{
    BRWallet *w = NULL; BRTransaction *coin = NULL; uint64_t change = 0;
    char dest[75]; externalAddress(dest, sizeof(dest), 0x61);
    BRTransaction *tx = taprootSend(&w, &coin, dest, &change);
    ck(tx != NULL, "a Taproot key-path send builds and signs");
    if (tx) {
        BRWallet *b = walletWithout(tx, &coin, NULL, 0, 1);
        ck(b != NULL, "wallet B holds the send and not its Taproot parent");
        if (b) {
            BRTxRow r;
            ck(rowFor(b, tx->txHash, &r), "it has a row");
            showRow("taproot send, parent missing (KNOWN GAP)", &r);
            ck(tx->inputs[0].witLen > 0 && tx->inputs[0].witLen <= 1 + 1 + 65,
               "the key-path witness is one signature and nothing else (%zu bytes): no key to recognise", tx->inputs[0].witLen);
            ck(!r.ownsInput, "KNOWN GAP: nothing in the transaction says the input was the wallet's");
            ck(r.amount == (int64_t)change, "KNOWN GAP: the send reads as a receive of its change, +%" PRIu64 " (got %" PRId64 ")",
               change, r.amount);
            BRWalletFree(b);
        }
        BRTransactionFree(tx);
    }
    if (coin) BRTransactionFree(coin);
    BRWalletFree(w);
}

// A transaction relayed unconfirmed that spends a made-up coin, names the wallet's own (public) key over
// it -- in a P2WPKH witness, or in a P2PKH scriptSig -- with a signature nothing can check, pays the
// wallet 1000 sat and 10,000,000 DGB elsewhere. The wallet registers it (it pays the wallet). Its row
// is a receive of 1000: an unconfirmed input whose coin the wallet cannot see is not the wallet's.
static void unverifiable_key_unconfirmed(int scriptSigForm)
{
    BRWallet *w = BRWalletNew(NULL, 0, g_mpk84);
    BRAddress a = BRWalletReceiveAddress(w, 1);
    uint8_t pub[33], spk[64], ext[22] = { 0x00, 0x14 }, item[1 + 71 + 1 + 33];
    size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), a.s);
    UInt256 ph;
    BRBIP32PubKey(pub, sizeof(pub), g_mpk84, 0, 0);   // external 0: the key behind the address above
    memset(ext + 2, 0x5a, 20);
    memset(ph.u8, 0x99, sizeof(ph.u8));
    item[0] = 71; memset(&item[1], 0x30, 71); item[72] = 33; memcpy(&item[73], pub, 33);
    BRTransaction *t = BRTransactionNew();
    t->version = 2;
    if (scriptSigForm) BRTransactionAddInput(t, ph, 0, 0, kPlaceholder, 0, item, sizeof(item), kPlaceholder, 0, 0xffffffff);
    else BRTransactionAddInput(t, ph, 0, 0, kPlaceholder, 0, kPlaceholder, 0, item, sizeof(item), 0xffffffff);
    BRTransactionAddOutput(t, 1000, spk, spkLen);
    BRTransactionAddOutput(t, 1000000000000000ULL, ext, sizeof(ext));
    finalizeTxHash(t);
    BRTransaction *p = parsed(t);
    BRTransactionFree(t);
    ck(p != NULL, "the transaction parses");
    if (!p) { BRWalletFree(w); return; }
    UInt256 h = p->txHash;
    printf("NOTE: %s form; parsed input address \"%s\"\n", scriptSigForm ? "scriptSig" : "witness", p->inputs[0].address);
    ck(p->blockHeight == TX_UNCONFIRMED, "it is unconfirmed");
    int reg = BRWalletRegisterTransaction(w, p);
    ck(reg, "the wallet registers it (it pays the wallet)");
    if (!reg) { BRTransactionFree(p); BRWalletFree(w); return; }
    BRTxRow r;
    ck(rowFor(w, h, &r), "it has a row");
    showRow(scriptSigForm ? "unconfirmed, wallet key in a scriptSig" : "unconfirmed, wallet key in a witness", &r);
    ck(!r.ownsInput, "the input is not counted as the wallet's");
    ck(r.amount == 1000 && r.rule == BRTxRowAmountNet, "the row is a receive of +1000, not a send of 10,000,000 DGB (got %" PRId64 ")", r.amount);
    BRWalletFree(w);
}

static void unconfirmed_witness_key_not_owned(void) { unverifiable_key_unconfirmed(0); }
static void unconfirmed_scriptsig_key_not_owned(void) { unverifiable_key_unconfirmed(1); }

// A legacy (P2PKH) coin spent, the parent missing, confirmed: the parsed input's address comes from its
// scriptSig's key (BRAddressFromScriptSig).
static void missing_parent_p2pkh_send(void)
{
    BRWallet *w = BRWalletNew(NULL, 0, g_mpk84);
    BRAddress a = BRWalletReceiveAddress(w, 0);
    char dest[75]; externalAddress(dest, sizeof(dest), 0x62);
    BRTransaction *coin = coinTx(a.s, 500000000, 0xAB);
    BRTransaction *keepCopy = BRTransactionCopy(coin);
    printf("NOTE: legacy coin address %s\n", a.s);
    BRWalletRegisterTransaction(w, coin);
    BRTransaction *tx = signedSend(w, 110000000, dest);
    ck(tx != NULL, "wallet A signs a send of 1.1 DGB from a P2PKH coin");
    if (!tx) { BRTransactionFree(keepCopy); BRWalletFree(w); return; }
    ck(tx->inputs[0].sigLen > 0 && tx->inputs[0].witLen == 0, "the input is signed in its scriptSig, no witness");
    uint64_t change = paidExcept(tx, dest);
    BRWallet *b = walletWithout(tx, &keepCopy, NULL, 0, 0);
    ck(b != NULL, "wallet B holds the send, confirmed, and not its parent");
    if (b) {
        BRTxRow r;
        BRTransaction *bt = parsed(tx);
        ck(bt && strcmp(bt->inputs[0].address, a.s) == 0, "the parsed input's address is the coin's, from its scriptSig key (%s)",
           bt ? bt->inputs[0].address : "");
        ck(rowFor(b, tx->txHash, &r), "it has a row");
        showRow("P2PKH send, parent missing", &r);
        ck(r.ownsInput && r.sent == 0, "the input is the wallet's by its address; sent 0");
        ck(r.amount == -110000000, "the row is the payment, -110000000, not +%" PRIu64 " (got %" PRId64 ")", change, r.amount);
        ck(r.fee == UNKNOWN, "the fee is unknown (got %" PRIu64 ")", r.fee);
        if (bt) BRTransactionFree(bt);
        BRWalletFree(b);
    }
    BRTransactionFree(tx);
    BRTransactionFree(keepCopy);
    BRWalletFree(w);
}

// A send to the wallet itself, its parent missing, confirmed: it pays no one else, so it reads as
// received - sent = +received (the whole of what it pays), with its fee unknown.
static void self_send_missing_parent(void)
{
    BRWallet *w = BRWalletNew(NULL, 0, g_mpk84);
    BRAddress a = BRWalletReceiveAddress(w, 1);
    BRTransaction *coin = coinTx(a.s, 500000000, 0xAC);
    BRTransaction *keepCopy = BRTransactionCopy(coin);
    BRWalletRegisterTransaction(w, coin);
    BRAddress own = BRWalletReceiveAddress(w, 1);
    BRTransaction *tx = signedSend(w, 100000000, own.s);
    ck(tx != NULL, "wallet A signs a send to its own address");
    if (!tx) { BRTransactionFree(keepCopy); BRWalletFree(w); return; }
    uint64_t out = 0;
    for (size_t i = 0; i < tx->outCount; i++) out += tx->outputs[i].amount;
    BRWallet *b = walletWithout(tx, &keepCopy, NULL, 0, 0);
    ck(b != NULL, "wallet B holds the self-send, confirmed, and not its parent");
    if (b) {
        BRTxRow r;
        ck(rowFor(b, tx->txHash, &r), "it has a row");
        showRow("self-send, parent missing", &r);
        ck(r.ownsInput && r.toOthers == 0 && r.rule == BRTxRowAmountNet, "the wallet's input, nothing to others: rule net");
        ck(r.amount == (int64_t)out, "it reads as +%" PRIu64 ", everything it pays (got %" PRId64 ")", out, r.amount);
        ck(r.fee == UNKNOWN, "its fee is unknown (got %" PRIu64 ")", r.fee);
        BRWalletFree(b);
    }
    BRTransactionFree(tx);
    BRTransactionFree(keepCopy);
    BRWalletFree(w);
}

typedef struct { const char *name; void (*run)(void); } Case;
static const Case kCases[] = {
    { "rule_table", rule_table },
    { "amount_arithmetic", amount_arithmetic },
    { "fee_unknown_pure", fee_unknown_pure },
    { "receive_is_net_fee_unknown", receive_is_net_fee_unknown },
    { "send_payee_only", send_payee_only },
    { "send_everything", send_everything },
    { "self_send_is_fee", self_send_is_fee },
    { "self_sweep_foreign_key", self_sweep_foreign_key },
    { "missing_parent_p2wpkh_send", missing_parent_p2wpkh_send },
    { "partial_parent_send", partial_parent_send },
    { "digiasset_send_marker", digiasset_send_marker },
    { "digidollar_keeps_net", digidollar_keeps_net },
    { "taproot_send_payee", taproot_send_payee },
    { "taproot_keypath_missing_parent", taproot_keypath_missing_parent },
    { "unconfirmed_witness_key_not_owned", unconfirmed_witness_key_not_owned },
    { "unconfirmed_scriptsig_key_not_owned", unconfirmed_scriptsig_key_not_owned },
    { "missing_parent_p2pkh_send", missing_parent_p2pkh_send },
    { "self_send_missing_parent", self_send_missing_parent },
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
        printf("ARM: %s\n", TX_ROWS_UNFIXED ? "comparison (TX_ROWS_UNFIXED=1)" : "shipped");
        kCases[i].run();
        printf("RESULT %s %s\n", kCases[i].name, g_failed ? "fail" : "pass");
        return g_failed;
    }
    fprintf(stderr, "unknown case %s\n", argv[1]);
    return 2;
}
