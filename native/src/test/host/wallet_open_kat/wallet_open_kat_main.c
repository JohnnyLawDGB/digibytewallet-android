// Host KAT for BRWalletOpen.h -- the one recipe for opening a wallet from its seed.
//
// The defect it gates (digibytewallet-ios docs/port-plan-4.0.89.md, D2): iOS opened
// the wallet with BRWalletNew over the BIP84 key alone, while Android watched BIP84,
// the legacy breadwallet tree (m/0H, P2PKH and P2WPKH) and BIP86 Taproot. Same seed,
// different address set, different balance.
//
// The ORACLE is independent of BRWallet's chain bookkeeping: each tree's first
// external and internal address is derived straight from the seed with the BIP32
// private-key functions and encoded with the BRKey address functions, then the
// opened wallet is asked whether it watches that address. The BIP84 m/84'/20'/0'/0/0
// address is also pinned to a value fixed outside this code -- the one the iOS app
// asserts on launch and that matched Android on device (2026-09-05).
//
// Built twice by run.sh:
//   -DWALLET_OPEN_SINGLE_TREE_UNFIXED  the pre-fix iOS recipe; every RED case fails
//   (default)                          the shipped recipe; every case passes
//
// The saved_* cases open the wallet FROM saved records, as restoreWalletFromBytes does on
// every unlock: a record paid to each tree must be credited, a repeated hash, an unsigned
// record and one outside the money range must not be kept, and the caller must be able to
// free exactly the records the wallet did not keep (BRWalletTransactionForHash(w, h) != tx,
// the rule saved_transactions_release_after_build follows) with no double free or
// use-after-free -- both arms build with AddressSanitizer.

#include "BRWalletOpen.h"
#include "BRBIP39Mnemonic.h"
#include "BRKey.h"
#include "BRInt.h"
#include "BRTransaction.h"
#include "BRAddress.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static const char *PHRASE =
    "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
static const char *PINNED_BIP84_0_0 = "dgb1q9gmf0pv8jdymcly6lz6fl7lf6mhslsd72e2jq8";

static int g_failed = 0;

static void result(const char *name, int pass, const char *detail)
{
    if (! pass) {
        printf("FAIL: %s%s%s\n", name, detail ? ": " : "", detail ? detail : "");
        g_failed = 1;
    }
    printf("RESULT %s %s\n", name, pass ? "pass" : "fail");
}

// Both chains (external 0, internal 1), index 0, must be watched.
typedef enum { ENC_P2PKH, ENC_P2WPKH, ENC_P2TR } Encoding;
typedef enum { TREE_BIP84, TREE_LEGACY, TREE_BIP86 } Tree;

static void derive_at(char *addr, size_t len, const UInt512 *seed, Tree tree, uint32_t chain, uint32_t index,
                      Encoding enc)
{
    BRKey key;

    if (tree == TREE_BIP84) BRBIP32PrivKeyBIP84(&key, seed->u8, sizeof(*seed), chain, index);
    else if (tree == TREE_BIP86) BRBIP32PrivKeyBIP86(&key, seed->u8, sizeof(*seed), chain, index);
    else BRBIP32PrivKey(&key, seed->u8, sizeof(*seed), chain, index);   // m/0H, "DigiByte seed"

    addr[0] = '\0';
    if (enc == ENC_P2PKH) BRKeyAddress(&key, addr, len);
    else if (enc == ENC_P2WPKH) BRKeySegwitAddress(&key, addr, len, 0);
    else BRKeyTaprootAddress(&key, addr, len);
    BRKeyClean(&key);
}

static void derive(char *addr, size_t len, const UInt512 *seed, Tree tree, uint32_t chain, Encoding enc)
{
    derive_at(addr, len, seed, tree, chain, 0, enc);
}

static int watches_both_chains(BRWallet *w, const UInt512 *seed, Tree tree, Encoding enc, char *missing, size_t mlen)
{
    char addr[96];

    for (uint32_t chain = 0; chain < 2; chain++) {
        derive(addr, sizeof(addr), seed, tree, chain, enc);
        if (addr[0] == '\0' || ! BRWalletContainsAddress(w, addr)) {
            snprintf(missing, mlen, "chain %u index 0 (%s) not watched", chain, addr[0] ? addr : "<no address>");
            return 0;
        }
    }
    return 1;
}

// ---- saved records ------------------------------------------------------------------

