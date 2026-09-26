// Host KAT: a coin-generation output counts as spendable only after maturity.
//
// THE RULE. A coin-generation (coinbase) output paid to the wallet is spendable only once the chain
// tip is at least the maturity depth past the coin's OWN height: 100 blocks, or 8 for a mainnet
// coin below height 145,000; testnet uses 100 at every height (its mempool does). This is the
// reference wallet's convention: excluded while tip == H + M - 1, included at tip == H + M. Until
// then the value is carried by BRWalletImmatureBalance and is absent from BRWalletBalance,
// BRWalletUTXOs and any transaction the wallet builds. The tip reaches the wallet through
// BRWalletSetBlockHeight, which must be O(1) unless a held output crosses its maturity boundary
// or the tip falls; balanceChanged fires only when the spendable balance actually changed.
//
// FIXTURE. BRTransactionAddInput refuses a null prevout, so the coinbase is assembled as raw wire
// bytes (version, one input {32 x 0x00, index 0xffffffff, a BIP34-style height push, sequence
// 0xffffffff}, one output to a wallet P2WPKH address, lockTime 0) and read back through
// BRTransactionParse -- exactly the path a coinbase takes when a peer relays it.
//
// ARMS. Shipped: no -D. Comparison: -DCOINBASE_MATURITY_UNFIXED credits coin-generation outputs
// like any other output; every case marked RED below must fail in that arm. The runner also
// passes -DWALLET_KAT_COUNT_REBUILD, which exposes the rebuild counter the gate cases read.
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
#include "BRNetwork.h"
#include "BRInt.h"

extern unsigned long _walletKatRebuilds;   // BRWallet.c, -DWALLET_KAT_COUNT_REBUILD

static int g_fail = 0, g_caseFail = 0;
static void check(int c, const char *d) { printf(c ? "   PASS: %s\n" : "   FAIL: %s\n", d); if (! c) { g_fail++; g_caseFail++; } }
static void begin(const char *name) { printf("--- %s ---\n", name); g_caseFail = 0; }
static void end(const char *name) { printf("RESULT %s %s\n", name, g_caseFail ? "fail" : "pass"); }

static const char *kMnemonic =
    "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
static const char *kOtherMnemonic =
    "legal winner thank year wave sausage worth useful legal winner thank yellow";

// BRWalletRegisterTransaction asserts BRTransactionIsSigned(), which only checks that each input's
// signature/witness pointers are non-NULL. Synthetic plain receives use this placeholder.
static const uint8_t kPlaceholder[1] = {0};

static const uint64_t CB_AMOUNT = 1000000000ull;   // 10 DGB
static const uint64_t RX_AMOUNT = 250000000ull;    // 2.5 DGB

static BRWallet *mkWallet(const char *mnemonic) {
    uint8_t seed[64]; BRBIP39DeriveKey(seed, mnemonic, NULL);
    return BRWalletNew(NULL, 0, BRBIP32MasterPubKeyBIP84(seed, sizeof(seed)));
}

static size_t spkOf(BRWallet *w, uint8_t *spk, size_t cap) {
    BRAddress a = BRWalletReceiveAddress(w, 1);   // P2WPKH
    return BRAddressScriptPubKey(spk, cap, a.s);
}

static void finalizeTxHash(BRTransaction *tx) {
    uint8_t data[BRTransactionSerialize(tx, NULL, 0)];
    size_t len = BRTransactionSerialize(tx, data, sizeof(data));
    BRTransaction *t = BRTransactionParse(data, len);
    if (t) { tx->txHash = t->txHash; tx->wtxHash = t->wtxHash; BRTransactionFree(t); }
}

