// Host KAT: TX-CKPT-CAP -- the wallet only writes saved-transactions checkpoints its own
// readers accept, and a checkpoint of a wallet past the old reader limit restores in full.
//
// THE INVARIANT
//   One limit, BR_SAVED_TRANSACTIONS_MAX_COUNT (BRSavedTransactions.h), bounds the writer
//   (BRWalletSerializeTransactions) and both readers (the core's BRSavedTransactionsDeserialize
//   and the bridge's deserialize_saved_transactions_guarded). Whatever the writer emits, both
//   readers load all of it; above the limit the writer emits nothing (returns 0) and the caller
//   keeps its previous checkpoint. A hostile count is still refused without allocating for it.
//   The app side (a checkpoint that loads fewer records than it declares forces a rebuild from
//   the birth floor) is held by the Kotlin test TransactionCheckpointPreflightTest.
//
// THE WALLET
//   Built as restoreWalletFromBytes builds it (BRWalletOpenWithKeys over a fixed mnemonic). Its
//   records are a spend chain: each record spends the previous one's output back to the
//   wallet's first segwit receive address, at a strictly increasing height. One coin is unspent
//   at any time, so the balance pass stays linear and a 100,000-record wallet opens in seconds.
//   The balance after a round trip must equal the balance before it.
//
// ARMS (presence of the macro selects the comparison arm; the shipped arm has no -D):
//   default               the shipped limit, on writer and readers alike.
//   -DTX_CKPT_CAP_UNFIXED the old shape: readers refuse above 10,000, the writer writes any count.
//
// CASES (one RESULT line each; run.sh says what each arm must show)
//   over_old_cap_round_trips   RED-THEN-GREEN. 10,001 records: both readers load every one, and
//                              the reopened wallet holds them all with the same balance.
//   writer_never_emits_refused RED-THEN-GREEN. MAX+1 records: the writer emits nothing, or both
//                              readers load everything it emitted. RED: it emits, they load 0.
//   at_limit_round_trips       GUARD. MAX records round-trip completely through both readers.
//   hostile_count_refused      GUARD. A 4-byte blob declaring MAX+1 or 0xFFFFFFFF records is
//                              refused by both readers: 0 loaded, no array, nothing read past it.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "BRWallet.h"
#include "BRWalletOpen.h"
#include "BRTransaction.h"
#include "BRBIP39Mnemonic.h"
#include "BRAddress.h"
#include "BRSavedTransactions.h"
#include "saved_transactions_deserialize.h"   /* the bridge's loader, as it ships */

static int g_failed = 0;

static void result(const char *name, int pass)
{
    if (! pass) { printf("FAIL: %s\n", name); g_failed = 1; }
    printf("RESULT %s %s\n", name, pass ? "pass" : "fail");
}

static const char *kMnemonic =
    "abandon abandon abandon abandon abandon abandon abandon abandon "
    "abandon abandon abandon about";

static BRWallet *openWallet(BRTransaction *saved[], size_t n)
{
    uint8_t seed[64];
    BRBIP39DeriveKey(seed, kMnemonic, NULL);
    BRWalletMasterKeys keys = BRWalletMasterKeysFromSeed(seed, sizeof(seed));
    BRWallet *w = BRWalletOpenWithKeys(n ? saved : NULL, n, keys);
    memset(seed, 0, sizeof(seed));
    return w;
}

// n records, a spend chain to the wallet's own segwit script; heights and times increasing.
static BRTransaction **chain(size_t n)
{
    BRWallet *w = openWallet(NULL, 0);
    BRAddress a = BRWalletReceiveAddress(w, 1);
    uint8_t spk[64], wit[106];
    size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), a.s);
    BRTransaction **txs = calloc(n, sizeof(*txs));
    UInt256 prev;
    uint32_t prevN = 7;

    BRWalletFree(w);
    memset(prev.u8, 0xa5, sizeof(prev.u8));                          // someone else's coin
    wit[0] = 71; memset(wit + 1, 0x11, 71); wit[1] = 0x30; wit[71] = 0x01;
    wit[72] = 33; wit[73] = 0x02; memset(wit + 74, 0x22, 32);
    for (size_t i = 0; i < n; i++) {
        BRTransaction *t = BRTransactionNew();
        uint8_t buf[512];
        size_t len;

        t->version = 2;
        BRTransactionAddInput(t, prev, prevN, 0, NULL, 0, NULL, 0, wit, 106, 0xffffffff);
        BRTransactionAddOutput(t, 100000000ULL, spk, spkLen);
        len = BRTransactionSerialize(t, buf, sizeof(buf));
        BRTransactionFree(t);
        txs[i] = BRTransactionParseSigned(buf, len);
        if (! txs[i]) { printf("FAIL: setup: record %zu did not parse\n", i); exit(2); }
        txs[i]->blockHeight = 1000000u + (uint32_t)i;
        txs[i]->timestamp = 1700000000u + (uint32_t)i;
        prev = txs[i]->txHash; prevN = 0;
    }
    return txs;
}