// A record paying `amount` to `addr` from a prevout no wallet owns. A signed record goes
// through BRTransactionSerialize + BRTransactionParseSigned, the reader the bridge's loader
// uses, so its txHash is the real one (never a hand-set or zero hash, for which
// BRWalletTransactionForHash finds nothing). The unsigned one cannot take that path -- the
// signed reader rejects it -- so its hash is set by hand, nonzero and distinct.
static BRTransaction *saved_record(const char *addr, uint64_t amount, uint8_t prevTag, int sign)
{
    BRTransaction *tx = BRTransactionNew();
    UInt256 prev = UINT256_ZERO;
    uint8_t sig[72], script[128], buf[1024];
    size_t scriptLen, len;

    prev.u8[0] = prevTag;
    prev.u8[1] = 0x5a;
    memset(sig, 0x01, sizeof(sig));
    BRTransactionAddInput(tx, prev, 0, 1000000000ULL, NULL, 0, sign ? sig : NULL, sign ? sizeof(sig) : 0,
                          NULL, 0, TXIN_SEQUENCE);
    scriptLen = BRAddressScriptPubKey(script, sizeof(script), addr);
    BRTransactionAddOutput(tx, amount, script, scriptLen);
    tx->blockHeight = 100;
    tx->timestamp = 1600000000;

    if (! sign) {
        tx->txHash = UINT256_ZERO;
        tx->txHash.u8[0] = prevTag;
        tx->txHash.u8[5] = 0x77;
        return tx;
    }

    len = BRTransactionSerialize(tx, buf, sizeof(buf));
    BRTransactionFree(tx);
    if (len == 0 || len > sizeof(buf)) return NULL;
    tx = BRTransactionParseSigned(buf, len);
    if (tx) {
        tx->blockHeight = 100;
        tx->timestamp = 1600000000;
    }
    return tx;
}

static int kept(BRWallet *w, const BRTransaction *tx)
{
    return tx && BRWalletTransactionForHash(w, tx->txHash) == tx;
}

static void saved_records_case(const UInt512 *seed)
{
    enum { BIP84_PAID, LEGACY_PAID, P2TR_PAID, DUPLICATE, UNSIGNED, OUT_OF_RANGE, N_RECORDS };
    char bip84[96], legacy[96], p2tr[96], why[160];
    BRTransaction *recs[N_RECORDS];
    BRTransaction *orig[N_RECORDS];
    int built = 1;

    // external chain, index 3: inside every tree's pre-generated window, but not index 0
    derive_at(bip84, sizeof(bip84), seed, TREE_BIP84, 0, 3, ENC_P2WPKH);
    derive_at(legacy, sizeof(legacy), seed, TREE_LEGACY, 0, 3, ENC_P2PKH);
    derive_at(p2tr, sizeof(p2tr), seed, TREE_BIP86, 0, 3, ENC_P2TR);

    recs[BIP84_PAID]   = saved_record(bip84, 1000, 1, 1);
    recs[LEGACY_PAID]  = saved_record(legacy, 2000, 2, 1);
    recs[P2TR_PAID]    = saved_record(p2tr, 4000, 3, 1);
    recs[DUPLICATE]    = saved_record(legacy, 2000, 2, 1);   // same bytes, so same hash, as LEGACY_PAID
    recs[UNSIGNED]     = saved_record(bip84, 8000, 5, 0);
    recs[OUT_OF_RANGE] = saved_record(bip84, MAX_MONEY + 1ULL, 6, 1);
    for (int i = 0; i < N_RECORDS; i++) {
        orig[i] = recs[i];
        if (! recs[i]) built = 0;
    }
    if (built && ! UInt256Eq(recs[DUPLICATE]->txHash, recs[LEGACY_PAID]->txHash)) built = 0;
    if (built && BRTransactionIsSigned(recs[UNSIGNED])) built = 0;
    result("saved_records_built", built, built ? NULL : "a record did not build as intended");
    if (! built) {
        for (int i = 0; i < N_RECORDS; i++) if (recs[i]) BRTransactionFree(recs[i]);
        return;
    }

    BRWallet *w = BRWalletOpenWithKeys(recs, N_RECORDS, BRWalletMasterKeysFromSeed(seed->u8, sizeof(*seed)));
    if (! w) {
        result("saved_wallet_opened", 0, NULL);
        for (int i = 0; i < N_RECORDS; i++) BRTransactionFree(recs[i]);
        return;
    }
    // The array stays the caller's: the open must not have rewritten it.
    result("saved_array_untouched", memcmp(recs, orig, sizeof(recs)) == 0, NULL);

    // ---- GUARD: true of both recipes ------------------------------------------------
    result("saved_bip84_credited",
           kept(w, recs[BIP84_PAID]) && BRWalletAmountReceivedFromTx(w, recs[BIP84_PAID]) == 1000, NULL);
    result("saved_duplicate_not_kept", ! kept(w, recs[DUPLICATE]), NULL);
    result("saved_unsigned_not_kept", ! kept(w, recs[UNSIGNED]), NULL);
    result("saved_out_of_range_not_kept", ! kept(w, recs[OUT_OF_RANGE]), NULL);

    // ---- RED: a BIP84-only wallet credits neither --------------------------------------
    snprintf(why, sizeof(why), "kept=%d received=%llu", kept(w, recs[LEGACY_PAID]),
             kept(w, recs[LEGACY_PAID]) ? (unsigned long long)BRWalletAmountReceivedFromTx(w, recs[LEGACY_PAID]) : 0ULL);
    result("saved_legacy_p2pkh_credited",
           kept(w, recs[LEGACY_PAID]) && BRWalletAmountReceivedFromTx(w, recs[LEGACY_PAID]) == 2000, why);
    snprintf(why, sizeof(why), "kept=%d received=%llu", kept(w, recs[P2TR_PAID]),
             kept(w, recs[P2TR_PAID]) ? (unsigned long long)BRWalletAmountReceivedFromTx(w, recs[P2TR_PAID]) : 0ULL);
    result("saved_p2tr_credited",
           kept(w, recs[P2TR_PAID]) && BRWalletAmountReceivedFromTx(w, recs[P2TR_PAID]) == 4000, why);
    snprintf(why, sizeof(why), "balance=%llu", (unsigned long long)BRWalletBalance(w));
    result("saved_balance_all_trees", BRWalletBalance(w) == 7000, why);

    // ---- the caller's release: free each record the wallet does not hold as itself ----
    size_t freed = 0;
    for (int i = 0; i < N_RECORDS; i++) {
        if (! kept(w, recs[i])) { BRTransactionFree(recs[i]); freed++; }
        recs[i] = NULL;
    }
    // Every record the wallet kept must still be live: read each one (ASan reports a
    // use-after-free if the release above freed a kept record), then BRWalletFree frees them
    // (ASan reports a double free if the release freed one twice).
    size_t txCount = BRWalletTransactions(w, NULL, 0);
    BRTransaction **txs = calloc(txCount ? txCount : 1, sizeof(*txs));
    size_t live = 0;
    txCount = BRWalletTransactions(w, txs, txCount);
    for (size_t i = 0; i < txCount; i++) if (txs[i] && txs[i]->outCount == 1 && txs[i]->inCount == 1) live++;
    free(txs);
    BRWalletFree(w);
    snprintf(why, sizeof(why), "freed=%zu kept=%zu live=%zu", freed, txCount, live);
    result("saved_release_clean", freed + txCount == N_RECORDS && live == txCount && freed >= 3, why);
}