// A coin-generation tx paying `amount` to `spk`, stamped at `height`, built from wire bytes.
// `seed` varies the scriptSig so two coinbases at the same height have distinct hashes.
static BRTransaction *mkCoinbase(const uint8_t *spk, size_t spkLen, uint64_t amount, uint32_t height, uint8_t seed) {
    uint8_t buf[256]; size_t off = 0;
    UInt32SetLE(&buf[off], 1); off += 4;                       // version
    buf[off++] = 1;                                            // input count
    memset(&buf[off], 0, 32); off += 32;                       // null prevout hash
    UInt32SetLE(&buf[off], 0xffffffff); off += 4;              // null prevout index
    buf[off++] = 5;                                            // scriptSig length
    buf[off++] = 0x03;                                         // BIP34 height push
    buf[off++] = (uint8_t)(height & 0xff);
    buf[off++] = (uint8_t)((height >> 8) & 0xff);
    buf[off++] = (uint8_t)((height >> 16) & 0xff);
    buf[off++] = seed;                                         // extra-nonce byte
    UInt32SetLE(&buf[off], 0xffffffff); off += 4;              // sequence
    buf[off++] = 1;                                            // output count
    UInt64SetLE(&buf[off], amount); off += 8;
    buf[off++] = (uint8_t)spkLen;
    memcpy(&buf[off], spk, spkLen); off += spkLen;
    UInt32SetLE(&buf[off], 0); off += 4;                       // lockTime
    BRTransaction *tx = BRTransactionParse(buf, off);
    if (tx) { tx->blockHeight = height; tx->timestamp = 1700000000u; }
    return tx;
}

// An ordinary receive of `amount` to `spk` from a synthetic prevout, stamped at `height`.
// `prevIndex` lets a case pin that a non-null prevout with index 0xffffffff is NOT a coinbase.
static BRTransaction *mkReceive(const uint8_t *spk, size_t spkLen, uint64_t amount, uint32_t height,
                                uint8_t seed, uint32_t prevIndex) {
    BRTransaction *tx = BRTransactionNew();
    UInt256 prev; memset(prev.u8, seed, 32);
    tx->version = 1;
    BRTransactionAddInput(tx, prev, prevIndex, amount, spk, spkLen, kPlaceholder, 0, kPlaceholder, 0, 0xffffffff);
    BRTransactionAddOutput(tx, amount, spk, spkLen);
    tx->blockHeight = height; tx->timestamp = 1700000000u;
    finalizeTxHash(tx);
    return tx;
}

// ---- cases -------------------------------------------------------------------------------------

// GUARD: the fixture is what the wallet will see from the wire.
static void case_coinbase_shape(void) {
    begin("coinbase_shape");
    BRWallet *w = mkWallet(kMnemonic);
    uint8_t spk[64]; size_t spkLen = spkOf(w, spk, sizeof(spk));
    BRTransaction *cb = mkCoinbase(spk, spkLen, CB_AMOUNT, 700000, 0x01);
    check(cb != NULL, "coinbase parses from wire bytes");
    if (cb) {
        check(cb->inCount == 1, "one input");
        check(UInt256IsZero(cb->inputs[0].txHash), "null prevout hash");
        check(cb->inputs[0].index == 0xffffffff, "null prevout index");
        check(cb->inputs[0].signature != NULL && cb->inputs[0].sigLen == 5, "scriptSig kept as signature bytes");
        check(BRTransactionIsSigned(cb), "parsed coinbase passes the registration signed-check");
        check(cb->outCount == 1 && cb->outputs[0].amount == CB_AMOUNT, "one output of the expected amount");
        check(BRWalletRegisterTransaction(w, cb) != 0, "coinbase to a wallet address registers");
        check(BRWalletTransactions(w, NULL, 0) == 1, "it is in the wallet's history");
    }
    BRWalletFree(w);
    end("coinbase_shape");
}