static void free_txs(BRTransaction **txs, size_t n)
{
    if (! txs) return;
    for (size_t i = 0; i < n; i++) if (txs[i]) BRTransactionFree(txs[i]);
    free(txs);
}

// Records the reader loaded that the wallet did not take are the caller's (as in the bridge).
static BRWallet *reopen(BRTransaction **saved, size_t n)
{
    BRWallet *w = openWallet(saved, n);
    for (size_t i = 0; i < n; i++) {
        if (! w || BRWalletTransactionForHash(w, saved[i]->txHash) != saved[i]) BRTransactionFree(saved[i]);
    }
    free(saved);
    return w;
}

// Writes the wallet's checkpoint. *len = 0 and NULL when the writer emits nothing.
static uint8_t *write_checkpoint(BRWallet *w, size_t *len)
{
    size_t need = BRWalletSerializeTransactions(w, NULL, 0);
    uint8_t *blob;

    *len = 0;
    if (need == 0) return NULL;
    blob = malloc(need);
    *len = BRWalletSerializeTransactions(w, blob, need);
    if (*len != need) { free(blob); *len = 0; return NULL; }
    return blob;
}

// Every record the writer emitted, loaded by both readers; the reopened wallet holds the same
// set and the same balance. Frees the wallet.
static int round_trips(BRWallet *w, size_t n, int *emitted)
{
    size_t len, nc, nb;
    uint64_t balance = BRWalletBalance(w);
    uint8_t *blob = write_checkpoint(w, &len);
    BRTransaction **core = NULL, **bridge = NULL;
    int ok;

    BRWalletFree(w);
    *emitted = (blob != NULL);
    if (! blob) return 0;
    nc = BRSavedTransactionsDeserialize(blob, len, &core);
    nb = deserialize_saved_transactions_guarded(blob, len, &bridge);
    ok = (UInt32GetLE(blob) == n) && nc == n && nb == n;
    printf("   writer declared %u, core reader loaded %zu, bridge reader loaded %zu (of %zu)\n",
           UInt32GetLE(blob), nc, nb, n);
    free_txs(core, nc);
    free(blob);
    if (! bridge) return 0;
    w = reopen(bridge, nb);
    ok = ok && w && BRWalletTransactions(w, NULL, 0) == n && BRWalletBalance(w) == balance;
    if (w) {
        printf("   reopened wallet holds %zu records, balance %llu (was %llu)\n",
               BRWalletTransactions(w, NULL, 0), (unsigned long long)BRWalletBalance(w),
               (unsigned long long)balance);
        BRWalletFree(w);
    }
    return ok;
}

static BRWallet *wallet_of(size_t n)
{
    BRTransaction **txs = chain(n);
    BRWallet *w = openWallet(txs, n);
    if (! w || BRWalletTransactions(w, NULL, 0) != n || BRWalletBalance(w) != 100000000ULL) {
        printf("FAIL: setup: the wallet did not take all %zu records\n", n);
        exit(2);
    }
    free(txs);   // the wallet owns every record now
    return w;
}

int main(void)
{
    int emitted;
    setvbuf(stdout, NULL, _IONBF, 0);
    printf("limit BR_SAVED_TRANSACTIONS_MAX_COUNT = %u\n", (unsigned)BR_SAVED_TRANSACTIONS_MAX_COUNT);

    printf("-- over_old_cap_round_trips: 10,001 records --\n");
    result("over_old_cap_round_trips", round_trips(wallet_of(10001), 10001, &emitted));

    printf("-- at_limit_round_trips: MAX records --\n");
    result("at_limit_round_trips",
           round_trips(wallet_of(BR_SAVED_TRANSACTIONS_MAX_COUNT), BR_SAVED_TRANSACTIONS_MAX_COUNT, &emitted));

    printf("-- writer_never_emits_refused: MAX+1 records --\n");
    {
        int ok = round_trips(wallet_of(BR_SAVED_TRANSACTIONS_MAX_COUNT + 1), BR_SAVED_TRANSACTIONS_MAX_COUNT + 1, &emitted);
        printf("   writer %s\n", emitted ? "emitted a checkpoint" : "emitted nothing (returned 0)");
        result("writer_never_emits_refused", emitted ? ok : 1);
    }

    printf("-- hostile_count_refused --\n");
    {
        const uint32_t counts[] = { BR_SAVED_TRANSACTIONS_MAX_COUNT + 1, 0xFFFFFFFFu };
        int ok = 1;
        for (size_t k = 0; k < 2; k++) {
            uint8_t *b = malloc(4);   // exact size: ASan sees any read past it
            BRTransaction **core = (BRTransaction **)1, **bridge = (BRTransaction **)1;
            UInt32SetLE(b, counts[k]);
            size_t nc = BRSavedTransactionsDeserialize(b, 4, &core);
            size_t nb = deserialize_saved_transactions_guarded(b, 4, &bridge);
            ok = ok && nc == 0 && nb == 0 && core == NULL && bridge == NULL;
            free_txs(core, nc); free_txs(bridge, nb);
            free(b);
        }
        result("hostile_count_refused", ok);
    }

    if (! g_failed) printf("ALL PASS\n");
    return g_failed ? 1 : 0;
}