int main(void)
{
    UInt512 seed = UINT512_ZERO;
    char why[160] = "";
    char addr[96];

    BRBIP39DeriveKey(seed.u8, PHRASE, NULL);

    BRWallet *w = BRWalletOpenFromSeed(seed.u8, sizeof(seed), NULL, 0);
    if (! w) { printf("FAIL: wallet did not open\n"); return 1; }

    // ---- GUARD: true of both recipes ------------------------------------------------
    derive(addr, sizeof(addr), &seed, TREE_BIP84, 0, ENC_P2WPKH);
    result("bip84_vector", strcmp(addr, PINNED_BIP84_0_0) == 0, addr);

    why[0] = '\0';
    result("bip84_watched", watches_both_chains(w, &seed, TREE_BIP84, ENC_P2WPKH, why, sizeof(why)), why[0] ? why : NULL);

    BRAddress receive = BRWalletReceiveAddress(w, 1);
    result("receive_is_bip84", strcmp(receive.s, PINNED_BIP84_0_0) == 0, receive.s);

    // Opening from stored PUBLIC keys must watch exactly what opening from the seed does,
    // so a platform may open (and sync) without reading the seed.
    BRWallet *fromKeys = BRWalletOpenWithKeys(NULL, 0, BRWalletMasterKeysFromSeed(seed.u8, sizeof(seed)));
    result("keys_open_matches_seed_open",
           fromKeys && BRWalletAllAddrsCount(fromKeys) == BRWalletAllAddrsCount(w), NULL);

    // ---- RED: the pre-fix BIP84-only recipe watches none of these ---------------------
    why[0] = '\0';
    result("legacy_p2pkh_watched", watches_both_chains(w, &seed, TREE_LEGACY, ENC_P2PKH, why, sizeof(why)), why[0] ? why : NULL);
    why[0] = '\0';
    result("legacy_p2wpkh_watched", watches_both_chains(w, &seed, TREE_LEGACY, ENC_P2WPKH, why, sizeof(why)), why[0] ? why : NULL);
    why[0] = '\0';
    result("bip86_watched", watches_both_chains(w, &seed, TREE_BIP86, ENC_P2TR, why, sizeof(why)), why[0] ? why : NULL);
    result("trees_constant_names_all_three",
           BR_WALLET_OPEN_TREES == (BR_WALLET_TREE_BIP84 | BR_WALLET_TREE_LEGACY | BR_WALLET_TREE_BIP86) &&
           BR_WALLET_OPEN_TREES == 0x7u, NULL);

    if (fromKeys) BRWalletFree(fromKeys);
    BRWalletFree(w);

    saved_records_case(&seed);
    memset(&seed, 0, sizeof(seed));

    if (! g_failed) printf("ALL PASS\n");
    return g_failed ? 1 : 0;
}