// RED: 100-block regime -- excluded at H+99, included at H+100.
static void case_mature_100(void) {
    begin("mature_100");
    const uint32_t H = 700000;
    BRWallet *w = mkWallet(kMnemonic);
    uint8_t spk[64]; size_t spkLen = spkOf(w, spk, sizeof(spk));
    BRTransaction *cb = mkCoinbase(spk, spkLen, CB_AMOUNT, H, 0x02);
    BRWalletRegisterTransaction(w, cb);
    check(BRWalletBalance(w) == 0, "not spendable before any tip is known");
    check(BRWalletImmatureBalance(w) == CB_AMOUNT, "carried as immature before any tip is known");
    BRWalletSetBlockHeight(w, H + 99);
    check(BRWalletBalance(w) == 0, "tip H+99: balance excludes the coin");
    check(BRWalletUTXOs(w, NULL, 0) == 0, "tip H+99: no spendable UTXO");
    check(BRWalletImmatureBalance(w) == CB_AMOUNT, "tip H+99: immature balance == amount");
    check(BRWalletTotalReceived(w) == 0, "tip H+99: totalReceived excludes the coin");
    BRWalletSetBlockHeight(w, H + 100);
    check(BRWalletBalance(w) == CB_AMOUNT, "tip H+100: balance includes the coin");
    check(BRWalletUTXOs(w, NULL, 0) == 1, "tip H+100: one spendable UTXO");
    check(BRWalletImmatureBalance(w) == 0, "tip H+100: immature balance == 0");
    check(BRWalletTotalReceived(w) == CB_AMOUNT, "tip H+100: totalReceived includes the coin");
    BRWalletFree(w);
    end("mature_100");
}

// RED: 8-block regime for a mainnet coin below 145,000 -- excluded at H+7, included at H+8.
static void case_mature_8_early_mainnet(void) {
    begin("mature_8_early_mainnet");
    const uint32_t H = 100000;
    BRWallet *w = mkWallet(kMnemonic);
    uint8_t spk[64]; size_t spkLen = spkOf(w, spk, sizeof(spk));
    BRWalletRegisterTransaction(w, mkCoinbase(spk, spkLen, CB_AMOUNT, H, 0x03));
    BRWalletSetBlockHeight(w, H + 7);
    check(BRWalletBalance(w) == 0 && BRWalletImmatureBalance(w) == CB_AMOUNT, "tip H+7: excluded");
    BRWalletSetBlockHeight(w, H + 8);
    check(BRWalletBalance(w) == CB_AMOUNT && BRWalletImmatureBalance(w) == 0, "tip H+8: included");
    BRWalletFree(w);
    end("mature_8_early_mainnet");
}

// RED: testnet applies 100 at every height, including below 145,000.
static void case_testnet_100_always(void) {
    begin("testnet_100_always");
    const uint32_t H = 100000;
    BRSetNetwork(1);
    BRWallet *w = mkWallet(kMnemonic);   // created AFTER the switch so its addresses are testnet
    uint8_t spk[64]; size_t spkLen = spkOf(w, spk, sizeof(spk));
    check(BRWalletRegisterTransaction(w, mkCoinbase(spk, spkLen, CB_AMOUNT, H, 0x04)) != 0, "testnet coinbase registers");
    BRWalletSetBlockHeight(w, H + 7);
    check(BRWalletBalance(w) == 0, "testnet tip H+7: excluded");
    BRWalletSetBlockHeight(w, H + 8);
    check(BRWalletBalance(w) == 0, "testnet tip H+8: still excluded (no 8-block regime on testnet)");
    BRWalletSetBlockHeight(w, H + 99);
    check(BRWalletBalance(w) == 0, "testnet tip H+99: excluded");
    BRWalletSetBlockHeight(w, H + 100);
    check(BRWalletBalance(w) == CB_AMOUNT, "testnet tip H+100: included");
    BRWalletFree(w);
    BRSetNetwork(0);
    end("testnet_100_always");
}

// RED: a send funded only by an immature coin cannot be built; it can once the coin matures.
static void case_immature_only_send_null(void) {
    begin("immature_only_send_null");
    const uint32_t H = 700000;
    BRWallet *w = mkWallet(kMnemonic), *other = mkWallet(kOtherMnemonic);
    uint8_t spk[64]; size_t spkLen = spkOf(w, spk, sizeof(spk));
    BRAddress foreign = BRWalletReceiveAddress(other, 1);
    BRWalletRegisterTransaction(w, mkCoinbase(spk, spkLen, CB_AMOUNT, H, 0x05));
    BRWalletSetBlockHeight(w, H + 99);
    BRTransaction *t = BRWalletCreateTransaction(w, CB_AMOUNT / 2, foreign.s);
    check(t == NULL, "tip H+99: no transaction can be built from an immature coin");
    if (t) BRTransactionFree(t);
    BRWalletSetBlockHeight(w, H + 100);
    t = BRWalletCreateTransaction(w, CB_AMOUNT / 2, foreign.s);
    check(t != NULL, "tip H+100: the same send builds");
    if (t) BRTransactionFree(t);
    BRWalletFree(w);
    BRWalletFree(other);
    end("immature_only_send_null");
}

// RED: the tip push rebuilds the balance only when it can change it.
static void case_rebuild_gate(void) {
    begin("rebuild_gate");
    const uint32_t H = 700000;
    unsigned long before;
    uint32_t h;

    // A wallet holding only an ordinary receive: a thousand pushes, no rebuild.
    BRWallet *a = mkWallet(kMnemonic);
    uint8_t spk[64]; size_t spkLen = spkOf(a, spk, sizeof(spk));
    BRWalletRegisterTransaction(a, mkReceive(spk, spkLen, RX_AMOUNT, H, 0x61, 0));
    before = _walletKatRebuilds;
    for (h = H + 1; h <= H + 1000; h++) BRWalletSetBlockHeight(a, h);
    check(_walletKatRebuilds == before, "no coin-generation output held: 1000 pushes, 0 rebuilds");
    check(BRWalletBalance(a) == RX_AMOUNT, "ordinary receive stays credited throughout");
    BRWalletFree(a);

    // A wallet holding an immature coinbase: pushes short of maturity do not rebuild, the
    // maturing push rebuilds exactly once, pushes past it do not rebuild.
    BRWallet *b = mkWallet(kMnemonic);
    BRWalletRegisterTransaction(b, mkCoinbase(spk, spkLen, CB_AMOUNT, H, 0x06));
    before = _walletKatRebuilds;
    for (h = H + 1; h <= H + 99; h++) BRWalletSetBlockHeight(b, h);
    check(_walletKatRebuilds == before, "immature coin held: pushes H+1..H+99, 0 rebuilds");
    check(BRWalletBalance(b) == 0, "still excluded at H+99");
    BRWalletSetBlockHeight(b, H + 100);
    check(_walletKatRebuilds == before + 1, "push H+100: exactly one rebuild");
    check(BRWalletBalance(b) == CB_AMOUNT, "credited at H+100");
    before = _walletKatRebuilds;
    for (h = H + 101; h <= H + 200; h++) BRWalletSetBlockHeight(b, h);
    check(_walletKatRebuilds == before, "pushes H+101..H+200: 0 rebuilds");
    BRWalletSetBlockHeight(b, H + 200);
    check(_walletKatRebuilds == before, "re-pushing the same tip: 0 rebuilds");
    BRWalletFree(b);
    end("rebuild_gate");
}

// RED: a lower tip re-hides the coin (and is the one downward case that must rebuild).
static void case_lower_tip_rehides(void) {
    begin("lower_tip_rehides");
    const uint32_t H = 700000;
    unsigned long before;
    BRWallet *w = mkWallet(kMnemonic);
    uint8_t spk[64]; size_t spkLen = spkOf(w, spk, sizeof(spk));
    BRWalletRegisterTransaction(w, mkCoinbase(spk, spkLen, CB_AMOUNT, H, 0x07));
    BRWalletSetBlockHeight(w, H + 100);
    check(BRWalletBalance(w) == CB_AMOUNT, "credited at H+100");
    before = _walletKatRebuilds;
    BRWalletSetBlockHeight(w, H + 50);
    check(_walletKatRebuilds == before + 1, "lower tip H+50: one rebuild");
    check(BRWalletBalance(w) == 0 && BRWalletUTXOs(w, NULL, 0) == 0, "lower tip H+50: excluded again");
    check(BRWalletImmatureBalance(w) == CB_AMOUNT, "lower tip H+50: carried as immature again");
    before = _walletKatRebuilds;
    BRWalletSetBlockHeight(w, H + 100);
    check(_walletKatRebuilds == before + 1, "back to H+100: one rebuild");
    check(BRWalletBalance(w) == CB_AMOUNT, "back to H+100: credited");
    BRWalletFree(w);
    end("lower_tip_rehides");
}

// RED: un-confirmed by a reorg, re-stamped at a new height, matured against the new height.
static void case_reorg_restamp(void) {
    begin("reorg_restamp");
    const uint32_t H = 700000;
    unsigned long before;
    BRWallet *w = mkWallet(kMnemonic);
    uint8_t spk[64]; size_t spkLen = spkOf(w, spk, sizeof(spk));
    BRTransaction *cb = mkCoinbase(spk, spkLen, CB_AMOUNT, H, 0x08);
    UInt256 hash = cb->txHash;
    BRWalletRegisterTransaction(w, cb);
    BRWalletSetBlockHeight(w, H + 100);
    check(BRWalletBalance(w) == CB_AMOUNT, "credited at H+100");

    BRWalletSetTxUnconfirmedAfter(w, H - 1);
    BRTransaction *t = BRWalletTransactionForHash(w, hash);
    check(t && t->blockHeight == TX_UNCONFIRMED, "reorg below H: coinbase is unconfirmed");
    check(BRWalletBalance(w) == 0, "unconfirmed coinbase: excluded");
    check(BRWalletImmatureBalance(w) == CB_AMOUNT, "unconfirmed coinbase: carried as immature");
    check(BRWalletTransactions(w, NULL, 0) == 1, "history keeps the transaction");

    before = _walletKatRebuilds;
    BRWalletUpdateTransactions(w, &hash, 1, H + 5, 1700000100u);
    check(_walletKatRebuilds == before + 1, "re-stamp of a coinbase rebuilds");
    check(BRWalletBalance(w) == 0, "re-stamped at H+5 with tip H+5: still excluded");
    BRWalletSetBlockHeight(w, H + 104);
    check(BRWalletBalance(w) == 0, "tip H+104: excluded (maturity keyed on the NEW height)");
    BRWalletSetBlockHeight(w, H + 105);
    check(BRWalletBalance(w) == CB_AMOUNT, "tip H+105: credited");
    BRWalletFree(w);
    end("reorg_restamp");
}

// RED: ordinary receives are untouched; a non-null prevout with index 0xffffffff is not a coinbase.
static void case_plain_receive_unaffected(void) {
    begin("plain_receive_unaffected");
    const uint32_t H = 700000;
    BRWallet *w = mkWallet(kMnemonic);
    uint8_t spk[64]; size_t spkLen = spkOf(w, spk, sizeof(spk));
    BRWalletRegisterTransaction(w, mkReceive(spk, spkLen, RX_AMOUNT, H, 0x71, 0xffffffff));
    check(BRWalletBalance(w) == RX_AMOUNT, "ordinary receive credited with no tip known");
    check(BRWalletImmatureBalance(w) == 0, "ordinary receive is never immature");
    BRWalletRegisterTransaction(w, mkCoinbase(spk, spkLen, CB_AMOUNT, H, 0x09));
    check(BRWalletBalance(w) == RX_AMOUNT, "adding an immature coinbase leaves the balance unchanged");
    check(BRWalletTransactions(w, NULL, 0) == 2, "history counts both transactions");
    check(BRWalletTotalReceived(w) == RX_AMOUNT, "totalReceived counts only the spendable receive");
    BRWalletSetBlockHeight(w, H + 100);
    check(BRWalletBalance(w) == RX_AMOUNT + CB_AMOUNT, "tip H+100: both credited");
    check(BRWalletTotalReceived(w) == RX_AMOUNT + CB_AMOUNT, "tip H+100: totalReceived counts both");
    BRWalletFree(w);
    end("plain_receive_unaffected");
}

// RED: a restored wallet evaluates maturity against the highest confirmed height it holds.
static void case_restore_seeds_tip(void) {
    begin("restore_seeds_tip");
    const uint32_t H = 700000;
    uint8_t seed[64]; BRBIP39DeriveKey(seed, kMnemonic, NULL);
    BRMasterPubKey mpk = BRBIP32MasterPubKeyBIP84(seed, sizeof(seed));
    BRWallet *probe = mkWallet(kMnemonic);
    uint8_t spk[64]; size_t spkLen = spkOf(probe, spk, sizeof(spk));
    BRWalletFree(probe);

    // coinbase at H plus a receive confirmed at H+100: mature on load, no push needed
    BRTransaction *set1[2] = { mkCoinbase(spk, spkLen, CB_AMOUNT, H, 0x0a),
                               mkReceive(spk, spkLen, RX_AMOUNT, H + 100, 0x81, 0) };
    BRWallet *w1 = BRWalletNew(set1, 2, mpk);
    check(w1 != NULL, "wallet restored from a coinbase and a later receive");
    if (w1) {
        check(BRWalletBalance(w1) == CB_AMOUNT + RX_AMOUNT, "loaded evidence reaches H+100: coinbase spendable on load");
        check(BRWalletImmatureBalance(w1) == 0, "nothing immature on load");
        BRWalletFree(w1);
    }

    // coinbase at H plus a receive confirmed at H+99: still immature on load
    BRTransaction *set2[2] = { mkCoinbase(spk, spkLen, CB_AMOUNT, H, 0x0b),
                               mkReceive(spk, spkLen, RX_AMOUNT, H + 99, 0x82, 0) };
    BRWallet *w2 = BRWalletNew(set2, 2, mpk);
    check(w2 != NULL, "wallet restored from a coinbase and a receive one block short");
    if (w2) {
        check(BRWalletBalance(w2) == RX_AMOUNT, "loaded evidence reaches H+99: coinbase excluded on load");
        check(BRWalletImmatureBalance(w2) == CB_AMOUNT, "carried as immature on load");
        BRWalletSetBlockHeight(w2, H + 100);
        check(BRWalletBalance(w2) == RX_AMOUNT + CB_AMOUNT, "first push to H+100 credits it");
        BRWalletFree(w2);
    }
    end("restore_seeds_tip");
}

// RED: balanceChanged fires once per actual change of the spendable balance, never per push.
typedef struct { unsigned fires; uint64_t last; } CbCtx;
static void onBalance(void *info, uint64_t balance) { CbCtx *c = info; c->fires++; c->last = balance; }

static void case_balance_changed_once(void) {
    begin("balance_changed_once");
    const uint32_t H = 700000;
    uint32_t h;
    CbCtx ctx = {0, 0};
    BRWallet *w = mkWallet(kMnemonic);
    uint8_t spk[64]; size_t spkLen = spkOf(w, spk, sizeof(spk));
    BRWalletRegisterTransaction(w, mkCoinbase(spk, spkLen, CB_AMOUNT, H, 0x0c));
    BRWalletSetCallbacks(w, &ctx, onBalance, NULL, NULL, NULL);   // after registration: it fires on add
    for (h = H + 1; h <= H + 99; h++) BRWalletSetBlockHeight(w, h);
    check(ctx.fires == 0, "pushes H+1..H+99: no balanceChanged");
    BRWalletSetBlockHeight(w, H + 100);
    check(ctx.fires == 1 && ctx.last == CB_AMOUNT, "push H+100: exactly one balanceChanged, with the new balance");
    for (h = H + 101; h <= H + 200; h++) BRWalletSetBlockHeight(w, h);
    check(ctx.fires == 1, "pushes H+101..H+200: no further balanceChanged");
    BRWalletSetBlockHeight(w, H + 50);
    check(ctx.fires == 2 && ctx.last == 0, "lower tip H+50: one balanceChanged, balance 0");
    BRWalletSetBlockHeight(w, H + 100);
    check(ctx.fires == 3 && ctx.last == CB_AMOUNT, "back to H+100: one balanceChanged");
    BRWalletSetBlockHeight(w, H + 100);
    check(ctx.fires == 3, "same tip again: no balanceChanged");
    BRWalletFree(w);
    end("balance_changed_once");
}

// RED: stamping an own unconfirmed tx with TX_UNCONFIRMED (the peer manager's "mark verified /
// unverified" shape) must not move the wallet's tip, so a held immature coin stays excluded.
static void case_unconfirmed_stamp_keeps_tip(void) {
    begin("unconfirmed_stamp_keeps_tip");
    const uint32_t H = 700000;
    unsigned long before;
    BRWallet *w = mkWallet(kMnemonic);
    uint8_t spk[64]; size_t spkLen = spkOf(w, spk, sizeof(spk));
    BRWalletRegisterTransaction(w, mkCoinbase(spk, spkLen, CB_AMOUNT, H, 0x0d));
    BRWalletSetBlockHeight(w, H + 50);
    check(BRWalletBalance(w) == 0 && BRWalletImmatureBalance(w) == CB_AMOUNT, "tip H+50: coin excluded");

    BRTransaction *own = mkReceive(spk, spkLen, RX_AMOUNT, TX_UNCONFIRMED, 0x91, 0);
    UInt256 ownHash = own->txHash;
    BRWalletRegisterTransaction(w, own);
    check(BRWalletBalance(w) == RX_AMOUNT, "own unconfirmed tx credited at 0-conf, coin still excluded");

    BRWalletUpdateTransactions(w, &ownHash, 1, TX_UNCONFIRMED, 1700000500u);   // "verified" stamp: height unchanged, timestamp set
    check(BRWalletTransactionForHash(w, ownHash)->timestamp == 1700000500u, "TX_UNCONFIRMED stamp sets the timestamp");
    check(BRWalletBalance(w) == RX_AMOUNT, "after TX_UNCONFIRMED stamp: balance still excludes the coin");
    check(BRWalletImmatureBalance(w) == CB_AMOUNT, "after TX_UNCONFIRMED stamp: coin still carried as immature");
    before = _walletKatRebuilds;
    BRWalletSetBlockHeight(w, H + 51);
    check(_walletKatRebuilds == before, "push H+51 after the stamp: no rebuild (the tip was not disturbed)");
    check(BRWalletBalance(w) == RX_AMOUNT, "tip H+51: coin still excluded");

    BRWalletUpdateTransactions(w, &ownHash, 1, TX_UNCONFIRMED, 0);   // "unverified" stamp: height unchanged, timestamp 0
    check(BRWalletBalance(w) == RX_AMOUNT && BRWalletImmatureBalance(w) == CB_AMOUNT, "after TX_UNCONFIRMED/0 stamp: coin still excluded");
    before = _walletKatRebuilds;
    BRWalletSetBlockHeight(w, H + 52);
    check(_walletKatRebuilds == before, "push H+52 after the second stamp: no rebuild");

    BRWalletSetBlockHeight(w, H + 100);
    check(BRWalletBalance(w) == RX_AMOUNT + CB_AMOUNT, "tip H+100: coin credited on the real tip");
    BRWalletFree(w);
    end("unconfirmed_stamp_keeps_tip");
}

int main(void) {
    BRSetNetwork(0);
    case_coinbase_shape();
    case_mature_100();
    case_mature_8_early_mainnet();
    case_testnet_100_always();
    case_immature_only_send_null();
    case_rebuild_gate();
    case_lower_tip_rehides();
    case_reorg_restamp();
    case_plain_receive_unaffected();
    case_restore_seeds_tip();
    case_balance_changed_once();
    case_unconfirmed_stamp_keeps_tip();
    printf(g_fail ? "\n%d CHECK(S) FAILED\n" : "\nALL PASS\n", g_fail);
    return g_fail ? 1 : 0;
}
